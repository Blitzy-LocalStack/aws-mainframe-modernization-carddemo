package com.carddemo.reference.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionTypeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the published width of a reference description to its content length on both mappers.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class asserts one rule, at the two entry points that apply it: a stored description is published
 * at the length of its content and is never carried out at the fifty characters the reference record pads it
 * to. The rule is a registered divergence rather than parity, {@code D-REFDATA-DESCRIPTION-TRIM} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, and a registered divergence with no executable
 * consumer is a claim rather than a property.</p>
 *
 * <p>Assumptions: the fixtures below are padded to the FULL declared fifty, because that is the form the
 * store actually holds. {@code TRAN-TYPE-DESC PIC X(50)} at L6 of {@code app/cpy/CVTRA03Y.cpy} and
 * {@code TRAN-CAT-TYPE-DESC PIC X(50)} at L8 of {@code app/cpy/CVTRA04Y.cpy} are fixed fields, and the seed
 * extract keeps the padding -- the first row of {@code app/data/ASCII/trancatg.txt} carries
 * {@code Regular Sales Draft} across bytes 7 to 56 with thirty-one trailing blanks. Passing an
 * already-short value would exercise nothing: the trim would be a no-op and the case would pass whether or
 * not the mapper trims at all.</p>
 *
 * <p>Alternatives Considered: asserting the trim through the published contract's own
 * {@code ReferenceDescription} schema instead of at the mapper. Rejected because that schema's pattern
 * admits trailing blanks -- it refuses an all-blank value and nothing else -- so a contract assertion would
 * pass on a padded response and could not detect the rule being dropped. The mapper is the only place the
 * rule exists, so it is the only place an assertion can hold it.</p>
 *
 * <p>Trade-offs: the two mappers are asserted in one class rather than in one class each. They implement a
 * single rule through a single shared helper, so splitting them would produce two files whose failure means
 * the same thing, and a reader looking for the rule would have to know both names to be sure of finding
 * it.</p>
 */
class ReferenceDescriptionTrimTest {

    /**
     * The declared width both reference descriptions are padded to in the reference record.
     */
    private static final int DECLARED_WIDTH = 50;

    /**
     * The description content of the first seeded category row.
     */
    private static final String CONTENT = "Regular Sales Draft";

    /**
     * The same content in the padded form the store holds it in.
     */
    private static final String PADDED = CONTENT + " ".repeat(DECLARED_WIDTH - CONTENT.length());

    /**
     * Verifies the transaction-type projection publishes the content and not the padding.
     */
    @Test
    @DisplayName("a padded transaction-type description is published at its content length")
    void aPaddedTypeDescriptionIsPublishedTrimmed() {
        TransactionTypeResponse published =
                TransactionTypeMapper.toResponse(new TransactionType("01", PADDED));

        assertThat(published.description())
                .as("the published value carries the content alone, so a caller has nothing to strip")
                .isEqualTo(CONTENT)
                .doesNotEndWith(" ");
        assertThat(PADDED).hasSize(DECLARED_WIDTH);
    }

    /**
     * Verifies the transaction-category projection publishes the content and not the padding.
     */
    @Test
    @DisplayName("a padded transaction-category description is published at its content length")
    void aPaddedCategoryDescriptionIsPublishedTrimmed() {
        TransactionCategoryResponse published = TransactionCategoryMapper.toResponse(
                new TransactionCategory(
                        new TransactionCategory.TransactionCategoryId("01", "0001"), PADDED));

        assertThat(published.description())
                .as("the published value carries the content alone, so a caller has nothing to strip")
                .isEqualTo(CONTENT)
                .doesNotEndWith(" ");
    }

    /**
     * Verifies an interior blank survives, so the rule is a trailing-blank rule and not a blank rule.
     *
     * <p>Assumptions: this case is separate because the two rules are easy to conflate and the wrong one
     * is silently destructive. A description is admitted with interior spaces by the published contract's
     * own pattern, and every seeded multi-word description depends on them, so a rule that removed blanks
     * rather than trailing blanks would turn {@code Regular Sales Draft} into a single token and no length
     * assertion above would notice.</p>
     */
    @Test
    @DisplayName("interior blanks survive; only the trailing pad is removed")
    void interiorBlanksSurvive() {
        TransactionTypeResponse published =
                TransactionTypeMapper.toResponse(new TransactionType("02", PADDED));

        assertThat(published.description())
                .contains(" ")
                .isEqualTo("Regular Sales Draft");
    }
}
