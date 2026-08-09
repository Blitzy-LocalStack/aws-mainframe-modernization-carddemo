package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import io.awspring.cloud.autoconfigure.sqs.SqsAutoConfiguration;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves on a real engine that one purge window commits or discards ENTIRELY, window by window.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: the purge deletes authorizations and, in the same window, reduces the account summary's
 * four accumulators by exactly what it removed. Those two writes must share a fate: a window that
 * deleted the rows and failed before the reduction would leave a summary claiming authorizations that no
 * longer exist, and one that reduced first and failed would leave the rows with their contribution
 * already taken off. Either state is silent -- nothing later in the system reads as wrong -- so the only
 * protection is that the window is one transaction, and the only way to establish that is to fail one
 * and then look at what the engine kept.
 *
 * <p>Refactoring Rationale: this class is an addition. The window boundary was previously asserted
 * against a MOCKED transaction manager, by verifying that rollback was called and commit was not. That
 * establishes what the service ASKED for and nothing about what the database DID: a stubbed manager
 * rolls nothing back, so the same verification would pass over a repository that had committed each
 * delete on its own connection. The incumbent test says so in its own rationale; this class supplies the
 * evidence it could not.
 *
 * <h2>How the failure is injected</h2>
 *
 * <p>Assumptions: both repositories are REAL, and the fault comes from a reflective PROXY wrapped around
 * the summary repository. The proxy forwards every call to the real repository and then, on the summary
 * delete for a chosen account, raises. That placement is deliberate: it is the LAST write of a window, so
 * by the time it raises the children have been removed AND the accumulators reduced AND the summary
 * deleted, and the case can therefore assert that all three were discarded rather than only the last.
 *
 * <p>Alternatives Considered: throwing from a repository stub that does not delegate. Rejected, because
 * nothing would have been written when the fault arrived, so the case would be asserting the rollback of
 * an empty transaction -- which passes whatever the boundary does, and is the shape of assertion this
 * class exists to replace.
 *
 * <p>Assumptions: every outcome is read through plain JDBC on a connection of its own, never through a
 * repository. A repository read would share the persistence context the job wrote through, so an
 * instance still held there could answer from the identity map and report a state the engine never
 * stored.
 *
 * <h2>Trade-offs</h2>
 *
 * <p>Trade-offs: the class name ends in {@code RepositoryIT} because the module's Failsafe configuration
 * includes exactly that suffix, so any other name would leave the class unrun. The suffix names the tier
 * rather than the subject.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.
 */
@Testcontainers
@SpringBootTest(
        classes = PurgeWindowRollbackRepositoryIT.PurgeWindowTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PurgeWindowRollbackRepositoryIT {

    /** The engine image, named by manifest digest so the version cannot drift: PostgreSQL 17.10-alpine. */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /** The engine every case in this class runs against, started once for the class. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

    /** The first account these cases purge. */
    private static final long FIRST_ACCOUNT_ID = 10_000_000_901L;

    /** The second account, used by the case that spans two windows. */
    private static final long SECOND_ACCOUNT_ID = 10_000_000_902L;

    /** The customer both accounts belong to. */
    private static final long CUSTOMER_ID = 902L;

    /** The encoded ordinal authorization date every seeded authorization carries: day 95 of 2024. */
    private static final int AUTH_DATE = 24_095;

    /** The decoded calendar date of that ordinal, which the run date is measured from. */
    private static final LocalDate AUTHORIZED_ON = LocalDate.ofYearDay(2024, 95);

    /** The decoded time of day of the seeded authorization. */
    private static final int AUTH_TIME = 91_500_000;

    /**
     * A second time of day, which makes both a distinct key and a distinct transaction identifier.
     *
     * <p>Assumptions: it serves two purposes and that is deliberate. Beneath one account it distinguishes
     * two authorizations by key; across two accounts it distinguishes them by transaction identifier,
     * which the schema requires to be unique per card.
     */
    private static final int SECOND_AUTH_TIME = 92_500_000;

    /** The response code of an approved authorization, which selects the approved reversal arm. */
    private static final String APPROVED = "00";

    /** The amount every seeded authorization requests and, being approved, is granted. */
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /** The credit limit the seeded summaries carry. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    /** The cash credit limit the seeded summaries carry. */
    private static final BigDecimal CASH_LIMIT = new BigDecimal("1000.00");

    /** The credit balance the seeded summaries carry, which the purge does not touch. */
    private static final BigDecimal CREDIT_BALANCE = new BigDecimal("250.00");

    /** The zero the cash balance and the declined amount are seeded at. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** How many days past the authorization date the run is dated, comfortably past expiry. */
    private static final int DAYS_PAST_AUTHORIZATION = 30;

    /** The window size these cases run with, so each window handles exactly one summary. */
    private static final int ONE_SUMMARY_PER_WINDOW = 1;

    /** The progress-log frequency, which no case reads but the parameters require. */
    private static final int LOG_FREQUENCY = 10;

    /** The expiry threshold in days, at the reference default. */
    private static final int EXPIRY_DAYS = 5;

    /** The real summary repository, which the proxy wraps and the assertions never read through. */
    @Autowired
    private PendingAuthSummaryRepository summaries;

    /** The real authorization repository the job reads and deletes through. */
    @Autowired
    private PendingAuthDetailRepository details;

    /** The manager the job's window template opens its transactions through. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The pool the JDBC observations take their own connections from. */
    @Autowired
    private DataSource dataSource;

    /** The template the seeding writes run in, which must commit before a case begins. */
    @Autowired
    private TransactionTemplate transactions;

    /**
     * Points the context's datasource and migration at the container this class started.
     *
     * @param registry the registry the framework supplies for late-bound properties
     */
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties both tables, so no case inherits another's rows.
     *
     * <p>Assumptions: authorizations before summaries, the authorization carrying the foreign key; and in
     * its own transaction, which has committed before a case begins, so the JDBC observations can tell an
     * absent row from an unseen one.
     */
    @BeforeEach
    void emptyBothTables() {
        this.transactions.executeWithoutResult(status -> {
            this.details.deleteAllInBatch();
            this.summaries.deleteAllInBatch();
        });
    }

    /**
     * A clean window commits the deletes and the reduction together.
     *
     * <p>Purpose: this is the companion the rollback case needs. Without it, a job that wrote nothing at
     * all would satisfy every assertion about a discarded window, and the pair could not tell a working
     * transaction boundary from a job that had stopped working.
     *
     * <p>Assumptions: the summary is seeded with two approved authorizations and only ONE of them is
     * given a row to purge, so the account still has pending work and the summary SURVIVES with its
     * accumulators reduced. That is the arrangement in which the reduction is observable at all -- a
     * summary that emptied would be deleted, and a deleted row cannot show what it was reduced to.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a clean window commits the authorization delete and the summary reduction together")
    void aCleanWindowCommitsBothWrites() {
        seedSummary(FIRST_ACCOUNT_ID, 2, 0);
        seedAuthorization(FIRST_ACCOUNT_ID, AUTH_TIME);

        PurgeJob.PurgeOutcome outcome = jobFailingOn(null).purge(runParameters());

        assertThat(outcome.detailsDeleted())
                .as("the run reports the one authorization it removed")
                .isEqualTo(1);
        assertThat(authorizationCount(FIRST_ACCOUNT_ID))
                .as("and the engine no longer holds it")
                .isZero();
        assertThat(summaryCount(FIRST_ACCOUNT_ID))
                .as("the summary survives, one approved authorization still standing beneath it")
                .isEqualTo(1);
        assertThat(approvedCount(FIRST_ACCOUNT_ID))
                .as("its approved count is reduced by exactly what was removed")
                .isEqualTo(1);
        assertThat(approvedAmount(FIRST_ACCOUNT_ID))
                .as("and so is its approved amount")
                .isEqualByComparingTo("100.00");
        assertThat(creditBalance(FIRST_ACCOUNT_ID))
                .as("the credit balance is not part of the reversal, transcribing cbl/CBPAUP0C.cbl"
                        + " L287 to L292, which moves four members and not the balance")
                .isEqualByComparingTo(CREDIT_BALANCE);
    }

    /**
     * A failure at the end of a window leaves none of that window's three writes behind.
     *
     * <p>Purpose: this is the property the incumbent unit test documents and cannot establish. By the
     * time the injected fault arrives the window has deleted the authorization, reduced the summary's
     * accumulators and deleted the summary itself; if any one of those had escaped the window's
     * transaction the engine would keep it, and the account would be left in a state no run produced.
     *
     * <p>Assumptions: the summary is seeded so that removing its one authorization empties it, which is
     * what carries execution as far as the summary delete the proxy is armed on. The accumulators are
     * then asserted at their SEEDED values, which is the reduction's rollback stated as an observation
     * rather than as an absence.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failure at the end of a window discards the delete, the reduction and the summary")
    void aFailedWindowDiscardsEveryWriteItMade() {
        seedSummary(FIRST_ACCOUNT_ID, 1, 0);
        seedAuthorization(FIRST_ACCOUNT_ID, AUTH_TIME);

        assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                .isThrownBy(() -> jobFailingOn(FIRST_ACCOUNT_ID).purge(runParameters()));

        assertThat(authorizationCount(FIRST_ACCOUNT_ID))
                .as("the authorization the window deleted is still there")
                .isEqualTo(1);
        assertThat(summaryCount(FIRST_ACCOUNT_ID))
                .as("and so is the summary the window deleted")
                .isEqualTo(1);
        assertThat(approvedCount(FIRST_ACCOUNT_ID))
                .as("the reduction was discarded with them, the count standing where it was seeded")
                .isEqualTo(1);
        assertThat(approvedAmount(FIRST_ACCOUNT_ID))
                .as("as does the amount, so the summary still accounts for a row that still exists")
                .isEqualByComparingTo("100.00");
    }

    /**
     * A failure in a later window leaves the earlier window's work committed.
     *
     * <p>Purpose: this is the checkpoint semantic, and it is the reason the boundary is per window rather
     * than per run. The reference commits at its checkpoint and a failure discards only the work since
     * the last one, so a run that failed after two hundred accounts must not undo the first hundred and
     * ninety-nine. A single-transaction implementation would satisfy every assertion in the case above
     * and fail here, which is why the two cases are not the same case.
     *
     * <p>Assumptions: the window is narrowed to one summary, so the two accounts are handled in two
     * windows and the boundary between them is the thing under test. The proxy is armed on the SECOND
     * account only, so the first window runs to a clean commit before the fault arrives.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a failure in a later window leaves the earlier window committed")
    void anEarlierWindowSurvivesALaterFailure() {
        seedSummary(FIRST_ACCOUNT_ID, 1, 0);
        seedAuthorization(FIRST_ACCOUNT_ID, AUTH_TIME);
        seedSummary(SECOND_ACCOUNT_ID, 1, 0);
        // WHY : Assumptions: the second account's authorization carries a DIFFERENT time of day, and
        //   therefore a different transaction identifier, because the schema declares
        //   uq_pending_auth_detail_card_transaction unique over the card and the transaction. That
        //   constraint is the idempotency guard the request consumer depends on, so two authorizations
        //   of one card sharing an identifier is not an arrangement this class may make -- even for two
        //   different accounts, the uniqueness being over the card rather than the account.
        seedAuthorization(SECOND_ACCOUNT_ID, SECOND_AUTH_TIME);

        assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                .isThrownBy(() -> jobFailingOn(SECOND_ACCOUNT_ID).purge(runParameters()));

        assertThat(authorizationCount(FIRST_ACCOUNT_ID))
                .as("the first window committed, so its authorization is gone for good")
                .isZero();
        assertThat(summaryCount(FIRST_ACCOUNT_ID))
                .as("and its emptied summary with it")
                .isZero();
        assertThat(authorizationCount(SECOND_ACCOUNT_ID))
                .as("the failing window kept nothing, so the second authorization stands")
                .isEqualTo(1);
        assertThat(summaryCount(SECOND_ACCOUNT_ID))
                .as("as does its summary")
                .isEqualTo(1);
        assertThat(approvedCount(SECOND_ACCOUNT_ID))
                .as("with the accumulator the failing window had already reduced put back")
                .isEqualTo(1);
    }

    /**
     * A window that reaches its authorization delete and then fails keeps neither of the two rows.
     *
     * <p>Purpose: the previous rollback case arms on the summary delete, which only happens when the
     * summary empties. This one covers the other shape -- two authorizations beneath a summary that does
     * NOT empty -- so the discarded window is one whose only writes are child deletes and a reduction.
     * Without it, the rollback evidence would rest entirely on runs that happened to delete their parent.
     *
     * <p>Assumptions: three approved authorizations are declared on the summary and two rows are seeded,
     * so one remains pending, the summary is not deleted, and the proxy is instead armed on the
     * REDUCTION. That is the last write such a window makes, so both child deletes precede the fault.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a window that fails at its reduction keeps both authorizations it had deleted")
    void aFailedReductionKeepsEveryAuthorizationItHadDeleted() {
        seedSummary(FIRST_ACCOUNT_ID, 3, 0);
        seedAuthorization(FIRST_ACCOUNT_ID, AUTH_TIME);
        seedAuthorization(FIRST_ACCOUNT_ID, SECOND_AUTH_TIME);

        assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                .isThrownBy(() -> jobFailingOnReduction(FIRST_ACCOUNT_ID).purge(runParameters()));

        assertThat(authorizationCount(FIRST_ACCOUNT_ID))
                .as("both authorizations the window deleted are back")
                .isEqualTo(2);
        assertThat(summaryCount(FIRST_ACCOUNT_ID))
                .as("the summary was never a candidate for deletion and is untouched")
                .isEqualTo(1);
        assertThat(approvedCount(FIRST_ACCOUNT_ID))
                .as("and its count stands where it was seeded, the reduction having been discarded")
                .isEqualTo(3);
    }

    /**
     * Builds the run parameters every case uses: one summary per window, well past expiry.
     *
     * @return parameters dated far enough past the seeded authorizations for all of them to qualify
     */
    private static PurgeJob.PurgeParameters runParameters() {
        return new PurgeJob.PurgeParameters(AUTHORIZED_ON.plusDays(DAYS_PAST_AUTHORIZATION),
                EXPIRY_DAYS, ONE_SUMMARY_PER_WINDOW, LOG_FREQUENCY);
    }

    /**
     * Builds one purge job whose summary repository raises after deleting a chosen account's summary.
     *
     * @param failForAccountId the account whose summary delete is to be followed by a fault, or
     *     {@code null} to let every call through
     * @return a job over the real repositories and a real window transaction
     */
    private PurgeJob jobFailingOn(Long failForAccountId) {
        return jobOver(proxied(new FaultAfterCall(this.summaries, "delete", failForAccountId)));
    }

    /**
     * Builds one purge job whose summary repository raises after reducing a chosen account's counters.
     *
     * @param failForAccountId the account whose reduction is to be followed by a fault
     * @return a job over the real repositories and a real window transaction
     */
    private PurgeJob jobFailingOnReduction(Long failForAccountId) {
        return jobOver(proxied(new FaultAfterCall(this.summaries, "reverseExpiredAuthorizations",
                failForAccountId)));
    }

    /**
     * Builds one purge job over a supplied summary repository, the real authorizations and a real window.
     *
     * @param summaryRepository the summary repository the job is to use
     * @return the job
     */
    private PurgeJob jobOver(PendingAuthSummaryRepository summaryRepository) {
        return new PurgeJob(summaryRepository, this.details,
                new TransactionTemplate(this.transactionManager));
    }

    /**
     * Wraps one invocation handler as a summary repository.
     *
     * @param handler the handler to route every call through
     * @return a proxy the job cannot distinguish from the real repository
     */
    private static PendingAuthSummaryRepository proxied(InvocationHandler handler) {
        return (PendingAuthSummaryRepository) Proxy.newProxyInstance(
                PendingAuthSummaryRepository.class.getClassLoader(),
                new Class<?>[] {PendingAuthSummaryRepository.class}, handler);
    }

    /**
     * Writes one summary carrying the supplied accumulators, in a transaction that has committed.
     *
     * @param accountId the account the summary belongs to
     * @param approvedCount how many approved authorizations it declares
     * @param declinedCount how many declined authorizations it declares
     */
    private void seedSummary(long accountId, int approvedCount, int declinedCount) {
        PendingAuthSummary summary = PendingAuthSummary.rehydrated(Long.valueOf(accountId),
                Long.valueOf(CUSTOMER_ID), "Y", null, null, null, null, null,
                CREDIT_LIMIT, CASH_LIMIT, CREDIT_BALANCE, ZERO,
                Short.valueOf((short) approvedCount), Short.valueOf((short) declinedCount),
                AMOUNT.multiply(BigDecimal.valueOf(approvedCount)),
                AMOUNT.multiply(BigDecimal.valueOf(declinedCount)));
        this.transactions.executeWithoutResult(status -> this.summaries.save(summary));
    }

    /**
     * Writes one approved authorization beneath an account, in a transaction that has committed.
     *
     * @param accountId the account it hangs from
     * @param authTime the decoded time of day, which makes the key distinct
     */
    private void seedAuthorization(long accountId, int authTime) {
        PendingAuthDetail authorization = PendingAuthDetail.rehydrated(
                new PendingAuthDetailKey(accountId, AUTH_DATE, authTime),
                "240404", "091500", "4111111111111111", "0100", "2712", "0100", "0100",
                "A00100", APPROVED, "0000", "003000", AMOUNT, AMOUNT,
                "5411", "840", Short.valueOf((short) 5), "MERCHANT0000001", "ACME HARDWARE",
                "SEATTLE", "WA", "981010000", transactionIdFor(authTime),
                PendingAuthDetail.MATCH_STATUS_PENDING);
        this.transactions.executeWithoutResult(status -> this.details.save(authorization));
    }

    /**
     * Composes a fifteen-character transaction identifier that is distinct per seeded authorization.
     *
     * <p>Assumptions: the width is exact rather than merely bounded, the column being a fixed-width
     * fifteen; a shorter value would be stored padded and would then not compare equal to what was
     * written.
     *
     * @param authTime the time of day the authorization carries
     * @return a fifteen-character identifier
     */
    private static String transactionIdFor(int authTime) {
        String suffix = String.valueOf(authTime);
        return "TXN" + "0".repeat(15 - 3 - suffix.length()) + suffix;
    }

    /**
     * Counts one account's authorizations on a connection of its own.
     *
     * @param accountId the account to count beneath
     * @return how many authorizations the engine holds for it
     */
    private int authorizationCount(long accountId) {
        return intColumn("SELECT count(*) FROM pending_auth_detail WHERE account_id = ?", accountId);
    }

    /**
     * Counts one account's summaries on a connection of its own.
     *
     * @param accountId the account to count
     * @return one where the engine holds a summary for it, and zero where it does not
     */
    private int summaryCount(long accountId) {
        return intColumn("SELECT count(*) FROM pending_auth_summary WHERE account_id = ?", accountId);
    }

    /**
     * Reads one account's approved-authorization count on a connection of its own.
     *
     * @param accountId the account to read
     * @return the stored count
     */
    private int approvedCount(long accountId) {
        return intColumn("SELECT approved_auth_cnt FROM pending_auth_summary WHERE account_id = ?",
                accountId);
    }

    /**
     * Reads one account's approved-authorization amount on a connection of its own.
     *
     * @param accountId the account to read
     * @return the stored amount, at the scale the column declares
     */
    private BigDecimal approvedAmount(long accountId) {
        return decimalColumn("SELECT approved_auth_amt FROM pending_auth_summary"
                + " WHERE account_id = ?", accountId);
    }

    /**
     * Reads one account's credit balance on a connection of its own.
     *
     * @param accountId the account to read
     * @return the stored balance, at the scale the column declares
     */
    private BigDecimal creditBalance(long accountId) {
        return decimalColumn("SELECT credit_balance FROM pending_auth_summary WHERE account_id = ?",
                accountId);
    }

    /**
     * Runs one single-value integer query for one account on a connection of its own.
     *
     * @param query the query to run, taking the account as its only parameter
     * @param accountId the account to bind
     * @return the first column of the first row
     * @throws IllegalStateException if the query answered no row, or could not be run
     */
    private int intColumn(String query, long accountId) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("no row answered " + query);
                }
                return rows.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the query " + query + " could not be run", failure);
        }
    }

    /**
     * Runs one single-value numeric query for one account on a connection of its own.
     *
     * <p>Assumptions: read as an exact decimal rather than through any binary floating type, these being
     * money columns whose stored scale is part of what the cases assert.
     *
     * @param query the query to run, taking the account as its only parameter
     * @param accountId the account to bind
     * @return the first column of the first row
     * @throws IllegalStateException if the query answered no row, or could not be run
     */
    private BigDecimal decimalColumn(String query, long accountId) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("no row answered " + query);
                }
                return rows.getBigDecimal(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the query " + query + " could not be run", failure);
        }
    }

    /**
     * Forwards every call to a real summary repository and raises AFTER one chosen call has been made.
     *
     * <p>Assumptions: it forwards FIRST and raises SECOND. A handler that raised instead of forwarding
     * would leave the window with nothing written when the fault arrived, and the rollback assertions
     * would then hold over an empty transaction whatever the boundary did.
     *
     * <p>Assumptions: the account is matched from the call's own arguments -- the summary's key on a
     * delete, the bound account on a reduction -- rather than from a call counter, so a case that spans
     * two windows fails in the window it means to and not merely on the nth call.
     */
    private static final class FaultAfterCall implements InvocationHandler {

        /** The real repository every call is forwarded to. */
        private final PendingAuthSummaryRepository delegate;

        /** The name of the method after which the fault is raised. */
        private final String methodName;

        /** The account the fault is armed for, or {@code null} to arm for none. */
        private final Long failForAccountId;

        /**
         * Arms a handler for one method and one account.
         *
         * @param delegate the real repository to forward to; must not be {@code null}
         * @param methodName the method after which to raise
         * @param failForAccountId the account to raise for, or {@code null} to raise for none
         */
        FaultAfterCall(PendingAuthSummaryRepository delegate, String methodName,
                Long failForAccountId) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            this.methodName = methodName;
            this.failForAccountId = failForAccountId;
        }

        /**
         * Forwards the call and then raises where this handler is armed for it.
         *
         * @param proxy the proxy the call arrived on, which is not used
         * @param method the method invoked
         * @param args the arguments it was invoked with, or {@code null} where it takes none
         * @return whatever the real repository answered
         * @throws Throwable the real repository's own failure, unwrapped from its reflective wrapper,
         *     or the injected fault where this handler is armed for the call
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object answer;
            try {
                answer = method.invoke(this.delegate, args);
            } catch (InvocationTargetException reflective) {
                throw reflective.getCause();
            }
            if (this.failForAccountId != null && this.methodName.equals(method.getName())
                    && namesTheArmedAccount(args)) {
                throw new IllegalStateException("the window's " + this.methodName
                        + " is followed by a fault, so nothing it wrote may survive");
            }
            return answer;
        }

        /**
         * Reports whether a call's arguments name the account this handler is armed for.
         *
         * @param args the arguments the call carried
         * @return whether the armed account is the one this call concerned
         */
        private boolean namesTheArmedAccount(Object[] args) {
            if (args == null || args.length == 0) {
                return false;
            }
            if (args[0] instanceof PendingAuthSummary summary) {
                return this.failForAccountId.equals(summary.getAccountId());
            }
            return this.failForAccountId.equals(args[0]);
        }
    }

    /**
     * The narrowest context that can host the real repositories against a real engine.
     *
     * <p>Assumptions: the purge job is NOT declared here. Each case builds its own over a differently
     * armed proxy, and the job takes its window template as a constructor argument rather than relying on
     * an annotation, so a bean declaration would add no proxy the cases need.
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = {OAuth2ResourceServerAutoConfiguration.class, SqsAutoConfiguration.class})
    @EntityScan("com.carddemo.authorization.domain")
    @EnableJpaRepositories("com.carddemo.authorization.repository")
    static class PurgeWindowTestApplication {

        /**
         * A template for the seeding and emptying writes, which must commit before a case begins.
         *
         * @param manager the context's transaction manager
         * @return a template that begins and commits one transaction per call
         */
        @Bean
        TransactionTemplate seedingTransactions(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
