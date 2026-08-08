package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the transaction-category queries and the referential rule only an engine enforces.
 *
 * <p>The rule is the reason this package exists. {@code db/migration/V1__reference.sql} declares the
 * category-to-type foreign key {@code ON DELETE RESTRICT} at line 268, reproducing the {@code XTRNTYCAT}
 * semantic the baseline's Db2 definition carries, and the reference service publishes that refusal as a
 * conflict rather than as a database error. No test double can establish it: a mocked repository would
 * delete whatever it was asked to delete.
 */
@Transactional
class TransactionCategoryRepositoryIT extends ReferencePersistenceBase {

    /** The number of categories the seed migration loads. */
    private static final int SEEDED_CATEGORIES = 18;

    /** The repository under test. */
    @Autowired
    private TransactionCategoryRepository categories;

    /** The type repository, used to attempt the restricted delete. */
    @Autowired
    private TransactionTypeRepository types;

    /**
     * Confirms the seed migration loads the categories, ordered by the composite key.
     */
    @Test
    @DisplayName("the seed migration loads eighteen categories in composite-key order")
    void theSeedLoadsEighteenCategories() {
        List<TransactionCategory> all = this.categories.findFirstPage(Limit.of(100));
        assertThat(all).hasSize(SEEDED_CATEGORIES);
        assertThat(all).extracting(row -> row.getId().getTypeCd() + row.getId().getCatCd())
                .as("the engine orders by type then category, which is the baseline's index order")
                .isSorted();
    }

    /**
     * Confirms deleting a referenced type is REFUSED by the engine rather than cascading.
     *
     * <p>Assumptions: this is the whole point of a container-backed test in this package. A cascade would
     * silently remove the categories of the deleted type, and a set-null would violate the key's own
     * not-null declaration -- either would be a data-loss defect that every unit test would miss.
     */
    @Test
    @DisplayName("deleting a type that categories reference is refused by the engine")
    void deletingAReferencedTypeIsRefused() {
        assertThat(this.categories.countByTypeCd("01"))
                .as("the fixture depends on type 01 having children")
                .isPositive();

        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> {
            this.types.findByTypeCd("01").ifPresent(this.types::delete);
            this.types.flush();
        });
    }

    /**
     * Confirms an unreferenced type CAN be deleted, so the refusal above is the rule and not a blanket.
     *
     * <p>Refactoring Rationale: the unreferenced type is INSERTED here rather than found among the seeded
     * ones. The first form of this case searched the seed for a type with no categories and failed,
     * because every one of the seven seeded types has at least one -- which is a property of the seed and
     * not of the constraint. Creating the subject makes the case assert the constraint rather than the
     * shape of the seed data, and it survives a seed that gains or loses a row.
     */
    @Test
    @DisplayName("a type no category references can be deleted")
    void anUnreferencedTypeCanBeDeleted() {
        String unreferenced = "90";
        assertThat(this.categories.countByTypeCd(unreferenced))
                .as("the chosen code must not be one the seed gives children to")
                .isZero();

        this.types.saveAndFlush(new TransactionType(unreferenced, "Unreferenced for this case"));
        assertThat(this.types.findByTypeCd(unreferenced)).isPresent();

        this.types.findByTypeCd(unreferenced).ifPresent(this.types::delete);
        this.types.flush();
        assertThat(this.types.findByTypeCd(unreferenced)).isEmpty();
    }

    /**
     * Confirms the child count answers per type rather than in total.
     */
    @Test
    @DisplayName("the child count is scoped to one type")
    void theChildCountIsScopedToOneType() {
        long forOne = this.categories.countByTypeCd("01");
        assertThat(forOne).isPositive().isLessThan(SEEDED_CATEGORIES);
        assertThat(this.categories.countByTypeCd("99")).isZero();
    }

    /**
     * Confirms the type-narrowed walk returns only that type's categories, in ascending category order.
     */
    @Test
    @DisplayName("the type-narrowed walk returns one type's categories in ascending order")
    void theTypeNarrowedWalkIsScopedAndOrdered() {
        List<TransactionCategory> page = this.categories.findFirstPageOfType("01", Limit.of(100));
        assertThat(page).isNotEmpty();
        assertThat(page).allSatisfy(row -> assertThat(row.getId().getTypeCd()).isEqualTo("01"));
        assertThat(page).extracting(row -> row.getId().getCatCd()).isSorted();
    }

    /**
     * Confirms both composite walks are strict about the position they are given.
     */
    @Test
    @DisplayName("the composite walks exclude the position in both directions")
    void theCompositeWalksAreStrict() {
        List<TransactionCategory> all = this.categories.findFirstPage(Limit.of(100));
        TransactionCategory second = all.get(1);
        String typeCd = second.getId().getTypeCd();
        String catCd = second.getId().getCatCd();

        assertThat(this.categories.findPageAfter(typeCd, catCd, Limit.of(100)))
                .noneSatisfy(row -> assertThat(row.getId()).isEqualTo(second.getId()));
        assertThat(this.categories.findPageBefore(typeCd, catCd, Limit.of(100)))
                .noneSatisfy(row -> assertThat(row.getId()).isEqualTo(second.getId()));
    }

    /**
     * Confirms the keyed finder resolves a composite identity and reports an unknown one absent.
     */
    @Test
    @DisplayName("the keyed finder resolves a composite identity")
    void theKeyedFinderResolvesACompositeIdentity() {
        TransactionCategory first = this.categories.findFirstPage(Limit.of(1)).get(0);
        assertThat(this.categories.findByIdIs(first.getId())).isPresent();
        assertThat(this.categories.findByIdIs(
                new TransactionCategory.TransactionCategoryId("99", "9999"))).isEmpty();
    }
}
