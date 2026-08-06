package com.carddemo.common.observability;

/**
 * Renders a value that came from outside the process safe to write into a log line.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>A log line is a record with a shape: one line per event, each carrying key-equals-value fields that
 * an operator greps and a collector parses. Every value interpolated into such a line comes from
 * somewhere, and some of those somewheres are outside this system's control -- a container command
 * argument an operator typed, an environment override an orchestrator supplied, a field read from a
 * record produced elsewhere. A value carrying a line feed does not appear inside its line; it ENDS the
 * line and begins another one that the collector reads as a separate event. Whoever supplied the value
 * therefore chooses what that second event says, including its level, its event name and its
 * correlation identity.</p>
 *
 * <p>Refactoring Rationale: this is the concern named CWE-117, and it is closed by neutralising the
 * characters that carry it rather than by escaping or by rejecting the value. Neutralising is chosen
 * because the value's purpose is to be READ by an operator diagnosing a failure: rejecting it would
 * withhold the diagnostic at the moment it is wanted, and escaping it into a printable form would keep
 * the operator's own line intact while making the value harder to read than the plain text a
 * well-formed value already is. Replacing each control character with a single space keeps the line
 * one line, keeps the value the same length, and leaves every legitimate value byte-identical.</p>
 *
 * <h2>Assumptions and boundaries</h2>
 *
 * <p>Assumptions: the whole of the ISO control range is neutralised, not the two line terminators
 * alone. A carriage return alone rewrites a line in a terminal, an escape byte alone begins a terminal
 * control sequence, and a NUL truncates a line in some collectors, so a rule covering only the line
 * feed and the carriage return would leave the same class of defect reachable through the neighbouring
 * characters. The range is a closed, well-defined set, which is what makes covering all of it cheaper
 * to reason about than enumerating the ones that matter today.</p>
 *
 * <p>Trade-offs: the ordinary case does no work and allocates nothing -- the scan returns its argument
 * itself when no control character is present, which is every value this system produces for itself --
 * so applying this at a site that logs on every run costs one pass over a short string.</p>
 *
 * <p>Assumptions: this class does NOT mask anything. A value can be free of control characters and
 * still be a primary account number, and the two concerns are deliberately separate classes:
 * {@code com.carddemo.common.security.CardNumberMasker} decides what a value may disclose, and this
 * class decides only whether it can forge a record. A site handling a value that is both untrusted and
 * sensitive applies both.</p>
 */
public final class LogSafeText {

    /**
     * The character every control character is replaced by.
     *
     * <p>Assumptions: a single space, so the replacement preserves the value's length and its position
     * within the line. A removal would shift every following character, which changes a fixed-width
     * value's meaning; a multi-character escape would change the length and could push a bounded field
     * past its declared width.</p>
     */
    public static final char REPLACEMENT = ' ';

    /**
     * Prevents instantiation of this utility holder.
     *
     * <p>Assumptions: the single operation is a stateless function of its argument, so an instance would
     * carry nothing. A private constructor states that, where an implicit public one would invite a
     * caller to inject this class as a collaborator and then to mock it.</p>
     *
     * @throws AssertionError always, so that reflective instantiation fails as loudly as direct
     *     instantiation is prevented
     */
    private LogSafeText() {
        throw new AssertionError("LogSafeText is a utility holder and is never instantiated");
    }

    /**
     * Replaces every ISO control character in a value with a single space.
     *
     * @param value the value about to be written into a log line or a diagnostic stream, which may be
     *     {@code null}
     * @return {@code null} when {@code value} is {@code null}; {@code value} itself when it carries no
     *     control character, so a verbatim guarantee stays literal for the ordinary case; otherwise a
     *     copy of the same length with each control character replaced by {@link #REPLACEMENT}
     */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }

        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                // WHY : Refactoring Rationale: the builder is created on first match rather than up
                //       front, and the second loop resumes from the index the first stopped at rather
                //       than from zero. Both keep the clean case free of any copy, which matters because
                //       the sites applying this run on every invocation and the value is almost always
                //       clean; re-scanning from zero would also re-test characters already known good.
                StringBuilder sanitized = new StringBuilder(value);
                for (int scan = index; scan < sanitized.length(); scan++) {
                    if (Character.isISOControl(sanitized.charAt(scan))) {
                        sanitized.setCharAt(scan, REPLACEMENT);
                    }
                }
                return sanitized.toString();
            }
        }
        return value;
    }
}
