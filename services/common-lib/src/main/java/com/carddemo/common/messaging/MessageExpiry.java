package com.carddemo.common.messaging;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * The message-expiry attribute every queue consumer in this system honours, and the one place it is parsed.
 *
 * <h2>Purpose</h2>
 * <p>The baseline sets a message expiry on its reply, and the target queue service has no per-message
 * time-to-live at all. The resolution recorded in {@code docs/adr/ADR-004-messaging.md} is to carry the expiry
 * instant as a message attribute and have each consumer drop and log a message that has already passed it.
 * That makes the expiry a consumer obligation rather than a broker guarantee, and this class is the shared
 * implementation of it.</p>
 *
 * <h2>Why this is shared rather than per-consumer</h2>
 * <p>Refactoring Rationale: the parsing below was first written inside the pending-authorization consumer,
 * which was for a time the only consumer that had one. Two further consumers now carry the same obligation --
 * the account-inquiry and date-conversion flows -- and three copies of a rule about when to discard a message
 * would be three chances to disagree about it. Disagreement here is particularly unpleasant to diagnose: two
 * consumers would honour the same requester's expiry differently, and the symptom would be an intermittently
 * unanswered request rather than an error anywhere.</p>
 *
 * <p>Assumptions: the two decisions this class embodies are stated once and inherited by every caller.
 * <b>An absent attribute means no expiry</b>, because a requester that sets none has not asked to be timed
 * out. <b>An unparseable attribute is treated as absent rather than as already passed</b>: treating it as
 * passed would let one malformed attribute silently discard every request from a requester whose formatting
 * differs, and the request itself may be entirely valid. Callers log the malformed value's length -- never its
 * content, which came off the wire and cannot be assumed free of cardholder data.</p>
 *
 * <p>Trade-offs: four textual forms are accepted -- an epoch-millisecond count, an instant with a trailing
 * {@code Z}, a date and time with a numeric offset, and a zone-less local date and time. Accepting fewer would
 * make interoperability depend on a formatting choice nobody negotiated, since the requester is not necessarily
 * this codebase. The cost is that a value which is none of them is indistinguishable from a typo in one of
 * them, which is why the caller logs its length.</p>
 *
 * <p>Refactoring Rationale: the offset-bearing forms were NOT accepted when this rule lived inside the
 * pending-authorization consumer, which tried only the epoch and zone-less forms. That was a real
 * interoperability gap rather than a theoretical one, and it was found by asserting the rule against
 * {@code Instant.toString()} -- the single most likely rendering a Java producer would emit, and the one form
 * whose trailing {@code Z} the zone-less parser rejects outright. A producer emitting it would have had its
 * expiry silently ignored on every message while believing it had set one.</p>
 *
 * <p>This class holds no state and cannot be instantiated. It accepts no construction parameter, yields no
 * instance and raises nothing from its own initialisation.</p>
 */
public final class MessageExpiry {

    /**
     * The message-attribute name carrying the expiry instant.
     *
     * <p>Assumptions: one name across every flow. A per-flow name would let a producer address one consumer's
     * expiry contract while silently missing another's.</p>
     */
    public static final String HEADER_EXPIRES_AT = "expiresAt";

    /**
     * The number of milliseconds in one second, used to split an epoch-millisecond value.
     */
    private static final long MILLIS_PER_SECOND = 1_000L;

    /**
     * The number of nanoseconds in one millisecond, used to widen the remainder of that split.
     */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /**
     * Prevents instantiation of this utility holder.
     *
     * @throws AssertionError always, so an accidental reflective construction fails loudly rather than
     *     yielding a useless instance
     */
    private MessageExpiry() {
        throw new AssertionError("MessageExpiry is a utility holder and must not be instantiated");
    }

    /**
     * Parses an expiry attribute, accepting either an epoch-millisecond value or an ISO local date and time.
     *
     * <p>Assumptions: a value that CARRIES a zone or offset is honoured as written, and a value that carries
     * none is read as coordinated universal time. Defaulting a zone-less value to the receiving container's
     * local zone instead would make the same message expire at different moments in two deployments, and would
     * pass a test run on a machine whose zone happened to be universal time.</p>
     *
     * <p>Assumptions: the forms are tried in order of decreasing specificity, and the order matters. An
     * all-digit value is an epoch count and must be recognised first, because it is not a date at all. The
     * instant form is tried before the offset form and both before the zone-less form, so a value that carries
     * zone information is never parsed by a reader that would discard it.</p>
     *
     * @param raw the attribute value as it arrived, which may be {@code null} or blank
     * @return the expiry instant, or {@code null} when the value is absent, blank, or in none of the accepted
     *     forms
     */
    public static Instant parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            if (isAllDigits(trimmed)) {
                long millis = Long.parseLong(trimmed);
                return Instant.ofEpochSecond(Math.floorDiv(millis, MILLIS_PER_SECOND),
                        Math.floorMod(millis, MILLIS_PER_SECOND) * NANOS_PER_MILLI);
            }
            return parseTemporal(trimmed);
        } catch (ArithmeticException | NumberFormatException | DateTimeParseException notAnExpiry) {
            // WHY : Assumptions: the offending value is deliberately NOT included in any message or log
            //   emitted from here. It came off the wire and this class cannot know it carries no cardholder
            //   data. The exception is swallowed rather than rethrown because an attribute treated as
            //   optional must not be able to fail a message; the caller reports the value's LENGTH.
            return null;
        }
    }

    /**
     * Reports whether a message carrying this attribute value has already expired.
     *
     * <p>Assumptions: the comparison is inclusive of the expiry instant itself -- a message whose expiry
     * equals the current instant is expired. That matches how a broker-side time-to-live behaves at its
     * boundary, and choosing the exclusive reading would make the last millisecond of validity depend on
     * clock resolution.</p>
     *
     * @param raw the attribute value as it arrived, which may be {@code null} or blank
     * @param now the instant to judge against; must not be {@code null}
     * @return {@code true} only when the value parses to an instant at or before {@code now}
     */
    public static boolean isExpired(String raw, Instant now) {
        Instant expiresAt = parse(raw);
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * Reports whether an attribute value is present but in neither accepted form.
     *
     * <p>Assumptions: this exists so a caller can act on a malformed attribute without re-implementing the
     * parse or having to distinguish absent from malformed by calling {@link #parse(String)} twice. Both
     * cases yield a {@code null} instant from {@link #isExpired(String, Instant)}, so this predicate is what
     * separates them: an absent attribute is ordinary, while a malformed one means a producer believes it set
     * an expiry that is not being read.</p>
     *
     * <p>Trade-offs: what a caller DOES with a malformed attribute is deliberately left to the caller and is
     * not decided here, because the right answer differs by flow. The authorization consumer refuses such a
     * message, since accepting it would let a producer defeat expiry enforcement on a decision that moves an
     * account's counters; the two inquiry consumers treat it as absent, since their answers are read-only.
     * Encoding either posture in this helper would silently impose it on the other.</p>
     *
     * @param raw the attribute value as it arrived, which may be {@code null} or blank
     * @return {@code true} when the value is non-blank and does not parse
     */
    public static boolean isMalformed(String raw) {
        return raw != null && !raw.trim().isEmpty() && parse(raw) == null;
    }

    /**
     * Parses one of the three textual date and time forms.
     *
     * @param trimmed the trimmed attribute value; must not be {@code null} or empty
     * @return the parsed instant, never {@code null}
     * @throws DateTimeParseException if the value is in none of the three forms, which the caller treats as an
     *     absent expiry
     */
    private static Instant parseTemporal(String trimmed) {
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException notAnInstant) {
            // WHY : Assumptions: the two fallbacks are attempted rather than the value being refused here,
            //   and neither exception is logged. Instant.parse accepts only the trailing-Z rendering, so a
            //   value carrying a numeric offset or no zone at all reaches this point legitimately. The
            //   offending text is never included in any message for the reason recorded on the caller.
            try {
                return OffsetDateTime.parse(trimmed).toInstant();
            } catch (DateTimeParseException notAnOffsetDateTime) {
                return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);
            }
        }
    }

    /**
     * Reports whether every character of a value is an ASCII digit.
     *
     * <p>Assumptions: {@code Character::isDigit} is deliberately not used. It accepts digits from every
     * Unicode decimal script, and {@code Long.parseLong} accepts those too -- so a value written in
     * non-ASCII digits would parse to a number here while being rejected or read differently by a producer
     * or a broker that assumes ASCII. Restricting the test to ASCII keeps the accepted set to the one form a
     * producer can have intended.</p>
     *
     * @param value the value to test; must not be {@code null}
     * @return {@code true} when the value is non-empty and every character is in {@code '0'} to {@code '9'}
     */
    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
