// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/service/ReferenceBatchUpdateServiceIT.java
// -----------------------------------------------------------------------------
// Purpose:
//      Proves, against a real PostgreSQL engine, that both maintenance entry
//      points of ReferenceBatchUpdateService actually write -- and that a
//      refused record or action is reported as an outcome without taking the
//      records that already applied down with it.
//
// WHY (non-obvious design decisions):
//  (1) Refactoring Rationale: this class exists because the defect it guards
//      against was INVISIBLE to a mocked repository. Both entry points reached
//      their writes by calling a @Transactional method on `this`, which does not
//      pass through the transactional proxy, so no transaction was ever opened;
//      and TransactionTypeRepository.insertType is a modifying native statement,
//      which the persistence layer refuses to execute outside one. Against a
//      test double the call simply returned whatever it was told, so every unit
//      case passed while the first ADD of any real run failed. Only a real
//      engine can tell the two apart, which is the whole argument for the cost
//      of starting one here.
//  (2) Assumptions: the suffix IT is what places these cases at the verify
//      phase. maven-failsafe-plugin is declared in services/pom.xml with its
//      default includes, which match **/*IT.java anywhere in the test tree
//      rather than within one package, so the suffix and not the package decides
//      the phase. Surefire's default includes do not match this name, so it is
//      collected exactly once.
//  (3) Trade-offs: this file starts its own engine rather than sharing the one
//      the repository test package starts, and that is the accepted cost of the
//      fixture there being package-private -- which is correct, since a fixture
//      reachable from every package is a fixture nothing constrains. The
//      alternative, widening it to public, was rejected for that reason.
//  (4) Assumptions: the four rationale labels used below are the PLURAL forms
//      docs/CODE_DOCUMENTATION_STANDARD.md rules for, so that they can be found
//      by grep before they are read by a person.
// =============================================================================
package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.MaintenanceActionBatchRequest;
import com.carddemo.reference.dto.MaintenanceActionBatchResponse;
import com.carddemo.reference.dto.MaintenanceActionRequest;
import com.carddemo.reference.repository.TransactionTypeRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies both maintenance paths write for real, and that a refusal does not abandon the run.
 *
 * <p>Purpose: every other assertion about this service is made against a mocked repository, which is
 * correct for branch structure and useless for the one property under test here -- whether the statements
 * execute at all. The service's writes reach a modifying native statement that the persistence layer
 * refuses to run outside a transaction, and a mock has no such objection, so a service that opened no
 * transaction satisfied every unit case and failed on its first real insert.
 *
 * <p>Assumptions: each case asserts the STORED ROW and not only the reported outcome. An outcome saying
 * {@code APPLIED} is produced by the service's own code path and would still be produced by a write that
 * was rolled back; reading the row back afterwards is what makes the commit part of the assertion.
 *
 * <p>Trade-offs: this class starts an engine, so it is among the slowest in the module. What it buys is
 * the only evidence available that the transaction boundary is real, together with proof that a doomed
 * transaction is confined to the one action that doomed it -- a property that depends on the engine's
 * own behaviour after a constraint violation and cannot be established any other way.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.
 */
@SpringBootTest(
        classes = ReferenceBatchUpdateServiceIT.MaintenanceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ReferenceBatchUpdateServiceIT {

    // WHY : Assumptions: the image is named by DIGEST and it is the SAME digest the repository test
    //       package and the ledger context pin. The properties under test here are engine behaviours --
    //       whether a modifying statement runs, and what a constraint violation does to the surrounding
    //       unit of work -- so a moving tag could change either between two runs of unchanged code.
    /** PostgreSQL 17 on Alpine, pinned by manifest digest rather than by a moving tag. */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    // WHY : Refactoring Rationale: the engine is started in a static initialiser rather than through the
    //       lifecycle annotations, which is the idiom the repository test package arrived at after
    //       measuring the alternative: the JUnit extension owns an annotated container for the duration
    //       of ONE class, so a cached application context shared across classes outlives it and every
    //       later class fails on connection acquisition after a pool timeout -- reporting an empty pool
    //       rather than a stopped container. Testcontainers' reaper removes this engine when the JVM
    //       exits, so nothing has to stop it explicitly.
    /** The engine this class runs against, started once for the JVM. */
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
    }

    /** A code the seed migration does not load, so an insert of it cannot collide. */
    private static final String UNSEEDED_TYPE_CD = "77";

    /** A second unseeded code, so a run can carry two inserts that are independent of each other. */
    private static final String SECOND_UNSEEDED_TYPE_CD = "78";

    /** A code the seed loads and gives children to, so a delete of it is refused by the declared key. */
    private static final String REFERENCED_TYPE_CD = "06";

    /** The fixed length of one maintenance record, transcribed from the program's own declaration. */
    private static final int RECORD_LENGTH = ReferenceBatchUpdateService.RECORD_LENGTH;

    /** The service under test, built by the configuration below over the real repository. */
    @Autowired
    private ReferenceBatchUpdateService service;

    /** The repository the stored rows are read back through. */
    @Autowired
    private TransactionTypeRepository types;

    /**
     * Composes one maintenance record: the action byte, the two-byte code, then the padded description.
     *
     * <p>Assumptions: blank is the fill byte, which the program declares as {@code VALUE SPACES} on each
     * of its three fields, and the padding is applied to the composed record rather than to the
     * description alone so a caller may hand in a description of exactly the declared width.</p>
     *
     * @param actionByte the single byte at offset zero, selecting the branch
     * @param typeCode the two bytes at offsets one and two, passed through exactly as given
     * @param description the bytes from offset three onward, right-padded here
     * @return a record of exactly {@link #RECORD_LENGTH} bytes in US-ASCII; never {@code null}
     */
    private static byte[] record(char actionByte, String typeCode, String description) {
        StringBuilder composed = new StringBuilder(RECORD_LENGTH);
        composed.append(actionByte).append(typeCode).append(description);
        while (composed.length() < RECORD_LENGTH) {
            composed.append(' ');
        }
        return composed.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Asserts an ADD arriving on the published-action path is actually written and committed.
     *
     * <p>⚠️ Refactoring Rationale: this is the case the finding names. Before the transaction boundary was
     * opened by a template, this call raised
     * {@code InvalidDataAccessApiUsageException: Executing an update/delete query} on its first action --
     * the annotation that was supposed to open one sat on a method reached by self-invocation, so the
     * proxy never saw the call. Every mocked case passed throughout.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an added type on the published-action path is stored and readable afterwards")
    void anAddedTypeOnThePublishedPathIsStored() {
        MaintenanceActionBatchResponse reply = this.service.apply(new MaintenanceActionBatchRequest(
                List.of(new MaintenanceActionRequest("INSERT", UNSEEDED_TYPE_CD, "Written for real"))));

        assertThat(reply.outcomes()).hasSize(1);
        assertThat(reply.outcomes().get(0).outcome())
                .as("a modifying statement executed outside a transaction is refused, not applied")
                .isEqualTo(ReferenceBatchUpdateService.OUTCOME_APPLIED);
        assertThat(reply.returnCode()).isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_CLEAN);

        assertThat(this.types.findByTypeCd(UNSEEDED_TYPE_CD))
                .as("the row must be readable in a LATER unit of work, which is what commit means")
                .map(TransactionType::getDescription)
                .contains("Written for real");
    }

    /**
     * Asserts an ADD arriving on the baseline record-stream path is actually written and committed.
     *
     * <p>Assumptions: the record path is asserted separately from the published path even though both
     * reach the same statement, because they reached it through two different broken arrangements: the
     * published path had an annotation that could not fire, and the record path had no annotation at all.
     * A single case over either would have left the other unproven.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("an added type on the record-stream path is stored and readable afterwards")
    void anAddedTypeOnTheRecordStreamPathIsStored() {
        ReferenceBatchUpdateService.BatchUpdateResult result = this.service.apply(
                new ByteArrayInputStream(record('A', SECOND_UNSEEDED_TYPE_CD, "Stream written")));

        assertThat(result.outcomes()).hasSize(1);
        assertThat(result.outcomes().get(0).succeeded())
                .as("the record path reached the same statement through no annotation at all")
                .isTrue();

        assertThat(this.types.findByTypeCd(SECOND_UNSEEDED_TYPE_CD))
                .map(TransactionType::getDescription)
                .contains("Stream written");
    }

    /**
     * Asserts a refused action is reported and the actions after it still commit.
     *
     * <p>Purpose: this is the property {@code PROPAGATION_REQUIRES_NEW} exists for, and it is the one an
     * engine can contradict. A constraint violation leaves the surrounding unit of work marked
     * rollback-only, so an arrangement that enclosed the whole run in one transaction -- or that caught the
     * violation inside the boundary and let the template try to commit it -- would either roll back the
     * work that had already applied or lose the classified outcome to an
     * {@code UnexpectedRollbackException}. Both are observable only against a real engine.</p>
     *
     * <p>Assumptions: the refusal is provoked by a duplicate primary key on a SEEDED code rather than by a
     * value the schema merely dislikes, because the duplicate is the refusal the published contract has
     * its own message for and is therefore the one a consumer can act on.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a refused action is reported and the action after it still commits")
    void aRefusedActionDoesNotRollBackTheActionAfterIt() {
        String survivor = "79";

        MaintenanceActionBatchResponse reply = this.service.apply(new MaintenanceActionBatchRequest(
                List.of(
                        new MaintenanceActionRequest("INSERT", REFERENCED_TYPE_CD, "Duplicate code"),
                        new MaintenanceActionRequest("INSERT", survivor, "Survives the refusal"))));

        assertThat(reply.outcomes()).hasSize(2);
        assertThat(reply.outcomes().get(0).applied())
                .as("a code the seed already carries is refused by the primary key")
                .isFalse();
        assertThat(reply.outcomes().get(1).applied())
                .as("the run continues past a refusal, which is the baseline's own behaviour")
                .isTrue();
        assertThat(reply.returnCode())
                .as("one tolerated refusal grades the run as a soft warn, not a failure")
                .isEqualTo(ReferenceBatchUpdateService.RETURN_CODE_SOFT_WARN);

        assertThat(this.types.findByTypeCd(survivor))
                .as("the later action's row must survive the earlier action's rollback")
                .isPresent();
        assertThat(this.types.findByTypeCd(REFERENCED_TYPE_CD))
                .map(TransactionType::getDescription)
                .as("the refused insert must not have altered the row whose key it collided with")
                .isNotEqualTo(Optional.of("Duplicate code"));
    }

    /**
     * Asserts a delete the declared foreign key forbids is reported and leaves the row in place.
     *
     * <p>Assumptions: the key is the one declared at
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 to 7 with {@code ON DELETE RESTRICT},
     * carried into the migrated schema unweakened. The refusal is raised by the flush inside the
     * boundary rather than at its commit, which is what makes it attributable to this action; a removal
     * whose refusal arrived after the outcome had been reported would be reported as applied.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a restricted delete is reported as this action's outcome and the row survives")
    void aRestrictedDeleteIsReportedAndTheRowSurvives() {
        MaintenanceActionBatchResponse reply = this.service.apply(new MaintenanceActionBatchRequest(
                List.of(new MaintenanceActionRequest("DELETE", REFERENCED_TYPE_CD, null))));

        assertThat(reply.outcomes()).hasSize(1);
        assertThat(reply.outcomes().get(0).applied())
                .as("a type a category still references cannot be removed")
                .isFalse();
        assertThat(this.types.findByTypeCd(REFERENCED_TYPE_CD))
                .as("a refused removal must leave the row exactly where it was")
                .isPresent();
    }

    /**
     * The minimal application these cases are bootstrapped from.
     *
     * <p>Assumptions: entities and repositories are scanned explicitly and the service is declared as a
     * bean by hand, rather than the module's own entry point being used. That entry point pulls in the
     * security chain, the queue listener and the interface document, none of which a maintenance write
     * exercises and each of which would then need configuration supplied for no assertion's sake.</p>
     *
     * <p>Assumptions: the service is a {@code @Bean} method and NOT a component scan over its package.
     * Scanning that package would also register every other service in it, each with its own
     * collaborators to satisfy, which is exactly the configuration burden the explicit entry point was
     * avoided to escape.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.reference.domain")
    @EnableJpaRepositories("com.carddemo.reference.repository")
    static class MaintenanceTestApplication {

        /**
         * Builds the service under test over the real repository and the real transaction manager.
         *
         * @param types the repository this run reads and writes; supplied by the repository scan
         * @param transactionManager the manager each per-record unit of work is opened against, supplied
         *     by the persistence auto-configuration
         * @return the service under test; never {@code null}
         */
        @Bean
        ReferenceBatchUpdateService referenceBatchUpdateService(TransactionTypeRepository types,
                PlatformTransactionManager transactionManager) {
            return new ReferenceBatchUpdateService(types, transactionManager);
        }
    }
}
