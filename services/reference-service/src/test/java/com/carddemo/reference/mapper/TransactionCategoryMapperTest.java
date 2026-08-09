package com.carddemo.reference.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionCategory.TransactionCategoryId;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionCategoryUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the three surviving members of the transaction-category anti-corruption layer to their contracts.
 *
 * <p>Purpose: this mapper is the only place the category record's representation concerns are allowed to
 * appear -- the blank padding of two fixed-width key halves, the trailing padding of a fifty-character
 * description, and the {@code 05 FILLER PIC X(04)} the record ends with and no column carries. Each
 * member below is one decision about that boundary, and each is asserted at the value rather than at the
 * shape, because a padded value and a trimmed one are the same length of the same type and differ only in
 * their content.
 *
 * <p>Refactoring Rationale: none of these members had a test, and one of them -- {@code applyUpdate} --
 * had no caller either, while the service performed the same write inline. The service now routes through
 * it, so a rule that existed in two places exists in one; this class is what makes that one place
 * assertable. Without it the normalisation would be proven only indirectly, through a service case that
 * would pass equally well against an inline copy.
 *
 * <p>Assumptions: {@code applyUpdate} returns nothing and mutates its second argument, so every case
 * about it reads the ENTITY afterwards rather than a return value. The two properties worth separating
 * are what it writes and what it leaves alone, and the second needs the entity read as well -- a
 * signature that returned a new row could not express "and nothing else changed" at all.
 *
 * <p>Assumptions: no Spring context, no database and no seeded fixture is involved. Every case builds the
 * entity directly, which is what lets an assertion name the exact character that was wrong.
 *
 * <p>Parameters, return values, exceptions or errors. A test class accepts no parameter, yields no value
 * and raises nothing, so this charter carries no such at-clause; the inapplicability is declared rather
 * than passed over so a reader can tell it from an oversight.
 */
@DisplayName("the transaction-category anti-corruption layer")
class TransactionCategoryMapperTest {

    /** The two-character type half, at the exact width its column declares. */
    private static final String TYPE_CD = "01";

    /** The four-digit category half, at the exact width its column declares. */
    private static final String CAT_CD = "0005";

    /**
     * A description as a fixed-width source would hold it, padded to a width the column does not keep.
     *
     * <p>Assumptions: the padding is TRAILING only. A leading blank in a description is content -- the
     * reference's own screen field admits one -- so a case that padded both ends could not tell a mapper
     * that strips one side from one that strips both.</p>
     */
    private static final String PADDED_DESCRIPTION = "Grocery purchase          ";

    /** The same description as the column stores it and the contract publishes it. */
    private static final String TRIMMED_DESCRIPTION = "Grocery purchase";

    /**
     * The revision the stored row carries when a case asserts the revision is left alone.
     *
     * <p>Assumptions: zero, because the entity exposes no setter for the revision and the persistence
     * provider is what advances it. A case cannot construct a row at a higher revision, so what it can
     * assert is that the mapper did not disturb the one the row has.</p>
     */
    private static final long STORED_VERSION = 0L;

    /**
     * Verifies the published shape trims both key halves and the description, and carries the revision.
     *
     * <p>Assumptions: the description is asserted at its exact trimmed value rather than merely being
     * checked for absence of blanks, because the trailing padding of a fixed-width source field is
     * padding and not data -- the record declares {@code TRAN-CAT-TYPE-DESC PIC X(50)} and every seed row
     * of {@code app/data/ASCII/trancatg.txt} fills it out to fifty characters. Publishing the padding
     * would make every comparison a caller performs depend on a width that stopped existing when the
     * column became a {@code VARCHAR}.</p>
     *
     * <p>Assumptions: the revision is asserted too. The replace operation answers 409 carrying the stored
     * revision so a caller can retry against the one that exists, and a response that dropped it would
     * leave the caller with nothing to retry with.</p>
     */
    @Test
    @DisplayName("the published shape trims the padding and carries the revision")
    void thePublishedShapeTrimsThePadding() {
        TransactionCategoryResponse published = TransactionCategoryMapper.toResponse(
                new TransactionCategory(new TransactionCategoryId(TYPE_CD, CAT_CD),
                        PADDED_DESCRIPTION));

        assertThat(published.typeCd()).isEqualTo(TYPE_CD);
        assertThat(published.catCd())
                .as("the leading zeros of the category half are content and must survive")
                .isEqualTo(CAT_CD);
        assertThat(published.description()).isEqualTo(TRIMMED_DESCRIPTION);
        assertThat(published.version()).isEqualTo(STORED_VERSION);
    }

    /**
     * Verifies a replace writes the normalised description onto the loaded row.
     *
     * <p>Assumptions: the value asserted is the STORED form, which is the trimmed one. The request's own
     * component may arrive padded -- a client echoing a fixed-width screen field is the ordinary case --
     * and the column is a {@code VARCHAR}, so storing the padding would leave two rows that a caller
     * cannot tell apart holding two different byte sequences for the same description.</p>
     */
    @Test
    @DisplayName("a replace writes the trimmed description onto the loaded row")
    void aReplaceWritesTheTrimmedDescription() {
        TransactionCategory stored = new TransactionCategory(
                new TransactionCategoryId(TYPE_CD, CAT_CD), "Superseded");

        TransactionCategoryMapper.applyUpdate(
                new TransactionCategoryUpdateRequest(PADDED_DESCRIPTION, STORED_VERSION), stored);

        assertThat(stored.getDescription()).isEqualTo(TRIMMED_DESCRIPTION);
    }

    /**
     * Verifies a replace writes the description and leaves every other member of the row alone.
     *
     * <p>Assumptions: this is the half of the contract that a passing write assertion cannot show. Both
     * key halves form the primary key and are mapped {@code updatable = false}, so a mapper that wrote
     * one would have its write DISCARDED rather than honoured -- the row would be saved successfully with
     * the caller's intent silently dropped. The revision is asserted for the mirror-image reason: the
     * request carries one, and a mapper that consumed it would make it look as though the concurrency
     * check had been performed here when it belongs to the service.</p>
     */
    @Test
    @DisplayName("a replace leaves both key halves and the revision untouched")
    void aReplaceLeavesTheKeyAndRevisionUntouched() {
        TransactionCategory stored = new TransactionCategory(
                new TransactionCategoryId(TYPE_CD, CAT_CD), "Superseded");

        TransactionCategoryMapper.applyUpdate(
                new TransactionCategoryUpdateRequest(TRIMMED_DESCRIPTION, 99L), stored);

        assertThat(stored.getTypeCd()).isEqualTo(TYPE_CD);
        assertThat(stored.getCatCd()).isEqualTo(CAT_CD);
        assertThat(stored.getVersion())
                .as("the revision the request carried must NOT be adopted; the provider owns it")
                .isEqualTo(STORED_VERSION);
    }

    /**
     * Verifies a create and a replace agree on the stored form of one description.
     *
     * <p>Assumptions: the two paths are compared against each OTHER rather than each against a literal,
     * which is the property that matters and the one a duplicated normalisation would break. Before the
     * service was routed through this mapper the rule existed in two places, and two implementations of
     * one storage rule are free to drift; a {@code VARCHAR} column reports nothing when they do, so the
     * divergence would surface as two rows that look identical to a caller and compare unequal.</p>
     */
    @Test
    @DisplayName("a create and a replace store one description identically")
    void aCreateAndAReplaceStoreOneDescriptionIdentically() {
        TransactionCategory created = TransactionCategoryMapper.toNewEntity(
                new TransactionCategoryCreateRequest(TYPE_CD, CAT_CD, PADDED_DESCRIPTION));

        TransactionCategory replaced = new TransactionCategory(
                new TransactionCategoryId(TYPE_CD, CAT_CD), "Superseded");
        TransactionCategoryMapper.applyUpdate(
                new TransactionCategoryUpdateRequest(PADDED_DESCRIPTION, STORED_VERSION), replaced);

        assertThat(replaced.getDescription()).isEqualTo(created.getDescription());
    }

    /**
     * Verifies each member refuses a {@code null} argument rather than folding it into a value.
     *
     * <p>Assumptions: a refusal is asserted rather than a substituted default, because every argument
     * here is required by the contract above it -- the entity is a loaded row and the request has passed
     * bean validation. Folding a {@code null} into an empty description would write a blank into a
     * {@code NOT NULL} column's row and report success, which is the failure a caller cannot detect.</p>
     */
    @Test
    @DisplayName("every member refuses a null argument")
    void everyMemberRefusesANullArgument() {
        TransactionCategory stored = new TransactionCategory(
                new TransactionCategoryId(TYPE_CD, CAT_CD), TRIMMED_DESCRIPTION);

        assertThatThrownBy(() -> TransactionCategoryMapper.toResponse(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransactionCategoryMapper.applyUpdate(null, stored))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransactionCategoryMapper.applyUpdate(
                new TransactionCategoryUpdateRequest(TRIMMED_DESCRIPTION, STORED_VERSION), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TransactionCategoryMapper.toNewEntity(null))
                .isInstanceOf(NullPointerException.class);
    }
}
