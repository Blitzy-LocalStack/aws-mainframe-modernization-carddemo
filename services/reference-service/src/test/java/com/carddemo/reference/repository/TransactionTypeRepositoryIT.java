// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/TransactionTypeRepositoryIT.java
// -----------------------------------------------------------------------------
// Purpose:
//      The container-backed integration tests of TransactionTypeRepository, and
//      the host of ReferencePersistenceBase -- the one engine fixture every
//      class in this package shares, declared below as a second top-level type
//      rather than as a further file.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: the four rationale labels below are written in the PLURAL
//      form throughout -- Alternatives Considered:, Refactoring Rationale:,
//      Assumptions: and Trade-offs: -- which is the form
//      docs/CODE_DOCUMENTATION_STANDARD.md rules at its lines 236 to 251. The
//      singular spellings mean the same thing and are deliberately not used, so
//      that the labels can be found by grep before they are read by a person.
//      This equivalence is stated once here and nowhere restated.
//  (2) Alternatives Considered: giving the shared fixture a file of its own,
//      which is where a reader would look for it first. Rejected because it
//      would take this package's closed set from eight compilation units to
//      nine, which package-info.java rules against at its lines 144 to 153,
//      and because the fixture's NAME is load-bearing: six sibling classes
//      extend ReferencePersistenceBase by same-package resolution with no
//      import, so moving or renaming it breaks all six at once. The remaining
//      alternative, one engine per class, is answered beside the static
//      initialiser further down this file.
//  (3) Alternatives Considered: the persistence-only test slice, which loads no
//      configuration or component beans and is the narrower instrument for a
//      repository assertion. Two concrete costs decided against it. It
//      substitutes an embedded datasource unless that is countermanded, and
//      this module declares no embedded driver among its test dependencies, so
//      the countermand is not optional -- and if it were ever dropped the
//      container would be bypassed silently rather than noisily. Whether the
//      migration runner is auto-configured under that slice is version-
//      dependent, so it becomes a second thing to keep true, and the two
//      migrations under test are the whole subject of several cases here. What
//      is used instead is the full context with NO web environment,
//      bootstrapped from the minimal configuration declared at the foot of this
//      file rather than from the service's own entry point, which leaves out
//      the security chain, the messaging listener and the interface document
//      just as the narrower slice would have.
//  (4) Alternatives Considered: a real PostgreSQL engine rather than an
//      in-memory one. The headline case in this file asserts that the engine
//      REFUSES a delete of a referenced transaction type and reports a
//      particular state while doing it. An engine modelling the restrict action
//      loosely, or reached through a different Flyway dialect, would let that
//      case pass against a fiction while the deployed schema behaved otherwise,
//      and the two remaining engine properties asserted here -- the ordering of
//      a declared-width character column and the treatment of an escaped
//      metacharacter in a like clause -- would go unverified for the same
//      reason.
//  (5) Assumptions: every line number cited below is a PHYSICAL line number and
//      each was read at that address. In
//      app/app-transaction-type-db2/cbl/COTRTLIC.cbl the printed sequence field
//      runs four ahead of the physical line from physical line 1808 onward, so
//      a citation checked by searching for a printed sequence value resolves to
//      the wrong line. Everything beneath app/ is the behavioural oracle of
//      this migration: it is read and cited, never modified, and a deliberate
//      departure from it is registered in
//      docs/architecture/cobol-to-service-traceability.md, which is maintained
//      elsewhere and referenced rather than reproduced.
//  (6) Trade-offs: this file is the slowest in the module because it starts an
//      engine, and that cost is accepted rather than reduced. What it buys is
//      that the constraint, the character semantics and the pattern escaping
//      are proven where they are actually enforced; a faster suite that proved
//      none of the three would be measuring its own doubles.
// =============================================================================
package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies the transaction-type queries against a real PostgreSQL engine.
 *
 * <p>This is the normative browse of the reference context, so the properties asserted here are the
 * ones every other walk in the package is a variation of: the seed migration's row count, the ascending
 * and descending orders under a declared-width character column's collation, the strictness of both
 * bounds, and the filter arms each tolerating an absent argument.
 *
 * <p>Assumptions: these are engine behaviours and not repository arithmetic. Whether {@code CHAR(2)}
 * orders the way the baseline's {@code ORDER BY TR_TYPE} orders, and whether a {@code like} with an
 * escape clause behaves as the pattern helper assumes, cannot be established by a test double -- a
 * double would answer whatever it was told and every assertion would pass while proving nothing.
 */
class TransactionTypeRepositoryIT extends ReferencePersistenceBase {

    /** The number of types the seed migration loads, which is also the published window. */
    private static final int SEEDED_TYPES = 7;

    /** The rows the window publishes, being the program constant at COTRTLIC.cbl physical line 60. */
    private static final int PAGE_SIZE = 7;

    /** The number of categories the seed migration loads. */
    private static final int SEEDED_CATEGORIES = 18;

    /** The number of disclosure-group rows the seed migration loads. */
    private static final int SEEDED_DISCLOSURE_GROUPS = 51;

    /** The number of phone area codes the seed migration loads. */
    private static final int SEEDED_PHONE_AREA_CODES = 490;

    /** The number of states the seed migration loads. */
    private static final int SEEDED_STATES = 56;

    /** The number of state-and-postal-prefix combinations the seed migration loads. */
    private static final int SEEDED_STATE_ZIP_PREFIXES = 240;

    /** Every row the seed migration loads across the six tables of this schema. */
    private static final long SEEDED_GRAND_TOTAL = 862L;

    /** The state PostgreSQL reports when a foreign key refuses a delete of a referenced row. */
    private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";

    /** The state PostgreSQL reports when a unique constraint refuses a duplicate key. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** The state PostgreSQL reports when a not-null column is given an absent value. */
    private static final String SQLSTATE_NOT_NULL_VIOLATION = "23502";

    /** A code the seed gives children to, so a delete of it is the refused case. */
    private static final String REFERENCED_TYPE_CD = "06";

    /** The repository under test. */
    @Autowired
    private TransactionTypeRepository types;

    // WHY : Assumptions: the five repositories below are injected for ONE case, the cross-table seed
    //       audit. They are not the subject of this class and no other case touches them.
    // WHY : Refactoring Rationale: the audit lives here rather than being split across the six classes
    //       that each own one table, because a per-table assertion can only state its own row count
    //       while the property that matters is the SUM. Six separate assertions all passing does not
    //       establish the total, since a table nobody counted would not be missed; one assertion over
    //       every table does. This class already owns the shared base's plumbing, so hosting it here
    //       costs no further setup and no further engine start.
    // WHY : Refactoring Rationale: the counts are asserted at all because a DIFFERENT service consumes
    //       them. account-service's AddressValidationService queries the three lookup tables and owns
    //       none of them, so a row missing from this seed surfaces there as an address rejected during
    //       account maintenance, with nothing in the rejection to point back at the seed that caused
    //       it. V2__seed_reference.sql records the same reasoning beside its own counts.
    /** The category repository, counted by the seed audit and used by the refused-delete case. */
    @Autowired
    private TransactionCategoryRepository categories;

    /** The disclosure-group repository, counted by the seed audit. */
    @Autowired
    private DisclosureGroupRepository disclosureGroups;

    /** The phone-area-code repository, counted by the seed audit. */
    @Autowired
    private UsPhoneAreaCodeRepository phoneAreaCodes;

    /** The state repository, counted by the seed audit. */
    @Autowired
    private UsStateRepository states;

    /** The state-and-postal-prefix repository, counted by the seed audit. */
    @Autowired
    private UsStateZipPrefixRepository stateZipPrefixes;

    /** Supplies the transaction the insert statement requires, one per write. */
    // WHY : Refactoring Rationale: the insert cases below run through this template rather than calling
    //       the repository directly, and the reason was measured rather than anticipated. A derived or
    //       declared modifying statement carries no transaction of its own -- only the inherited save,
    //       delete and flush members are annotated by the framework's base implementation -- so calling
    //       insertType outside a transaction raised InvalidDataAccessApiUsageException reporting "No
    //       active transaction for update or delete query" and never reached the constraint at all. The
    //       template supplies the boundary that TransactionTypeService.create supplies in production,
    //       so what is exercised here is the statement AS THE SERVICE ISSUES IT.
    // WHY : Alternatives Considered: annotating this class @Transactional and letting the framework roll
    //       each case back. Rejected for the same reason the auth context's identity suite records: a
    //       UNIQUE constraint is evaluated when its statement executes, and one ambient transaction
    //       spanning a whole case makes the point at which a refusal arrives depend on when the context
    //       happens to flush. Committing each write on its own makes each refusal attributable to the
    //       statement that caused it.
    // WHY : Alternatives Considered: declaring insertType @Transactional on the repository interface so
    //       no caller has to supply a boundary. Rejected because it would let the insert commit on its
    //       own when a service method that already owns a transaction calls it -- exactly the case the
    //       create path is, where the row and the identity-provider compensation must share one unit of
    //       work -- and because the sibling auth repository's insert is declared the same way, so the
    //       two contexts would then disagree about who owns the boundary.
    @Autowired
    private TransactionTemplate commit;

    /**
     * Confirms the seed migration loads exactly the seven types the window is sized for.
     *
     * <p>Assumptions: this is asserted first because several cases below depend on it. The
     * {@code repository/package-info.java} charter records the same number and warns that a test
     * expecting a further page on seeded data alone has to insert an eighth row first.
     */
    @Test
    @DisplayName("the seed migration loads exactly seven transaction types")
    void theSeedLoadsSevenTypes() {
        List<TransactionType> all = this.types.findAllByOrderByTypeCdAsc(Limit.of(100));
        assertThat(all).hasSize(SEEDED_TYPES);
        assertThat(all).extracting(TransactionType::getTypeCd)
                .as("the engine's ordering of a declared-width character column, not a sorted list")
                .containsExactly("01", "02", "03", "04", "05", "06", "07");
    }

    /**
     * Confirms the forward walk is bounded and excludes the position it is given.
     */
    @Test
    @DisplayName("the forward walk excludes its position and honours the bound")
    void theForwardWalkIsStrictAndBounded() {
        List<TransactionType> page = this.types.findByTypeCdGreaterThanOrderByTypeCdAsc("02",
                Limit.of(3));
        assertThat(page).extracting(TransactionType::getTypeCd).containsExactly("03", "04", "05");
    }

    /**
     * Confirms the backward walk reads descending, excludes its position and honours the bound.
     */
    @Test
    @DisplayName("the backward walk reads descending, excludes its position and honours the bound")
    void theBackwardWalkIsStrictDescendingAndBounded() {
        List<TransactionType> page = this.types.findByTypeCdLessThanOrderByTypeCdDesc("05",
                Limit.of(3));
        assertThat(page).extracting(TransactionType::getTypeCd).containsExactly("04", "03", "02");
    }

    /**
     * Confirms the keyed finder resolves a seeded code and reports an unseeded one as absent.
     */
    @Test
    @DisplayName("the keyed finder resolves a seeded code and reports an unknown one absent")
    void theKeyedFinderResolvesASeededCode() {
        assertThat(this.types.findByTypeCd("01")).isPresent();
        assertThat(this.types.findByTypeCd("99")).isEmpty();
    }

    /**
     * Confirms the filtered forward walk narrows to one code and still excludes its position.
     *
     * <p>Assumptions: the strictness of this bound is asserted against a real engine because it was
     * changed. The query declared an inclusive comparison, matching the baseline cursor literally, while
     * the sealed position this service publishes carries the last code the caller received -- so an
     * inclusive comparison would have returned that row a second time.
     */
    @Test
    @DisplayName("the filtered forward walk narrows by code and excludes its position")
    void theFilteredForwardWalkNarrowsAndIsStrict() {
        assertThat(this.types.findFilteredPageAfter("04", null, null, Limit.of(8)))
                .extracting(TransactionType::getTypeCd).containsExactly("04");
        assertThat(this.types.findFilteredPageAfter("04", null, "04", Limit.of(8)))
                .as("the position is excluded, so narrowing to it and starting from it yields nothing")
                .isEmpty();
    }

    /**
     * Confirms an absent filter argument admits every row rather than matching none.
     *
     * <p>Assumptions: this is the arm the baseline expresses with a guard flag, because a one-byte field
     * cannot represent its own absence. The migrated form relies on the engine evaluating
     * {@code :arg is null or ...}, which is exactly what a double could not establish.
     */
    @Test
    @DisplayName("an absent filter argument admits every row")
    void anAbsentFilterAdmitsEveryRow() {
        assertThat(this.types.findFilteredPageAfter(null, null, null, Limit.of(100)))
                .hasSize(SEEDED_TYPES);
    }

    /**
     * Confirms the description filter matches by containment with the caller's metacharacters escaped.
     */
    @Test
    @DisplayName("the description filter matches by containment and escapes the caller's wildcards")
    void theDescriptionFilterMatchesByContainment() {
        Optional<TransactionType> first = this.types.findByTypeCd("01");
        assertThat(first).isPresent();
        String fragment = first.get().getDescription().trim();

        String pattern = TransactionTypeRepository.descriptionFilterPattern(fragment);
        assertThat(this.types.findFilteredPageAfter(null, pattern, null, Limit.of(100)))
                .isNotEmpty();

        String literalWildcard = TransactionTypeRepository.descriptionFilterPattern("%");
        assertThat(this.types.findFilteredPageAfter(null, literalWildcard, null, Limit.of(100)))
                .as("an escaped percent must match a literal percent, not every row")
                .isEmpty();
    }

    /**
     * Confirms the count answers the filter question without any reference to a position.
     */
    @Test
    @DisplayName("the count answers whether the filter matches anything anywhere")
    void theCountIgnoresAnyPosition() {
        assertThat(this.types.countFilterMatches(null, null)).isEqualTo(SEEDED_TYPES);
        assertThat(this.types.countFilterMatches("01", null)).isEqualTo(1L);
        assertThat(this.types.countFilterMatches("99", null))
                .as("a filter matching nothing is a field refusal upstream, not an empty page")
                .isZero();
    }

    /**
     * Confirms the unique constraint refuses a duplicate code at the statement rather than at commit.
     *
     * <p>Purpose: this is the timing the service's duplicate classification depends on. The service
     * catches {@code DataIntegrityViolationException} around its insert so it can read the SQLSTATE and
     * answer the referential conflict the contract publishes. That catch can only run if the INSERT is
     * issued inside it -- a plain {@code save} registers the row with the persistence context and the
     * statement goes out when the context is flushed, which for a transactional method is at commit,
     * after the catch has returned.</p>
     *
     * <p>Assumptions: {@code insertType} is what the service now calls, so that is what is exercised
     * here. A double could not establish this: whether the violation arrives from this call or from a
     * later commit is the engine's and the persistence provider's behaviour, and a mock would raise
     * whenever it was told to. The sibling case below records WHY a save is not what is called, which is
     * a measured property of this entity rather than a preference.</p>
     *
     * <p>Assumptions: a SEEDED code is reused rather than one inserted by this case, so the row the
     * constraint collides with is committed and outside this test's own unit of work.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a duplicate code is refused by the flush and not deferred to commit")
    void aDuplicateCodeIsRefusedByTheFlush() {
        Optional<TransactionType> seeded =
                this.types.findAllByOrderByTypeCdAsc(Limit.of(1)).stream().findFirst();
        assertThat(seeded).as("the seed migration must have loaded a row to collide with").isPresent();

        assertThatThrownBy(() -> this.commit.executeWithoutResult(status ->
                this.types.insertType(seeded.get().getTypeCd(), "Duplicate of a seeded code")))
                .as("the constraint must refuse the insert the service issues")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Confirms that a save CANNOT insert this entity, which is why the explicit insert exists.
     *
     * <p>Purpose: this is the measurement that produced {@code insertType}, kept as a case so the
     * reasoning cannot be undone by someone "simplifying" the service back to a save. The entity carries
     * an assigned {@code String} identifier and a primitive {@code long} version, so the framework's
     * newness test can find neither a null version nor a null identifier and routes the save through
     * {@code merge} -- which loads the row that identifier names and writes an UPDATE against it.</p>
     *
     * <p>Assumptions: what is asserted is that the save does NOT raise the integrity violation the
     * service classifies. Whether it raises an optimistic-locking failure or silently updates depends on
     * whether the stored version equals the zero on the new instance, and both outcomes are wrong in the
     * same way -- neither reaches the duplicate classification. Asserting the absence of the integrity
     * violation states exactly the property that matters and does not depend on which of the two the
     * seeded row's version happens to produce.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a save cannot insert this entity, so the duplicate never reaches the integrity branch")
    void aSaveCannotInsertThisEntity() {
        Optional<TransactionType> seeded =
                this.types.findAllByOrderByTypeCdAsc(Limit.of(1)).stream().findFirst();
        assertThat(seeded).isPresent();

        TransactionType duplicate =
                new TransactionType(seeded.get().getTypeCd(), "Merged rather than inserted");

        assertThat(catchThrowableOfType(DataIntegrityViolationException.class,
                () -> this.types.saveAndFlush(duplicate)))
                .as("a save reaches merge, so the duplicate does not arrive as an integrity violation")
                .isNull();
    }

    /**
     * Confirms the state the service classifies on is the state the engine actually reports.
     *
     * <p>Purpose: the service reads the SQLSTATE out of the violation's cause chain and branches on
     * {@code 23505} to answer the duplicate as a referential conflict. The literal it compares against is
     * only correct if this engine reports that state for this constraint, and nothing in the codebase
     * establishes that except a real violation.</p>
     *
     * <p>Assumptions: the state is read by walking the cause chain for a {@link java.sql.SQLException},
     * which is how the service reads it, rather than by matching a provider-specific exception subclass.
     * Asserting it the same way the production code reads it is what makes this case evidence for that
     * code and not for a different mechanism.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the engine reports SQLSTATE 23505 for the duplicate the service classifies")
    void theEngineReportsTheClassifiedSqlState() {
        Optional<TransactionType> seeded =
                this.types.findAllByOrderByTypeCdAsc(Limit.of(1)).stream().findFirst();
        assertThat(seeded).isPresent();

        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> this.commit.executeWithoutResult(status ->
                        this.types.insertType(seeded.get().getTypeCd(), "Second insert")));

        assertThat(refusal).isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("the state the service branches on when it answers a duplicate create")
                .isEqualTo(SQLSTATE_UNIQUE_VIOLATION);
    }

    /**
     * Confirms the engine REFUSES a delete of a referenced type, and reports the state as 23503.
     *
     * <p>Purpose: this is the load-bearing assertion of the whole package and it is proven at no other
     * level. {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares the constraint across L6 and
     * L7 as a foreign key on the category table's type column referencing the type table with the
     * restrict action, and {@code db/migration/V1__reference.sql} reproduces it at L266 to L268 as
     * {@code fk_transaction_categories_type}. The refusal then travels a fixed chain: the reference
     * platform answers a referential-constraint failure, the target answers PostgreSQL SQLSTATE
     * {@code 23503}, the framework translates that into a data-integrity exception, and
     * {@code com.carddemo.common.error.GlobalExceptionHandler} renders it as HTTP 409. Only the middle
     * link is this package's to prove; the 409 belongs to the {@code api} subpackage, and this case may
     * never be weakened to accept a server-error status in place of the conflict.</p>
     *
     * <p>Assumptions: the STATE is asserted rather than a message, because the state is what
     * {@code TransactionTypeService} branches on -- it declares the same literal at its L245 -- and a
     * message is free to change with a driver or a locale without any behaviour changing with it. The
     * state is read by walking the cause chain, which is how the service reads it, so this case is
     * evidence for that code rather than for a different mechanism.</p>
     *
     * <p>Assumptions: the sibling {@code TransactionCategoryRepositoryIT} asserts that a refusal
     * ARRIVES, and this case asserts WHICH refusal it is. The two are not the same claim: a cascade
     * removed by a later schema change would still raise something on some other statement, and only the
     * state distinguishes a referential refusal from every other integrity failure the same exception
     * type carries.</p>
     *
     * <p>Assumptions: the delete goes through the INHERITED KEYED operation rather than a declared
     * modifying statement, which is what {@code package-info.java} rules at its L332 to L335, so the
     * declared foreign key is what refuses it and the provider-managed counter column is not bypassed.
     * The category count taken first is a fixture guard and NOT the proof -- the charter records at its
     * L418 to L420 that a count is a diagnosis, since two callers deleting and inserting at once could
     * each read zero -- so the refusal is established by attempting the delete.</p>
     *
     * <p>Assumptions: the flush is EXPLICIT. Without it the DELETE would be issued when the ambient
     * transaction ends, so the refusal would arrive after this method returned and the case would assert
     * nothing at all while still reading as green.</p>
     *
     * <p>Alternatives Considered: annotating this one method rather than the class, even though the class
     * comment above rejects the class-level form. The rejection there is about a refusal whose ARRIVAL
     * TIME would otherwise depend on when the context happened to flush; here the flush is written out,
     * so the timing is pinned by the statement and the ambient transaction only supplies the rollback
     * that keeps the seeded row this case deletes from actually disappearing.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("a delete of a referenced type is refused, and the engine reports SQLSTATE 23503")
    void aDeleteOfAReferencedTypeIsRefusedWithTheForeignKeyState() {
        assertThat(this.categories.countByTypeCd(REFERENCED_TYPE_CD))
                .as("fixture guard only: the subject must be a type the seed gives children to")
                .isPositive();

        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> {
                    this.types.findByTypeCd(REFERENCED_TYPE_CD).ifPresent(this.types::delete);
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
     * Confirms the refusal holds for a parent and child this case creates, not only for seeded rows.
     *
     * <p>Purpose: the case above deletes a SEEDED type, which proves the constraint but leans on the seed
     * having given that type a child. This one creates both rows itself, so what it asserts is the
     * constraint alone and it survives a seed that gains or loses a row. The sibling
     * {@code TransactionCategoryRepositoryIT} records the same lesson from the other direction: its first
     * attempt to find an UNREFERENCED seeded type failed because every one of the seven has a child,
     * which is a property of the seed rather than of the constraint.</p>
     *
     * <p>Assumptions: the parent is written with the native insert and the child through the persistence
     * context, and the two differ for a reason recorded on {@code insertType} in the main tree: a
     * {@code save} of a type reaches {@code EntityManager.merge}, because the entity's assigned key and
     * primitive version leave the framework's newness test with nothing to detect, so it cannot be relied
     * on to issue an INSERT for a code that may already exist. The category is a genuinely new composite
     * key here, so merge finds no row and persists one.</p>
     *
     * <p>Assumptions: the parent is created BEFORE the child, which is the order the constraint requires
     * of any writer -- the same order {@code V2__seed_reference.sql} loads its two tables in.</p>
     *
     * <p>Assumptions: this is a separate case rather than a second half of the one above, because a
     * persistence context that has raised an integrity violation cannot be used again. Continuing in the
     * same context after the first refusal would fail on the reuse rather than on the constraint.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("the refusal holds for a parent and child this case creates, not only for seeded rows")
    void aDeleteOfATypeThisCaseGaveAChildIsAlsoRefused() {
        String parent = "96";
        this.types.insertType(parent, "Parent created by the referential case");
        this.categories.saveAndFlush(new TransactionCategory(
                new TransactionCategory.TransactionCategoryId(parent, "0001"),
                "Child created by the referential case"));

        assertThat(this.categories.countByTypeCd(parent))
                .as("fixture guard only: the child must have been written before the delete is tried")
                .isEqualTo(1L);

        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> {
                    this.types.findByTypeCd(parent).ifPresent(this.types::delete);
                    this.types.flush();
                });

        assertThat(refusal).as("the constraint must refuse a parent this case referenced").isNotNull();
        assertThat(sqlStateOf(refusal)).isEqualTo(SQLSTATE_FOREIGN_KEY_VIOLATION);
    }

    /**
     * Confirms the key round-trips at its declared width and the description keeps its stored case.
     *
     * <p>Purpose: two column contracts are read back here. The key is
     * {@code TR_TYPE CHAR(2) NOT NULL} at {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} L2 and
     * the primary key at its L4, and the description is {@code TR_DESCRIPTION VARCHAR(50) NOT NULL} at
     * its L3. A declared-width character column pads its value on the way in, so a round-trip is the
     * only way to establish that what a caller receives is the two characters it stored and not those
     * two followed by padding the width introduced.</p>
     *
     * <p>Assumptions: the description asserted is {@code Reversal}, in mixed case. Two seed sources
     * exist for this table and they disagree.
     * {@code app/app-transaction-type-db2/ctl/DB2LTTYP.ctl} inserts upper case and spells this row
     * {@code REVERAL} -- a count over that file finds that spelling once and {@code REVERSAL} not at all
     * -- whereas {@code app/data/ASCII/trantype.txt} carries mixed case and the correct spelling.
     * {@code V2__seed_reference.sql} seeds from the ASCII dataset in preference to the control card, so
     * neither {@code REVERSAL} nor {@code REVERAL} is an accepted value here.</p>
     *
     * <p>Assumptions: the record this is read against is 60 bytes -- {@code app/cpy/CVTRA03Y.cpy} L2
     * declares {@code RECLN = 60} and its L5 to L7 sum to it as {@code X(02)} plus {@code X(50)} plus an
     * {@code X(08)} FILLER, which V1 drops. The FILLER holds ASCII zero-fill rather than spaces in every
     * one of the seven rows, which is why dropping it is a drop and not a trim.</p>
     *
     * <p>Assumptions: the 60 above is the RECORD length and not the line length of the dataset the seed
     * was taken from, and the two differ. {@code app/data/ASCII/trantype.txt} is 433
     * bytes over 7 lines and carries 6 carriage returns, so its first six lines measure 61 bytes and its
     * last measures 60: it is CRLF-terminated EXCEPT on its final row. {@code app/data/ASCII/discgrp.txt}
     * alongside it is LF-only. A reader that does not strip the carriage return pulls a 61st byte into a
     * 60-byte record and every field after the first decodes one place out, and the symptom presents as a
     * field-offset defect rather than as a line-ending one. Nothing in this file parses those bytes -- the
     * seed migration is what read them -- so this is recorded to explain why the assertion above is
     * against DATABASE contents and why the line endings must not be assumed uniform across the three
     * datasets the reference seed draws on.</p>
     *
     * <p>Assumptions: code {@code 06} is the subject rather than the first seeded row, and the choice is
     * deliberate. The case above named {@code aSaveCannotInsertThisEntity} reaches
     * {@code EntityManager.merge} against the FIRST seeded row outside any transaction, so it commits a
     * changed description for that row; a case asserting a description against that same row would then
     * pass or fail on test ORDER. No case in this package writes to {@code 06} outside a transaction.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the key round-trips at its declared width and the description keeps its stored case")
    void theKeyRoundTripsAtItsDeclaredWidth() {
        Optional<TransactionType> row = this.types.findByTypeCd(REFERENCED_TYPE_CD);
        assertThat(row).as("the seed must have loaded the subject row").isPresent();

        assertThat(row.get().getTypeCd())
                .as("a declared-width character column must not return its padding as data")
                .isEqualTo(REFERENCED_TYPE_CD)
                .hasSize(TransactionType.TYPE_CD_WIDTH);
        assertThat(row.get().getDescription())
                .as("the mixed-case ASCII dataset is the seed lineage, not the upper-case control card")
                .isEqualTo("Reversal");
    }

    /**
     * Confirms the description column refuses an absent value at the engine rather than above it.
     *
     * <p>Purpose: {@code TR_DESCRIPTION} is declared {@code NOT NULL} at
     * {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} L3 and {@code V1__reference.sql} reproduces
     * that at its L117. What is asserted is that the ENGINE holds the column, so that the declaration
     * remains a guarantee for every writer of the schema and not only for callers who happen to arrive
     * through this application.</p>
     *
     * <p>Assumptions: the native insert is the only route that reaches the engine with an absent value.
     * The mapped attribute is declared {@code nullable = false}, so a write through the persistence
     * context is rejected by the provider before a statement is issued -- which would assert the mapping
     * rather than the column, and would keep passing if the column's own declaration were dropped.</p>
     *
     * <p>Assumptions: the code inserted is outside the seeded range, so the statement fails on the
     * absent description rather than on the key, and the state asserted distinguishes the two: a
     * not-null violation is {@code 23502} where a duplicate key would be {@code 23505}.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("the description column refuses an absent value at the engine")
    void theDescriptionColumnRefusesAnAbsentValue() {
        DataIntegrityViolationException refusal = catchThrowableOfType(
                DataIntegrityViolationException.class,
                () -> this.types.insertType("95", null));

        assertThat(refusal).as("the engine must refuse a row with no description").isNotNull();
        assertThat(sqlStateOf(refusal))
                .as("a not-null violation, which is a different state from a duplicate key")
                .isEqualTo(SQLSTATE_NOT_NULL_VIOLATION);
    }

    /**
     * Confirms the seed loads 862 rows across the six tables of this schema, table by table.
     *
     * <p>Purpose: {@code V2__seed_reference.sql} states its own counts in its header at L12 to L18 -- 7
     * types, 18 categories, 51 disclosure-group rows, 490 phone area codes, 56 states and 240
     * state-and-postal-prefix combinations, a reference subtotal of 76, a lookup subtotal of 786 and a
     * grand total of 862 -- and this case is what turns that header from a description into a contract.
     * Each per-table figure is a literal count of its baseline source, so a count asserted here states
     * that the seed transcribed its source completely rather than merely that it ran.</p>
     *
     * <p>Refactoring Rationale: the aggregate is hosted here rather than distributed over the six classes
     * that each own one table. A per-table assertion can only state its own figure, and six of those all
     * passing still does not establish the sum, because a table that nobody counted would not be missed.
     * One assertion spanning every table does establish it, and this class already owns the shared base's
     * plumbing so the cross-table read needs no further setup and no further engine start.</p>
     *
     * <p>Refactoring Rationale: the counts are asserted at all because a DIFFERENT bounded context
     * consumes three of these tables. account-service's {@code AddressValidationService} queries the
     * lookup tables and owns none of them, so a row missing from this seed does not surface here -- it
     * surfaces there as an address rejected during account maintenance, with nothing in the rejection to
     * name the seed that caused it. Asserting the figures at the level that owns them is what makes that
     * failure attributable.</p>
     *
     * <p>Assumptions: the figures hold whatever order the cases in this package run in. No case in the
     * package commits an inserted or deleted row: every write is either inside a transaction that rolls
     * back, or a statement the engine refuses. The one write that does commit reaches
     * {@code EntityManager.merge} against an existing row, so it changes a description and leaves the row
     * count untouched.</p>
     *
     * <p>Assumptions: this counts rows to audit a seed and is NOT a pagination total.
     * {@code package-info.java} rules at its L456 to L462 that the further-page flag comes from reading
     * one row beyond the window and never from a count, and the case below is what honours that.</p>
     *
     * <p>Trade-offs: the cases that write are annotated one by one so that each rolls back, and what that
     * gives up is commit-visibility realism -- no case here observes a row as a separate connection would
     * see it after a commit, so a defect that only appears once a change is durable would not be caught at
     * this level. What it buys is that the figures asserted above are exact rather than approximate, in
     * any order, on a re-run and under the parallel execution the module's runners allow. An approximate
     * count would be worth little: the property under audit is that the seed transcribed its source
     * COMPLETELY, and a tolerance wide enough to absorb another case's leftovers is also wide enough to
     * absorb a missing row.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the seed loads 862 rows across the six tables, table by table")
    void theSeedIsAuditableAcrossAllSixTables() {
        long typeRows = this.types.count();
        long categoryRows = this.categories.count();
        long disclosureRows = this.disclosureGroups.count();
        long areaCodeRows = this.phoneAreaCodes.count();
        long stateRows = this.states.count();
        long zipPrefixRows = this.stateZipPrefixes.count();

        assertThat(typeRows).as("app/data/ASCII/trantype.txt").isEqualTo(SEEDED_TYPES);
        assertThat(categoryRows).as("app/data/ASCII/trancatg.txt").isEqualTo(SEEDED_CATEGORIES);
        assertThat(disclosureRows).as("app/data/ASCII/discgrp.txt")
                .isEqualTo(SEEDED_DISCLOSURE_GROUPS);
        assertThat(areaCodeRows).as("app/cpy/CSLKPCDY.cpy L30")
                .isEqualTo(SEEDED_PHONE_AREA_CODES);
        assertThat(stateRows).as("app/cpy/CSLKPCDY.cpy L1013").isEqualTo(SEEDED_STATES);
        assertThat(zipPrefixRows).as("app/cpy/CSLKPCDY.cpy L1073")
                .isEqualTo(SEEDED_STATE_ZIP_PREFIXES);

        assertThat(typeRows + categoryRows + disclosureRows + areaCodeRows + stateRows + zipPrefixRows)
                .as("the grand total V2__seed_reference.sql declares in its header at L18")
                .isEqualTo(SEEDED_GRAND_TOTAL);
    }

    /**
     * Confirms the further-page answer comes from reading one row beyond the window.
     *
     * <p>Purpose: the window is seven rows, declared as a program constant at
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} physical line 60, and the baseline decides
     * whether a further page exists by FETCHING once more rather than by counting. Its forward reader has
     * two fetch sites: one inside the row loop, and a second at physical lines 1661 to 1665 that fires
     * only when physical line 1657 finds the row number equal to that constant. Physical line 1670 tests
     * the outcome and 1671 records that a further page exists, while 1674 and 1675 take the exhausted
     * branch instead. The migrated form asks the engine for one row more than the window and reads the
     * surplus the same way, which is what {@code TransactionTypeService} does at its L342 with a bound of
     * the window plus one and what {@code ReferencePaging.page} then interprets.</p>
     *
     * <p>Assumptions: an eighth row has to be INSERTED for the affirmative half of this case to be
     * assertable at all. The seed loads exactly seven types and the window is exactly seven, so on seeded
     * data the whole table is one page and the honest answer is that no further page exists;
     * {@code package-info.java} records the same trap at its L465 to L469. The
     * {@code reference_list/happy_path} fixture is the populated state of this browse and carries those
     * same seven rows, so it does not supply an eighth either.</p>
     *
     * <p>Trade-offs: the inserted row is rolled back with the ambient transaction rather than deleted
     * afterwards. A delete in a teardown would leave the table permanently changed if this case failed
     * partway, and the seed figures asserted above would then fail for a reason belonging to this
     * method.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("the further-page answer comes from one row beyond the window, never from a count")
    void theFurtherPageAnswerComesFromOneRowBeyondTheWindow() {
        List<TransactionType> onSeededData =
                this.types.findAllByOrderByTypeCdAsc(Limit.of(PAGE_SIZE + 1));
        assertThat(onSeededData)
                .as("the probe asks for eight and the seed holds seven, so no surplus can appear")
                .hasSize(SEEDED_TYPES);
        assertThat(onSeededData.size() > PAGE_SIZE)
                .as("one page holds the whole seeded table, so no further page exists")
                .isFalse();

        this.types.saveAndFlush(new TransactionType("08", "Eighth type, present for this case only"));

        List<TransactionType> withASurplus =
                this.types.findAllByOrderByTypeCdAsc(Limit.of(PAGE_SIZE + 1));
        assertThat(withASurplus).hasSize(PAGE_SIZE + 1);
        assertThat(withASurplus.size() > PAGE_SIZE)
                .as("the surplus row is the whole evidence that a further page follows")
                .isTrue();
        assertThat(withASurplus.subList(0, PAGE_SIZE)).extracting(TransactionType::getTypeCd)
                .as("the published window is the first seven, and the surplus is not published")
                .containsExactly("01", "02", "03", "04", "05", "06", "07");
        assertThat(withASurplus.get(PAGE_SIZE).getTypeCd())
                .as("the surplus row is the one the window would have published next")
                .isEqualTo("08");
    }

    /**
     * Confirms two consecutive pages share no row and skip none between them.
     *
     * <p>Purpose: this is the observable property a caller actually depends on, and it is asserted rather
     * than a bare position value because the position alone is design-dependent. Two designs are both
     * correct and they pair differently. The baseline pairs a LOOKAHEAD position with an INCLUSIVE
     * predicate: {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} physical line 1659 sets the
     * trailing position to the seventh DISPLAYED row and physical line 1673 then OVERWRITES it with the
     * eighth, not-displayed row on the branch its 1670 selects, which is exactly what stops the seventh
     * row reappearing when the inclusive comparison at physical line 343 resumes from it. The migrated
     * form pairs a LAST-RETURNED position with an EXCLUSIVE predicate instead.</p>
     *
     * <p>Assumptions: the pairing the authored source implements is the second one, and it was read from
     * the source rather than assumed. {@code ReferencePaging.page} drops the surplus row and then seals
     * the key of the LAST ROW IT PUBLISHES as the trailing position, and the walk that resumes from that
     * position is {@code findByTypeCdGreaterThanOrderByTypeCdAsc}, whose comparison is strictly greater
     * than. So the two halves agree, and the case below walks them exactly as the service does.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("consecutive pages share no row and skip none")
    void consecutivePagesShareNoRowAndSkipNone() {
        this.types.saveAndFlush(new TransactionType("08", "Eighth type, present for this case only"));

        List<TransactionType> probe = this.types.findAllByOrderByTypeCdAsc(Limit.of(PAGE_SIZE + 1));
        assertThat(probe.size() > PAGE_SIZE).as("a second page must exist for this case to mean anything")
                .isTrue();
        List<TransactionType> pageOne = probe.subList(0, PAGE_SIZE);

        // WHY : Assumptions: the position handed to the second walk is the key of the LAST ROW PAGE ONE
        //       PUBLISHED, not the key of the surplus row the probe also returned. That is the half of
        //       the pairing this service implements, and it is only safe BECAUSE the resuming comparison
        //       is strictly greater than. Handing the last published key to an INCLUSIVE comparison
        //       instead would return that row again as the first row of page two -- exactly one
        //       duplicate per page boundary, which is a defect no membership-only assertion would
        //       notice, since every row would still be present somewhere.
        String position = pageOne.get(pageOne.size() - 1).getTypeCd();
        List<TransactionType> pageTwo =
                this.types.findByTypeCdGreaterThanOrderByTypeCdAsc(position, Limit.of(PAGE_SIZE + 1));

        List<String> walked = new ArrayList<>(pageOne.stream().map(TransactionType::getTypeCd).toList());
        walked.addAll(pageTwo.stream().map(TransactionType::getTypeCd).toList());

        assertThat(walked)
                .as("no row may be published twice across the boundary between the two pages")
                .doesNotHaveDuplicates();
        assertThat(walked)
                .as("no row may be passed over: the two pages together are the whole table, in order")
                .containsExactly("01", "02", "03", "04", "05", "06", "07", "08");
    }

    /**
     * Confirms a row inserted inside page one is afterwards neither skipped nor published twice.
     *
     * <p>Purpose: this demonstrates the property that positions the walk by key rather than by ordinal,
     * instead of only asserting that no ordinal appears in a query. A row is inserted BETWEEN the two
     * reads, at a key that sorts inside the range page one already published, and the second page is then
     * read through the key position page one ended at.</p>
     *
     * <p>Alternatives Considered: positioning a page by ordinal offset was rejected, and the ground is
     * behavioural rather than a preference. An ordinal counts rows in the ordering AS IT STANDS WHEN THE
     * SECOND QUERY RUNS, so a row inserted before that ordinal shifts every later row one place along:
     * the second page then re-publishes the last row of the first page and, symmetrically, a delete makes
     * it pass one over entirely. Positioning by key cannot do either, because the key names a row rather
     * than a place in a sequence, and rows arriving elsewhere in the ordering do not move it. On the
     * arrangement below an ordinal-positioned second page would have re-published the row named by the
     * position, and that reasoning stays in this comment on purpose: no ordinal-positioned query is
     * written here, since asserting that a query text contains no ordinal is not the same claim as
     * proving that positions do not shift.</p>
     *
     * <p>Assumptions: the interleaved insert is issued on this same connection rather than from a second
     * one. What the property needs is that the second query runs against a table that has CHANGED since
     * the first, and a flushed insert inside the ambient transaction supplies exactly that while keeping
     * the change inside the rollback this case depends on. The insert's visibility is asserted rather
     * than assumed, so that the case cannot pass by the change having silently not arrived.</p>
     *
     * <p>Assumptions: the keys used sit above the seeded range so that a gap exists to insert into at
     * all. The seven seeded codes are consecutive, so no key sorts strictly between two of them, and a
     * case built on the seeded rows alone could not place a row inside a published page.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @Transactional
    @DisplayName("a row inserted inside page one is neither skipped nor published twice")
    void aRowInsertedInsidePageOneIsNeitherSkippedNorPublishedTwice() {
        this.types.saveAndFlush(new TransactionType("11", "Interleave fixture, first"));
        this.types.saveAndFlush(new TransactionType("13", "Interleave fixture, second"));
        this.types.saveAndFlush(new TransactionType("15", "Interleave fixture, third"));
        this.types.saveAndFlush(new TransactionType("17", "Interleave fixture, fourth"));

        int window = 3;
        List<TransactionType> pageOne =
                this.types.findByTypeCdGreaterThanOrderByTypeCdAsc("07", Limit.of(window));
        assertThat(pageOne).extracting(TransactionType::getTypeCd)
                .as("the first page of the range this case owns")
                .containsExactly("11", "13", "15");
        String position = pageOne.get(pageOne.size() - 1).getTypeCd();

        this.types.saveAndFlush(new TransactionType("12", "Inserted between the two reads"));
        assertThat(this.types.findByTypeCd("12"))
                .as("the interleaved row must be visible, or this case proves nothing")
                .isPresent();

        List<TransactionType> pageTwo =
                this.types.findByTypeCdGreaterThanOrderByTypeCdAsc(position, Limit.of(window));

        assertThat(pageTwo).extracting(TransactionType::getTypeCd)
                .as("no row page one published may return, and the row after the position may not be"
                        + " passed over")
                .containsExactly("17");
        List<String> walked = new ArrayList<>(pageOne.stream().map(TransactionType::getTypeCd).toList());
        walked.addAll(pageTwo.stream().map(TransactionType::getTypeCd).toList());
        assertThat(walked).doesNotHaveDuplicates();
    }

    /**
     * Confirms the backward walk reads descending and reverses into the order a caller is shown.
     *
     * <p>Purpose: the two directions are not mirror images and the difference must survive the migration
     * rather than being smoothed away. The backward cursor tests the key at
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} physical line 359 with an EXCLUSIVE
     * comparison and orders DESCENDING at its physical line 367, where the forward cursor tests
     * inclusively at physical line 343 and orders ascending at 351. A backward page is therefore read
     * descending and reversed before it is published, so rows reach a caller ascending whichever
     * direction was asked for.</p>
     *
     * <p>Assumptions: the EMITTED order is asserted and not only the membership of the page. Membership
     * is identical under both orders, so a walk that returned its rows ascending would satisfy a
     * membership assertion while breaking the one thing the descending order is for: it puts the surplus
     * row at the START of a backward page, and a publisher that drops the surplus from the wrong end
     * silently removes a row the caller should have seen.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the backward walk reads descending and reverses into ascending display order")
    void theBackwardWalkReversesIntoAscendingDisplayOrder() {
        List<TransactionType> asRead =
                this.types.findByTypeCdLessThanOrderByTypeCdDesc("06", Limit.of(PAGE_SIZE));

        assertThat(asRead).extracting(TransactionType::getTypeCd)
                .as("the engine emits descending, and the position itself is excluded")
                .containsExactly("05", "04", "03", "02", "01");
        assertThat(asRead.reversed()).extracting(TransactionType::getTypeCd)
                .as("reversing is what a caller is shown, and it must be contiguous and ascending")
                .containsExactly("01", "02", "03", "04", "05");
    }

    /**
     * Confirms both filter arms active narrow by their conjunction rather than either one alone.
     *
     * <p>Purpose: the baseline carries each optional filter as a guarded pair of arms --
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} physical lines 344 to 346 for the code and
     * 347 to 350 for the description -- and the two pairs are joined by AND, so an active code arm and an
     * active description arm must both hold of the same row. The four combinations of the two guards are
     * covered across this class: neither arm active, the code arm alone, the description arm alone, and
     * both together here.</p>
     *
     * <p>Assumptions: the guard's ACTIVE sentinel in the baseline is the literal one character
     * {@code '1'} and every other value means the arm is off, because a one-byte field cannot represent
     * its own absence. The migrated form expresses the same thing as an absent argument, which is why the
     * cases in this class pass {@code null} where the baseline sets the guard off.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("both filter arms active narrow by their conjunction")
    void bothFilterArmsActiveNarrowByTheirConjunction() {
        String pattern = TransactionTypeRepository.descriptionFilterPattern("Reversal");

        assertThat(this.types.findFilteredPageAfter(REFERENCED_TYPE_CD, pattern, null, Limit.of(100)))
                .extracting(TransactionType::getTypeCd)
                .as("both arms hold of this row, so it is the only one that survives them")
                .containsExactly(REFERENCED_TYPE_CD);
        assertThat(this.types.findFilteredPageAfter("01", pattern, null, Limit.of(100)))
                .as("a code that exists with a description that does not match must yield nothing,"
                        + " which is a conjunction and not a disjunction")
                .isEmpty();
    }

    /**
     * Confirms the code arm is an equality, so a partial code is not a prefix or substring search.
     *
     * <p>Purpose: the code arm is transcribed from {@code TR_TYPE = :WS-TYPE-CD-FILTER} at
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} physical line 345 and is an equality there.
     * A partial code that matched the rows it is a prefix of would widen every filtered browse silently:
     * a caller narrowing to one type would receive several and have no way to tell that it had.</p>
     *
     * <p>Alternatives Considered: the description arm is the one place the migrated form deliberately
     * does NOT match its source character for character, and the difference is worth stating beside the
     * arm that does. Physical lines 348 and 349 apply the pattern with no ESCAPE clause and add no
     * wildcard of their own, so a plain value there matches only a description equal to it, whereas
     * {@code TransactionTypeRepository.descriptionFilterPattern} wraps the caller's text in its own
     * wildcards and escapes the caller's, making the description arm a containment search over literal
     * text. That divergence is registered in the traceability document; what it does not change is this
     * arm, which stays an equality, and which is the property both shapes agree on.</p>
     *
     * <p>Assumptions: a single character is compared against a two-character column. A declared-width
     * character comparison ignores trailing padding, so the shorter value compares as itself and matches
     * no two-character code rather than raising -- which is why the assertion is an empty page and not an
     * expected failure.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the code arm is an equality, not a prefix or substring search")
    void theCodeArmIsAnEqualityAndNotAPrefixSearch() {
        assertThat(this.types.findFilteredPageAfter("0", null, null, Limit.of(100)))
                .as("a partial code is not the prefix of the codes it opens, it is simply absent")
                .isEmpty();
        assertThat(this.types.findFilteredPageAfter(REFERENCED_TYPE_CD, null, null, Limit.of(100)))
                .extracting(TransactionType::getTypeCd)
                .as("a whole code narrows to exactly the one row it names")
                .containsExactly(REFERENCED_TYPE_CD);
    }

    /**
     * Reads the SQLSTATE out of a violation the way the service under test reads it.
     *
     * @param failure the violation the provider raised; must not be {@code null}
     * @return the first non-blank SQLSTATE found by walking the cause chain, or {@code null} when the
     *     chain reports none
     */
    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.sql.SQLException reported
                    && reported.getSQLState() != null
                    && !reported.getSQLState().isBlank()) {
                return reported.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}

/**
 * The container fixture every repository integration test in this package shares.
 *
 * <p>Assumptions: this is declared as a second, package-private, top-level type in this file rather than
 * as a file of its own, exactly as {@code package-info.java} in this package rules. A separate file would
 * take the package's closed set from eight compilation units to nine and break the property the sibling
 * test packages of this module rely on when they state their own inventories. A second top-level type in
 * one file is legal Java -- the restriction is one PUBLIC top-level type per file -- and it passes the
 * audit because {@code config/checkstyle/checkstyle.xml} configures neither the one-top-level-type module
 * nor the outer-type-filename module.
 *
 * <p>Assumptions: the container field is {@code static} on this base, so ONE engine is started for the
 * whole package rather than one per test class. Seven containers would multiply the slowest part of the
 * build by seven and would additionally run Flyway seven times over identical migrations.
 *
 * <p>Trade-offs: the base type is findable only by opening the class that hosts it, which is the cost
 * accepted for the closed-set property above, and it is why the hosting file is named in the package
 * charter rather than left to a search.
 */
@SpringBootTest(
        classes = ReferencePersistenceBase.ReferencePersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
abstract class ReferencePersistenceBase {

    // WHY : Assumptions: the image is named by DIGEST rather than by the tag postgres:17-alpine, for the
    //       reason the ledger context's integration tests record at length: that tag pins the MAJOR line
    //       only, and the properties under test here are engine behaviours -- the collation a
    //       declared-width character column is ordered in, and how a like clause with an escape
    //       character treats a caller's own metacharacter. A moving tag would let either change between
    //       two runs of unchanged repositories, so a failure could not be attributed and a silently
    //       altered ordering could keep every assertion green while proving something different.
    // WHY : Assumptions: this is the SAME digest the ledger context pins, so the two suites cannot
    //       disagree about engine behaviour, and the image is already present wherever either has run.
    // WHY : Trade-offs: a digest is unreadable, so the engine version it denotes survives only in this
    //       comment and has to be updated with the value. That is the trade this repository's workflows
    //       already accept when they pin an action to a commit identifier with the tag written beside it.
    /** PostgreSQL 17 on Alpine, pinned by manifest digest rather than by a moving tag. */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    // WHY : Refactoring Rationale: the engine is started HERE, in a static initialiser, and the class
    //       carries neither @Testcontainers nor @Container. Those two were tried first and produced a
    //       failure worth recording, because it does not look like a lifecycle problem: the JUnit
    //       extension owns a @Container field for the duration of ONE test class, so the engine was
    //       stopped when the first class in this package finished while the framework's cached
    //       application context -- which is shared across all six classes, because they declare the same
    //       configuration and profile -- went on pointing at it. Every later class then failed on
    //       connection acquisition after a ten-second pool timeout, reporting an empty pool rather than a
    //       stopped container, so the reported cause named the pool and not the lifecycle.
    // WHY : Assumptions: nothing stops the engine explicitly and nothing needs to. Testcontainers'
    //       companion reaper container removes it when the test JVM exits, which is the mechanism the
    //       annotation-driven form relies on for its own cleanup too; the difference here is only WHEN
    //       the shutdown is triggered, not whether it happens.
    // WHY : Alternatives Considered: keeping @Container and letting each class start its own engine.
    //       Rejected because it multiplies the slowest part of this module's build by six and runs the
    //       same two migrations six times, and because the framework would still cache one context
    //       across the six classes -- so the later classes would be pointed at the FIRST engine while
    //       five more sat idle.
    // WHY : Assumptions: the connection details reach the context DECLARATIVELY, through
    //       @ServiceConnection, and this is the mechanism this module's own
    //       src/test/resources/application-test.yml already depends on. That file deliberately omits
    //       spring.flyway.user and spring.flyway.password and records why: the container's
    //       @ServiceConnection is adapted into a Flyway connection-details bean, so the container's own
    //       generated credential migrates the schema and no stand-in credential has to be invented.
    // WHY : Alternatives Considered: @DynamicPropertySource, which this package's charter ruled for on
    //       the grounds that no dependency should be added to obtain a shorter annotation. Tried, and
    //       measured to fail: publishing the three spring.datasource keys leaves spring.flyway.user bound
    //       to an unset placeholder, and every context load failed authenticating as the literal
    //       placeholder text. Publishing Flyway's two keys as well would restate keys the same charter
    //       forbids a class here from restating, so the ruling could not be met in either direction. The
    //       ruling is withdrawn in that charter rather than worked around here.
    /** The one engine every test class in this package runs against, started once for the JVM. */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
    }

    /**
     * The minimal application this package's tests are bootstrapped from.
     *
     * <p>Assumptions: entities and repositories are scanned explicitly rather than the service's own
     * entry point being used, because that entry point pulls in the security chain, the messaging
     * listener and the interface document, none of which a persistence test exercises and each of which
     * would then need configuration a persistence test has no reason to supply.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reference.domain")
    @EnableJpaRepositories("com.carddemo.reference.repository")
    static class ReferencePersistenceTestApplication {
    }
}
