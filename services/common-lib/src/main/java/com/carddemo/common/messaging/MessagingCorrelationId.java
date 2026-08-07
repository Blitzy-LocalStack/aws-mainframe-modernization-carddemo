package com.carddemo.common.messaging;

/**
 * The canonical encoding a correlation identity must satisfy to travel as QUEUE metadata, and the
 * separate rendering that identity may take in a log line.
 *
 * <h2>Why this is not the servlet rule</h2>
 *
 * <p>Refactoring Rationale: the queue-driven consumers previously judged an inbound correlation
 * attribute with {@code com.carddemo.common.web.CorrelationIdFilter#isConformingCorrelationId}, which is
 * the SERVLET rule. Sharing one predicate across two transports was right in intent and wrong in effect,
 * because the two transports do not carry the same values. The servlet rule bounds an identity at 24
 * characters drawn from letters, digits and a short separator set, which is appropriate for a value this
 * system MINTS and publishes in a response header. A queue correlation identity is not minted here: the
 * baseline's own field is {@code MQBYTE24}, twenty-four arbitrary BYTES, and a requester renders those
 * bytes as it likes -- 48 hexadecimal characters, 32 base64 characters, a hyphenated UUID. Every one of
 * those is a legitimate identity and every one of them fails the servlet rule, on length or on alphabet
 * or on both. The consumer consequently dropped valid identities and answered with a reply carrying no
 * correlation attribute at all, which leaves a requester unable to pair the answer it is waiting on.</p>
 *
 * <p>Assumptions: the messaging rule is therefore WIDER in what it admits and NARROWER in what it lets
 * reach a log. It admits any non-empty run of at most {@link #MAX_LENGTH} printable US-ASCII characters,
 * which covers every rendering above; and because a value that broad must never be written verbatim into
 * a log line, the log rendering is produced separately by {@link #logSafe(String)}. Those are two
 * different jobs and they were previously conflated into one predicate that did neither well.</p>
 *
 * <h2>What the two bounds are, and why</h2>
 *
 * <p>Assumptions: the character domain is the printable US-ASCII range and excludes the space. Excluding
 * the control characters is what closes log injection -- a line terminator inside an identity is what
 * lets an attacker forge a log record -- and it is also required by the queue service, whose string
 * attribute values may not carry arbitrary control characters. The space is excluded because the value is
 * an identity rather than prose, and admitting it would let leading or trailing whitespace make two
 * renderings of one identity compare unequal. Characters above the ASCII range are excluded because the
 * attribute crosses a system boundary where the encoding is not negotiated, so a multi-byte value could
 * arrive re-encoded and stop matching the request it correlates.</p>
 *
 * <p>Assumptions: {@link #MAX_LENGTH} is 64 and the number is taken from the STORE rather than invented.
 * The authorization outbox persists the echoed identity in a {@code VARCHAR(64)} column, so a longer value
 * could be accepted at intake and would then fail on insert -- after the decision had been made and
 * inside the transaction that records it. Fixing the bound at the column width means an over-long
 * identity is refused at the edge, where the refusal is about the message rather than about the database.
 * The width is comfortably above every rendering the baseline's 24-byte field can produce: 48 hexadecimal
 * characters is the longest of them.</p>
 *
 * <h2>What a caller does with a refusal</h2>
 *
 * <p>Assumptions: this class answers only whether a value conforms; it does not decide the consequence,
 * because the consequence belongs to the consumer. The authorization consumer REFUSES the message so the
 * queue redelivers it and the dead-letter queue receives it, which is deliberate and is a change from the
 * earlier drop-and-proceed behaviour: with a rule this wide, a value that fails it carries a control
 * character or is absurdly long, and neither is a formatting preference a legitimate requester expresses.
 * Silently discarding it left the requester holding a correlation value that never came back and left no
 * evidence of why.</p>
 *
 * <p>Assumptions: an ABSENT attribute is not a malformed one. The baseline sets its correlation field to
 * a no-match constant before the read at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} L396,
 * so a requester that supplies none is ordinary and its reply simply carries none either. Absence is
 * reported by {@link #isPresent(String)} and is never routed through {@link #isCanonical(String)}.</p>
 */
public final class MessagingCorrelationId {

    /**
     * The greatest number of characters a queue correlation identity may carry.
     *
     * <p>Assumptions: 64 is the width of the column the authorization outbox echoes the identity into,
     * so the intake bound and the storage bound are one number rather than two that can disagree.</p>
     */
    public static final int MAX_LENGTH = 64;

    /**
     * The lowest character code admitted: the first printable US-ASCII character after the space.
     */
    private static final char LOWEST_ADMITTED = 0x21;

    /**
     * The highest character code admitted: the last printable US-ASCII character.
     */
    private static final char HIGHEST_ADMITTED = 0x7E;

    /**
     * The character an inadmissible character is rendered as in a log line.
     *
     * <p>Assumptions: a fixed replacement rather than removal, so that the LENGTH of a log rendering
     * still matches the length of the value it renders. Removing characters would make two different
     * identities render identically, which is the opposite of what a correlation identity is for.</p>
     */
    private static final char LOG_REPLACEMENT = '.';

    /**
     * The punctuation a log rendering may carry beyond letters and digits.
     *
     * <p>Assumptions: this set is deliberately much NARROWER than {@link #isCanonical(String)} admits,
     * and the asymmetry is the whole design. A reply must carry the requester's own bytes, so the
     * canonical rule admits every printable character; a log record must not be able to carry anything
     * that could terminate a field or a line, so this set admits only separators that cannot. The four
     * chosen are the ones the identity renderings this system actually meets are built from -- a
     * hyphenated identifier, a base64url token, a dotted namespace, a colon-separated pair -- so an
     * ordinary identity reaches a log unchanged while a quotation mark, a comma, a backslash or an
     * equals sign, each of which can close or split a structured log field, is replaced.</p>
     *
     * <p>Alternatives Considered: reusing {@code com.carddemo.common.observability.LogSafeText}, which
     * neutralises the ISO control range and is the right rule for free text. Rejected for THIS value
     * because it admits the quotation mark, and a correlation identity is attacker-chosen metadata that
     * lands in a structured log field -- so a value of {@code a","level":"ERROR} would pass it and forge
     * a record. That class remains correct where it is used; this is a narrower rule for a narrower
     * value, and the difference is recorded here so the two are not consolidated by mistake.</p>
     */
    private static final String LOG_SAFE_PUNCTUATION = "-_.:";

    /**
     * Prevents instantiation of this rule holder.
     *
     * <p>Assumptions: the type carries no state, so an instance would offer nothing an unqualified static
     * call does not. The constructor is private and throws rather than merely being private, so that
     * reflective construction is refused as well.</p>
     *
     * @throws AssertionError always, because no instance of this type is meaningful
     */
    private MessagingCorrelationId() {
        throw new AssertionError("MessagingCorrelationId states a rule and holds no state");
    }

    /**
     * Reports whether a correlation attribute was supplied at all.
     *
     * @param candidate the attribute value, or {@code null} when the message carried none
     * @return {@code true} when a non-blank value was supplied
     */
    public static boolean isPresent(String candidate) {
        return candidate != null && !candidate.isBlank();
    }

    /**
     * Reports whether a supplied correlation identity may be echoed onto a reply unaltered.
     *
     * <p>Assumptions: the check is on the value as supplied, with no trimming and no normalisation,
     * because the requester is waiting on the exact opaque value it sent. Normalising it here would make
     * the echoed identity differ from the sent one, which is the single thing this attribute must not
     * do.</p>
     *
     * @param candidate the attribute value; may be {@code null}
     * @return {@code true} when the value is present, no longer than {@link #MAX_LENGTH}, and composed
     *     entirely of printable US-ASCII characters other than the space
     */
    public static boolean isCanonical(String candidate) {
        if (!isPresent(candidate) || candidate.length() > MAX_LENGTH) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (character < LOWEST_ADMITTED || character > HIGHEST_ADMITTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders a correlation identity for a log line, replacing anything a log record must not carry.
     *
     * <p>Assumptions: this is applied to the value on its way to a LOG and never to the value on its way
     * to a reply. The two renderings are deliberately allowed to differ: the reply must carry the
     * requester's own bytes, while a log record must carry nothing that could terminate a line or embed
     * a delimiter. Using one rendering for both would either corrupt the correlation or leave the log
     * forgeable, and the pair of methods here is what keeps that choice from being made by accident.</p>
     *
     * <p>Assumptions: an over-long value is TRUNCATED here rather than refused, because a log rendering
     * has no contract to satisfy and a caller may want to log a value it is about to refuse. Refusing
     * inside a logging helper would make diagnosing the refusal depend on the refusal succeeding.</p>
     *
     * @param candidate the attribute value; may be {@code null}
     * @return the rendering to log: the empty string when nothing was supplied, otherwise the value with
     *     every character outside letters, digits and {@link #LOG_SAFE_PUNCTUATION} replaced by
     *     {@link #LOG_REPLACEMENT}, at the same length as the value up to {@link #MAX_LENGTH}
     */
    public static String logSafe(String candidate) {
        if (candidate == null) {
            return "";
        }
        int limit = Math.min(candidate.length(), MAX_LENGTH);
        StringBuilder rendered = new StringBuilder(limit);
        for (int index = 0; index < limit; index++) {
            char character = candidate.charAt(index);
            boolean safe = (character >= '0' && character <= '9')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || LOG_SAFE_PUNCTUATION.indexOf(character) >= 0;
            rendered.append(safe ? character : LOG_REPLACEMENT);
        }
        return rendered.toString();
    }
}
