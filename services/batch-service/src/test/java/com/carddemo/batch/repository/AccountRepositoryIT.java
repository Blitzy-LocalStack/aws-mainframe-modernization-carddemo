package com.carddemo.batch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.service.CategoryBalanceService;
import com.carddemo.batch.service.DailyFeedWatermarkService;
import com.carddemo.batch.service.PostingRecordUnitOfWork;
import com.carddemo.batch.service.PostingValidationService;
import com.carddemo.common.money.Money;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the account master's three durable rulings against a real engine, observed from outside.
 *
 * <h2>The three obligations this class owns</h2>
 *
 * <p>Purpose: {@code account.accounts} is written by exactly two reference paragraphs and read through
 * one key, and each of the three properties below is a statement about what an engine made durable
 * rather than about what a method computed. None of them can be established without a database, and
 * each is verified here from a connection that is not the one doing the writing.</p>
 *
 * <ul>
 *   <li><b>Obligation A -- the posting unit of work is ONE transaction across THREE schemas.</b>
 *       {@code app/cbl/CBTRN02C.cbl:424} opens {@code 2000-POST-TRANSACTION} and performs exactly three
 *       writes: {@code :440} the category balance, {@code :441} the account, {@code :442} the posted
 *       transaction. In the target the first and third land in {@code ledger} and the second in
 *       {@code account}, and the migration plan's section 0.4.1.3 records that split as the single
 *       documented exception to database-per-service purity, reached through the narrowly-scoped
 *       cross-schema write grants that {@code data-migration/sql/V0__schemas_and_roles.sql} declares.
 *       This class documents those grants and neither creates nor issues them. The migrated unit adds a
 *       FOURTH durable effect the reference has no counterpart for -- the feed's consumed position in
 *       {@code batch.daily_feed_watermark}, advanced once per record accounted for -- and it commits
 *       inside the same transaction as the three writes. That co-commit is the property that decides
 *       whether a re-run can double-post or skip a record, so it is asserted here beside them rather
 *       than left to the component's own unit test: a checkpoint that committed independently would
 *       either mark a rolled-back record consumed or re-present a committed one.</li>
 *   <li><b>Obligation B -- the account half of the cycle-bucket accumulation.</b>
 *       {@code app/cbl/CBTRN02C.cbl:545} is {@code 2800-UPDATE-ACCOUNT-REC}: {@code :547} advances the
 *       running balance unconditionally, {@code :548} tests the amount with an INCLUSIVE comparison,
 *       {@code :549} credits and {@code :551} debits, and {@code :554} rewrites the record. The account
 *       update at the interest control break, {@code app/cbl/CBACT04C.cbl:350}, additionally zeroes both
 *       cycle buckets at {@code :353} and {@code :354}.</li>
 *   <li><b>Obligation C -- the optimistic-lock ruling.</b> A step that loses the version race FAILS,
 *       so the orchestrator retries the step, and never re-reads and writes again behind its caller.</li>
 * </ul>
 *
 * <p>Trade-offs: the accumulation obligation is split BY TABLE rather than held whole in one class, and
 * the halves are named here so a reviewer can see both exist and neither is orphaned. This class owns
 * the ACCOUNT cycle buckets and the running balance;
 * {@code TransactionCategoryBalanceRepositoryIT} owns the CATEGORY-BALANCE half, its create-versus-update
 * arms and its ascending key-order walk. The alternative was one class asserting both tables, which
 * would have made a single file the owner of two schemas' accumulation rules and left neither table's
 * owner identifiable from its name.</p>
 *
 * <h2>The line this class does not cross</h2>
 *
 * <p>Assumptions: tier two's {@code PostTransactionsJobTest} proves that {@code PostTransactionsJob}
 * DECLARES one transaction boundary, which it establishes by construction from the job definition. This
 * class proves that the DATABASE observably committed all three writes or none of them. Those are
 * different assertions and only the second is evidence about an engine, so the graded return-code tier,
 * the two summary counter lines, the inversion of a baseline step gate into an orchestrator predicate
 * and job-level parity against the committed posting goldens are all left to that class and are not
 * restated here. No case below asserts a return code: a graded exit status is a process concern.</p>
 *
 * <p>Assumptions: {@code PostingUnitOfWorkIT} beside this file already holds the commit-and-rollback
 * pair read through a CLEARED PERSISTENCE CONTEXT on the writing connection, together with the ledger
 * write's refusal at the third write. What is added here is the property that reading through the
 * writer's own connection cannot reach: what a SEPARATE session sees while the unit is in flight, and
 * what survives a refusal arising at each of the three write positions rather than only at the last.
 * The category balance is cited as write one and the posted transaction as write three; their own
 * contracts belong to {@code TransactionCategoryBalanceRepositoryIT} and {@code TransactionRepositoryIT}
 * and are not re-asserted.</p>
 *
 * <h2>The seam these cases drive, and why it is the production one</h2>
 *
 * <p>Refactoring Rationale: every posting case below calls
 * {@code PostingRecordUnitOfWork.applyOneRecord} -- the production per-record unit -- inside a
 * transaction this class opens, and asserts what the engine then holds. It did NOT, and that is the
 * defect this arrangement replaces: the sequence was re-issued from a private helper of this file that
 * resolved the cross-reference, called the accumulation rule, applied the amount to the account and
 * inserted a hand-built posted row, so what the ordering, cycle-bucket and rollback cases pinned was
 * the order of a COPY. A production change that reordered the writes, dropped the checkpoint, applied
 * the amount to the wrong accumulator or inverted the sign test would have left every case here green,
 * and the copy additionally had no fourth write at all, so the checkpoint was unasserted anywhere
 * against a real engine.</p>
 *
 * <p>Trade-offs: driving the production unit gives up the ability to place a flush between two of its
 * writes, because the unit issues them without one and this class may not reach inside it. What that
 * costs, and how each of the three positions is still refused separately, is recorded under the
 * failure-position heading below. Alternatives Considered: driving the whole posting JOB instead, which
 * would need no seam at all. Rejected because a job run is a pass over a feed and would make every
 * case here a statement about a step's return code, its generation staging and its reject dataset --
 * which is tier two's subject and {@code PostTransactionsJobParityIT}'s -- rather than about what one
 * record's transaction made durable.</p>
 *
 * <p>Assumptions: one transition in this class is still applied by a helper rather than called from
 * production, and the exception is deliberate and narrow. The interest control break at
 * {@code app/cbl/CBACT04C.cbl:350} is transcribed inside {@code CalculateInterestJob}, whose per-account
 * update has no injectable component of its own; extracting one would be a change to a job this
 * checkpoint's finding does not reach. The case that asserts it therefore documents its transition as a
 * transcription and asserts the DURABLE CONSEQUENCE of the paragraph -- balance advanced, both cycle
 * accumulators reset -- while the posting transition beside it is production code.</p>
 *
 * <p>Assumptions: the reject arm's two writes are NOT participants in the accepted unit of work.
 * {@code app/cbl/CBTRN02C.cbl:211-216} branches to post OR to count and write a reject, never to both,
 * so a rejected record reaches {@code TransactionRejectRepositoryIT}'s table and never the three
 * asserted here. What the two arms DO share is the checkpoint: the production unit advances the
 * watermark on both, because a rejected record is consumed as surely as a posted one. Only the accepted
 * arm is driven below, the reject arm's row and its 430-byte stream record belonging to the reject
 * table's own owner in this package.</p>
 *
 * <p>Assumptions: tier one owns and this class does not restate the reject-reason precedence, the
 * verbatim reject literals, both inclusive VALIDATION boundaries, and ALL interest arithmetic. The
 * validation boundary at {@code app/cbl/CBTRN02C.cbl:407} and the accumulation boundary at {@code :548}
 * are two different inclusive comparisons in one program; only the second is asserted here. The accrued
 * interest is treated as an INPUT to the account update below and is never computed, so no case here
 * asserts a rounding mode -- the baseline divide truncates, no {@code ROUNDED} phrase appears anywhere
 * under {@code app/cbl}, and tier one's interest test owns that arithmetic.</p>
 *
 * <h2>How atomicity is observed, and why it has to be observed that way</h2>
 *
 * <p>Alternatives Considered: provoking a foreign-key violation between two of the written tables.
 * Unavailable rather than unattractive: the harness declares no inter-table foreign key, because the
 * owning migrations declare none either. Alternatives Considered: provoking a privilege error to show
 * the grant is scoped. Also unavailable: the harness creates no role and issues no {@code GRANT}, so
 * these cases connect as the container's own superuser and every table is writable at test time.
 * Atomicity is therefore proved by ROW VISIBILITY read from OUTSIDE the transaction under test, which is
 * the stronger statement in any case because it is about what the engine made durable rather than about
 * which constraint happened to fire.</p>
 *
 * <p>Alternatives Considered: reaching that outside view with a {@code JdbcTemplate} built over the
 * injected {@code DataSource}. Rejected on a mechanism rather than a preference, and this is the single
 * decision that makes or breaks every case below. Inside a {@code TransactionTemplate} boundary Spring
 * binds the transactional connection to the thread, and a template over the same {@code DataSource}
 * resolves to THAT connection -- so it would read the transaction's own uncommitted snapshot, see all
 * three writes, and report a passing observation that proves nothing whatever about durability. The
 * observer therefore takes a connection straight from the pool with {@code DataSource#getConnection},
 * which is a genuinely separate session, and rolls it back before closing it.</p>
 *
 * <p>Assumptions: that second connection is already available and no mechanism needs inventing to obtain
 * one. {@code src/test/resources/application-test.yml} sets the pool maximum to four and turns
 * connection auto-commit off, which is exactly what an outside observer and a version race both
 * require; a pool of one would deadlock the observation rather than fail it.</p>
 *
 * <h2>How each write position is refused, now that the unit issues its own writes</h2>
 *
 * <p>Alternatives Considered: forcing the refusal at one write position only, as a single rollback case
 * would. Rejected because the claim being defended is about ORDERING -- three writes in a fixed sequence
 * under one commit -- so a refusal at the last position leaves the two earlier positions unproven, and
 * an implementation that committed after the first write would still satisfy a last-position case. Each
 * of the three positions is refused separately below, and every refusal is raised on the production code
 * path -- the domain type's own picture guard, the shared money domain, the provider's identifier
 * requirement or its version check -- rather than by a manufactured exception or a rollback-only flag,
 * either of which would merely show that the framework can roll a transaction back.</p>
 *
 * <p>Assumptions: the production unit issues its three writes through repository {@code save} calls and
 * flushes none of them, so a refusal cannot be attributed to a position by watching WHICH statement the
 * engine rejects -- at commit the provider emits the queued statements in its own order. Attribution
 * comes from WHERE the refusal arises in the call sequence instead, and that is what makes the choice of
 * mechanism at each position load-bearing rather than incidental:</p>
 *
 * <ul>
 *   <li><b>Position one, the category balance.</b> The accumulated total is pushed one cent past
 *       {@link #CATEGORY_BALANCE_CEILING}, and the category-balance entity's own nine-integer-digit
 *       picture refuses it IN MEMORY, inside the accumulation rule. The unit therefore aborts before it
 *       reads the account at all, so the account and the ledger being untouched is a statement about
 *       ordering as well as about rollback.</li>
 *   <li><b>Position two, the account.</b> The seeded balance sits at {@link #ACCOUNT_BALANCE_CEILING}
 *       and the posted amount carries it past the shared money domain, which refuses it IN MEMORY
 *       inside the account transition -- after the category balance has been accumulated and before the
 *       posted row is built. This exploits a second real asymmetry: the validation rule projects a
 *       balance from the CYCLE accumulators rather than from the running balance, so an account may sit
 *       at the domain ceiling and still be accepted for posting.</li>
 *   <li><b>Position three, the posted ledger row.</b> The feed row carries NO transaction identifier,
 *       which {@code ledger.daily_transactions} admits and {@code ledger.transactions} refuses -- the
 *       one nullability asymmetry the two tables declare, and the harness records why the feed has it.
 *       The provider refuses the insert for want of an assigned identifier, at the third write, after
 *       the first two have already been issued.</li>
 *   <li><b>Position two again, from the engine rather than from memory.</b> The version race supplies
 *       the one refusal that arises at the COMMIT flush, so its case is the one in which the earlier
 *       writes genuinely reached the server before the unit failed. The two mechanisms at this position
 *       are complementary: the in-memory one proves the account is written before the ledger, and the
 *       flush-time one proves that statements the engine has already accepted are rolled back with
 *       it.</li>
 * </ul>
 *
 * <p>Assumptions: the in-flight case flushes the unit's writes EXPLICITLY, once, after the production
 * call returns and before the observation. That is the one place a flush is added, and it is
 * load-bearing there: an unflushed row is not at the server at all, so its invisibility to another
 * session would be vacuous. It is added after the unit rather than between its writes, which is all this
 * class can do without reaching inside the component, and it is enough -- the property under test is
 * that NONE of the four effects is visible before the commit.</p>
 *
 * <p>Alternatives Considered: a transactional outbox with compensating reversals, or a distributed
 * two-phase commit across the two schemas. Both were evaluated and rejected at the migration plan's
 * section 0.4.1.3, because each replaces one atomic commit with a sequence of individually committed
 * steps and therefore makes an intermediate state observable -- a posted transaction beside an unposted
 * balance -- which the baseline does not have and which the committed goldens would correctly report as
 * a parity failure. The in-flight case below is what makes that rejection checkable rather than merely
 * asserted: under either rejected design the earlier writes would already be visible to another session,
 * so that case would fail. There is one transaction, one commit, no reversal row and no second
 * datasource.</p>
 *
 * <p>Assumptions: a step-ledger row is deliberately NOT one of the three writes and is not asserted
 * here. The step ledger declares its own independent boundary precisely so a failed row survives the
 * rollback of the step it records, so a rollback case must expect the three writes to vanish while such
 * a row remains.</p>
 *
 * <p>Assumptions: no assertion description, message or diagnostic in this class emits a whole row or a
 * full card number. The unit of work writes a sixteen-character card number into the posted row and
 * reads the account key through a cross-reference whose own key IS a card number, and the migration
 * plan's section 0.7.8 masks primary account numbers to their last four digits. A failing three-table
 * visibility assertion is exactly the place an unmasked fixture value would otherwise reach a build log,
 * so the card number is referred to by its last four digits and the observer returns counts and single
 * columns rather than rows.</p>
 *
 * <p>Assumptions: money is compared with {@code compareTo} through AssertJ's
 * {@code isEqualByComparingTo} and never with {@code equals}, because equality on {@code BigDecimal} is
 * scale-sensitive and would report a stored {@code 697.70} as unequal to an expected {@code 697.7}
 * while both denote the same amount. Every money value is {@code BigDecimal} at scale two, matching the
 * {@code NUMERIC} scale the columns declare, and arithmetic goes through
 * {@code com.carddemo.common.money.Money}; binary floating point is forbidden on this path and
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * fails the build on a violation.</p>
 *
 * <p>Assumptions: this class writes ROWS only, into {@code account.accounts},
 * {@code account.card_xref}, {@code ledger.daily_transactions},
 * {@code ledger.transaction_category_balances}, {@code ledger.transactions} and
 * {@code batch.daily_feed_watermark}. It creates, alters and seeds no STRUCTURE in any schema: that data
 * definition belongs to {@code account-service}'s {@code V1__account.sql} and
 * {@code transaction-service}'s {@code V1__ledger.sql} for the five foreign tables, whose test-time
 * shape the harness script this container runs supplies, and to this module's OWN
 * {@code db/migration/V2__batch_feed_watermark.sql} for the watermark, which Flyway applies against the
 * container because the test profile leaves it enabled. Every path under {@code app/} it cites is
 * reference material, read as the specification and never modified, as are the oracle suite under
 * {@code tests/} and the runners under {@code scripts/}.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own. The written convention
 * each block follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.</p>
 */
@Testcontainers
// Refactoring Rationale: the Parameter Store config-data location is disabled for this context,
//     because application.yml's `optional:aws-parameterstore:` location builds an SSM client while
//     configuration is still loading and a build host with no AWS_REGION aborts the context on the
//     unresolved placeholder. BatchRunRepositoryIT records the full measurement, why disabling the
//     LOAD is sufficient when the resolver ignores the flag, and the two rejected alternatives; it is
//     cited from here rather than repeated.
@SpringBootTest(
        classes = AccountRepositoryIT.AccountPersistenceTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.cloud.aws.parameterstore.enabled=false")
@ActiveProfiles("test")
// Alternatives Considered: a class-level @Transactional with rollback-after-test isolation, which the
//     unit-test tiers use and which one sibling in this package uses. It is ACTIVELY WRONG here rather
//     than merely unnecessary: this class has to observe commit and rollback from OUTSIDE the
//     transaction, and a test-managed enclosing transaction would make every write invisible to the
//     observer for a reason unrelated to the code under test, while making every provoked rollback
//     indistinguishable from the harness's own. Rows are removed explicitly before each case instead.
class AccountRepositoryIT {

    /**
     * The engine image, named by manifest digest: PostgreSQL 17.10 on Alpine.
     *
     * <p>Assumptions: the digest is the one every sibling integration test in this build pins, so one
     * engine serves the whole suite and two classes cannot disagree about one schema. The version is
     * recorded in prose because a digest states nothing a reader recognises.</p>
     */
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193";

    /**
     * The classpath-relative path of the harness that supplies the two foreign schemas.
     *
     * <p>Assumptions: this class REQUIRES the script rather than merely benefiting from it. Its writes
     * land in {@code account.accounts}, {@code ledger.transaction_category_balances} and
     * {@code ledger.transactions}, none of which this module's own migration creates or may create, so
     * without the script every case here would fail on an undefined table.</p>
     */
    private static final String HARNESS_SCRIPT =
            "db/testharness/test-harness-schemas-and-foreign-tables.sql";

    /**
     * The account every case writes against.
     *
     * <p>Assumptions: an eleven-digit value, the width {@code ACCT-ID PIC 9(11)} declares at
     * {@code app/cpy/CVACT01Y.cpy}. It is fabricated and identifies nothing.</p>
     */
    private static final long ACCOUNT_ID = 10000000077L;

    /**
     * The card number the cross-reference resolves to that account.
     *
     * <p>Assumptions: sixteen characters, the width {@code XREF-CARD-NUM PIC X(16)} declares at
     * {@code app/cpy/CVACT03Y.cpy}. Two properties together make it unroutable, and both are COMPUTED
     * by {@link #theCardFixtureIsUnroutableAndFailsTheLuhnCheck} rather than merely attested here: it
     * begins {@code 9900}, whose leading major industry identifier 9 is reserved for national use and
     * assigned to no card issuer, so the range routes to nobody; and its final digit is deliberately not
     * the Luhn check digit, so it fails the checksum every network applies before a number reaches an
     * issuer at all. It is referred to in every message below by {@link #CARD_LAST_FOUR} rather than in
     * full.</p>
     *
     * <p>Refactoring Rationale: the value was {@code 4000000000000077} and this block attested that it
     * "fails the Luhn check". The attestation was false -- its Luhn contribution sum is 20, a multiple
     * of ten, so the value is checksum-VALID, and a leading 4 sits in an allocated issuer range -- which
     * made it a routable-looking primary account number committed under a safety claim a reviewer would
     * have trusted without recomputing. It is replaced rather than re-documented, under the convention
     * this repository already applies to fabricated card numbers in
     * {@code services/reporting-service/src/test/java/com/carddemo/reporting/fixtures/ReportingFixtureContractTest.java}:
     * the {@code 9900} prefix plus a deliberately wrong check digit. Alternatives Considered: keeping
     * the value and correcting only the sentence, which is cheaper. Rejected because the number would
     * still be checksum-valid and issuer-shaped, so the fixture would still be the thing a scanner or a
     * reviewer flags; and Alternatives Considered: a documented network test credential, rejected
     * because those are Luhn-VALID by design and this file's whole claim is that its value cannot be
     * one.</p>
     */
    private static final String CARD_NUM = "9900000000000078";

    /**
     * The masked rendering of the card number, used wherever a message names the card at all.
     *
     * <p>Assumptions: the last four digits are the whole of what the migration plan's section 0.7.8
     * permits a diagnostic to carry. A failing visibility assertion prints its description, so the
     * masked form is the only form this class holds in a message.</p>
     */
    private static final String CARD_LAST_FOUR = "0078";

    /**
     * The prefix that makes the card fixture unroutable, asserted rather than assumed.
     *
     * <p>Assumptions: ISO/IEC 7812 assigns major industry identifier 9 to national use and no card
     * network issues under it, so a number beginning with this prefix belongs to no issuer range. It is
     * named as a constant because the case that checks it must compare against the same value this
     * block documents, and a literal in the assertion could drift from the prose.</p>
     */
    private static final String UNROUTABLE_PREFIX = "9900";

    /** The customer the cross-reference names, fabricated at the declared nine-digit width. */
    private static final long CUSTOMER_ID = 900000077L;

    /**
     * The orchestrator execution identifier every checkpoint in this class is attributed to.
     *
     * <p>Assumptions: it is carried verbatim into {@code batch.daily_feed_watermark.run_id}, whose
     * column is {@code VARCHAR(80)} and whose CHECK constraint refuses a blank, so the value is a short
     * non-blank literal. It names WHICH RUN moved the position for an operator reading the row and
     * reaches no posted or rejected record.</p>
     */
    private static final String RUN_ID = "account-repository-it";

    /**
     * The injected business date every unit of work in this class is driven with.
     *
     * <p>Assumptions: a literal date rather than a clock read, for the reason the reference itself
     * gives -- {@code app/jcl/INTCALC.jcl:22} injects the business date as a job parameter so a re-run
     * reproduces its inputs. The separated ISO layout is used because it is the one the migrated chain
     * injects, and it is exactly ten characters, which the watermark's own CHECK constraint requires.</p>
     */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate("2022-07-18");

    /** The transaction type every posting in this class carries. */
    private static final String TYPE_CD = "01";

    /** The transaction category every posting in this class carries. */
    private static final String CATEGORY_CD = "0001";

    /** The identifier of the transaction an accepted unit of work posts. */
    private static final String TRANSACTION_ID = "0000000000000077";

    /**
     * The identifier of a SECOND accepted transaction, posted by the case that needs two commits.
     *
     * <p>Assumptions: it differs from {@link #TRANSACTION_ID} because {@code ledger.transactions} keys
     * on the identifier, so two units posting the same one would update a single row rather than commit
     * two rewrites of the account -- which is precisely what the version case has to distinguish.</p>
     */
    private static final String SECOND_TRANSACTION_ID = "0000000000000088";

    /**
     * The amount the accepted posting carries, taken from the committed parity vector.
     *
     * <p>Assumptions: {@code tests/fixtures/posting/happy_path/dailytran.txt} carries the zoned amount
     * {@code 0000005047G}, which decodes to this value under the sign overpunch, and
     * {@code tests/golden/posting/happy_path/acctdat.expected} carries the resulting balance. Using the
     * shipped vector rather than a round number keeps the arithmetic below checkable against a file.</p>
     */
    private static final BigDecimal POSTED_AMOUNT = new BigDecimal("504.77");

    /**
     * The account balance the posting parity fixture starts from.
     *
     * <p>Assumptions: {@code tests/fixtures/posting/happy_path/acctdata.txt} holds
     * {@code 00000001930} closed by the positive-zero sign overpunch, which decodes to this value.</p>
     */
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("193.00");

    /**
     * The account balance the posting parity golden ends at.
     *
     * <p>Assumptions: {@code tests/golden/posting/happy_path/acctdat.expected} holds
     * {@code 00000006977G}, which decodes to this value, and it equals {@link #OPENING_BALANCE} plus
     * {@link #POSTED_AMOUNT} exactly. Both sides are named so the sum is verifiable rather than
     * computed here from one of them.</p>
     */
    private static final BigDecimal POSTED_BALANCE = new BigDecimal("697.77");

    /**
     * The credit limit the parity fixture declares, comfortably above the posted amount.
     *
     * <p>Assumptions: {@code 00000020650} closed by the positive-zero sign overpunch, in the same fixture row. It is carried so the seeded account
     * is one the reference would have accepted rather than rejected, which keeps a failure here
     * attributable to durability instead of to a rejectable fixture.</p>
     */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("2065.00");

    /** The cash credit limit the seeded account carries, unread by any rule this class exercises. */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("500.00");

    /**
     * The expiration date the parity fixture declares, under the CORRECTED column name.
     *
     * <p>Refactoring Rationale: the baseline field is spelled {@code ACCT-EXPIRAION-DATE} in
     * {@code app/cpy/CVACT01Y.cpy} -- a misspelling in the immutable source -- and the target column is
     * {@code expiration_date}. It is one of three corrections the migration plan records in
     * {@code docs/architecture/data-model-and-schema-mapping.md}, and it is named here so a reader
     * comparing the two sides does not read the difference as a transcription error in this file. The
     * value {@code 2024-12-13} is the fixture's own, held as a date rather than as ten characters
     * because the column is {@code DATE}.</p>
     */
    private static final LocalDate EXPIRATION_DATE = LocalDate.of(2024, 12, 13);

    /** The open date the seeded account carries, well inside the reference's own range. */
    private static final LocalDate OPEN_DATE = LocalDate.of(2014, 11, 20);

    /** The reissue date the seeded account carries, unread by any rule this class exercises. */
    private static final LocalDate REISSUE_DATE = LocalDate.of(2024, 5, 20);

    /**
     * The postal code the seeded account carries, at the declared ten-character width.
     *
     * <p>Assumptions: {@code ACCT-ADDR-ZIP PIC X(10)} in {@code app/cpy/CVACT01Y.cpy} and
     * {@code addr_zip CHAR(10)} in the harness, so the value is blank-padded to ten. No case below
     * compares this column: a fixed-length column compares ignoring trailing blanks where a varying one
     * does not, so an assertion on it would be a statement about the column type rather than about the
     * account rule, and the type is the harness's to declare.</p>
     */
    private static final String ADDR_ZIP = "12345     ";

    /**
     * The disclosure-group key the seeded account carries, blank-padded to its declared width.
     *
     * <p>Assumptions: {@code group_id} is {@code CHAR(10)} and the reference's fallback group is the
     * seven-character {@code DEFAULT}, so the stored key is blank-padded to {@code 'DEFAULT   '}. The
     * padding matters because it is the join key into {@code reference.disclosure_groups}, whose lookup
     * and whose seeded rows under exactly this padded key belong to the disclosure-group owner in this
     * package rather than to this class; it is carried so the seeded row is joinable.</p>
     */
    private static final String GROUP_ID = "DEFAULT   ";

    /** The active status the seeded account carries, one of the two the harness CHECK constraint admits. */
    private static final String ACTIVE_STATUS = "Y";

    /**
     * The instant every posted row in this class is stamped with.
     *
     * <p>Assumptions: a literal, never a clock read. {@code app/cbl/CBTRN02C.cbl:438} stamps the moment
     * of posting and the oracle suite normalises that field before comparing goldens precisely because
     * it is the one non-deterministic value a posted record carries, so a case reading the clock would
     * inherit the non-determinism the normalisation exists to remove.</p>
     */
    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2022, 7, 18, 2, 5, 30);

    /**
     * The originating instant a posted row carries, earlier than the processing stamp.
     *
     * <p>Assumptions: a literal for the same reason the processing stamp is one. It is earlier because
     * a transaction originates before it is posted, and an order inverted here would be a fixture that
     * could not have arisen.</p>
     */
    private static final LocalDateTime ORIGINATED_AT = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /**
     * The description an accepted posting carries, comfortably inside its declared width.
     *
     * <p>Assumptions: {@code DALYTRAN-DESC} is {@code PIC X(100)} in {@code app/cpy/CVTRA06Y.cpy} and
     * the owning migration declares {@code description VARCHAR(100)}, so any value at or below one
     * hundred characters is admitted.</p>
     */
    private static final String DESCRIPTION = "Account repository unit of work fixture";

    /**
     * The source channel every seeded feed record carries, blank-padded to its declared width.
     *
     * <p>Assumptions: {@code DALYTRAN-SOURCE PIC X(10)} in {@code app/cpy/CVTRA06Y.cpy} and
     * {@code source CHAR(10)} in both the feed and the posted table, so the value is padded to ten. It
     * is carried through the production mapper unaltered and no case asserts it: a fixed-length column
     * compares ignoring trailing blanks, so an assertion on it would report on the column type rather
     * than on the unit of work.</p>
     */
    private static final String FEED_SOURCE = "POS       ";

    /** The merchant identifier every seeded feed record carries, fabricated and identifying nothing. */
    private static final long MERCHANT_ID = 900000001L;

    /** The merchant name every seeded feed record carries, inside the fifty characters declared. */
    private static final String MERCHANT_NAME = "Fixture Merchant";

    /** The merchant city every seeded feed record carries, inside the fifty characters declared. */
    private static final String MERCHANT_CITY = "Fixture City";

    /** The merchant postal code every seeded feed record carries, padded to the ten declared. */
    private static final String MERCHANT_ZIP = "12345     ";

    /**
     * The largest balance the category-balance column can hold, used to provoke the write-one refusal.
     *
     * <p>Assumptions: this exploits a real and non-obvious asymmetry between two contracts rather than
     * an artificial value. {@code TRAN-CAT-BAL} is {@code PIC S9(09)V99} in
     * {@code app/cpy/CVTRA01Y.cpy}, so the column is {@code NUMERIC(11,2)} and admits nine integer
     * digits, whereas {@code ACCT-CURR-BAL} is {@code PIC S9(10)V99} and the shared money type's own
     * domain is sized to that WIDER ten-digit picture. A sum one cent past this value is therefore a
     * legal {@code Money} that the narrower column refuses, which is what lets the first write be
     * refused by the engine without a manufactured exception.</p>
     */
    private static final BigDecimal CATEGORY_BALANCE_CEILING = new BigDecimal("999999999.99");

    /**
     * The largest running balance the shared money domain admits, used to provoke the write-two refusal.
     *
     * <p>Assumptions: {@code ACCT-CURR-BAL} is {@code PIC S9(10)V99} in {@code app/cpy/CVACT01Y.cpy},
     * the column is {@code NUMERIC(12,2)} and the shared money type's domain is sized to exactly that
     * ten-integer-digit picture, so this value is the last one both admit. Adding any positive amount to
     * it produces an eleven-digit total that {@code Money} refuses in memory, inside the account
     * transition of the production unit.</p>
     *
     * <p>Assumptions: an account seeded here is still ACCEPTED by the validation rule, which is what
     * makes the arrangement reachable at all rather than a rejected record. The over-limit projection at
     * {@code app/cbl/CBTRN02C.cbl:403-405} is the cycle credit MINUS the cycle debit plus the amount --
     * the running balance is not in it -- so an account at this ceiling with both accumulators at zero
     * projects the amount alone against its credit limit and passes.</p>
     */
    private static final BigDecimal ACCOUNT_BALANCE_CEILING = new BigDecimal("9999999999.99");

    /**
     * The interest the control-break case adds to the account balance.
     *
     * <p>Assumptions: taken from the committed accrual vector rather than computed here.
     * {@code tests/fixtures/interest/happy_path/acctdata.txt} holds {@code 00000001940} closed by the positive-zero sign overpunch for the first
     * account and {@code tests/golden/interest/happy_path/acctdat.expected} holds
     * {@code 00000002065} closed by the same overpunch, a rise of exactly this amount. Tier one owns the arithmetic that produces
     * it, so it enters the account update below as an input.</p>
     */
    private static final BigDecimal ACCRUED_INTEREST = new BigDecimal("12.50");

    /** The account balance the accrual fixture starts from, decoded from the committed fixture. */
    private static final BigDecimal INTEREST_OPENING_BALANCE = new BigDecimal("194.00");

    /** The account balance the accrual golden ends at, decoded from the committed golden. */
    private static final BigDecimal INTEREST_CLOSING_BALANCE = new BigDecimal("206.50");

    /**
     * The container every assertion in this class runs against, started once for the class.
     *
     * <p>Assumptions: the type comes from {@code org.testcontainers.postgresql} rather than the
     * deprecated {@code org.testcontainers.containers} package and carries no type argument, the
     * replacement not being generic. Assumptions: the container is this class's own rather than shared
     * through a base class, so rows one class inserts are never rows another reads and any one class in
     * this package can be run alone and still mean something.</p>
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE).withInitScript(HARNESS_SCRIPT);

    /** The account master under test, injected as the production repository interface. */
    @Autowired
    private AccountRepository accounts;

    /** The posted ledger, written as the third of the three writes. */
    @Autowired
    private TransactionRepository ledger;

    /** The cross-reference the account key is resolved through. */
    @Autowired
    private CardXrefRepository crossReferences;

    /** The category balances, written as the first of the three writes. */
    @Autowired
    private TransactionCategoryBalanceRepository categoryBalances;

    /**
     * The production accumulation rule, used here only to ARRANGE the row a refused unit updates.
     *
     * <p>Assumptions: the rule is reached directly for one purpose -- seeding the category balance at
     * its ceiling -- so that the row a later refused unit meets is one the rule itself created and the
     * refusal arises on its own update arm. Every posting case drives the rule through
     * {@link #perRecord} instead, which is the production caller. Assumptions: the service declares no
     * transaction of its own, so it runs inside whichever boundary its caller opened; the one production
     * service in this module that does declare one is the step ledger's writer, which is deliberately
     * {@code REQUIRES_NEW} so a failed step's row survives the rollback of the step it records.</p>
     */
    @Autowired
    private CategoryBalanceService balances;

    /**
     * The unposted feed, read exactly as the production job reads it.
     *
     * <p>Assumptions: the feed record handed to the production unit is READ THROUGH THIS INTERFACE
     * rather than constructed, and that is a requirement rather than a preference. The unit checkpoints
     * the record's own ingestion ordinal, and that column is
     * {@code BIGINT GENERATED BY DEFAULT AS IDENTITY} whose value the engine assigns -- so a constructed
     * instance carries none, and the checkpoint would fail on an absent ordinal rather than on anything
     * this class is asserting.</p>
     */
    @Autowired
    private DailyTransactionRepository feed;

    /**
     * The production per-record unit of work, which every posting case below drives.
     *
     * <p>Assumptions: it is the REAL component over the real repositories, injected as the production
     * job injects it, and it declares no transaction of its own -- the boundary belongs to its caller,
     * which is this class standing in for the tasklet step. That is what lets a case observe the unit in
     * flight and roll it back.</p>
     */
    @Autowired
    private PostingRecordUnitOfWork perRecord;

    /** The persistence context, used to flush inside a boundary and to detach after one. */
    @Autowired
    private EntityManager entityManager;

    /** The boundary every unit of work below is issued inside, standing in for the tasklet step. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * The pool both the writing session and the observing session draw from.
     *
     * <p>Assumptions: held as a field rather than taken as a setup parameter because the observer needs
     * it inside a case, not only during setup. It is the injected pool and not a second one: a second
     * datasource would be a different engine session pool and would prove nothing about the
     * single-datasource claim this class exists to check.</p>
     */
    @Autowired
    private DataSource dataSource;

    /** A plain handle used only to empty the three written tables between cases. */
    private JdbcTemplate jdbc;

    /**
     * Registers the running container's coordinates as configuration properties.
     *
     * @param registry the Spring test property registry this method adds the container's JDBC URL, user
     *     name and credential to as deferred suppliers; must not be {@code null}
     */
    // Assumptions: the Flyway pair is registered beside the datasource pair because application.yml
    //     binds spring.flyway.user and spring.flyway.password to placeholders with no fallback, and
    //     Boot consults those keys precisely when no connection-details bean supplies them -- which is
    //     this module's case, the artifact contributing that bean being deliberately absent from its
    //     POM. No connection literal appears here or in the test profile: a container assigns its host
    //     port as it starts, so a literal authored beforehand would either address nothing or, worse
    //     because it would pass, address whatever database happened to be listening.
    @DynamicPropertySource
    static void registerContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * Empties every written table and commits one seeded account and its cross-reference.
     *
     * <p>Assumptions: the seed is COMMITTED before each case rather than shared across the class or
     * held open in a transaction, so a case that provokes a rollback still starts from a known balance
     * and the observing session can see the starting row at all. Deleting explicitly is the isolation
     * strategy this class uses in place of transactional rollback, for the reason recorded on the class
     * declaration.</p>
     *
     * <p>Assumptions: the delete order is not a foreign-key order, there being no inter-table foreign
     * key in the harness; it is simply every table a case writes plus the cross-reference each one
     * resolves through. Assumptions: the watermark is emptied too, and that is load-bearing rather than
     * tidy -- the production unit advances a position MONOTONICALLY and treats a request at or below the
     * stored one as a no-op, so a row surviving from an earlier case would make the next case's
     * checkpoint silently do nothing and its watermark assertion report the earlier case's ordinal.</p>
     *
     * <p>This setup method takes no parameter and returns no value.</p>
     */
    @BeforeEach
    void resetAndSeed() {
        this.jdbc = new JdbcTemplate(this.dataSource);
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM ledger.transactions");
            this.jdbc.update("DELETE FROM ledger.transaction_category_balances");
            this.jdbc.update("DELETE FROM ledger.transaction_rejects");
            this.jdbc.update("DELETE FROM ledger.daily_transactions");
            this.jdbc.update("DELETE FROM account.accounts");
            this.jdbc.update("DELETE FROM account.card_xref");
            this.jdbc.update("DELETE FROM batch.daily_feed_watermark");
        });
        this.transactionTemplate.executeWithoutResult(status -> {
            this.accounts.save(seedAccount(OPENING_BALANCE, BigDecimal.ZERO, BigDecimal.ZERO));
            // Assumptions: the cross-reference is written through the persistence context and NOT
            //     through its repository, because that interface deliberately extends the bare marker
            //     base rather than a CRUD one and so declares no write method at all -- this module
            //     only ever READS the cross-reference. Persisting the seed directly is what lets the
            //     read path this class exercises stay read-only.
            this.entityManager.persist(new CardXref(CARD_NUM, CUSTOMER_ID, ACCOUNT_ID));
            this.entityManager.flush();
            this.entityManager.clear();
        });
    }

    /**
     * Confirms all four durable effects become visible to another session together, on the commit.
     *
     * <p>Purpose: this is obligation A read from the committed side. It pins
     * {@code app/cbl/CBTRN02C.cbl:440-442} -- the category balance, the account and the posted
     * transaction -- together with the migrated unit's checkpoint as ONE durable outcome spanning
     * {@code ledger}, {@code account} and {@code batch}, and the balance it asserts is the committed
     * parity vector's: {@code 193.00} advancing to {@code 697.77} on an amount of {@code 504.77}, from
     * {@code tests/fixtures/posting/happy_path/acctdata.txt} to
     * {@code tests/golden/posting/happy_path/acctdat.expected}.</p>
     *
     * <p>Assumptions: the reading is taken through the OUTSIDE observer rather than through the writing
     * session's own repositories, which distinguishes this case from the sibling that reads the same
     * commit through a cleared persistence context. A commit is only durable if a session that never
     * participated in it can see it.</p>
     *
     * <p>Assumptions: the checkpoint is asserted at the record's OWN ingestion ordinal rather than at a
     * literal, and the ordinal comes back from the seeding statement. The identity sequence behind the
     * feed is not reset between cases, so a literal would pass only on whichever case happened to run
     * first, and the value the checkpoint must carry is exactly the one the next pass compares against
     * when it resumes.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all four durable effects become visible to another session together on the commit")
    void allFourDurableEffectsBecomeVisibleTogetherOnCommit() {
        long consumed = postAccepted(TRANSACTION_ID, POSTED_AMOUNT);

        ObservedUnit committed = observeFromOutsideTheTransaction();

        assertThat(committed.categoryBalanceRows())
                .as("write one, the category balance, is durable in the ledger schema")
                .isEqualTo(1L);
        assertThat(committed.ledgerRows())
                .as("write three, the posted transaction, is durable in the ledger schema")
                .isEqualTo(1L);
        assertThat(committed.balance())
                .as("write two is durable in the ACCOUNT schema, in the same unit of work")
                .isEqualByComparingTo(POSTED_BALANCE);
        assertThat(committed.cycleCredit())
                .as("the positive amount reached the cycle credit accumulator")
                .isEqualByComparingTo(POSTED_AMOUNT);
        assertThat(committed.cycleDebit())
                .as("the cycle debit accumulator is untouched by a positive amount")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(committed.watermark().ordinal())
                .as("the checkpoint is durable in the BATCH schema, in the same unit of work, at the"
                        + " record's own ingestion ordinal")
                .isEqualTo(consumed);
        assertThat(committed.watermark().runId())
                .as("the advance names the run that made it, so an operator reading a recovered night"
                        + " can attribute the position")
                .isEqualTo(RUN_ID);
        assertThat(committed.watermark().businessDate())
                .as("and the injected business date rather than a clock read, so a re-run reproduces"
                        + " the attribution")
                .isEqualTo(BUSINESS_DATE.token());
    }

    /**
     * Confirms the four flushed writes stay invisible to another session until the commit.
     *
     * <p>Purpose: this is the load-bearing case of the class and the one that distinguishes ONE
     * transaction from three. All three writes of {@code app/cbl/CBTRN02C.cbl:440-442} are issued and
     * flushed, so all three statements have reached the engine and are held in the writing
     * transaction's own snapshot; a session that is not that transaction must still see none of them.
     * An implementation that committed after each write -- which is what a compensating-reversal
     * sequence or a distributed two-phase commit would amount to across the schema boundary -- would
     * make the earlier rows visible at this point and would fail here. That is what makes the
     * rejection recorded on the class declaration checkable rather than merely asserted.</p>
     *
     * <p>Assumptions: the account row is asserted still to carry its SEEDED balance rather than merely
     * asserted absent, because the account row exists throughout -- it is updated, not inserted -- so
     * absence is not available as evidence for it and only the unchanged value distinguishes an
     * uncommitted update from a committed one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the four flushed writes stay invisible to another session until the commit")
    void theFourFlushedWritesStayInvisibleToAnotherSessionUntilCommit() {
        ObservedUnit inFlight = postAndObserveBeforeCommit(TRANSACTION_ID, POSTED_AMOUNT);

        assertThat(inFlight.categoryBalanceRows())
                .as("write one is flushed but uncommitted, so another session sees no category balance")
                .isZero();
        assertThat(inFlight.ledgerRows())
                .as("write three is flushed but uncommitted, so another session sees no posted row")
                .isZero();
        assertThat(inFlight.balance())
                .as("write two is flushed but uncommitted, so another session still reads the seeded"
                        + " balance rather than the advanced one")
                .isEqualByComparingTo(OPENING_BALANCE);
        assertThat(inFlight.cycleCredit())
                .as("the uncommitted cycle credit is invisible to the observing session too")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(inFlight.watermark().rows())
                .as("the checkpoint is flushed but uncommitted, so another session finds no consumed"
                        + " position at all -- a resume starting here would re-present the record")
                .isZero();

        ObservedUnit committed = observeFromOutsideTheTransaction();
        assertThat(committed.categoryBalanceRows())
                .as("the same observation after the commit finds the row, so the earlier zero was"
                        + " isolation and not an arrangement that never wrote anything")
                .isEqualTo(1L);
        assertThat(committed.balance()).isEqualByComparingTo(POSTED_BALANCE);
        assertThat(committed.watermark().rows())
                .as("and finds the checkpoint, so its earlier absence was isolation too")
                .isEqualTo(1L);
    }

    /**
     * Confirms a refusal at the FIRST write leaves nothing in either schema.
     *
     * <p>Purpose: the first of the three failure positions. The unit is refused a sum one cent past
     * {@link #CATEGORY_BALANCE_CEILING}, which is the contract asymmetry recorded on that constant:
     * {@code app/cpy/CVTRA01Y.cpy} declares nine integer digits where {@code app/cpy/CVACT01Y.cpy}
     * declares ten. Because the refusal arises at {@code app/cbl/CBTRN02C.cbl:440}, no later write is
     * attempted at all, and the assertion is that the account and the ledger are untouched rather than
     * merely that the balance did not advance.</p>
     *
     * <p>Assumptions: the refusal comes from the category-balance entity's OWN picture guard rather than
     * from the engine's column, and the distinction is recorded because it was measured rather than
     * assumed. The narrower picture is defended twice -- by the domain type, which reduces every balance
     * through the nine-integer-digit picture, and by the {@code NUMERIC(11,2)} column behind it -- and
     * the domain guard is reached first, so the statement never leaves the application. Either way the
     * unit aborts at write one, which is the position this case exists to refuse; what would have been
     * illegitimate is a manufactured exception thrown by the test, and this is the production entity
     * refusing a value it is contracted to refuse.</p>
     *
     * <p>Assumptions: the pre-existing category balance is asserted to be unchanged at the ceiling. The
     * refused unit took the UPDATE arm of the accumulation, so the row it would have overwritten is
     * present both before and after, and only its value can report whether the rollback happened.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refusal at the category balance write leaves nothing in any schema")
    void aRefusalAtTheCategoryBalanceWriteLeavesNothingInAnySchema() {
        seedCategoryBalanceAtItsCeiling();

        assertThatThrownBy(() -> postAccepted(TRANSACTION_ID, new BigDecimal("1.00")))
                .as("the accumulated balance is refused past the nine integer digits its picture admits")
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("9-integer-digit picture domain");

        ObservedUnit afterRefusal = observeFromOutsideTheTransaction();

        assertThat(afterRefusal.balance())
                .as("the account write was never reached, so the balance is exactly as seeded")
                .isEqualByComparingTo(OPENING_BALANCE);
        assertThat(afterRefusal.ledgerRows())
                .as("the ledger write was never reached, so no posted transaction exists")
                .isZero();
        assertThat(afterRefusal.watermark().rows())
                .as("and the checkpoint was never reached either, so the record stays unconsumed and the"
                        + " next pass re-presents it whole rather than skipping it")
                .isZero();
        assertThat(storedCategoryBalance().orElseThrow().getBalance())
                .as("the refused accumulation was rolled back, so the row still holds its ceiling value")
                .isEqualByComparingTo(CATEGORY_BALANCE_CEILING);
    }

    /**
     * Confirms a refusal at the THIRD write rolls back the earlier writes, seen from another session.
     *
     * <p>Purpose: the third of the three failure positions, and the one in which a non-atomic
     * implementation would leave the two earlier rows behind -- an advanced balance with no posted
     * transaction, which is precisely the observable intermediate state the migration plan rejected a
     * compensating-reversal design in order to avoid. The refusal is provoked by a feed record carrying
     * NO transaction identifier: {@code ledger.daily_transactions} admits that and
     * {@code ledger.transactions} keys on it, so the posted row has nothing to be keyed by and the
     * provider refuses it where the unit saves it.</p>
     *
     * <p>Assumptions: the refusal is asserted by TYPE and not by message. What the provider says about a
     * missing assigned identifier is its own wording and would pin a library string that no contract in
     * this repository owns; that it is refused at all, and that the whole unit including its checkpoint
     * disappears, is the property under test.</p>
     *
     * <p>Assumptions: the survivors are read through the OUTSIDE observer. The sibling
     * {@code PostingUnitOfWorkIT} already asserts this position through a cleared persistence context on
     * the writing connection; what is added here is that a session which never participated in the
     * failed transaction also finds nothing, which is the statement about durability rather than about
     * the context's contents.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a refusal at the ledger write rolls back the earlier writes, seen from outside")
    void aRefusalAtTheLedgerWriteRollsBackTheEarlierWrites() {
        assertThatThrownBy(() -> postRefusedAtTheLedgerWrite(POSTED_AMOUNT))
                .as("the posted master refuses a row that has no identifier to key it by, which is the"
                        + " one nullability asymmetry the feed and the posted table declare")
                .isInstanceOf(DataAccessException.class);

        ObservedUnit afterRefusal = observeFromOutsideTheTransaction();

        assertThat(afterRefusal.categoryBalanceRows())
                .as("write one was rolled back with the unit, so no category balance survives")
                .isZero();
        assertThat(afterRefusal.ledgerRows())
                .as("write three never landed, so no posted transaction survives")
                .isZero();
        assertThat(afterRefusal.balance())
                .as("write two was rolled back with the unit, so the balance is exactly as seeded")
                .isEqualByComparingTo(OPENING_BALANCE);
        assertThat(afterRefusal.cycleCredit())
                .as("the cycle accumulator of the refused unit was rolled back too")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterRefusal.watermark().rows())
                .as("and the checkpoint went with them, so a record the posted master could not accept"
                        + " is not silently marked consumed")
                .isZero();
    }

    /**
     * Confirms no partial posting state is observable in either direction.
     *
     * <p>Purpose: the negative claim stated in its own terms. The migration plan's section 0.4.1.3
     * rejects a compensating-reversal design because it would make a posted transaction beside an
     * unposted balance observable, so the property to establish is an IMPOSSIBILITY rather than a count
     * after a clean run. Both directions are asserted over the refused unit: there is no posted
     * transaction without its category balance, and no category balance without its posted transaction.
     * A count taken after a successful posting would satisfy neither direction, because every row
     * present after a success is individually valid.</p>
     *
     * <p>Assumptions: the two directions are asserted as a joint condition on one observed snapshot
     * rather than as two independent readings, because the state being excluded is a DISAGREEMENT
     * between the two tables and two readings taken at different moments could not exclude it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("no posted transaction is observable without its category balance, or the reverse")
    void noPartialPostingStateIsObservableInEitherDirection() {
        assertThatThrownBy(() -> postRefusedAtTheLedgerWrite(POSTED_AMOUNT))
                .isInstanceOf(DataAccessException.class);

        ObservedUnit afterRefusal = observeFromOutsideTheTransaction();

        assertThat(afterRefusal.ledgerRows() == 0L && afterRefusal.categoryBalanceRows() == 0L)
                .as("a posted transaction beside an unposted balance, and an unposted balance beside a"
                        + " posted transaction, are both absent from the observed snapshot")
                .isTrue();
        assertThat(afterRefusal.ledgerRows())
                .as("the two tables agree, so neither is ahead of the other")
                .isEqualTo(afterRefusal.categoryBalanceRows());

        postAccepted(TRANSACTION_ID, POSTED_AMOUNT);
        ObservedUnit afterSuccess = observeFromOutsideTheTransaction();
        assertThat(afterSuccess.ledgerRows())
                .as("the two tables still agree once the unit commits, so the agreement is the"
                        + " invariant rather than an artefact of both being empty")
                .isEqualTo(afterSuccess.categoryBalanceRows());
    }

    /**
     * Confirms the category-balance write precedes the account write.
     *
     * <p>Purpose: the first half of the ordering that {@code app/cbl/CBTRN02C.cbl:440-442} fixes as
     * category balance, then account, then posted transaction. This case asserts the ORDER rather than
     * merely that all three writes occur, and the technique is to make TWO positions violate their
     * contracts in one unit and observe WHICH refusal arises: an engine can only refuse the write it
     * reaches first, so the identity of the refusal reports the order. A single failing position could
     * not distinguish an order at all.</p>
     *
     * <p>Assumptions: both violations are domain refusals raised IN MEMORY, and they are told apart by
     * their message rather than by their type -- the category balance names the nine-integer-digit
     * picture its own copybook declares, the account names the shared money domain sized to the wider
     * ten-digit one. The assertion therefore checks both directions: that the message names the
     * category-balance domain AND that it does not name the account's. Matching one text alone would
     * pass against either refusal if the two messages ever converged.</p>
     *
     * <p>Refactoring Rationale: the account position was previously violated by a lost version race, and
     * that pairing no longer reports an order. The production unit issues its writes without flushing
     * between them, so a version failure arises at the COMMIT -- after the third write has been issued --
     * whereas the category-balance refusal arises during the first. An in-memory refusal at the account
     * position is what keeps both violations on the same side of the flush, so the one that arises is the
     * one the unit reached first.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the category balance write precedes the account write")
    void theCategoryBalanceWritePrecedesTheAccountWrite() {
        seedCategoryBalanceAtItsCeiling();
        seedAccountAtItsBalanceCeiling();

        assertThatThrownBy(() -> postAccepted(TRANSACTION_ID, new BigDecimal("1.00")))
                .as("both positions would be refused, and the refusal that arises is the category"
                        + " balance's picture domain, so write one precedes write two")
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("9-integer-digit picture domain")
                .hasMessageNotContaining("reference money domain");
    }

    /**
     * Confirms the account write precedes the ledger write.
     *
     * <p>Purpose: the second half of the ordering, which together with the case above fixes all three
     * positions transitively. The account sits at the money domain's ceiling while the feed record
     * carries no transaction identifier, so both positions would be refused; the refusal that arises is
     * the account's, which places {@code app/cbl/CBTRN02C.cbl:441} ahead of {@code :442}.</p>
     *
     * <p>Assumptions: the two violations are of different KINDS here -- an in-memory money-domain
     * refusal at the account and a provider refusal at the posted row -- so the type of the exception
     * names the position on its own and no message match is needed. The ledger table is additionally
     * asserted empty, which says the third write was never issued rather than merely rolled back.</p>
     *
     * <p>Assumptions: a third pairwise comparison is deliberately not made. Category balance before
     * account and account before ledger already order all three, and a further case would restate what
     * transitivity gives.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the account write precedes the ledger write")
    void theAccountWritePrecedesTheLedgerWrite() {
        seedAccountAtItsBalanceCeiling();

        assertThatThrownBy(() -> postRefusedAtTheLedgerWrite(new BigDecimal("1.00")))
                .as("both positions would be refused, and the refusal that arises is the account's"
                        + " money domain, so write two precedes write three")
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("reference money domain");

        ObservedUnit afterRefusal = observeFromOutsideTheTransaction();
        assertThat(afterRefusal.ledgerRows())
                .as("the ledger write was never issued at all, the unit having stopped at write two")
                .isZero();
        assertThat(afterRefusal.watermark().rows())
                .as("and the checkpoint was never issued either")
                .isZero();
    }

    /**
     * Confirms the amount's sign decides which cycle accumulator the posting reaches, durably.
     *
     * <p>Purpose: obligation B at the engine. {@code app/cbl/CBTRN02C.cbl:547} advances the running
     * balance unconditionally, {@code :548} tests the amount with an INCLUSIVE comparison, {@code :549}
     * adds to the cycle credit and {@code :551} adds to the cycle debit. The three-way partition below
     * -- positive, exactly zero, negative -- is asserted as the stored consequence of that paragraph on
     * all three money columns at once.</p>
     *
     * <p>Assumptions: the ELSE arm adds the amount AS IS. {@code DALYTRAN-AMT} is already negative on
     * that arm, so {@code curr_cyc_debit} accumulates a NEGATIVE number: it is not negated, not
     * absolute-valued and not sign-flipped. The stored sign is what this case turns on, and it is the
     * one member of the partition that genuinely discriminates -- an implementation that negated or
     * took an absolute value would store the opposite sign and fail here. It matters beyond this column
     * because {@code app/cbl/CBTRN02C.cbl:402-404} SUBTRACTS the debit accumulator when it projects a
     * balance, so a stored sign convention inverted here would corrupt the over-limit decision that
     * tier one owns.</p>
     *
     * <p>Trade-offs: the ZERO member cannot discriminate the inclusive comparison from an exclusive one
     * in the stored row, and that limitation is stated rather than papered over. Both arms ADD the
     * amount, and adding zero leaves either accumulator unchanged, so a {@code > 0} implementation
     * would produce byte-identical columns. What this member does establish is the durable consequence
     * that no fixture covers at all: a zero-amount posting is ACCEPTED rather than rejected and leaves
     * the balance and both accumulators untouched, which
     * {@code aZeroAmountPostingCommitsAndLeavesTheAccountRowUnwritten} asserts along with the measured
     * fact that the account row is then not written at all. The branch itself is code rather than state,
     * and tier one owns it.</p>
     *
     * <p>Trade-offs: both the zero and the negative members are CONSTRUCTED, because the reference data
     * supplies no vector for either. Not one of the nine posting fixtures under
     * {@code tests/fixtures/posting} carries a negative or a zero amount -- all of them use positive
     * sign overpunches -- and none of the three hundred rows of {@code app/data/ASCII/dailytran.txt}
     * carries a zero amount, though fifty of them are negative. So the negative arm is genuine baseline
     * behaviour with no golden vector and the zero arm has no vector anywhere; both are constructed here
     * and the absence is recorded so nobody searches for a fixture that does not exist. The positive
     * member alone uses committed values, from
     * {@code tests/fixtures/posting/happy_path/acctdata.txt} to
     * {@code tests/golden/posting/happy_path/acctdat.expected}.</p>
     *
     * @param member the name of the partition member under test, used only to label the case in the
     *     report so a failure names the sign it was exercising rather than an index
     * @param amount the transaction amount the unit of work posts, one of a positive committed vector,
     *     an exact zero, or a constructed negative value
     * @param expectedBalance the running balance the account must carry once the unit commits, being the
     *     seeded balance advanced by the amount unconditionally
     * @param expectedCycleCredit the cycle credit accumulator the account must carry once the unit
     *     commits
     * @param expectedCycleDebit the cycle debit accumulator the account must carry once the unit
     *     commits, negative on the member that exercises the ELSE arm
     */
    @ParameterizedTest(name = "a {0} amount accumulates as the paragraph directs")
    @MethodSource("amountSignPartition")
    @DisplayName("the amount's sign decides which cycle accumulator the posting reaches")
    void theAmountSignSelectsTheCycleAccumulatorItReaches(String member, BigDecimal amount,
            BigDecimal expectedBalance, BigDecimal expectedCycleCredit, BigDecimal expectedCycleDebit) {

        long consumed = postAccepted(TRANSACTION_ID, amount);

        ObservedUnit committed = observeFromOutsideTheTransaction();

        assertThat(committed.balance())
                .as("the running balance advances unconditionally, whichever accumulator took the"
                        + " amount, for the %s member", member)
                .isEqualByComparingTo(expectedBalance);
        assertThat(committed.cycleCredit())
                .as("the cycle credit accumulator holds what the paragraph directs for the %s member",
                        member)
                .isEqualByComparingTo(expectedCycleCredit);
        assertThat(committed.cycleDebit())
                .as("the cycle debit accumulator holds what the paragraph directs for the %s member,"
                        + " with the amount stored as is rather than negated", member)
                .isEqualByComparingTo(expectedCycleDebit);
        assertThat(committed.watermark().ordinal())
                .as("every accepted member is checkpointed at its own ordinal, whichever accumulator it"
                        + " reached, for the %s member", member)
                .isEqualTo(consumed);
    }

    /**
     * Supplies the three-way partition of transaction amounts the accumulation paragraph distinguishes.
     *
     * <p>Assumptions: the seeded account starts with both accumulators at zero and a balance of
     * {@link #OPENING_BALANCE}, so each expectation below is that balance advanced by the member's
     * amount together with the accumulator the paragraph selects. The negative member's expectations are
     * arithmetic on the seeded values rather than decoded from a file, there being no file to decode.</p>
     *
     * @return the three cases as argument tuples of member name, amount, expected balance, expected
     *     cycle credit and expected cycle debit, never {@code null}
     */
    private static Stream<Arguments> amountSignPartition() {
        return Stream.of(
                Arguments.of("positive", POSTED_AMOUNT, POSTED_BALANCE, POSTED_AMOUNT, BigDecimal.ZERO),
                Arguments.of("zero", new BigDecimal("0.00"), OPENING_BALANCE,
                        BigDecimal.ZERO, BigDecimal.ZERO),
                Arguments.of("negative", new BigDecimal("-250.00"), new BigDecimal("-57.00"),
                        BigDecimal.ZERO, new BigDecimal("-250.00")));
    }

    /**
     * Confirms a zero-amount posting is accepted and commits, and records what it does NOT do.
     *
     * <p>Purpose: this is the observable half of the zero member the partition above cannot reach. A
     * zero-amount record is POSTED rather than rejected -- {@code app/cbl/CBTRN02C.cbl:211} branches on
     * the validation reason and a zero amount produces none -- so the unit writes its category balance
     * and its ledger row and leaves every account money column exactly where it was.</p>
     *
     * <p>Assumptions: a MEASURED behavioural difference is asserted here rather than the behaviour a
     * reader would predict from the paragraph, and it is recorded so nobody later reads the assertion as
     * a defect. {@code app/cbl/CBTRN02C.cbl:554} rewrites the account record UNCONDITIONALLY, outside
     * the comparison at {@code :548}, so the reference performs a write even when the amount moves
     * nothing. The target's provider compares the managed row against its loaded state and issues no
     * statement when no mapped column changed, so the version column does not advance. The difference is
     * confined to a column that has NO baseline counterpart -- the reference record carries no version --
     * and it is unobservable in the migrated data, because the record the reference rewrote would have
     * been byte-identical to the one already there. Parity is therefore unaffected, which is why the
     * measured behaviour is pinned rather than the predicted behaviour being forced.</p>
     *
     * <p>Trade-offs: no fixture and none of the three hundred shipped feed rows carries a zero amount,
     * so this case is constructed. It is worth constructing because it is the only assertion available
     * about the zero member at all, and because it fixes the suppressed-update behaviour as a known
     * quantity: a future change that made the account write unconditional would advance the version and
     * fail here, which is the point at which someone should decide whether that is wanted.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a zero amount posting commits, and leaves the account row unwritten")
    void aZeroAmountPostingCommitsAndLeavesTheAccountRowUnwritten() {
        long seededVersion = storedAccount().getVersion();

        long consumed = postAccepted(TRANSACTION_ID, new BigDecimal("0.00"));

        ObservedUnit committed = observeFromOutsideTheTransaction();
        assertThat(committed.version())
                .as("no mapped column changed, so the provider issued no account statement and the"
                        + " version stands where the seed left it")
                .isEqualTo(seededVersion);
        assertThat(committed.balance())
                .as("no money column moved, the amount being exactly zero")
                .isEqualByComparingTo(OPENING_BALANCE);
        assertThat(committed.cycleCredit())
                .as("neither accumulator moved either, adding zero to whichever the guard selected")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(committed.ledgerRows())
                .as("a zero amount is posted rather than rejected, so the unit wrote its ledger row")
                .isEqualTo(1L);
        assertThat(committed.categoryBalanceRows())
                .as("and its category balance row, so the unit committed rather than being skipped")
                .isEqualTo(1L);
        assertThat(committed.watermark().ordinal())
                .as("and its checkpoint, so a record that moved no money is still consumed and is not"
                        + " presented again by the next pass")
                .isEqualTo(consumed);
    }

    /**
     * Confirms the interest control break advances the balance and zeroes BOTH cycle accumulators.
     *
     * <p>Purpose: the second reference paragraph that writes this table.
     * {@code app/cbl/CBACT04C.cbl:350} is {@code 1050-UPDATE-ACCOUNT} and it makes THREE state changes,
     * not one: {@code :352} adds the accrued interest to the running balance, {@code :353} moves zero to
     * the cycle credit and {@code :354} moves zero to the cycle debit, before rewriting at {@code :356}.
     * The zeroing is a billing-cycle reset and a case that checked only the balance would miss two
     * thirds of the behaviour.</p>
     *
     * <p>Assumptions: the balance vector is the committed accrual one --
     * {@code tests/fixtures/interest/happy_path/acctdata.txt} holds {@code 194.00} and
     * {@code tests/golden/interest/happy_path/acctdat.expected} holds {@code 206.50} for the first
     * account -- and the accrued interest enters as an INPUT rather than being computed here, so no
     * rounding mode is asserted on this path. Tier one owns that arithmetic and pins its truncation.</p>
     *
     * <p>Trade-offs: the accumulators are seeded NON-ZERO here, and they have to be constructed because
     * the committed accrual fixture carries zero in both. A reset applied to an already-zero column is
     * indistinguishable from no reset at all, so the shipped vector cannot demonstrate the property its
     * own program performs; only a non-zero starting value can. The balance figures remain the
     * committed ones so the arithmetic stays checkable against the golden.</p>
     *
     * <p>Assumptions: the last account of that same golden stays at {@code 158.00} unchanged, because
     * the reference never flushes the final account's accrual. That divergence belongs to tier two's
     * accrual job test; this case asserts the per-account update semantics only and neither reproduces
     * the defect nor asserts its correction.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the interest control break advances the balance and zeroes both cycle accumulators")
    void theControlBreakUpdateZeroesBothCycleAccumulators() {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM account.accounts");
            this.accounts.save(seedAccount(INTEREST_OPENING_BALANCE,
                    new BigDecimal("31.00"), new BigDecimal("-17.50")));
        });

        this.transactionTemplate.executeWithoutResult(status -> {
            Account accruing = this.accounts.findByAccountId(ACCOUNT_ID).orElseThrow();
            applyControlBreakToAccount(accruing, ACCRUED_INTEREST);
            this.accounts.save(accruing);
        });

        ObservedUnit committed = observeFromOutsideTheTransaction();
        assertThat(committed.balance())
                .as("the accrued interest is added to the running balance, reaching the golden's value")
                .isEqualByComparingTo(INTEREST_CLOSING_BALANCE);
        assertThat(committed.cycleCredit())
                .as("the cycle credit accumulator is reset, not merely left alone")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(committed.cycleDebit())
                .as("the cycle debit accumulator is reset too, which a balance-only assertion would miss")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Confirms the version column advances on every committed update, so the mechanism is live.
     *
     * <p>Purpose: obligation C's precondition. The two cases below assert that a LOST race fails, and
     * such a case would pass for the wrong reason against an inert version column -- one that never
     * moved would make every comparison trivially equal and no race could ever be detected. This case
     * establishes that the column genuinely advances first, across two successive commits rather than
     * one, so a single accidental increment cannot satisfy it.</p>
     *
     * <p>Refactoring Rationale: the {@code @Version} column expresses a pattern the baseline ALREADY
     * implements rather than introducing a new one. {@code COACTUPC} snapshots the complete pre-edit
     * account record and compares it before rewriting, which is before-image optimistic concurrency
     * carried across the pseudo-conversational gap; what was wrong with porting that mechanism literally
     * is that it would carry a whole shadow copy of the record and a hand-written field-by-field
     * comparison into the target, where a single monotonic column expresses the same guarantee and the
     * provider maintains it. The contrast with the cross-reference is informative and
     * {@code CardXrefRepositoryIT} records it: that mapping carries no version column, for want of any
     * update path at all.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the version column advances on every committed update")
    void theVersionColumnAdvancesOnEveryCommittedUpdate() {
        long seeded = storedAccount().getVersion();

        postAccepted(TRANSACTION_ID, POSTED_AMOUNT);
        long afterFirst = observeFromOutsideTheTransaction().version();

        postAccepted(SECOND_TRANSACTION_ID, POSTED_AMOUNT);
        long afterSecond = observeFromOutsideTheTransaction().version();

        assertThat(afterFirst)
                .as("the first committed rewrite advances the version")
                .isEqualTo(seeded + 1L);
        assertThat(afterSecond)
                .as("the second committed rewrite advances it again, so the column is monotonic rather"
                        + " than incremented once by chance")
                .isEqualTo(seeded + 2L);
    }

    /**
     * Confirms a lost version race is propagated rather than retried in place.
     *
     * <p>Purpose: the ruling itself. A batch step that loses the race must FAIL so the orchestrator
     * retries the step, and must never re-read the row and write again behind its caller. The race is
     * made concrete rather than simulated: the account is read and detached, a genuinely separate
     * session commits a competing change to the same row, and the stale copy is then written.</p>
     *
     * <p>Refactoring Rationale: a silent re-read and retry is the alternative this rejects, and what is
     * wrong with it is specific rather than stylistic. The baseline rewrite accepts only a clean file
     * status and abends otherwise -- {@code app/cbl/CBTRN02C.cbl:554-559} carries an
     * {@code INVALID KEY} clause that sets reason 109 and there is no re-read path anywhere in the
     * program, and {@code app/cbl/CBACT04C.cbl:357} accepts only status {@code '00'} before failing --
     * so re-reading would invent behaviour the reference does not have. Concretely it would mask a
     * genuine concurrent modification and could then post the amount a second time against a balance
     * that had already moved underneath it, which is a wrong figure committed silently rather than a
     * step the orchestrator retries.</p>
     *
     * <p>Assumptions: the competing session is the second pool connection, the same mechanism the
     * outside observer uses, so no extra machinery is introduced for this case.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a lost version race is propagated rather than retried in place")
    void aLostVersionRaceIsPropagatedRatherThanRetriedInPlace() {
        BigDecimal competing = new BigDecimal("321.00");

        assertThatThrownBy(() ->
                postWithASupersededAccountVersion(TRANSACTION_ID, POSTED_AMOUNT, competing))
                .as("the write carrying the superseded version fails instead of re-reading the row")
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(observeFromOutsideTheTransaction().balance())
                .as("the competing session's value stands, so the losing write neither overwrote it nor"
                        + " quietly reapplied its own amount on top of it")
                .isEqualByComparingTo(competing);
    }

    /**
     * Confirms a lost race at the account write rolls back the other two writes as well.
     *
     * <p>Purpose: the composite of obligations A and C, and the case that ties the concurrency ruling to
     * the atomicity ruling. The account is the SECOND of the three writes, so a refusal there has an
     * earlier write to undo and a later write to prevent: the category balance of
     * {@code app/cbl/CBTRN02C.cbl:440} must vanish and the posted transaction of {@code :442} must never
     * appear. This is also the second of the three failure positions, reached by a genuine provider
     * refusal rather than by a manufactured exception.</p>
     *
     * <p>Assumptions: every survivor is read through the OUTSIDE observer, so what is asserted is what
     * the engine made durable rather than what the failed persistence context still holds.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a lost race at the account write rolls back the other two writes")
    void aLostVersionRaceAtTheAccountWriteRollsBackTheOtherTwoWrites() {
        BigDecimal competing = new BigDecimal("410.00");

        assertThatThrownBy(() ->
                postWithASupersededAccountVersion(TRANSACTION_ID, POSTED_AMOUNT, competing))
                .isInstanceOf(OptimisticLockingFailureException.class);

        ObservedUnit afterRefusal = observeFromOutsideTheTransaction();

        assertThat(afterRefusal.categoryBalanceRows())
                .as("write one had already reached the engine in the same flush and was rolled back")
                .isZero();
        assertThat(afterRefusal.ledgerRows())
                .as("write three had been issued too, and it went back with the unit")
                .isZero();
        assertThat(afterRefusal.watermark().rows())
                .as("and so did the checkpoint, which is the effect that decides whether a re-run"
                        + " re-presents this record or loses it")
                .isZero();
        assertThat(afterRefusal.balance())
                .as("the account carries the competing session's value and no part of the failed unit")
                .isEqualByComparingTo(competing);
    }

    /**
     * Confirms the account is read by the key the cross-reference supplies.
     *
     * <p>Purpose: {@code app/cbl/CBTRN02C.cbl:393-394} is {@code 1500-B-LOOKUP-ACCT}, and it moves
     * {@code XREF-ACCT-ID} into the file key before reading -- the account key comes from the
     * CROSS-REFERENCE and never from a field of the daily-transaction record, which carries no account
     * at all. This case resolves the card through the real table and reads the account by the identifier
     * that resolution returns.</p>
     *
     * <p>Assumptions: the cross-reference's own two access paths, their ordering under an account
     * holding several cards, and the non-unique secondary index that permits it belong to
     * {@code CardXrefRepositoryIT}; this case consumes the resolution's result as a key and asserts
     * nothing about how it was reached. The card number is named by its last four digits only.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the account is read by the key the cross-reference supplies")
    void theAccountIsReadByTheKeyTheCrossReferenceSupplies() {
        Long resolvedKey = this.transactionTemplate.execute(status ->
                this.crossReferences.findByCardNum(CARD_NUM).orElseThrow().getAccountId());

        assertThat(resolvedKey)
                .as("the cross-reference resolves the card ending %s to an account key", CARD_LAST_FOUR)
                .isEqualTo(ACCOUNT_ID);

        Optional<Account> keyed = this.transactionTemplate.execute(status ->
                this.accounts.findByAccountId(resolvedKey));

        assertThat(keyed)
                .as("the account master answers the key the cross-reference supplied")
                .isPresent();
        assertThat(keyed.orElseThrow().getAccountId()).isEqualTo(ACCOUNT_ID);
    }

    /**
     * Confirms the corrected expiration column and the padded group key round-trip through the engine.
     *
     * <p>Purpose: the account row's remaining mapped contracts.
     * {@code app/cpy/CVACT01Y.cpy} declares the record at 300 bytes, of which the trailing
     * {@code FILLER PIC X(178)} is DROPPED in the target -- the record reconciles without it and no
     * assertion here looks for it, this tier asserting rows rather than bytes.</p>
     *
     * <p>Refactoring Rationale: the expiration date is asserted under the CORRECTED name. The baseline
     * spells the field {@code ACCT-EXPIRAION-DATE}, and rather than carry a misspelling into a column
     * name that every future reader and every query would have to reproduce, the target names it
     * {@code expiration_date}; the lineage is recorded in
     * {@code docs/architecture/data-model-and-schema-mapping.md} so the rename is auditable rather than
     * silent. It is asserted as a {@code LocalDate} because the column is {@code DATE} rather than the
     * ten characters the copybook declares.</p>
     *
     * <p>Assumptions: the group key is stored blank-padded to ten characters, the fallback group name
     * being seven characters in a {@code CHAR(10)} column. The padded form is the join key into
     * {@code reference.disclosure_groups}, whose lookup and whose seeded rows under exactly this padded
     * key belong to the disclosure-group owner in this package; this case asserts only that the account
     * side stores the key in the padded form that join requires.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the corrected expiration column and the padded group key round-trip")
    void theCorrectedExpirationDateAndPaddedGroupKeyRoundTrip() {
        Account stored = storedAccount();

        assertThat(stored.getExpirationDate())
                .as("the corrected expiration column holds the fixture's date as a date")
                .isEqualTo(EXPIRATION_DATE);
        assertThat(stored.getOpenDate()).isEqualTo(OPEN_DATE);
        assertThat(stored.getReissueDate()).isEqualTo(REISSUE_DATE);
        assertThat(stored.getGroupId())
                .as("the group key round-trips blank-padded to the width its join partner expects")
                .isEqualTo(GROUP_ID)
                .hasSize(10);
        assertThat(stored.getActiveStatus())
                .as("the status is one of the two values the harness CHECK constraint admits")
                .isEqualTo(ACTIVE_STATUS);
    }

    /**
     * Confirms every money column round-trips at scale two carrying its own sign.
     *
     * <p>Purpose: the five money columns come from {@code PIC S9(10)V99} fields in
     * {@code app/cpy/CVACT01Y.cpy} and are declared {@code NUMERIC(12,2)}, so the engine fixes the scale
     * at two and preserves the sign. A negative accumulator is included because the debit arm stores its
     * amount as is, and because the oracle suite records that the reference build must compile with
     * EBCDIC sign handling since the default convention silently corrupts negative balances -- which
     * makes a negative value's round trip worth asserting at the database rather than assumed.</p>
     *
     * <p>Assumptions: the scale is asserted explicitly as well as the value. Comparison is by
     * {@code compareTo}, so {@code 0.1} and {@code 0.10} compare equal and a value assertion alone
     * cannot report a scale the column failed to fix; the two assertions together do.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every money column round-trips at scale two carrying its own sign")
    void moneyColumnsRoundTripAtScaleTwoWithTheirSign() {
        BigDecimal negativeAccumulator = new BigDecimal("-0.10");
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM account.accounts");
            this.accounts.save(seedAccount(OPENING_BALANCE, new BigDecimal("0.10"),
                    negativeAccumulator));
        });

        Account stored = storedAccount();

        assertThat(stored.getCurrCycDebit())
                .as("a negative accumulator round-trips with its sign intact")
                .isEqualByComparingTo(negativeAccumulator);
        assertThat(stored.getCurrCycDebit().scale())
                .as("the column fixes the scale at two, which a comparison by value cannot report")
                .isEqualTo(2);
        assertThat(stored.getCurrCycCredit().scale()).isEqualTo(2);
        assertThat(stored.getCurrBal())
                .as("the running balance round-trips at the seeded value")
                .isEqualByComparingTo(OPENING_BALANCE);
        assertThat(stored.getCreditLimit()).isEqualByComparingTo(CREDIT_LIMIT);
        assertThat(stored.getCashCreditLimit()).isEqualByComparingTo(CASH_CREDIT_LIMIT);
    }

    /**
     * Confirms the card fixture this class commits is unroutable and fails the Luhn check.
     *
     * <p>Purpose: to hold the fixture to the two properties that make it safe to commit, MECHANICALLY,
     * rather than to a sentence in a comment. Every case in this class writes {@code CARD_NUM} into a
     * cross-reference row, a feed row and a posted row, so the value is committed to the repository; the
     * only thing that keeps it from being a plausible payment instrument is that it belongs to no issuer
     * range and would be refused by a check-digit test before an issuer ever saw it. Those are checkable
     * facts, and this case checks them.</p>
     *
     * <p>Refactoring Rationale: the value replaced here was {@code 4}-prefixed and its Luhn contribution
     * sum was twenty, so it was checksum-VALID and issuer-shaped, while the comment above it attested
     * that it failed the check. The attestation was the defect: a false safety claim is worse than none,
     * because it stops the next reader looking. Prose alone would have gone stale the same way the moment
     * anyone edited a digit, so the claim is now a test -- the digits and the assertions cannot disagree
     * without a failure.</p>
     *
     * <p>Assumptions: the two properties are independent and both are required. The {@code 9900} prefix
     * puts the value in major-industry identifier 9, which ISO/IEC 7812 leaves for national assignment
     * and which no card network issues under, so the number is unroutable whatever its check digit. The
     * failed Luhn check is the second, narrower guarantee: even a validator that ignored the prefix
     * entirely would reject it. This is the same pair
     * {@code services/reporting-service/src/test/java/com/carddemo/reporting/fixtures/ReportingFixtureContractTest.java}
     * holds that module's fixture resources to, and the convention is deliberately shared -- one rule for
     * fabricated card numbers across the repository is one rule a reviewer can check.</p>
     *
     * <p>Assumptions: no assertion here prints the value. The length, prefix and checksum are all
     * asserted as booleans or integers rather than by passing the string to a matcher, because a matcher
     * that fails prints its actual value and would put a sixteen-digit number in a build log -- which is
     * the precaution the rest of the class takes by asserting on {@code CARD_LAST_FOUR}.</p>
     *
     * <p>Trade-offs: a control value is checked as well as the fixture, which costs one assertion and
     * buys the only thing that makes the negative result meaningful -- evidence that
     * {@link #satisfiesLuhn} can return {@code true} at all. Without it a checker that returned
     * {@code false} unconditionally would pass this case, which is exactly how the claim it replaces went
     * unexamined. The control differs from the fixture in its final digit alone and carries the same
     * unassigned prefix, so it introduces no second issuer-shaped value.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the card fixture is unroutable and fails the Luhn check")
    void theCardFixtureIsUnroutableAndFailsTheLuhnCheck() {
        assertThat(CARD_NUM.length())
                .as("the fixture is sixteen digits, the width the card copybook declares")
                .isEqualTo(16);
        assertThat(CARD_NUM.chars().allMatch(Character::isDigit))
                .as("the fixture is digits only, so the checksum below is computed over the whole value")
                .isTrue();
        assertThat(CARD_NUM.startsWith(UNROUTABLE_PREFIX))
                .as("the fixture carries the unassigned major-industry prefix, so it belongs to no"
                        + " issuer range")
                .isTrue();
        assertThat(satisfiesLuhn(CARD_NUM))
                .as("and it fails the Luhn check, so a validator that ignored the prefix would refuse"
                        + " it too")
                .isFalse();

        // WHY : Trade-offs: the control carries the same unassigned prefix and differs from the fixture
        //       in its last digit alone, so it demonstrates the checker can report a VALID value without
        //       committing a second value that looks like a real instrument. A checker stuck at false
        //       would pass the assertion above and fail this one.
        String luhnValidControl = "9900000000000077";
        assertThat(satisfiesLuhn(luhnValidControl))
                .as("the control satisfies the check, so the negative result above is a measurement"
                        + " rather than a checker that never returns true")
                .isTrue();

        assertThat(CARD_NUM.endsWith(CARD_LAST_FOUR))
                .as("the masked form every message in this class uses is the fixture's own last four"
                        + " digits, so a diagnostic cannot name a different card")
                .isTrue();
    }

    /**
     * Commits one unposted feed record and returns the ingestion ordinal the engine assigned it.
     *
     * <p>Assumptions: the row is inserted by STATEMENT and not through a repository, because the feed's
     * mapping is {@code @Immutable} and its interface declares no mutator at all -- this module only
     * ever reads the feed, the rows being produced upstream by the extract-and-load package. Inserting
     * through the same {@code DataSource} inside a committed template keeps the row visible to the unit
     * of work that reads it back.</p>
     *
     * <p>Assumptions: the ordinal is RETURNED by the statement rather than assumed, because
     * {@code ingest_seq} is {@code BIGINT GENERATED BY DEFAULT AS IDENTITY} and the identity sequence is
     * not reset by the {@code DELETE} each case begins with -- so ordinals rise across the whole class
     * run and no case may compare a watermark against a literal. Every assertion below compares against
     * the value this method returned.</p>
     *
     * @param transactionId the identifier the feed row carries, or {@code null} for the case that
     *     provokes the third write's refusal, the feed column admitting a null where the posted table's
     *     primary key does not
     * @param amount the transaction amount the row carries; must not be {@code null}
     * @return the ingestion ordinal the engine assigned, which is also the position the unit checkpoints
     * @throws IllegalStateException if the statement returns no ordinal, the arrangement then being
     *     broken rather than the case having a result
     */
    private long seedFeedRecord(String transactionId, BigDecimal amount) {
        Long ordinal = this.transactionTemplate.execute(status -> this.jdbc.queryForObject(
                "INSERT INTO ledger.daily_transactions (transaction_id, type_cd, category_cd, source,"
                        + " description, amount, merchant_id, merchant_name, merchant_city,"
                        + " merchant_zip, card_num, orig_ts)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING ingest_seq",
                Long.class, transactionId, TYPE_CD, CATEGORY_CD, FEED_SOURCE, DESCRIPTION, amount,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, CARD_NUM, ORIGINATED_AT));

        if (ordinal == null) {
            throw new IllegalStateException(
                    "the seeded feed record returned no ingestion ordinal, so no unit of work can be"
                            + " driven over it");
        }
        return ordinal;
    }

    /**
     * Reads one seeded feed record back through the production interface, by its ordinal.
     *
     * <p>Assumptions: the read goes through the CONTINUATION FINDER the posting job itself walks with,
     * called with the ordinal one below the wanted row and a limit of one, rather than through a finder
     * of this test's own. The interface declares exactly one member and this is it, so a case cannot
     * reach a row by a path the production step does not have.</p>
     *
     * @param ingestSeq the ordinal the seeding statement returned; must be an ordinal it assigned
     * @return the feed record at that ordinal, as the production step would read it, never {@code null}
     * @throws IllegalStateException if the row is absent or another row answered, either of which is a
     *     broken arrangement rather than a result
     */
    private DailyTransaction feedRecord(long ingestSeq) {
        List<DailyTransaction> read =
                this.feed.findByIngestSeqGreaterThanOrderByIngestSeqAsc(ingestSeq - 1L, Limit.of(1));

        if (read.isEmpty() || !Long.valueOf(ingestSeq).equals(read.getFirst().getIngestSeq())) {
            throw new IllegalStateException("the seeded feed record at ordinal " + ingestSeq
                    + " did not answer the continuation finder, so the arrangement is broken");
        }
        return read.getFirst();
    }

    /**
     * Drives the PRODUCTION per-record unit over one seeded feed record, inside one committed boundary.
     *
     * <p>Assumptions: this method is the single entry point every posting case reaches production
     * through, and it exists as one method so that no case can drift into issuing writes of its own. The
     * boundary is opened here and the unit opens none, which is exactly the production arrangement: the
     * job brackets each record in a transaction and the component performs the record's decisions and
     * writes inside it.</p>
     *
     * <p>Assumptions: the returned reject record is handed back rather than discarded, because it is how
     * the production unit reports which arm it took -- an empty result means the record posted. A case
     * that arranged an accepted record and received a reject record would otherwise assert against
     * tables the unit never wrote and report a durability failure that was really a rejected
     * arrangement.</p>
     *
     * @param ingestSeq the ordinal of the seeded feed record to post; must be an assigned ordinal
     * @return the 430-byte reject record when the unit rejected the record, empty when it posted, never
     *     {@code null}
     * @throws ArithmeticException if the accumulated category balance or the account balance leaves the
     *     domain its picture admits, which two failure-position cases provoke deliberately
     * @throws DataAccessException if the provider or the engine refuses a write, which the third
     *     failure-position case provokes deliberately
     * @throws OptimisticLockingFailureException if the account carries a superseded version, which the
     *     two version cases provoke deliberately
     */
    private Optional<byte[]> postThroughTheProductionUnit(long ingestSeq) {
        return this.transactionTemplate.execute(status ->
                this.perRecord.applyOneRecord(feedRecord(ingestSeq), RUN_ID, BUSINESS_DATE));
    }

    /**
     * Seeds one feed record and posts it through the production unit, expecting the accepted arm.
     *
     * @param transactionId the identifier the feed row and the posted row carry; must not be
     *     {@code null}
     * @param amount the transaction amount the unit accumulates and posts; must not be {@code null}
     * @return the ingestion ordinal the record was assigned, which every watermark assertion compares
     *     against
     * @throws IllegalStateException if the unit REJECTED the record, the arrangement then being broken
     * @throws ArithmeticException if a money domain refuses a write, which two cases provoke
     * @throws OptimisticLockingFailureException if the account write loses a version race, which two
     *     cases provoke
     */
    private long postAccepted(String transactionId, BigDecimal amount) {
        long ordinal = seedFeedRecord(transactionId, amount);

        if (postThroughTheProductionUnit(ordinal).isPresent()) {
            throw new IllegalStateException(
                    "the production unit rejected the seeded record, so the arrangement is broken"
                            + " rather than the case having a result");
        }
        return ordinal;
    }

    /**
     * Seeds a feed record the POSTED table cannot accept, and posts it, so the third write is refused.
     *
     * <p>Assumptions: the refusal comes from the one nullability asymmetry the two tables declare.
     * {@code ledger.daily_transactions.transaction_id} is nullable -- the harness records why, the
     * pre-posting feed having no usable natural key -- while {@code ledger.transactions.transaction_id}
     * is that table's {@code NOT NULL} primary key. So a feed row carrying no identifier is a row the
     * feed admits and the posted master must refuse, and the refusal arises where the production unit
     * builds and saves the posted row, which is the third write.</p>
     *
     * <p>Alternatives Considered: a description one character wider than the posted column admits, which
     * is what this class used before it drove production. Unavailable now rather than rejected: the
     * description would have to reach the unit through the feed ROW, and both columns are
     * {@code VARCHAR(100)}, so a value the posted column refuses cannot be stored in the feed in the
     * first place. Alternatives Considered: posting the same identifier twice for a duplicate-key
     * refusal. Rejected on a measured mechanism -- the posted entity carries no version column, so
     * Spring Data's {@code save} treats a non-null assigned identifier as existing and issues a
     * {@code merge}, which UPDATES the earlier row instead of refusing a second insert.</p>
     *
     * @param amount the transaction amount the unit accumulates before the refused write; must not be
     *     {@code null}
     * @return never returns normally
     * @throws DataAccessException always, carrying the provider's refusal of a posted row that has no
     *     identifier to be keyed by
     */
    private Optional<byte[]> postRefusedAtTheLedgerWrite(BigDecimal amount) {
        return postThroughTheProductionUnit(seedFeedRecord(null, amount));
    }

    /**
     * Posts one record through the production unit while a competing session supersedes the account.
     *
     * <p>Assumptions: the race is made real in the ONLY order that produces one. The account is read
     * INSIDE the boundary first, which puts the row in the persistence context at the version current at
     * that moment; a separate session then commits its own change to the same row, advancing the version
     * column itself; and the production unit is driven afterwards. Its own read resolves to the instance
     * already in the context -- the provider's identity guarantee, which does not overwrite managed state
     * with a fresher row -- so the unit accumulates onto the superseded version and the version check
     * fails when the transaction flushes.</p>
     *
     * <p>Assumptions: no explicit flush is issued, deliberately. The refusal then arises at the COMMIT,
     * which is where the framework translates the provider's optimistic-lock failure into the Spring
     * exception the cases assert on, and it is also what makes those cases the ones in which the earlier
     * writes genuinely reached the engine before the unit failed.</p>
     *
     * <p>Assumptions: the arrangement is GUARDED rather than assumed. If the competing session had not
     * advanced the version there would be no race, the unit would commit, and a case expecting a refusal
     * would fail with a message about a missing exception instead of about a broken arrangement.</p>
     *
     * @param transactionId the identifier the feed row and the posted row carry; must not be
     *     {@code null}
     * @param amount the transaction amount the unit accumulates before the refused write; must not be
     *     {@code null}
     * @param competingBalance the balance the competing session commits; must not be {@code null}
     * @throws OptimisticLockingFailureException always, the account write losing the version race
     * @throws IllegalStateException if the competing session did not supersede the version
     */
    private void postWithASupersededAccountVersion(String transactionId, BigDecimal amount,
            BigDecimal competingBalance) {

        long ordinal = seedFeedRecord(transactionId, amount);
        this.transactionTemplate.executeWithoutResult(status -> {
            long versionInContext =
                    this.accounts.findByAccountId(ACCOUNT_ID).orElseThrow().getVersion();
            advanceTheAccountOnACompetingSession(competingBalance);

            if (versionInContext == observeFromOutsideTheTransaction().version()) {
                throw new IllegalStateException(
                        "the competing session did not advance the version, so the arrangement is not"
                                + " a race and the case would prove nothing");
            }
            this.perRecord.applyOneRecord(feedRecord(ordinal), RUN_ID, BUSINESS_DATE);
        });
    }

    /**
     * Posts one record through the production unit, flushes it, and reads the tables from outside.
     *
     * <p>Assumptions: the observation happens INSIDE the boundary, after the unit's writes have been
     * flushed to the engine and before the commit, which is the only moment at which the property being
     * asserted exists. The flush is issued here because the production unit issues none of its own --
     * every write goes through a repository {@code save} and the provider queues them until commit -- and
     * an unflushed row is not at the server at all, so its invisibility to another session would be
     * vacuous. Returning the snapshot rather than asserting inside the callback keeps the assertions with
     * their case, where a failure names them.</p>
     *
     * @param transactionId the identifier the feed row and the posted row carry; must not be
     *     {@code null}
     * @param amount the transaction amount the unit accumulates and posts; must not be {@code null}
     * @return what a session outside the still-open transaction can see of the four written tables,
     *     never {@code null}
     */
    private ObservedUnit postAndObserveBeforeCommit(String transactionId, BigDecimal amount) {
        long ordinal = seedFeedRecord(transactionId, amount);
        return this.transactionTemplate.execute(status -> {
            this.perRecord.applyOneRecord(feedRecord(ordinal), RUN_ID, BUSINESS_DATE);
            this.entityManager.flush();
            return observeFromOutsideTheTransaction();
        });
    }

    /**
     * Applies the interest control break's three state changes to an account.
     *
     * <p>Assumptions: {@code app/cbl/CBACT04C.cbl:352-354} adds the accrued interest to the running
     * balance and then moves zero into BOTH cycle accumulators, and all three changes are applied here
     * because the reset is the part a reader most easily misses. The interest arrives as a parameter
     * rather than being computed, so this method asserts no rounding behaviour and none is implied: the
     * baseline divide truncates and tier one's interest test owns that arithmetic.</p>
     *
     * @param accruing the account the control break updates; must not be {@code null}
     * @param interest the interest total the break has accrued for that account; must not be
     *     {@code null}
     */
    private static void applyControlBreakToAccount(Account accruing, BigDecimal interest) {
        accruing.setCurrBal(Money.of(accruing.getCurrBal()).plus(Money.of(interest)).amount());
        accruing.setCurrCycCredit(Money.of(BigDecimal.ZERO).amount());
        accruing.setCurrCycDebit(Money.of(BigDecimal.ZERO).amount());
    }

    /**
     * Builds the account row a case starts from, at the balances that case needs.
     *
     * <p>Assumptions: the three dates are the parity fixture's own and the expiration date is ahead of
     * the processing stamp, so no seeded account is one the reference would have rejected. This class
     * asserts durability rather than validation, and a fixture that happened to be rejectable would make
     * a failure ambiguous.</p>
     *
     * @param balance the running balance the account starts at; must not be {@code null}
     * @param cycleCredit the cycle credit accumulator the account starts at; must not be {@code null}
     * @param cycleDebit the cycle debit accumulator the account starts at, which one case starts
     *     negative; must not be {@code null}
     * @return a transient account row at the requested balances, never {@code null}
     */
    private static Account seedAccount(BigDecimal balance, BigDecimal cycleCredit,
            BigDecimal cycleDebit) {

        return new Account(ACCOUNT_ID, ACTIVE_STATUS, balance, CREDIT_LIMIT, CASH_CREDIT_LIMIT,
                OPEN_DATE, EXPIRATION_DATE, REISSUE_DATE, cycleCredit, cycleDebit, ADDR_ZIP, GROUP_ID);
    }

    /**
     * Commits one category-balance row holding the largest value its column can carry.
     *
     * <p>Assumptions: it is written through the production accumulation rule rather than by direct SQL,
     * so the row that a later refused unit updates is one the rule itself created and the refusal
     * arises on the rule's own update arm.</p>
     */
    private void seedCategoryBalanceAtItsCeiling() {
        this.transactionTemplate.executeWithoutResult(status -> this.balances.accumulate(
                new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CD, CATEGORY_CD),
                Money.of(CATEGORY_BALANCE_CEILING)));
    }

    /**
     * Re-seeds the account at the last balance the shared money domain admits, and commits it.
     *
     * <p>Assumptions: the row is replaced rather than updated, so its version column starts from the
     * value a fresh insert carries and no case that reads the version has to account for a rewrite this
     * arrangement made. The accumulators are seeded at zero deliberately: the validation rule projects
     * its over-limit decision from them and not from the running balance, so an account at this ceiling
     * with zero accumulators is one the rule ACCEPTS -- which is what puts the refusal at the account
     * write rather than at validation.</p>
     */
    private void seedAccountAtItsBalanceCeiling() {
        this.transactionTemplate.executeWithoutResult(status -> {
            this.jdbc.update("DELETE FROM account.accounts");
            this.accounts.save(
                    seedAccount(ACCOUNT_BALANCE_CEILING, BigDecimal.ZERO, BigDecimal.ZERO));
        });
    }

    /**
     * Computes whether a candidate card number satisfies the Luhn check digit algorithm.
     *
     * <p>Assumptions: the algorithm is implemented here rather than taken from a dependency, because the
     * only thing this class needs it for is to hold its own fixture to a stated property, and a
     * dependency added for one assertion would be a dependency every consumer of this module inherits.
     * It doubles every second digit counting from the RIGHT, casts out nine from any doubled value above
     * nine, and reports whether the total is a multiple of ten, which is the definition ISO/IEC 7812
     * annex B gives.</p>
     *
     * <p>Assumptions: the candidate is never echoed, by this method or by any assertion that calls it.
     * It returns a boolean rather than the sum precisely so that a failing assertion prints
     * {@code true}/{@code false} and cannot put a sixteen-digit value into a build log.</p>
     *
     * @param candidate the digits to check, which must contain digits only; must not be {@code null}
     * @return {@code true} when the candidate's check digit is correct, {@code false} when it is not
     * @throws IllegalArgumentException if the candidate carries a character that is not a digit, the
     *     fixture then being malformed rather than merely invalid
     */
    private static boolean satisfiesLuhn(String candidate) {
        int sum = 0;
        boolean doubling = false;
        for (int position = candidate.length() - 1; position >= 0; position--) {
            int digit = Character.digit(candidate.charAt(position), 10);
            if (digit < 0) {
                throw new IllegalArgumentException(
                        "the candidate carries a non-digit character at position " + position);
            }
            int contribution = doubling ? digit * 2 : digit;
            sum += contribution > 9 ? contribution - 9 : contribution;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /**
     * Reads the one category-balance row this class writes, by its composite key.
     *
     * @return the stored row, or empty when no unit of work has created it; never {@code null}
     */
    private Optional<TransactionCategoryBalance> storedCategoryBalance() {
        return this.transactionTemplate.execute(status -> this.categoryBalances.findById(
                new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CD, CATEGORY_CD)));
    }

    /**
     * Reads the seeded account through the repository under test, in its own transaction.
     *
     * @return the stored account row; never {@code null}
     */
    private Account storedAccount() {
        return this.transactionTemplate.execute(status ->
                this.accounts.findByAccountId(ACCOUNT_ID).orElseThrow());
    }

    /**
     * Commits a competing change to the account row from a genuinely separate session.
     *
     * <p>Assumptions: the competing write goes through its own pool connection and its own commit, and
     * it advances the version column itself, so what the losing write below meets is a row another
     * session has already changed rather than a version this test decremented by hand. Direct SQL is
     * used deliberately: routing it through the repository would enlist it in whatever context the test
     * thread holds and it would no longer be a second session.</p>
     *
     * @param newBalance the balance the competing session commits; must not be {@code null}
     * @throws IllegalStateException if the competing session cannot issue or commit its update, the
     *     arrangement then being broken rather than the case having a result
     */
    private void advanceTheAccountOnACompetingSession(BigDecimal newBalance) {
        try (Connection competing = this.dataSource.getConnection()) {
            try (PreparedStatement update = competing.prepareStatement(
                    "UPDATE account.accounts SET curr_bal = ?, version = version + 1"
                            + " WHERE account_id = ?")) {
                update.setBigDecimal(1, newBalance);
                update.setLong(2, ACCOUNT_ID);
                update.executeUpdate();
            }
            competing.commit();
        } catch (SQLException refusal) {
            throw new IllegalStateException(
                    "the competing session could not advance the account row", refusal);
        }
    }

    /**
     * Reads the four written tables from a session that is not the transaction under test.
     *
     * <p>Assumptions: the connection is taken straight from the pool with
     * {@code DataSource#getConnection} rather than through a {@code JdbcTemplate}, and that is the
     * decision every atomicity case in this class rests on. A template over the same
     * {@code DataSource} resolves to the connection Spring has bound to the current thread for the open
     * transaction, so it would read that transaction's own uncommitted snapshot and report a passing
     * observation that says nothing about durability. A pool connection is a separate session, and the
     * test profile's pool maximum of four is what makes one available while the writer holds its own.</p>
     *
     * <p>Assumptions: the read is rolled back before the connection returns to the pool. Auto-commit is
     * off on this pool, so even a read opens a transaction, and ending it explicitly keeps the observer
     * from holding a snapshot open across the next case.</p>
     *
     * <p>Assumptions: counts and single columns are returned rather than rows. The ledger row carries a
     * sixteen-character card number and the account is keyed through a cross-reference whose key is a
     * card number, so returning whole rows would put them within reach of a failing assertion's output;
     * the migration plan's section 0.7.8 masks primary account numbers to their last four digits.</p>
     *
     * @return what an outside session can see of the two ledger tables, the watermark and the account's
     *     four mutable columns, never {@code null}
     * @throws IllegalStateException if the observing session cannot read the four tables, or if the
     *     seeded account row is absent, either of which is a broken arrangement rather than a result
     */
    private ObservedUnit observeFromOutsideTheTransaction() {
        try (Connection observer = this.dataSource.getConnection()) {
            try {
                long categoryBalanceRows =
                        countOn(observer, "SELECT count(*) FROM ledger.transaction_category_balances");
                long ledgerRows = countOn(observer, "SELECT count(*) FROM ledger.transactions");
                return readAccountColumns(observer, categoryBalanceRows, ledgerRows,
                        readWatermark(observer));
            } finally {
                observer.rollback();
            }
        } catch (SQLException refusal) {
            throw new IllegalStateException(
                    "the observing session could not read the four tables", refusal);
        }
    }

    /**
     * Reads the daily feed's consumed position on the observing connection.
     *
     * <p>Assumptions: an ABSENT row is reported as {@code NOTHING_CONSUMED} together with a null run
     * identifier, rather than as an error, because that is exactly what the production reader does with
     * it -- a feed that has never been consumed has no row. It is unambiguous as evidence because the
     * production advance is strictly monotonic from that same starting value, so no committed row can
     * carry the zero an absent row reports.</p>
     *
     * @param observer the separate session the read is issued on; must not be {@code null}
     * @return the consumed position, the run it is attributed to and whether a row exists at all, never
     *     {@code null}
     * @throws SQLException if the statement cannot be issued or read
     */
    private static ObservedWatermark readWatermark(Connection observer) throws SQLException {
        try (PreparedStatement reading = observer.prepareStatement(
                "SELECT last_ingest_seq, run_id, business_date FROM batch.daily_feed_watermark"
                        + " WHERE feed_name = ?")) {
            reading.setString(1, DailyFeedWatermarkService.DAILY_TRANSACTION_FEED);
            try (ResultSet read = reading.executeQuery()) {
                if (!read.next()) {
                    return new ObservedWatermark(
                            0L, DailyFeedWatermarkService.NOTHING_CONSUMED, null, null);
                }
                return new ObservedWatermark(
                        1L, read.getLong(1), read.getString(2), read.getString(3));
            }
        }
    }

    /**
     * Counts the rows a scalar counting statement reports on the observing connection.
     *
     * @param observer the separate session the count is issued on; must not be {@code null}
     * @param sql a statement selecting exactly one counting column and taking no parameter; must not be
     *     {@code null}
     * @return the count the statement reported, or zero when it reported no row at all
     * @throws SQLException if the statement cannot be issued or read
     */
    private static long countOn(Connection observer, String sql) throws SQLException {
        try (PreparedStatement counting = observer.prepareStatement(sql);
                ResultSet counted = counting.executeQuery()) {
            return counted.next() ? counted.getLong(1) : 0L;
        }
    }

    /**
     * Reads the account's four mutable columns on the observing connection into a snapshot.
     *
     * <p>Assumptions: the account row always exists while a case runs, being seeded and committed before
     * each one and only ever updated, so a missing row is a broken arrangement rather than an expected
     * outcome and is reported as such instead of being returned as zeros.</p>
     *
     * @param observer the separate session the read is issued on; must not be {@code null}
     * @param categoryBalanceRows the category-balance count already taken on the same session, carried
     *     into the returned snapshot so all three readings describe one moment
     * @param ledgerRows the posted-transaction count already taken on the same session, carried into the
     *     returned snapshot for the same reason
     * @param watermark the feed's consumed position already read on the same session, carried into the
     *     returned snapshot so all four readings describe one moment; must not be {@code null}
     * @return the snapshot of what the outside session can see, never {@code null}
     * @throws SQLException if the statement cannot be issued or read
     * @throws IllegalStateException if the seeded account row is absent
     */
    private static ObservedUnit readAccountColumns(Connection observer, long categoryBalanceRows,
            long ledgerRows, ObservedWatermark watermark) throws SQLException {

        try (PreparedStatement reading = observer.prepareStatement(
                "SELECT curr_bal, curr_cyc_credit, curr_cyc_debit, version"
                        + " FROM account.accounts WHERE account_id = ?")) {
            reading.setLong(1, ACCOUNT_ID);
            try (ResultSet read = reading.executeQuery()) {
                if (!read.next()) {
                    throw new IllegalStateException(
                            "the seeded account row is absent, so the arrangement did not commit it");
                }
                return new ObservedUnit(categoryBalanceRows, ledgerRows, read.getBigDecimal(1),
                        read.getBigDecimal(2), read.getBigDecimal(3), read.getLong(4), watermark);
            }
        }
    }

    /**
     * What a session outside the transaction under test can see of the unit of work's three tables.
     *
     * <p>Assumptions: this carries counts and individual columns rather than entities, so that a failing
     * assertion cannot render a whole row and cannot reach the card number either table holds.</p>
     *
     * @param categoryBalanceRows the number of category-balance rows visible, being write one
     * @param ledgerRows the number of posted-transaction rows visible, being write three
     * @param balance the account's running balance as visible, being part of write two
     * @param cycleCredit the account's cycle credit accumulator as visible
     * @param cycleDebit the account's cycle debit accumulator as visible, which one case expects
     *     negative
     * @param version the account's version column as visible, reporting whether a rewrite was made
     *     durable
     * @param watermark the feed's consumed position as visible, being the unit's fourth durable effect
     */
    private record ObservedUnit(long categoryBalanceRows, long ledgerRows, BigDecimal balance,
            BigDecimal cycleCredit, BigDecimal cycleDebit, long version, ObservedWatermark watermark) {
    }

    /**
     * What a session outside the transaction under test can see of the feed's consumed position.
     *
     * <p>Assumptions: the row count is carried BESIDE the ordinal rather than inferred from it, so that
     * a case refusing a write can assert that no row exists at all -- which is a stronger statement than
     * an ordinal of zero, and the one the checkpoint's own contract makes.</p>
     *
     * @param rows the number of watermark rows visible for the daily feed, being zero or one
     * @param ordinal the consumed position as visible, or {@code NOTHING_CONSUMED} when no row exists
     * @param runId the run the visible advance is attributed to, or {@code null} when no row exists
     * @param businessDate the injected date the visible advance is attributed to, or {@code null} when no
     *     row exists
     */
    private record ObservedWatermark(long rows, long ordinal, String runId, String businessDate) {
    }

    /**
     * The minimal Spring Boot configuration this class runs against.
     *
     * <p>Assumptions: no component scan is declared, so the job definitions, the queue client and the
     * object-store client this module's own application class registers stay out of the context. The
     * production types these cases need are declared as beans instead, which keeps the context to the
     * persistence layer plus the per-record unit of work and the three rules it composes.</p>
     *
     * <p>Assumptions: every bean below is the PRODUCTION type over the production repositories, and none
     * is a stand-in. That is the whole point of the arrangement: a substitute at any of these positions
     * would move the behaviour under test out of production code and back into this file, which is the
     * defect the class-level rationale records.</p>
     *
     * <p>A configuration class accepts no parameter, yields no value and raises nothing, so this block
     * carries no parameter, return or exception at-clause.</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.carddemo.batch.domain")
    @EnableJpaRepositories("com.carddemo.batch.repository")
    static class AccountPersistenceTestApplication {

        /**
         * Registers the production accumulation rule over the real repository.
         *
         * @param balances the category-balance repository the rule reads and writes through; must not be
         *     {@code null}
         * @return the production service, never {@code null}
         */
        @Bean
        CategoryBalanceService categoryBalanceService(
                TransactionCategoryBalanceRepository balances) {
            return new CategoryBalanceService(balances);
        }

        /**
         * Registers the clock the posted row's processing stamp is minted from.
         *
         * <p>Assumptions: a FIXED clock at {@link #PROCESSED_AT} in UTC, never the system clock. The
         * processing stamp is the one non-deterministic field a posted record carries -- the oracle
         * suite masks it before comparing goldens for exactly that reason -- and the watermark's
         * {@code updated_at} is minted from the same clock, so pinning it makes both values reproducible
         * and lets a case assert them rather than merely assert they are present.</p>
         *
         * @return a clock fixed at the class's processing instant, never {@code null}
         */
        @Bean
        Clock fixedPostingClock() {
            return Clock.fixed(PROCESSED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        }

        /**
         * Registers the production validation rule, which resolves the card and the account it posts to.
         *
         * @param crossReferences the cross-reference the card number is resolved through; must not be
         *     {@code null}
         * @param accounts the account master the resolved key is read from; must not be {@code null}
         * @return the production service, never {@code null}
         */
        @Bean
        PostingValidationService postingValidationService(CardXrefRepository crossReferences,
                AccountRepository accounts) {
            return new PostingValidationService(crossReferences, accounts);
        }

        /**
         * Registers the production watermark service, whose advance is the unit's fourth durable effect.
         *
         * @param watermarks the watermark table this module owns; must not be {@code null}
         * @param clock the fixed clock the advance is stamped from; must not be {@code null}
         * @return the production service, never {@code null}
         */
        @Bean
        DailyFeedWatermarkService dailyFeedWatermarkService(DailyFeedWatermarkRepository watermarks,
                Clock clock) {
            return new DailyFeedWatermarkService(watermarks, clock);
        }

        /**
         * Registers the production per-record unit of work every posting case below drives.
         *
         * <p>Assumptions: the argument order matches the production constructor exactly, and the
         * production job's own bean method composes the same seven collaborators. A divergence here
         * would make these cases assert a differently-composed unit from the one the nightly step runs,
         * which is the failure mode a test-owned copy of the sequence had.</p>
         *
         * @param accounts the account master the second write reaches; must not be {@code null}
         * @param ledger the posted-transaction master the third write reaches; must not be {@code null}
         * @param rejects the reject rows the rejected arm writes; must not be {@code null}
         * @param validation the rule deciding which conditions a record fails; must not be {@code null}
         * @param categoryBalances the accumulation rule the first write goes through; must not be
         *     {@code null}
         * @param watermark the consumed position the fourth write advances; must not be {@code null}
         * @param clock the fixed clock the posted row's processing stamp is minted from; must not be
         *     {@code null}
         * @return the production component, never {@code null}
         */
        @Bean
        PostingRecordUnitOfWork postingRecordUnitOfWork(AccountRepository accounts,
                TransactionRepository ledger, TransactionRejectRepository rejects,
                PostingValidationService validation, CategoryBalanceService categoryBalances,
                DailyFeedWatermarkService watermark, Clock clock) {

            return new PostingRecordUnitOfWork(accounts, ledger, rejects, validation, categoryBalances,
                    watermark, clock);
        }
    }
}
