// WHAT: pins the identity of every migration this module ships, read back through Flyway's own
//      history table after Flyway has applied it to a real engine.
// WHY : Refactoring Rationale: this class exists because a released migration was edited and the
//      edit was invisible until deployment. A comment-only rewrite of V1__reference.sql changed
//      the checksum Flyway stores, so every database that had already applied the earlier bytes
//      refused to start -- the schema was correct and the service was unstartable. Nothing in the
//      build could see it: the file compiles to nothing, no test read its bytes, and a first apply
//      onto an empty database succeeds whatever the bytes are. Pinning the checksum here moves
//      that failure from deploy time to build time, on the machine of whoever changes the file.
package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Holds the applied identity of this module's migrations against a real engine, so an edit to a
 * released one fails the build rather than a deployment.
 *
 * <h2>What is being asserted, and why it is asserted this way</h2>
 *
 * <p>Assumptions: the values below are read from {@code reference.flyway_schema_history} AFTER Flyway
 * has resolved and applied the files, so Flyway itself computes them. That is deliberate: a checksum
 * recomputed here by a second implementation of the same algorithm would agree with this module's
 * files and could still disagree with the value a deployment stores, which is the only value that
 * decides whether a service starts. Reading Flyway's own output removes that gap entirely.</p>
 *
 * <p>Assumptions: a migration file is IMMUTABLE once released. Flyway validates an applied migration
 * by comparing the checksum it stored against the checksum it computes from the file now, and the
 * comparison covers the WHOLE file -- comment text included, because the checksum is a cyclic
 * redundancy check over the file's lines and not over its executable statements. So a rewrite that
 * changes no SQL at all still breaks startup in every database that applied the earlier bytes. The
 * remedy when a change is genuinely needed is a NEW migration, never an edit to an applied one; the
 * remedy for an environment that has already applied superseded bytes is Flyway's repair command,
 * whose procedure is recorded in {@code docs/runbooks/data-migration.md}.</p>
 *
 * <p>Alternatives Considered: asserting only that each migration applied, which is what the module
 * asserted before this class existed. Rejected because it is exactly the assertion that passed while
 * the defect shipped: the file had applied, on a database created moments earlier from the edited
 * bytes. Also considered: computing the checksum from the file with no engine involved, which would
 * run in the unit tier and need no container. Rejected for the reason given above -- it would pin a
 * value this repository computes rather than the value a deployment stores.</p>
 *
 * <p>Trade-offs: the pinned numbers below have to be updated in the same change that ADDS a
 * migration, which is one more edit per addition. That cost is accepted because the failure it
 * replaces is a service that cannot start in an environment nobody rebuilt, and because the update
 * is mechanical: run this class, read the reported value, record it beside the script it belongs to.
 * A value that changes for a file already in this table is not a value to update -- it means an
 * applied migration was edited, and the change belongs in a new script.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
@DisplayName("Migration history: every shipped migration keeps the identity a deployment already stored")
class MigrationHistoryIT extends ReferencePersistenceBase {

    /**
     * The checksum Flyway stores for each shipped script, keyed by the script's file name.
     *
     * <p>Assumptions: the numbers are Flyway's, obtained from its own history table, and each one is
     * the value at least one already-migrated environment holds. V1 in particular is pinned at the
     * value that the environments migrated before this class existed carry, which is why its bytes
     * are not free to move even for a comment.</p>
     */
    private static final Map<String, Integer> PINNED_CHECKSUMS = Map.of(
            "V1__reference.sql", 561195120,
            "V2__seed_reference.sql", -332909786,
            "V3__reference_inquiry_reply_ledger.sql", 1668977859,
            "V4__drop_reference_inquiry_reply_ledger.sql", -894884716);

    /** The versions this module ships, in the order Flyway applies them. */
    private static final List<String> PINNED_VERSIONS = List.of("1", "2", "3", "4");

    /** A plain JDBC handle, because the history table is a catalog rather than a mapped entity. */
    private JdbcTemplate jdbc;

    /**
     * Opens a JDBC handle onto the engine the shared context built.
     *
     * @param dataSource the pool the shared context built from the container's coordinates; must not
     *     be {@code null}
     */
    @BeforeEach
    void openHandle(@Autowired DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Confirms every shipped script kept the checksum an already-migrated environment stored.
     *
     * <p>Assumptions: the failure text names the remedy rather than only the mismatch, because the
     * mismatch on its own reads as a stale expectation to update, and updating it is precisely the
     * wrong response -- it would leave the deployed environments unstartable and hide that fact.</p>
     */
    @Test
    @DisplayName("each script's checksum is the one already-migrated environments hold")
    void eachScriptKeepsItsStoredChecksum() {
        for (Map.Entry<String, Integer> pinned : PINNED_CHECKSUMS.entrySet()) {
            Integer applied = this.jdbc.queryForObject(
                    "SELECT checksum FROM reference.flyway_schema_history WHERE script = ?",
                    Integer.class, pinned.getKey());

            assertThat(applied)
                    .as("%s must keep checksum %d. A different value means the file's bytes changed"
                            + " after release, INCLUDING a comment-only change, because Flyway"
                            + " checksums the whole file. Every database that applied the earlier"
                            + " bytes will refuse to start with a validate failure. Restore the file"
                            + " and put the change in a NEW migration; repair an already-migrated"
                            + " environment with the procedure in docs/runbooks/data-migration.md.",
                            pinned.getKey(), pinned.getValue())
                    .isEqualTo(pinned.getValue());
        }
    }

    /**
     * Confirms the set of applied versions is exactly the set of shipped scripts.
     *
     * <p>Assumptions: this is the other half of the same guarantee and it fails on the opposite
     * mistake. Deleting a released migration leaves an environment holding an applied version that
     * the module no longer resolves, which Flyway reports as a missing migration and refuses just as
     * firmly as a checksum mismatch. Withdrawing an object therefore means ADDING a migration that
     * drops it, and this assertion is what notices a file that went away instead.</p>
     */
    @Test
    @DisplayName("the applied versions are exactly the shipped ones, in order")
    void theAppliedVersionsAreExactlyTheShippedOnes() {
        List<String> applied = this.jdbc.queryForList(
                "SELECT version FROM reference.flyway_schema_history"
                        + " WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class);

        assertThat(applied)
                .as("a version present here and absent from the module is a migration that was"
                        + " deleted rather than superseded, which makes every already-migrated"
                        + " environment refuse to start; withdraw an object with a new migration"
                        + " that drops it instead")
                .isEqualTo(PINNED_VERSIONS);
    }

    /**
     * Confirms every applied migration succeeded, which is what makes the checksums above meaningful.
     *
     * <p>Assumptions: a failed row would carry a checksum too, so the two assertions above would pass
     * over a history in which a script had not actually run. The success flag is read separately for
     * that reason rather than folded into the queries above, where a false value would be invisible.</p>
     */
    @Test
    @DisplayName("no shipped migration is recorded as failed")
    void noShippedMigrationIsRecordedAsFailed() {
        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM reference.flyway_schema_history WHERE success = false",
                Integer.class))
                .as("a failed history row leaves the schema in a state no later migration can assume")
                .isZero();
    }

    /**
     * Confirms the withdrawn reply ledger is absent once the shipped migrations have run.
     *
     * <p>Assumptions: this asserts the OUTCOME of the withdrawal rather than the presence of the script
     * that performs it. V3 still ships and still creates the table -- it must, because deleting a
     * released migration is what the assertion above refuses -- so on every database the table is
     * created and then dropped. Pinning V4's checksum proves the script is unchanged; only a query
     * against the catalog proves the object it names actually went away, which is the property an
     * operator cares about and the one a mis-scoped drop would break silently.</p>
     *
     * <p>Assumptions: the six reference tables are counted in the same breath, because a drop that
     * reached too far -- a cascade, or a schema-level statement -- would satisfy an absence check on its
     * own while removing the data this context exists to serve. The two halves together say the script
     * removed exactly one object.</p>
     */
    @Test
    @DisplayName("the withdrawn reply ledger is gone and the six reference tables remain")
    void theWithdrawnLedgerIsAbsentAndTheOwnedTablesRemain() {
        assertThat(this.jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = 'reference' AND table_name = 'inquiry_reply_ledger'",
                Integer.class))
                .as("V4 drops this table; a row here means the drop did not run or did not apply to the"
                        + " schema this service migrates")
                .isZero();

        assertThat(this.jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'reference' AND table_type = 'BASE TABLE'"
                        + " AND table_name <> 'flyway_schema_history' ORDER BY table_name",
                String.class))
                .as("the reference context owns exactly these six tables")
                .containsExactly("disclosure_groups", "transaction_categories", "transaction_types",
                        "us_phone_area_codes", "us_state_zip_prefixes", "us_states");
    }
}
