package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what a posting-validation rendering may and may not carry into a batch log.
 *
 * <p>Refactoring Rationale: this record was flagged because it overrode nothing, so the
 * record-generated {@code toString} rendered {@code projectedCycleBalance} -- a monetary value derived
 * from a cardholder's account. The volume is what makes it a hazard rather than a style question: the
 * record is produced once per daily transaction inside the posting loop, so a single trace-level
 * statement in that loop would write one balance per transaction for the whole file.</p>
 *
 * <p>Assumptions: the assertions name the value that must be ABSENT rather than checking the shape of
 * what is present, which is the direction the sibling renderings in this reactor settled on. A
 * rendering can only regress by GAINING a member, and an assertion on presence cannot detect a gain. A
 * positive assertion accompanies them so that a rendering reduced to the empty string could not pass by
 * carrying nothing at all.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.</p>
 */
@DisplayName("the posting validation result rendering")
class PostingValidationResultDiagnosticRenderingTest {

    /**
     * A balance distinctive enough that a substring assertion cannot match by accident.
     *
     * <p>Assumptions: the digits are chosen so that no part of the reason name, the type name or any
     * punctuation the rendering emits could contain them, which is what makes an absence assertion
     * meaningful rather than incidental.</p>
     */
    private static final BigDecimal BALANCE = new BigDecimal("8675.31");

    /**
     * An accepted outcome discloses no balance.
     */
    @Test
    @DisplayName("discloses no balance on an accepted outcome")
    void acceptedOutcomeDisclosesNoBalance() {
        assertThat(PostingValidationResult.accepted(BALANCE).toString())
                .doesNotContain("8675.31")
                .doesNotContain("8675");
    }

    /**
     * A rejected outcome discloses no balance either.
     *
     * <p>Assumptions: the reason asserted here is 102 specifically, and not one of the two that end
     * validation earlier. The record's constructor couples the two components -- a reason that
     * {@link RejectReason#terminatesValidation() terminates validation} may not carry a projection,
     * because the reference cannot have computed one before it read the account -- so 100 and 101 have
     * no balance to leak in the first place and asserting absence on them would prove nothing. 102 is
     * the reject reason that <em>is</em> decided by the projection at
     * {@code app/cbl/CBTRN02C.cbl:407}, so it is the only rejected path on which a balance could
     * reach a log at all, and therefore the only one worth pinning.</p>
     *
     * <p>Refactoring Rationale: an earlier draft of this test paired reason 100 with a balance and the
     * constructor refused it. The refusal was correct and the test was wrong, so the test moved to the
     * reason that carries a projection rather than the constraint being loosened to admit the pairing.
     * The guard is the reason the impossible state is unrepresentable, and weakening it to satisfy a
     * test would have removed the protection to preserve the mistake.</p>
     */
    @Test
    @DisplayName("discloses no balance on a rejected outcome that carries one")
    void rejectedOutcomeDisclosesNoBalance() {
        String rendered = PostingValidationResult
                .rejected(RejectReason.OVER_CREDIT_LIMIT, BALANCE)
                .toString();

        assertThat(rendered).doesNotContain("8675.31").doesNotContain("8675");
    }

    /**
     * The reason is rendered in full, which is the point of the rendering.
     *
     * <p>Assumptions: the reason is a value from a closed set -- the four documented reject reasons and
     * the accepted case -- so it carries no cardholder content and it is exactly what an operator
     * reconciling a reject stream needs. Withholding it as well would leave a rendering that says
     * nothing at all, which is a different failure rather than a safer one.</p>
     *
     * <p>Assumptions: the identifier asserted is the four-character REASON-CODE FIELD rather than the
     * constant's Java name, because the code field is the identifier the artifact an operator is
     * reconciling against actually carries -- it occupies the code position of the reject record that
     * {@code app/cbl/CBTRN02C.cbl} writes, and it is the value the committed expectation files under
     * {@code tests/golden/posting/} compare. Asserting the Java name instead would pin a spelling that
     * appears nowhere in the reject stream, and a rename of the constant would fail this case while
     * changing nothing an operator reads.</p>
     *
     * <p>Assumptions: the code field is asserted by asking the reason for it rather than by spelling
     * {@code 0101} here, so the width and the zero padding stay owned by one type. A literal would let
     * this case and the field's own renderer disagree about the padding while both passed.</p>
     *
     * <p>Assumptions: 101 is passed with a {@code null} projection because that is the only shape the
     * constructor admits for it, reason 101 being assigned in the {@code INVALID KEY} branch of the
     * account read and so before any balance exists. This case therefore also covers the
     * terminating-reason path, which the balance assertions above cannot reach.</p>
     */
    @Test
    @DisplayName("still names the reason in full")
    void reasonIsRenderedInFull() {
        String rendered = PostingValidationResult
                .rejected(RejectReason.ACCOUNT_NOT_FOUND_ON_READ, null)
                .toString();

        assertThat(rendered)
                .contains("PostingValidationResult")
                .contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.codeField());

        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.codeField())
                .as("the identifier the rendering carries is itself non-empty, so the assertion above"
                        + " cannot pass vacuously against a rendering that names nothing")
                .isNotBlank();
    }

    /**
     * The balance position discloses only what the reason on the same line already implies.
     *
     * <p>Assumptions: the balance position renders whether a projection was FORMED and never the
     * projection itself, so neither shape can carry the amount. That is the disclosure property this
     * case exists for, and it is asserted for both shapes rather than only for the one that had a real
     * value -- a rendering that withheld the present value but interpolated {@code null} for the absent
     * one would still put a second shape on an operator's line for no gain.</p>
     *
     * <p>Assumptions: the formed-or-not bit adds no information, and that is provable here rather than
     * assumed: the constructor couples the two components in BOTH directions, refusing a terminating
     * reason that carries a projection and refusing a non-terminating one that lacks it, so the bit is
     * exactly {@code !reason.terminatesValidation()}. This case asserts that equivalence directly, which
     * is what makes rendering the bit safe -- were the coupling ever loosened, the bit would begin to
     * carry a fact the reason does not imply and this case would fail at that point rather than after a
     * disclosure review.</p>
     *
     * <p>Alternatives Considered: substituting one fixed redaction unconditionally, so that the two
     * shapes render byte-identically in this position. It was declined because the bit is derivable from
     * the reason either way, so uniformity would remove a fact an operator can already compute while
     * leaving the disclosure surface exactly as it is -- and because the equivalence asserted below is a
     * stronger guard than uniformity: a uniform rendering would keep passing if the constructor's
     * coupling were removed, whereas this one would not.</p>
     */
    @Test
    @DisplayName("discloses in the balance position only what the reason already implies")
    void balancePositionDisclosesOnlyWhatTheReasonImplies() {
        String withProjection = PostingValidationResult
                .rejected(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION, BALANCE)
                .toString();
        String withoutProjection = PostingValidationResult
                .rejected(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE, null)
                .toString();

        assertThat(withProjection).doesNotContain("8675.31").doesNotContain("8675");
        assertThat(withProjection).doesNotContain("null");
        assertThat(withoutProjection).doesNotContain("null");

        assertThat(withProjection)
                .as("a reason that reached the boundary tests reports a projection was formed")
                .contains(Boolean.toString(
                        !RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.terminatesValidation()));
        assertThat(withoutProjection)
                .as("a reason that ended validation first reports that none was")
                .contains(Boolean.toString(
                        !RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.terminatesValidation()));

        assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.terminatesValidation())
                .as("the two reasons chosen sit on opposite sides of the coupling, so the two"
                        + " assertions above cannot both be satisfied by one constant rendering")
                .isNotEqualTo(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.terminatesValidation());
    }

    /**
     * A whole batch's worth of results discloses nothing when rendered together.
     *
     * <p>Assumptions: asserted on a list because that is the volume case. A collection's own rendering
     * delegates to each element's, so one interpolation of a batch of results would have written one
     * balance per transaction -- which is the specific exposure this override closes.</p>
     */
    @Test
    @DisplayName("discloses nothing when a batch of results is rendered at once")
    void aBatchOfResultsDisclosesNothing() {
        String rendered = java.util.List.of(
                PostingValidationResult.accepted(BALANCE),
                PostingValidationResult.rejected(RejectReason.OVER_CREDIT_LIMIT, BALANCE),
                PostingValidationResult.accepted(new BigDecimal("2468.13"))).toString();

        assertThat(rendered)
                .doesNotContain("8675.31")
                .doesNotContain("2468.13");
    }
}
