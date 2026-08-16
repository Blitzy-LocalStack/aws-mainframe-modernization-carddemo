// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/TransactionCategoryRepositoryIT.java
// -----------------------------------------------------------------------------
// Purpose:
//      The container-backed integration tests of TransactionCategoryRepository.
//      Four contracts are established here against a real engine: the CHILD
//      side of the reference context's one foreign key, the declared width of
//      the category code, the composite identity together with its uniqueness,
//      and the key-positioned walk that covers the eighteen seeded rows in
//      three windows.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: the four rationale labels used below are the PLURAL forms
//      that docs/CODE_DOCUMENTATION_STANDARD.md rules at its lines 236 to 239
//      as their one permitted written form -- Alternatives Considered:,
//      Refactoring Rationale:, Assumptions: and Trade-offs:. The singular
//      spellings carry the same meaning and are not used in this Java tree. The
//      equivalence is recorded once here and is not restated below, and the
//      label text is taken from that standard rather than from tests/README.md,
//      whose line 548 renders one of the four with a non-breaking hyphen and an
//      em dash in place of the ASCII hyphen -- a difference no reader can see
//      on screen.
//  (2) Alternatives Considered: a real PostgreSQL engine rather than an
//      in-memory one. The headline case here is that the engine REFUSES to
//      delete a transaction type a category still references, and reports
//      SQLSTATE 23503 while doing it. An engine modelling the restrict action
//      loosely, or reached through a different migration dialect, would let
//      that case pass against a fiction while the deployed schema behaved
//      otherwise, and package-info.java in this package records that this is
//      the only level at which the constraint itself is proven at all.
//  (3) Assumptions: the engine, the test slice and the profile are all
//      INHERITED from ReferencePersistenceBase, the package-private base
//      declared beside TransactionTypeRepositoryIT and reached here by
//      same-package resolution rather than by import. No annotation on this
//      class restates that slice, no engine is started here, and no per-class
//      container lifecycle annotation appears below either -- the base records
//      the measured failure that produced that rule, which is that a container
//      field owned by the per-class extension is stopped when the first class
//      in the package finishes while the cached context, shared across all of
//      them, goes on pointing at it.
//  (4) Refactoring Rationale: this class carries NO class-level transaction,
//      although an earlier form of it did. Each case that writes declares its
//      own boundary and flushes explicitly, which is the form
//      TransactionTypeRepositoryIT settled on for a reason that applies here
//      unchanged: a constraint is evaluated when its statement executes, so one
//      ambient transaction spanning a whole case leaves the moment a refusal
//      arrives dependent on when the context happened to flush, and a refusal
//      that arrives after the case returns is asserted by nothing.
//  (5) Assumptions: every line number cited below is a PHYSICAL line number and
//      each was read at that address. In
//      app/app-transaction-type-db2/cbl/COTRTLIC.cbl the printed six-digit
//      sequence field stands four higher than the physical line from physical
//      line 1808 onward, so a citation checked by searching for a printed
//      sequence value resolves to the wrong line; verify positionally instead.
//      Everything beneath app/ is the behavioural oracle of this migration: it
//      is read and cited, never modified, and a deliberate departure from it is
//      registered in docs/architecture/cobol-to-service-traceability.md, a
//      document this file references and does not author.
//  (6) Trade-offs: container start-up is what this file costs, and it is
//      accepted rather than reduced. What it buys is that the referential
//      refusal, the fixed-width key semantics and the window arithmetic are
//      each proven where they are actually enforced; a faster suite that proved
//      none of the three would be measuring its own substitutes.
// =============================================================================
package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionType;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the transaction-category access paths and the referential rule only an engine enforces.
 *
 * <p>This class asserts the CHILD side of the reference context's single foreign key.
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares it across its L6 and L7 as a foreign
 * key on the category table's type column referencing the type table with the restrict action, and
 * {@code src/main/resources/db/migration/V1__reference.sql} reproduces it at its L255 to L257. The
 * refusal then travels a fixed chain: the baseline platform's referential failure becomes PostgreSQL's
 * foreign-key-violation state, which the framework translates into a data-integrity exception, which
 * {@code com.carddemo.common.error.GlobalExceptionHandler} renders as HTTP 409. Only the first link is
 * asserted here, because it is the only one an engine decides; the surfacing of the conflict status
 * belongs to the {@code api} subpackage and is never weakened to accept a server-error status.</p>
 *
 * <p>Assumptions: none of it can be established by a test double. A mocked repository deletes whatever
 * it is asked to delete, so every assertion would pass while the deployed schema behaved differently.
 * The same holds for the two column semantics asserted below -- that a four-wide character column
 * returns its leading zeros as data, and that the composite key is unique as a pair rather than in
 * either half alone.</p>
 *
 * <p>Trade-offs: the ambient transaction on each writing case rolls its fixture back, which trades the
 * realism of a committed row for a seed whose contents the reading cases can state exactly. The
 * reading cases assert eighteen rows and a specific key sequence, and neither would hold if a writing
 * case left its fixture behind.</p>
 */
class TransactionCategoryRepositoryIT extends ReferencePersistenceBase {

    // WHY : Assumptions: eighteen is the count of records in
    //       app/data/ASCII/trancatg.txt, the dataset V2__seed_reference.sql loads this table from and
    //       names as its source at that file's L161. The dataset was read byte by byte rather than
    //       eyeballed: 1116 bytes over 18 lines, every line measuring 61 bytes, and 18 carriage
    //       returns -- so 18 times 60 plus a carriage return and a line feed accounts for the file
    //       exactly, and the RECORD is the 60 bytes that app/cpy/CVTRA04Y.cpy L2 declares.
    /** The number of categories the seed migration loads. */
    private static final int SEEDED_CATEGORIES = 18;

    // WHY : Assumptions: seven is the published window, declared as a program constant at
    //       app/app-transaction-type-db2/cbl/COTRTLIC.cbl physical line 60. The category listing has
    //       no cursor of its own in the baseline -- that program's L333 includes the type declaration
    //       only -- so the category window mirrors the type cursor's shape, which is what
    //       TransactionCategoryService.PAGE_SIZE also carries.
    /** The rows one window publishes, being the program constant at COTRTLIC.cbl physical line 60. */
    private static final int WINDOW_ROWS = 7;

    // WHY : Assumptions: a walk is bounded at one row MORE than the window publishes, and the surplus
    //       row is the whole mechanism by which a further window is detected. COTRTLIC.cbl reads that
    //       extra row at its L1661 to L1665 and sets its further-page indicator from whether the read
    //       succeeded, never from an aggregate. Bounding at the window size instead would leave the
    //       flag with nothing to be derived from.
    /** The bound each walk is given: the window plus the one surplus row that settles the flag. */
    private static final int WINDOW_WITH_LOOKAHEAD = WINDOW_ROWS + 1;

    /** The state PostgreSQL reports when a foreign key refuses a write. */
    private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";

    /** The state PostgreSQL reports when a unique constraint refuses a duplicate key. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    // WHY : Assumptions: this sequence is the seed's own, read from the first six bytes of each record
    //       of app/data/ASCII/trancatg.txt in file order with the carriage return stripped, and it is
    //       also the order the engine returns under the ordering the walks declare. It is written out
    //       rather than computed because a computed expectation would reproduce whatever the query
    //       returned and then agree with it. The per-type shape it encodes is five rows under type 01,
    //       three each under 02, 03 and 04, one under 05, two under 06 and one under 07.
    /** Every seeded composite key, as type code followed by category code, in key order. */
    private static final List<String> SEEDED_KEYS = List.of(
            "010001", "010002", "010003", "010004", "010005",
            "020001", "020002", "020003",
            "030001", "030002", "030003",
            "040001", "040002", "040003",
            "050001",
            "060001", "060002",
            "070001");

    /** A type code the seed gives five children to, used where a populated parent is needed. */
    private static final String POPULATED_TYPE_CD = "01";

    /** The repository under test. */
    @Autowired
    private TransactionCategoryRepository categories;

    // WHY : Assumptions: the type repository is injected to build and to delete the PARENT of a
    //       category, which is the only way the child side of the foreign key can be exercised at all.
    //       No case here asserts a property of the type table itself; those belong to
    //       TransactionTypeRepositoryIT.
    /** The type repository, used to supply and to attempt the delete of a parent row. */
    @Autowired
    private TransactionTypeRepository types;

    // WHY : Alternatives Considered: reaching the duplicate-key refusal through the repository's own
    //       save, which is how every other write in this file is issued. Measured to be unusable for
    //       that one case: these entities carry an assigned key and a primitive optimistic-lock
    //       counter, which leaves the framework's newness test nothing to detect, so a save resolves
    //       to a merge -- and a merge against an existing key issues a SELECT and then an UPDATE, so
    //       the unique constraint is never reached and the case would assert nothing while passing.
    //       TransactionTypeRepositoryIT proves that same merge behaviour on the type table directly.
    // WHY : Refactoring Rationale: a persist is used for that case and for nothing else. It is neither
    //       a bulk nor a derived modifying statement, so it stays inside the ruling this package's
    //       charter states about writes, while being the one call that forces the INSERT the
    //       constraint has to refuse. The flush that follows is issued through the repository so that
    //       the provider failure is translated into the same framework exception the service branches
    //       on, rather than into a provider-specific type no production code inspects.
    /** The context used to force an insert where a save would resolve to a merge instead. */
    @Autowired
    private EntityManager entityManager;

    /**
     * Confirms the seed loads eighteen categories and that they arrive in composite-key order.
     *
     * <p>Purpose: this is asserted before anything else because the walk cases below are stated in
     * terms of this exact sequence. The count and the order are one assertion rather than two, since a
     * seed that loaded the right number of rows under the wrong keys would satisfy a count alone.</p>
     *
     * <p>Assumptions: the ordering asserted is the ENGINE's, not a sorted copy of the expectation. The
     * walk declares its ordering over the two key columns and the comparison is a fixed-width character
     * comparison, so what this establishes is that a four-wide character column collates the way the
     * baseline's own index ordered it -- {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl} declares
     * that index over the type code ascending then the category code ascending.</p>
     *
     * <p>Assumptions: the grand total across this schema's six tables is asserted by
     * {@code TransactionTypeRepositoryIT} and is deliberately not restated here. A total asserted in two
     * places can be satisfied in one of them and reported as satisfied in both.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the seed loads eighteen categories in composite-key order")
    void theSeedLoadsEighteenCategoriesInCompositeKeyOrder() {
        List<TransactionCategory> all = this.categories.findFirstPage(Limit.of(100));

        assertThat(all).hasSize(SEEDED_CATEGORIES);
        assertThat(keysOf(all))
                .as("the engine's ordering of two fixed-width character columns, not a sorted list")
                .containsExactlyElementsOf(SEEDED_KEYS);
    }

    /**
     * Confirms the seeded categories are distributed across the seven types exactly as the dataset is.
     *
     * <p>Purpose: the count above establishes how many rows exist and the order establishes their keys.
     * This establishes the shape a caller of the type-narrowed walk depends on, which is that some types
     * carry several children and others carry one. A seed that put all eighteen rows under one type
     * would satisfy both assertions above and break every narrowed walk.</p>
     *
     * <p>Assumptions: the per-type counts are the dataset's, obtained by counting the first two bytes of
     * each of the eighteen records of {@code app/data/ASCII/trancatg.txt}. Their sum is asserted against
     * the total as well, so a count that drifted in one type could not be absorbed by another.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the seeded categories are distributed across the seven types as the dataset is")
    void theSeedDistributesTheCategoriesAcrossTheSevenTypes() {
        assertThat(this.categories.countByTypeCd("01")).isEqualTo(5L);
        assertThat(this.categories.countByTypeCd("02")).isEqualTo(3L);
        assertThat(this.categories.countByTypeCd("03")).isEqualTo(3L);
        assertThat(this.categories.countByTypeCd("04")).isEqualTo(3L);
        assertThat(this.categories.countByTypeCd("05")).isEqualTo(1L);
        assertThat(this.categories.countByTypeCd("06")).isEqualTo(2L);
        assertThat(this.categories.countByTypeCd("07")).isEqualTo(1L);

        long counted = 0L;
        for (String typeCd : List.of("01", "02", "03", "04", "05", "06", "07")) {
            counted += this.categories.countByTypeCd(typeCd);
        }
        assertThat(counted)
                .as("the per-type counts must account for every seeded row and for no further one")
                .isEqualTo(SEEDED_CATEGORIES);

        assertThat(this.categories.countByTypeCd("99"))
                .as("a code the seed never loads has no children, which is also what lets it be deleted")
                .isZero();
    }

    /**
     * Confirms the seeded descriptions keep the mixed case and the punctuation of their dataset.
     *
     * <p>Purpose: two seed sources exist for this table and they disagree throughout.
     * {@code app/app-transaction-type-db2/ctl/DB2LTCAT.ctl} inserts upper case, spelling the first row
     * {@code REGULAR SALES DRAFT}, whereas {@code app/data/ASCII/trancatg.txt} carries mixed case.
     * {@code V2__seed_reference.sql} seeds from the ASCII dataset in preference to the control card, so
     * an upper-case description is not an accepted value here and is asserted against directly.</p>
     *
     * <p>Assumptions: the seventeenth record is {@code Non-fraud reversal} and it contains a HYPHEN. No
     * character-class rule for a category description exists anywhere in the baseline, so the hyphen is
     * data and is asserted as data; a test that expected an alphanumeric description would fail on this
     * row and invite the seed to be altered to suit it.</p>
     *
     * <p>Assumptions: the descriptions are compared with no trimming on either side. The column is
     * {@code VARCHAR(50)} at {@code TRNTYCAT.ddl} L4 and {@code V1__reference.sql} L198, so it stores
     * what it was given rather than padding to its width -- the trailing spaces the 60-byte record
     * carries were dropped by the seed and are not part of the stored value.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the seeded descriptions keep their mixed case and the hyphen of the seventeenth row")
    void theSeedKeepsTheMixedCaseDescriptionsIncludingTheHyphen() {
        assertThat(descriptionOf("01", "0001"))
                .as("the mixed-case dataset is the seed lineage, not the upper-case control card")
                .isEqualTo("Regular Sales Draft")
                .isNotEqualTo("REGULAR SALES DRAFT");

        assertThat(descriptionOf("06", "0002"))
                .as("the hyphen is data: no baseline rule restricts the characters of a description")
                .isEqualTo("Non-fraud reversal")
                .contains("-");

        assertThat(descriptionOf("07", "0001")).isEqualTo("Sales draft credit adjustment");
    }

    /**
     * Confirms a category code round-trips at its declared width with its leading zeros intact.
     *
     * <p>Purpose: this is the one column contract two baseline files disagree about.
     * {@code app/cpy/CVTRA04Y.cpy} L7 declares {@code TRAN-CAT-CD PIC 9(04)}, which is numeric, while
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L3 declares {@code TRC_TYPE_CATEGORY
     * CHAR(4) NOT NULL} and its L5 places that column in the primary key. The data definition is
     * authoritative and {@code V1__reference.sql} follows it at L192. Assumptions: the consequence of
     * resolving it the other way is concrete rather than stylistic -- an integer column would store
     * {@code 0001} and return {@code 1}, which breaks the composite key and the concatenated
     * {@code 010001} form that every listing and every cursor position is built from.</p>
     *
     * <p>Assumptions: the width is proven by reading the value back and asserting its LENGTH and its
     * content, never by comparing it in a predicate the engine evaluates. PostgreSQL implements
     * {@code CHAR(n)} as a blank-padded type whose equality ignores trailing blanks, so a predicate
     * would report a match for a value of the wrong width and prove nothing about padding. Every value
     * in this column happens to be exactly four characters so no padding arises, which is precisely why
     * the property has to be asserted on the returned string rather than inferred from a comparison
     * succeeding.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a category code round-trips at four characters with its leading zeros intact")
    void theCategoryCodeKeepsItsLeadingZerosAtItsDeclaredWidth() {
        Optional<TransactionCategory> row = this.categories.findByIdIs(
                new TransactionCategory.TransactionCategoryId("01", "0001"));
        assertThat(row).as("the seed must have loaded the subject row").isPresent();

        assertThat(row.get().getCatCd())
                .as("a four-wide character column must return its leading zeros as data")
                .hasSize(TransactionCategory.CAT_CD_WIDTH)
                .isEqualTo("0001")
                .isNotEqualTo("1");
        assertThat(row.get().getTypeCd())
                .as("the type half is two wide and must not return padding as data")
                .hasSize(TransactionCategory.TYPE_CD_WIDTH)
                .isEqualTo("01");
        assertThat(row.get().getTypeCd() + row.get().getCatCd())
                .as("the concatenated form every cursor position and listing key is built from")
                .isEqualTo("010001");
    }

    /**
     * Confirms a category code narrowed to its significant digits cannot even be constructed.
     *
     * <p>Purpose: the case above asserts what a correct value round-trips to. This asserts that the
     * incorrect value is unreachable, so the width contract is enforced at the boundary rather than only
     * observed at the column. {@code TransactionCategory.TransactionCategoryId} requires exactly four
     * characters and requires every one of them to be a digit, so the {@code 1} that a numeric column
     * would have produced cannot be used to build an identity, a probe or a walk position.</p>
     *
     * <p>Assumptions: the refusal is the constructor's own and arrives before any statement is issued,
     * which is why this case needs no transaction and writes nothing.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a category code narrowed to significant digits cannot be constructed at all")
    void aCategoryCodeNarrowedToSignificantDigitsCannotBeBuilt() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a one-character code is the exact value an integer column would have returned")
                .isThrownBy(() -> new TransactionCategory.TransactionCategoryId("01", "1"));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a code padded with a space rather than a zero is a different key, not the same one")
                .isThrownBy(() -> new TransactionCategory.TransactionCategoryId("01", "   1"));
    }

    /**
     * Confirms the keyed read resolves a composite identity and reports an unknown one as absent.
     *
     * <p>Purpose: the identity is the pair, so a read has to be satisfied by both halves at once. The
     * primary key is declared over both columns at {@code TRNTYCAT.ddl} L5 and reproduced at
     * {@code V1__reference.sql} L223, and the entity maps it as a nested embeddable used through an
     * embedded identifier.</p>
     *
     * <p>Assumptions: the absent case names a type the seed never loads, so it establishes that the read
     * answers empty rather than raising, which is the condition a caller turns into the migrated
     * not-found refusal instead of into an insert.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the keyed read resolves a composite identity and reports an unknown one absent")
    void theKeyedReadResolvesACompositeIdentity() {
        assertThat(this.categories.findByIdIs(
                new TransactionCategory.TransactionCategoryId("03", "0002"))).isPresent();
        assertThat(this.categories.findByIdIs(
                new TransactionCategory.TransactionCategoryId("99", "9999"))).isEmpty();
    }

    /**
     * Confirms one category code is accepted under several type codes.
     *
     * <p>Purpose: this is what distinguishes a composite key from a unique constraint on either column
     * alone. {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl} declares its index over the pair, so
     * {@code 0001} is expected to recur under every type that has a first category -- the seed carries it
     * under all seven. A constraint wrongly narrowed to the category column would have made the seed
     * itself unloadable, and a constraint wrongly narrowed to the type column would have admitted only
     * one row per type; either would be caught here.</p>
     *
     * <p>Assumptions: the two rows read are distinct identities holding the same category half, which is
     * asserted on the identity objects themselves. The nested identity type declares value equality, so
     * comparing them compares both halves rather than two references.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("one category code is accepted under several type codes")
    void theSameCategoryCodeIsAcceptedUnderDifferentTypes() {
        List<String> firstCategoryOfEachType = new ArrayList<>();
        for (String typeCd : List.of("01", "02", "03", "04", "05", "06", "07")) {
            Optional<TransactionCategory> row = this.categories.findByIdIs(
                    new TransactionCategory.TransactionCategoryId(typeCd, "0001"));
            assertThat(row).as("every seeded type carries a first category").isPresent();
            firstCategoryOfEachType.add(row.get().getTypeCd() + row.get().getCatCd());
        }

        assertThat(firstCategoryOfEachType)
                .as("the same category half recurs under every type, so the key is the pair")
                .containsExactly("010001", "020001", "030001", "040001", "050001", "060001", "070001");

        TransactionCategory.TransactionCategoryId underFirstType =
                new TransactionCategory.TransactionCategoryId("01", "0001");
        TransactionCategory.TransactionCategoryId underSecondType =
                new TransactionCategory.TransactionCategoryId("02", "0001");
        assertThat(underFirstType)
                .as("value equality over both halves, so a shared category half is not a shared key")
                .isNotEqualTo(underSecondType);
    }

    /**
     * Confirms the engine refuses a duplicate composite key and reports SQLSTATE 23505.
     *
     * <p>Purpose: {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl} declares a UNIQUE index over
     * the pair and {@code V1__reference.sql} carries the same object as the composite primary key at its
     * L223. The literal the service compares against when it classifies a duplicate create is only
     * correct if this engine reports that state for this constraint, and nothing establishes that except
     * a real violation.</p>
     *
     * <p>Assumptions: the insert is issued through the persistence context directly because a save would
     * not reach the constraint. These entities carry an assigned key and a primitive optimistic-lock
     * counter, so the framework's newness test resolves a save to a merge, and a merge against an
     * existing key issues an UPDATE -- the duplicate would never arrive and the case would pass while
     * asserting nothing. The rationale is recorded in full at the injected context above.</p>
     *
     * <p>Assumptions: the flush is EXPLICIT, and it is issued through the repository rather than through
     * the context. Without a flush the INSERT would be deferred to the end of the ambient transaction and
     * the refusal would arrive after this method returned, asserting nothing while still reading as
     * green; issuing it through the repository is what translates the provider failure into the framework
     * exception the service actually branches on.</p>
     *
     * <p>Refactoring Rationale: the context is CLEARED between the fixture guard and the insert, and the
     * reason was measured rather than anticipated. Without the clear this case failed reporting that a
     * different object with the same identifier was already associated with the persistence context --
     * the guard's own read had attached the seeded row, so the duplicate was refused by the CONTEXT
     * before any statement was issued, and the SQLSTATE the case exists to establish was never produced.
     * Clearing detaches the row so the insert reaches the engine and the constraint is what refuses.
     * Assumptions: dropping the guard instead would also have worked but would have left the case
     * asserting a duplicate of a key it had not established was there.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("a duplicate composite key is refused, and the engine reports SQLSTATE 23505")
    void aDuplicateCompositeKeyIsRefusedWithTheUniqueViolationState() {
        TransactionCategory.TransactionCategoryId seeded =
                new TransactionCategory.TransactionCategoryId("04", "0002");
        assertThat(this.categories.findByIdIs(seeded))
                .as("fixture guard only: the subject key must already be present")
                .isPresent();
        this.entityManager.clear();

        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> {
                    this.entityManager.persist(
                            new TransactionCategory(seeded, "Second row under one composite key"));
                    this.categories.flush();
                });

        assertThat(refusal)
                .as("the engine must refuse a second row under one composite key")
                .isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("the state the service branches on when it answers a duplicate create")
                .isEqualTo(SQLSTATE_UNIQUE_VIOLATION);
    }

    /**
     * Confirms a delete of a type this case gave a child to is refused with SQLSTATE 23503.
     *
     * <p>Purpose: this is the load-bearing assertion of the package and it is proven at no other level.
     * The constraint is declared at {@code TRNTYCAT.ddl} L6 and L7 with the restrict action and
     * reproduced at {@code V1__reference.sql} L255 to L257. Restrict is asserted rather than assumed
     * because the two alternatives an engine could implement are both silent data defects: a cascade
     * would remove the categories of the deleted type, and a set-null would violate the key column's own
     * not-null declaration at {@code V1__reference.sql} L167.</p>
     *
     * <p>Refactoring Rationale: {@code TransactionCategoryService.existsForType} performs a pre-check
     * before it deletes a parent, and that check is ADVISORY only. It is racy by construction -- two
     * callers deleting and inserting at once can each read zero -- so the declared foreign key is the
     * authoritative guard and the pre-check exists to produce a useful message rather than to protect the
     * table. That is why this case proves the refusal by attempting the delete and never by reading the
     * count; the count assertion below is a fixture guard and is labelled as one.</p>
     *
     * <p>Assumptions: the parent and the child are both created by this case rather than taken from the
     * seed, so what is asserted is the constraint alone and it survives a seed that gains or loses a row.
     * The parent is created BEFORE the child, which is the order the constraint requires of any writer
     * and the order {@code V2__seed_reference.sql} loads the two tables in.</p>
     *
     * <p>Assumptions: the flush is EXPLICIT. Without it the DELETE would be issued when the ambient
     * transaction ended, so the refusal would arrive after this method returned and the case would assert
     * nothing at all while still reading as green.</p>
     *
     * <p>Assumptions: the state is read by walking the cause chain for a SQL exception, which is how the
     * service reads it, rather than by matching a message or a provider-specific subclass. Asserting it
     * the same way the production code reads it is what makes this case evidence for that code.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("a delete of a type this case gave a child is refused, and the engine reports 23503")
    void aDeleteOfATypeThisCaseGaveAChildIsRefusedWithTheForeignKeyState() {
        String parent = "91";
        this.types.saveAndFlush(new TransactionType(parent, "Parent created by the referential case"));
        this.categories.saveAndFlush(new TransactionCategory(
                new TransactionCategory.TransactionCategoryId(parent, "0001"),
                "Child created by the referential case"));
        assertThat(this.categories.countByTypeCd(parent))
                .as("fixture guard only: the child must be written before the delete is attempted")
                .isEqualTo(1L);

        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> {
                    this.types.findByTypeCd(parent).ifPresent(this.types::delete);
                    this.types.flush();
                });

        assertThat(refusal)
                .as("the engine must refuse the delete rather than cascade or null the reference")
                .isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("the state the service branches on when it answers a referential conflict")
                .isEqualTo(SQLSTATE_FOREIGN_KEY_VIOLATION);
    }

    /**
     * Confirms a category naming a parent type that does not exist is refused with SQLSTATE 23503.
     *
     * <p>Purpose: the case above exercises the constraint from the parent's side, by deleting a
     * referenced row. This exercises it from the child's, by inserting a reference to a row that was
     * never there. Both directions are asserted deliberately: the same constraint governs them, but an
     * engine or a migration that declared the key without enforcing it on insert would pass the delete
     * case and fail here, and a schema that declared no key at all would pass neither.</p>
     *
     * <p>Assumptions: this insert DOES reach the engine, unlike the duplicate above, because the
     * composite key is genuinely new -- a merge against a key no row carries issues an INSERT. That is
     * the same property {@code TransactionTypeRepositoryIT} relies on when it creates a child of its
     * own.</p>
     *
     * <p>Assumptions: the flush is EXPLICIT, for the reason recorded on the case above.</p>
     *
     * <p>Assumptions: {@code 99} is a type code the seed never loads, which the distribution case above
     * asserts directly rather than leaving to inspection.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("a category naming a parent type that does not exist is refused with 23503")
    void anInsertOfACategoryWhoseParentTypeIsAbsentIsRefused() {
        String absentParent = "99";
        assertThat(this.types.findByTypeCd(absentParent))
                .as("fixture guard only: the parent must genuinely be absent")
                .isEmpty();

        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> this.categories.saveAndFlush(new TransactionCategory(
                        new TransactionCategory.TransactionCategoryId(absentParent, "0001"),
                        "Child of a parent that was never created")));

        assertThat(refusal)
                .as("the engine must refuse a reference to a row that does not exist")
                .isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("an unknown parent is the same violation as a referenced delete, not a different one")
                .isEqualTo(SQLSTATE_FOREIGN_KEY_VIOLATION);
    }

    /**
     * Confirms a type no category references can be deleted, so the refusal is the rule and not a bar.
     *
     * <p>Refactoring Rationale: the unreferenced type is CREATED here rather than found among the seeded
     * ones. An earlier form of this case searched the seed for a type with no categories and failed,
     * because every one of the seven seeded types has at least one child -- which is a property of the
     * seed rather than of the constraint. Creating the subject makes the case assert the constraint
     * itself, and it survives a seed that gains or loses a row.
     * {@code TransactionTypeRepositoryIT} records the same lesson from the other direction.</p>
     *
     * <p>Assumptions: without this case the two refusals above would be consistent with a table that
     * refused every parent delete, which is a different and much worse behaviour than restrict.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("a type no category references can be deleted")
    void anUnreferencedTypeCanBeDeleted() {
        String unreferenced = "90";
        assertThat(this.categories.countByTypeCd(unreferenced))
                .as("fixture guard only: the chosen code must be one the seed gives no children to")
                .isZero();

        this.types.saveAndFlush(new TransactionType(unreferenced, "Unreferenced for this case"));
        assertThat(this.types.findByTypeCd(unreferenced)).isPresent();

        this.types.findByTypeCd(unreferenced).ifPresent(this.types::delete);
        this.types.flush();

        assertThat(this.types.findByTypeCd(unreferenced))
                .as("a parent with no child is deletable, so restrict is conditional and not absolute")
                .isEmpty();
    }

    /**
     * Confirms an absent description is refused before a statement is ever issued.
     *
     * <p>Purpose: the column is {@code TRC_CAT_DATA VARCHAR(50) NOT NULL} at {@code TRNTYCAT.ddl} L4 and
     * {@code description VARCHAR(50) NOT NULL} at {@code V1__reference.sql} L198. Assumptions: the entity
     * refuses an absent value in its constructor and in its mutator, so on the write path this package is
     * permitted to use the value never reaches the engine and the engine's own not-null state is not
     * observable from here. What is asserted is therefore the guard that actually fires, and it is
     * asserted as such rather than described as an engine refusal it is not.
     * {@code TransactionTypeRepositoryIT} does reach the engine's not-null state, on the type table,
     * because that repository declares a native insert this one does not.</p>
     *
     * <p>Assumptions: the over-width refusal is asserted alongside it because the two guards share one
     * declaration. Fifty is the width both the data definition and the migration declare, so a
     * fifty-one-character description is refused at the boundary rather than truncated into the
     * column.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent description is refused before a statement is issued")
    void theDescriptionIsRefusedBeforeItReachesTheEngine() {
        TransactionCategory.TransactionCategoryId id =
                new TransactionCategory.TransactionCategoryId("08", "0001");

        assertThatExceptionOfType(NullPointerException.class)
                .as("the not-null column is guarded at the boundary, so no absent value is issued")
                .isThrownBy(() -> new TransactionCategory(id, null));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("fifty is the declared width, so an over-width value is refused and not truncated")
                .isThrownBy(() -> new TransactionCategory(
                        id, "x".repeat(TransactionCategory.DESCRIPTION_WIDTH + 1)));
    }

    /**
     * Confirms three key-positioned windows cover the whole seed with no duplicate and no gap.
     *
     * <p>Purpose: the listing orders by the key PAIR, so a position has to carry both halves. This case
     * proves the round trip of that two-part position behaviourally, by walking the whole table with it.
     * Assumptions: the codec that packs the pair into the single opaque position a caller receives is
     * private inside {@code TransactionCategoryService}, so it cannot be called from here; what can be
     * observed is whether feeding a position back yields the correct next window, which is the property
     * the codec exists to provide.</p>
     *
     * <p>Assumptions: eighteen seeded rows over a window of seven give three natural windows of seven,
     * seven and four, so no extra fixture is needed and the last window is short. The short read is
     * itself the signal that no further window exists, which is the same signal
     * {@code COTRTLIC.cbl} derives at its L1661 to L1675 from whether the surplus read succeeded.</p>
     *
     * <p>Assumptions: the position handed to each following read is the LAST DISPLAYED row, and it is
     * paired with a strictly-greater-than predicate. That pairing is self-consistent and it is the one
     * the authored service implements -- {@code TransactionCategoryRepository.findPageAfter} excludes its
     * position and {@code ReferencePaging.page} publishes the last row of the trimmed window as the
     * onward position. The baseline pairs the other way round and is equally consistent: at
     * {@code COTRTLIC.cbl} L343 the forward cursor compares INCLUSIVELY, so at its L1659 it records the
     * seventh displayed row and then at its L1673 OVERWRITES that with the surplus row it just read,
     * which is what stops the seventh row being served twice. Mixing the two -- an inclusive predicate
     * with a last-displayed position -- repeats exactly one row per window, which is why the invariant
     * below is asserted rather than a bare position value.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("three key-positioned windows cover the whole seed with no duplicate and no gap")
    void theWindowWalksTheWholeSeedInThreeWindowsWithNoDuplicateAndNoGap() {
        List<TransactionCategory> firstRead =
                this.categories.findFirstPage(Limit.of(WINDOW_WITH_LOOKAHEAD));
        assertThat(firstRead)
                .as("a full read including the surplus row is what reports a further window")
                .hasSize(WINDOW_WITH_LOOKAHEAD);
        List<TransactionCategory> windowOne = forwardWindow(firstRead);
        assertThat(keysOf(windowOne)).containsExactly(
                "010001", "010002", "010003", "010004", "010005", "020001", "020002");

        String positionOne = positionOf(windowOne);
        assertThat(positionOne)
                .as("the onward position is the last DISPLAYED row, not the surplus row read past it")
                .isEqualTo("020002");

        List<TransactionCategory> secondRead = readAfter(positionOne);
        assertThat(secondRead).hasSize(WINDOW_WITH_LOOKAHEAD);
        List<TransactionCategory> windowTwo = forwardWindow(secondRead);
        assertThat(keysOf(windowTwo))
                .as("the excluded position must not reappear at the head of the following window")
                .containsExactly(
                        "020003", "030001", "030002", "030003", "040001", "040002", "040003");

        String positionTwo = positionOf(windowTwo);
        List<TransactionCategory> thirdRead = readAfter(positionTwo);
        assertThat(thirdRead)
                .as("a short read is the signal that no further window exists")
                .hasSize(SEEDED_CATEGORIES - WINDOW_ROWS - WINDOW_ROWS);
        List<TransactionCategory> windowThree = forwardWindow(thirdRead);
        assertThat(keysOf(windowThree))
                .containsExactly("050001", "060001", "060002", "070001");

        List<String> walked = new ArrayList<>();
        walked.addAll(keysOf(windowOne));
        walked.addAll(keysOf(windowTwo));
        walked.addAll(keysOf(windowThree));

        assertThat(walked)
                .as("no row is served twice, which is what a mismatched position pairing would cause")
                .doesNotHaveDuplicates();
        assertThat(walked)
                .as("no row is skipped and the order is the seed's, so the three windows are a cover")
                .containsExactlyElementsOf(SEEDED_KEYS)
                .hasSize(SEEDED_CATEGORIES);
    }

    /**
     * Confirms a backward read arrives descending and reverses onto the window that precedes it.
     *
     * <p>Purpose: the two directions are deliberately asymmetric in the baseline and the asymmetry is
     * transcribed rather than smoothed. At {@code COTRTLIC.cbl} L343 the forward cursor compares
     * inclusively and at L351 orders ascending, while at L359 the backward cursor compares exclusively
     * and at L367 orders DESCENDING. The migrated backward walk keeps the descending order because
     * reading away from a position requires the rows NEAREST it, and the caller reverses the result so a
     * reader always receives ascending rows whichever direction was asked for.</p>
     *
     * <p>Assumptions: the emitted ORDER is asserted and not merely the membership. A test that checked
     * only which rows came back would pass against a walk that had lost the reversal, and a caller would
     * then render a window bottom to top.</p>
     *
     * <p>Assumptions: the surplus row sits at the OPPOSITE end of a reversed backward read from where it
     * sits on a forward one, so it is dropped from the leading end. Dropping the wrong end here removes a
     * row the caller should have seen instead of the one it should not, which is why this case asserts
     * that the reversed and trimmed result reproduces the middle window EXACTLY.</p>
     *
     * <p>Assumptions: backward availability is inferred rather than read from a component of the
     * published envelope, because that envelope carries no such component. Here it is inferred from the
     * position being one a caller was given, which is the same inference the assembling layer makes.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a backward read arrives descending and reverses onto the preceding window")
    void theBackwardWalkReadsDescendingAndReversesOntoThePrecedingWindow() {
        List<TransactionCategory> backwardRead = this.categories.findPageBefore(
                "05", "0001", Limit.of(WINDOW_WITH_LOOKAHEAD));

        assertThat(keysOf(backwardRead))
                .as("the query itself returns descending rows, nearest the position first")
                .containsExactly(
                        "040003", "040002", "040001", "030003", "030002", "030001",
                        "020003", "020002");

        List<TransactionCategory> published = backwardWindowAscending(backwardRead);
        assertThat(keysOf(published))
                .as("reversed and trimmed at the leading end, a backward read is the preceding window")
                .containsExactly(
                        "020003", "030001", "030002", "030003", "040001", "040002", "040003");
        assertThat(keysOf(published))
                .as("the position a backward read was given is excluded from its result")
                .doesNotContain("050001");
    }

    /**
     * Confirms a row inserted behind a held position leaves the following window untouched.
     *
     * <p>Alternatives Considered: positioning a window by its distance from the start of the table
     * instead of by a key. Rejected, and this case is what makes the rejection evidence rather than
     * assertion. A window positioned by distance has to re-count the rows preceding it on every request,
     * so a row inserted behind the caller's position shifts every later window by one: the next request
     * then serves a row the caller has already seen and eventually omits one it has not. That is the
     * ordinary situation for this data rather than an edge case, because the baseline browse program
     * deletes rows from the table it is browsing while its companion maintenance program inserts into
     * it.</p>
     *
     * <p>Assumptions: the inserted key sorts INSIDE the first window's range, which the case establishes
     * by comparison rather than by inspection -- it is greater than that window's first key and less than
     * its last. Type {@code 01} already exists in the seed, so the insert satisfies the foreign key and
     * this case exercises the window arithmetic rather than the constraint.</p>
     *
     * <p>Assumptions: the flush is EXPLICIT, so the row is in the table before the second read is issued.
     * Without it the INSERT would be deferred to the end of the ambient transaction and the second read
     * would be answered from a table the row had never reached, so the case would pass without having
     * tested anything.</p>
     *
     * <p>Trade-offs: the insert is rolled back with the ambient transaction, so this case proves the
     * property against a row that never commits. The alternative, committing it, would leave a
     * nineteenth row behind and break every count the reading cases state.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("a row inserted behind a held position leaves the following window untouched")
    void aRowInsertedBehindTheHeldPositionDoesNotDisturbTheFollowingWindow() {
        List<TransactionCategory> windowOne =
                forwardWindow(this.categories.findFirstPage(Limit.of(WINDOW_WITH_LOOKAHEAD)));
        String position = positionOf(windowOne);
        List<String> followingBefore = keysOf(forwardWindow(readAfter(position)));

        String interleaved = POPULATED_TYPE_CD + "0006";
        assertThat(interleaved.compareTo(keysOf(windowOne).get(0)))
                .as("the inserted key must sort after the first window's opening row")
                .isPositive();
        assertThat(interleaved.compareTo(position))
                .as("the inserted key must sort before the held position, so it lands behind it")
                .isNegative();

        this.categories.saveAndFlush(new TransactionCategory(
                new TransactionCategory.TransactionCategoryId(POPULATED_TYPE_CD, "0006"),
                "Inserted behind the held position"));
        assertThat(keysOf(this.categories.findFirstPage(Limit.of(100))))
                .as("fixture guard only: the row must really be in the table before the second read")
                .contains(interleaved)
                .hasSize(SEEDED_CATEGORIES + 1);

        List<String> followingAfter = keysOf(forwardWindow(readAfter(position)));

        assertThat(followingAfter)
                .as("the key-positioned window neither repeats nor skips a row after the insert")
                .containsExactlyElementsOf(followingBefore);
        assertThat(followingAfter)
                .as("a row behind the position is not served again by the window that follows it")
                .doesNotContain(interleaved);
    }

    /**
     * Confirms the type-narrowed walk returns one type's categories, ascending, and nothing else.
     *
     * <p>Purpose: the narrowed walk is what the by-parent listing reads, so its scope is the property a
     * caller depends on. Type {@code 01} carries five of the eighteen rows, which is fewer than the
     * window, so the read is short and no surplus row arrives -- the same signal that reports no further
     * window.</p>
     *
     * <p>Assumptions: the categories under one type are consecutive in the full key order as well, so the
     * narrowed walk returning five rows is checked against the same five keys the unnarrowed walk opens
     * with. A narrowing that leaked a row from another type would satisfy a count but not this.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the type-narrowed walk returns one type's categories ascending and nothing else")
    void theTypeNarrowedWalkIsScopedAndOrdered() {
        List<TransactionCategory> read = this.categories.findFirstPageOfType(
                POPULATED_TYPE_CD, Limit.of(WINDOW_WITH_LOOKAHEAD));

        assertThat(read)
                .as("this type carries fewer rows than the window, so no surplus row arrives")
                .hasSize(5);
        assertThat(read).allSatisfy(row ->
                assertThat(row.getTypeCd()).isEqualTo(POPULATED_TYPE_CD));
        assertThat(keysOf(read))
                .containsExactly("010001", "010002", "010003", "010004", "010005");
    }

    /**
     * Confirms both composite walks exclude the position they are given, in either direction.
     *
     * <p>Purpose: strictness is asserted directly because it is the half of the pairing that the position
     * choice depends on. The migrated forward walk compares strictly, which differs from the inclusive
     * comparison at {@code COTRTLIC.cbl} L343; the difference is deliberate and it is what makes pairing
     * the predicate with the last displayed row correct. The narrowed walks are asserted alongside the
     * unnarrowed ones because they are separate queries and could drift apart.</p>
     *
     * <p>Assumptions: the subject is a row with neighbours on both sides, so neither direction can appear
     * strict merely by running out of rows.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("both composite walks exclude the position they are given, in either direction")
    void theCompositeWalksExcludeTheirPositionInBothDirections() {
        String typeCd = "03";
        String catCd = "0002";
        String position = typeCd + catCd;

        assertThat(keysOf(this.categories.findPageAfter(typeCd, catCd, Limit.of(100))))
                .doesNotContain(position)
                .startsWith("030003");
        assertThat(keysOf(this.categories.findPageBefore(typeCd, catCd, Limit.of(100))))
                .doesNotContain(position)
                .startsWith("030001");
        assertThat(keysOf(this.categories.findPageOfTypeAfter(typeCd, catCd, Limit.of(100))))
                .doesNotContain(position)
                .containsExactly("030003");
        assertThat(keysOf(this.categories.findPageOfTypeBefore(typeCd, catCd, Limit.of(100))))
                .doesNotContain(position)
                .containsExactly("030001");
    }

    /**
     * Reduces a walk's rows to the concatenated composite keys every assertion here is written in.
     *
     * @param rows the rows a walk returned, in the order it returned them; must not be {@code null}
     * @return the type code followed by the category code for each row, in the order given
     */
    // WHY : Alternatives Considered: comparing the identity objects themselves, which carry value
    //       equality and would compare both halves correctly. The concatenated form is used instead
    //       because it is the form the seed dataset's own first six bytes are read in and the form a
    //       failure message is legible in -- a mismatch reports 020003 rather than a rendered object,
    //       so the row at fault is identifiable without decoding the report.
    private static List<String> keysOf(List<TransactionCategory> rows) {
        List<String> keys = new ArrayList<>(rows.size());
        for (TransactionCategory row : rows) {
            keys.add(row.getTypeCd() + row.getCatCd());
        }
        return keys;
    }

    /**
     * Names the onward position of a displayed window, which is its last row's composite key.
     *
     * @param window the rows a caller would publish, already trimmed of any surplus row; must not be
     *     empty, since an empty window has no position to hand on
     * @return the concatenated composite key of the last row in that window
     */
    // WHY : Alternatives Considered: taking the position from the SURPLUS row instead of the last
    //       displayed one, which is what the baseline does -- COTRTLIC.cbl records the seventh
    //       displayed row at its physical line 1659 and then overwrites it at 1673 with the row it
    //       read past the window. That choice is correct THERE because its cursor compares
    //       inclusively at physical line 343, so the surplus row is the first row the next window
    //       must return. It is wrong here, because the migrated walk compares strictly: pairing a
    //       strict predicate with the surplus row would SKIP that row entirely, and pairing an
    //       inclusive predicate with the last displayed row would serve it twice. The last displayed
    //       row is the half that matches this walk, and the no-duplicate assertion is what proves it.
    private static String positionOf(List<TransactionCategory> window) {
        TransactionCategory last = window.get(window.size() - 1);
        return last.getTypeCd() + last.getCatCd();
    }

    /**
     * Reads the window strictly after a position expressed as one concatenated composite key.
     *
     * @param position the concatenated composite key to resume after, being two characters of type code
     *     followed by four of category code exactly as the columns store them
     * @return the rows the forward walk returned, bounded at the window plus its one surplus row
     */
    // WHY : Assumptions: the two halves are split at a FIXED offset rather than at a separator, because
    //       the widths are declared and invariant -- TRNTYCAT.ddl L2 and L3 declare two and four
    //       characters and the entity's identity refuses any other width. Splitting on a separator
    //       would invent a format the columns do not have.
    private List<TransactionCategory> readAfter(String position) {
        return this.categories.findPageAfter(
                position.substring(0, TransactionCategory.TYPE_CD_WIDTH),
                position.substring(TransactionCategory.TYPE_CD_WIDTH),
                Limit.of(WINDOW_WITH_LOOKAHEAD));
    }

    /**
     * Drops the surplus row from a FORWARD read, leaving the window a caller would publish.
     *
     * @param read the rows a forward walk returned, bounded at the window plus one surplus row
     * @return every row when the read was short, or all but the LAST row when it was full
     */
    // WHY : Assumptions: the surplus row of a FORWARD read sits at the trailing end, because the rows
    //       arrive ascending from the position outwards. Dropping the leading end here would discard
    //       the first row the caller is entitled to see and silently shift every window by one, which
    //       is why this helper and its backward counterpart trim opposite ends rather than sharing one
    //       implementation.
    private static List<TransactionCategory> forwardWindow(List<TransactionCategory> read) {
        if (read.size() <= WINDOW_ROWS) {
            return List.copyOf(read);
        }
        return List.copyOf(read.subList(0, WINDOW_ROWS));
    }

    /**
     * Reverses a BACKWARD read onto ascending order and drops its surplus row from the leading end.
     *
     * @param read the rows a backward walk returned, descending and bounded at the window plus one
     * @return the rows a caller would publish, ascending: all but the FIRST once reversed when the read
     *     was full, and every row reversed when it was short
     */
    // WHY : Refactoring Rationale: the reversal happens BEFORE the trim, and the order of those two
    //       steps is the whole content of this helper. A backward read arrives descending, so its
    //       surplus row is the one FURTHEST from the position -- which lands at the leading end once
    //       the rows are reversed, the opposite end from where a forward read's surplus sits.
    //       Trimming first and reversing afterwards would drop the row NEAREST the position, removing
    //       a row the caller should have seen while leaving a plausible-looking window of the right
    //       size; the backward case asserts the emitted sequence precisely so that substitution
    //       cannot pass.
    private static List<TransactionCategory> backwardWindowAscending(List<TransactionCategory> read) {
        List<TransactionCategory> ascending = new ArrayList<>(read);
        Collections.reverse(ascending);
        if (ascending.size() <= WINDOW_ROWS) {
            return List.copyOf(ascending);
        }
        return List.copyOf(ascending.subList(1, ascending.size()));
    }

    /**
     * Reads the description stored under one composite key, failing the case when no row carries it.
     *
     * @param typeCd the type half of the key, at its declared width of two characters
     * @param catCd the category half of the key, at its declared width of four characters
     * @return the description exactly as the column returns it, with no trimming applied
     */
    // WHY : Trade-offs: this helper ASSERTS before it returns, which puts an assertion inside a reader
    //       and is not where a reader expects one. It is accepted because the alternative is returning
    //       an empty optional to three callers that would each then fail on an absent value with a
    //       report naming the description rather than the missing row. Failing here names the key that
    //       was not found, so a seed regression is diagnosed from the failure message alone.
    private String descriptionOf(String typeCd, String catCd) {
        Optional<TransactionCategory> row = this.categories.findByIdIs(
                new TransactionCategory.TransactionCategoryId(typeCd, catCd));
        assertThat(row).as("the seed must have loaded the row %s%s", typeCd, catCd).isPresent();
        return row.get().getDescription();
    }

    /**
     * Reads the SQLSTATE out of a violation the way the service under test reads it.
     *
     * @param failure the violation the provider raised; must not be {@code null}
     * @return the first non-blank SQLSTATE found by walking the cause chain, or {@code null} when the
     *     chain reports none
     */
    // WHY : Assumptions: the state is reached through the CAUSE CHAIN and never through a message. A
    //       message is provider text that can change between releases without the state changing, and
    //       the service classifies on the state, so asserting the state is what makes these cases
    //       evidence for that classification rather than for a string.
    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException reported
                    && reported.getSQLState() != null
                    && !reported.getSQLState().isBlank()) {
                return reported.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
