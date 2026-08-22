package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds every RELEASED Flyway migration in the repository byte-for-byte to the bytes it was released
 * with, so that an already-applied migration cannot be edited in place.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>Refactoring Rationale: this class answers a runtime defect rather than a stylistic preference. Two
 * migrations that had already been applied were later edited -- {@code V1__reference.sql} and
 * {@code V1__batch.sql} -- and in both cases the edit changed only prose. Flyway's checksum covers the
 * whole file, comments included, so every environment that had already run the earlier bytes refused to
 * start: {@code reference-service} aborted with "Migration checksum mismatch for migration version 1:
 * applied 561195120 / resolved -1455475555" under the {@code validate-on-migrate} and
 * {@code clean-disabled} posture the services deliberately run with. The DDL was byte-identical on both
 * sides, so nothing about the schema was wrong; what was wrong was that the upgrade path had been broken
 * by a change no test could see. {@code V1__account.sql}'s own header already states the rule -- a later
 * change arrives as a new versioned migration, never as an edit here -- and this class is what makes that
 * rule enforceable rather than advisory.
 *
 * <p>Assumptions: the guard belongs in the shared module's test tree rather than once per owning service,
 * because the rule is one rule and the failure it prevents is identical in all seven schemas. Two sibling
 * classes in this package already read the repository tree this way for the same reason --
 * {@link ServiceReadmeInventoryTest} and {@link ServiceCatalogInventoryTest} -- so the mechanism is
 * established rather than introduced here.
 *
 * <h2>Why two digests per file</h2>
 *
 * <p>Assumptions: each entry records a SHA-256 of the file's exact bytes AND the Flyway checksum of the
 * same file, and both are asserted. They answer different questions and neither substitutes for the
 * other. The SHA-256 is the stricter test: it changes for any edit whatsoever, including one Flyway would
 * forgive, so it is what actually enforces immutability. The Flyway checksum is the value an operator
 * reads out of {@code flyway_schema_history.checksum} in a live database, so recording it here lets
 * anyone compare this repository against a deployed environment without running the application at all --
 * which is precisely the comparison that would have caught the defect above in one command.
 *
 * <p>Assumptions: the Flyway checksum is computed here rather than obtained from the library, and the
 * arithmetic is the library's own: a single {@link CRC32} updated once per line, with line terminators
 * removed and no trailing empty line contributed by a final newline. That reimplementation is not taken
 * on trust. It was validated against a live database holding twenty-one applied rows across seven schemas
 * and reproduced every recorded value exactly, including both drifted ones. Alternatives Considered:
 * calling Flyway's own calculator. Rejected because it lives under {@code org.flywaydb.core.internal},
 * which the library documents as unstable, so a minor upgrade could remove the very check that guards the
 * upgrade path; a documented, validated eight-line computation is the more durable of the two.
 *
 * <h2>Why a transcription rather than a discovery</h2>
 *
 * <p>Alternatives Considered: deriving the expected digests from the files themselves, which would need no
 * maintenance. Rejected because a derived expectation cannot fail: it would change with the file it is
 * meant to hold, which is the whole failure mode this class exists to stop.
 * {@link ServiceReadmeInventoryTest} rejects the same shortcut for the same reason. Adding a genuinely new
 * migration therefore costs one line here, in the commit that adds it, and that line is the record that
 * the migration has been released.
 *
 * <p>Trade-offs: because the on-disk set and the recorded set are required to match exactly, a migration
 * being iterated on before it is released also has to carry its line and update it on each edit. That
 * friction is accepted knowingly: the alternative is a rule that admits "not released yet" as a state this
 * class cannot verify, and an unverifiable exemption is exactly where an edit to an applied file would
 * hide.
 */
@DisplayName("released Flyway migrations are immutable")
class ReleasedMigrationImmutabilityTest {

    /** A file that exists only at the repository root, used to recognise it while walking upwards. */
    private static final String ROOT_MARKER = "services/pom.xml";

    /** The directory holding every Maven module, relative to the repository root. */
    private static final String SERVICES_DIRECTORY = "services";

    /** The path, within one module, at which Flyway resolves that module's migrations. */
    private static final String MIGRATION_DIRECTORY = "src/main/resources/db/migration";

    /** The rule a failure of this class reports, stated once so both assertions carry it. */
    private static final String RULE = "an APPLIED Flyway migration is immutable: its checksum is"
            + " recorded in every environment that ran it, so editing it -- even to change only a"
            + " comment -- makes those environments refuse to start under validate-on-migrate. A later"
            + " change arrives as a NEW versioned migration. If this migration is genuinely new, record"
            + " its digests in this class in the same commit that adds it.";

    /**
     * One released migration and the two digests it was released with.
     *
     * @param module the Maven module under {@code services} that owns the migration
     * @param script the migration's file name, which is also the name Flyway records
     * @param sha256 the lower-case hexadecimal SHA-256 of the file's exact bytes
     * @param flywayChecksum the value Flyway records in {@code flyway_schema_history.checksum}
     */
    private record ReleasedMigration(String module, String script, String sha256,
            int flywayChecksum) {

        /**
         * Renders the identity a failure message names, so a reader is told which file is at fault.
         *
         * @return the module-qualified script name, never {@code null}
         */
        private String identity() {
            return this.module + "/" + this.script;
        }
    }

    /**
     * Builds one released-migration record.
     *
     * <p>Assumptions: a factory rather than direct construction, purely so the table below reads as a
     * table. The record's own constructor would repeat its type name twenty-four times and push each
     * entry onto three lines.
     *
     * @param module the owning module; must not be {@code null}
     * @param script the migration file name; must not be {@code null}
     * @param sha256 the released bytes' SHA-256 in lower-case hexadecimal; must not be {@code null}
     * @param flywayChecksum the released bytes' Flyway checksum
     * @return the record, never {@code null}
     */
    private static ReleasedMigration released(String module, String script, String sha256,
            int flywayChecksum) {
        return new ReleasedMigration(module, script, sha256, flywayChecksum);
    }

    /**
     * Every migration this repository has released, with the digests it was released with.
     *
     * <p>Assumptions: the list is ordered by module and then by version, which is the order Flyway
     * applies them in and the order {@code flyway_schema_history} reports them in, so a reader can put
     * this table beside a live query and read the two together.
     *
     * @return the released migrations, never {@code null} and never empty
     */
    private static List<ReleasedMigration> releasedMigrations() {
        return List.of(
                released("account-service", "V1__account.sql",
                        "803ab6a7227862c11c0f0e3ab1e3e87c182aaf40e5220cc1048d694b83746bcc",
                        -1797008526),
                released("account-service", "V2__account_inquiry_reply_ledger.sql",
                        "fe43b6017e84bcd7d1b9375943829417e6c21c3089589b934e1bdfd439c04e14",
                        258081528),
                released("auth-service", "V1__auth.sql",
                        "9d31078cdb4ca20303c0cced1d221cb2c168af22d840180d2aaac65cf1d017be",
                        1349438659),
                released("auth-service", "V2__auth_identity_sync.sql",
                        "3af82d0f8c6871a7da0c1a277ccc4a85bdc9b5feff49a85b32514249dbdc3b9c",
                        -1911694883),
                released("auth-service", "V3__auth_identity_sync_operations.sql",
                        "0c6c50953fd7bebaa650773eb9656222c632a6293f98519556be24101da9d075",
                        -1019710415),
                released("auth-service", "V4__auth_folded_user_id.sql",
                        "e19f67d0e111b4f4b39439ba7c7c20cf18a0f7ab19ba4146e86495ec3669cf01",
                        -1270417446),
                released("auth-service", "V5__auth_folded_user_id_trim.sql",
                        "99852556bd295497ecac4404ab0b76f15f1acddc5a4224b2bdd53571dff4391e",
                        774803838),
                released("auth-service", "V6__auth_canonical_user_id.sql",
                        "a3d90e5b3529044d0a6f90f9fd12820657840c7764995f3ae02537e44c9e32c7",
                        -1433777562),
                released("auth-service", "V7__auth_identity_sync_provisioning_guard.sql",
                        "fc710d88dcf8bc55a8a86a292b08502a57769497558e1a2abef7f4f69fb5b18d",
                        747237720),
                // WHY : Assumptions: V8 narrows auth.users.user_id to the URI-safe canonical domain
                //       that UserController addresses as a single path segment, so it arrives as a NEW
                //       versioned migration rather than as an edit to V6 -- V6 is released and its
                //       checksum is recorded in every environment that ran it. Its digests are
                //       recorded here in the same commit that adds it, which is what this class asks
                //       of a genuinely new migration.
                released("auth-service", "V8__auth_addressable_user_id.sql",
                        "996ce1a6234e696d72e4ffcc22f27d1c068e3f87cb6fa32f5755a6994da4914f",
                        -553492156),
                released("authorization-service", "V1__authorization.sql",
                        "8ccf0a709156a16659f0c8afe5311a1c8004484f4d1c2f93fb8aef2a07929b9a",
                        -507180909),
                released("authorization-service", "V2__authorization_outbox_claim_version.sql",
                        "e4079c08b5a44ee833711aecc50ea168f9e3c0745d60549abfc0ab215ca9c86f",
                        1845462073),
                released("authorization-service", "V3__authorization_outbox_fifo_identities.sql",
                        "eb9fa2156ab88c959e16f99b3b82cc0ab17bb3345bebce8df43e86eedf5adf36",
                        1045020743),
                released("authorization-service", "V4__authorization_outbox_send_acceptance.sql",
                        "80ce72f39aa5c919bc1be4d2c1e17b7afa8312d188def348c5818ed21f4a14e1",
                        1087490245),
                released("batch-service", "V1__batch.sql",
                        "aac1918a657d65ae413bc4d0c94629e53d942873b45035c0a4860f9b35a1cfc1",
                        1224491848),
                released("batch-service", "V2__batch_feed_watermark.sql",
                        "f05eea4859d7b35daca9b7b0819e22a72faad72c407109d5fde814ed7554d789",
                        -1394169399),
                // WHY : Refactoring Rationale: this entry is the record of the remedy this class
                //       forced. V1__batch.sql's post-state block miscounts the table it creates --
                //       it reads "seven columns and five named constraints" where the table has
                //       eight and seven -- and correcting that block in place is exactly the edit
                //       this class refuses, because the file has been applied and Flyway's checksum
                //       covers comments. So the accurate statement arrives as a new migration, which
                //       also writes the commentary V1 omitted for the eighth column and for all
                //       seven constraints. Adding it costs this one line, in the commit that adds
                //       it, which is what this class's own documentation prescribes.
                // WHY : Trade-offs: V1's frozen block still carries the stale counts and no route
                //       exists to change that. The remedy makes every OTHER route to the contract
                //       accurate -- the migration set, the catalogue and the module README -- and
                //       the alternative, re-recording V1's digests here, would have risked a service
                //       that will not start for the benefit of one comment.
                released("batch-service", "V3__batch_run_contract_restatement.sql",
                        "fe28fc38579cb7c46a4087d1c0cf86dcf666b31f637c619f3747033cd5dd58e2",
                        -174390203),
                released("card-service", "V1__card.sql",
                        "8708a75dc3543f58999c666be63ec270f9cfe941a22630f0187d02823f36555a",
                        1450941986),
                released("card-service", "V2__card_num_digit_domain.sql",
                        "bc4424a30f9e0b01df59417f8e490f557ae1818027deb12a4546a8f55ef1114a",
                        1819629723),
                released("reference-service", "V1__reference.sql",
                        "4a17d83c7137aa9065879bb18bc4473f76c18914859178bc424eb6cb880cf706",
                        561195120),
                released("reference-service", "V2__seed_reference.sql",
                        "d92a6761282b8a47c4a514040f9c8d8390bcc8e282c6c3f11213a56530cf94b5",
                        -332909786),
                released("reference-service", "V3__reference_inquiry_reply_ledger.sql",
                        "67c5e8f99d187625d5344ad14384a9333bfad9614edd44620674b3f251ddd0fb",
                        1668977859),
                // WHY : Assumptions: the migration that WITHDRAWS the table V3 created is recorded on
                //       exactly the same terms as the one that created it. V3 stays in the tree because
                //       deleting a released script is the same upgrade-path break this class exists for --
                //       an environment that applied it would find its history naming a file that no longer
                //       resolves -- so the ledger is created and then dropped on every database and the
                //       reference schema settles at its six reference tables. Trade-offs: recording a
                //       reversal here reads oddly beside the file it reverses, and it is still the right
                //       entry: from this class's point of view a reversal is just another script Flyway
                //       has checksummed, and leaving it out would have failed
                //       everyMigrationInTheTreeIsRecorded, which is the half of the guard that stops the
                //       record being kept honest by omission.
                released("reference-service", "V4__drop_reference_inquiry_reply_ledger.sql",
                        "6a22b5f5ee9cf1f8eff8589e8bb9b4110276606ebd1f207873fee94abddd4dc4",
                        -894884716),
                released("transaction-service", "V1__ledger.sql",
                        "3af0e78d9fd9b4fb01239c8ee662cc96ec3f379869a2842cf0e95ec9ef930586",
                        1895802025),
                released("transaction-service", "V2__ledger_transaction_id_allocator.sql",
                        "48901556fa1ba4df745d6dce4fad341e50668a22f865cf53e91ecb476c6db24e",
                        584225949),
                released("transaction-service", "V3__ledger_bytewise_collation.sql",
                        "b4c0b186659345be2679b9e250a5bbb9262312cb0ce798f820ba5af69a0ef36d",
                        2013828371));
    }

    /**
     * Every released migration still carries the exact bytes, and the exact Flyway checksum, it was
     * released with.
     */
    @Test
    @DisplayName("no released migration has been edited in place")
    void releasedMigrationsStillCarryTheirReleasedBytes() {
        List<String> drifted = new ArrayList<>();

        for (ReleasedMigration migration : releasedMigrations()) {
            Path file = migrationFile(migration);
            assertThat(file)
                    .as("released migration %s must still exist. %s", migration.identity(), RULE)
                    .isRegularFile();

            byte[] bytes = readBytes(file);
            String actualSha = sha256(bytes);
            int actualChecksum = flywayChecksum(bytes);
            if (!migration.sha256().equals(actualSha)
                    || migration.flywayChecksum() != actualChecksum) {
                drifted.add(migration.identity()
                        + ": released sha256=" + migration.sha256()
                        + " flywayChecksum=" + migration.flywayChecksum()
                        + " but the file now hashes to sha256=" + actualSha
                        + " flywayChecksum=" + actualChecksum);
            }
        }

        assertThat(drifted)
                .as("%s", RULE)
                .isEmpty();
    }

    /**
     * The record above names every migration the tree holds, so a new one cannot arrive unrecorded.
     *
     * <p>Assumptions: this is the half of the guard that stops the record being kept honest by deletion.
     * Without it, an author could remove an entry rather than answer the first assertion, and the file
     * would then be editable again with the build still green.
     */
    @Test
    @DisplayName("every migration in the tree is recorded as released")
    void everyMigrationInTheTreeIsRecorded() {
        Map<String, Path> onDisk = migrationsOnDisk();
        List<String> recorded = releasedMigrations().stream()
                .map(ReleasedMigration::identity)
                .sorted()
                .toList();

        assertThat(onDisk.keySet().stream().sorted().toList())
                .as("the released record and the migration tree must name the same files. %s", RULE)
                .isEqualTo(recorded);
    }

    /**
     * Resolves the file one released migration names.
     *
     * @param migration the released migration; must not be {@code null}
     * @return the path the migration occupies, whether or not it exists; never {@code null}
     */
    private static Path migrationFile(ReleasedMigration migration) {
        return repositoryRoot()
                .resolve(SERVICES_DIRECTORY)
                .resolve(migration.module())
                .resolve(MIGRATION_DIRECTORY)
                .resolve(migration.script());
    }

    /**
     * Finds every migration the services tree holds, keyed the way the record names them.
     *
     * @return the migrations on disk, keyed by module-qualified script name; never {@code null}
     * @throws UncheckedIOException when the tree cannot be walked, which is a broken checkout rather
     *     than a failed assertion
     */
    private static Map<String, Path> migrationsOnDisk() {
        Path services = repositoryRoot().resolve(SERVICES_DIRECTORY);
        Map<String, Path> found = new TreeMap<>();
        try (Stream<Path> modules = Files.list(services)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path migrations = module.resolve(MIGRATION_DIRECTORY);
                if (!Files.isDirectory(migrations)) {
                    continue;
                }
                try (Stream<Path> scripts = Files.list(migrations)) {
                    for (Path script : scripts.filter(Files::isRegularFile).toList()) {
                        String name = script.getFileName().toString();
                        // WHY : Assumptions: only versioned migrations are governed. Flyway's
                        //       repeatable scripts carry an R prefix and are re-applied whenever their
                        //       checksum changes, so immutability is not their contract; this
                        //       repository declares none today, and admitting one silently would be
                        //       the wrong default for a class whose whole subject is immutability.
                        if (name.startsWith("V") && name.endsWith(".sql")) {
                            found.put(module.getFileName() + "/" + name, script);
                        }
                    }
                }
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("the services tree could not be walked", cause);
        }
        return found;
    }

    /**
     * Locates the repository root by walking upwards until the aggregator POM is visible.
     *
     * @return the repository root directory; never {@code null}
     * @throws IllegalStateException when no ancestor of the working directory contains the anchor, which
     *     means the test is running from outside a checkout and no assertion here could be meaningful
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(ROOT_MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of " + Path.of("").toAbsolutePath() + " contains " + ROOT_MARKER
                        + ", so the repository root could not be located");
    }

    /**
     * Reads a migration's exact bytes.
     *
     * @param file the file to read; must not be {@code null}
     * @return the file's bytes; never {@code null}
     * @throws UncheckedIOException when the file cannot be read, which is a broken checkout rather than
     *     a failed assertion
     */
    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException cause) {
            throw new UncheckedIOException("migration " + file + " could not be read", cause);
        }
    }

    /**
     * Renders a file's SHA-256 as lower-case hexadecimal.
     *
     * @param bytes the file's exact bytes; must not be {@code null}
     * @return the digest; never {@code null}
     * @throws IllegalStateException when the platform cannot supply SHA-256, which no supported
     *     runtime does
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is required by every supported runtime", absent);
        }
    }

    /**
     * Computes the checksum Flyway records for a migration.
     *
     * <p>Assumptions: the arithmetic is Flyway's own -- one {@link CRC32} updated once per line, with
     * the line terminator excluded, and no contribution from the empty remainder after a final newline.
     * The reasoning for reimplementing it, and the live validation that established it, are on this
     * class.
     *
     * @param bytes the file's exact bytes; must not be {@code null}
     * @return the checksum, as the signed 32-bit value the history table stores
     */
    private static int flywayChecksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        int lastIndex = lines.length - 1;
        for (int index = 0; index < lines.length; index++) {
            // WHY : Assumptions: a trailing newline yields a final empty element which contributes
            //       NOTHING, because Flyway reads lines through a reader that returns null at the end
            //       of the stream rather than yielding an empty last line. Contributing it would
            //       change every checksum of every file that ends in a newline -- which is all of
            //       them.
            if (index == lastIndex && lines[index].isEmpty()) {
                continue;
            }
            // WHY : Assumptions: a carriage return is stripped as part of the terminator, so a file
            //       checked out with Windows line endings hashes to the same value as the same file
            //       with Unix endings. That is Flyway's behaviour and it is what keeps this guard from
            //       failing on a developer machine whose Git configuration converts terminators.
            String line = lines[index].endsWith("\r")
                    ? lines[index].substring(0, lines[index].length() - 1)
                    : lines[index];
            crc.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return (int) crc.getValue();
    }
}
