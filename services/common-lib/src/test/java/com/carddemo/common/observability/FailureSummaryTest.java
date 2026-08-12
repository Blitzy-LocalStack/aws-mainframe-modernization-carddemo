package com.carddemo.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts what a failure's own message is allowed to become before it reaches a log line.
 *
 * <p><b>Purpose.</b> {@link FailureSummary} exists because {@link ThrowableDigest} deliberately carries no
 * message text, and a generic transport type carries its whole diagnosis in its message -- a failed queue
 * send renders as one {@code SdkClientException} with no cause beneath it, so a digest of it cannot tell a
 * refused connection from an unresolvable name from a certificate that does not verify. This class holds
 * the four properties that make logging that text defensible: the deepest message is the one taken, control
 * characters cannot forge a second log record, a card-number-shaped run cannot survive, and the whole is
 * bounded.
 *
 * <p>Assumptions: the ORDER of the three transformations is asserted and not merely their presence. Masking
 * after truncation would let a bound cut a sixteen-digit run in half and leave a partial number the masker
 * no longer recognises, and sanitising after masking would let a value that is two lines be reasoned about
 * as one. Each case below therefore fixes one step against a value the other steps would mishandle.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class FailureSummaryTest {

    /** A card number of the declared sixteen digits, so the masker's own rule applies to it. */
    private static final String CARD_NUM = "4111111111111111";

    /**
     * An absent failure renders as the placeholder the digest beside it uses.
     *
     * <p>Assumptions: the two classes share the placeholder rather than each defining one, so a line
     * carrying both fields reads consistently when the failure is absent from both.</p>
     */
    @Test
    @DisplayName("a null failure renders as the shared placeholder")
    void aNullFailureRendersAsThePlaceholder() {
        assertThat(FailureSummary.of(null)).isEqualTo(FailureSummary.NONE);
        assertThat(FailureSummary.NONE).isEqualTo(ThrowableDigest.NONE);
    }

    /**
     * A failure carrying no message anywhere says so rather than rendering empty.
     *
     * <p>Assumptions: an empty value in a structured line cannot be told apart from a field the emitter
     * forgot to populate, and the two want different responses from whoever reads the line.</p>
     */
    @Test
    @DisplayName("a chain with no message at all renders as the no-message placeholder")
    void aChainWithNoMessageSaysSo() {
        Throwable outer = new IllegalStateException((String) null, new IllegalArgumentException());

        assertThat(FailureSummary.of(outer)).isEqualTo(FailureSummary.NO_MESSAGE);
    }

    /**
     * The DEEPEST message is taken, not the outermost.
     *
     * <p>Assumptions: a wrapping framework restates its cause's message inside its own, so the outermost
     * message is the longest and the least specific. This case gives the outer link a message that
     * contains the inner one, which is exactly the shape a container's bean-creation failure has, and
     * asserts the inner one is what survives.</p>
     */
    @Test
    @DisplayName("the deepest message in the chain is the one rendered")
    void theDeepestMessageIsRendered() {
        Throwable root = new IOException("Connection refused: connect");
        Throwable middle = new IllegalStateException("send failed: Connection refused: connect", root);
        Throwable outer = new RuntimeException("could not publish reply: send failed", middle);

        assertThat(FailureSummary.of(outer)).isEqualTo("Connection refused: connect");
    }

    /**
     * A blank message is treated as absent, so a shallower non-blank one is used instead.
     *
     * <p>Assumptions: a blank message and a null one are the same amount of information, and a rendering
     * that preferred the blank because it was deeper would report nothing while a usable message sat one
     * link out.</p>
     */
    @Test
    @DisplayName("a blank deepest message falls back to the nearest non-blank one")
    void aBlankDeepestMessageFallsBack() {
        Throwable root = new IOException("   ");
        Throwable outer = new IllegalStateException("certificate verification failed", root);

        assertThat(FailureSummary.of(outer)).isEqualTo("certificate verification failed");
    }

    /**
     * Every control character is neutralised, so a message cannot end the caller's log line.
     *
     * <p>Assumptions: this is the concern named CWE-117 and it is asserted here as well as on
     * {@link LogSafeText} itself, because a rendering that forgot to apply the sanitiser would pass every
     * other case in this class. The replacement preserves the value's LENGTH, which is why the assertion
     * names the expected text exactly rather than merely testing for the absence of a terminator.</p>
     */
    @Test
    @DisplayName("a message carrying line terminators cannot forge a second log record")
    void controlCharactersAreNeutralised() {
        Throwable forging = new IllegalStateException(
                "refused\nevent=auth.reply.published outboxId=1\r\ttrailing");

        String summary = FailureSummary.of(forging);

        assertThat(summary).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        assertThat(summary)
                .isEqualTo("refused event=auth.reply.published outboxId=1  trailing");
    }

    /**
     * A card number embedded in a driver's message is masked to its last four digits.
     *
     * <p>Assumptions: this is the case that decides whether a message may be logged at all. A driver's
     * unique-violation message quotes the values that broke the constraint, and on this schema the
     * constrained columns include a primary account number -- so the shape asserted here is the shape that
     * message actually has.</p>
     */
    @Test
    @DisplayName("a card number quoted by a driver message is masked")
    void anEmbeddedCardNumberIsMasked() {
        Throwable refusal = new SQLException(
                "duplicate key value violates unique constraint \"pk_pending_auth_detail\""
                        + " Detail: Key (card_num)=(" + CARD_NUM + ") already exists.");

        String summary = FailureSummary.of(refusal);

        assertThat(summary).doesNotContain(CARD_NUM);
        assertThat(summary).contains("************1111");
    }

    /**
     * A message longer than the bound is cut and marked, and the masking still applied first.
     *
     * <p>Assumptions: BOTH halves are asserted in one case because they are one property. The card number
     * sits before the cut, so a rendering that truncated first would leave it whole in the output; and the
     * marker is asserted so a reader can tell a cut message from one that happened to end there.</p>
     */
    @Test
    @DisplayName("an over-long message is masked first and then bounded with a marker")
    void anOverLongMessageIsMaskedThenBounded() {
        String preamble = "x".repeat(40);
        Throwable verbose = new IllegalStateException(
                preamble + CARD_NUM + "y".repeat(400));

        String summary = FailureSummary.of(verbose);

        assertThat(summary).hasSize(FailureSummary.MAX_LENGTH
                + FailureSummary.TRUNCATION_MARKER.length());
        assertThat(summary).endsWith(FailureSummary.TRUNCATION_MARKER);
        assertThat(summary).doesNotContain(CARD_NUM);
        assertThat(summary).contains("************1111");
    }

    /**
     * A message exactly at the bound is returned whole, with no marker.
     *
     * <p>Assumptions: the boundary is asserted from both sides -- the case above is one character past it
     * -- because a bound that was off by one would be invisible in every other case here.</p>
     */
    @Test
    @DisplayName("a message exactly at the bound is returned unmarked")
    void aMessageAtTheBoundIsUnmarked() {
        String exact = "z".repeat(FailureSummary.MAX_LENGTH);

        String summary = FailureSummary.of(new IllegalStateException(exact));

        assertThat(summary).isEqualTo(exact);
        assertThat(summary).doesNotContain(FailureSummary.TRUNCATION_MARKER);
    }

    /**
     * A self-referential cause chain terminates rather than spinning.
     *
     * <p>Assumptions: the same two guards the digest's walk carries are asserted here -- the identity test
     * for the single-element cycle and the depth cap for longer ones -- because a rendering that
     * terminated at a different depth from the digest would put a message beside a digest that does not
     * name the type it came from.</p>
     */
    @Test
    @DisplayName("a self-referential cause chain terminates")
    void aSelfReferentialChainTerminates() {
        Throwable cyclic = new IllegalStateException("cycle") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(FailureSummary.of(cyclic)).isEqualTo("cycle");
    }

    /**
     * The database state code is read out of a wrapped refusal, and is absent for anything else.
     *
     * <p>Assumptions: the state code is the one part of a driver failure that can be logged with no
     * further consideration -- five standard characters naming the condition and quoting no value -- and
     * it is the part an operator needs first. {@code 22003} is the numeric overflow this checkpoint's
     * money-domain finding produced.</p>
     */
    @Test
    @DisplayName("a wrapped database refusal yields its state code, and other failures yield none")
    void theStateCodeIsReadFromTheChain() {
        Throwable refusal = new RuntimeException("could not execute statement",
                new IllegalStateException("wrapped",
                        new SQLException("numeric field overflow", "22003")));

        assertThat(FailureSummary.sqlStateOf(refusal)).isEqualTo("22003");
        assertThat(FailureSummary.sqlStateOf(new IllegalStateException("no database here"))).isNull();
        assertThat(FailureSummary.sqlStateOf(null)).isNull();
    }

    /**
     * A refusal carrying no state code yields none rather than an empty value.
     *
     * <p>Assumptions: a caller omits the field entirely when this returns {@code null}, so returning an
     * empty string instead would put {@code sqlState=} with nothing after it into a log line and a parser
     * would read that as a state code it could not interpret.</p>
     */
    @Test
    @DisplayName("a refusal with no state code yields null so the field is omitted")
    void anAbsentStateCodeYieldsNull() {
        assertThat(FailureSummary.sqlStateOf(new SQLException("no state declared"))).isNull();
        assertThat(FailureSummary.sqlStateOf(new SQLException("blank state", "   "))).isNull();
    }

    /**
     * The absence renderer answers a stable token where the raw reader answers {@code null}.
     *
     * <p>⚠️ Assumptions: both halves are asserted on the SAME inputs the case above uses, so the two
     * renderings cannot drift into disagreeing about what "absent" means. The token is asserted by
     * constant rather than by literal, because a log query is written against the constant's value and a
     * literal here would let the two diverge silently.</p>
     */
    @Test
    @DisplayName("the absence renderer answers the shared token rather than null")
    void theAbsenceRendererAnswersTheSharedToken() {
        assertThat(FailureSummary.sqlStateOrAbsent(null)).isEqualTo(FailureSummary.NO_SQL_STATE);
        assertThat(FailureSummary.sqlStateOrAbsent(new IllegalStateException("no database here")))
                .isEqualTo(FailureSummary.NO_SQL_STATE);
        assertThat(FailureSummary.sqlStateOrAbsent(new SQLException("no state declared")))
                .isEqualTo(FailureSummary.NO_SQL_STATE);
        assertThat(FailureSummary.sqlStateOrAbsent(
                new RuntimeException("wrapped", new SQLException("numeric field overflow", "22003"))))
                .as("a present code must pass through unchanged, or the token would hide every state")
                .isEqualTo("22003");
    }

    /**
     * The default-withheld renderer shows a driver's condition and withholds everything else.
     *
     * <p>⚠️ Purpose: this is the rendering a generic log site uses -- a queue error handler, a job runner --
     * because such a site receives whatever the work it wrapped raised and cannot reason about who composed
     * the message. The gate is the presence of a database state code, which is the only evidence available
     * that a JDBC driver wrote the text.
     *
     * <p>⚠️ Assumptions: the withheld half is asserted with a message carrying an ACCESS-KEY identifier and
     * no digit run at all, because that is the case that makes the gate necessary rather than tidy. A digit
     * rule cannot recognise a credential, so a renderer that showed every message would have put that
     * identifier into an operational log while passing every assertion about digits.
     */
    @Test
    @DisplayName("the default-withheld renderer shows a driver condition and withholds other messages")
    void theDefaultWithheldRendererShowsOnlyDriverConditions() {
        Throwable driverFailure = new IllegalStateException("could not execute statement",
                new SQLException("ERROR: numeric field overflow", "22003"));

        assertThat(FailureSummary.databaseConditionOf(driverFailure))
                .as("a chain carrying a state code was composed by a driver, so its words are shown")
                .contains("numeric field overflow");

        Throwable transportFailure = new IllegalStateException(
                "connect failed to https://sqs.example.invalid using key AKIAEXAMPLEKEY");

        assertThat(FailureSummary.databaseConditionOf(transportFailure))
                .as("a credential carries no digit run, so the message must be withheld rather than"
                        + " redacted")
                .isEqualTo(FailureSummary.WITHHELD);
        assertThat(FailureSummary.databaseConditionOf(null)).isEqualTo(FailureSummary.WITHHELD);
        assertThat(FailureSummary.databaseConditionOf(new SQLException("no state declared")))
                .as("a database type with no state code is no evidence either, so it withholds too")
                .isEqualTo(FailureSummary.WITHHELD);
    }

    /**
     * The redacting renderer keeps the condition's words and replaces every value-shaped digit run.
     *
     * <p>⚠️ Purpose: this is the rendering for a message composed by a driver, a codec or a validation
     * library over a record this process received. The card-masking the plain renderer applies does not
     * reach the identifiers such a message quotes -- its rule is a run of sixteen or more digits, so an
     * eleven-digit account identifier passes through untouched -- and this case is what holds the wider
     * rule in place.
     *
     * <p>⚠️ Assumptions: the geometry a reader needs is asserted PRESENT alongside the values asserted
     * absent, because a renderer that replaced every digit would satisfy the absence half while making the
     * line useless. A declared precision and a declared scale are one and two digits, and a power of ten
     * is one, so all three survive a three-digit threshold; that is the reason the threshold is three.
     */
    @Test
    @DisplayName("the redacting renderer keeps words and geometry and replaces value-shaped runs")
    void theRedactingRendererKeepsWordsAndReplacesValues() {
        Throwable overflow = new RuntimeException("could not execute statement",
                new SQLException("ERROR: numeric field overflow  Detail: A field with precision 11,"
                        + " scale 2 must round to an absolute value less than 10^9.", "22003"));

        String rendered = FailureSummary.redactedOf(overflow);

        assertThat(rendered)
                .as("the condition and the declared geometry are what make this line actionable")
                .contains("numeric field overflow")
                .contains("precision 11, scale 2")
                .contains("less than 10^9");

        Throwable violation = new RuntimeException("wrapped", new SQLException(
                "duplicate key value violates unique constraint \"pk_pending_auth_detail\""
                        + "  Detail: Key (account_id, auth_date, auth_time)"
                        + "=(20000000005, 99366, 235959994) already exists", "23505"));

        assertThat(FailureSummary.redactedOf(violation))
                .as("no identifier the message quotes may survive, and none of them is card-shaped")
                .doesNotContain("20000000005")
                .doesNotContain("99366")
                .doesNotContain("235959994")
                .contains("(###########, #####, #########)")
                .contains("pk_pending_auth_detail");
    }

    /**
     * The redacting renderer preserves the placeholders, the sanitisation and the bound.
     *
     * <p>⚠️ Assumptions: the two placeholder inputs are asserted to pass through UNREDACTED, because
     * {@code (none)} and {@code (no-message)} carry no digits and a renderer that rebuilt them would be
     * doing work whose only possible effect is to change them. The line-terminator case is asserted here
     * as well as on the plain renderer, because the redaction runs after the sanitisation and an
     * implementation that reordered the two would pass the plain case and fail this one.
     *
     * <p>⚠️ Assumptions: a card number is asserted to end up with no digits at all. It is masked first to
     * its last four and those four are then a qualifying run, so the two rules compose rather than
     * competing -- which is the property that makes applying both, in that order, correct rather than
     * redundant.
     */
    @Test
    @DisplayName("the redacting renderer keeps the placeholders, the sanitisation and the bound")
    void theRedactingRendererKeepsThePlaceholdersAndTheBound() {
        assertThat(FailureSummary.redactedOf(null)).isEqualTo(FailureSummary.NONE);
        assertThat(FailureSummary.redactedOf(new IllegalStateException()))
                .isEqualTo(FailureSummary.NO_MESSAGE);
        assertThat(FailureSummary.redactedOf(
                new IllegalStateException("first line\r\nevent=forged second record")))
                .as("the sanitisation must run before the redaction, or a forged record survives it")
                .doesNotContain("\n")
                .doesNotContain("\r");
        assertThat(FailureSummary.redactedOf(
                new IllegalStateException("could not parse card 4111111111111111 in request")))
                .as("masking then redaction leaves a card-shaped run with no digit standing")
                .doesNotContain("4111")
                .doesNotContain("1111");
        assertThat(FailureSummary.redactedOf(new IllegalStateException("x".repeat(400))))
                .as("the bound is the plain renderer's, applied before this one and preserved by it")
                .hasSize(FailureSummary.MAX_LENGTH + FailureSummary.TRUNCATION_MARKER.length())
                .endsWith(FailureSummary.TRUNCATION_MARKER);
    }
}
