package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.reference.domain.UsPhoneAreaCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the area-code allow-list, its two sublists and the constraint that closes its domain.
 *
 * <p>Assumptions: the counts asserted here are the baseline's own. {@code app/cpy/CSLKPCDY.cpy} holds a
 * 410-member general-purpose list at L521 and an easily-recognisable list at L931, and the seed migration
 * loads 490 codes in total. The account context's address validation accepts only the general-purpose
 * members, so the split is load-bearing rather than descriptive.
 */
@Transactional
class UsPhoneAreaCodeRepositoryIT extends ReferencePersistenceBase {

    /** Every code the seed loads, across both sublists. */
    private static final int SEEDED_CODES = 490;

    /** The general-purpose sublist the account update path accepts. */
    private static final int GENERAL_PURPOSE_CODES = 410;

    /** The easily-recognisable sublist the account update path declines. */
    private static final int EASILY_RECOGNISABLE_CODES = 80;

    /** The repository under test. */
    @Autowired
    private UsPhoneAreaCodeRepository areaCodes;

    /**
     * Confirms the seed loads the whole allow-list, ordered ascending.
     */
    @Test
    @DisplayName("the seed loads 490 codes in ascending order")
    void theSeedLoadsEveryCode() {
        List<UsPhoneAreaCode> all = this.areaCodes.findAllByOrderByAreaCodeAsc(Limit.of(1000));
        assertThat(all).hasSize(SEEDED_CODES);
        assertThat(all).extracting(UsPhoneAreaCode::getAreaCode).isSorted();
    }

    /**
     * Confirms the two sublists partition the allow-list exactly.
     *
     * <p>Assumptions: the two counts are asserted to SUM to the total, so a code carrying neither letter
     * would be caught. Asserting each count alone would leave a third classification undetected.
     */
    @Test
    @DisplayName("the general-purpose and easily-recognisable sublists partition the allow-list")
    void theSublistsPartitionTheAllowList() {
        int general = this.areaCodes.findByCodeClassOrderByAreaCodeAsc(
                UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE, Limit.of(1000)).size();
        int recognisable = this.areaCodes.findByCodeClassOrderByAreaCodeAsc(
                UsPhoneAreaCode.CODE_CLASS_EASILY_RECOGNISABLE, Limit.of(1000)).size();

        assertThat(general).isEqualTo(GENERAL_PURPOSE_CODES);
        assertThat(recognisable).isEqualTo(EASILY_RECOGNISABLE_CODES);
        assertThat(general + recognisable)
                .as("no code may carry a third classification")
                .isEqualTo(SEEDED_CODES);
    }

    /**
     * Confirms the keyed finder returns the row WITH its classification.
     *
     * <p>Assumptions: this is the method the account context's adapter reads through the published item
     * route, and returning the classification is what lets that context ask a membership question without
     * this service publishing a membership predicate.
     */
    @Test
    @DisplayName("the keyed finder returns the row with its classification")
    void theKeyedFinderReturnsTheClassification() {
        assertThat(this.areaCodes.findByAreaCode("201"))
                .isPresent()
                .get()
                .extracting(UsPhoneAreaCode::getCodeClass)
                .isEqualTo(UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE);
        assertThat(this.areaCodes.findByAreaCode("000")).isEmpty();
    }

    /**
     * Confirms the class-narrowed walks are strict about the position and stay within their class.
     */
    @Test
    @DisplayName("the class-narrowed walks exclude the position and stay within one class")
    void theClassNarrowedWalksAreScopedAndStrict() {
        String general = UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE;
        List<UsPhoneAreaCode> forward = this.areaCodes
                .findByCodeClassAndAreaCodeGreaterThanOrderByAreaCodeAsc(general, "201",
                        Limit.of(5));
        assertThat(forward).hasSize(5);
        assertThat(forward).allSatisfy(row ->
                assertThat(row.getCodeClass()).isEqualTo(general));
        assertThat(forward).extracting(UsPhoneAreaCode::getAreaCode)
                .allSatisfy(code -> assertThat(code.compareTo("201")).isPositive());

        List<UsPhoneAreaCode> backward = this.areaCodes
                .findByCodeClassAndAreaCodeLessThanOrderByAreaCodeDesc(general, "210", Limit.of(3));
        assertThat(backward).extracting(UsPhoneAreaCode::getAreaCode)
                .allSatisfy(code -> assertThat(code.compareTo("210")).isNegative());
    }

    /**
     * Confirms the unnarrowed walks are strict in both directions.
     */
    @Test
    @DisplayName("the unnarrowed walks exclude the position in both directions")
    void theUnnarrowedWalksAreStrict() {
        assertThat(this.areaCodes.findByAreaCodeGreaterThanOrderByAreaCodeAsc("201", Limit.of(2)))
                .extracting(UsPhoneAreaCode::getAreaCode)
                .allSatisfy(code -> assertThat(code).isGreaterThan("201"));
        assertThat(this.areaCodes.findByAreaCodeLessThanOrderByAreaCodeDesc("210", Limit.of(2)))
                .extracting(UsPhoneAreaCode::getAreaCode)
                .allSatisfy(code -> assertThat(code).isLessThan("210"));
    }

    /**
     * Confirms the engine refuses a classification outside the two the baseline defines.
     *
     * <p>Assumptions: this is a constraint the migration declares and only the engine enforces. Without
     * it a third letter could be stored, and the account context's translation of the letter into a
     * classification refuses an unrecognised one -- so the row would be unreadable rather than merely
     * unusual, and the failure would surface in another service.
     */
    @Test
    @DisplayName("a classification outside the two defined letters is refused by the engine")
    void anUnknownClassificationIsRefused() {
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() ->
                this.areaCodes.saveAndFlush(new UsPhoneAreaCode("999", "Z")));
    }
}
