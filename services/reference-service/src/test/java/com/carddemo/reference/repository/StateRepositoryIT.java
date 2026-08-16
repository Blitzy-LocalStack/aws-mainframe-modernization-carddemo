// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/StateRepositoryIT.java
// -----------------------------------------------------------------------------
// Purpose:
//      The container-backed integration test of the state allow-list: that the
//      seed migration loads the whole closed domain the baseline declares, that
//      the six codes which are NOT states are each present by name, that the
//      declared column width round-trips into Java without padding, and that the
//      primary key refuses a second row for a code already held.
//
// WHY (non-obvious design decisions):
//  (1) Assumptions: the four rationale labels below are written in the PLURAL,
//      unparenthesised, colon-terminated form -- Alternatives Considered:,
//      Refactoring Rationale:, Assumptions: and Trade-offs: -- which is the form
//      docs/CODE_DOCUMENTATION_STANDARD.md rules at its lines 236 to 239. The
//      singular spellings mean the same thing and are deliberately never used,
//      so a label can be found by grep before it is read by a person. This
//      equivalence is stated once here and nowhere restated.
//  (2) Assumptions: the domain is 56 codes and decomposes as the fifty states
//      plus DC plus the five territory codes AS, GU, MP, PR and VI. That is not
//      a rounded figure: app/cpy/CSLKPCDY.cpy L1013 carries VALID-US-STATE-CODE
//      over the L1012 field US-STATE-CODE-TO-EDIT PIC X(2), the clause runs from
//      L1013 to L1069, and extracting its literals yields 56 of them, 56 of them
//      distinct, every one exactly two characters. Everything beneath app/ is
//      the behavioural oracle of this migration: it is read and cited, never
//      modified, and a deliberate departure from it is registered in
//      docs/architecture/cobol-to-service-traceability.md, which is maintained
//      elsewhere and referenced rather than reproduced here.
//  (3) Assumptions: the domain must never be narrowed to 50. Six of the 56 are
//      not states, so a list trimmed to the states alone still looks plausible
//      while refusing a legitimate territory address -- a customer in San Juan
//      or on Guam would be unable to complete account maintenance, and nothing
//      in that refusal would point back at the seed that caused it.
//  (4) Refactoring Rationale: the cardinality is pinned HERE, in the context
//      that owns and seeds the table, because the context that depends on it
//      owns neither. account-service's AddressValidationService queries this
//      lookup over this service's published contract and holds no copy of the
//      list, so a code missing from this seed surfaces there as an address
//      rejected during account maintenance -- a failure in a service with no
//      defect of its own. Pinning it where the rows are produced is what makes
//      the cross-service consequence attributable.
//  (5) Alternatives Considered: re-verifying the copybook figure with a naive
//      literal extractor, which this file's own figure was NOT produced by. The
//      same copybook carries a COBOL comment line INSIDE an unrelated VALUES
//      clause at L440, reading "*Easily recognizable codes begin here." with a
//      terminating period, so an extractor that treats a period as the clause
//      terminator stops early and under-counts. That trap sits in the phone
//      area-code list rather than this one, but the technique is shared, so the
//      figures above were taken with comment lines skipped by column-7 test.
//  (6) Assumptions: services/reference-service/src/main/resources/db/migration/
//      V1__reference.sql is the SOLE source of this table's column names, since
//      no baseline table exists for it -- the baseline holds the domain as a
//      condition name over a working-storage field, not as a file. Its L413 to
//      L430 declare exactly one column, state_cd CHAR(2) NOT NULL, and make it
//      the whole of pk_us_states. There is therefore NO further NOT NULL column
//      for this file to prove a refusal for, and that absence is recorded as a
//      verified reading of V1 rather than left silent, because an unstated
//      absence is indistinguishable from an omission.
//  (7) Trade-offs: this class starts no engine of its own and adds no
//      dependency. It extends ReferencePersistenceBase, the fixture declared as
//      a second top-level type in TransactionTypeRepositoryIT.java and resolved
//      here by same-package lookup with no import, so the one container that
//      package starts is the one used. The start-up cost carried by that fixture
//      is accepted rather than avoided: an in-memory engine reached through a
//      different Flyway dialect would let the seed assertions below pass against
//      a fiction, and the padding property asserted here is a declared-width
//      character behaviour that only a real engine exhibits.
// =============================================================================
package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.carddemo.reference.domain.UsState;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the seeded state allow-list and the key that closes it, against a real engine.
 *
 * <p>The properties asserted here are the ones no stand-in for a database can establish. Whether the
 * seed loaded every code, whether a two-character declared-width column hands its value back unpadded,
 * and whether the primary key refuses a second row for a held code are all behaviours of the migration
 * and the engine; a stand-in would answer whatever it was told and every assertion would pass while
 * proving nothing.
 *
 * <p>Assumptions: the reads below go through {@code StateRepository}, which is the interface the main
 * tree declares over {@code reference.us_states} -- it was authored as {@code UsStateRepository} and
 * renamed, and this class's own name already matched the assigned one. The writes go through the
 * persistence context so
 * that an insert is issued rather than merged. The sibling coverage of the ordering and of the two
 * bounded walks is not repeated here and lives in {@code StateRepositoryWalkIT}, nor are the seed totals
 * owned by other classes in this package:
 * the grand total across the schema belongs to {@code TransactionTypeRepositoryIT} and the area-code
 * subtotals to the two area-code classes.
 *
 * <p>Trade-offs: every case runs inside one transaction that the framework rolls back, so the inserted
 * row a refusal case attempts never becomes visible to the classes that share this container. What that
 * costs is realism about commit visibility, which none of these cases asserts; what it buys is that the
 * seeded counts stay stable no matter which order the classes in this package run in.
 *
 * <p>It declares no parameter, returns no value and raises nothing itself, so this type carries no
 * parameter, return or exception at-clause. The inapplicability is stated rather than left silent
 * because the project Explainability rule counts a docstring that omits its parameters or return values
 * among the omissions it rejects at line 39, and a reader has to be able to tell a declared
 * inapplicability from an oversight.
 */
@Transactional
class StateRepositoryIT extends ReferencePersistenceBase {

    /** Every code the seed migration loads, being the copybook's own literal count. */
    private static final int SEEDED_STATES = 56;

    /** The count that remains once the six non-state codes are set aside. */
    private static final int ACTUAL_STATES = 50;

    // WHY : Assumptions: the bound EXCEEDS the domain rather than matching it, and the difference is
    //       load-bearing. A bound of exactly 56 would return 56 rows from a table holding 57, so the
    //       cardinality assertion would pass while the domain had silently grown. A bound above the
    //       domain makes an over-seeded table read as over-seeded.
    /** A read bound set above the domain so a truncating read cannot pass as a matching count. */
    private static final int READ_BOUND = 100;

    // WHY : Alternatives Considered: naming these six codes individually rather than relying on the
    //       cardinality alone. The count cannot detect a SUBSTITUTION: the seed statement at
    //       V2__seed_reference.sql L609 to L617 ends ON CONFLICT (state_cd) DO NOTHING, so a literal
    //       repeated in that list collapses into one row and shows up as 55 -- which the count does
    //       catch -- whereas a territory replaced by some other two-character code keeps the total at
    //       56 and is invisible to it. Naming the six is what closes that gap, and they are the six
    //       that matter because they are exactly the members a reader trimming the list to "the
    //       states" would drop.
    /** The six codes in the domain that are not states, from CSLKPCDY.cpy L1064 to L1069. */
    private static final List<String> NON_STATE_CODES = List.of("DC", "AS", "GU", "MP", "PR", "VI");

    /** The declared width of the key column, from V1__reference.sql L427. */
    private static final int DECLARED_KEY_WIDTH = 2;

    /** The constraint a refused duplicate must name, declared at V1__reference.sql L429. */
    private static final String PRIMARY_KEY_CONSTRAINT = "pk_us_states";

    /** The state PostgreSQL reports when a unique or primary-key constraint refuses a row. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** Stands in for the SQL state when a failure carries no database cause at all. */
    private static final String NO_SQL_STATE = "absent";

    /** The repository under test. */
    @Autowired
    private StateRepository states;

    // WHY : Refactoring Rationale: the duplicate case below writes through the persistence context
    //       rather than through the repository, and the reason is a measured property of this entity
    //       rather than a preference. Its identifier is assigned and it carries no version, so the
    //       framework's newness test finds a non-null identifier, routes save through merge, and merge
    //       loads the row that identifier names and writes an UPDATE against it -- no insert is ever
    //       issued and the primary key is never reached. The sibling case
    //       TransactionTypeRepositoryIT.aSaveCannotInsertThisEntity records the same measurement for
    //       the type entity. A persist is what issues the insert, so a persist is what is used.
    /** The persistence context, used to issue an insert and to flush it at a chosen point. */
    @Autowired
    private EntityManager entityManager;

    /**
     * Confirms the seed migration loads the whole closed domain and nothing beyond it.
     *
     * <p>Assumptions: 56 is the figure extracted from {@code app/cpy/CSLKPCDY.cpy} L1013 through
     * L1069, and {@code V2__seed_reference.sql} L609 through L617 loads exactly those literals. The
     * assertion is two-sided by construction, since a read bounded above the domain reports a table
     * that has grown as well as one that has shrunk.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the seed loads the whole 56-code domain and nothing beyond it")
    void theSeedLoadsTheWholeDomain() {
        // WHY : Assumptions: this counts SEEDED ROWS to establish a data-cardinality property, and it
        //       is deliberately not the total-count-for-pagination this package rules out. No page is
        //       being sized here and no offset is being derived; the walks that do page this table use
        //       keyset bounds and are covered by StateRepositoryWalkIT. A later reader should not mistake
        //       one for the other and remove it.
        List<String> codes = seededCodes();

        assertThat(codes)
                .as("the seeded state domain is closed at %d codes", SEEDED_STATES)
                .hasSize(SEEDED_STATES);
        // WHY : Assumptions: distinctness is asserted to license the reading of the size above. It is
        //       what makes 56 a count of DISTINCT codes rather than a count of rows, so the figure
        //       still means the domain size if this read is ever widened into one that joins and fans
        //       out. It is deliberately NOT claimed to detect a repeated seed literal: the seed ends
        //       ON CONFLICT (state_cd) DO NOTHING, so a repeat collapses before it is stored and
        //       arrives as a short count on the assertion above instead.
        assertThat(codes)
                .as("the read yields one row per code, so the size above is a domain size")
                .doesNotHaveDuplicates();
    }

    /**
     * Confirms each of the six non-state codes is present by name, not merely counted.
     *
     * <p>Assumptions: the residual arithmetic is asserted alongside the six, so the case states the
     * whole decomposition rather than half of it. Fifty plus the six is the 56 the copybook declares,
     * and a domain narrowed to the fifty states alone fails both halves at once.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("DC and the five territory codes are each present by name")
    void theSixNonStateCodesArePresentByName() {
        List<String> codes = seededCodes();

        assertThat(codes)
                .as("a narrowed domain refuses a legitimate territory address in account-service")
                .contains("DC", "AS", "GU", "MP", "PR", "VI");
        assertThat(codes)
                .as("the six non-state codes leave exactly %d states behind them", ACTUAL_STATES)
                .filteredOn(code -> !NON_STATE_CODES.contains(code))
                .hasSize(ACTUAL_STATES);
    }

    /**
     * Confirms the declared-width key column hands its value back unpadded.
     *
     * <p>Assumptions: this is asserted by reading the value back and measuring the Java string, never
     * by an equality predicate against the column. PostgreSQL implements {@code CHAR(n)} as
     * {@code bpchar}, whose equality ignores trailing blanks, so a predicate comparing the column
     * against a padded literal matches whatever the declared width happens to be -- it is blind to the
     * very property under test and would pass unchanged if the column were widened. The length of the
     * returned string is not blind to it: a wider column hands back a blank-padded value, and the
     * measurement fails.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the two-character key round-trips into Java with no padding")
    void theDeclaredWidthRoundTripsWithoutPadding() {
        assertThat(seededCodes())
                .as("a value wider than %d characters is padding the engine added", DECLARED_KEY_WIDTH)
                .allSatisfy(code -> assertThat(code).hasSize(DECLARED_KEY_WIDTH));

        assertThat(this.states.findByStateCode("DC"))
                .as("the district's row must be readable by its exact code")
                .isPresent()
                .get()
                .extracting(UsState::getStateCode)
                .isEqualTo("DC");
    }

    /**
     * Confirms the primary key refuses a second row for a code the table already holds.
     *
     * <p>Assumptions: the flush is EXPLICIT and the assertion is vacuous without it. An insert issued
     * inside a transaction is held in the persistence context until something forces it out, and this
     * transaction is rolled back rather than committed, so with no flush the statement would never
     * reach the engine, no refusal would ever arrive, and a case written to expect one would report
     * that expectation as met by an exception raised somewhere else entirely.
     *
     * <p>Assumptions: the context is cleared between reading the row and inserting over it. Without
     * that, the code is already managed when the insert is attempted and the provider reports the
     * collision from its own bookkeeping before any statement is issued -- which would prove the
     * provider tracks identifiers, not that the database holds a key. Clearing first is what makes the
     * refusal the engine's.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a duplicate code is refused by the primary key at the explicit flush")
    void aDuplicateCodeIsRefusedAtTheExplicitFlush() {
        String held = "DC";
        assertThat(this.states.findByStateCode(held))
                .as("the row the key must collide with has to be present first")
                .isPresent();
        this.entityManager.clear();

        Throwable failure = catchThrowable(() -> {
            this.entityManager.persist(new UsState(held));
            this.entityManager.flush();
        });

        assertThat(failure)
                .as("the flush must surface the refusal rather than defer it to a commit")
                .isNotNull();
        SQLException databaseFailure = firstDatabaseFailure(failure);
        assertThat(sqlStateOf(databaseFailure))
                .as("the engine reports %s for a key it already holds", UNIQUE_VIOLATION)
                .isEqualTo(UNIQUE_VIOLATION);
        // WHY : Assumptions: the failure is widened to Throwable here, and the widening is required
        //       rather than tidy. java.sql.SQLException implements Iterable<Throwable>, so the bare
        //       assertion entry point resolves ambiguously between its throwable overload and its
        //       iterable overload and the file does not compile at all -- measured, not anticipated.
        //       The cast selects the throwable reading, which is the one every assertion here wants;
        //       the iterable reading would walk the chain as a collection and assert nothing about
        //       the message.
        assertThat((Throwable) databaseFailure)
                .as("the refusal names the constraint, so it cannot be read as some other violation")
                .hasMessageContaining(PRIMARY_KEY_CONSTRAINT);
    }

    /**
     * Reads every seeded code in key order, bounded above the domain.
     *
     * @return the {@code List} of {@code state_cd} values the seed migration loaded, in ascending
     *     code order, holding one entry per row
     */
    private List<String> seededCodes() {
        return this.states.findAllByOrderByStateCodeAsc(Limit.of(READ_BOUND)).stream()
                .map(UsState::getStateCode)
                .toList();
    }

    /**
     * Walks a failure's cause chain for the database exception underneath it.
     *
     * <p>Assumptions: the chain is walked rather than a provider-specific wrapper being matched,
     * because the wrapper a failed flush surfaces depends on how the persistence context was obtained
     * and on which layer translated it. What the assertions care about is the state and the message the
     * engine reported, and those travel in the {@code SQLException} however it is wrapped.
     *
     * @param failure the {@code Throwable} the flush raised, whose cause chain is searched from the
     *     outermost link inward
     * @return the first {@code SQLException} in the chain, or {@code null} when the failure carries no
     *     database cause at all
     */
    private static SQLException firstDatabaseFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException databaseFailure) {
                return databaseFailure;
            }
        }
        return null;
    }

    /**
     * Reports the SQL state of a database failure, or a stand-in when there is none.
     *
     * <p>Assumptions: a stand-in is returned rather than null so that a failure carrying no database
     * cause is reported as a mismatched state against the expected one, which names the problem, in
     * place of a null-pointer failure raised while reading it, which does not.
     *
     * @param databaseFailure the {@code SQLException} to read the state from, which may be
     *     {@code null} when no database cause was found
     * @return the {@code String} SQL state the engine reported, or {@code NO_SQL_STATE} when the
     *     argument is null or carries no state
     */
    private static String sqlStateOf(SQLException databaseFailure) {
        if (databaseFailure == null || databaseFailure.getSQLState() == null) {
            return NO_SQL_STATE;
        }
        return databaseFailure.getSQLState();
    }
}
