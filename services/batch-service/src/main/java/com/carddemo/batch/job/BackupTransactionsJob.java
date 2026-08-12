package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.mapper.TransactionRecordMapper;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.batch.service.DatasetGenerationService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Copies the whole transaction master into a new generation of the backup family.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this job is state six of the nightly chain and re-expresses
 * {@code app/jcl/TRANBKP.jcl}. <b>No COBOL program stands behind it.</b> The reference copy step at
 * {@code app/jcl/TRANBKP.jcl:23} reads {@code //STEP05R EXEC PROC=REPROC}, a catalogued procedure
 * resolved through the library named at line 19, rather than a program, so a reader should not go
 * looking in {@code app/cbl/} for a source file that does not exist. The procedure copies the
 * transaction master, named {@code DISP=SHR} at lines 26 to 27, into
 * {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} at line 33 -- a NEW generation of a base defined
 * {@code LIMIT(5)} with {@code SCRATCH}. This job does both halves of that: it stages the copy as a
 * new generation and it scratches whatever that generation pushed out of the retained window.</p>
 *
 * <h2>The reference job has three steps, and only the first one survives</h2>
 *
 * <p>The three steps together mean <i>back up the master, then wipe it and re-create it empty</i>:</p>
 *
 * <ol>
 *   <li><b>Copy</b> -- {@code app/jcl/TRANBKP.jcl:23-33}, the catalogued procedure above, writing the
 *       next generation at {@code LRECL=350,RECFM=FB} (line 31). <b>This is the only step with a
 *       migrated counterpart</b>, and it is what the body of this class performs.</li>
 *   <li><b>Delete</b> -- {@code app/jcl/TRANBKP.jcl:37-45}, an {@code IDCAMS} step deleting the
 *       master {@code CLUSTER} (lines 40 to 41) and then its {@code ALTERNATEINDEX} (lines 43 to
 *       44). Retired; see the section below.</li>
 *   <li><b>Re-define</b> -- {@code app/jcl/TRANBKP.jcl:51-67}, a second {@code IDCAMS} step whose
 *       {@code DEFINE CLUSTER} re-creates the master EMPTY with {@code KEYS(16 0)} at line 58 and
 *       {@code RECORDSIZE(350 350)} at line 59. Retired; see the section below.</li>
 * </ol>
 *
 * <p>Assumptions: the key declaration {@code KEYS(16 0)} at line 58 is a sixteen-byte key at offset
 * zero, which is the transaction identifier -- {@code TRAN-ID PIC X(16)} is the first field of
 * {@code app/cpy/CVTRA05Y.cpy:5}. That is the same key the combine step sorts on, declared as
 * {@code TRAN-ID,1,16,CH} at {@code app/jcl/COMBTRAN.jcl:28}, so the order this job writes in is the
 * master's own physical order rather than an ordering chosen here.</p>
 *
 * <p>Assumptions: the reference's own comments at {@code app/jcl/TRANBKP.jcl:35} and {@code :49}
 * spell the word "TRANSACATION". The misspelling is quoted here only to keep the citations
 * verifiable against the file; it is <b>not</b> propagated into any identifier in this module, and it
 * is not one of the three baseline field misspellings that the schema mapping deliberately corrects.
 * It is a comment in an immutable reference file and nothing depends on it.</p>
 *
 * <h2>Why the wipe is not reproduced, and the hazard that makes the reason load-bearing</h2>
 *
 * <p>Refactoring Rationale: steps two and three exist to serve VSAM's physical model, and that model
 * is gone. An alternate index in VSAM is a SEPARATE PHYSICAL OBJECT that has to be deleted alongside
 * its base cluster and rebuilt afterwards, which is why line 43 deletes one; and re-issuing
 * {@code DEFINE CLUSTER} is simply how one obtains an empty dataset there. PostgreSQL maintains
 * indexes TRANSACTIONALLY, in the same commit as the row change, so there is no index to drop and
 * none to rebuild -- the migration's transformation rule T6 retires {@code IDCAMS BLDINDEX} for
 * exactly this reason, and the batch chain replaces the reference's index-building job with a
 * {@code VACUUM ANALYZE} state that only refreshes planner statistics. A table also does not need to
 * be dropped in order to be emptied. Neither retired step therefore has anything to translate INTO,
 * and this job does not truncate the transaction table, does not drop an index, and does not
 * re-create anything.</p>
 *
 * <p>Trade-offs: <b>the wipe was not gratuitous, and omitting it is safe only because of a decision
 * taken in a different class.</b> In the reference, the wipe exists so that the load-back at
 * {@code app/jcl/COMBTRAN.jcl:48} -- {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)}, which copies
 * the combined dataset back into the master -- does not DOUBLE every row. The reference had to
 * assemble the merge physically: its posted transactions were in the master and its generated
 * interest transactions were in a separate {@code SYSTRAN} dataset
 * ({@code app/jcl/INTCALC.jcl:41}), so it emptied the master and reloaded it from the sort output.
 * In the migrated model that merge is already materialised in the table itself, because
 * {@code PostTransactionsJob} inserts the day's transactions and {@code CalculateInterestJob}
 * inserts the generated interest rows into the same relation, and
 * {@link CombineTransactionsJob} therefore stages its ordered extract WITHOUT reloading anything.
 * That paired omission is registered as {@code D-COMBINE-NO-LOADBACK} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Trade-offs: the failure mode this pairing guards against is worth stating plainly, because a
 * maintainer can reintroduce it while touching only one of the two files. <b>If a future change ever
 * makes the combine step reload the transaction table, then omitting the wipe here would double
 * every row.</b> The two decisions are one decision written in two places: remove either half alone
 * and the ledger is corrupted, silently, in a way no compiler or type reports. The mirror-image note
 * lives on {@link CombineTransactionsJob}, and neither note should be deleted without the other.</p>
 *
 * <p>Assumptions: each of the two deletions -- the cluster at {@code app/jcl/TRANBKP.jcl:40-41} and
 * the alternate index at {@code :43-44} -- is followed by {@code IF MAXCC LE 08 THEN SET MAXCC = 0},
 * at lines 42 and 45 respectively. That is the reference's idiom for "delete it if it exists, and do
 * not fail if it does not" -- a tolerated-not-found pattern rather than blanket error suppression,
 * since it normalises an already-tolerated code back to clean instead of masking a real failure. It
 * is recorded because it is what leaves the job's accumulated code low enough for the gate on the
 * following step to admit it.</p>
 *
 * <h2>The soft-warn continuation gate this job's driver carries, and where it went</h2>
 *
 * <p>Assumptions: {@code app/jcl/TRANBKP.jcl:51} reads {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)},
 * and it is the <b>only</b> {@code COND=(4,LT)} in the whole reference job library. An exhaustive
 * scan of the thirty-eight files in {@code app/jcl/} finds exactly TEN {@code COND=} occurrences:
 * this one, eight of the form {@code COND=(0,NE)} ({@code app/jcl/TXT2PDF1.JCL:26},
 * {@code app/jcl/CREASTMT.JCL:56}, {@code :66}, {@code :79}, {@code app/jcl/DEFGDGD.jcl:36},
 * {@code :47}, {@code :59}, {@code :82}), and one that is not a step gate at all.</p>
 *
 * <p>Refactoring Rationale: <b>a JCL condition is a SKIP predicate and an orchestration choice is a
 * RUN predicate, so the sense inverts.</b> Read literally, {@code COND=(4,LT)} skips the step when 4
 * is less than the accumulated return code; its complement -- the condition under which the step
 * actually RUNS -- is therefore {@code rc <= 4}. That is precisely the soft-warn continuation gate: a
 * run in which {@code PostTransactionsJob} finished in the warn tier because it wrote rejects still
 * proceeds. Tier 4 originates in exactly one place in the reference,
 * {@code app/cbl/CBTRN02C.cbl:229-230}, where a non-zero reject count selects
 * {@code MOVE 4 TO RETURN-CODE}. Transcribing the clause as written, as though it were already a run
 * predicate, would produce a chain that halts on exactly the outcome the reference lets continue --
 * which is the single easiest thing in this migration to write backwards.</p>
 *
 * <p>Assumptions: for contrast, and because the keyword is identical while the construct is not,
 * {@code COND=(0,NE)} skips unless the prior code was zero -- it runs only on a clean predecessor,
 * the default success edge -- whereas {@code app/jcl/TRANREPT.jcl:47},
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,...)}, is a DFSORT RECORD-SELECTION
 * predicate that becomes a SQL {@code WHERE} clause and <b>must never be modelled as a choice
 * state</b>. Conflating a record filter with a step gate is a real hazard precisely because both are
 * spelled {@code COND=}.</p>
 *
 * <p>Assumptions: the gate is decided by the PREDECESSOR's tier, not by this job's, so it belongs to
 * the orchestration state machine that {@code infra/modules/step-functions-batch} owns and
 * deliberately <b>does not appear as a condition in this file</b>. Nothing here reads a predecessor's
 * return code. This job's only obligation to that gate is to report cleanly when the copy succeeded,
 * so a downstream {@code rc <= 4} predicate evaluates against a code it can admit; it reports
 * {@link BatchReturnCode#CLEAN} or it raises, and it never reports the warn tier, because a copy
 * either happened or did not.</p>
 *
 * <h2>Why the copy targets an object-store generation rather than another table</h2>
 *
 * <p>Alternatives Considered: a table-to-table copy inside PostgreSQL is the obvious alternative --
 * {@code CREATE TABLE ... AS SELECT} into a dated backup table -- and it was rejected. The artefact
 * this step produces is an ARCHIVAL GENERATION: it is consumed by a later step in the chain, it is
 * compared byte for byte against the reference's own dataset by the migration's verification
 * harness, and it is retained under a five-generation policy. A versioned object store expresses
 * generation-and-retention natively, whereas a copy table would need bespoke naming and bespoke
 * pruning logic hand-written for it, and -- decisively -- it would sit INSIDE the very transactional
 * datastore the backup exists to protect, so the one failure a backup is for would take the backup
 * with it.</p>
 *
 * <h2>Retention: five generations, and a rolled-off one is really gone</h2>
 *
 * <p>Assumptions: the retained window is five, and roll-off is a PHYSICAL DELETE rather than a mere
 * uncataloguing. The base for this family is defined at {@code app/jcl/DEFGDGB.jcl:25-27} as
 * {@code NAME(AWS.M2.CARDDEMO.TRANSACT.BKUP)} with {@code LIMIT(5)} on line 26 and an explicit
 * {@code SCRATCH} on line 27; {@code SCRATCH} is the operand that makes the aged-out generation
 * deleted rather than merely dropped from the catalogue. Every one of the ten
 * {@code DEFINE GENERATIONDATAGROUP} statements across {@code app/jcl/DEFGDGB.jcl},
 * {@code app/jcl/DEFGDGD.jcl} and {@code app/jcl/DALYREJS.jcl} pairs {@code LIMIT(5)} with
 * {@code SCRATCH} the same way. The target analogue is bucket versioning with a lifecycle rule that
 * retains five noncurrent versions and then expires them, which is why this job actively removes what
 * {@link DatasetGenerationService#generationsToScratch(DatasetFamily)} names instead of leaving the
 * family to grow.</p>
 *
 * <p>Assumptions: one reference artefact disagrees with that reading, and it is recorded here rather
 * than smoothed over so a reader who finds it does not think this file got the operand wrong. The
 * catalogue LISTING at {@code app/catlg/LISTCAT.txt:1637} reports this very base as
 * {@code LIMIT------5  NOSCRATCH}, the opposite of what its own defining job asks for. The
 * definition is taken as normative: a {@code LISTCAT} is a point-in-time report of one system whose
 * own history block on that entry records a later alteration, whereas the JCL is the reproducible
 * definition this migration is asked to encode, and the retain-then-expire lifecycle the plan
 * specifies follows the {@code SCRATCH} form.</p>
 *
 * <h2>The record form is the contract, not a serialisation detail</h2>
 *
 * <p>Assumptions: the staged generation's whole purpose is to be read back by something that is not
 * this module -- the combine step reads it, and the verification harness compares it against the
 * reference's output -- so <b>its bytes are the interface</b>. Every record is therefore written in
 * the fixed-width form the reference's own datasets carry, through
 * {@link TransactionRecordMapper}, and not in any more convenient serialisation. Two properties of
 * that form are easy to break silently and neither is negotiable: each record is EXACTLY the 350
 * bytes that {@code app/jcl/TRANBKP.jcl:31} declares as {@code LRECL=350,RECFM=FB} and that
 * {@code app/cpy/CVTRA05Y.cpy:2} states as {@code RECLN = 350}, including the trailing
 * {@code FILLER PIC X(20)} at offset 330 which is preserved as spaces; and the monetary field
 * {@code TRAN-AMT PIC S9(09)V99} is zoned decimal carrying its sign as an overpunch on the final
 * digit rather than as a leading character.</p>
 *
 * <p>Trade-offs: both concerns are delegated to the mapper and the shared codecs rather than handled
 * here, and that boundary is deliberate. Trailing-blank stripping is the specific regression to
 * avoid -- the parity harness records at {@code tests/helpers/golden_compare.py:22-28} that stripping
 * trailing whitespace collapses a 350-byte record to roughly 278 characters, which was a real defect
 * in that harness -- and the sign convention is the other, since {@code tests/README.md} records that
 * the default ASCII convention misreads the overpunch and silently corrupts negative balances. Both
 * are the kind of fault that produces plausible-looking output rather than an error, so they are
 * expressed once, in the anti-corruption layer, instead of once per writer.</p>
 *
 * <h2>This job writes nothing to the database</h2>
 *
 * <p>Assumptions: this is a read-and-export step. It performs no insert, update or delete against
 * {@code ledger} or {@code account}, so there is deliberately NO business transaction boundary in
 * this class and a reader should not go looking for the one that is missing. The only durable write
 * the step makes is to the {@code batch.batch_run} ledger, and that is made by
 * {@link BatchStepLedger} under the conventions {@link BatchConfig} establishes, which is also what
 * supplies the transaction manager the step runs under.</p>
 *
 * <h2>Baseline lineage: provenance only</h2>
 *
 * <p>Every citation in this file is provenance. Nothing under {@code app/**} is read at run time and
 * nothing under it is altered by this migration -- the reference implementation is the behavioural
 * oracle and stays byte-identical. Where migrated behaviour differs, the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Line numbers refer to the source as
 * committed, and in a COBOL or JCL line columns 73 to 80 carry a sequence field that is not part of
 * the statement.</p>
 */
@Configuration
public class BackupTransactionsJob {

    /** The name this job registers under, taken from the shared token enumeration. */
    public static final String JOB_NAME = BatchJobName.BACKUP_TRANSACTIONS.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = JOB_NAME + BatchJobName.STEP_NAME_SUFFIX;

    /** The name of the single object each staged generation of this family holds. */
    public static final String DATASET_OBJECT_NAME = "transact.bkup";

    /** The prefix of the temporary file the copy is streamed into before upload. */
    private static final String STAGING_FILE_PREFIX = "carddemo-transact-bkup-";

    /** The operational log this job reports its record counts through. */
    private static final Logger LOG = LoggerFactory.getLogger(BackupTransactionsJob.class);

    /** The transaction master being copied. */
    private final TransactionRepository ledger;

    /** The resolver that allocates the new generation and applies the retention rule. */
    private final DatasetGenerationService generations;

    /** The durable step record that makes a re-run of a completed step a no-op. */
    private final BatchStepLedger ledgerOfSteps;

    /**
     * Builds the job over the master it copies and the generation resolver it stages through.
     *
     * @param ledger the transaction master this job reads, ordered by transaction identifier; must
     *     not be {@code null}
     * @param generations the generation resolver that allocates the new generation, stages bytes
     *     into it and names the aged-out generations; must not be {@code null}
     * @param ledgerOfSteps the durable step ledger that makes a re-run of a completed step a no-op;
     *     must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public BackupTransactionsJob(TransactionRepository ledger,
            DatasetGenerationService generations, BatchStepLedger ledgerOfSteps) {

        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.generations = Objects.requireNonNull(generations, "generations must not be null");
        this.ledgerOfSteps = Objects.requireNonNull(ledgerOfSteps, "ledgerOfSteps must not be null");
    }

    /**
     * Registers the job under the token the orchestrator names it by.
     *
     * @param jobRepository the framework's durable job repository; must not be {@code null}
     * @param transactionManager the transaction manager the step runs under; must not be
     *     {@code null}
     * @param validator the shared parameter validator that requires both job parameters; must not be
     *     {@code null}
     * @return the registered job, never {@code null}
     */
    @Bean
    // WHY : Refactoring Rationale: the factory method is named WITHOUT the "Job" suffix its class
    //       carries, and the difference is load-bearing rather than cosmetic. A `@Configuration`
    //       class registered by type takes a bean id from its own decapitalised class name, so a
    //       `@Bean` method spelled `backupTransactionsJob` inside `BackupTransactionsJob` claims the
    //       identifier the class itself already holds -- and Spring refuses the context with a
    //       BeanDefinitionOverrideException rather than choosing between them. Dropping the suffix
    //       gives the two definitions distinct identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The entry point iterates the
    //       `Job` beans and compares `getName()` against its `--job=` argument, and `getName()` comes
    //       from JOB_NAME, which is derived from the shared token enumeration rather than spelled
    //       here. The bean identifier is therefore free to change and the job's published token is
    //       not.
    public Job backupTransactions(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JobParametersValidator validator) {

        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .start(new StepBuilder(STEP_NAME, jobRepository)
                        .tasklet(this::runStep, transactionManager)
                        .build())
                .build();
    }

    /**
     * Runs the copy once, under the durable step record that makes a completed re-run a no-op.
     *
     * @param contribution the framework's handle for reporting this step's exit status; required by
     *     the tasklet contract and not consulted here, because the step reports success by returning
     *     and failure by raising
     * @param context the chunk context carrying the two required job parameters; must not be
     *     {@code null}
     * @return {@link RepeatStatus#FINISHED} always, because the copy is one indivisible pass rather
     *     than a chunked loop the framework should re-enter
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context) {
        String runId = BatchConfig.runIdOf(context);
        BusinessDate businessDate = BatchConfig.businessDateOf(context);

        // WHY : Assumptions: the body runs through the durable (runId, stepName) ledger so that a
        //       state the orchestrator redrives after a downstream failure finds its own completed
        //       record and does nothing. Idempotency matters MORE here than in a step that only
        //       writes rows: a re-run that actually re-executed would allocate a SECOND (+1)
        //       generation for the same business date, and because the retained window is five, that
        //       spends one of five archival slots on a duplicate and ages out a genuinely older
        //       backup a generation early. The wasted slot is the harm, not the wasted work.
        this.ledgerOfSteps.runStep(runId, STEP_NAME, BatchJobName.BACKUP_TRANSACTIONS,
                () -> copyToNewGeneration(runId, businessDate));
        return RepeatStatus.FINISHED;
    }

    /**
     * Allocates the new generation, streams the master into it, then applies the retention rule.
     *
     * <p>The order of the three actions is itself the contract: allocating before staging is what
     * makes the bytes land under a coordinate that was reserved, and applying retention only after
     * staging is what stops the new generation from being counted as absent and six being kept.</p>
     *
     * @param runId the orchestrator execution the allocation is claimed under; must not be
     *     {@code null}
     * @param businessDate the injected business date the generation is partitioned under; must not
     *     be {@code null}
     * @return {@link BatchReturnCode#CLEAN}, because a copy either completes or raises -- this job
     *     has no soft-warn outcome of its own
     */
    private BatchReturnCode copyToNewGeneration(String runId, BusinessDate businessDate) {
        // WHY : Assumptions: `(+1)` is a request for a NEW generation, and the resolver -- not this
        //       job -- decides which number that is and memoises the answer against the run claim.
        //       A repeated request for the same family within one run therefore returns the IDENTICAL
        //       generation rather than advancing the counter again, which is what keeps a step that
        //       stages in more than one pass from consuming two of the five retained slots.
        // WHY : Assumptions: the business date is handed over as the opaque token it arrived as, and
        //       the `dt=YYYY-MM-DD` prefix segment is derived inside the coordinate type. That
        //       delegation is deliberate rather than incidental: BusinessDate's ISO-parsing accessor
        //       is documented to THROW for the compact ten-digit token, and the compact form is the
        //       reference baseline's own production parameter at `app/jcl/INTCALC.jcl:22`, so a
        //       prefix built on that accessor here would fail on the one input the baseline actually
        //       supplies. DatasetGeneration accepts both committed layouts and raises, naming the
        //       offending token, when it can honour neither -- so a malformed token fails loudly
        //       instead of yielding a malformed prefix.
        DatasetGeneration target = this.generations.allocateNewGeneration(
                DatasetFamily.TRANSACT_BKUP, businessDate, runId);

        Path staged = writeMasterToTemporaryFile();
        try {
            this.generations.stageDataset(target, DATASET_OBJECT_NAME, staged);
        } finally {
            // WHY : Assumptions: the temporary file is removed on every path, including a failed
            //       upload, because a batch task's ephemeral disk is finite and a failed step is
            //       retried. Leaving it would let a sequence of retries fill the volume and turn a
            //       transient upload failure into a task that cannot start at all.
            deleteQuietly(staged);
        }

        // WHY : Refactoring Rationale: the retention rule NAMES the aged-out generations and this
        //       job is what removes them, which is the split the reference had between `LIMIT(5)`
        //       declaring the window and the catalogue enforcing it. A job that consulted the rule
        //       and discarded its answer would leave the family growing without bound while every
        //       log line below reported a retention figure, so the count is accumulated from actual
        //       removals rather than from the size of the list.
        int scratched = 0;
        for (DatasetGeneration agedOut
                : this.generations.generationsToScratch(DatasetFamily.TRANSACT_BKUP)) {
            scratched += this.generations.scratchGeneration(agedOut);
        }

        LOG.info("event=batch.backup.completed generation={} scratchedObjects={} location={}",
                target.generationNumber(), scratched, this.generations.datasetUri(target));
        return BatchReturnCode.CLEAN;
    }

    /**
     * Streams every transaction, in key order, into a temporary file in the reference's record form.
     *
     * <p>Assumptions: the ordering is by transaction identifier ascending, which is not a choice made
     * here but the master's own physical order -- {@code KEYS(16 0)} at
     * {@code app/jcl/TRANBKP.jcl:58} over {@code TRAN-ID PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:5}. Because the key is the record's leading sixteen characters, a
     * character ordering of the reference's dataset and an ordering of the migrated column agree byte
     * for byte.</p>
     *
     * <p>Trade-offs: the copy streams through a temporary file rather than being assembled in memory.
     * A transaction master is as large as the ledger, so an in-memory copy would bound this step by
     * heap; ephemeral disk is what a batch task has more of, and a sequential file is what the
     * reference's own output dataset was. The cost accepted is a second write of the bytes -- once to
     * disk, once to the object store -- in exchange for a step whose ceiling is a provisioning
     * parameter rather than the container's heap.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return the temporary file holding the copy, never {@code null}
     * @throws IllegalStateException if the temporary file cannot be created, or if the master cannot
     *     be written to it; the message names the path so a container log identifies the failure
     *     without access to this source
     */
    private Path writeMasterToTemporaryFile() {
        final Path staged;
        try {
            staged = Files.createTempFile(STAGING_FILE_PREFIX, ".dat");
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "could not create the temporary file the transaction copy streams through",
                    unavailable);
        }

        try (OutputStream sink = Files.newOutputStream(staged)) {
            // WHY : Assumptions: the walk is a Stream closed by this try-with-resources rather than a
            //       materialised list, and closing it is not optional bookkeeping -- the query is
            //       backed by an open server-side cursor, so an unclosed stream pins a pooled
            //       connection for the remainder of the step and a batch pool is sized for the steps
            //       that run concurrently with it.
            try (Stream<Transaction> rows = this.ledger.findAllByOrderByTransactionIdAsc()) {
                // WHY : Trade-offs: the stream is adapted to an Iterable so the body can be a plain
                //       loop. The alternative, forEach with a lambda, cannot propagate the checked
                //       IOException that writing raises, so it would force the failure to be wrapped
                //       inside the lambda and rethrown -- which loses the single catch below that
                //       both cleans up the partial file and names the path.
                for (Transaction row : (Iterable<Transaction>) rows::iterator) {
                    sink.write(TransactionRecordMapper.toRecord(row));
                }
            }
        } catch (IOException unwritable) {
            // WHY : Assumptions: the partial file is removed before the failure propagates, because
            //       the caller's own cleanup is attached to the staging attempt that now never
            //       happens. Without this, a write that failed halfway would leave the file behind
            //       for the lifetime of the container.
            deleteQuietly(staged);
            throw new IllegalStateException(
                    "could not write the transaction copy to " + staged, unwritable);
        }

        return staged;
    }

    /**
     * Removes a temporary file, reporting rather than raising when it cannot be removed.
     *
     * <p>Trade-offs: a failed deletion is logged and swallowed rather than raised, because this runs
     * on paths that are already failing and re-raising there would replace the real failure with a
     * cleanup one -- the operator would see a deletion error instead of the upload error that caused
     * it. The consequence accepted is a file left behind, which is bounded by the container's
     * lifetime.</p>
     *
     * @param file the file to remove; must not be {@code null}
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException undeletable) {
            LOG.warn("event=batch.backup.temp-file-retained path={} reason={}",
                    file, undeletable.getClass().getName());
        }
    }

    /**
     * Reports the generation families this job stages into.
     *
     * <p>Assumptions: the list is stated here so that an operator or a test can learn which families
     * this job touches without reading its body or standing up its collaborators. It is exactly one
     * family, which is why the reference job needs no equivalent statement -- its single data
     * definition at {@code app/jcl/TRANBKP.jcl:29-33} says the same thing.</p>
     *
     * <p>This operation accepts no parameters.</p>
     *
     * @return an immutable list holding the single family this job writes, never {@code null} and
     *     never empty
     */
    public static List<DatasetFamily> stagedFamilies() {
        return List.of(DatasetFamily.TRANSACT_BKUP);
    }
}
