package com.carddemo.common.messaging;

import com.carddemo.common.security.CardNumberMasker;

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
 * <h2>Why the log rendering also has to judge the value's SHAPE</h2>
 *
 * <p>Refactoring Rationale: {@link #logSafe(String)} filtered CHARACTERS and nothing else, and a
 * character filter cannot see this exposure at all because the characters involved are unobjectionable.
 * A requester chooses its own correlation attribute, so it can set that attribute to a primary account
 * number; sixteen digits pass every character test ever written, and the value was published to the
 * mapped diagnostic context and therefore onto every log line the message produced. The servlet transport
 * already closed the same hole -- {@code com.carddemo.common.web.CorrelationIdFilter} refuses a
 * bare-numeric identity outright -- and the messaging transport carried the rule's character half without
 * its shape half. This class now applies both, so the two transports agree on WHAT is dangerous while
 * differing on the CONSEQUENCE, which is the point below.</p>
 *
 * <p>Assumptions: the two transports must differ in consequence and cannot share one. The servlet filter
 * REFUSES an identifier-shaped identity and mints its own, which it may do because it publishes that
 * identity in a response header it owns. This transport may not: the requester is waiting on the exact
 * bytes it sent, and replacing them would leave it unable to pair the answer -- so the value is echoed
 * unaltered by {@link #isCanonical(String)} and redacted only on its way to a log by
 * {@link #logSafe(String)}. Separating the echo from the log rendering is what makes both correct at
 * once; a single rendering would have to be either forgeable or uncorrelatable.</p>
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
     * <p>Refactoring Rationale: the asterisk was ADDED to this set when {@link #logSafe(String)} began
     * masking embedded card numbers through {@code com.carddemo.common.security.CardNumberMasker}, whose
     * mask character it is. Without it the filter would have replaced the mask with
     * {@link #LOG_REPLACEMENT} and produced a run of dots -- still redacted, but no longer recognisable
     * as a deliberate masking, which is the difference between an operator reading "this was masked" and
     * an operator wondering what corrupted the value. Admitting it costs nothing: an asterisk can neither
     * terminate a line nor close a field in any format this value is written into, so a requester that
     * sends one of its own is simply echoed it back in the log.</p>
     *
     * <p>Alternatives Considered: reusing {@code com.carddemo.common.observability.LogSafeText}, which
     * neutralises the ISO control range and is the right rule for free text. Rejected for THIS value
     * because it admits the quotation mark, and a correlation identity is attacker-chosen metadata that
     * lands in a structured log field -- so a value of {@code a","level":"ERROR} would pass it and forge
     * a record. That class remains correct where it is used; this is a narrower rule for a narrower
     * value, and the difference is recorded here so the two are not consolidated by mistake.</p>
     */
    private static final String LOG_SAFE_PUNCTUATION = "-_.:*";

    /**
     * The fewest digits a bare-numeric correlation attribute must carry to be treated as an identifier.
     *
     * <p>Assumptions: nine, and it is the narrowest identifier width the baseline declares rather than a
     * round number. {@code CUST-ID PIC 9(09)} at line 5 of {@code app/cpy/CVCUS01Y.cpy} is nine digits,
     * {@code ACCT-ID PIC 9(11)} at line 5 of {@code app/cpy/CVACT01Y.cpy} is eleven, and
     * {@code CARD-NUM PIC X(16)} at line 5 of {@code app/cpy/CVACT02Y.cpy} and
     * {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy} are sixteen. Taking the
     * narrowest means no identifier this system stores can reach a log through this attribute, and
     * anything shorter cannot be one of them, so the bound is exactly as wide as it needs to be.</p>
     *
     * <p>Assumptions: this is deliberately LOWER than the thirteen
     * {@code com.carddemo.common.web.CorrelationIdFilter} uses, and the two are not in conflict. That
     * filter's bound answers "is this a card number", because its consequence is to REFUSE a caller's
     * chosen identity and it must refuse as narrow a class as possible. This bound answers "could this be
     * any identifier at all", because its consequence is only to redact a LOG rendering while the value
     * is still echoed in full -- so a false positive costs a log line's precision and nothing else, and
     * the bound can afford to be conservative where the filter's cannot.</p>
     *
     * <p>Trade-offs: a legitimate correlation identity that is a bare run of nine or more digits is
     * logged as {@link #NUMERIC_IDENTITY_MARKER} rather than as itself, so an operator cannot grep the
     * log for that value. Accepted, and the cost is smaller than it looks: this system's own minted
     * identities open with the two-letter prefix {@code CD} and so can never be bare-numeric, and a
     * requester rendering the baseline's twenty-four-byte correlation field as hexadecimal, base64 or a
     * hyphenated UUID produces letters with overwhelming probability. The value also remains greppable at
     * its source, because the reply still carries it unaltered.</p>
     */
    public static final int NUMERIC_IDENTITY_MIN_DIGITS = 9;

    /**
     * The fixed rendering a bare-numeric correlation attribute is logged as.
     *
     * <p>Assumptions: a fixed marker rather than a partially masked value, because there is nothing here
     * worth keeping. A masked rendering earns its keep when the surrounding text is diagnostic -- a
     * request path with one number in it -- and this value is nothing BUT the number, so a mask would
     * carry only its length and its tail digits at the cost of implying that the rest is recoverable.</p>
     *
     * <p>Assumptions: the marker draws only on characters {@link #LOG_SAFE_PUNCTUATION} and the letter
     * range already admit, so it cannot itself be the thing that breaks a structured log field. The digit
     * COUNT is appended by {@link #logSafe(String)} rather than being part of this constant, so an
     * operator can tell an eleven-digit smuggle from a sixteen-digit one without either value being
     * present.</p>
     */
    public static final String NUMERIC_IDENTITY_MARKER = "numeric-identity.";

    /**
     * The one character {@link #LOG_SAFE_PUNCTUATION} admits that is not an identity separator.
     *
     * <p>Assumptions: named rather than written inline so the exclusion in
     * {@link #bareNumericDigitCount(String)} reads as the decision it is. It is
     * {@code com.carddemo.common.security.CardNumberMasker}'s mask character, so a value already
     * carrying it has been masked or was sent looking masked, and treating it as a separator would let
     * {@code ************1111} be reported as a four-digit bare number.</p>
     */
    private static final char MASK_CHARACTER_EXCLUDED_FROM_SEPARATORS = CardNumberMasker.MASK_CHARACTER;

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
     * Renders a correlation identity for a log line, redacting anything a log record must not carry.
     *
     * <p>Assumptions: this is applied to the value on its way to a LOG and never to the value on its way
     * to a reply. The two renderings are deliberately allowed to differ: the reply must carry the
     * requester's own bytes, while a log record must carry nothing that could terminate a line, embed a
     * delimiter, or disclose an identifier. Using one rendering for both would either corrupt the
     * correlation or leave the log unsafe, and the pair of methods here is what keeps that choice from
     * being made by accident.</p>
     *
     * <p>Assumptions: three redactions are applied and their ORDER is load-bearing, so it is stated
     * rather than left to be read off the statements. First, a value that is nothing but digits and
     * separators is replaced WHOLESALE, because such a value carries no diagnostic content to preserve
     * and could be any of the four identifiers the baseline declares. Second, a value that carries other
     * characters as well has its embedded card-number-shaped runs masked, because there the surrounding
     * characters ARE the diagnostic content -- {@code req-4111111111111111} must keep its prefix to be
     * useful and must lose its number to be safe, and the whole-value test cannot see it at all since one
     * letter disqualifies the shape. Third, whatever survives is filtered to the log-safe alphabet.
     * Reversing the first two would let a bare sixteen-digit value be masked to
     * {@code ************1111} and then reported as a masked card number, disclosing four digits the
     * wholesale replacement withholds.</p>
     *
     * <p>Assumptions: an over-long value is TRUNCATED here rather than refused, because a log rendering
     * has no contract to satisfy and a caller may want to log a value it is about to refuse. Refusing
     * inside a logging helper would make diagnosing the refusal depend on the refusal succeeding. The
     * truncation is applied LAST so that it cannot split a card-number-shaped run and defeat the mask.</p>
     *
     * @param candidate the attribute value; may be {@code null}
     * @return the rendering to log: the empty string when nothing was supplied;
     *     {@link #NUMERIC_IDENTITY_MARKER} followed by the digit count when the value is a bare run of
     *     at least {@link #NUMERIC_IDENTITY_MIN_DIGITS} digits, optionally separated; otherwise the value
     *     with every embedded card number masked and every character outside letters, digits and
     *     {@link #LOG_SAFE_PUNCTUATION} replaced by {@link #LOG_REPLACEMENT}, at no more than
     *     {@link #MAX_LENGTH} characters
     */
    public static String logSafe(String candidate) {
        if (candidate == null) {
            return "";
        }

        int bareDigits = bareNumericDigitCount(candidate);
        if (bareDigits >= NUMERIC_IDENTITY_MIN_DIGITS) {
            return NUMERIC_IDENTITY_MARKER + bareDigits;
        }

        // WHY : Assumptions: the shared masker is reused rather than a digit-run scan being written here,
        //       because it already carries the two rules this value needs -- a contiguous run and a
        //       uniformly separated one -- together with the argument for the sixteen-digit threshold. A
        //       second scan in this class would be a second expression of one rule, free to be relaxed
        //       while the other still claimed the guarantee, and the separated case is exactly the one an
        //       independently written scan omits.
        String masked = CardNumberMasker.maskEmbeddedCardNumbers(candidate);

        int limit = Math.min(masked.length(), MAX_LENGTH);
        StringBuilder rendered = new StringBuilder(limit);
        for (int index = 0; index < limit; index++) {
            char character = masked.charAt(index);
            boolean safe = (character >= '0' && character <= '9')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || LOG_SAFE_PUNCTUATION.indexOf(character) >= 0;
            rendered.append(safe ? character : LOG_REPLACEMENT);
        }
        return rendered.toString();
    }

    /**
     * Counts the digits of a value made up only of digits and identity separators.
     *
     * <p>Assumptions: separators are counted as belonging to the number rather than disqualifying it,
     * matching {@code com.carddemo.common.web.CorrelationIdFilter}'s own shape test, because
     * {@code 4111-1111-1111-1111} is a card number written the way a human writes one. A value carrying
     * ANY other character is not a written identifier in any convention, so it is reported as not
     * bare-numeric immediately and reaches the masking step instead, where its diagnostic content
     * survives.</p>
     *
     * <p>Assumptions: the separator set is the one {@link #LOG_SAFE_PUNCTUATION} already admits minus the
     * asterisk, which is a mask character rather than a separator. Reading the set from that constant
     * rather than declaring a second one keeps the two from drifting, and the asterisk is excluded here
     * explicitly so that an already-masked value is never mistaken for a bare number.</p>
     *
     * @param candidate the value to measure; must not be {@code null}
     * @return the number of digits when the value consists only of digits and separators, and zero
     *     otherwise, so a caller can compare against a minimum without a second predicate
     */
    private static int bareNumericDigitCount(String candidate) {
        int digits = 0;
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (character >= '0' && character <= '9') {
                digits++;
                continue;
            }
            if (character == MASK_CHARACTER_EXCLUDED_FROM_SEPARATORS
                    || LOG_SAFE_PUNCTUATION.indexOf(character) < 0) {
                return 0;
            }
        }
        return digits;
    }
}
