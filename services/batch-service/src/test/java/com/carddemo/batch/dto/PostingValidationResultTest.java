package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds {@link PostingValidationResult} to the precedence the reference program expresses by omission,
 * together with the trailer bytes, the fixed-point scale and the presence coupling that surround it.
 *
 * <p>Assumptions: the assertion that matters most in this class is
 * {@link #overLimitAndPastExpiryReportsOneHundredThreeAndNotOneHundredTwo()}, and the parameterised
 * matrix in {@link #resolvesEveryCombinationOfFindingsAsTheReferenceDoes(boolean, boolean, boolean,
 * boolean, int)} that contains the same row. The rule they pin is written in
 * {@code app/cbl/CBTRN02C.cbl} as an ABSENCE -- there is no guard between the credit-limit assignment
 * at {@code :410} and the expiration assignment at {@code :417}, so the second overwrites the first.
 * An implementation written as an either-or over the two conditions compiles cleanly, reports the other
 * reason, and passes every scenario that trips only ONE condition. That single defect is what this
 * class exists to catch, which is why the matrix drives all sixteen combinations rather than the four
 * single-condition cases a reader would think sufficient.</p>
 *
 * <p>Alternatives Considered: asserting only the four single-condition outcomes plus acceptance, which
 * is the natural shape and is five rows instead of sixteen. Rejected because the defect this class
 * guards against is invisible to exactly those five rows: each of them trips at most one condition, so
 * an either-or and a last-writer-wins resolution agree on every one of them. The rows that separate
 * the two implementations are the ones where more than one finding holds, and they are the rows a
 * hand-written fixture is least likely to include.</p>
 *
 * <p>Assumptions: the expected trailer bytes below are pinned to two independent authorities rather
 * than to values chosen to read well -- a string literal typed from the reference program, and the
 * committed expectation files of the functional-parity oracle under {@code tests/golden/posting/},
 * whose reject records carry {@code 0100}, {@code 0101}, {@code 0102} and {@code 0103} at characters
 * 351 to 354 and the space-padded description at 355 to 430. Nothing under {@code app/**} or
 * {@code tests/**} is modified by this class; both are read as the behavioural oracle.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each method below carries its own. No authorship,
 * availability or revision at-clause appears in this file, matching the rest of this package.</p>
 */
class PostingValidationResultTest {

    /**
     * A projection landing exactly on the credit limit, at the scale the record requires.
     *
     * <p>Assumptions: the value is the one the oracle's {@code boundary_exact_limit} scenario turns
     * on. That scenario's account carries a 2000.00 limit reached by a 2065.00 projection under its
     * own fixture arithmetic; the figure used here only has to be a well-scaled projection, because
     * this class asserts the resolution of FINDINGS and the service under
     * {@code com.carddemo.batch.service} is what derives a finding from a comparison.</p>
     */
    private static final BigDecimal PROJECTION = new BigDecimal("2065.00");

    /** The expected code column value standing for an accepted transaction, which carries no reason. */
    private static final int ACCEPTED = 0;

    /**
     * Supplies the projection a set of findings is allowed to carry.
     *
     * <p>Assumptions: a projection exists exactly when both lookups succeeded, because the reference
     * forms {@code WS-TEMP-BAL} at {@code app/cbl/CBTRN02C.cbl:403-405} from fields of the account
     * record inside the account read's {@code NOT INVALID KEY} branch. A cross-reference failure never
     * reaches that branch, since the guard at {@code :372} stops the account lookup running, and an
     * account read that found nothing does not enter it.</p>
     *
     * @param crossReferenceMissing whether the card number resolved to no cross-reference row
     * @param accountMissing whether the account the cross-reference named could not be read
     * @return {@link #PROJECTION} when both lookups succeeded and a projection could therefore have
     *     been computed, or {@code null} when either lookup failed
     */
    private static BigDecimal projectionFor(boolean crossReferenceMissing, boolean accountMissing) {
        return crossReferenceMissing || accountMissing ? null : PROJECTION;
    }

    /**
     * Drives the resolving factory across every combination of the four findings.
     *
     * <p>The four boolean columns are the findings and the fifth column is the reason code expected to
     * survive them, with {@link #ACCEPTED} standing for a transaction that failed nothing. Sixteen rows
     * cover the whole input space, so no combination is left unasserted.</p>
     *
     * @param crossReferenceMissing whether the card number resolved to no cross-reference row, which
     *     the reference finds in the {@code INVALID KEY} branch at {@code app/cbl/CBTRN02C.cbl:384}
     * @param accountMissing whether the account could not be read, which the reference finds in the
     *     {@code INVALID KEY} branch at {@code :396}
     * @param overCreditLimit whether the projection was strictly greater than the credit limit, the
     *     strict complement of the inclusive PASS guard at {@code :407}
     * @param pastAccountExpiration whether the expiration date was strictly earlier than the
     *     transaction date, the strict complement of the inclusive PASS guard at {@code :414}
     * @param expectedCode the reason code the reference reports for that row, or {@link #ACCEPTED}
     *     when it reports none
     */
    @ParameterizedTest(name = "[{0},{1},{2},{3}] -> {4}")
    @DisplayName("resolve every combination of the four findings as the reference does")
    @CsvSource({
        // crossRefMissing, acctMissing, overLimit, pastExpiry, expectedCode
        "false, false, false, false,   0",
        "false, false, false,  true, 103",
        "false, false,  true, false, 102",
        "false, false,  true,  true, 103",
        "false,  true, false, false, 101",
        "false,  true, false,  true, 101",
        "false,  true,  true, false, 101",
        "false,  true,  true,  true, 101",
        " true, false, false, false, 100",
        " true, false, false,  true, 100",
        " true, false,  true, false, 100",
        " true, false,  true,  true, 100",
        " true,  true, false, false, 100",
        " true,  true, false,  true, 100",
        " true,  true,  true, false, 100",
        " true,  true,  true,  true, 100",
    })
    void resolvesEveryCombinationOfFindingsAsTheReferenceDoes(boolean crossReferenceMissing,
            boolean accountMissing, boolean overCreditLimit, boolean pastAccountExpiration,
            int expectedCode) {

        PostingValidationResult outcome = PostingValidationResult.resolve(crossReferenceMissing,
                accountMissing, overCreditLimit, pastAccountExpiration,
                projectionFor(crossReferenceMissing, accountMissing));

        if (expectedCode == ACCEPTED) {
            assertThat(outcome.isAccepted()).isTrue();
            assertThat(outcome.rejectReason()).isEmpty();
            return;
        }
        // WHY : Assumptions: the row is asserted on the CODE rather than on the constant, because the
        //       code is what the reject trailer carries and what a reader of that stream can act on.
        //       Two of the reference's reasons share byte-identical descriptions, so a text comparison
        //       would conflate them.
        assertThat(outcome.isRejected()).isTrue();
        assertThat(outcome.rejectReason()).isPresent();
        assertThat(outcome.rejectReason().orElseThrow().code()).isEqualTo(expectedCode);
    }

    /**
     * Verifies that a transaction failing BOTH boundary guards is reported as 103 and never as 102.
     *
     * <p>This is the regression guard against re-inverting the precedence, and it is stated as its own
     * method as well as a matrix row so that its purpose is unmistakable to whoever breaks it. The
     * reference's two boundary blocks at {@code app/cbl/CBTRN02C.cbl:407-420} are sequential and
     * unguarded: the first closes at {@code :413}, the second opens at {@code :414}, and nothing
     * between them tests whether a reason has already been assigned, so {@code MOVE 103} at
     * {@code :417} overwrites {@code MOVE 102} at {@code :410}. An either-or written in the order the
     * source reads would report 102 here.</p>
     */
    @Test
    @DisplayName("report 103 and NOT 102 when a transaction is both over limit and past expiry")
    void overLimitAndPastExpiryReportsOneHundredThreeAndNotOneHundredTwo() {
        PostingValidationResult outcome =
                PostingValidationResult.resolve(false, false, true, true, PROJECTION);

        assertThat(outcome.rejectReason())
                .contains(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);
        assertThat(outcome.rejectReason().orElseThrow().code()).isEqualTo(103);
        assertThat(outcome.rejectReason().orElseThrow())
                .as("an either-or in the source's reading order would report 102 here")
                .isNotSameAs(RejectReason.OVER_CREDIT_LIMIT);
    }

    /**
     * Verifies that the resolution does not depend on how the two boundary findings are ordered.
     *
     * <p>Assumptions: the resolution selects a maximum under
     * {@link RejectReason#baselineAssignmentOrder()} rather than branching, so the outcome is a
     * property of the reference's assignment order and not of the order the candidates appear in the
     * factory. Asserting the pair against each single condition is what pins that: 103 has to win over
     * the pair as well as over itself.</p>
     */
    @Test
    @DisplayName("resolve the boundary pair independently of the order the findings are written")
    void boundaryResolutionIsIndependentOfArgumentOrder() {
        PostingValidationResult both =
                PostingValidationResult.resolve(false, false, true, true, PROJECTION);
        PostingValidationResult expiryOnly =
                PostingValidationResult.resolve(false, false, false, true, PROJECTION);
        PostingValidationResult limitOnly =
                PostingValidationResult.resolve(false, false, true, false, PROJECTION);

        assertThat(both).isEqualTo(expiryOnly);
        assertThat(both).isNotEqualTo(limitOnly);
    }

    /**
     * Verifies that a cross-reference failure suppresses every later finding, per the guard at 372.
     *
     * <p>Assumptions: the reference performs the account lookup only while no reason has been
     * assigned, at {@code app/cbl/CBTRN02C.cbl:372}, so nothing downstream of a failed cross-reference
     * read is evaluated. The reason is therefore 100 even when the other three findings all hold, and
     * {@link RejectReason#terminatesValidation()} reports that short-circuit.</p>
     */
    @Test
    @DisplayName("report 100 and suppress the later findings when the cross-reference is missing")
    void crossReferenceFailureSuppressesEveryLaterFinding() {
        PostingValidationResult outcome =
                PostingValidationResult.resolve(true, true, true, true, null);

        assertThat(outcome.rejectReason())
                .contains(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);
        assertThat(outcome.rejectReason().orElseThrow().terminatesValidation()).isTrue();
        assertThat(outcome.projectedCycleBalance()).isNull();
    }

    /**
     * Verifies that a missing account excludes both boundary reasons, per the not-invalid-key nesting.
     *
     * <p>Assumptions: 101 is assigned in the {@code INVALID KEY} branch of the account read at
     * {@code app/cbl/CBTRN02C.cbl:396}, while both boundary tests sit in the {@code NOT INVALID KEY}
     * branch of that same read, so an account that was not found leaves neither balance nor date
     * evaluated.</p>
     */
    @Test
    @DisplayName("report 101 rather than a boundary reason when the account is missing")
    void missingAccountExcludesBothBoundaryReasons() {
        PostingValidationResult outcome =
                PostingValidationResult.resolve(false, true, true, true, null);

        assertThat(outcome.rejectReason()).contains(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
        assertThat(outcome.rejectReason().orElseThrow().code()).isEqualTo(101);
        assertThat(outcome.projectedCycleBalance()).isNull();
    }

    /**
     * Verifies the accepted state: no reason, both predicates agreeing, and the sentinel trailer.
     *
     * <p>Assumptions: the sentinel is four zero digits followed by 76 spaces, which is what the
     * reference's reset at {@code app/cbl/CBTRN02C.cbl:208-209} leaves in a
     * {@code PIC 9(04)} code field and a {@code PIC X(76)} description field. The oracle never
     * observes this form, because an accepted transaction reaches no reject record at all -- the
     * writer is performed only on the branch at {@code :215} -- so its emptiness is corroborated
     * instead by {@code tests/golden/posting/boundary_exact_limit/dalyrejs.expected}, which is an empty
     * file.</p>
     */
    @Test
    @DisplayName("render the four-zero and 76-space sentinel for an accepted transaction")
    void acceptedOutcomeCarriesNoReasonAndRendersTheSentinelTrailer() {
        PostingValidationResult outcome = PostingValidationResult.accepted(PROJECTION);

        assertThat(outcome.isAccepted()).isTrue();
        assertThat(outcome.isRejected()).isFalse();
        assertThat(outcome.rejectReason()).isEmpty();
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.trailerField()).isEqualTo("0000" + " ".repeat(76));
        assertThat(outcome.trailerField()).hasSize(80);
        assertThat(outcome.trailerField()).hasSize(RejectReason.TRAILER_WIDTH);
    }

    /**
     * Verifies that every reason the reference can persist renders an 80-character trailer.
     *
     * @param reason one of the reasons validation can report, supplied by the enumeration source so
     *     that a reason added later is asserted rather than left unchecked; the one reason assigned
     *     outside validation is skipped, because this record refuses to carry it at all
     */
    @ParameterizedTest
    @DisplayName("render an 80-character trailer for every persistable reason")
    @EnumSource(RejectReason.class)
    void everyPersistableReasonRendersTheContractWidth(RejectReason reason) {
        if (!reason.isPersistedToRejectStream()) {
            // WHY : Assumptions: the unpersistable reason is skipped rather than asserted, because
            //       this record's constructor refuses it outright -- see
            //       rejectsTheReasonAssignedOutsideValidation, which pins that refusal. Driving it
            //       through here would assert the refusal twice and obscure what this method covers.
            return;
        }
        BigDecimal projection = reason.terminatesValidation() ? null : PROJECTION;

        String trailer = PostingValidationResult.rejected(reason, projection).trailerField();

        assertThat(trailer).hasSize(RejectReason.TRAILER_WIDTH);
        assertThat(trailer).startsWith(reason.codeField());
        assertThat(trailer).isEqualTo(reason.codeField() + reason.descriptionField());
        assertThat(trailer.substring(RejectReason.CODE_WIDTH).strip())
                .isEqualTo(reason.description());
    }

    /**
     * Verifies the exact 80 characters each of the four validation reasons contributes.
     *
     * <p>Assumptions: each expected string is composed from a four-character zero-padded code and a
     * description typed from the literal the reference moves, padded to 76. The same four trailers
     * appear at characters 351 to 430 of the oracle's committed reject records under
     * {@code tests/golden/posting/reject_100_card_missing}, {@code reject_101_acct_missing},
     * {@code reject_102_overlimit} and {@code reject_103_expired}, each of which is a 430-character
     * record as {@code app/jcl/POSTTRAN.jcl:36} allocates.</p>
     */
    @Test
    @DisplayName("render the exact 80 characters of each validation reason's trailer")
    void rendersTheExactTrailerBytesForEachValidationReason() {
        assertThat(PostingValidationResult
                .rejected(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE, null).trailerField())
                .isEqualTo("0100" + "INVALID CARD NUMBER FOUND" + " ".repeat(51));
        assertThat(PostingValidationResult
                .rejected(RejectReason.ACCOUNT_NOT_FOUND_ON_READ, null).trailerField())
                .isEqualTo("0101" + "ACCOUNT RECORD NOT FOUND" + " ".repeat(52));
        assertThat(PostingValidationResult
                .rejected(RejectReason.OVER_CREDIT_LIMIT, PROJECTION).trailerField())
                .isEqualTo("0102" + "OVERLIMIT TRANSACTION" + " ".repeat(55));
        assertThat(PostingValidationResult
                .rejected(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION, PROJECTION).trailerField())
                .isEqualTo("0103" + "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" + " ".repeat(34));
    }

    /**
     * Verifies that the 80-character trailer is the second part of the 430-character reject record.
     *
     * <p>Assumptions: the widths compose as the reference declares them --
     * {@code REJECT-TRAN-DATA PIC X(350)} at {@code app/cbl/CBTRN02C.cbl:177} plus
     * {@code VALIDATION-TRAILER PIC X(80)} at {@code :178} is the {@code LRECL=430} that
     * {@code app/jcl/POSTTRAN.jcl:36} allocates. This record contributes only the second part, so the
     * assertion is on the trailer width and its place in that sum rather than on a whole record.</p>
     */
    @Test
    @DisplayName("compose the 80-character trailer into the 430-character reject record")
    void theTrailerIsTheEightyCharacterTailOfAFourHundredAndThirtyCharacterRecord() {
        assertThat(RejectReason.CODE_WIDTH + RejectReason.DESCRIPTION_WIDTH).isEqualTo(80);
        assertThat(RejectReason.TRAILER_WIDTH).isEqualTo(80);
        assertThat(350 + RejectReason.TRAILER_WIDTH).isEqualTo(430);

        assertThat(PostingValidationResult.resolve(false, false, true, false, PROJECTION)
                .trailerField()).hasSize(RejectReason.TRAILER_WIDTH);
        assertThat(PostingValidationResult.accepted(PROJECTION).trailerField())
                .hasSize(RejectReason.TRAILER_WIDTH);
    }

    /**
     * Verifies that a projection at the required scale is carried through unchanged.
     *
     * <p>Assumptions: the value is neither rescaled nor normalised, so the exact
     * {@link BigDecimal} the caller computed is what a later reader sees. Comparing with
     * {@code isEqualByComparingTo} would pass even if the scale had been altered, so the assertion is
     * on the scale and on object equality, which {@link BigDecimal#equals(Object)} makes
     * scale-sensitive.</p>
     */
    @Test
    @DisplayName("carry a scale-two projection through unchanged")
    void aProjectionAtTheRequiredScaleRoundTripsUnchanged() {
        BigDecimal supplied = new BigDecimal("2065.00");

        PostingValidationResult outcome = PostingValidationResult.accepted(supplied);

        assertThat(outcome.projectedCycleBalance()).isEqualTo(supplied);
        assertThat(outcome.projectedCycleBalance().scale())
                .isEqualTo(PostingValidationResult.PROJECTED_BALANCE_SCALE);
        assertThat(outcome.projectedCycleBalance().toPlainString()).isEqualTo("2065.00");
    }

    /**
     * Verifies that a projection at any scale other than two is refused rather than adjusted.
     *
     * @param supplied a projection whose scale is not two, written so that its scale is explicit in
     *     the literal; a value at scale 0 or 1 has lost the cents and a value at scale 3 carries a
     *     digit the reference's {@code PIC S9(09)V99} field has no room for
     */
    @ParameterizedTest
    @DisplayName("refuse a projection held at any scale other than two")
    @CsvSource({"2065", "2065.0", "2065.000", "0", "0.000"})
    void refusesAProjectionAtTheWrongScale(String supplied) {
        BigDecimal wrongScale = new BigDecimal(supplied);

        // WHY : Assumptions: the value is refused rather than rounded, because rescaling a projection
        //       that has already been computed would move the quantity the inclusive credit-limit
        //       guard at app/cbl/CBTRN02C.cbl:407 compares, and it would do so inside a constructor
        //       where the adjustment is invisible at the call site that produced the wrong scale.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingValidationResult.accepted(wrongScale))
                .withMessageContaining("scale");
    }

    /**
     * Verifies that a projection is required whenever the findings reached the boundary tests.
     *
     * <p>Assumptions: an outcome that reached the two boundary tests necessarily had a projection,
     * because the reference computes {@code WS-TEMP-BAL} at {@code app/cbl/CBTRN02C.cbl:403-405}
     * before either test runs. An absent projection on such an outcome is therefore an impossible
     * state rather than a permissible omission.</p>
     */
    @Test
    @DisplayName("require a projection for an outcome that reached the boundary tests")
    void requiresAProjectionWhenTheBoundaryTestsWereReached() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingValidationResult.accepted(null))
                .withMessageContaining("projectedCycleBalance");
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        PostingValidationResult.rejected(RejectReason.OVER_CREDIT_LIMIT, null))
                .withMessageContaining("projectedCycleBalance");
    }

    /**
     * Verifies that a projection is refused on an outcome whose reason ended validation early.
     *
     * @param reason a reason that ends validation before the account is read, so no projection could
     *     have been computed for it; supplied by the enumeration source and skipped for reasons that
     *     do not terminate validation
     */
    @ParameterizedTest
    @DisplayName("refuse a projection on an outcome whose reason ended validation early")
    @EnumSource(RejectReason.class)
    void refusesAProjectionWhenValidationEndedBeforeTheAccountRead(RejectReason reason) {
        if (!reason.terminatesValidation()) {
            return;
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingValidationResult.rejected(reason, PROJECTION))
                .withMessageContaining("projectedCycleBalance");
        assertThat(PostingValidationResult.rejected(reason, null).projectedCycleBalance()).isNull();
    }

    /**
     * Verifies that the reason assigned outside validation can never become a validation outcome.
     *
     * <p>Assumptions: the reference assigns reason 109 at {@code app/cbl/CBTRN02C.cbl:556}, in the
     * {@code INVALID KEY} branch of the account REWRITE inside {@code 2800-UPDATE-ACCOUNT-REC}, which
     * is reached only from {@code :441} on the posting path that {@code :212} enters when validation
     * assigned no reason at all. It describes a transaction that passed validation, posted, and then
     * could not be recorded, so it is a write failure rather than a validation result. No committed
     * expectation file under {@code tests/golden/posting/} carries {@code 0109} in the trailer's code
     * position.</p>
     */
    @Test
    @DisplayName("refuse the reason the reference assigns outside validation")
    void rejectsTheReasonAssignedOutsideValidation() {
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.isPersistedToRejectStream()).isFalse();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingValidationResult
                        .rejected(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE, PROJECTION));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PostingValidationResult
                        .rejected(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE, null));
    }

    /**
     * Verifies that no combination of findings can produce the reason assigned outside validation.
     *
     * <p>Assumptions: the resolving factory reaches only the four reasons the validation paragraphs
     * assign, so the unpersistable reason is unreachable through it by construction rather than by a
     * guard placed at the end. Driving all sixteen combinations is what turns that claim into an
     * assertion.</p>
     */
    @Test
    @DisplayName("never resolve to the reason assigned outside validation")
    void resolutionNeverYieldsTheReasonAssignedOutsideValidation() {
        for (int combination = 0; combination < 16; combination++) {
            boolean crossReferenceMissing = (combination & 1) != 0;
            boolean accountMissing = (combination & 2) != 0;
            boolean overCreditLimit = (combination & 4) != 0;
            boolean pastAccountExpiration = (combination & 8) != 0;

            PostingValidationResult outcome = PostingValidationResult.resolve(crossReferenceMissing,
                    accountMissing, overCreditLimit, pastAccountExpiration,
                    projectionFor(crossReferenceMissing, accountMissing));

            outcome.rejectReason().ifPresent(reason -> {
                assertThat(reason).isNotSameAs(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE);
                assertThat(reason.isPersistedToRejectStream()).isTrue();
                assertThat(reason.code()).isIn(100, 101, 102, 103);
            });
        }
    }

    /**
     * Verifies that the record carries no collection, no map and no array among its components.
     *
     * <p>Assumptions: the reference records a refusal in one field --
     * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:181}, with no
     * {@code OCCURS} and no table -- and the 80-character trailer has room for exactly one code and one
     * description. A component able to hold several reasons would make a state representable that
     * neither the reference nor the reject stream can express, so the shape is asserted by reflection
     * rather than trusted to review.</p>
     */
    @Test
    @DisplayName("carry no collection, map or array component")
    void carriesNoCollectionComponent() {
        RecordComponent[] components = PostingValidationResult.class.getRecordComponents();

        assertThat(components).hasSize(2);
        for (RecordComponent component : components) {
            Class<?> type = component.getType();
            assertThat(type.isArray())
                    .as("component %s must not be an array", component.getName())
                    .isFalse();
            assertThat(Collection.class.isAssignableFrom(type))
                    .as("component %s must not be a collection", component.getName())
                    .isFalse();
            assertThat(Iterable.class.isAssignableFrom(type))
                    .as("component %s must not be iterable", component.getName())
                    .isFalse();
        }
        assertThat(components[0].getType()).isEqualTo(RejectReason.class);
        assertThat(components[1].getType()).isEqualTo(BigDecimal.class);
    }

    /**
     * Verifies that value equality is the contract and that the factories return fresh instances.
     *
     * <p>Assumptions: two outcomes carrying the same reason and the same projection are equal, which
     * the record's generated members give directly, and nothing here has an identity worth
     * distinguishing. The accepted outcome is produced fresh rather than shared so that its projection
     * can differ between transactions, which is why identity is asserted to differ while value does
     * not.</p>
     */
    @Test
    @DisplayName("treat equal reason and projection as equal outcomes")
    void valueEqualityIsTheContractAndFactoriesReturnNewInstances() {
        PostingValidationResult first = PostingValidationResult.accepted(PROJECTION);
        PostingValidationResult second = PostingValidationResult.accepted(PROJECTION);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotSameAs(second);

        PostingValidationResult refused =
                PostingValidationResult.rejected(RejectReason.OVER_CREDIT_LIMIT, PROJECTION);
        assertThat(refused).isNotEqualTo(first);
        assertThat(PostingValidationResult.resolve(false, false, true, false, PROJECTION))
                .isEqualTo(refused);
    }

    /**
     * Verifies that the refusing factory rejects a null reason instead of treating it as acceptance.
     *
     * <p>Assumptions: the two outcomes are built through different factories deliberately, so letting
     * a null through here would make this a second and undocumented way to build an accepted outcome.
     * A caller that reached it by accident would report a posted transaction where it meant to report a
     * refused one.</p>
     */
    @Test
    @DisplayName("refuse a null reason rather than reading it as acceptance")
    void theRefusingFactoryRejectsANullReason() {
        assertThatNullPointerException()
                .isThrownBy(() -> PostingValidationResult.rejected(null, PROJECTION))
                .withMessageContaining("reason");
    }

    /**
     * Verifies that the two predicates are exact negations across every reachable outcome.
     *
     * <p>Assumptions: the pair mirrors the two branches the reference takes at
     * {@code app/cbl/CBTRN02C.cbl:211-216}, which are alternatives, so no outcome may answer the same
     * way to both. A pair that could both answer false is a state no caller tests for.</p>
     */
    @Test
    @DisplayName("keep the acceptance predicates exact negations of one another")
    void thePredicatesAreExactNegations() {
        for (int combination = 0; combination < 16; combination++) {
            boolean crossReferenceMissing = (combination & 1) != 0;
            boolean accountMissing = (combination & 2) != 0;
            boolean overCreditLimit = (combination & 4) != 0;
            boolean pastAccountExpiration = (combination & 8) != 0;

            PostingValidationResult outcome = PostingValidationResult.resolve(crossReferenceMissing,
                    accountMissing, overCreditLimit, pastAccountExpiration,
                    projectionFor(crossReferenceMissing, accountMissing));

            assertThat(outcome.isRejected()).isNotEqualTo(outcome.isAccepted());
            assertThat(outcome.isAccepted()).isEqualTo(outcome.rejectReason().isEmpty());
        }
    }

    /**
     * Verifies both boundary framings, which are inclusive on the passing side in the reference.
     *
     * <p>Assumptions: each boundary is written as a PASS guard using {@code &gt;=}, so each reject
     * predicate is the strict complement. A projection landing exactly ON the limit posts and only a
     * strictly greater one rejects, per {@code app/cbl/CBTRN02C.cbl:407}; a transaction dated exactly
     * ON the expiration date posts and only a strictly earlier expiration rejects, per {@code :414}.
     * The oracle pins both senses independently, since
     * {@code tests/golden/posting/boundary_exact_limit/dalyrejs.expected} and
     * {@code tests/golden/posting/boundary_expiry_equal/dalyrejs.expected} are each EMPTY, whereas
     * {@code tests/golden/posting/reject_102_overlimit} holds records carrying {@code 0102}.</p>
     *
     * <p>Assumptions: this record resolves FINDINGS rather than performing the comparisons, so the
     * inclusive boundary appears here as the finding being {@code false} at equality. The comparison
     * itself belongs to {@code com.carddemo.batch.service}.</p>
     */
    @Test
    @DisplayName("post at exactly the limit and on the expiration date, reject only strictly beyond")
    void bothBoundariesAreInclusiveOnThePassingSide() {
        // WHY : Assumptions: equality is expressed as the finding being false, because the finding IS
        //       the strict complement of the inclusive guard. Passing true here would describe a
        //       projection strictly beyond the limit, which is the reject_102_overlimit scenario and
        //       not the boundary_exact_limit one.
        assertThat(PostingValidationResult.resolve(false, false, false, false, PROJECTION)
                .isAccepted())
                .as("exactly at the limit posts, as boundary_exact_limit expects an empty stream")
                .isTrue();
        assertThat(PostingValidationResult.resolve(false, false, true, false, PROJECTION)
                .rejectReason())
                .as("one cent beyond the limit rejects with 102, as reject_102_overlimit expects")
                .contains(RejectReason.OVER_CREDIT_LIMIT);

        assertThat(PostingValidationResult.resolve(false, false, false, false, PROJECTION)
                .isAccepted())
                .as("a transaction dated on the expiration date posts, per boundary_expiry_equal")
                .isTrue();
        assertThat(PostingValidationResult.resolve(false, false, false, true, PROJECTION)
                .rejectReason())
                .as("one day past expiration rejects with 103, as reject_103_expired expects")
                .contains(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);
    }
}
