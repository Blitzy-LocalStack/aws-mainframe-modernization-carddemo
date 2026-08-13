package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.DatasetGeneration.GenerationReference;
import com.carddemo.batch.mapper.TransactionRecordMapper;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DatasetGenerationService;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.ZonedDecimalCodec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Settles the continuation gate, the tolerated deletion, the omitted wipe and the emitted image of
 * {@code BackupTransactionsJob}.
 *
 * <h2>Purpose, and why this file is a specification rather than a transcription</h2>
 *
 * <p>Purpose: this is the structural-tier test for state six of the nightly chain, the migration of
 * {@code app/jcl/TRANBKP.jcl}. It owns four subjects and no others: the condition-code inversion that
 * job carries, the tolerated-not-found semantics of its deletion step, the deliberate omission of its
 * wipe-and-re-create pair, and the byte form of the backup image it produces.</p>
 *
 * <p>Assumptions: <b>no COBOL program stands behind this job</b>, and that is what separates this
 * file from every sibling in this package. {@code app/jcl/TRANBKP.jcl:23} reads
 * {@code //STEP05R EXEC PROC=REPROC} with {@code CNTLLIB=AWS.M2.CARDDEMO.CNTL} on line 24 -- a
 * catalogued procedure resolved through the library named at line 19 -- and the job's other two steps
 * at lines 37 and 51 are both {@code IDCAMS} utility invocations. There is therefore no paragraph to
 * transcribe and no program listing to cite a rule against, so a case here pins a job-control line or
 * a copybook line instead. Every sibling test in this package can point at a program; this one
 * cannot, and each Javadoc below names the specific line it does point at.</p>
 *
 * <p>Trade-offs: because no golden output exists for this dataset anywhere in the repository, <b>this
 * file is the specification of the backup image rather than a check against one</b>. The fixture root
 * for this module, {@code services/batch-service/src/test/resources/fixtures}, holds exactly four
 * domains -- {@code posting}, {@code interest}, {@code export} and {@code preflight} -- and no backup
 * domain; the parity oracle's own trees under {@code tests/fixtures} and {@code tests/golden} hold no
 * backup directory either. Every input below is consequently constructed in code, in the same posture
 * the generation resolver's own tier test takes. The cost accepted is that a wrong expectation here
 * becomes the contract rather than being caught by an independent artifact, which is why the field
 * geometry is asserted against the registry descriptor and the copybook rather than against literals
 * chosen here.</p>
 *
 * <p>Assumptions: constructing the inputs in code is an ADVANTAGE for this particular job rather than
 * merely an unavoidable substitute. This job copies rows that were already persisted and reads no
 * clock of its own, so with the timestamps chosen in the input the emitted image is fully
 * deterministic and needs <b>no normalisation at all</b> -- unlike the posting and interest images,
 * whose run-generated stamps have to be masked before any comparison. The image-independence case
 * below asserts exactly that property rather than assuming it.</p>
 *
 * <h2>The four subjects, and the evidence each rests on</h2>
 *
 * <h3>One: the condition-code inversion, in the one place the baseline demonstrates it</h3>
 *
 * <p>Refactoring Rationale: a job-control condition is a SKIP predicate and an orchestration choice is
 * a RUN predicate, so the sense inverts exactly once, and getting it backwards inverts which runs
 * proceed. {@code app/jcl/TRANBKP.jcl:51} reads {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}, which
 * says "skip this step when 4 is less than the accumulated code"; its complement -- the condition
 * under which the step actually RUNS -- is therefore that the code is 4 or lower. A predicate written
 * to mirror the keyword instead of its complement would answer false for the middle tier, so the
 * following state would be skipped on precisely those runs that correctly rejected records and on no
 * others, while every clean run continued to look right because zero satisfies both spellings.</p>
 *
 * <p>Assumptions: the strength of that reading comes from CONTRAST, not from the clause alone. A scan
 * of all thirty-eight files under {@code app/jcl} finds ten occurrences of the condition keyword:
 * this one, eight of the form {@code COND=(0,NE)} at {@code app/jcl/DEFGDGD.jcl:36}, {@code :47},
 * {@code :59} and {@code :82}, {@code app/jcl/CREASTMT.JCL:56}, {@code :66} and {@code :79}, and
 * {@code app/jcl/TXT2PDF1.JCL:26}, and one that is not a step gate at all. Every one of those eight
 * demands a clean zero. <b>{@code app/jcl/TRANBKP.jcl:51} is thus the single place in the entire
 * baseline where a preceding code of 4 is deliberately tolerated</b>, and the tier it tolerates is
 * produced in exactly one place, {@code app/cbl/CBTRN02C.cbl:229-230}, where
 * {@code IF WS-REJECT-COUNT > 0} selects {@code MOVE 4 TO RETURN-CODE}.</p>
 *
 * <p>Assumptions: the baseline clause is JOB-SCOPED, and this file does not claim otherwise. A
 * condition parameter is evaluated only against codes of earlier steps within the same job, so
 * {@code app/jcl/TRANBKP.jcl:51} gates its own job's third step against its own first two;
 * {@code app/jcl/POSTTRAN.jcl:23} reads {@code //STEP15 EXEC PGM=CBTRN02C} and carries no condition
 * parameter, so no baseline job control consumes posting's tier across a job boundary. The clause is
 * cited here solely as the one place the baseline demonstrates the inverted SENSE of a threshold
 * comparison, which is the hazard being pinned; that its threshold coincides with posting's code is
 * not read as a data path. What the migrated chain adds is a single state machine in which the tier
 * does cross a state boundary, which is why the tolerance has to survive the translation.</p>
 *
 * <p>Assumptions: the predicate describes what this state TOLERATES from what ran before it, never
 * what this state EMITS. Only {@code PostTransactionsJob} can emit the middle tier, because it is the
 * only step whose reference program contains a {@code RETURN-CODE} statement at all; a copy either
 * happened or did not, so this job reports clean or raises. The cases below assert both halves
 * separately so the two are not blurred.</p>
 *
 * <p>Assumptions: one baseline construct shares the keyword and is not a step gate, and conflating
 * them is a real hazard rather than a hypothetical one. {@code app/jcl/TRANREPT.jcl:47} reads
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,} and sits inside the control statements
 * of a sort step, so it selects RECORDS: its migrated form is a query restriction over the processing
 * date and it must <b>never</b> be modelled as a choice state. Nothing in this file treats a record
 * filter as a step result or a step result as a filter.</p>
 *
 * <h3>Two: the deletion is tolerated-not-found, and the job is idempotent</h3>
 *
 * <p>Refactoring Rationale: each of the two deletions in the baseline's second step -- the cluster at
 * {@code app/jcl/TRANBKP.jcl:40-41} and its alternate index at {@code :43-44} -- is immediately
 * followed by {@code IF MAXCC LE 08 THEN SET MAXCC = 0}, at lines 42 and 45 respectively. That idiom
 * exists so that deleting something absent is not fatal: it normalises an already-tolerated code back
 * to clean rather than letting a not-found result accumulate and poison the step, which is also what
 * leaves the code low enough for the gate on line 51 to admit the following step. The migrated
 * equivalent must reproduce that tolerance rather than treat a missing object as an error, and it
 * must be safe to run twice.</p>
 *
 * <h3>Three: the wipe is deliberately not ported, and the omission is conditional</h3>
 *
 * <p>Alternatives Considered: two options were available for the baseline's second and third steps,
 * and only one of them is correct. <b>Port both steps:</b> {@code app/jcl/TRANBKP.jcl:37-46} deletes
 * the transaction cluster and its alternate index and {@code :51-60} re-creates the cluster empty, so
 * a literal port against a relational target would empty the transaction table every night.
 * <b>Port neither step:</b> the pair exists to serve a physical storage model that is gone -- an
 * alternate index is a separate physical object that must be dropped alongside its base and rebuilt,
 * and re-issuing the cluster definition is simply how an empty dataset is obtained there -- whereas
 * PostgreSQL maintains indexes transactionally in the same commit as the row change and a table needs
 * no periodic reallocation to stay usable. Neither step has anything to translate into, so neither is
 * ported. This is a platform-primitive mapping with no behavioural consequence and not a divergence
 * from the reference.</p>
 *
 * <p>Alternatives Considered: the omission is safe <b>only</b> because a matching omission was made
 * elsewhere, and the pairing is what this file records. In the baseline the master is emptied here and
 * refilled by the combine step's unconditional {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} at
 * {@code app/jcl/COMBTRAN.jcl:48}, inside the step opened at {@code :41}. The migrated chain performs
 * NEITHER action: {@code CombineTransactionsJob} stages its ordered extract without reloading the
 * table, so the two omissions cancel. Performing one without the other would either duplicate every
 * row or lose every row, silently, in a way no compiler or type reports. That reload ruling belongs to
 * {@code CombineTransactionsJobTest} and is cross-referenced here as this omission's safety condition
 * rather than re-asserted.</p>
 *
 * <p>Assumptions: what the third step declared is preserved as SCHEMA KNOWLEDGE rather than as a
 * run-time action. {@code app/jcl/TRANBKP.jcl:58} declares {@code KEYS(16 0)} and {@code :59} declares
 * {@code RECORDSIZE(350 350)}, and both survive as properties of the record descriptor the codecs
 * read -- a sixteen-byte key at offset zero, and a fixed record of 350 bytes. The geometry case below
 * asserts them against that descriptor, so the definition is honoured without anything being
 * re-created.</p>
 *
 * <h3>Four: the emitted image, asserted through the sanctioned codecs only</h3>
 *
 * <p>Assumptions: decoding goes through {@code CopybookLayout} for the geometry and
 * {@code ZonedDecimalCodec} for the signed money field, and through nothing else. The fixture
 * contract for this module names those two as the only sanctioned Java-side decoders and states that a
 * test here must never re-declare an overpunch table locally, because two tables that agree today can
 * disagree after one edit and the disagreement would surface as a wrong cent rather than as a
 * compilation error. The sign convention is the EBCDIC one; {@code tests/README.md} section 5.2
 * records that the default ASCII convention misreads the overpunch and silently corrupts negative
 * balances, silently being the operative word, so at least one input below carries a negative amount
 * and its round trip is asserted.</p>
 *
 * <p>Trade-offs: the output sink is asserted at the injected seam rather than against an object store,
 * emulated or otherwise. {@code services/batch-service/pom.xml} contributes the Testcontainers
 * PostgreSQL and JUnit modules and deliberately NO object-store emulator module, so there is no engine
 * to assert a bucket against in the first place. Byte-level fidelity of the emitted image is fully
 * decidable at the seam, because the staging call receives the finished file; bucket-level concerns --
 * versioning, and the retention of five noncurrent versions that stands in for
 * {@code LIMIT(5) SCRATCH} -- belong to {@code infra/modules/s3-datasets} and are asserted there, so no
 * case here reads them.</p>
 *
 * <p>Refactoring Rationale: the paragraph above also argued from this class being "named to run under
 * Surefire in the test phase where no container runtime is assumed", and that clause is removed rather
 * than reworded because it is not true of this module. {@code CombineTransactionsJobTest} and
 * {@code PreflightDailyTransactionsJobTest} carry the same plain suffix and both start a database
 * container under the same runner. The absent object-store emulator is the whole of the reason and it
 * stands on its own; the suffix carries no claim about a runtime.</p>
 *
 * <h2>What this file deliberately leaves to its siblings</h2>
 *
 * <p>Assumptions: four subjects that a reader might expect here are owned elsewhere, and restating any
 * of them would put one ruling in two places. The middle tier's production rules and the two printed
 * counter lines belong to {@code PostTransactionsJobTest}; the five-generation retained window and the
 * per-run memoisation of an allocation belong to {@code DatasetGenerationServiceTest}; the order in
 * which this job allocates, stages and applies retention belongs to
 * {@code GenerationStagingJobsTest}; and the combine step's sort order and reload ruling belong to
 * {@code CombineTransactionsJobTest}. This file asserts the CONSUMPTION side of the tolerated tier and
 * the job-level behaviour of the deletion, and it takes the generation coordinate only as far as
 * proving which family and which reference form were requested.</p>
 *
 * <p>Assumptions: a FIFTH subject is owned elsewhere, and it is the other half of a claim this file
 * makes. That every transaction row is still present and unchanged after a run is asserted here at the
 * repository seam, as an exhaustive census of the calls this job makes; the same claim is asserted
 * against a real relation by {@code BackupTransactionsJobPersistenceTest}, which launches this job over
 * seeded rows in a database container and compares every persisted column before and after. The split
 * is deliberate and is argued at the case that makes it: the census catches a mutating call that does
 * not exist yet, and the round trip catches a mutation that reaches the table by a route no call on
 * this interface expresses.</p>
 *
 * <p>Alternatives Considered: annotating this class with an active profile, as a test that loads
 * configuration would. Rejected because no case here starts a configured application context: the
 * behavioural cases construct the job configuration directly and drive it with the framework's
 * resourceless repository and transaction manager, as {@code CalculateInterestJobTest} and
 * {@code PostTransactionsJobTest} also do, and the one case that does assemble a context uses a narrow
 * context runner. The profile
 * document {@code services/batch-service/src/test/resources/application-test.yml} configures a
 * datasource, a connection pool and schema migration for the container-backed classes in this module,
 * none of which this class needs. An inert annotation naming a profile that is never activated would
 * describe this file as something it is not, and no test in this module carries one.</p>
 *
 * <p>Trade-offs: a numeric tier is asserted in two forms only -- the value the application hands back,
 * and the process exit status an orchestrator reads -- and never as a tolerance configured on a test
 * runner. The parity oracle suite grades a run across five tiers and aggregates the worst code seen,
 * and the fixture contract for this module records that the graded rubric belongs exclusively to that
 * suite, where a code of 4 is a fixture expectation value rather than a build outcome. Maven, Surefire
 * and the documentation gate are binary. What is given up is the ability to report a partly successful
 * build; what is bought is that a real failure cannot be configured to read as an accepted warning,
 * which is the only way the graded form could enter a Java gate.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>Every citation in this file is provenance. Nothing under {@code app/**} is read at run time and
 * nothing under it is altered by this migration -- the reference implementation is the behavioural
 * oracle and stays byte-identical. Where migrated behaviour differs, the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}; the omitted wipe described above is a
 * platform-primitive mapping rather than such a divergence. Line numbers refer to the source as
 * committed, and in a COBOL or job-control line columns 73 to 80 carry a sequence field that is not
 * part of the statement.</p>
 */
@DisplayName("the transaction backup job")
class BackupTransactionsJobTest {

    /** The business date the cases inject, in the ten-character separated layout. */
    private static final String BUSINESS_DATE_TOKEN = "2022-07-18";

    /** A second committed business-date layout, used to prove the image ignores the token's value. */
    private static final String ALTERNATE_BUSINESS_DATE_TOKEN = "2022-07-19";

    /** The injected business date as the job receives it. */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate(BUSINESS_DATE_TOKEN);

    /** The orchestrator execution identifier the cases run under. */
    private static final String RUN_ID = "batch-run-0006";

    /** The job-instance identifier the cases run under, which no assertion depends on. */
    private static final long INSTANCE_ID = 6L;

    /** The job-execution identifier the cases run under, which no assertion depends on. */
    private static final long EXECUTION_ID = 6L;

    /** Record length of one posted transaction, {@code LRECL=350} at {@code app/jcl/TRANBKP.jcl:31}. */
    private static final int RECORD_LENGTH = 350;

    /** Key width the reference cluster declares as {@code KEYS(16 0)} at {@code TRANBKP.jcl:58}. */
    private static final int KEY_LENGTH = 16;

    /** Key offset the reference cluster declares as {@code KEYS(16 0)} at {@code TRANBKP.jcl:58}. */
    private static final int KEY_OFFSET = 0;

    /** Digit positions before the implied point of {@code TRAN-AMT S9(09)V99}, {@code CVTRA05Y.cpy:10}. */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Digit positions after the implied point of {@code TRAN-AMT S9(09)V99}, {@code CVTRA05Y.cpy:10}. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /** Zero-based offset of the trailing {@code FILLER PIC X(20)} at {@code app/cpy/CVTRA05Y.cpy:18}. */
    private static final int FILLER_OFFSET = 330;

    /** The byte the reference leaves in a span no statement of either producer writes. */
    private static final byte LOW_VALUE = 0x00;

    /** The description text every posted row this class builds carries, from the posting producer. */
    private static final String POSTED_DESCRIPTION = "Purchase at Abshire-Lowe";

    /**
     * The description text an accrual row carries, being the reference's own rendering.
     *
     * <p>Assumptions: the twenty-four characters are the prefix at {@code app/cbl/CBACT04C.cbl:485}
     * followed by {@code ACCT-ID PIC 9(11)} at its full declared width, which is what
     * {@code STRING ... DELIMITED BY SIZE} moves, so seventy-six bytes of the hundred-character field
     * remain for the pad this case is about.</p>
     */
    private static final String ACCRUAL_DESCRIPTION = "Int. for a/c 00000000001";

    /**
     * Record length of one category balance, {@code LRECL=50} at {@code app/jcl/PRTCATBL.jcl:38} and the
     * summed width of {@code app/cpy/CVTRA01Y.cpy}.
     */
    private static final int CATEGORY_BALANCE_RECORD_LENGTH = 50;

    /** The generation number the allocation stub hands back for every family. */
    private static final int ALLOCATED_GENERATION = 1;

    /** The record descriptor the sanctioned codecs publish for the posted-transaction layout. */
    private static final CopybookLayout.RecordSpec TRANSACTION_SPEC =
            TransactionRecordMapper.Layout.POSTED_MASTER.spec();

    /** The transaction master this job copies, held as the state a mutating call would disturb. */
    private List<Transaction> masterRows;

    /** The master repository, stubbed to stream {@link #masterRows} in key order. */
    private TransactionRepository ledger;

    /** The category balances this job's third family copies, held as a mutable per-case list. */
    private List<TransactionCategoryBalance> categoryBalanceRows;

    /** The category-balance repository, stubbed to stream {@link #categoryBalanceRows} in key order. */
    private TransactionCategoryBalanceRepository categoryBalances;

    /** The generation resolver, mocked so the staged bytes can be captured at its seam. */
    private DatasetGenerationService generations;

    /** The durable step ledger, stubbed to evaluate the body it is handed. */
    private BatchStepLedger ledgerOfSteps;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

    /** The shared parameter validator the job is built with. */
    private JobParametersValidator validator;

    /**
     * The bytes handed to the staging seam for the FULL transaction copy, captured so the image can be
     * decoded.
     *
     * <p>Assumptions: this member holds the {@code transact.bkup} image specifically, not simply the
     * last image staged. The run now stages three families and every case written before it staged one
     * asserts against the full copy, so binding this member to that family's object name keeps each of
     * those assertions asserting what it was written to assert. The other two images are reachable
     * through {@link #stagedImages}.</p>
     */
    private byte[] stagedImage;

    /** Every staged image of the run, keyed by the dataset object name it was staged under. */
    private Map<String, byte[]> stagedImages;

    /** The tier the job body handed back through the step ledger, captured per run. */
    private BatchReturnCode reportedTier;

    /** The number of bodies the step ledger actually evaluated, counted across runs. */
    private int evaluatedBodies;

    /**
     * Builds the collaborators with the defaults a clean copy of an empty master sees.
     *
     * <p>Assumptions: a fresh set is built per case rather than shared, because several cases assert
     * call counts or the absence of a call, and a shared mock would carry one case's calls into the
     * next.</p>
     */
    @BeforeEach
    void buildCollaborators() {
        this.masterRows = new ArrayList<>();
        this.categoryBalanceRows = new ArrayList<>();
        this.ledger = mock(TransactionRepository.class);
        this.categoryBalances = mock(TransactionCategoryBalanceRepository.class);
        this.generations = mock(DatasetGenerationService.class);
        this.ledgerOfSteps = mock(BatchStepLedger.class);
        this.jobRepository = new ResourcelessJobRepository();
        this.validator = new BatchConfig().carddemoJobParametersValidator();
        this.stagedImage = null;
        this.stagedImages = new HashMap<>();
        this.reportedTier = null;
        this.evaluatedBodies = 0;

        // WHY : Assumptions: the step ledger is stubbed to EVALUATE the supplier it receives, matching
        //       GenerationStagingJobsTest and PostTransactionsJobTest in this same package. A mock
        //       returning its default would run none of the copy, so every assertion about the emitted
        //       image would hold vacuously against a job that did nothing at all. The tier the body
        //       returns is captured on the way past, because that value is what the orchestrator reads
        //       and one case asserts it directly.
        when(this.ledgerOfSteps.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenAnswer(call -> {
                    this.evaluatedBodies++;
                    BatchReturnCode outcome = call.<Supplier<BatchReturnCode>>getArgument(3).get();
                    this.reportedTier = outcome;
                    return new BatchStepLedger.StepOutcome(outcome, false);
                });

        // WHY : Assumptions: findAllByOrderByTransactionIdAsc is stubbed from the backing list on every
        //       call rather than from a single prepared stream, because a stream is consumed once and
        //       two cases read the master twice -- once through the job and once to prove the rows
        //       survived it. Sorting here models the key order a sequential copy of a dataset keyed
        //       KEYS(16 0) at app/jcl/TRANBKP.jcl:58 walks in, which is exactly what that finder's name
        //       promises, so a case can seed rows out of order and still assert the ordering.
        when(this.ledger.findAllByOrderByTransactionIdAsc())
                .thenAnswer(call -> this.masterRows.stream()
                        .sorted((left, right) -> left.getTransactionId()
                                .compareTo(right.getTransactionId())));

        // WHY : Assumptions: the daily-subset finder is stubbed from the SAME backing list as the full
        //       copy, filtered by the half-open window the job passes and ordered by card then
        //       identifier. Modelling the window here rather than returning the whole list is what lets
        //       a case seed a row outside the night and assert it is absent from the daily generation
        //       while still present in the full one -- the property that distinguishes the two families
        //       app/jcl/TRANREPT.jcl:37-55 derives from one unload.
        when(this.ledger.streamProcessedInWindowOrderedByCard(any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenAnswer(call -> {
                    LocalDateTime from = call.getArgument(0);
                    LocalDateTime untilExclusive = call.getArgument(1);
                    return this.masterRows.stream()
                            .filter(row -> !row.getProcTs().isBefore(from)
                                    && row.getProcTs().isBefore(untilExclusive))
                            .sorted((left, right) -> {
                                int byCard = left.getCardNum().compareTo(right.getCardNum());
                                return byCard != 0 ? byCard
                                        : left.getTransactionId().compareTo(right.getTransactionId());
                            });
                });

        // WHY : Assumptions: the balance finder is stubbed to sort by the three key members in the order
        //       app/jcl/PRTCATBL.jcl:52 names them, so its name and its behaviour agree here the same
        //       way the master finder's do. A case can then seed rows out of order and assert the
        //       staged generation carries the reference's sequence.
        when(this.categoryBalances.findAllByOrderByIdAccountIdAscIdTypeCdAscIdCategoryCdAsc())
                .thenAnswer(call -> this.categoryBalanceRows.stream()
                        .sorted((left, right) -> {
                            int byAccount = left.getId().getAccountId()
                                    .compareTo(right.getId().getAccountId());
                            if (byAccount != 0) {
                                return byAccount;
                            }
                            int byType = left.getId().getTypeCd().compareTo(right.getId().getTypeCd());
                            return byType != 0 ? byType
                                    : left.getId().getCategoryCd()
                                            .compareTo(right.getId().getCategoryCd());
                        }));

        when(this.generations.allocateNewGeneration(any(DatasetFamily.class), any(BusinessDate.class),
                anyString())).thenAnswer(call -> generation(call.getArgument(0), ALLOCATED_GENERATION));
        when(this.generations.generationsToScratch(any(DatasetFamily.class))).thenReturn(List.of());
        when(this.generations.datasetUri(any(DatasetGeneration.class)))
                .thenReturn("s3://carddemo-datasets-test/ledger/transact-bkup/");

        // WHY : Assumptions: the staged bytes are read INSIDE the staging call, because
        //       BackupTransactionsJob deletes its temporary file in the finally block that wraps this
        //       very call. A case that read the path after the run would therefore find nothing, so the
        //       seam is the only point at which the finished image exists on disk.
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenAnswer(call -> {
                    String objectName = call.getArgument(1, String.class);
                    byte[] image = Files.readAllBytes(call.getArgument(2, Path.class));
                    this.stagedImages.put(objectName, image);
                    if (BackupTransactionsJob.DATASET_OBJECT_NAME.equals(objectName)) {
                        this.stagedImage = image;
                    }
                    return "ledger/transact-bkup/dt=" + BUSINESS_DATE_TOKEN + "/gen=0001/"
                            + objectName;
                });
    }

    /**
     * The job registers under the published backup token and records its step under that token.
     *
     * <p>Pins the vocabulary side of state six of the nightly chain. The name is resolved from an
     * assembled context rather than read off the class, because the entry point selects a job by
     * iterating the registered job beans and comparing each one's own name against its
     * {@code --job=} argument: a bean whose identifier is right and whose name is wrong cannot be
     * selected at all.</p>
     */
    @Test
    @DisplayName("register under the published backup token and derive its step name from it")
    void theJobRegistersUnderThePublishedBackupToken() {
        assertThat(BackupTransactionsJob.JOB_NAME)
                .isEqualTo(BatchJobName.BACKUP_TRANSACTIONS.token())
                .isEqualTo("backup-transactions");
        assertThat(BackupTransactionsJob.STEP_NAME)
                .isEqualTo("backup-transactions" + BatchJobName.STEP_NAME_SUFFIX);

        // WHY : Assumptions: the batch.batch_run ledger stores the STEP name rather than the job token,
        //       so BatchJobName.forStepName is what turns a stored row back into a job, and it has to
        //       invert the STEP_NAME_SUFFIX concatenation above. Asserting the round trip here keeps a
        //       change to either half from producing a ledger row that can be written and never read.
        assertThat(BatchJobName.forStepName(BackupTransactionsJob.STEP_NAME))
                .isEqualTo(BatchJobName.BACKUP_TRANSACTIONS);

        registryRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(Job.class).values())
                    .as("the orchestrator selects this state by the name the bean itself reports")
                    .extracting(Job::getName)
                    .containsExactly(BackupTransactionsJob.JOB_NAME);
        });
    }

    /**
     * The migrated gate is a RUN predicate: the clean and warn tiers pass it, a hard failure does not.
     *
     * <p>Pins {@code app/jcl/TRANBKP.jcl:51}, {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)}, which is
     * the ONLY {@code COND=(4,LT)} in all thirty-eight files of {@code app/jcl}. Every other step gate
     * in the baseline is {@code COND=(0,NE)} and demands a clean zero -- the eight sites at
     * {@code app/jcl/DEFGDGD.jcl:36}, {@code :47}, {@code :59}, {@code :82},
     * {@code app/jcl/CREASTMT.JCL:56}, {@code :66}, {@code :79} and {@code app/jcl/TXT2PDF1.JCL:26} --
     * so this is the one seam where a preceding code of 4 is deliberately tolerated. The tier it
     * tolerates is produced only at {@code app/cbl/CBTRN02C.cbl:229-230}.</p>
     *
     * @param tierName the name of the {@code BatchReturnCode} constant under test, supplied as a
     *     String and resolved to the constant so the case reads as the tier rather than as a number
     * @param expectedNumber the int the tier is reported as, which is the value an orchestration
     *     predicate and the durable ledger both compare
     * @param permitsRun the boolean the run predicate must answer for that tier
     */
    @ParameterizedTest(name = "{0} reports {1} and permits the following state: {2}")
    @CsvSource({
        "CLEAN, 0, true",
        "SOFT_WARN, 4, true",
        "HARD_FAILURE, 8, false",
    })
    @DisplayName("invert the skip condition into a run predicate that admits 4 and refuses 8")
    void theRunPredicateAdmitsTheToleratedTierAndRefusesFailure(
            String tierName, int expectedNumber, boolean permitsRun) {

        BatchReturnCode tier = BatchReturnCode.valueOf(tierName);

        assertThat(tier.numericValue()).isEqualTo(expectedNumber);

        // WHY : Refactoring Rationale: the assertion is written in the RUN sense because the migrated
        //       mechanism reads the comparison the opposite way round from the mechanism it replaces,
        //       and that is the whole substance of this case. A job-control condition states when the
        //       following step is BYPASSED, so COND=(4,LT) at app/jcl/TRANBKP.jcl:51 reads "skip when
        //       4 is less than the accumulated code" and the step therefore RUNS while the code is 4
        //       or lower. An orchestration choice states when the following state is ENTERED, so it
        //       must carry the complement. Transcribing the clause as though it were already a run
        //       predicate would answer false for the middle tier: the chain would stop on exactly
        //       those nights that correctly rejected records, and on no others, while every clean
        //       night still looked right because zero satisfies both spellings.
        assertThat(tier.permitsDownstreamRun())
                .as("a gate written from COND=(4,LT) admits the code 4 rather than only 0")
                .isEqualTo(permitsRun);

        // WHY : Assumptions: the tier is resolved through BatchReturnCode.fromNumericValue as well as
        //       asserted on the constant, because the orchestrator never holds the constant -- it reads
        //       the process exit status BatchApplication.EXIT_STATUS_* publishes and resolves that. A
        //       predicate correct on the enumeration and wrong after resolution would gate correctly in
        //       a unit test and wrongly in the chain.
        assertThat(BatchReturnCode.fromNumericValue(expectedNumber)).isSameAs(tier);
        assertThat(BatchReturnCode.fromNumericValue(expectedNumber).permitsDownstreamRun())
                .isEqualTo(permitsRun);
    }

    /**
     * A night whose posting step reported the tolerated tier still produces a complete backup.
     *
     * <p>Pins the point of the tolerance at {@code app/jcl/TRANBKP.jcl:51}: admitting the code 4 is
     * only meaningful if the admitted state then does its whole job. The preceding tier is asserted to
     * be admitted and the copy is then asserted to be complete in the same case, because a gate that
     * admits a state which produces a truncated artifact tolerates nothing useful.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("run to completion after an upstream night that reported the tolerated tier")
    void theBackupRunsCompletelyAfterATolerationOfTheWarnTier() throws Exception {
        // WHY : Assumptions: the upstream tier is asserted rather than injected, because nothing in
        //       this job reads a predecessor's code -- the gate belongs to the orchestration definition
        //       that infra/modules/step-functions-batch owns. What is decidable here is that the tier
        //       the posting step can report is one this state is permitted to run after, and that the
        //       state is complete when it does.
        assertThat(BatchReturnCode.SOFT_WARN.permitsDownstreamRun()).isTrue();
        masterHolds(
                transactionRow("0000000000000001", new BigDecimal("504.77"), "4859452612877065"),
                transactionRow("0000000000000002", new BigDecimal("-125.50"), "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedImage)
                .as("the tolerated state must emit every record, not a partial image")
                .hasSize(2 * RECORD_LENGTH);
    }

    /**
     * This job reports the clean tier on success, and never the tolerated middle tier.
     *
     * <p>Pins the distinction the run predicate makes easy to blur: {@code rc <= 4} describes what
     * this state TOLERATES from what ran before it, not what it EMITS. The middle tier is produced by
     * one statement in the whole batch set, {@code app/cbl/CBTRN02C.cbl:229-230}, and this job has no
     * reject stream and no counter, so a copy either completed or raised.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("report the clean tier on success and never the tolerated middle tier")
    void theBackupReportsTheCleanTierAndNeverTheWarnTier() throws Exception {
        masterHolds(transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.reportedTier)
                .as("only the posting step may report the middle tier")
                .isEqualTo(BatchReturnCode.CLEAN)
                .isNotEqualTo(BatchReturnCode.SOFT_WARN);

        // WHY : Trade-offs: the tier is asserted as the value the application hands back AND as the
        //       process exit status the orchestrator reads, and never as a tolerance configured on a
        //       test runner. The parity oracle suite under tests/ grades a run across five tiers and
        //       aggregates the worst code seen; borrowing that rubric here would let a real failure be
        //       configured to read as an accepted warning. What is given up is the ability to describe
        //       a partly successful build, which no Java gate in this repository expresses anyway.
        assertThat(this.reportedTier.numericValue()).isEqualTo(BatchApplication.EXIT_STATUS_CLEAN);
        assertThat(BatchReturnCode.SOFT_WARN.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN);
        assertThat(BatchReturnCode.HARD_FAILURE.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE);
    }

    /**
     * A copy that cannot be staged fails the job outright rather than reporting the tolerated tier.
     *
     * <p>Pins the other half of the emitted-tier contract for {@code app/jcl/TRANBKP.jcl:23-33}: the
     * copy step has no soft outcome, so a staging failure has to surface as a failed job whose tier the
     * gate at {@code :51} refuses. Reporting the middle tier here would let the chain continue on a
     * night whose backup does not exist.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("fail the job when the copy cannot be staged, refusing the following state")
    void aCopyThatCannotBeStagedFailsRatherThanWarning() throws Exception {
        masterHolds(transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"));
        when(this.generations.stageDataset(any(DatasetGeneration.class), anyString(), any(Path.class)))
                .thenThrow(new IllegalStateException("the dataset bucket refused the copy"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(this.reportedTier)
                .as("a failed copy must not resolve to a tier the gate admits")
                .isNull();
        assertThat(BatchReturnCode.HARD_FAILURE.permitsDownstreamRun()).isFalse();
        assertThat(BatchReturnCode.HARD_FAILURE.numericValue())
                .isGreaterThanOrEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE);
    }

    /**
     * A first run against an environment holding nothing to remove succeeds.
     *
     * <p>Pins the tolerated-not-found idiom at {@code app/jcl/TRANBKP.jcl:42} and {@code :45}. Each of
     * the two deletions in that step -- the cluster at {@code :40-41} and its alternate index at
     * {@code :43-44} -- is followed by {@code IF MAXCC LE 08 THEN SET MAXCC = 0}, so a delete that
     * finds nothing is explicitly not an error. A first nightly run against a fresh environment is that
     * situation.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("succeed on a first run when there is nothing to remove")
    void theRemovalStepIsNotFatalWhenThereIsNothingToRemove() throws Exception {
        masterHolds(transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.generations, never()).scratchGeneration(any(DatasetGeneration.class));
        assertThat(this.stagedImage).hasSize(RECORD_LENGTH);
    }

    /**
     * A removal that found nothing to remove leaves the run clean rather than failing it.
     *
     * <p>Pins the same idiom at {@code app/jcl/TRANBKP.jcl:42} and {@code :45} from the other side. Here
     * the retention rule does name an aged-out generation, but the removal reports that it took nothing
     * away -- the object was already gone. That is the exact condition
     * {@code IF MAXCC LE 08 THEN SET MAXCC = 0} exists to neutralise: the reference resets the
     * accumulated code instead of letting a not-found result poison the step and close the gate on
     * line 51 against the step that follows.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stay clean when a removal reports that nothing was there to remove")
    void aRemovalThatFoundNothingIsNotFatal() throws Exception {
        DatasetGeneration agedOut = generation(DatasetFamily.TRANSACT_BKUP, ALLOCATED_GENERATION);
        when(this.generations.generationsToScratch(DatasetFamily.TRANSACT_BKUP))
                .thenReturn(List.of(agedOut));
        // WHY : Refactoring Rationale: a removal count of zero is the migrated shape of a not-found
        //       delete, and it must read as tolerated rather than as a failure. The reference could not
        //       express "nothing to delete" as a success on its own -- a delete of an absent dataset
        //       raises the accumulated code -- so app/jcl/TRANBKP.jcl:42 and :45 neutralise that code
        //       explicitly on the line after each delete. The migrated form has to reproduce the same
        //       tolerance instead of treating an absent object as an error, and this case is what stops
        //       a stricter reading being introduced.
        when(this.generations.scratchGeneration(agedOut)).thenReturn(0);
        masterHolds(transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.reportedTier).isEqualTo(BatchReturnCode.CLEAN);
    }

    /**
     * Running the job twice succeeds both times and produces the same image both times.
     *
     * <p>Pins the idempotence the tolerated-not-found idiom at {@code app/jcl/TRANBKP.jcl:42} and
     * {@code :45} buys: the reference's second step can be re-entered because a delete of something
     * absent is survivable, so the migrated job must be re-runnable too. An orchestrator that redrives
     * a state after a downstream failure re-enters this one, and a second run that failed -- or that
     * emitted a different image from the same rows -- would turn a recoverable night into a manual
     * one.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("succeed on a second run and emit the same image")
    void runningTheJobTwiceSucceedsBothTimes() throws Exception {
        masterHolds(
                transactionRow("0000000000000001", new BigDecimal("504.77"), "4859452612877065"),
                transactionRow("0000000000000002", new BigDecimal("-125.50"), "4859452612877065"));

        JobExecution first = runBackup();
        byte[] firstImage = this.stagedImage;
        JobExecution second = runBackup(BUSINESS_DATE_TOKEN, INSTANCE_ID + 1, EXECUTION_ID + 1);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedImage)
                .as("the same rows must copy to the same bytes on a redriven night")
                .isEqualTo(firstImage);
    }

    /**
     * The job leaves every transaction row present and byte-identical.
     *
     * <p>Pins the deliberate omission of {@code app/jcl/TRANBKP.jcl:37-46}, which deletes the
     * transaction cluster and its alternate index, and {@code :51-60}, which re-creates the cluster
     * empty. This is the single most consequential assertion in this file: ported literally to a
     * relational target, that pair would empty the transaction table on every nightly run. The pair has
     * no target analogue -- PostgreSQL maintains indexes transactionally in the same commit as the row
     * change, and a table needs no periodic reallocation -- so both steps are omitted, and this case is
     * what keeps them omitted.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("leave every transaction row present and unchanged")
    void theJobLeavesEveryTransactionRowPresentAndUnchanged() throws Exception {
        masterHolds(
                transactionRow("0000000000000001", new BigDecimal("504.77"), "4859452612877065"),
                transactionRow("0000000000000002", new BigDecimal("-125.50"), "4111111111111111"),
                transactionRow("0000000000000003", new BigDecimal("0.00"), "4111111111111111"));
        List<byte[]> before = encodedMaster();

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.masterRows)
                .as("a ported cluster delete would have emptied the master")
                .hasSize(3);
        assertThat(encodedMaster())
                .as("a ported cluster re-create would have replaced the rows")
                .containsExactlyElementsOf(before);

        // WHY : Trade-offs: this class settles the claim at the REPOSITORY SEAM and the sibling
        //       BackupTransactionsJobPersistenceTest settles it against the engine, because the two
        //       checks are strict in opposite directions and neither subsumes the other. The
        //       interaction census below fails on ANY mutating call this job is not supposed to make,
        //       including one added to TransactionRepository after this was written, which a round trip
        //       cannot do -- a round trip over a table that was emptied and repopulated would pass. It
        //       cannot, however, establish what a reader takes from it: a double answers whatever it
        //       was told to answer, so a truncation reaching the table by a route no interaction on
        //       this interface expresses would satisfy every verification here. That half is asserted
        //       in the sibling class, which seeds distinguishable rows into ledger.transactions,
        //       launches this job over them and compares every persisted column before and after.
        // WHY : Refactoring Rationale: this block previously declined the round trip on the ground that
        //       "this module puts every container-backed class behind the integration-test suffix that
        //       Failsafe owns", naming PostingUnitOfWorkIT and BatchRunRepositoryIT as the only two.
        //       Both halves were wrong when measured. CombineTransactionsJobTest and
        //       PreflightDailyTransactionsJobTest carry the PLAIN suffix and both start a database
        //       container under Surefire, and CrossSchemaFeedRepositoryIT is a third integration-test
        //       class in this module -- so the suffix marks a case whose subject is a repository
        //       contract, not the availability of a container runtime, and every runner this repository
        //       defines provides one. The correction matters beyond accuracy: the false rule was the
        //       stated reason this claim had no engine-backed half at all.
        verify(this.ledger).findAllByOrderByTransactionIdAsc();
        verify(this.ledger, never()).deleteAll();
        verify(this.ledger, never()).deleteAllInBatch();
        verify(this.ledger, never()).delete(any(Transaction.class));
        verify(this.ledger, never()).save(any(Transaction.class));
        verify(this.ledger, never()).saveAll(any());
        verify(this.ledger, never()).flush();

        // WHY : Refactoring Rationale: the ordered window read is named as an EXPECTED interaction, and
        //       it was not there when the job staged one family. The step now stages three, and the
        //       daily subset is derived from a second read of this same repository -- a read, so the
        //       ruling this case makes is untouched. Naming it is what keeps the catch-all below
        //       exhaustive rather than having to be loosened, which would have cost the whole assertion.
        verify(this.ledger).streamProcessedInWindowOrderedByCard(
                any(LocalDateTime.class), any(LocalDateTime.class));

        // WHY : Assumptions: the catch-all is what makes this ruling durable. The explicit refusals
        //       above name only the mutators TransactionRepository inherits from JpaRepository today,
        //       and a mutating method added to that interface would slip past every one of them; an
        //       exhaustive interaction check fails on ANY call this job is not supposed to make,
        //       including one that does not exist yet, which is the only form of the assertion that
        //       cannot quietly go stale.
        verifyNoMoreInteractions(this.ledger);
    }

    /**
     * The cluster definition survives as schema knowledge rather than as a run-time action.
     *
     * <p>Pins {@code app/jcl/TRANBKP.jcl:54-60}, whose {@code DEFINE CLUSTER} declares
     * {@code KEYS(16 0)} on line 58 and {@code RECORDSIZE(350 350)} on line 59. The definition is not
     * executed by the migrated job; what it asserted about the record -- a sixteen-byte key at offset
     * zero and a fixed length of 350 bytes -- is carried instead by the descriptor the sanctioned
     * codecs publish, and the key is the transaction identifier because
     * {@code app/cpy/CVTRA05Y.cpy:5} places {@code TRAN-ID PIC X(16)} first in the record.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("preserve the declared key and record geometry as a descriptor, not an action")
    void theClusterGeometryIsPreservedAsSchemaKnowledge() throws Exception {
        assertThat(TRANSACTION_SPEC.reclen()).isEqualTo(RECORD_LENGTH);
        assertThat(TRANSACTION_SPEC.keyLength()).isEqualTo(KEY_LENGTH);
        assertThat(TRANSACTION_SPEC.keyOffset()).isEqualTo(KEY_OFFSET);
        assertThat(TRANSACTION_SPEC.hasRetrievalKey()).isTrue();

        // WHY : Assumptions: the key KEYS(16 0) declares at app/jcl/TRANBKP.jcl:58 is asserted to
        //       COINCIDE with TRAN-ID at app/cpy/CVTRA05Y.cpy:5 rather than merely to measure sixteen
        //       bytes at offset zero. That coincidence is what makes a character ordering of the emitted
        //       image agree with the reference dataset's own physical order, and it is the premise the
        //       ordering case below depends on; a descriptor whose key straddled two fields would
        //       satisfy the widths above and break that agreement.
        CopybookLayout.FieldSpec key = TRANSACTION_SPEC.field("TRAN-ID");
        assertThat(key.start()).isEqualTo(KEY_OFFSET);
        assertThat(key.length()).isEqualTo(KEY_LENGTH);

        masterHolds(transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"));
        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedImage).hasSize(RECORD_LENGTH);
    }

    /**
     * Records are fixed 350-byte spans laid end to end with no separator between them.
     *
     * <p>Pins {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} at {@code app/jcl/TRANBKP.jcl:31} against the
     * record length {@code app/cpy/CVTRA05Y.cpy:2} states as {@code RECLN = 350}. A fixed-blocked
     * dataset carries no record separator at all: the length IS the delimiter, so a newline written
     * between records would shift every following record and a reader slicing at fixed offsets would
     * decode shifted fields rather than report an error.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit fixed 350-byte records with no separator between them")
    void everyRecordIsExactly350BytesWithNoSeparator() throws Exception {
        masterHolds(
                transactionRow("0000000000000001", new BigDecimal("504.77"), "4859452612877065"),
                transactionRow("0000000000000002", new BigDecimal("-125.50"), "4111111111111111"),
                transactionRow("0000000000000003", new BigDecimal("0.00"), "4111111111111111"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedImage).hasSize(3 * RECORD_LENGTH);
        assertThat(this.stagedImage.length % RECORD_LENGTH)
                .as("a fixed-blocked image divides exactly into records")
                .isZero();
        assertThat(new String(this.stagedImage, StandardCharsets.US_ASCII))
                .as("a record separator would shift every following field")
                .doesNotContain("\n")
                .doesNotContain("\r");
    }

    /**
     * The image is ordered by transaction identifier ascending, not by insertion order.
     *
     * <p>Pins {@code KEYS(16 0)} at {@code app/jcl/TRANBKP.jcl:58}: the reference copies from a
     * key-sequenced dataset, so its sequential copy walks the file in key order, and the combine step
     * that reads the result sorts on the same key, declared {@code TRAN-ID,1,16,CH} at
     * {@code app/jcl/COMBTRAN.jcl:28}. The rows below are seeded out of order deliberately, so a
     * migrated job that emitted them in the order it received them would fail this case.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit records ordered by transaction identifier ascending")
    void theImageIsOrderedByTransactionIdentifierAscending() throws Exception {
        // WHY : Assumptions: an unordered relational scan is the easiest way to break this silently,
        //       and it is the one hazard the key declaration KEYS(16 0) at app/jcl/TRANBKP.jcl:58 does
        //       not protect against by itself. A table scan may return rows in any order and typically
        //       returns them in physical order, which changes as rows are updated, so an unordered copy
        //       would emit a DIFFERENT byte stream from the same data on different nights -- and every
        //       assertion about record count and record length would still pass. Seeding out of order
        //       is what makes the ordering observable at all.
        masterHolds(
                transactionRow("0000000000000003", new BigDecimal("3.00"), "4111111111111111"),
                transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"),
                transactionRow("0000000000000002", new BigDecimal("-2.00"), "4111111111111111"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(List.of(fieldText(0, "TRAN-ID"), fieldText(1, "TRAN-ID"), fieldText(2, "TRAN-ID")))
                .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");

        // WHY : Assumptions: findAllByOrderByTransactionIdAsc is asserted to be the finder that was
        //       called and JpaRepository's findAll to be untouched, because TransactionRepository
        //       publishes both. The ordering above could otherwise be satisfied by an unordered read
        //       that happened to arrive sorted, and a case that passes for the wrong reason is worse
        //       here than no case, because this is the property with no golden to catch it.
        verify(this.ledger).findAllByOrderByTransactionIdAsc();
        verify(this.ledger, never()).findAll();
    }

    /**
     * Every field lands at the offset and width its copybook line declares.
     *
     * <p>Pins {@code app/cpy/CVTRA05Y.cpy} field by field. Because no golden output for this dataset
     * exists anywhere in the repository, this case IS the specification of the backup image geometry,
     * so the expectations are taken from the copybook rather than from an emitted sample: the fourteen
     * declared widths sum to the 350 bytes {@code app/jcl/TRANBKP.jcl:31} declares, and the descriptor
     * the sanctioned codecs publish is asserted to agree with each line.</p>
     *
     * @param fieldName the copybook name of the field, supplied as a String and looked up in the
     *     descriptor exactly as a decoder would look it up
     * @param expectedOffset the int zero-based byte offset the copybook line implies
     * @param expectedLength the int byte width the field's picture clause declares
     */
    @ParameterizedTest(name = "{0} occupies {2} bytes at offset {1}")
    @CsvSource({
        "TRAN-ID, 0, 16",
        "TRAN-TYPE-CD, 16, 2",
        "TRAN-CAT-CD, 18, 4",
        "TRAN-SOURCE, 22, 10",
        "TRAN-DESC, 32, 100",
        "TRAN-AMT, 132, 11",
        "TRAN-MERCHANT-ID, 143, 9",
        "TRAN-MERCHANT-NAME, 152, 50",
        "TRAN-MERCHANT-CITY, 202, 50",
        "TRAN-MERCHANT-ZIP, 252, 10",
        "TRAN-CARD-NUM, 262, 16",
        "TRAN-ORIG-TS, 278, 26",
        "TRAN-PROC-TS, 304, 26",
        "FILLER, 330, 20",
    })
    @DisplayName("place each field at the offset and width the copybook declares")
    void eachFieldLandsWhereTheCopybookDeclaresIt(
            String fieldName, int expectedOffset, int expectedLength) {

        CopybookLayout.FieldSpec field = TRANSACTION_SPEC.field(fieldName);

        assertThat(field.start()).isEqualTo(expectedOffset);
        assertThat(field.length()).isEqualTo(expectedLength);
        assertThat(field.end()).isLessThanOrEqualTo(RECORD_LENGTH);
    }

    /**
     * The emitted record carries each entity value at its declared span and decodes back unchanged.
     *
     * <p>Pins the content half of {@code app/cpy/CVTRA05Y.cpy} against the geometry the case above
     * pins. The whole record is additionally read back through the sanctioned mapper, because a pair of
     * matching mistakes in the writer and in one hand-written expectation would otherwise agree with
     * each other while disagreeing with the copybook.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("carry every field value at its declared span and decode back unchanged")
    void theEmittedRecordCarriesEveryFieldValue() throws Exception {
        Transaction row = transactionRow("0000000000000001", new BigDecimal("504.77"),
                "4859452612877065");
        masterHolds(row);

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(fieldText(0, "TRAN-ID")).isEqualTo(row.getTransactionId());
        assertThat(fieldText(0, "TRAN-TYPE-CD")).isEqualTo(row.getTypeCd());
        assertThat(fieldText(0, "TRAN-CAT-CD")).isEqualTo(row.getCategoryCd());
        assertThat(fieldText(0, "TRAN-SOURCE").strip()).isEqualTo(row.getSource());
        assertThat(fieldText(0, "TRAN-DESC").strip()).isEqualTo(row.getDescription());
        assertThat(fieldText(0, "TRAN-MERCHANT-NAME").strip()).isEqualTo(row.getMerchantName());
        assertThat(fieldText(0, "TRAN-MERCHANT-CITY").strip()).isEqualTo(row.getMerchantCity());
        assertThat(fieldText(0, "TRAN-MERCHANT-ZIP").strip()).isEqualTo(row.getMerchantZip());
        assertThat(fieldText(0, "TRAN-CARD-NUM")).isEqualTo(row.getCardNum());
        assertThat(fieldText(0, "TRAN-MERCHANT-ID")).contains(String.valueOf(row.getMerchantId()));
        assertThat(fieldText(0, "TRAN-ORIG-TS").strip()).isNotEmpty();
        assertThat(fieldText(0, "TRAN-PROC-TS").strip()).isNotEmpty();

        // WHY : Assumptions: the round trip goes through TransactionRecordMapper, the same mapper the
        //       job writes with, so it proves self-consistency of the byte form rather than independent
        //       correctness -- the independent check is the geometry case above, whose expectations come
        //       from app/cpy/CVTRA05Y.cpy rather than from this mapper. The two together are what a
        //       golden would otherwise provide, and this repository has none for this dataset.
        Transaction decoded = TransactionRecordMapper.toEntity(recordAt(0));
        assertThat(decoded.getTransactionId()).isEqualTo(row.getTransactionId());
        assertThat(decoded.getAmount()).isEqualByComparingTo(row.getAmount());
        assertThat(decoded.getCardNum()).isEqualTo(row.getCardNum());
        assertThat(decoded.getOrigTs()).isEqualTo(row.getOrigTs());
    }

    /**
     * A negative amount survives the copy as a sign overpunch on the field's final character.
     *
     * <p>Pins {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:10}. The leading
     * {@code S} makes the field signed, and a zoned-decimal field carries its sign as an overpunch on
     * the final digit position rather than as a separate character, so the field's width stays eleven
     * whatever the sign. {@code tests/README.md} section 5.2 records that the baseline is compiled with
     * the EBCDIC sign convention and that the default convention misreads the overpunch and silently
     * corrupts negative balances -- silently being the operative word, because the run still produces
     * plausible numbers -- which is why a negative amount is carried by this case rather than assumed
     * to behave like a positive one.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("carry a negative amount as a sign overpunch that decodes back to the same value")
    void aNegativeAmountRoundTripsThroughTheSignOverpunch() throws Exception {
        BigDecimal owed = new BigDecimal("-125.50");
        masterHolds(transactionRow("0000000000000001", owed, "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        String raw = fieldText(0, "TRAN-AMT");

        // WHY : Assumptions: the decode goes through ZonedDecimalCodec and its overpunch tables are NOT
        //       restated here. services/batch-service/src/test/resources/fixtures/README.md names that
        //       codec as the only sanctioned Java-side decoder for these fields and forbids a local copy
        //       of its tables, on the ground that two tables agreeing today can disagree after one edit
        //       and the disagreement would surface as a wrong cent rather than as a compilation error.
        //       The sign is therefore asserted by its observable properties -- the width the codec's own
        //       widthOf reports, no leading minus, and a final character that is not a plain digit --
        //       plus the value the codec itself reads back.
        assertThat(raw).hasSize(
                ZonedDecimalCodec.widthOf(AMOUNT_INTEGER_DIGITS, AMOUNT_DECIMAL_DIGITS));
        assertThat(raw).doesNotContain("-");
        assertThat(Character.isDigit(raw.charAt(raw.length() - 1)))
                .as("a signed zoned field carries its sign on the final character")
                .isFalse();
        assertThat(ZonedDecimalCodec.decode(raw, AMOUNT_INTEGER_DIGITS, AMOUNT_DECIMAL_DIGITS, true))
                .isEqualByComparingTo(owed);
    }

    /**
     * The trailing pad is written out in full, and carries the byte the reference leaves there.
     *
     * <p>Pins {@code FILLER PIC X(20)} at {@code app/cpy/CVTRA05Y.cpy:18}, the last twenty bytes of the
     * record. Trailing-blank stripping is one of the two regressions this guards against: the parity
     * harness records that stripping trailing whitespace collapses a 350-byte record to roughly 278
     * characters, which was a real defect in that harness, and under a fixed-blocked dataset a short
     * record shifts every record after it.</p>
     *
     * <p>Refactoring Rationale: this case required the pad to be BLANK, which was a restatement of the
     * shared codec's rebuild rather than a reading of the dataset this job copies. Section 6.1 of
     * {@code services/batch-service/src/test/resources/fixtures/README.md} measures the pad of an
     * output transaction record as twenty LOW VALUES, in the posting expectation and in all three
     * interest expectations, because no {@code MOVE} in either producer names the item; the mapper now
     * writes that byte and this case asserts it. Requiring the blank was the second regression, and it
     * was the more dangerous one: a byte-image copy that substitutes a pad byte differs from the dataset
     * it claims to reproduce in twenty positions per record, on a span no field assertion reaches.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("preserve the trailing pad, carrying the low values the reference leaves there")
    void theTrailingPadIsPreservedRatherThanTrimmed() throws Exception {
        masterHolds(
                transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"),
                transactionRow("0000000000000002", new BigDecimal("2.00"), "4111111111111111"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(fieldText(0, "FILLER")).hasSize(RECORD_LENGTH - FILLER_OFFSET);
        assertThat(fieldText(1, "FILLER")).hasSize(RECORD_LENGTH - FILLER_OFFSET);
        assertThat(padOf(0)).containsOnly(LOW_VALUE);
        assertThat(padOf(1)).containsOnly(LOW_VALUE);
        assertThat(recordAt(1))
                .as("the second record must start on the 350-byte boundary")
                .hasSize(RECORD_LENGTH);
    }

    /**
     * Each row class keeps its own producer's description padding across the copy.
     *
     * <p>Pins section 6.3 of {@code services/batch-service/src/test/resources/fixtures/README.md},
     * which measures the description pad as a property of the program that WROTE the record rather than
     * of the record type: the accrual pass builds its text with {@code STRING} at
     * {@code app/cbl/CBACT04C.cbl:485-489} and leaves the remaining seventy-six bytes of
     * {@code TRAN-DESC} at the low values the record area held, while the posting pass moves an
     * already blank-padded feed field at {@code app/cbl/CBTRN02C.cbl:429}. This copy is a byte image of
     * a master holding both classes, so both pads have to survive it.</p>
     *
     * <p>Assumptions: the two classes are asserted SIDE BY SIDE in one run rather than in two runs of
     * one class each, because what is at stake is a per-row decision. A job that applied one producer's
     * pad to the whole stream would satisfy either single-class case and fail this one.</p>
     *
     * <p>Assumptions: the accrual row is recognised from the attribution the accrual pass itself
     * writes -- the {@code System} source at {@code app/cbl/CBACT04C.cbl:484} and the
     * {@code Int. for a/c } description prefix at {@code :485} -- and the row built here carries both,
     * so this case also pins that the recogniser reads the fields the reference sets rather than a
     * marker invented by the target.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("keep each producer's description padding, both classes in one copy")
    void eachRowClassKeepsItsOwnProducerDescriptionPadding() throws Exception {
        masterHolds(
                transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"),
                interestRow("2022-07-18000001", new BigDecimal("12.50"), "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        CopybookLayout.FieldSpec description = TRANSACTION_SPEC.field("TRAN-DESC");
        int postedTextLength = POSTED_DESCRIPTION.length();
        int accrualTextLength = ACCRUAL_DESCRIPTION.length();

        assertThat(fieldText(0, "TRAN-DESC")).startsWith(POSTED_DESCRIPTION);
        assertThat(fieldText(1, "TRAN-DESC")).startsWith(ACCRUAL_DESCRIPTION);

        // WHY : Assumptions: each tail is read from the descriptor's own span and from the text's own
        //       length rather than from an offset written here, so the two claims are located by the
        //       same registry the encoder used. The posting tail is asserted to be blanks and the
        //       accrual tail low values, which is the whole of the distinction section 6.3 measures.
        assertThat(Arrays.copyOfRange(recordAt(0), description.start() + postedTextLength,
                description.end()))
                .as("the posting producer blank-pads its description")
                .containsOnly((byte) ' ');
        assertThat(Arrays.copyOfRange(recordAt(1), description.start() + accrualTextLength,
                description.end()))
                .as("the accrual producer leaves low values behind its description")
                .containsOnly(LOW_VALUE);

        // WHY : Assumptions: both records' trailing pads are asserted too, because the two pad rules
        //       are independent -- the trailing pad is low values for BOTH producers while the
        //       description pad differs -- and a change that made the description pad follow the
        //       trailing one, or the reverse, would be caught by nothing else.
        assertThat(padOf(0)).containsOnly(LOW_VALUE);
        assertThat(padOf(1)).containsOnly(LOW_VALUE);
    }

    /**
     * The run allocates a NEW generation of the backup family and never reuses the current one.
     *
     * <p>Pins {@code app/jcl/TRANBKP.jcl:29-33}, whose output data definition is
     * {@code DISP=(NEW,CATLG,DELETE)} against {@code DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} -- the
     * relative reference for a new generation, not the {@code (0)} that names the current one. The base
     * is defined at {@code app/jcl/DEFGDGB.jcl:25-27}. Writing over the current generation would
     * destroy the previous night's backup, which is the one artifact this state exists to keep.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("allocate a new generation of the backup family rather than reusing the current one")
    void theRunAllocatesANewGenerationOfTheBackupFamily() throws Exception {
        masterHolds(transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(GenerationReference.NEW.jclNotation()).isEqualTo("(+1)");
        assertThat(GenerationReference.CURRENT.jclNotation()).isEqualTo("(0)");
        verify(this.generations).allocateNewGeneration(
                DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, RUN_ID);
        verify(this.generations, never()).resolveCurrentGeneration(any(DatasetFamily.class));

        // WHY : Assumptions: the families are asserted as well as the reference form, because
        //       DatasetGeneration.DatasetFamily declares TEN families -- one per generation base across
        //       app/jcl/DEFGDGB.jcl, app/jcl/DEFGDGD.jcl and app/jcl/DALYREJS.jcl -- and this job
        //       writes three of them. A copy staged under a neighbouring family would still allocate,
        //       still stage and still report clean, and the loss would surface only in whichever step
        //       went looking for the backup.
        // WHY : Refactoring Rationale: this asserted ONE family and now asserts three, in the order the
        //       step stages them. The step took over the two families that had no production writer at
        //       all -- TRANSACT.DALY, which app/jcl/TRANREPT.jcl:37-55 derives from the same unload, and
        //       TCATBALF.BKUP, which app/jcl/PRTCATBL.jcl:29-39 unloads -- so the list is asserted
        //       EXACTLY rather than by containment: the IAM scoping in both environment roots reads this
        //       list, and a family added here without a grant fails at run time and not at plan time.
        assertThat(BackupTransactionsJob.stagedFamilies())
                .containsExactly(DatasetFamily.TRANSACT_BKUP, DatasetFamily.TRANSACT_DALY,
                        DatasetFamily.TCATBALF_BKUP);
        assertThat(DatasetFamily.TRANSACT_BKUP.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TRANSACT.BKUP");
        assertThat(DatasetFamily.TRANSACT_DALY.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TRANSACT.DALY");
        assertThat(DatasetFamily.TCATBALF_BKUP.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TCATBALF.BKUP");
    }

    /**
     * The staged coordinate carries the date partition and a zero-padded generation number.
     *
     * <p>Pins the migrated form of the generation reference at {@code app/jcl/TRANBKP.jcl:33}: a
     * relative generation becomes a key prefix under a date partition, so the artifact of one night is
     * addressable independently of the next.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stage under the date partition and a zero-padded generation segment")
    void theStagedCoordinateCarriesTheDatePartitionAndPaddedGeneration() throws Exception {
        masterHolds(transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        DatasetGeneration staged = generation(DatasetFamily.TRANSACT_BKUP, ALLOCATED_GENERATION);
        assertThat(staged.datePartitionSegment()).isEqualTo("dt=" + BUSINESS_DATE_TOKEN);
        assertThat(staged.generationSegment()).isEqualTo("gen=0001");
        assertThat(staged.keyPrefix())
                .startsWith(DatasetFamily.TRANSACT_BKUP.pathSegment())
                .endsWith("dt=" + BUSINESS_DATE_TOKEN + "/gen=0001/");
        verify(this.generations).stageDataset(eq(staged),
                eq(BackupTransactionsJob.DATASET_OBJECT_NAME), any(Path.class));

        // WHY : Assumptions: the retention of five noncurrent versions -- the analogue of the
        //       LIMIT(5) SCRATCH the base carries at app/jcl/DEFGDGB.jcl:26-27 -- is provisioned by
        //       infra/modules/s3-datasets and asserted there, so no expectation about it is placed on
        //       this job. The retained window itself belongs to the generation resolver's own tier
        //       test, which settles it against a store; what is decidable here is only which
        //       coordinate this job asked for.
        assertThat(staged.family()).isEqualTo(DatasetFamily.TRANSACT_BKUP);
    }

    /**
     * An empty master produces a valid empty generation and a clean run.
     *
     * <p>Pins the copy step at {@code app/jcl/TRANBKP.jcl:23-33} over an empty input. An empty nightly
     * backup is a legitimate outcome rather than an error -- a day with no posted transactions is a
     * quiet day, not a broken one -- and the generation still has to be allocated and staged so that the
     * step which reads the backup finds an artifact instead of a missing dataset. The tolerance at
     * {@code :51} exists to admit exactly this kind of unremarkable outcome rather than to treat it as a
     * failure.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("produce a valid empty generation over an empty master")
    void anEmptyMasterProducesAValidEmptyGeneration() throws Exception {
        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.reportedTier).isEqualTo(BatchReturnCode.CLEAN);
        assertThat(this.stagedImage)
                .as("an empty backup is an empty artifact, not an absent one")
                .isEmpty();
        verify(this.generations).allocateNewGeneration(
                DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, RUN_ID);
        verify(this.generations).stageDataset(any(DatasetGeneration.class),
                eq(BackupTransactionsJob.DATASET_OBJECT_NAME), any(Path.class));
    }

    /**
     * The durable step ledger is entered once per run-and-step pair, and a repeat is a no-op.
     *
     * <p>Refactoring Rationale: the durable step record is a strict IMPROVEMENT on the reference rather
     * than a port of anything, and saying so matters because a reader looking for the baseline
     * equivalent will not find one. The only restart specification anywhere in the thirty-eight
     * job-control files is commented out, at {@code app/jcl/DEFGDGD.jcl:2}, and no checkpoint
     * specification appears in any of them, so a rerun of the reference chain re-executed every step it
     * reached. Keying the record on the run and the step is what lets an orchestrator redrive a night
     * from the state that failed and have the states before it do nothing, and idempotence matters more
     * here than in a step that only writes rows: a re-execution would allocate a second generation for
     * the same night, spending one of five retained slots on a duplicate and ageing out a genuinely
     * older backup one night early.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("enter the durable step ledger once per run and step pair")
    void theDurableLedgerRecordsOneEntryForTheRunAndStepPair() throws Exception {
        masterHolds(transactionRow("0000000000000001", new BigDecimal("1.00"), "4859452612877065"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        verify(this.ledgerOfSteps, times(1)).runStep(eq(RUN_ID), eq(BackupTransactionsJob.STEP_NAME),
                eq(BatchJobName.BACKUP_TRANSACTIONS), any());
        assertThat(this.evaluatedBodies).isOne();

        // WHY : Assumptions: the no-op half is asserted by having the ledger return a StepOutcome
        //       whose skipped component is true, which is how BatchStepLedger reports a pair it already
        //       holds a completion for. The body must then not run at all -- not run and be discarded
        //       -- so the assertion is that no second allocation happened rather than that the outcome
        //       was equal, since an allocation made and thrown away still consumes one of the retained
        //       slots.
        when(this.ledgerOfSteps.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenReturn(new BatchStepLedger.StepOutcome(BatchReturnCode.CLEAN, true));
        JobExecution repeat = runBackup(BUSINESS_DATE_TOKEN, INSTANCE_ID + 1, EXECUTION_ID + 1);

        assertThat(repeat.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.evaluatedBodies)
                .as("a completed pair must not be executed a second time")
                .isOne();
        verify(this.generations, times(1)).allocateNewGeneration(
                DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, RUN_ID);
    }

    /**
     * The emitted image is identical under a different business date and reads no clock.
     *
     * <p>Pins the absence of a {@code PARM=} on {@code app/jcl/TRANBKP.jcl}. The only reference step in
     * the chain that passes a business date is {@code app/jcl/INTCALC.jcl:22},
     * {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}; this job's steps take no parameter at all,
     * so no value of the date may reach the copied bytes. Every timestamp in the image comes from the
     * copied rows, which is what makes this image the one artifact of the chain that needs no timestamp
     * normalisation before it can be compared -- unlike the posting and interest images, whose
     * run-generated stamps have to be masked first.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit the same bytes under a different business date, reading no clock")
    void theEmittedImageIgnoresTheBusinessDateValueAndReadsNoClock() throws Exception {
        masterHolds(
                transactionRow("0000000000000001", new BigDecimal("504.77"), "4859452612877065"),
                transactionRow("0000000000000002", new BigDecimal("-125.50"), "4111111111111111"));

        runBackup();
        byte[] underFirstDate = this.stagedImage;
        runBackup(ALTERNATE_BUSINESS_DATE_TOKEN, INSTANCE_ID + 1, EXECUTION_ID + 1);

        // WHY : Assumptions: the date is still a REQUIRED job parameter even though no step of
        //       app/jcl/TRANBKP.jcl carries one, so this case asserts independence of its VALUE rather
        //       than a launch without it. BatchConfig.carddemoJobParametersValidator requires the token
        //       for every job in this module because BatchApplication also adds it as an IDENTIFYING
        //       parameter, which is what makes one night a distinct job instance from the next; a case
        //       asserting that this job launches without the token would contradict the validator it is
        //       driving. What the absent PARM= does entail -- against app/jcl/INTCALC.jcl:22, the one
        //       reference step that does pass a date -- is that the token cannot influence the copied
        //       bytes, and that is decidable.
        assertThat(this.stagedImage)
                .as("no part of the business date or of a clock may reach the copied bytes")
                .isEqualTo(underFirstDate);
        assertThat(this.stagedImage).hasSize(2 * RECORD_LENGTH);
    }

    // WHY : Assumptions: the three staged families are asserted by OBJECT NAME rather than by counting
    //       staging calls, because the count alone would pass over a run that staged the same family
    //       three times. The object name is what distinguishes the artifacts inside a generation
    //       coordinate, so it is the property a consumer resolves and the one worth pinning.
    /**
     * The one step stages all three families the reference derives from a single unload.
     *
     * <p>Pins the consolidation of three reference jobs into state six. {@code app/jcl/TRANBKP.jcl:33}
     * writes {@code TRANSACT.BKUP(+1)}; {@code app/jcl/TRANREPT.jcl:29-55} unloads the master AGAIN and
     * sorts it into {@code TRANSACT.DALY(+1)}; {@code app/jcl/PRTCATBL.jcl:29-39} unloads the category
     * balances into {@code TCATBALF.BKUP(+1)}. Two of those three families had no production writer at
     * all before this step took them over, so the prefixes and five-generation lifecycle rules
     * {@code infra/modules/s3-datasets} provisions for them governed nothing.</p>
     *
     * @throws Exception if the framework's own execution path raises
     */
    @Test
    @DisplayName("stage all three generation families the reference derives from one unload")
    void theStepStagesAllThreeFamilies() throws Exception {
        this.masterRows.add(transactionRow("TXN0000000000001", new BigDecimal("10.00"),
                "4111111111111111"));
        this.categoryBalanceRows.add(categoryBalanceRow(11L, "01", "0001", "25.00"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedImages.keySet())
                .as("one pass, three artifacts, each under its own family's object name")
                .containsExactlyInAnyOrder(
                        BackupTransactionsJob.DATASET_OBJECT_NAME,
                        BackupTransactionsJob.DAILY_DATASET_OBJECT_NAME,
                        BackupTransactionsJob.CATEGORY_BALANCE_DATASET_OBJECT_NAME);
        verify(this.generations).allocateNewGeneration(
                DatasetFamily.TRANSACT_BKUP, BUSINESS_DATE, RUN_ID);
        verify(this.generations).allocateNewGeneration(
                DatasetFamily.TRANSACT_DALY, BUSINESS_DATE, RUN_ID);
        verify(this.generations).allocateNewGeneration(
                DatasetFamily.TCATBALF_BKUP, BUSINESS_DATE, RUN_ID);
    }

    // WHY : Assumptions: the daily subset is asserted to be ORDERED BY CARD THEN IDENTIFIER, and the
    //       rows are seeded in neither order so the assertion cannot pass by accident. The card key is
    //       the reference's -- SORT FIELDS=(TRAN-CARD-NUM,A) at app/jcl/TRANREPT.jcl:46 -- and the
    //       identifier is the tie-break the target adds, registered as D-DALY-CARD-TIE-BREAK, because a
    //       single-key sort leaves the order within one card undetermined and a staged generation whose
    //       bytes differ between two runs over the same rows cannot serve as a comparison baseline.
    /**
     * The daily subset carries the reference's card ordering plus the registered identifier tie-break.
     *
     * @throws Exception if the framework's own execution path raises
     */
    @Test
    @DisplayName("order the daily subset by card number, then by transaction identifier")
    void theDailySubsetIsOrderedByCardThenIdentifier() throws Exception {
        LocalDateTime withinTheNight = LocalDateTime.of(2022, 7, 18, 3, 0, 0);
        this.masterRows.add(transactionRow("TXN0000000000004", "4222222222222222", withinTheNight));
        this.masterRows.add(transactionRow("TXN0000000000002", "4111111111111111", withinTheNight));
        this.masterRows.add(transactionRow("TXN0000000000003", "4222222222222222", withinTheNight));
        this.masterRows.add(transactionRow("TXN0000000000001", "4111111111111111", withinTheNight));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        byte[] daily = this.stagedImages.get(BackupTransactionsJob.DAILY_DATASET_OBJECT_NAME);
        assertThat(daily).hasSize(4 * RECORD_LENGTH);
        assertThat(identifiersOf(daily))
                .as("grouped by card, ascending by identifier inside each card")
                .containsExactly("TXN0000000000001", "TXN0000000000002",
                        "TXN0000000000003", "TXN0000000000004");
        assertThat(cardNumbersOf(daily))
                .containsExactly("4111111111111111", "4111111111111111",
                        "4222222222222222", "4222222222222222");
    }

    // WHY : Assumptions: the window is asserted to be HALF-OPEN at both edges with rows placed one
    //       microsecond inside and one microsecond outside. proc_ts is a TIMESTAMP(6) while the
    //       reference's own selection field is TRAN-PROC-DT,305,10,CH -- the first TEN characters of it,
    //       a date -- so a comparison that included the following midnight would carry a row the
    //       reference's INCLUDE at app/jcl/TRANREPT.jcl:47-48 excludes.
    /**
     * The daily subset covers exactly the business date, and the full copy is unaffected by the window.
     *
     * @throws Exception if the framework's own execution path raises
     */
    @Test
    @DisplayName("bound the daily subset to the business date while the full copy carries every row")
    void theDailySubsetIsBoundedToTheBusinessDate() throws Exception {
        this.masterRows.add(transactionRow("TXN0000000000001", "4111111111111111",
                LocalDateTime.of(2022, 7, 17, 23, 59, 59, 999_999_000)));
        this.masterRows.add(transactionRow("TXN0000000000002", "4111111111111111",
                LocalDateTime.of(2022, 7, 18, 0, 0, 0)));
        this.masterRows.add(transactionRow("TXN0000000000003", "4111111111111111",
                LocalDateTime.of(2022, 7, 18, 23, 59, 59, 999_999_000)));
        this.masterRows.add(transactionRow("TXN0000000000004", "4111111111111111",
                LocalDateTime.of(2022, 7, 19, 0, 0, 0)));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(identifiersOf(this.stagedImages.get(
                BackupTransactionsJob.DAILY_DATASET_OBJECT_NAME)))
                .as("the first instant of the date is in and the first instant of the next is out")
                .containsExactly("TXN0000000000002", "TXN0000000000003");
        assertThat(this.stagedImage)
                .as("the full copy is the whole master and is not filtered by the window")
                .hasSize(4 * RECORD_LENGTH);
    }

    // WHY : Assumptions: the balance generation is asserted at the FIFTY-byte record length declared by
    //       app/jcl/PRTCATBL.jcl:38 and by app/cpy/CVTRA01Y.cpy, not merely as non-empty. The unload
    //       this family replaces is a REPROC of the cluster, so its records are the cluster's records
    //       byte for byte -- an image at any other length is not the artifact the sort step reads.
    /**
     * The category-balance generation carries fifty-byte records in the reference's sort order.
     *
     * @throws Exception if the framework's own execution path raises
     */
    @Test
    @DisplayName("stage the category balances at fifty bytes each, in the reference's sort order")
    void theCategoryBalanceGenerationCarriesFiftyByteRecords() throws Exception {
        this.categoryBalanceRows.add(categoryBalanceRow(22L, "02", "0003", "30.00"));
        this.categoryBalanceRows.add(categoryBalanceRow(11L, "01", "0002", "20.00"));
        this.categoryBalanceRows.add(categoryBalanceRow(11L, "01", "0001", "10.00"));

        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        byte[] balances =
                this.stagedImages.get(BackupTransactionsJob.CATEGORY_BALANCE_DATASET_OBJECT_NAME);
        assertThat(balances)
                .as("LRECL=50 at app/jcl/PRTCATBL.jcl:38, three records")
                .hasSize(3 * CATEGORY_BALANCE_RECORD_LENGTH);
        assertThat(categoryKeysOf(balances))
                .as("SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A) at PRTCATBL.jcl:52")
                .containsExactly("00000000011010001", "00000000011010002", "00000000022020003");
    }

    /**
     * An empty relation produces a valid empty generation for each of the three families.
     *
     * @throws Exception if the framework's own execution path raises
     */
    @Test
    @DisplayName("produce a valid empty generation per family when nothing was posted")
    void everyFamilyProducesAValidEmptyGeneration() throws Exception {
        JobExecution execution = runBackup();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.stagedImages).hasSize(3);
        assertThat(this.stagedImages.values()).allSatisfy(image -> assertThat(image).isEmpty());
        assertThat(this.reportedTier)
                .as("an empty night is clean, because app/jcl/TRANBKP.jcl gates nothing on a count")
                .isEqualTo(BatchReturnCode.CLEAN);
    }

    /**
     * Reads the transaction identifier out of every record of a staged image.
     *
     * @param image the staged bytes, a whole number of 350-byte records
     * @return the identifiers in the order the image carries them
     */
    private static List<String> identifiersOf(byte[] image) {
        return fieldOf(image, RECORD_LENGTH, 0, 16);
    }

    /**
     * Reads the card number out of every record of a staged image.
     *
     * <p>Assumptions: the offset is 262 and is stated as a literal here, matching the FILLER_OFFSET
     * constant's own posture in this class. It is the sum of the declared widths above
     * {@code TRAN-CARD-NUM} at {@code app/cpy/CVTRA05Y.cpy:16}, corroborated independently by
     * {@code TRAN-CARD-NUM,263,16,ZD} at {@code app/jcl/TRANREPT.jcl:41} in one-based positions.</p>
     *
     * @param image the staged bytes, a whole number of 350-byte records
     * @return the card numbers in the order the image carries them
     */
    private static List<String> cardNumbersOf(byte[] image) {
        return fieldOf(image, RECORD_LENGTH, 262, 16);
    }

    /**
     * Reads the seventeen-byte composite key out of every record of a staged balance image.
     *
     * @param image the staged bytes, a whole number of 50-byte records
     * @return the keys in the order the image carries them
     */
    private static List<String> categoryKeysOf(byte[] image) {
        return fieldOf(image, CATEGORY_BALANCE_RECORD_LENGTH, 0, 17);
    }

    /**
     * Reads one fixed field out of every record of a staged image.
     *
     * @param image the staged bytes, a whole number of {@code recordLength}-byte records
     * @param recordLength the fixed record length
     * @param offset the zero-based field offset within a record
     * @param width the field width
     * @return the field value per record, in image order
     */
    private static List<String> fieldOf(byte[] image, int recordLength, int offset, int width) {
        List<String> values = new ArrayList<>();
        for (int start = 0; start < image.length; start += recordLength) {
            values.add(new String(image, start + offset, width, StandardCharsets.US_ASCII));
        }
        return values;
    }

    /**
     * Builds a context runner holding this job configuration and a stand-in for each collaborator.
     *
     * <p>Assumptions: the runner carries ONLY this job configuration, so the single registered job bean
     * is this one and the census can be asserted exactly. The roster-wide agreement between the token
     * vocabulary and the landed job beans is settled across all seven configurations by
     * {@code JobRegistrationCensusTest}, and restating it here would put one ruling in two places.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return the configured runner, never {@code null}
     */
    private static ApplicationContextRunner registryRunner() {
        // WHY : Assumptions: the collaborators are stand-ins because this case asks whether the bean can
        //       be BUILT and NAMED, which is a wiring question that reads no data, and it is the same
        //       posture JobRegistrationCensusTest takes for the roster-wide census. Stub behaviour would
        //       suggest a body runs here, and none does -- the behavioural cases in this class construct
        //       BackupTransactionsJob directly instead, which is also what lets them assert call counts.
        return new ApplicationContextRunner()
                .withUserConfiguration(BatchConfig.class, BackupTransactionsJob.class)
                .withBean(TransactionRepository.class, () -> mock(TransactionRepository.class))
                .withBean(TransactionCategoryBalanceRepository.class,
                        () -> mock(TransactionCategoryBalanceRepository.class))
                .withBean(DatasetGenerationService.class, () -> mock(DatasetGenerationService.class))
                .withBean(BatchStepLedger.class, () -> mock(BatchStepLedger.class))
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withPropertyValues(
                        "carddemo.dataset.bucket=carddemo-datasets-test",
                        BatchApplication.RUN_ID_VARIABLE + "=" + RUN_ID);
    }

    /**
     * Runs the backup job once under the standard business date, instance and execution identifiers.
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution runBackup() throws Exception {
        return runBackup(BUSINESS_DATE_TOKEN, INSTANCE_ID, EXECUTION_ID);
    }

    /**
     * Runs the backup job once with both required parameters and returns its execution.
     *
     * <p>Assumptions: the instance and the execution are constructed directly and registered before the
     * job is driven, matching the two sibling job tests in this package, because the repository's own
     * creation method takes an instance rather than a name. Driving the job through the framework rather
     * than by calling its body is deliberate: the parameter validator it was built with, the step
     * lifecycle and the propagation of a step failure into the job's status are all part of what the
     * orchestrator relies on, and a direct call would bypass all three.</p>
     *
     * @param businessDateToken the ten-character business-date token to inject as the identifying job
     *     parameter; must be one of the two committed layouts
     * @param instanceId the long job-instance identifier to register under, varied by callers that run
     *     the job more than once so the second run is a distinct instance
     * @param executionId the long job-execution identifier to register under, varied for the same
     *     reason as the instance identifier
     * @return the finished job execution, never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution runBackup(String businessDateToken, long instanceId, long executionId)
            throws Exception {

        Job job = new BackupTransactionsJob(this.ledger, this.categoryBalances, this.generations,
                this.ledgerOfSteps)
                .backupTransactions(this.jobRepository, new ResourcelessTransactionManager(),
                        this.validator);

        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, businessDateToken, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();

        JobInstance instance = new JobInstance(instanceId, BackupTransactionsJob.JOB_NAME);
        JobExecution execution = new JobExecution(executionId, instance, parameters);
        this.jobRepository.update(execution);
        job.execute(execution);
        return execution;
    }

    /**
     * Builds one generation coordinate of a family under the injected business date.
     *
     * @param family the {@code DatasetFamily} the coordinate belongs to; must not be {@code null}
     * @param number the int generation number the coordinate carries, which must be inside the range
     *     the coordinate type accepts
     * @return the coordinate, never {@code null}
     */
    private static DatasetGeneration generation(DatasetFamily family, int number) {
        return new DatasetGeneration(family, BUSINESS_DATE, number);
    }

    /**
     * Seeds the transaction master with the rows a case copies.
     *
     * <p>Assumptions: the rows are held in a list this class owns rather than handed to the repository
     * as a prepared stream, because the deletion ruling needs the master to be STATE that a mutating
     * call would visibly disturb, and because two cases read it twice -- once through the job and once
     * to prove the rows survived. A prepared stream is consumed by the first read.</p>
     *
     * @param rows the {@code Transaction} rows to place in the master, in any order, since the stubbed
     *     finder sorts them into the key order the repository contract promises
     */
    private void masterHolds(Transaction... rows) {
        this.masterRows.clear();
        this.masterRows.addAll(Arrays.asList(rows));
    }

    /**
     * Encodes the master's current rows so two snapshots can be compared byte for byte.
     *
     * <p>Assumptions: the comparison is made on ENCODED images rather than on the entity instances,
     * because the entities are the same objects across the run and comparing them to themselves would
     * pass whatever happened to their fields. Encoding forces every field through the record form, so a
     * field the job altered shows up as differing bytes.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return one 350-byte image per row, in the master's key order, never {@code null}
     */
    private List<byte[]> encodedMaster() {
        List<byte[]> images = new ArrayList<>();
        this.masterRows.stream()
                .sorted((left, right) -> left.getTransactionId().compareTo(right.getTransactionId()))
                .forEach(row -> images.add(TransactionRecordMapper.toRecord(row)));
        return images;
    }

    /**
     * Slices one fixed-length record out of the captured image.
     *
     * @param index the int zero-based ordinal of the record within the image, counted in 350-byte
     *     records because a fixed-blocked dataset carries no separator to count instead
     * @return the record's 350 bytes, never {@code null}
     */
    private byte[] recordAt(int index) {
        int start = index * RECORD_LENGTH;
        return Arrays.copyOfRange(this.stagedImage, start, start + RECORD_LENGTH);
    }

    /**
     * Reads one field's raw characters out of a record of the captured image.
     *
     * <p>Assumptions: the offset and the width come from the descriptor the sanctioned layout registry
     * publishes rather than from constants restated here, so a case cannot drift away from the copybook
     * by agreeing with its own arithmetic. The characters are returned untrimmed, because trailing
     * blanks are part of the fixed-width contract and one case asserts them directly.</p>
     *
     * @param recordIndex the int zero-based ordinal of the record within the captured image
     * @param fieldName the copybook name of the field to read, which must be one the descriptor
     *     declares
     * @return the field's characters at their declared width, never {@code null}
     */
    private String fieldText(int recordIndex, String fieldName) {
        CopybookLayout.FieldSpec field = TRANSACTION_SPEC.field(fieldName);
        return new String(recordAt(recordIndex), field.start(), field.length(),
                StandardCharsets.US_ASCII);
    }

    /**
     * Builds one fully populated transaction row whose key, amount and card number a case chooses.
     *
     * <p>Assumptions: every field other than the three parameters is fixed here, and fixed at values
     * whose widths are known to fit the layout's fixed-width fields. Two of them are timestamps, and
     * they are set EXPLICITLY rather than left absent: this job copies persisted rows and reads no
     * clock, so pinning both stamps in the input is what makes the emitted image fully deterministic and
     * lets it be compared with no normalisation at all -- the property the image-independence case
     * asserts.</p>
     *
     * @param transactionId the String key of exactly sixteen characters, which is also the field the
     *     image is ordered by
     * @param amount the {@code BigDecimal} signed amount at scale 2, which a case sets negative to
     *     exercise the sign overpunch; money is exact fixed point here and never a binary
     *     approximation
     * @param cardNum the String card number of exactly sixteen characters
     * @return the populated row, never {@code null}
     */
    private static Transaction transactionRow(String transactionId, BigDecimal amount,
            String cardNum) {

        Transaction row = new Transaction(transactionId);
        row.setTypeCd("01");
        row.setCategoryCd("0001");
        row.setSource("POS TERM");
        row.setDescription(POSTED_DESCRIPTION);
        row.setAmount(amount);
        row.setMerchantId(800000000L);
        row.setMerchantName("Abshire-Lowe");
        row.setMerchantCity("North Enoshaven");
        row.setMerchantZip("72112");
        row.setCardNum(cardNum);
        row.setOrigTs(LocalDateTime.of(2022, 6, 10, 19, 27, 53));
        row.setProcTs(LocalDateTime.of(2022, 7, 18, 2, 15, 30));
        return row;
    }

    /**
     * Reads the trailing pad bytes of one record of the captured image.
     *
     * <p>Assumptions: the span comes from the registered descriptor rather than from the two offset
     * constants this class also declares, so a descriptor drift fails the case instead of agreeing with
     * arithmetic written here. The bytes are returned rather than a String, because the byte under
     * assertion is not a printable character and a String comparison would hide the difference between
     * a low value and a blank in some renderings.</p>
     *
     * @param recordIndex the int zero-based ordinal of the record within the captured image
     * @return the record's twenty trailing pad bytes, never {@code null}
     */
    private byte[] padOf(int recordIndex) {
        CopybookLayout.FieldSpec pad = TRANSACTION_SPEC.field("FILLER");
        return Arrays.copyOfRange(recordAt(recordIndex), pad.start(), pad.end());
    }

    /**
     * Builds one row carrying the attribution the interest accrual pass writes onto what it generates.
     *
     * <p>Assumptions: the source and the description are the reference's own, taken from
     * {@code app/cbl/CBACT04C.cbl:484-489}, because they are what identifies the writing producer to a
     * flow re-emitting the row -- a relational row carries the description's text and not the bytes
     * behind it. Building the row from those two literals rather than from a target-invented marker is
     * what makes this case test the recogniser production uses.</p>
     *
     * @param transactionId the String key of exactly sixteen characters, which for an accrual row is
     *     the business-date token followed by a six-digit run-scoped suffix
     * @param amount the {@code BigDecimal} accrued amount at scale 2, exact fixed point and never a
     *     binary approximation
     * @param cardNum the String card number of exactly sixteen characters
     * @return the populated row, never {@code null}
     */
    private static Transaction interestRow(String transactionId, BigDecimal amount, String cardNum) {
        Transaction row = new Transaction(transactionId);
        row.setTypeCd("01");
        row.setCategoryCd("0005");
        row.setSource("System");
        row.setDescription(ACCRUAL_DESCRIPTION);
        row.setAmount(amount);
        row.setMerchantId(0L);
        row.setMerchantName("");
        row.setMerchantCity("");
        row.setMerchantZip("");
        row.setCardNum(cardNum);
        row.setOrigTs(LocalDateTime.of(2022, 7, 18, 2, 15, 30));
        row.setProcTs(LocalDateTime.of(2022, 7, 18, 2, 15, 30));
        return row;
    }

    /**
     * Builds one posted transaction with an explicit processing instant, for the window cases.
     *
     * @param transactionId the sixteen-character identifier
     * @param cardNum the sixteen-digit card number, the daily subset's leading sort key
     * @param procTs the processing instant the half-open window is evaluated against
     * @return the row
     */
    private static Transaction transactionRow(
            String transactionId, String cardNum, LocalDateTime procTs) {

        Transaction row = transactionRow(transactionId, new BigDecimal("100.00"), cardNum);
        row.setProcTs(procTs);
        return row;
    }

    /**
     * Builds one transaction-category balance row.
     *
     * @param accountId the eleven-digit account identifier
     * @param typeCd the two-character transaction type code
     * @param categoryCd the four-character transaction category code
     * @param balance the category balance at scale two
     * @return the row
     */
    private static TransactionCategoryBalance categoryBalanceRow(
            long accountId, String typeCd, String categoryCd, String balance) {

        return new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(accountId, typeCd, categoryCd),
                new BigDecimal(balance));
    }
}
