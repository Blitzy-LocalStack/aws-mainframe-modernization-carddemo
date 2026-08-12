package com.carddemo.batch.config;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.service.BatchStepLedger;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Supplies the batch module's step infrastructure and the job-parameter contract every job runs under.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: the seven jobs in {@code com.carddemo.batch.job} share four things that are properties of
 * the MODULE rather than of any one job -- the page size a job walks its feed in, the names of the two
 * job parameters the orchestrator supplies, the validation that refuses a job started without them, and
 * the time source the durable step ledger stamps its rows with. Declaring any of the four seven times
 * would let one copy drift, and the copy that drifts is the one nobody re-derives. They are declared
 * here once. This class declares no job, no reader, no writer, no processor and no business rule: the
 * posting validation chain and the interest control break belong to {@code com.carddemo.batch.service},
 * and the units of work to {@code com.carddemo.batch.job}.</p>
 *
 * <p>Assumptions: this is the reactor's ONLY {@code BatchConfig}, and the build agrees rather than
 * merely asserting it. The migration plan scopes the class to this module at its section 0.4.1.2, and
 * {@code org.springframework.boot:spring-boot-starter-batch} is declared as a real dependency in exactly
 * one of the nine module descriptors, {@code services/batch-service/pom.xml}. The parent descriptor
 * names it once more, but only inside {@code dependencyManagement}, which pins a version without adding
 * the artifact to any module's path; every sibling service descriptor names it only inside a comment
 * recording its deliberate absence. The charter beside this file closes the package at three
 * configuration classes and records the same scoping.</p>
 *
 * <h2>The seven jobs this infrastructure serves, and the JCL each one migrates</h2>
 *
 * <p>Assumptions: {@code BatchApplication} fixes the command contract, whose {@code --job=} option
 * selects one of the seven tokens below and whose {@code --business-date=} option reaches a job as the
 * parameter named there. Neither option carries a baked default, because the orchestrator supplies both
 * as container command overrides. Two rows below are the ones most often assumed rather than checked, so
 * both are stated outright.</p>
 *
 * <ul>
 *   <li>{@code preflight-daily-transactions} migrates {@code app/cbl/CBTRN01C.cbl}, which has NO JCL
 *       driver among the 38 members of {@code app/jcl} -- no member names it at all. It is driven only
 *       by its integration test in the parity oracle, and it is migrated regardless.</li>
 *   <li>{@code post-transactions} migrates {@code app/cbl/CBTRN02C.cbl} under
 *       {@code app/jcl/POSTTRAN.jcl:23}, which reads {@code //STEP15 EXEC PGM=CBTRN02C} and carries nine
 *       data definitions and NO {@code PARM=}. That is why the posting job reads no business date, and
 *       why the paragraph on parameter identity below distinguishes reading the value from being
 *       identified by it.</li>
 *   <li>{@code calculate-interest} migrates {@code app/cbl/CBACT04C.cbl} under
 *       {@code app/jcl/INTCALC.jcl:22}, which reads
 *       {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} -- the one driver in the tree that injects
 *       a date.</li>
 *   <li>{@code backup-transactions} migrates {@code app/jcl/TRANBKP.jcl}, whose step gating is the
 *       subject of the return-code paragraph below.</li>
 *   <li>{@code combine-transactions} migrates {@code app/jcl/COMBTRAN.jcl:22-46}, a sort-merge of two
 *       generations into a third followed by a load.</li>
 *   <li>{@code export} migrates {@code app/cbl/CBEXPORT.cbl} under {@code app/jcl/CBEXPORT.jcl}.</li>
 *   <li>{@code import} migrates {@code app/cbl/CBIMPORT.cbl} under {@code app/jcl/CBIMPORT.jcl}.</li>
 * </ul>
 *
 * <h2>Why there is no {@code @EnableBatchProcessing} on this class</h2>
 *
 * <p>This is the single most valuable thing recorded in this file, because the annotation's absence
 * looks like an omission and is the opposite of one. Adding it would not enable batch; it would DISABLE
 * the auto-configuration that supplies the job repository, the job operator and the job explorer, and
 * the context would then fail to build for every value of {@code --job=}.</p>
 *
 * <p>Alternatives Considered: annotating this class, which is the reflex. It is rejected on evidence
 * read out of the resolved artifacts rather than from documentation.
 * {@code org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration}, in
 * {@code spring-boot-batch:4.1.0}, carries
 * {@code @ConditionalOnMissingBean(value = DefaultBatchConfiguration.class,
 * annotation = EnableBatchProcessing.class)} alongside
 * {@code @ConditionalOnClass(JobOperator.class)}. The {@code annotation} attribute is what makes this
 * decisive: the auto-configuration backs off as soon as ANY bean in the context carries
 * {@code @EnableBatchProcessing}, so annotating this class would remove the very beans the seven jobs
 * are built from. The supported path is the one taken here -- let the auto-configuration stand, and
 * receive {@code JobRepository} and {@code PlatformTransactionManager} as method parameters where a job
 * or a step needs them.</p>
 *
 * <p>Assumptions: the resolved versions are Spring Boot 4.1.0 with Spring Batch 6.0.4, and the ruling
 * above was verified against those exact artifacts rather than inherited from a Spring Batch 5 note.
 * Two checks establish it: the annotation set on the auto-configuration class was read directly from the
 * compiled artifact, and the module's own configuration metadata was read for the property surface
 * described further below. A reader changing the parent version should re-read both before assuming
 * this paragraph still holds.</p>
 *
 * <h2>Job selection is not owned here, and the framework's launcher must not run</h2>
 *
 * <p>Assumptions: {@code BatchApplication} owns job selection and the process exit status, and this
 * class introduces no competing selector, parses no argument and translates no exit code. The
 * interlock is a single property: {@code spring.batch.job.enabled} must be {@code false}. With all
 * seven job beans registered and no job name configured, the framework's own
 * {@code JobLauncherApplicationRunner} cannot determine which job was wanted, so leaving it enabled
 * would have the container start work nobody selected. Turning it off does NOT suppress exit-status
 * translation: the framework contributes {@code JobExecutionExitCodeGenerator} on the separate
 * condition that the context declares no generator of its own, so {@code BatchApplication} remains
 * free to supply its own.</p>
 *
 * <h2>The launch is synchronous, and no bean here makes it otherwise</h2>
 *
 * <p>Assumptions: the process exits when {@code main} returns, and the process exit status is the only
 * numeric channel the orchestrator reads, so work left running on another thread would be abandoned
 * while the state machine observed a success that had not happened. No task-executor bean is declared
 * here because the framework's own default is ALREADY synchronous:
 * {@code BatchAutoConfiguration$SpringBootBatchDefaultConfiguration} extends
 * {@code DefaultBatchConfiguration} and takes an {@code ObjectProvider<TaskExecutor>}, and
 * {@code DefaultBatchConfiguration.getTaskExecutor()} in {@code spring-batch-core:6.0.4} returns a new
 * {@code SyncTaskExecutor}. Declaring one would restate that default and add a second place for it to
 * disagree with itself.</p>
 *
 * <p>Trade-offs: this forgoes concurrent step execution. That is the correct trade here because the
 * baseline is strictly sequential -- {@code app/cbl/CBTRN02C.cbl:202-220} is one read loop -- and each
 * orchestrator state maps to one job invocation, so concurrency belongs to the state machine rather
 * than to this JVM. A contributor who later supplies a {@code @BatchTaskExecutor}-qualified executor
 * would be changing that, and would need to re-read this paragraph first.</p>
 *
 * <h2>Where the framework's own tables live, and why no prefix property is set</h2>
 *
 * <p>Assumptions: at the resolved version the whole {@code spring.batch} namespace is exactly two keys,
 * {@code spring.batch.job.enabled} and {@code spring.batch.job.name}. That was read from
 * {@code META-INF/spring-configuration-metadata.json} inside {@code spring-boot-batch:4.1.0}, and the
 * binding class agrees: {@code BatchProperties} declares one nested type, {@code Job}, carrying one
 * property, {@code name}. There is therefore no {@code spring.batch.jdbc} group at all, and neither a
 * table-prefix key nor a schema-initialization key EXISTS to be set. Writing either would bind to
 * nothing, be accepted in silence, and leave a reader believing a prefix had been pinned when it had
 * not.</p>
 *
 * <p>Assumptions: both settings' INTENT is nevertheless satisfied, by mechanisms that do exist. The
 * placement intent -- the framework's job-repository tables resolving into the one schema this module
 * owns -- is met because the default prefix is unqualified and {@code DataSourceConfig} puts
 * {@code batch} FIRST in the connection search path, which resolves it to exactly where
 * {@code db/migration/V1__batch.sql} creates those tables and their sequences. The
 * no-second-creator intent is met because this framework version contributes no batch schema
 * initialiser at all, and {@code spring.sql.init.mode} is pinned to {@code never} so no script
 * initialiser can become one.</p>
 *
 * <p>Trade-offs: correctness therefore rests on the ORDER of the search-path list in a sibling file
 * rather than on a prefix written here, and that coupling is accepted only because the alternative is
 * unavailable rather than because it is preferable. It is not left to chance: that file guards the
 * ordering at start-up and refuses a path whose first entry is not the owned schema, and this module
 * migrates that one schema and no other.</p>
 *
 * <h2>The business date identifies a job instance; the run identifier must not</h2>
 *
 * <p>Assumptions: exactly one of the two parameters is identifying. The business date is, because it is
 * what makes a job instance the run of a particular day -- the reference injects it as
 * {@code PARM='2022071800'} at {@code app/jcl/INTCALC.jcl:22} rather than reading a clock, which is the
 * property that makes a rerun reproduce its output byte for byte. The run identifier is NOT, and that is
 * load-bearing rather than incidental: were it identifying, every retry of a step would present a new
 * job instance, the framework would find no completed instance to refuse, and
 * {@code BatchApplication}'s already-complete branch -- the migrated form of resubmitting a job that has
 * already run -- would never be reached. A redriven Step Functions execution would then post the same
 * day's transactions twice.</p>
 *
 * <p>Trade-offs: the consequence is that the run identifier cannot distinguish two runs of one business
 * date at the framework's level, and it is not asked to. It distinguishes them where it matters, in the
 * durable generation reservation and in the step ledger, both of which key on it explicitly.</p>
 *
 * <p>Refactoring Rationale: there is no baseline checkpoint contract to carry across, and the absence
 * is factual rather than a judgement. Across the 38 members of {@code app/jcl} the only
 * {@code RESTART=} anywhere is {@code app/jcl/DEFGDGD.jcl:2}, which reads
 * {@code //*  RESTART=STEP30} and is a COMMENT rather than a live parameter, and no member carries a
 * {@code CHKPT=} operand at all. The baseline restarts a chain by resubmitting it; the target adds
 * orchestrator redrive over a durable step ledger. That is a documented improvement rather than a port
 * of an existing mechanism, and describing it as a port would misattribute it.</p>
 *
 * <p>Alternatives Considered: a run-id incrementer that simply bumps a counter on each launch. It was
 * rejected because it would make the run identifier part of the instance identity by another route, so
 * every redrive would present a NEW job instance and resumption would be lost entirely -- the same
 * failure the non-identifying flag above exists to prevent.</p>
 *
 * <p>Assumptions: both parameters are required for all seven jobs even though only five of them READ the
 * business date. Five do: the interest, backup and combine jobs read it directly, and the export and
 * import round-trips receive it through the step body described on {@link LedgerGuardedStep}. Two do
 * not, and both have a baseline reason -- {@code post-transactions}, whose driver at
 * {@code app/jcl/POSTTRAN.jcl:23} carries no {@code PARM=}, and
 * {@code preflight-daily-transactions}, which has no driver at all. Requiring the parameter uniformly
 * is nevertheless what gives every job an instance identity per business date, so the already-complete
 * branch protects those two as well as the five that consume the value.</p>
 *
 * <p>Alternatives Considered: making the requirement per job, so the two jobs that never read the value
 * would not have to be given one. Declined because it would leave those two with an instance identity
 * that ignores the day they ran for -- and that identity is precisely what a redrive depends on, so a
 * second run of a different day's posting would resume the first day's instance. It would also move one
 * shared validation bean into seven per-job copies, six of which would then be free to drift.</p>
 *
 * <h2>The graded return code, and the two places it must not leak</h2>
 *
 * <p>Assumptions: a reject is a soft warn rather than a failure, and that grading originates in the
 * baseline. {@code app/cbl/CBTRN02C.cbl:228} displays {@code 'TRANSACTIONS REJECTED  :'} with its
 * reject count, its line 229 reads {@code IF WS-REJECT-COUNT > 0} and its line 230 reads
 * {@code MOVE 4 TO RETURN-CODE}. A run that produced rejects and reported clean would be a parity
 * difference rather than a tidier result, so the grade is carried to the step's exit status by the
 * listener nested below and translated to a process status by {@code BatchApplication}.</p>
 *
 * <p>Assumptions: a JCL {@code COND} is a SKIP predicate while an orchestrator choice is a RUN
 * predicate, so the sense inverts on the way across and the inversion is easy to lose.
 * {@code app/jcl/TRANBKP.jcl:51} reads {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}, meaning "skip this
 * step when 4 is less than the preceding return code" -- so the step runs when the code is 4 or lower,
 * and the target predicate is therefore {@code rc <= 4} rather than its negation. That is exactly the
 * warn tier surviving into a downstream decision. It must not be confused with
 * {@code app/jcl/TRANREPT.jcl:47}, whose {@code INCLUDE COND=(...)} sits inside a sort step and selects
 * RECORDS rather than gating a step; that form becomes a SQL {@code WHERE} clause and never a choice
 * state. The two share a keyword and mean different things.</p>
 *
 * <p>Trade-offs: the graded 0/4/8 domain reaches the container's process status and the durable ledger
 * and stops there. It is deliberately kept out of every build and test gate, because a Java assertion
 * is binary and folding a graded scale into one would either read a legitimate warn as a failure or
 * hide a real failure inside a warn. The graded rubric belongs to the parity oracle under
 * {@code tests/}, whose runners aggregate it; nothing in this module's own build consumes it.</p>
 *
 * <p>Assumptions: no listener, converter or log encoder that reformats, filters or wraps a job's
 * standard output is installed here, and none may be. Two stdout artifacts are compared by the golden
 * masters and must survive byte for byte: the message at {@code app/cbl/CBTRN02C.cbl:228}, which
 * carries TWO spaces before its colon, and {@code app/cbl/CBACT04C.cbl:193}, whose
 * {@code DISPLAY TRAN-CAT-BAL-RECORD} emits exactly fifty bytes for every input row. A structured
 * encoder would re-render both and the comparison would fail on formatting rather than on
 * behaviour.</p>
 *
 * <h2>No resilience library, and no blanket method-level retry</h2>
 *
 * <p>Alternatives Considered: an external resilience library was evaluated and is not adopted, and
 * {@code services/batch-service/pom.xml} declares none. The decision is enforced rather than merely
 * recorded: the parent descriptor's enforcer configuration bans {@code io.github.resilience4j} and
 * {@code org.springframework.retry:spring-retry} outright, so adding either fails the build rather than
 * a review. Spring Framework 7, which arrives inside the Spring Boot parent, moved retry into the
 * framework core -- {@code @Retryable}, {@code @ConcurrencyLimit} and a programmatic retry policy --
 * activated by {@code @EnableResilientMethods} rather than by the older {@code @EnableRetry}, and
 * carrying an attribute named {@code maxRetries} rather than the older spelling, where total attempts
 * are one plus that value. The superseded annotation name is recorded alongside the current one because
 * reaching for it, and for the older attribute spelling, is the likely error.</p>
 *
 * <p>Trade-offs: method-level retry is nevertheless NOT enabled anywhere in this class, and the
 * capability existing in the core is not a reason to switch it on. The durable retry tier is the
 * orchestrator's per-state retry with exponential backoff plus redrive, described in the migration
 * plan's section 0.4.1.7, and the message tier is queue redelivery with a dead-letter queue. A retry
 * wrapped around a step body would multiply those attempts non-obviously and, because a step body here
 * runs inside one transaction, could re-enter work whose partial effects were already rolled back. A
 * circuit breaker is omitted for a simpler reason: this module makes no synchronous outbound service
 * call, so there is nothing for one to open against.</p>
 *
 * <h2>Metrics reach this module from the shared kernel, not from here</h2>
 *
 * <p>Assumptions: no meter registry customiser is declared here because the shared kernel already
 * contributes one, and that was verified rather than assumed:
 * {@code services/common-lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * names {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, and an auto-configuration import
 * is honoured without a component scan reaching the class. An explicit {@code @Import} would therefore
 * register the same configuration a second time, so none is written.</p>
 *
 * <p>Assumptions: the common-tag canon is exactly {@code service}, {@code environment} and
 * {@code version} -- no fourth tag, no renamed tag, none omitted -- with values resolved from
 * configuration rather than written into source. Meter cardinality is kept low deliberately: nothing in
 * this module tags a meter by account identifier, card number or transaction identifier, and no primary
 * account number, card verification value, national identifier or government-issued identifier may
 * appear in a meter name, description, tag key or tag value.</p>
 *
 * <h2>The property-key contract this class depends on</h2>
 *
 * <p>Assumptions: the keys below are the ones this class's behaviour depends on, each with the value it
 * requires, because a key named without its required value is not an actionable contract. They are
 * supplied by {@code services/batch-service/src/main/resources/application.yml} and its profiles. No
 * secret, endpoint, connection string, queue identifier or bucket name is named here or defaulted here;
 * those reach the task as infrastructure outputs through parameter and secret storage.</p>
 *
 * <ul>
 *   <li>{@code spring.batch.job.enabled} -- REQUIRED, and must be {@code false}, so that the
 *       framework's startup launcher does not race the command contract for job selection.</li>
 *   <li>{@code spring.batch.job.name} -- must remain UNSET. Setting it would give the task a second,
 *       silent source of job selection able to disagree with the {@code --job=} argument.</li>
 *   <li>{@code spring.sql.init.mode} -- REQUIRED, and must be {@code never}, so no script initialiser
 *       becomes a second creator of the tables schema migration owns.</li>
 *   <li>{@code carddemo.batch.datasource.search-path} -- the ordered schema list, whose FIRST entry
 *       must be the owned {@code batch} schema; that ordering is what resolves the framework's
 *       unqualified table prefix into this module's own schema. It is read by
 *       {@code DataSourceConfig}, and named here because this class's table placement depends on
 *       it.</li>
 *   <li>{@link BatchApplication#RUN_ID_VARIABLE} -- the container environment variable carrying the
 *       orchestrator execution identifier, read through {@link #ledgerGuardedStep}. It is the one key
 *       here with a fallback, and the reason is given at that method.</li>
 * </ul>
 *
 * <p>Assumptions: this class reads NO key of its own for the feed page size below. That is a
 * compile-time constant rather than a bound property, and the reason is recorded with it.</p>
 */
@Configuration
public class BatchConfig {

    /**
     * The number of feed rows a job fetches per keyset page while walking its input, one hundred.
     *
     * <p>Assumptions: this bounds a READ, not a commit. Every step in this module is a single
     * transactional tasklet that returns {@link RepeatStatus#FINISHED} from its first invocation, so one
     * step invocation is one transaction and the whole pass commits exactly once; there is no
     * commit-per-page boundary anywhere in this module for this value to set. It is consumed as the page
     * limit of a keyset query -- {@code PostTransactionsJob} and
     * {@code PreflightDailyTransactionsJob} each pass it as the {@code Limit} of a
     * greater-than-last-key query -- so what it governs is how many rows are materialised at a time
     * inside that one transaction.</p>
     *
     * <p>Refactoring Rationale: this description previously presented the value as the number of records
     * a chunk-oriented step processes before committing, and reasoned about the window a failure
     * discards and the locks a page holds as though each page committed. That reading does not match the
     * delivered step shape and understated the transaction's true extent, which is the entire pass. The
     * distinction is not cosmetic: a reader who believed a page committed could reduce this value
     * expecting finer restart granularity and would get none, or could assume a partially posted run is
     * observable when it is not. The charter beside this file already recorded the tasklet shape, so the
     * two now agree.</p>
     *
     * <p>Assumptions: the atomicity that matters is a property of the step, not of this number, and it
     * is what the reference requires. {@code app/cbl/CBTRN02C.cbl:424} opens
     * {@code 2000-POST-TRANSACTION.}; its line 440 performs {@code 2700-UPDATE-TCATBAL}, line 441
     * performs {@code 2800-UPDATE-ACCOUNT-REC} and line 442 performs
     * {@code 2900-WRITE-TRANSACTION-FILE}, with the paragraph bodies at lines 467, 545 and 562. Those
     * three writes span two schemas and commit together. Changing this value cannot split them, and no
     * change to it should ever be made in the belief that it could: the atomicity is the step's single
     * transaction, and the repository integration test that asserts it is the executable statement of
     * that.</p>
     *
     * <p>Trade-offs: a hundred was chosen over one and over a thousand. One would issue a query per row
     * and make the walk's duration dominated by round trips rather than by work; a thousand would hold
     * ten times as many hydrated entities in the persistence context at once, which on a long feed is
     * where heap pressure and flush cost actually come from. A hundred keeps both small. The accepted
     * cost of the single-transaction shape this page size sits inside is that a long run holds one
     * transaction open for its duration, which the charter beside this file records as the deliberate
     * price of keeping a redrive idempotent.</p>
     *
     * <p>Trade-offs: raising this value buys less on the reject path than on the posting path, and the
     * ceiling is concrete rather than a guess. {@code TransactionReject} generates its key with
     * {@code GenerationType.IDENTITY}, and an identity key must be read back from the database for each
     * row, so the provider cannot group those inserts however large a page is fetched. The configured
     * statement-grouping size therefore bounds what a larger page can recover there, and a page sized
     * for the reject path alone would be sized against a limit it cannot pass.</p>
     *
     * <p>Alternatives Considered: exposing this as a bound property under a
     * {@code carddemo.batch.} prefix so an operator could retune it per environment without a rebuild.
     * Declined at this tree state for a specific reason rather than on principle: the two consumers read
     * it as a compile-time constant, so a bound key would have to be threaded into both of them to
     * govern anything, and a key added without that threading would bind to nothing and be accepted in
     * silence -- leaving a reader believing a page size was tunable when it was not. A constant that is
     * honestly one edit and one review is preferable to a property that only appears to be
     * configuration.</p>
     */
    public static final int CHUNK_SIZE = 100;

    /**
     * The name of the non-identifying job parameter carrying the orchestrator execution identifier.
     *
     * <p>Assumptions: the name is spelled here and consumed by {@link #runIdOf(ChunkContext)}, so no job
     * spells it. {@code BatchApplication} supplies it from the {@code CARDDEMO_BATCH_RUN_ID} container
     * variable the state machine injects.</p>
     */
    public static final String RUN_ID_PARAMETER = "runId";

    /**
     * Refuses a job started without both of the parameters every job in this module requires.
     *
     * <p>Assumptions: validation is a bean shared by all seven jobs rather than a check written into each
     * one, because the failure it prevents is identical in all seven and its cost is identical too: a job
     * missing the business date would run against whatever a job reading a clock would produce, which is
     * an output no rerun reproduces. Refusing at start-up turns that into a failure the orchestrator
     * catches on state entry rather than a plausible-looking dataset nobody re-derives.</p>
     *
     * <p>⚠️ Refactoring Rationale: this rationale used to claim that declaring both names REQUIRED and
     * neither optional meant "a parameter this module does not name at all is rejected as well as a
     * missing one". <b>That is the opposite of what the framework does.</b>
     * {@link DefaultJobParametersValidator} compares the supplied keys against the union of the required
     * and optional sets ONLY when the optional set is non-empty; with an empty optional set it checks
     * nothing but the presence of the required keys, so an unrecognised parameter passes validation
     * silently. The claim is corrected rather than made true, because making it true would need a
     * composite validator carrying a second rule, and the failure it would catch -- an orchestration
     * change that has not reached this module -- has no way to produce a wrong OUTPUT: every job reads
     * its inputs by name and an unread parameter affects nothing it computes. Trade-offs: the cost is
     * that such a drift is discovered by reading the state machine rather than by a refused launch, which
     * is accepted; what is not acceptable is a stated guarantee that does not hold, because a reader
     * relying on it would stop looking. Assumptions: the two required names ARE enforced, and a launch
     * missing either is refused before the job instance is created -- which is the guarantee the
     * paragraph above depends on and the one this bean actually delivers.</p>
     *
     * @return the validator every job in this module is built with, never {@code null}
     */
    @Bean
    public JobParametersValidator carddemoJobParametersValidator() {
        return new DefaultJobParametersValidator(
                new String[] {BatchApplication.BUSINESS_DATE_PARAMETER, RUN_ID_PARAMETER},
                new String[] {});
    }

    /**
     * Reads the injected business date out of the parameters the running step was started with.
     *
     * <p>Assumptions: the value is read from the step's own execution rather than from a field or a
     * step-scoped bean, so a job holds no state between invocations and reads no clock. That is the
     * property the reference's injected parameter has and the reason a rerun reproduces its output. The
     * reference declares it as a linkage parameter at {@code app/cbl/CBACT04C.cbl:175-178}, where
     * {@code 05  PARM-DATE           PIC X(10).} sits under {@code 01  EXTERNAL-PARMS.}, and receives it
     * at line 180 through {@code PROCEDURE DIVISION USING EXTERNAL-PARMS.}</p>
     *
     * <p>Assumptions: two layouts of this parameter are accepted and they are consumed DIFFERENTLY, and
     * the difference is named at the point the value is obtained precisely so nobody unifies it. The
     * token this method returns is whichever ten-character layout the caller supplied -- the ISO-shaped
     * surface the command contract accepts, or the compact form the reference's own driver injects,
     * {@code app/jcl/INTCALC.jcl:22} passing {@code PARM='2022071800'}.</p>
     *
     * <p>Assumptions: a generated TRANSACTION IDENTIFIER concatenates the token VERBATIM and must not
     * normalise it. {@code app/cbl/CBACT04C.cbl:476-480} strings the parameter and a six-digit suffix
     * {@code DELIMITED BY SIZE}, which copies all ten characters of {@code PARM-DATE PIC X(10)} as
     * supplied -- there is no normalisation anywhere in that program -- so the compact parameter yields
     * {@code 2022071800000001} and a separated one yields {@code 2024-01-15000001}. The second is not a
     * guess: it is the committed golden master, at bytes 1 to 16 of
     * {@code tests/golden/interest/happy_path/transact.expected}, documented at line 161 of that
     * scenario's README. {@code InterestCalculationService} therefore reads {@link BusinessDate#token()},
     * and a normalisation applied there would break parity on the one job the oracle pins byte for
     * byte.</p>
     *
     * <p>Assumptions: a DATASET GENERATION KEY normalises the token and must not carry it verbatim,
     * because an object key is listed and compared as text, so two spellings of one day would be two
     * prefixes and a run started one way would not find the generation a run started the other way
     * wrote. {@link BusinessDate#identifierPrefix()} is the one place that conversion is expressed and
     * the export and import jobs are its only callers.</p>
     *
     * <p>Refactoring Rationale: this note previously required that "identifier construction must go
     * through it and never through the raw token", without qualifying which identifier. Followed
     * literally it breaks the interest golden, because the reference's identifier IS the verbatim
     * concatenation; and it was not followed by the code, which read the raw token throughout. A rule
     * stated in a docstring that the implementation contradicts is worse than no rule -- a later reader
     * resolves the disagreement in whichever direction the docstring points, which here is the direction
     * that fails the oracle. The two consumers are now named separately with the evidence for each.</p>
     *
     * @param context the chunk context the framework passes into a tasklet; must not be {@code null}
     * @return the business date the run was started for, never {@code null}
     * @throws IllegalStateException if the parameter is absent, which the validator makes unreachable
     *     through the supported entry point and which therefore names a job started some other way
     * @throws IllegalArgumentException if the parameter is present but is not a token the business date
     *     accepts
     */
    public static BusinessDate businessDateOf(ChunkContext context) {
        return new BusinessDate(
                requiredParameter(context, BatchApplication.BUSINESS_DATE_PARAMETER));
    }

    /**
     * Reads the orchestrator execution identifier out of the parameters the running step was started with.
     *
     * @param context the chunk context the framework passes into a tasklet; must not be {@code null}
     * @return the run identifier, never {@code null} and never blank
     * @throws IllegalStateException if the parameter is absent or blank
     */
    public static String runIdOf(ChunkContext context) {
        return requiredParameter(context, RUN_ID_PARAMETER);
    }

    /**
     * Reads one required string parameter out of a running step's job parameters.
     *
     * @param context the chunk context the framework passes into a tasklet; must not be {@code null}
     * @param name the parameter name to read; must not be {@code null}
     * @return the parameter's value, never {@code null} and never blank
     * @throws IllegalStateException if the parameter is absent or holds nothing but whitespace
     */
    private static String requiredParameter(ChunkContext context, String name) {
        JobParameters parameters =
                context.getStepContext().getStepExecution().getJobParameters();
        String value = parameters.getString(name);

        // WHY : Assumptions: blank is rejected as well as absent, because an environment variable
        //       exported with an empty value resolves successfully to the empty string. Without this the
        //       step would run with an empty business date, and the coordinate type's own width check
        //       would then report a malformed token rather than the variable nobody set.
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("job parameter '" + name + "' is required and was not"
                    + " supplied; a job in this module is startable only through the module's own entry"
                    + " point, which supplies both parameters");
        }

        return value;
    }

    /**
     * Supplies the time source the durable step ledger stamps its rows with.
     *
     * <p>Assumptions: this bean is what lets the context be built at all. {@code BatchStepLedger} is a
     * component with a {@code (BatchRunRepository, Clock)} constructor, and the shared kernel's single
     * auto-configuration contributes the correlation filter, the meter filter, the money codec and the
     * error advice -- no clock. Without this bean every value of {@code --job=} fails before any job is
     * looked up. Assumptions: no batch-service test had loaded a context before the jobs landed, which
     * is why a missing bean on the only path that needs it stayed invisible; the job registration
     * census now loads one.</p>
     *
     * <p>Alternatives Considered: the system default zone, and reading the clock at each call site
     * instead of injecting one. The zone is rejected because a ledger row is compared against rows
     * written by other tasks in other containers, and a started-at that is only interpretable alongside
     * the zone of the host that wrote it cannot be ordered against them; this is the same posture the
     * timestamp formatter in the shared kernel takes. Reading the clock at each call site is rejected
     * because a test could then not fix time, and a ledger assertion would have to compare against
     * whatever the wall clock said.</p>
     *
     * <p>Assumptions: this is a record-stamping time source and is NOT a licence to read a clock for
     * the business date. That value arrives only as a job parameter, for the reason recorded on the
     * parameter contract above.</p>
     *
     * @return a UTC clock; never {@code null}
     */
    @Bean
    public Clock batchClock() {
        return Clock.systemUTC();
    }

    /**
     * Supplies the prebuilt ledger-guarded step the dataset round-trip jobs turn their work into.
     *
     * <p>Assumptions: this builder is injected by TWO of the seven jobs -- the export and import
     * round-trips -- and not by all of them, and the split is deliberate rather than partial adoption.
     * Those two contribute a whole-pass body and nothing else, so handing the entire step over is the
     * smaller surface. The remaining five build their own tasklet with {@code StepBuilder} because their
     * bodies grade themselves through the {@code StepContribution} the framework passes in, which a
     * prebuilt step does not expose to them. Both routes reach the same place: one ledger row per
     * {@code (runId, stepName)} pair and the same graded exit status, by the two mechanisms described on
     * {@link LedgerGuardedStep} and on the return-code paragraph of this class.</p>
     *
     * @param ledger the durable step ledger providing redrive idempotency; must not be
     *     {@code null}
     * @param runId the orchestrator's execution identifier, which scopes a ledger row so that
     *     one night's run of a step is a different row from the previous night's; must not be
     *     {@code null} or blank
     * @return the shared ledger-guarded step builder; never {@code null}
     * @throws NullPointerException if either argument is {@code null}, propagated from the builder's
     *     own constructor
     * @throws IllegalArgumentException if the resolved run identifier is blank, which fails context
     *     start-up rather than letting a step record ledger rows under an empty key
     */
    @Bean
    public LedgerGuardedStep ledgerGuardedStep(
            BatchStepLedger ledger,
            // WHY : Assumptions: the variable name is taken from BatchApplication's own public constant rather
            //       than spelled a second time, so the environment name this module reads cannot
            //       drift between the two places that read it. The default matches that class's
            //       own fallback exactly, so a task started outside an orchestrator still records
            //       ledger rows under a stable identifier instead of failing to start.
            // WHY : Trade-offs: a default means a developer running two jobs locally without
            //       setting the variable shares one run identifier, so the second job's step is
            //       skipped as already complete. That is the correct behaviour for the same step
            //       and a surprise only across different ones; the alternative -- no default --
            //       makes the module unusable outside an orchestrator, which is worse for the
            //       runbook and for every test.
            @Value("${" + BatchApplication.RUN_ID_VARIABLE + ":unorchestrated}") String runId) {

        return new LedgerGuardedStep(ledger, runId);
    }

    /**
     * Builds the step shape this module uses: a single transactional tasklet, guarded by the durable
     * ledger, whose graded return code becomes the step's exit status.
     *
     * <p>Instances are obtained from {@link BatchConfig#ledgerGuardedStep}; the constructor is not
     * public because the run identifier it binds is a property of the running task rather than
     * something a caller chooses.</p>
     *
     * <p>Assumptions: the tasklet shape is the load-bearing choice here, not an implementation detail.
     * A chunk-oriented read-process-write step commits per chunk, which would make a partially posted
     * run observable and a redrive non-idempotent; the reference commits its three writes as one unit
     * of work at {@code app/cbl/CBTRN02C.cbl:440-442}, so a single transactional tasklet is what
     * preserves that and the ledger row is what makes a redrive of an already-completed step a no-op.
     * Trade-offs: the accepted cost is that a very large run holds one transaction open for its
     * duration. The charter beside this file records the same decision, and the two agree
     * deliberately.</p>
     */
    public static final class LedgerGuardedStep {

        /**
         * Execution-context key under which a completed body's graded return code is parked for
         * the exit-status listener to read.
         *
         * <p>Alternatives Considered: a field on this builder holding the last graded outcome. Rejected
         * because one builder instance is shared by every job that injects it, so that field would be
         * written by whichever step ran last and read by whichever listener fired next. The execution
         * context is scoped to the single step execution that wrote it, which is the only scope that
         * makes the value unambiguous.</p>
         */
        public static final String RETURN_CODE_KEY = "carddemo.batch.returnCode";

        private final BatchStepLedger ledger;

        private final String runId;

        /**
         * Binds the ledger and the run identifier this builder's steps record against.
         *
         * @param ledger the durable step ledger; must not be {@code null}
         * @param runId the orchestrator execution identifier; must not be {@code null} or blank
         * @throws IllegalArgumentException when {@code runId} is blank
         */
        LedgerGuardedStep(BatchStepLedger ledger, String runId) {
            this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
            Objects.requireNonNull(runId, "runId must not be null");
            if (runId.isBlank()) {
                throw new IllegalArgumentException("runId must not be blank");
            }
            this.runId = runId;
        }

        /**
         * Reports the orchestrator execution identifier this builder's steps record against.
         *
         * @return the run identifier; never {@code null} or blank
         */
        public String runId() {
            return this.runId;
        }

        /**
         * Wraps a unit of work as a ledger-guarded, exit-status-graded step.
         *
         * @param stepName the ledger step name, which is also the Spring Batch step name; must not
         *     be {@code null} or blank
         * @param jobName the job the step belongs to, forwarded to the ledger so that a published
         *     failure names its job; must not be {@code null}
         * @param jobRepository the batch job repository the step records its execution in; must
         *     not be {@code null}
         * @param transactionManager the transaction manager whose boundary the body runs inside;
         *     must not be {@code null}
         * @param body the unit of work, which receives the business date the orchestrator supplied
         *     and returns its graded outcome; must not be {@code null}
         * @return the built step; never {@code null}
         * @throws NullPointerException when any argument is {@code null}
         * @throws IllegalArgumentException when {@code stepName} is blank
         */
        // WHY : Alternatives Considered: resolving the job inside the tasklet from the running step's
        //       own context, through BatchJobName.resolve on the framework's job name, rather than
        //       taking it as a parameter. Rejected because that resolution RAISES on any name outside
        //       the seven, so a step built through this method and launched under another job name --
        //       which is what a focused step test does -- would fail inside the ledger on a diagnostic
        //       about a token rather than on the behaviour under test. Taking it as a parameter also
        //       means a caller states which job its step belongs to at the point it already states the
        //       step name, so the two cannot come to disagree.
        public Step build(
                String stepName,
                BatchJobName jobName,
                JobRepository jobRepository,
                PlatformTransactionManager transactionManager,
                StepBody body) {

            Objects.requireNonNull(stepName, "stepName must not be null");
            Objects.requireNonNull(jobName, "jobName must not be null");
            Objects.requireNonNull(jobRepository, "jobRepository must not be null");
            Objects.requireNonNull(transactionManager, "transactionManager must not be null");
            Objects.requireNonNull(body, "body must not be null");
            if (stepName.isBlank()) {
                throw new IllegalArgumentException("stepName must not be blank");
            }

            // WHY : Assumptions: returning FINISHED from the first invocation is what makes the step ONE
            //       transaction rather than a repeated one. A tasklet that returned CONTINUABLE would be
            //       re-invoked in a fresh transaction each time, which would commit the pass in pieces
            //       and make a partially posted run observable -- the state the reference never exposes,
            //       because app/cbl/CBTRN02C.cbl:440-442 commits its three writes together.
            Tasklet tasklet = (contribution, chunkContext) -> {
                BusinessDate businessDate = businessDateOf(chunkContext);
                BatchStepLedger.StepOutcome outcome =
                        this.ledger.runStep(this.runId, stepName, jobName,
                                () -> body.run(businessDate));
                chunkContext.getStepContext().getStepExecution().getExecutionContext()
                        .putInt(RETURN_CODE_KEY, outcome.returnCode().numericValue());
                return RepeatStatus.FINISHED;
            };

            // WHY : Assumptions: both collaborators arrive as parameters precisely because this module
            //       declares neither. The repository comes from the batch auto-configuration this class
            //       is careful not to disable, and the transaction manager is the single
            //       non-distributed one the sibling data-source configuration establishes; taking them
            //       as arguments is what keeps a second of either from being introduced here.
            return new StepBuilder(stepName, jobRepository)
                    .tasklet(tasklet, transactionManager)
                    .listener(new ReturnCodeExitStatusListener())
                    .build();
        }

        /**
         * Reads the business date the orchestrator supplied as a job parameter.
         *
         * <p>Assumptions: the date is read from the job parameters and from nowhere else, because
         * injecting it is what makes a rerun reproducible and reproducibility is what makes a
         * golden-master comparison possible at all. {@code app/jcl/INTCALC.jcl:22} establishes the
         * contract, passing {@code PARM='2022071800'} rather than letting the program read a clock,
         * and a step that consulted a clock could not be compared against a recorded output.</p>
         *
         * @param chunkContext the running step's context; must not be {@code null}
         * @return the business date; never {@code null}
         * @throws IllegalStateException when the parameter is absent or is not a string, which
         *     means the step was started by something other than the documented command contract
         */
        private static BusinessDate businessDateOf(ChunkContext chunkContext) {
            Map<String, Object> parameters =
                    chunkContext.getStepContext().getJobParameters();
            Object supplied = parameters.get(BatchApplication.BUSINESS_DATE_PARAMETER);
            if (!(supplied instanceof String token)) {
                throw new IllegalStateException("job parameter '"
                        + BatchApplication.BUSINESS_DATE_PARAMETER + "' is absent or is not a"
                        + " string, so no business date can be resolved; a job must be started"
                        + " through the documented --business-date= option");
            }
            return new BusinessDate(token);
        }

        /**
         * A unit of work a job contributes, expressed so that the business date can only reach it
         * as a parameter.
         */
        @FunctionalInterface
        public interface StepBody {

            /**
             * Runs the unit of work.
             *
             * @param businessDate the injected business date; never {@code null}
             * @return the graded outcome, which becomes the step's exit status; must not be
             *     {@code null}
             */
            BatchReturnCode run(BusinessDate businessDate);
        }

        /**
         * Promotes a body's graded return code to the step's exit status.
         *
         * <p>⚠️ Refactoring Rationale: the rationale here used to reject
         * {@code StepContribution.setExitStatus} on the ground that the tasklet step folds a
         * contribution's status in with {@code ExitStatus.and}, "whose severity ranking maps every code
         * beginning {@code COMPLETED} to one level", so that "the custom warn code therefore compares
         * equal to the incumbent {@code COMPLETED} and the incumbent is kept" and the grade "would be
         * discarded without any error". <b>The first clause is right and the conclusion drawn from it is
         * wrong.</b> The severity ranking does put {@code COMPLETED} and
         * {@code COMPLETED_WITH_WARNINGS} at one level -- and precisely because they tie on severity,
         * {@code and} falls through to comparing the two exit CODES lexically, where
         * {@code "COMPLETED"} sorts before {@code "COMPLETED_WITH_WARNINGS"}, so the incumbent is
         * REPLACED and the warn code is adopted. It is adopted in either order. So the contribution
         * route would not have discarded the grade, and a reader who believed this paragraph would have
         * believed the framework loses a value it in fact keeps.</p>
         *
         * <p>Assumptions: the ranking does still dominate in the direction that matters for safety. A
         * step that failed carries {@code FAILED}, which outranks both completed codes on severity, so
         * the fold keeps {@code FAILED} whichever side the warn code is on -- a failed run cannot be
         * reported as a run that merely had rejects by this mechanism. The same holds for
         * {@code STOPPED} and {@code NOOP}, both of which outrank the completed level.</p>
         *
         * <p>Alternatives Considered: {@code StepContribution.setExitStatus}, now that it is known to
         * work. It is still declined, for a reason that does not depend on the fold at all. The warn
         * code's survival through {@code and} rests on a lexical comparison between two framework
         * constants that tie on severity -- an implementation detail of a private ranking method, not a
         * published contract -- and this module's exit status is the value the orchestration choice
         * predicate reads to decide whether a night's posting had rejects. Resting a parity-critical
         * exit code on which of two strings sorts first is a dependency worth not having. A listener
         * that RETURNS a status replaces it outright, so this route produces the same code without
         * consulting the ranking, and it additionally gates on the FINAL step status -- something a
         * contribution applied mid-step cannot do, because at that moment the step has not finished.
         * Trade-offs: the cost is one small class instead of one method call. The behaviour is covered
         * end to end by {@code PostTransactionsJobTest}, which drives a real framework execution and
         * asserts the warn code for a rejected record and its absence for a posted one.</p>
         */
        private static final class ReturnCodeExitStatusListener implements StepExecutionListener {

            /**
             * Replaces the exit status when, and only when, the body graded itself as a soft warn.
             *
             * @param stepExecution the completed step execution; must not be {@code null}
             * @return the warn exit status when the body returned one, or {@code null} to leave
             *     the framework's own status in place
             */
            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                // WHY : Assumptions: a step that did not complete already carries a failure status that
                //       maps to the hard-failure exit code, and overwriting it with a warn would report
                //       a failed run as a run that merely had rejects. This gate is what makes the
                //       listener independent of the framework's severity ranking rather than merely
                //       agreeing with it -- the class note above records why that independence is worth
                //       having for a value the orchestration choice predicate reads.
                if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
                    return null;
                }
                int recorded = stepExecution.getExecutionContext()
                        .getInt(RETURN_CODE_KEY, BatchReturnCode.CLEAN.numericValue());
                return BatchReturnCode.fromNumericValue(recorded) == BatchReturnCode.SOFT_WARN
                        ? new ExitStatus(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS)
                        : null;
            }
        }
    }
}
