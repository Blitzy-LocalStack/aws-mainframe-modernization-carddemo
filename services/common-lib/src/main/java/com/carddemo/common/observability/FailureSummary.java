package com.carddemo.common.observability;

import com.carddemo.common.security.CardNumberMasker;
import java.sql.SQLException;

/**
 * Renders the one sentence a failure carries about itself, safe to write into a log line.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>{@link ThrowableDigest} names every type in a failure's cause chain and the frame each was raised
 * at, and deliberately carries no message text at all. That is the right default and it is not always
 * sufficient: a transport fault is frequently reported as a single generic type whose whole diagnosis
 * lives in its message. {@code SdkClientException} is the case that forced this class into existence --
 * a failed send renders as one type name with no cause beneath it, so a digest of it says only that a
 * client-side fault occurred, and an operator reading that line cannot tell a refused connection from a
 * name that does not resolve from a certificate that does not verify. The three want different
 * responses, and the message is the only place the difference appears.</p>
 *
 * <p>Refactoring Rationale: this is a SECOND class rather than a method on {@code ThrowableDigest},
 * because that class's charter states that withholding message text is what it is for and its tests
 * hold it to that. A message-carrying method there would make one type answer two opposite questions,
 * and a caller reaching for the safe rendering could no longer tell which it had. Two classes make the
 * choice explicit at every call site: a site that must not disclose text calls the digest, and a site
 * that needs the diagnosis calls this and accepts the bounds below.</p>
 *
 * <h2>What is done to the text before it is returned</h2>
 *
 * <p>Three transformations, in this order, and the order matters. The text is SANITISED first, through
 * {@link LogSafeText}, so a message carrying a line terminator cannot end the caller's log line and
 * forge a second record -- that has to happen before anything else, because every later step could
 * otherwise be reasoning about a value that is really two lines. It is then MASKED, through
 * {@link CardNumberMasker#maskEmbeddedCardNumbers(String)}, because a database driver's own messages
 * quote the values that broke a constraint and on this schema those values include a primary account
 * number: the unique-violation form of a driver message is exactly "duplicate key value violates unique
 * constraint ... Detail: Key (card_num)=(4111111111111111) already exists". It is TRUNCATED last, to
 * {@link #MAX_LENGTH}, because a bound applied before masking could cut a card-number-shaped run in half
 * and leave a partial number the masker no longer recognises.</p>
 *
 * <p>Assumptions: the message taken is the DEEPEST one in the chain, not the outermost. A wrapping
 * framework restates its cause's message inside its own -- "Error creating bean with name X: Factory
 * method threw exception with message: Y" -- so the outermost message is the longest and the least
 * specific, and a bound applied to it discards Y, which is the only part that says what happened. The
 * walk is bounded by the same depth cap and the same self-cause guard the digest uses, so a cyclic chain
 * terminates here for the same reason it terminates there.</p>
 *
 * <h2>What this is not</h2>
 *
 * <p>Assumptions: this class makes a message SAFER to log; it does not make it safe to PERSIST
 * unbounded or to return to a caller over an API. A message is still text this process did not author,
 * so it belongs in a diagnostic stream an operator reads and never in a response body -- the shared
 * error advice keeps that boundary and this class does not move it. Sites that persist the value into a
 * column are relying on the bound below and on the column's own truncation, both of which are stated
 * rather than assumed.</p>
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A class declaration accepts no parameter,
 * returns no value and raises nothing, so this descriptor carries no parameter, return or exception
 * at-clause; each method below carries its own.</p>
 */
public final class FailureSummary {

    /**
     * The greatest number of characters a rendered summary may occupy.
     *
     * <p>Assumptions: two hundred, which is chosen against the two consumers of the value rather than as
     * a round number. A log line carrying several structured fields stays readable when one free-text
     * field cannot dominate it, and the narrowest column this value is persisted into --
     * {@code auth_reply_outbox.last_error} -- is two hundred and fifty-six, so a summary plus a short
     * field name fits without the column's own truncation ever being reached. A shorter bound was
     * rejected because a driver's message routinely spends its first eighty characters on preamble
     * before it says anything specific.</p>
     */
    public static final int MAX_LENGTH = 200;

    /** The text rendered for a {@code null} failure, matching {@link ThrowableDigest#NONE}. */
    public static final String NONE = ThrowableDigest.NONE;

    /**
     * The text rendered when no link in the chain carries a message.
     *
     * <p>Assumptions: a placeholder rather than an empty value, for the reason the digest gives for its
     * own: an empty value in a structured line cannot be told apart from a field the emitter forgot to
     * populate, and a failure that genuinely carries no message is worth stating so a reader stops
     * looking for one.</p>
     */
    public static final String NO_MESSAGE = "(no-message)";

    /** Appended when the message was longer than {@link #MAX_LENGTH}. */
    public static final String TRUNCATION_MARKER = "...";

    /**
     * The shortest run of digits {@link #redactedOf(Throwable)} replaces.
     *
     * <p>⚠️ Assumptions: three, and the figure is chosen against what this migration's records actually
     * hold rather than as a round number. The narrowest value any of them treats as protected is three
     * digits -- a card verification value is {@code PIC 9(03)} -- while the numbers that make a driver's
     * message diagnostic are one and two digits: a declared precision, a declared scale, a power of ten.
     * Three is therefore the highest threshold that redacts every protected shape, and the lowest that
     * keeps the geometry a reader needs. A threshold of sixteen, which is what
     * {@link CardNumberMasker#maskEmbeddedCardNumbers(String)} applies, was rejected here for the reason
     * that method's own charter records: it deliberately leaves shorter runs alone, and the shorter runs
     * are exactly the account identifier at eleven digits and the transaction identifier's nine.</p>
     */
    public static final int MIN_REDACTED_DIGITS = 3;

    /** The character each digit of a redacted run is replaced with. */
    public static final char REDACTION_CHARACTER = '#';

    /**
     * Rendered in place of a message whose composer cannot be established.
     *
     * <p>⚠️ Assumptions: a token rather than an empty field, for the reason every other placeholder in
     * this class is one -- an empty structured field cannot be told apart from one the emitter failed to
     * populate. It says something different from {@link #NO_MESSAGE}, and the difference matters to a
     * reader: that token says the failure carried no message, this one says it carried a message that was
     * not shown.</p>
     */
    public static final String WITHHELD = "(withheld)";

    /**
     * Rendered by {@link #sqlStateOrAbsent(Throwable)} when the chain carries no database state code.
     *
     * <p>⚠️ Assumptions: the value matches the token the outbox publisher already rendered for an absent
     * broker-assigned value, so a reader who has learned one structured absence has learned them all and
     * a log query written against one field's absence matches the other's.</p>
     */
    public static final String NO_SQL_STATE = "(absent)";

    /**
     * Prevents instantiation of this utility holder.
     *
     * <p>Assumptions: every operation here is a stateless function of its argument, so an instance would
     * carry nothing. A private constructor states that, where an implicit public one would invite a
     * caller to inject this class as a collaborator and then to mock it -- and a mocked summary is one
     * that can be made to return the text the bounds above exist to constrain.</p>
     *
     * @throws AssertionError always, so that reflective instantiation fails as loudly as direct
     *     instantiation is prevented
     */
    private FailureSummary() {
        throw new AssertionError("FailureSummary is a utility holder and is never instantiated");
    }

    /**
     * Renders the deepest message in a failure's cause chain, sanitised, masked and bounded.
     *
     * @param failure the throwable to describe, which may be {@code null}
     * @return the deepest non-blank message in the chain with every control character neutralised, every
     *     card-number-shaped run masked and the whole bounded to {@link #MAX_LENGTH} characters plus
     *     {@link #TRUNCATION_MARKER}; {@link #NONE} when {@code failure} is {@code null}; or
     *     {@link #NO_MESSAGE} when no link in the chain carries one. Never {@code null}
     */
    public static String of(Throwable failure) {
        if (failure == null) {
            return NONE;
        }

        String deepest = null;
        Throwable walk = failure;
        int depth = 0;
        while (walk != null && depth < ThrowableDigest.MAX_CAUSE_DEPTH) {
            String message = walk.getMessage();
            if (message != null && !message.isBlank()) {
                deepest = message;
            }
            // WHY : Assumptions: the identity test is written against the retrieved cause, exactly as
            //       ThrowableDigest's walk is, so the two agree about where a chain ends. A chain that
            //       terminated at a different depth in the two renderings would put a message beside a
            //       digest that does not name the type it came from, which is worse than either alone.
            Throwable cause = walk.getCause();
            walk = cause == walk ? null : cause;
            depth++;
        }

        if (deepest == null) {
            return NO_MESSAGE;
        }
        return bounded(CardNumberMasker.maskEmbeddedCardNumbers(LogSafeText.sanitize(deepest)));
    }

    /**
     * Renders the deepest message in a failure's cause chain with every digit run of three or more
     * replaced, in addition to the sanitising, masking and bounding {@link #of(Throwable)} performs.
     *
     * <p>⚠️ Purpose: this is the rendering for a failure whose message text was composed by a DATABASE
     * DRIVER, a CODEC or a VALIDATION LIBRARY over a record this process received -- a queue payload, an
     * extract record, a row being written. Those messages quote the values they could not handle, and on
     * this schema those values are account identifiers, transaction identifiers and amounts. The
     * card-number masking {@link #of(Throwable)} applies does not reach them: its rule is a run of sixteen
     * or more digits, deliberately, so an eleven-digit account identifier passes through it untouched.
     * Replacing every run of {@link #MIN_REDACTED_DIGITS} digits or more closes that gap without closing
     * the diagnosis, because what makes such a message useful is its WORDS -- {@code numeric field
     * overflow}, {@code duplicate key value violates unique constraint "pk_pending_auth_detail"}, the
     * table and column names it quotes -- and every one of those is letters.</p>
     *
     * <p>⚠️ Refactoring Rationale: this exists because two log sites had to choose between an unusable
     * line and a disclosing one, and both chose unusable. The queue error handler and the maintenance-task
     * runner each recorded a failure as a chain of TYPES with no message, on the stated ground that the
     * message could quote a key value -- which was true, and left an operator holding
     * {@code PSQLException} with no way to learn that the condition was a numeric overflow. A third
     * option was needed rather than a relaxation of the second: {@link #of(Throwable)} stays as it is,
     * because the transport failures it serves carry endpoints and host names rather than record values,
     * and a single renderer would have to be as strict as its strictest caller.</p>
     *
     * <p>⚠️ Assumptions: the redaction runs BEFORE the bound and AFTER the sanitisation, for the same
     * reasons that ordering holds in {@link #of(Throwable)}: a message that is really two lines must be
     * made one before anything reasons about its runs, and a bound applied before redaction could cut a
     * run in half and leave a partial value standing. Card masking is applied as well and not skipped,
     * even though every run it would mask is also a run this redaction replaces -- the two are kept in
     * the same order for both renderings so that a reader who has followed one has followed both, and the
     * masker's own tail-preserving form is what a card-shaped run should render as if the thresholds ever
     * diverge.</p>
     *
     * <p>⚠️ Trade-offs: a genuinely diagnostic number of three or more digits is lost -- a byte offset, a
     * record length, a port. The exchange is accepted because the sites that call this one already carry
     * the located facts as their OWN structured fields: the queue handler carries the broker's message
     * identifier and the redelivery count, the extract loader carries the record's ordinal, and the runner
     * carries the job name. What the message adds is the CONDITION, and the condition is words.</p>
     *
     * @param failure the throwable to describe, which may be {@code null}
     * @return the deepest non-blank message in the chain, sanitised, card-masked, with every run of
     *     {@link #MIN_REDACTED_DIGITS} or more digits replaced by {@link #REDACTION_CHARACTER} and the
     *     whole bounded to {@link #MAX_LENGTH} characters plus {@link #TRUNCATION_MARKER};
     *     {@link #NONE} when {@code failure} is {@code null}; or {@link #NO_MESSAGE} when no link in the
     *     chain carries one. Never {@code null}
     */
    public static String redactedOf(Throwable failure) {
        String summary = of(failure);
        if (NONE.equals(summary) || NO_MESSAGE.equals(summary)) {
            return summary;
        }
        // WHY : ⚠️ Assumptions: the redaction is applied to the ALREADY-BOUNDED rendering rather than to
        //       the raw message, so this method cannot disagree with of(...) about what the deepest
        //       message was or about where the truncation fell. Redacting a run does not change its
        //       length, so the bound is preserved exactly and the truncation marker stays where of(...)
        //       put it -- which is why re-bounding afterwards is unnecessary rather than omitted.
        return redactDigitRuns(summary);
    }

    /**
     * Replaces every run of {@link #MIN_REDACTED_DIGITS} or more consecutive digits in one string.
     *
     * <p>⚠️ Assumptions: a run is delimited by any non-digit, so a value written with separators -- a
     * date as {@code 2026-08-12} or a key tuple as {@code (20000000005, 99366)} -- is replaced group by
     * group rather than passed over for want of one long run. That is what makes the rule hold against a
     * driver message, whose values arrive punctuated.</p>
     *
     * @param text the sanitised, masked and bounded message to redact; must not be {@code null}
     * @return the same text with each qualifying run replaced character for character, never {@code null}
     */
    private static String redactDigitRuns(String text) {
        StringBuilder redacted = new StringBuilder(text.length());
        int position = 0;
        while (position < text.length()) {
            if (!Character.isDigit(text.charAt(position))) {
                redacted.append(text.charAt(position));
                position++;
                continue;
            }
            int runEnd = position;
            while (runEnd < text.length() && Character.isDigit(text.charAt(runEnd))) {
                runEnd++;
            }
            int runLength = runEnd - position;
            if (runLength >= MIN_REDACTED_DIGITS) {
                redacted.append(String.valueOf(REDACTION_CHARACTER).repeat(runLength));
            } else {
                redacted.append(text, position, runEnd);
            }
            position = runEnd;
        }
        return redacted.toString();
    }

    /**
     * Returns the first database state code in a failure's cause chain.
     *
     * <p>Purpose: a database refusal is identified by its state code and not by its message. The code is
     * five characters, is defined by the standard rather than by the driver, and carries no values at
     * all -- {@code 22003} is a numeric overflow and {@code 23514} is a check-constraint violation
     * whatever row provoked them. It is therefore the one part of a driver failure that can be logged
     * with no further consideration, and it is the part an operator needs first.</p>
     *
     * <p>Assumptions: the FIRST code found walking outward-in is returned rather than the deepest,
     * because a driver wraps its own exception in a translated one that carries the same code, and the
     * outer one is the one whose code the framework has already normalised.</p>
     *
     * @param failure the throwable to inspect, which may be {@code null}
     * @return the state code of the first {@link SQLException} in the chain that declares one, or
     *     {@code null} when the chain holds none -- which is every failure that is not a database
     *     refusal, so a caller omits the field rather than printing an empty one
     */
    public static String sqlStateOf(Throwable failure) {
        Throwable walk = failure;
        int depth = 0;
        while (walk != null && depth < ThrowableDigest.MAX_CAUSE_DEPTH) {
            if (walk instanceof SQLException refusal) {
                String state = refusal.getSQLState();
                if (state != null && !state.isBlank()) {
                    // WHY : Assumptions: the code is bounded even though the standard fixes it at five
                    //       characters, because the value arrives from a driver rather than from this
                    //       process and a bound costs nothing beside a failure path. Sanitising is
                    //       applied for the same reason every other externally-sourced value in this
                    //       package is: a state code carrying a line terminator would end the caller's
                    //       log line as readily as a message would.
                    return bounded(LogSafeText.sanitize(state));
                }
            }
            Throwable cause = walk.getCause();
            walk = cause == walk ? null : cause;
            depth++;
        }
        return null;
    }

    /**
     * Returns the first database state code in a failure's cause chain, or a stable token when there is
     * none.
     *
     * <p>⚠️ Refactoring Rationale: this exists because two call sites were rendering the absence of a
     * state code with two different tokens of their own, and a third was rendering it as the literal
     * {@code null} that a formatter produces for an unset argument. A structured field reading
     * {@code sqlState=null} cannot be told apart from a field the emitter failed to populate, which is
     * precisely the confusion the sibling {@link #NO_MESSAGE} token exists to prevent. Naming the token
     * once here is what keeps one query able to match every site.</p>
     *
     * <p>⚠️ Assumptions: the token is {@value #NO_SQL_STATE} rather than the {@link #NONE} this class uses
     * for a {@code null} failure, because the two absences mean different things: {@link #NONE} says there
     * was no failure to describe at all, while this says a failure was described and was not a database
     * one. A reader triaging a queue backlog needs to tell those apart at a glance.</p>
     *
     * @param failure the throwable to inspect, which may be {@code null}
     * @return the state code as {@link #sqlStateOf(Throwable)} renders it, or {@value #NO_SQL_STATE} when
     *     no link in the chain is a database failure carrying one; never {@code null}
     */
    public static String sqlStateOrAbsent(Throwable failure) {
        String state = sqlStateOf(failure);
        return state == null ? NO_SQL_STATE : state;
    }

    /**
     * Renders the redacted condition of a DATABASE failure, and withholds the message of anything else.
     *
     * <p>⚠️ Purpose: this is the rendering for a log site that does not know what kind of failure will
     * reach it. A generic handler — a queue error handler, a job runner — receives whatever the work it
     * wrapped happened to raise, so it cannot reason about who composed the message it is holding. This
     * method makes that decision for it on the one piece of evidence available: a chain carrying a database
     * state code was composed by a JDBC driver, whose messages quote DDL names and record values, and both
     * of those are handled — the names are letters and survive, the values are digit runs and are replaced.
     * A chain carrying no state code could have been composed by anything, including a cloud SDK, and those
     * messages quote credentials and signed locations that no digit rule can recognise.</p>
     *
     * <p>⚠️ Refactoring Rationale: this gate exists because adding the message to a generic handler without
     * one broke a security property a sibling test already held: a transport failure reporting
     * {@code connect failed to https://... using key AKIA...} carries an access-key identifier, which
     * contains no digit run at all and would therefore have passed through the redaction untouched into an
     * operational log. Withholding by default and admitting on evidence is the direction that cannot fail
     * open, and the digest beside this field still names every type in the chain, so a withheld message
     * never leaves a reader with nothing.</p>
     *
     * <p>⚠️ Alternatives Considered: an allowlist of exception types whose messages are known safe.
     * Rejected because it has to be maintained against every library on the classpath, and the failure mode
     * of a stale allowlist is silent disclosure rather than a missing line. Also considered: rendering the
     * message for every failure and relying on the redaction. Rejected for the measured reason above.</p>
     *
     * <p>⚠️ Trade-offs: a diagnostic message from a non-database failure is lost at these sites, and the
     * site that knows its own failure's provenance should call {@link #of(Throwable)} or
     * {@link #redactedOf(Throwable)} directly instead of this method. Two such sites exist and both do:
     * the authorization listener's wire-format refusal renders the codec's own message, which that codec
     * gates field by field, and the extract loader renders its mapper's, which it redacts.</p>
     *
     * @param failure the throwable to describe, which may be {@code null}
     * @return {@link #redactedOf(Throwable)} when some link in the chain is a database failure carrying a
     *     state code; {@value #WITHHELD} otherwise, including when {@code failure} is {@code null}. Never
     *     {@code null}
     */
    public static String databaseConditionOf(Throwable failure) {
        return sqlStateOf(failure) == null ? WITHHELD : redactedOf(failure);
    }

    /**
     * Bounds a value to {@link #MAX_LENGTH}, marking it when it was cut.
     *
     * @param value the already-sanitised, already-masked value; must not be {@code null}
     * @return the value itself when it fits, so the ordinary case allocates nothing; otherwise its first
     *     {@link #MAX_LENGTH} characters followed by {@link #TRUNCATION_MARKER}
     */
    private static String bounded(String value) {
        if (value.length() <= MAX_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LENGTH) + TRUNCATION_MARKER;
    }
}
