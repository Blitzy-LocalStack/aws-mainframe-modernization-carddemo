package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds {@link RejectReason} to the reject-stream contract: five codes, their verbatim
 * descriptions, the fixed widths they render at, and the precedence that decides which reason
 * survives when two conditions hold at once.
 *
 * <p>Assumptions: the assertion that matters most in this class is
 * {@link #expirationBeatsOverLimitBecauseTheBaselineAssignmentsAreUnguarded()}. The rule it pins is
 * expressed in the reference program as an ABSENCE -- there is no guard between the credit-limit
 * assignment at {@code app/cbl/CBTRN02C.cbl:410} and the expiration assignment at {@code :417}, so
 * the second overwrites the first. An implementation written as a chain that stops at its first
 * match reports the other reason, compiles cleanly, and passes every test that trips only one
 * condition. That is the single defect this class exists to catch.</p>
 *
 * <p>Alternatives Considered: asserting the renderings against values chosen to read well. Rejected
 * because these renderings are bytes in a data file rather than presentation, so an expectation
 * edited by whoever changes the code records agreement with the author instead of agreement with
 * the contract. Every expectation here is pinned to one of two independent authorities: a string
 * literal typed from the reference program, or the committed expectation files of the
 * functional-parity oracle under {@code tests/golden/posting/}. The two are asserted separately, in
 * {@link #reportsTheVerbatimDescriptions()} and
 * {@link #trailerMatchesTheCommittedRejectExpectations()}, so neither one alone can bless a
 * regression.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own.</p>
 */
class RejectReasonTest {

    /**
     * The number of reasons the reference program's vocabulary declares, five.
     *
     * <p>Assumptions: the count is asserted rather than assumed because a sixth constant would
     * compile cleanly and could then leak into the reject stream. In particular a constant standing
     * for "accepted" would carry the code zero, and this type models an accepted transaction as the
     * absence of a reason instead.</p>
     */
    private static final int DECLARED_REASON_COUNT = 5;

    /**
     * The code the reference program uses to mean "no reason", zero.
     *
     * <p>Assumptions: {@code app/cbl/CBTRN02C.cbl:208-209} resets the reason field to this value
     * with a blank description before each record, so it is the sentinel for an accepted
     * transaction and must not be claimed by any constant.</p>
     */
    private static final int ACCEPTED_SENTINEL_CODE = 0;

    /**
     * The character length of the longest of the five descriptions, 42.
     *
     * <p>Assumptions: the longest is the expiration description at
     * {@code app/cbl/CBTRN02C.cbl:418}. Naming the measured value here is what makes
     * {@link #noDescriptionExceedsTheFieldWidth(RejectReason)} meaningful: it shows how much
     * headroom the 76-character field has, so a future description can be added without silently
     * consuming all of it.</p>
     */
    private static final int LONGEST_DESCRIPTION_LENGTH = 42;

    /**
     * The one-based character offset at which the trailer begins in a reject record, 351.
     *
     * <p>Assumptions: the trailer follows the 350-character transaction image declared at
     * {@code app/cbl/CBTRN02C.cbl:177}, so it starts at character 351 of the 430-character record
     * that {@code app/jcl/POSTTRAN.jcl:36} allocates.</p>
     */
    private static final int TRAILER_START_OFFSET_ONE_BASED = 351;

    /**
     * The oracle expectation files that carry one rejected record each.
     *
     * <p>Assumptions: these four scenario directories are the ones whose reject stream is
     * non-empty, one per persisted reason. The two boundary scenarios are deliberately absent
     * because their expectation files are empty, which is itself asserted by
     * {@link #boundaryScenariosExpectNoRejectAtAll()}.</p>
     */
    private static final List<String> REJECT_EXPECTATION_SCENARIOS = List.of(
            "reject_100_card_missing",
            "reject_101_acct_missing",
            "reject_102_overlimit",
            "reject_103_expired");

    /**
     * The oracle scenarios whose transaction sits exactly on a boundary and therefore posts.
     *
     * <p>Assumptions: both boundary guards in the reference are inclusive on the passing side, so a
     * projection landing exactly on the credit limit and a transaction dated exactly on the
     * expiration date both post and neither writes a reject record.</p>
     */
    private static final List<String> BOUNDARY_POSTING_SCENARIOS = List.of(
            "boundary_exact_limit",
            "boundary_expiry_equal");

    /**
     * Verifies that each reason carries its description exactly as the reference program writes it.
     */
    @Test
    void reportsTheVerbatimDescriptions() {
        // WHY : Assumptions: these five literals are typed from the reference program's own MOVE
        //       statements at app/cbl/CBTRN02C.cbl:386, :398, :411, :418 and :557, and they are
        //       compared for exact equality rather than for containment. Containment would accept a
        //       description that had acquired a prefix, a suffix or trailing punctuation, and the
        //       reject stream is compared byte for byte, so any of those would be a parity failure
        //       that this assertion had passed over. Three spellings are load-bearing and are
        //       reproduced deliberately: OVERLIMIT is one word, ACCT is abbreviated only in the
        //       expiration description, and the cross-reference description ends in FOUND while
        //       describing a row that was not found.
        assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.description())
                .isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.description())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(RejectReason.OVER_CREDIT_LIMIT.description())
                .isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.description())
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.description())
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
    }

    /**
     * Verifies that each reason reports the numeric code the reference program assigns for it.
     */
    @Test
    void reportsTheContractCodes() {
        assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.code()).isEqualTo(100);
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.code()).isEqualTo(101);
        assertThat(RejectReason.OVER_CREDIT_LIMIT.code()).isEqualTo(102);
        assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.code()).isEqualTo(103);
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.code()).isEqualTo(109);
    }

    /**
     * Verifies that the two account reasons share their description text but not their code.
     */
    @Test
    void theTwoAccountReasonsShareTheirTextButNotTheirCode() {
        // WHY : Assumptions: this is the assertion that stops the two constants being merged. The
        //       reference assigns 101 when the account READ finds nothing at
        //       app/cbl/CBTRN02C.cbl:397 and 109 when the account REWRITE finds nothing at :556,
        //       and it writes the same description for both. Both halves are asserted because each
        //       one alone invites the wrong conclusion: equal text on its own reads as duplication
        //       to be collapsed, and unequal codes on their own give no hint that the descriptions
        //       coincide. Together they state that a description does not identify a reason, which
        //       is why callers compare code() instead.
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.description())
                .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.description());
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.code())
                .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.code());
    }

    /**
     * Verifies that a transaction which is both over limit and past expiration reports the
     * expiration reason, because the reference program's two assignments are unguarded and the
     * later one overwrites the earlier.
     *
     * <p>This is the regression guard against re-inverting the precedence. An implementation written
     * as a chain that returns on its first matching condition, in the order the constants are
     * declared, would report {@link RejectReason#OVER_CREDIT_LIMIT} where the reference reports
     * {@link RejectReason#RECEIVED_AFTER_ACCOUNT_EXPIRATION}, and the reject stream is compared byte
     * for byte against the oracle's expectation files.</p>
     */
    @Test
    void expirationBeatsOverLimitBecauseTheBaselineAssignmentsAreUnguarded() {
        RejectReason overLimit = RejectReason.OVER_CREDIT_LIMIT;
        RejectReason pastExpiration = RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION;

        // WHY : Assumptions: the rule is asserted in BOTH argument orders because the reference's
        //       outcome does not depend on which condition a caller happens to evaluate first. In
        //       the reference the order is determined by the source -- the credit-limit block at
        //       app/cbl/CBTRN02C.cbl:407-413 precedes the expiration block at :414-420 with no
        //       guard between them -- so the later assignment always wins. A pairwise operation
        //       that answered differently depending on the receiver would reintroduce exactly the
        //       ordering sensitivity this method exists to remove.
        assertThat(overLimit.lastWriterWins(pastExpiration)).isSameAs(pastExpiration);
        assertThat(pastExpiration.lastWriterWins(overLimit)).isSameAs(pastExpiration);

        // WHY : Assumptions: the comparator is asserted on the same pair as well, because a caller
        //       that collects the tripped conditions resolves them with a maximum rather than with a
        //       pairwise call, and the two routes have to agree. Selecting the maximum is the
        //       expression the service layer uses, so it is the one a regression would travel
        //       through.
        assertThat(List.of(overLimit, pastExpiration).stream()
                .max(RejectReason.baselineAssignmentOrder()))
                .contains(pastExpiration);
        assertThat(pastExpiration.code()).isEqualTo(103);
    }

    /**
     * Verifies that the pairwise precedence agrees with the comparator for every ordered pair of
     * reasons, in both argument orders.
     *
     * @param reason one of the five constants, supplied by the enumeration source so that a reason
     *     added later is compared against all the others rather than left unasserted
     */
    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void precedenceAgreesWithTheComparatorForEveryPair(RejectReason reason) {
        // WHY : Assumptions: the pairwise operation and the comparator are two expressions of one
        //       rule, and nothing in the compiler relates them. Asserting the equivalence across
        //       every pair is what keeps them from drifting, which would otherwise show up only as
        //       a caller that reported a different reason depending on which of the two it used.
        for (RejectReason other : RejectReason.values()) {
            RejectReason expected = List.of(reason, other).stream()
                    .max(RejectReason.baselineAssignmentOrder())
                    .orElseThrow();
            assertThat(reason.lastWriterWins(other)).isSameAs(expected);
            assertThat(other.lastWriterWins(reason)).isSameAs(expected);
        }
    }

    /**
     * Verifies that a reason compared with itself is the reason that wins.
     *
     * @param reason one of the five constants, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void precedenceOfAReasonAgainstItselfIsThatReason(RejectReason reason) {
        assertThat(reason.lastWriterWins(reason)).isSameAs(reason);
    }

    /**
     * Verifies that selecting the greatest of no tripped reason yields an empty result, which is how
     * an accepted transaction is represented.
     */
    @Test
    void selectingTheMaximumOfNoTrippedReasonYieldsTheAcceptedTransaction() {
        // WHY : Assumptions: this is the behaviour that makes a sixth "accepted" constant
        //       unnecessary. The reference marks an accepted record by leaving the reason field at
        //       the zero it was reset to at app/cbl/CBTRN02C.cbl:208, and the migrated equivalent is
        //       an absent value, so the accepted case falls out of the same expression that resolves
        //       a rejected one instead of needing a branch of its own.
        assertThat(List.<RejectReason>of().stream().max(RejectReason.baselineAssignmentOrder()))
                .isEmpty();
    }

    /**
     * Verifies that the precedence rejects a null competitor rather than treating it as an absent
     * reason.
     */
    @Test
    void lastWriterWinsRejectsANullCompetitor() {
        // WHY : Assumptions: an absent reason is modelled as an empty optional, not as a null
        //       argument, so accepting null here would create a second representation of the
        //       accepted transaction. Refusing it keeps that fault at the boundary it entered rather
        //       than letting it travel into a rendered trailer.
        assertThatNullPointerException()
                .isThrownBy(() -> RejectReason.OVER_CREDIT_LIMIT.lastWriterWins(null));
    }

    /**
     * Verifies that each reason renders its code zero-padded to exactly four characters.
     */
    @Test
    void rendersTheCodeZeroPaddedToFourCharacters() {
        // WHY : Assumptions: the field is numeric-display, WS-VALIDATION-FAIL-REASON PIC 9(04) at
        //       app/cbl/CBTRN02C.cbl:181, so a three-digit code is padded with a leading ZERO and
        //       not with a leading blank. The exact strings are asserted rather than only the width,
        //       because a blank-padded rendering is also four characters wide and would satisfy a
        //       width-only check while differing from the oracle's bytes in the first character of
        //       the trailer.
        assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.codeField()).isEqualTo("0100");
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.codeField()).isEqualTo("0101");
        assertThat(RejectReason.OVER_CREDIT_LIMIT.codeField()).isEqualTo("0102");
        assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.codeField()).isEqualTo("0103");
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.codeField()).isEqualTo("0109");
    }

    /**
     * Verifies that every code rendering occupies exactly the declared field width.
     *
     * @param reason one of the five constants, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void codeFieldIsAlwaysExactlyTheDeclaredWidth(RejectReason reason) {
        assertThat(reason.codeField()).hasSize(RejectReason.CODE_WIDTH);
        assertThat(RejectReason.CODE_WIDTH).isEqualTo(4);
    }

    /**
     * Verifies that the code rendering carries Latin digits whatever the ambient default locale is.
     */
    @Test
    void rendersLatinDigitsWhateverTheDefaultLocaleIs() {
        // WHY : Assumptions: the conversion of a numeric format specifier is locale-sensitive, so
        //       formatting without an explicit locale renders the digits of whatever numbering
        //       system the default locale names. Under the three locales below the same call would
        //       produce Arabic-Indic, Devanagari and Bengali digits, which would put non-ASCII
        //       characters into a data file that is compared byte for byte. The default locale of a
        //       container is not this module's to assume, so the rendering pins the locale and this
        //       test proves the pin holds.
        // WHY : Trade-offs: the default locale is process-global state, so this test mutates it and
        //       restores it in a finally block. That is acceptable here because no parallel
        //       execution is configured for this module -- there is no junit-platform.properties and
        //       no execution annotation anywhere in these trees -- so the tests run sequentially and
        //       the window in which the default differs is confined to this method. Asserting
        //       against a formatter built with the same locale would have been side-effect free and
        //       would also have been tautological, since it would restate the implementation rather
        //       than test it.
        Locale ambientDefault = Locale.getDefault();
        try {
            for (String nonLatinTag
                    : List.of("ar-EG-u-nu-arab", "hi-IN-u-nu-deva", "bn-IN-u-nu-beng")) {
                Locale.setDefault(Locale.forLanguageTag(nonLatinTag));
                assertThat(RejectReason.OVER_CREDIT_LIMIT.codeField())
                        .as("code field under default locale %s", nonLatinTag)
                        .isEqualTo("0102");
            }
        } finally {
            Locale.setDefault(ambientDefault);
        }
    }

    /**
     * Verifies that each reason renders its description space-padded on the right to exactly the
     * declared field width, with the verbatim text as its untrimmed prefix.
     *
     * @param reason one of the five constants, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void rendersTheDescriptionSpacePaddedToTheDeclaredWidth(RejectReason reason) {
        String rendered = reason.descriptionField();

        // WHY : Assumptions: the field is alphanumeric, WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at
        //       app/cbl/CBTRN02C.cbl:182, and a COBOL move into an alphanumeric target
        //       left-justifies and space-fills. All three properties are asserted separately: the
        //       exact width, because a wider or narrower field displaces every following byte of
        //       the record; the prefix, because padding on the wrong side would still be 76
        //       characters; and the padding character, because a null or zero fill would also
        //       satisfy the first two.
        assertThat(rendered).hasSize(RejectReason.DESCRIPTION_WIDTH);
        assertThat(RejectReason.DESCRIPTION_WIDTH).isEqualTo(76);
        assertThat(rendered).startsWith(reason.description());

        // WHY : Assumptions: the padding is compared against a run of spaces of the exact remaining
        //       length rather than tested for being whitespace. The equality is the stronger claim
        //       -- it rejects a tab or a no-break space, either of which would read as whitespace
        //       and occupy the width while differing from the reference's space fill byte for byte
        //       -- and unlike a whitespace predicate it also holds for a description that filled the
        //       field exactly, where the remainder is empty.
        assertThat(rendered.substring(reason.description().length()))
                .isEqualTo(" ".repeat(RejectReason.DESCRIPTION_WIDTH
                        - reason.description().length()));
    }

    /**
     * Verifies that no description is wide enough to be truncated by the field it is written into.
     *
     * @param reason one of the five constants, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void noDescriptionExceedsTheFieldWidth(RejectReason reason) {
        // WHY : Assumptions: a description longer than the field would be silently cut short by the
        //       reference's own move and would have nowhere to go in the migrated rendering either,
        //       so the headroom is asserted rather than trusted. The longest of the five is the
        //       expiration description at 42 characters against a field of 76, so a future
        //       description has 34 characters of room; stating the measured maximum is what lets a
        //       later author see how much.
        assertThat(reason.description().length())
                .as("description of %s must fit the %d-character field",
                        reason.name(), RejectReason.DESCRIPTION_WIDTH)
                .isLessThanOrEqualTo(RejectReason.DESCRIPTION_WIDTH)
                .isLessThanOrEqualTo(LONGEST_DESCRIPTION_LENGTH);
    }

    /**
     * Verifies that the longest description is the expiration one, at its measured length.
     */
    @Test
    void theLongestDescriptionIsTheExpirationOne() {
        assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.description())
                .hasSize(LONGEST_DESCRIPTION_LENGTH);
    }

    /**
     * Verifies that the whole trailer renders at exactly the sum of its two field widths.
     *
     * @param reason one of the five constants, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void rendersTheWholeTrailerAtTheSummedWidth(RejectReason reason) {
        // WHY : Assumptions: the sum is what makes the record cohere -- 4 plus 76 is the
        //       VALIDATION-TRAILER PIC X(80) at app/cbl/CBTRN02C.cbl:178, and 350 plus 80 is the
        //       LRECL=430 that app/jcl/POSTTRAN.jcl:36 allocates. The identity is asserted here as
        //       well as declared in the type, so a width changed on one side alone fails a test
        //       rather than producing records of the wrong length.
        assertThat(reason.trailerField()).hasSize(RejectReason.TRAILER_WIDTH);
        assertThat(RejectReason.TRAILER_WIDTH).isEqualTo(80);
        assertThat(RejectReason.TRAILER_WIDTH)
                .isEqualTo(RejectReason.CODE_WIDTH + RejectReason.DESCRIPTION_WIDTH);
        assertThat(reason.trailerField())
                .isEqualTo(reason.codeField() + reason.descriptionField());
    }

    /**
     * Verifies that the rendered trailer reproduces, byte for byte, the trailer of every rejected
     * record in the functional-parity oracle's committed expectation files.
     *
     * @throws IOException if an expectation file cannot be read from the repository tree
     */
    @Test
    void trailerMatchesTheCommittedRejectExpectations() throws IOException {
        Map<Integer, String> trailerByCode = committedRejectTrailersByCode();

        // WHY : Assumptions: this is the strongest available check on the renderings, because the
        //       expectation files were produced by the reference program itself rather than by this
        //       module, so they are an authority this code cannot edit into agreement with. The
        //       comparison covers the four persisted reasons; the fifth cannot appear, which
        //       onlyTheAccountRewriteReasonIsNeverPersisted asserts from the other side.
        assertThat(trailerByCode).hasSize(4);
        assertThat(trailerByCode.keySet()).containsExactly(100, 101, 102, 103);

        trailerByCode.forEach((code, committedTrailer) -> {
            RejectReason reason = RejectReason.fromCode(code).orElseThrow();
            assertThat(reason.trailerField())
                    .as("rendered trailer for reason %d must equal the committed expectation", code)
                    .isEqualTo(committedTrailer);
        });
    }

    /**
     * Verifies that the code the reference cannot write appears in no committed expectation file.
     *
     * @throws IOException if an expectation file cannot be read from the repository tree
     */
    @Test
    void theRewriteReasonAppearsInNoCommittedExpectation() throws IOException {
        // WHY : Assumptions: the reachability argument for reason 109 is a claim about control flow,
        //       and this assertion is its independent corroboration from data. If the reference
        //       could reach the reject writer with that code, a rendering of it would appear among
        //       the expectation files the reference produced; none does.
        assertThat(committedRejectTrailersByCode())
                .doesNotContainKey(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.code());
    }

    /**
     * Verifies that the two boundary scenarios write no reject record at all, pinning the inclusive
     * sense of both boundary guards.
     *
     * @throws IOException if an expectation file cannot be read from the repository tree
     */
    @Test
    void boundaryScenariosExpectNoRejectAtAll() throws IOException {
        // WHY : Assumptions: both guards in the reference pass on greater-than-or-equal --
        //       app/cbl/CBTRN02C.cbl:407 for the credit limit and :414 for the expiration date --
        //       so a projection landing exactly on the limit and a transaction dated exactly on the
        //       expiration date both POST. An empty expectation file is the only outcome consistent
        //       with that reading, which is why the emptiness is asserted rather than assumed: it is
        //       the evidence behind the dual framing recorded on the two boundary constants.
        for (String scenario : BOUNDARY_POSTING_SCENARIOS) {
            assertThat(rejectRecordsOf(scenario))
                    .as("scenario %s posts on the boundary and writes no reject", scenario)
                    .isEmpty();
        }
    }

    /**
     * Verifies that exactly one reason is excluded from the reject stream, and that it is the one
     * the reference assigns during the account rewrite.
     */
    @Test
    void onlyTheAccountRewriteReasonIsNeverPersisted() {
        // WHY : Assumptions: all five are asserted rather than sampled, because the value of this
        //       predicate is that it defines the sibling entity's narrower persisted domain in terms
        //       of this type's wider vocabulary. A change that flipped any one of the five would
        //       put the two files into disagreement, and nothing in the compiler relates them.
        assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.isPersistedToRejectStream())
                .isTrue();
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.isPersistedToRejectStream()).isTrue();
        assertThat(RejectReason.OVER_CREDIT_LIMIT.isPersistedToRejectStream()).isTrue();
        assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.isPersistedToRejectStream())
                .isTrue();
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.isPersistedToRejectStream()).isFalse();
    }

    /**
     * Verifies that the persisted reasons are exactly the four codes the sibling reject entity
     * declares as its domain.
     */
    @Test
    void thePersistedReasonsAreTheFourCodesTheRejectEntityDeclares() {
        List<Integer> persistedCodes = new ArrayList<>();
        for (RejectReason reason : RejectReason.values()) {
            if (reason.isPersistedToRejectStream()) {
                persistedCodes.add(reason.code());
            }
        }
        assertThat(persistedCodes).containsExactly(100, 101, 102, 103);
    }

    /**
     * Verifies that validation stops on the two lookup failures and continues on the two boundary
     * failures.
     */
    @Test
    void validationTerminatesOnlyOnTheTwoLookupFailures() {
        // WHY : Assumptions: the boundary pair is the half that carries this test. The reference
        //       guards the account lookup with IF WS-VALIDATION-FAIL-REASON = 0 at
        //       app/cbl/CBTRN02C.cbl:372, so a cross-reference failure ends validation, but the two
        //       boundary blocks at :407 and :414 have no such guard between them -- which is
        //       precisely why the second can overwrite the first. A predicate that answered true for
        //       the over-limit reason would contradict the precedence rule this type also carries.
        assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.terminatesValidation()).isTrue();
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.terminatesValidation()).isTrue();
        assertThat(RejectReason.OVER_CREDIT_LIMIT.terminatesValidation()).isFalse();
        assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.terminatesValidation()).isFalse();
        assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.terminatesValidation()).isFalse();
    }

    /**
     * Verifies that the vocabulary declares exactly five reasons.
     */
    @Test
    void declaresExactlyFiveReasons() {
        assertThat(RejectReason.values()).hasSize(DECLARED_REASON_COUNT);
    }

    /**
     * Verifies that no reason claims the code the reference uses to mean "accepted".
     *
     * @param reason one of the five constants, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void noReasonClaimsTheAcceptedSentinelCode(RejectReason reason) {
        // WHY : Assumptions: this is the assertion that keeps an "accepted" constant out of the
        //       type. Such a constant would carry the sentinel code, and every member of this type
        //       answers a question about a rejected transaction -- so it would render a well-formed
        //       trailer for a record the reference never writes.
        assertThat(reason.code()).isNotEqualTo(ACCEPTED_SENTINEL_CODE);
    }

    /**
     * Verifies that every declared code resolves back to the reason that declares it.
     *
     * @param reason one of the five constants, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(RejectReason.class)
    void everyCodeRoundTripsThroughTheFactory(RejectReason reason) {
        // WHY : Assumptions: identity is asserted rather than equality, because an enumeration
        //       constant is a singleton and a resolution that produced an equal-but-distinct value
        //       would mean the lookup had constructed something instead of finding it.
        assertThat(RejectReason.fromCode(reason.code())).containsSame(reason);
    }

    /**
     * Verifies that a code no reason claims resolves to an empty result rather than raising.
     *
     * @param unclaimedCode a code outside the declared vocabulary, including the accepted sentinel,
     *     a value inside the numeric gap between the declared codes, and a negative value
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 104, 105, 108, 99, 110, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void unclaimedCodesResolveToAnEmptyResult(int unclaimedCode) {
        // WHY : Assumptions: the accepted sentinel is deliberately among these values. It is the
        //       most common value the reference's reason field holds across a run, because most
        //       transactions post, so a resolution that raised on it would raise on the ordinary
        //       case and force every caller to test for it first.
        assertThat(RejectReason.fromCode(unclaimedCode)).isEmpty();
    }

    /**
     * Verifies that the accepted sentinel resolves to an empty result specifically, so that an
     * accepted transaction and an absent reason are the same thing.
     */
    @Test
    void theAcceptedSentinelResolvesToNoReason() {
        Optional<RejectReason> resolved = RejectReason.fromCode(ACCEPTED_SENTINEL_CODE);
        assertThat(resolved).isEmpty();
    }

    /**
     * Reads the 80-character trailer of every rejected record in the oracle's expectation files,
     * indexed by the numeric reason code it carries.
     *
     * @return a map from reason code to committed trailer text, in ascending code order, holding one
     *     entry per scenario that expects a reject; never {@code null}
     * @throws IOException if an expectation file cannot be read from the repository tree
     */
    private static Map<Integer, String> committedRejectTrailersByCode() throws IOException {
        Map<Integer, String> trailerByCode = new LinkedHashMap<>();
        for (String scenario : REJECT_EXPECTATION_SCENARIOS) {
            for (String record : rejectRecordsOf(scenario)) {
                String trailer = record.substring(TRAILER_START_OFFSET_ONE_BASED - 1,
                        TRAILER_START_OFFSET_ONE_BASED - 1 + RejectReason.TRAILER_WIDTH);
                trailerByCode.put(
                        Integer.parseInt(trailer.substring(0, RejectReason.CODE_WIDTH)),
                        trailer);
            }
        }
        return trailerByCode;
    }

    /**
     * Reads the reject records the oracle expects for one posting scenario.
     *
     * @param scenario the name of a scenario directory under the oracle's posting expectations, for
     *     example {@code reject_102_overlimit}
     * @return the expected reject records for that scenario, each a full fixed-width record, empty
     *     when the scenario expects no reject at all; never {@code null}
     * @throws IOException if the expectation file cannot be read from the repository tree
     */
    private static List<String> rejectRecordsOf(String scenario) throws IOException {
        // WHY : Assumptions: the file is decoded as ISO-8859-1 rather than as UTF-8. These are
        //       fixed-width records whose character positions are the contract, and ISO-8859-1 is
        //       the one charset that maps every byte to exactly one character, so a character offset
        //       stays equal to a byte offset. A multi-byte decoding would shift every offset after
        //       the first non-ASCII byte and could substitute a replacement character for one that
        //       did not decode.
        Path expectation = oracleRejectExpectation(scenario);
        List<String> records = new ArrayList<>();
        for (String line : Files.readAllLines(expectation, StandardCharsets.ISO_8859_1)) {
            if (!line.isBlank()) {
                records.add(line);
            }
        }
        return records;
    }

    /**
     * Locates one posting scenario's reject expectation file inside the repository.
     *
     * @param scenario the name of a scenario directory under the oracle's posting expectations
     * @return the path of that scenario's reject expectation file, which is asserted to exist;
     *     never {@code null}
     */
    private static Path oracleRejectExpectation(String scenario) {
        Path expectation = repositoryRoot()
                .resolve(Path.of("tests", "golden", "posting", scenario, "dalyrejs.expected"));

        // WHY : Assumptions: a missing expectation file is asserted rather than skipped over. A
        //       skipped comparison reads as a pass, so the one check that pins these renderings to
        //       an authority outside this module would go quiet exactly when it stopped running.
        assertThat(expectation)
                .as("oracle reject expectation for scenario %s", scenario)
                .exists();
        return expectation;
    }

    /**
     * Locates the repository root by walking up from the directory the test process runs in.
     *
     * @return the first ancestor directory, starting with the working directory itself, that holds
     *     both the reference posting program and the oracle's posting expectations; never
     *     {@code null}
     * @throws IllegalStateException if no ancestor of the working directory holds both markers,
     *     which means the test is running outside a checkout of this repository; the message names
     *     the directory searched from and both markers looked for
     */
    private static Path repositoryRoot() {
        // WHY : Assumptions: the root is discovered rather than reached with a preset number of
        //       parent steps. The test runner starts each module in its own base directory, so a
        //       literal pair of parent steps is correct for a module build and wrong for anything
        //       started elsewhere; walking up until two independent markers are both present holds
        //       either way. Both markers are required because either alone appears in more than one
        //       place in this tree.
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            boolean hasReferenceProgram =
                    Files.isRegularFile(candidate.resolve(Path.of("app", "cbl", "CBTRN02C.cbl")));
            boolean hasOracleExpectations =
                    Files.isDirectory(candidate.resolve(Path.of("tests", "golden", "posting")));
            if (hasReferenceProgram && hasOracleExpectations) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "repository root not found above " + Path.of("").toAbsolutePath()
                        + "; expected an ancestor holding both app/cbl/CBTRN02C.cbl and"
                        + " tests/golden/posting");
    }
}
