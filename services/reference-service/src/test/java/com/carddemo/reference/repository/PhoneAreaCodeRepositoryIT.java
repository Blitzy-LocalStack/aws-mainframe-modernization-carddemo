package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.reference.domain.UsPhoneAreaCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that the classification-scoped predicate partitions the allow-list as the baseline does.
 *
 * <p><b>Purpose.</b> {@code app/cbl/COACTUPC.cbl} line 2298 tests {@code IF VALID-GENERAL-PURP-CODE},
 * the 410-literal condition name at {@code app/cpy/CSLKPCDY.cpy} line 521, and not the 490-literal
 * union at line 30. This class asserts that {@code PhoneAreaCodeRepository} draws the same line: the
 * predicate holds for a general-purpose code and does NOT hold for an easily-recognisable one, even
 * though both are members of the union. That single pair of assertions is what protects functional
 * parity, because a predicate written against the union would pass every other check here while
 * accepting the 80 codes the baseline declines.
 *
 * <p>Assumptions: the two codes used below are read from the copybook rather than chosen for
 * convenience. {@code '201'} is the FIRST literal of the general-purpose list at line 521 and appears
 * nowhere in the easily-recognisable list; {@code '200'} is the first literal of the
 * easily-recognisable list at line 931 and appears nowhere in the general-purpose list. Both were
 * confirmed absent from the other list before being used here, so neither assertion can pass because
 * a code happened to carry both classifications -- a state the schema makes unrepresentable and this
 * class therefore does not have to guard.
 *
 * <p>Trade-offs: this class covers one interface and the sibling {@code UsPhoneAreaCodeRepositoryIT}
 * covers the other, rather than both being folded into one file over the shared table. The split
 * follows this package's own naming rule -- a test takes the name of the interface it covers with
 * {@code IT} appended -- and the rule exists so the subject under test is derivable from the test's
 * name. Its cost is that two classes read the same seeded rows, and the shared container base means
 * that costs one engine start rather than two.
 */
class PhoneAreaCodeRepositoryIT extends ReferencePersistenceBase {

    /** Every code the seed loads, across both classifications. */
    private static final int SEEDED_CODES = 490;

    /** The general-purpose sublist the baseline's account-maintenance path accepts. */
    private static final int GENERAL_PURPOSE_CODES = 410;

    /** The easily-recognisable sublist the baseline's account-maintenance path declines. */
    private static final int EASILY_RECOGNISABLE_CODES = 80;

    /** The first literal of the general-purpose list at {@code app/cpy/CSLKPCDY.cpy} L521. */
    private static final String GENERAL_PURPOSE_CODE = "201";

    /** The first literal of the easily-recognisable list at {@code app/cpy/CSLKPCDY.cpy} L931. */
    private static final String EASILY_RECOGNISABLE_CODE = "200";

    /** A well-formed three-character value on neither copybook list and in neither seeded class. */
    private static final String ABSENT_CODE = "000";

    /** The interface under test: the classification-scoped predicate. */
    @Autowired
    private PhoneAreaCodeRepository classifiedCodes;

    /** The sibling interface, read here only to enumerate the rows the predicate is checked over. */
    @Autowired
    private UsPhoneAreaCodeRepository areaCodes;

    /**
     * Confirms the predicate accepts a general-purpose code and refuses an easily-recognisable one.
     *
     * <p>Assumptions: both codes are asserted in the SAME test rather than in two, because what is
     * under test is a distinction and not two independent facts. A predicate written against the
     * union would satisfy the first assertion and fail the second, and separating them would let a
     * suite report one green result for a predicate that had lost the distinction entirely.
     */
    @Test
    @DisplayName("the classification-scoped predicate accepts general purpose and refuses easy recognition")
    void thePredicateIsScopedToTheNamedClassification() {
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                GENERAL_PURPOSE_CODE, UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE))
                .as("%s is the first literal of the general-purpose list at CSLKPCDY.cpy L521",
                        GENERAL_PURPOSE_CODE)
                .isTrue();

        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                EASILY_RECOGNISABLE_CODE, UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE))
                .as("%s is on the easily-recognisable list at CSLKPCDY.cpy L931, which "
                        + "COACTUPC.cbl L2298 does not test", EASILY_RECOGNISABLE_CODE)
                .isFalse();
    }

    /**
     * Confirms each code answers only for its own classification, in both directions.
     *
     * <p>Assumptions: the mirror of the previous test is asserted too, so the predicate is shown to
     * be scoped rather than merely biased towards one letter. A predicate that ignored its second
     * argument would satisfy the general-purpose half of both tests and fail here.
     */
    @Test
    @DisplayName("neither code answers for the classification it does not carry")
    void eachCodeAnswersOnlyForItsOwnClassification() {
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                EASILY_RECOGNISABLE_CODE, UsPhoneAreaCode.CODE_CLASS_EASILY_RECOGNISABLE))
                .isTrue();
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                GENERAL_PURPOSE_CODE, UsPhoneAreaCode.CODE_CLASS_EASILY_RECOGNISABLE))
                .isFalse();
    }

    /**
     * Confirms the inherited identity check answers the union question over all seeded rows.
     *
     * <p>Assumptions: this exercises {@code existsById}, which the interface documents as the
     * union-scoped predicate instead of re-declaring it. It is asserted over EVERY seeded row rather
     * than over a sample, because the property claimed is that the union check holds for all 490, and
     * a sample would leave the claim partly untested. The row count is asserted first so the loop
     * cannot pass by iterating an empty result.
     */
    @Test
    @DisplayName("the inherited identity check holds for all 490 codes and for nothing else")
    void theInheritedIdentityCheckAnswersTheUnionQuestion() {
        List<UsPhoneAreaCode> all = this.areaCodes.findAll();
        assertThat(all).hasSize(SEEDED_CODES);
        assertThat(all).allSatisfy(row ->
                assertThat(this.classifiedCodes.existsById(row.getAreaCode())).isTrue());

        // WHY : Assumptions: the absent value is '000', and it was chosen by checking BOTH the seed
        //   and the copybook rather than by looking plausible. '999' looks like the natural choice
        //   and is wrong: it is seeded, carrying the easily-recognisable classification, and it
        //   appears in app/cpy/CSLKPCDY.cpy. '000' appears in neither. The sibling
        //   UsPhoneAreaCodeRepositoryIT uses the same value as its empty-finder case, so the two
        //   classes agree on which value is outside the allow-list.
        assertThat(this.classifiedCodes.existsById(ABSENT_CODE)).isFalse();
    }

    /**
     * Confirms the two classifications partition the allow-list with nothing over or under.
     *
     * <p>Assumptions: the counts are taken through the predicate's own interface, by counting how
     * many seeded codes it accepts under each classification, rather than through the sibling's
     * class-narrowed walks. The sibling test already asserts the walks agree with these totals; what
     * this adds is that the PREDICATE agrees with them too, which is the property a caller relies on
     * and which a walk cannot establish on the predicate's behalf.
     */
    @Test
    @DisplayName("the predicate partitions the 490 codes into 410 and 80")
    void thePredicatePartitionsTheAllowList() {
        List<String> codes = this.areaCodes.findAll().stream()
                .map(UsPhoneAreaCode::getAreaCode)
                .toList();
        assertThat(codes).hasSize(SEEDED_CODES);

        long general = codes.stream()
                .filter(code -> this.classifiedCodes.existsByAreaCodeAndCodeClass(
                        code, UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE))
                .count();
        long recognisable = codes.stream()
                .filter(code -> this.classifiedCodes.existsByAreaCodeAndCodeClass(
                        code, UsPhoneAreaCode.CODE_CLASS_EASILY_RECOGNISABLE))
                .count();

        assertThat(general).isEqualTo(GENERAL_PURPOSE_CODES);
        assertThat(recognisable).isEqualTo(EASILY_RECOGNISABLE_CODES);
        assertThat(general + recognisable)
                .as("410 plus 80 is 490: no code may carry a third classification or none")
                .isEqualTo(SEEDED_CODES);
    }

    /**
     * Records what a value of the wrong width does against a {@code CHAR(3)} column.
     *
     * <p>Assumptions: {@code area_cd} is {@code CHAR(3)} because {@code app/cpy/CSLKPCDY.cpy} line 24
     * declares {@code PIC XXX}. A value with different digits, whether shorter or longer, matches
     * nothing -- and a value differing from a seeded code only by TRAILING BLANKS matches. That last
     * one is asserted as {@code true} rather than as {@code false} because it is what the engine does:
     * PostgreSQL's {@code character(n)} type is blank-padded, and its comparison operators disregard
     * trailing blanks, so {@code '201 '} and {@code '201'} are one value at the column's type. This is
     * measured behaviour, written down here so a later reader does not mistake it for leniency in the
     * predicate and tighten a query that is already correct.
     *
     * <p>Assumptions: this is not a parity divergence, because the baseline has no case to diverge
     * from. {@code WS-US-PHONE-AREA-CODE-TO-EDIT} is exactly three bytes wide, so a four-character
     * value cannot be held in it and a trailing blank inside it is a blank digit position rather than
     * padding on a longer value. There is therefore no reference behaviour for this input, and the
     * assertion states the engine's behaviour rather than claiming a baseline agreement it cannot have.
     *
     * <p>Trade-offs: the consequence for a caller is stated rather than left to be inferred -- a caller
     * must not rely on this predicate to reject an untrimmed value, and must trim its input if the
     * distinction matters to it. Asserting {@code false} here instead would have made the suite fail
     * against a correct schema and invited a query change to satisfy it.
     */
    @Test
    @DisplayName("digits of the wrong width match nothing, and a trailing blank is not a difference")
    void theComparisonIsExactOnDigitsAndBlankPaddedOnWidth() {
        String general = UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE;
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass("20", general)).isFalse();
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass("2010", general)).isFalse();
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass("", general)).isFalse();

        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                GENERAL_PURPOSE_CODE + " ", general))
                .as("PostgreSQL character(3) comparison disregards trailing blanks")
                .isTrue();
    }

    /**
     * Confirms a classification letter the schema does not admit matches nothing.
     *
     * <p>Assumptions: {@code V1__reference.sql} constrains {@code code_class} to the two letters the
     * entity names, so no stored row can carry a third. The predicate is asserted to return
     * {@code false} rather than to raise, because a caller passing an unrecognised letter is asking
     * about an empty set and an empty set is a legitimate answer -- and because a method that threw
     * would make the classification argument's domain a run-time surprise rather than a documented
     * pair of constants.
     */
    @Test
    @DisplayName("an unrecognised classification letter matches no code")
    void anUnrecognisedClassificationMatchesNothing() {
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(GENERAL_PURPOSE_CODE, "Z"))
                .isFalse();
        assertThat(this.classifiedCodes.existsByAreaCodeAndCodeClass(
                EASILY_RECOGNISABLE_CODE, "Z")).isFalse();
    }
}
