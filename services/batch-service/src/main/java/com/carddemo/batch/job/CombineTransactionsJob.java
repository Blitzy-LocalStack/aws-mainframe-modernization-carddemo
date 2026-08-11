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
import java.util.Objects;
import java.util.Optional;
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
 * Stages the posted and system-generated transactions together, in key order, as one combined generation.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this job is state seven of the nightly chain and re-expresses {@code app/jcl/COMBTRAN.jcl},
 * whose merge step at {@code app/jcl/COMBTRAN.jcl:22} invokes the sort utility, so there is no COBOL
 * program behind this state at all. The reference concatenates two inputs, {@code TRANSACT.BKUP(0)} at
 * {@code app/jcl/COMBTRAN.jcl:24} and {@code SYSTRAN(0)} at {@code :26}; orders the union by transaction
 * identifier ascending, declared {@code SORT FIELDS=(TRAN-ID,A)} at {@code :30} over the symbol
 * {@code TRAN-ID,1,16,CH} at {@code :28}; and writes {@code TRANSACT.COMBINED(+1)} at {@code :37}. A
 * second step at {@code :41-48} copies that combined dataset back into the transaction master, and this
 * job deliberately carries no counterpart to it, for the reason recorded two sections below.</p>
 *
 * <p>Assumptions: the staged records carry the fixed-width form of {@code app/cpy/CVTRA05Y.cpy}, 350 bytes
 * each including the trailing {@code FILLER PIC X(20)} at offset 330. That length appears nowhere in this
 * job's own driver, which is why it is stated here: {@code app/jcl/COMBTRAN.jcl:35} declares
 * {@code DCB=(*.SORTIN)}, so the sort output inherits its attributes by reference from its first input, and
 * that input was created {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} at {@code app/jcl/TRANBKP.jcl:31}.
 * Encoding is delegated to {@code TransactionRecordMapper}, which owns the sign overpunch of
 * {@code TRAN-AMT PIC S9(09)V99} and rebuilds the pad as blanks; nothing here trims a span, because a
 * staged generation's bytes are the contract every reader of it holds.</p>
 *
 * <h2>Why the two concatenated inputs are satisfied from one relation</h2>
 *
 * <p>Refactoring Rationale: the reference had to concatenate two SEQUENTIAL DATASETS because its two
 * producers wrote to different files -- the posting step to the transaction master, and the interest step
 * to {@code SYSTRAN}, the newly created generation output at {@code app/jcl/INTCALC.jcl:37-41}. In the
 * migrated model both producers commit to the SAME relation: {@code PostTransactionsJob} inserts the day's
 * posted transactions into {@code ledger.transactions} and {@code CalculateInterestJob} inserts the
 * generated interest transactions into it as well. The union the reference had to assemble physically is
 * therefore already materialised, so this job reads it back out of the relation rather than out of two
 * object-store generations. Reading the generations would be strictly worse for two concrete reasons: it
 * would reintroduce fixed-width decoding on the READ path for data that is already relational, and it
 * would make this step depend on the serialised form of two upstream artefacts instead of on the ledger
 * those artefacts were themselves derived from.</p>
 *
 * <p>Assumptions: the ordering is by transaction identifier ascending, and its provenance is
 * {@code app/jcl/COMBTRAN.jcl:27-30} rather than a preference of this module -- the symbol declaration and
 * the sort statement together fix both the key and its direction. It is recorded here because an ordered
 * read looks like an optimisation opportunity to anyone who has not read the driver, and the order is the
 * one property of the output a downstream reader is entitled to rely on.</p>
 *
 * <p>Trade-offs: one consequence of the two producers sharing a relation is surfaced rather than left to
 * be discovered. Because the interest rows enter the ledger at state five instead of waiting in
 * {@code SYSTRAN} until state seven, the {@code TRANSACT.BKUP} generation that state six produces is a
 * SUPERSET of the reference's -- it contains the interest rows too. The artefact THIS job produces is
 * unaffected and keeps parity, because it is assembled from the whole relation either way, and so does the
 * final content of the ledger. The reason the interest rows are committed at state five anyway is that
 * {@code CalculateInterestJob} also updates the account: {@code app/cbl/CBACT04C.cbl:350-356} adds the
 * accumulated interest to the current balance and zeroes both cycle buckets. Committing that balance
 * change while withholding the rows behind it would publish a balance with no transaction to explain it,
 * which is the observable partial state the migration plan rejects in section 0.4.3 when it declines a
 * saga for posting. No committed golden constrains the intermediate generation: the expectation tree holds
 * the {@code interest}, {@code posting}, {@code provisioning}, {@code reporting} and {@code statement}
 * domains only, and no test in {@code tests/} names {@code TRANSACT.BKUP}, {@code SYSTRAN} or
 * {@code TRANSACT.COMBINED} at all. The difference is registered with the load-back omission below, under
 * {@code D-COMBINE-NO-LOADBACK} in {@code docs/architecture/cobol-to-service-traceability.md} section
 * 7.4.</p>
 *
 * <h2>Why the load-back into the master is deliberately absent</h2>
 *
 * <p>Refactoring Rationale: the reference's second step, the
 * {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} at {@code app/jcl/COMBTRAN.jcl:48}, exists to GIVE the
 * master its new contents after the backup job emptied it -- {@code app/jcl/TRANBKP.jcl:40-41} deletes the
 * cluster and {@code :54-67} defines it again. In the migrated model the master was never emptied:
 * {@code BackupTransactionsJob} deliberately does not truncate the table, because a relational store needs
 * no delete-and-redefine to produce an empty extract and maintains its indexes transactionally, which is
 * the same reasoning under which the migration plan's transformation rule T6 retires
 * {@code IDCAMS BLDINDEX}. The rows the copy would restore are already in place, put there by the two
 * upstream jobs, so there is nothing to reload. This job therefore writes a generation and stops: it
 * issues no insert, no update and no delete against {@code ledger.transactions}.</p>
 *
 * <p>Trade-offs: these two omissions are correct only as a PAIR, and neither half may be changed without
 * the other. {@code BackupTransactionsJob} omits the wipe because this job omits the reload; this job omits
 * the reload because the rows are already committed and the table was never emptied. Reinstating the
 * reload without also reinstating the wipe would insert every row of the combined extract over a table
 * that still holds all of them, and DUPLICATE the entire ledger. That is the failure mode a future author
 * has to see before touching either file, which is why it is stated on both of them rather than in a
 * document neither of them points at.</p>
 *
 * <h2>Why both current inputs are still resolved</h2>
 *
 * <p>Assumptions: the two current generations are resolved even though neither is read for its bytes,
 * because resolving them reproduces the reference's own precondition. Both inputs are named
 * {@code DISP=SHR} at {@code app/jcl/COMBTRAN.jcl:23-26}, so a run in which an upstream step had produced
 * no generation fails at allocation rather than quietly merging one input; here an absent generation fails
 * the step for the same reason and names the family that is missing, so an operator resolving a broken
 * chain learns WHICH predecessor did not run.</p>
 *
 * <h2>The tier this job reports</h2>
 *
 * <p>Assumptions: this job reports clean or fails, and never the soft-warn tier. The graded outcome
 * exists because the durable ledger records one per step, but a staging step has no third outcome to
 * report -- the generation was written or it was not -- so a completed execution carries no warning exit
 * code and {@code BatchApplication} maps it to the clean process status. The reference's own warn gate is
 * elsewhere: {@code app/jcl/TRANBKP.jcl:51} carries the only {@code COND=(4,LT)} anywhere in
 * {@code app/jcl}, and that gate reads a PREDECESSOR's tier, so it belongs to the state machine rather
 * than to any job.</p>
 */
@Configuration
public class CombineTransactionsJob {

    /** The name this job registers under, taken from the shared token enumeration. */
    public static final String JOB_NAME = BatchJobName.COMBINE_TRANSACTIONS.token();

    /** The step name the durable ledger records this job's progress under. */
    public static final String STEP_NAME = "combine-transactions-step";

    /** The name of the single object each staged generation of this family holds. */
    public static final String DATASET_OBJECT_NAME = "transact.combined";

    /** The prefix of the temporary file the combined output is streamed into before upload. */
    private static final String STAGING_FILE_PREFIX = "carddemo-transact-combined-";

    /** The operational log this job reports its record counts through. */
    private static final Logger LOG = LoggerFactory.getLogger(CombineTransactionsJob.class);

    /** The relation both merge inputs now live in. */
    private final TransactionRepository ledger;

    /** The resolver that reads the two current generations and allocates the combined one. */
    private final DatasetGenerationService generations;

    /** The durable step record that makes a re-run of a completed step a no-op. */
    private final BatchStepLedger ledgerOfSteps;

    /**
     * Builds the job over the relation it reads and the generation resolver it stages through.
     *
     * @param ledger the transaction relation; must not be {@code null}
     * @param generations the generation resolver; must not be {@code null}
     * @param ledgerOfSteps the durable step ledger; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CombineTransactionsJob(TransactionRepository ledger,
            DatasetGenerationService generations, BatchStepLedger ledgerOfSteps) {

        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.generations = Objects.requireNonNull(generations, "generations must not be null");
        this.ledgerOfSteps = Objects.requireNonNull(ledgerOfSteps, "ledgerOfSteps must not be null");
    }

    /**
     * Registers the job under the token the orchestrator names it by.
     *
     * @param jobRepository the framework's durable job repository; must not be {@code null}
     * @param transactionManager the transaction manager the step runs under; must not be {@code null}
     * @param validator the shared parameter validator; must not be {@code null}
     * @return the registered job, never {@code null}
     */
    @Bean
    // WHY : Refactoring Rationale: the factory method is named WITHOUT the "Job" suffix its class
    //       carries, and the difference is load-bearing rather than cosmetic. A `@Configuration` class
    //       registered by type takes a bean id from its own decapitalised class name, so a `@Bean`
    //       method spelled `combineTransactionsJob` inside `CombineTransactionsJob` claims the identifier
    //       the class itself already holds -- and Spring refuses the context with a
    //       BeanDefinitionOverrideException rather than choosing between them. Dropping the suffix
    //       gives the two definitions distinct identifiers.
    // WHY : Assumptions: nothing selects this bean by its identifier. The command contract iterates
    //       the `Job` beans and compares `getName()` against its argument, and `getName()` comes from
    //       JOB_NAME below, so the identifier is free to change and the job's published token is not.
    public Job combineTransactions(JobRepository jobRepository,
            PlatformTransactionManager transactionManager, JobParametersValidator validator) {

        // WHY : Assumptions: the step is built here rather than published as a second bean, matching
        //       the four sibling jobs that grade themselves. One transactional tasklet is also what
        //       keeps the ordered read inside a transaction: the repository's combine walk declares
        //       MANDATORY propagation, so it refuses to run outside one rather than returning a
        //       cursor that was already closed.
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(validator)
                .start(new StepBuilder(STEP_NAME, jobRepository)
                        .tasklet(this::runStep, transactionManager)
                        .build())
                .build();
    }

    /**
     * Runs the merge once, under the durable step record.
     *
     * <p>Trade-offs: the {@link StepContribution} is accepted and deliberately never written to. It is the
     * handle through which a step reports a non-default exit status, and the sibling posting job does
     * exactly that for its warn tier; this job has no warn tier, for the reason the class charter records,
     * so writing to it would invent a tier the staged artefact cannot express. The parameter stays because
     * the tasklet contract supplies it, and saying so is better than leaving a reader to wonder whether
     * grading was forgotten.</p>
     *
     * @param contribution the framework's handle for reporting this step's exit status, deliberately
     *     unused here; must not be {@code null}
     * @param context the chunk context carrying the job parameters; must not be {@code null}
     * @return {@link RepeatStatus#FINISHED} always, because the merge completes within one invocation
     */
    private RepeatStatus runStep(StepContribution contribution, ChunkContext context) {
        String runId = BatchConfig.runIdOf(context);
        BusinessDate businessDate = BatchConfig.businessDateOf(context);

        // WHY : Assumptions: the ledger keys on the run identifier paired with STEP_NAME, and that pair
        //       is what makes a redriven state a no-op instead of a second pass. It matters here beyond
        //       the usual idempotency argument: a second pass would allocate a second generation and
        //       consume one of the five that app/jcl/DEFGDGB.jcl:55-57 retains, so the run would silently
        //       shorten the history an operator can restore from.
        this.ledgerOfSteps.runStep(runId, STEP_NAME, () -> combineIntoNewGeneration(runId, businessDate));
        return RepeatStatus.FINISHED;
    }

    /**
     * Resolves both inputs, stages the ordered output as a new generation, and applies retention.
     *
     * @param runId the orchestrator execution the allocation belongs to; must not be {@code null}
     * @param businessDate the injected business date the generation is partitioned under; must not be
     *     {@code null}
     * @return {@link BatchReturnCode#CLEAN}, because the merge either completes or raises
     */
    private BatchReturnCode combineIntoNewGeneration(String runId, BusinessDate businessDate) {
        String datePartition = resolveDatePartitionSegment(businessDate);

        DatasetGeneration backupInput = requireCurrentGeneration(DatasetFamily.TRANSACT_BKUP);
        DatasetGeneration systemInput = requireCurrentGeneration(DatasetFamily.SYSTRAN);

        // WHY : Assumptions: one call per family and run yields ONE generation, because the resolver
        //       records its allocation and answers a repeat request with the coordinate it already gave.
        //       That is not an optimisation, it is the semantic of the notation being migrated:
        //       app/jcl/COMBTRAN.jcl names TRANSACT.COMBINED(+1) TWICE, at :37 as the sort output and
        //       again at :44 as the copy's input, and within one job a relative reference addresses the
        //       same physical generation -- which is precisely how the reference's second step reads what
        //       its first step wrote. This job no longer has that second consumer, but the semantic is
        //       stated because it is what a reader has to know to follow the driver, and because any
        //       later step needing the generation this run produced depends on it holding.
        DatasetGeneration target = this.generations.allocateNewGeneration(
                DatasetFamily.TRANSACT_COMBINED, businessDate, runId);

        Path staged = writeOrderedTransactionsToTemporaryFile();
        try {
            this.generations.stageDataset(target, DATASET_OBJECT_NAME, staged);
        } finally {
            // WHY : Assumptions: the temporary file is removed on every path, including a failed upload,
            //       because a batch task's ephemeral disk is finite and a failed step is retried. Leaving
            //       it would let a sequence of retries fill the volume and turn a transient upload failure
            //       into a task that can no longer start.
            deleteQuietly(staged);
        }

        int scratched = 0;
        for (DatasetGeneration agedOut
                : this.generations.generationsToScratch(DatasetFamily.TRANSACT_COMBINED)) {
            scratched += this.generations.scratchGeneration(agedOut);
        }

        LOG.info("event=batch.combine.completed backupInput={} systemInput={} generation={}"
                        + " datePartition={} scratchedObjects={} location={}",
                backupInput.generationNumber(), systemInput.generationNumber(),
                target.generationNumber(), datePartition, scratched,
                this.generations.datasetUri(target));

        // WHY : Trade-offs: the method returns HERE, where the reference goes on to a second step, and the
        //       difference is worth naming rather than passing off as a like-for-like port. That step,
        //       app/jcl/COMBTRAN.jcl:41, carries NO `COND` operand, so the reference runs it even when the
        //       sort ahead of it failed -- copying a partial or empty dataset into a master that the
        //       preceding job had just emptied. Nothing here inherits that: there is no second step to
        //       gate, and every state of the migrated chain carries an explicit `Retry`, `Catch` and
        //       `TimeoutSeconds`, so a failure routes to notification and to the failure state instead of
        //       letting a successor run on bad input. What is given up is the reference's ability to finish
        //       a night with a half-written master, which was never a feature.
        return BatchReturnCode.CLEAN;
    }

    /**
     * Resolves the injected business date to the {@code dt=} key segment, before anything is allocated.
     *
     * <p>Trade-offs: this resolution decides WHEN an unresolvable token is reported, not whether -- the
     * coordinate's own renderer raises the same exception later, when the resolver composes a listing
     * prefix. It is done at the top of the body anyway for two specific reasons, and the cost is one extra
     * object construction. First, it fails before ANY object-store call, so a malformed job parameter is
     * reported as a malformed job parameter instead of arriving after two input listings that a reader of
     * the log sees first. Second, the resolved value is read once and carried into the completion record,
     * so the log names the partition the generation was actually written under rather than leaving an
     * operator to re-derive it from a token in two possible layouts.</p>
     *
     * <p>Assumptions: both business-date layouts committed in this repository reach this method and both
     * must be accepted -- the separated {@code 2024-01-15} form used by the module's own tests and the
     * compact {@code 2022071800} form the reference injects as {@code PARM} at
     * {@code app/jcl/INTCALC.jcl:22}. {@code DatasetGeneration} resolves both to one partition value and
     * raises, quoting the token, for anything else including a rendering that names a day that does not
     * exist. The raise is the required behaviour: a malformed prefix would place this night's records
     * under a key no reader looks beneath, and nothing downstream would report it.</p>
     *
     * <p>Alternatives Considered: calling {@code BusinessDate.parseIsoDateForRangeComparison()}, the ISO
     * accessor, and rendering the date from its result. Rejected concretely rather than on taste: that
     * accessor is documented to throw for a compact token, and the compact token is the form the
     * reference's own driver supplies, so this step would fail on the one input the baseline actually
     * passes. The ISO reading of a business date is confined to this path segment for the same reason it
     * is confined on the accessor itself -- an ISO rendering must never reach a stored identifier, where
     * {@code app/cbl/CBACT04C.cbl:474-480} concatenates the token as supplied into
     * {@code TRAN-ID PIC X(16)}.</p>
     *
     * @param businessDate the injected business date to resolve; must not be {@code null}
     * @return the {@code dt=} segment of the generation key, for example {@code dt=2022-07-18}; never
     *     {@code null}
     * @throws IllegalStateException if the token matches neither committed layout, or renders a date that
     *     is not a real calendar day, so no partition value can be derived from it
     */
    private static String resolveDatePartitionSegment(BusinessDate businessDate) {
        // WHY : Assumptions: the coordinate built here is a probe and never addresses an object. Only its
        //       rendering is read, so the family and the generation number are the cheapest values that
        //       satisfy the coordinate's own validity rules; the resolver allocates the real number a few
        //       statements later. The sibling resolver documents the same probe idiom for the same reason.
        return new DatasetGeneration(DatasetFamily.TRANSACT_COMBINED, businessDate,
                DatasetGeneration.MINIMUM_GENERATION_NUMBER).datePartitionSegment();
    }

    /**
     * Resolves one input family's current generation, failing the step when the family holds none.
     *
     * @param family the input family to resolve; must not be {@code null}
     * @return the current generation of that family, never {@code null}
     * @throws IllegalStateException if the family holds no generation, which is the migrated form of the
     *     reference's allocation failure on a {@code DISP=SHR} dataset that was never created
     */
    private DatasetGeneration requireCurrentGeneration(DatasetFamily family) {
        Optional<DatasetGeneration> current = this.generations.resolveCurrentGeneration(family);
        return current.orElseThrow(() -> new IllegalStateException("dataset family "
                + family.mainframeBaseName() + " holds no generation, so the combine step has nothing"
                + " to name as an input; the step that writes that family has not run for any business"
                + " date"));
    }

    /**
     * Streams every transaction, ordered by identifier, into a temporary file in the record form.
     *
     * <p>Alternatives Considered: reading the rows into a list and sorting them in memory, which is the
     * direct transliteration of the sort utility this step replaces. Rejected on two independent grounds.
     * The transaction relation is unbounded, so buffering it makes the heap the limit at exactly the
     * volume the nightly window exists to process; and the transaction identifier is the relation's
     * primary key, so the store satisfies the order from an index walk and performs no sort at all. The
     * ordered result set is streamed and each record encoded as it arrives, which keeps the step's memory
     * flat in the row count.</p>
     *
     * <p>Assumptions: the ordering is TOTAL here where the reference's was not, and the strengthening is
     * benign. {@code SORT FIELDS=(TRAN-ID,A)} at {@code app/jcl/COMBTRAN.jcl:30} carries no {@code EQUALS}
     * operand, so the utility's treatment of equal keys is unspecified. Ties cannot arise in either
     * system: the reference's master is key-sequenced on {@code KEYS(16 0)} at
     * {@code app/jcl/TRANBKP.jcl:58}, so its identifiers are unique, and the interest job's generated
     * identifiers are the ten-character business-date token followed by a six-digit suffix
     * ({@code app/cbl/CBACT04C.cbl:474-480} over {@code WS-TRANID-SUFFIX PIC 9(06)} at {@code :173}),
     * which cannot collide with a posted identifier. Ordering by a unique primary key therefore makes this
     * output deterministic rather than merely sorted, and no observable behaviour of the reference depended
     * on the unspecified case.</p>
     *
     * <p>Assumptions: the records are staged through a temporary file rather than pushed straight at the
     * object store because {@code DatasetGenerationService.stageDataset} takes a {@link Path} -- the file
     * is the collaborator's contract, not a convenience. Ephemeral disk is also what a batch task has more
     * of than heap, and it is what the reference's own sequential output dataset was.</p>
     *
     * <p>Assumptions: the key the ordering names is the record's FIRST sixteen bytes, and the conversion
     * between the two coordinate systems is stated because an off-by-one here is silent. The symbol
     * {@code TRAN-ID,1,16,CH} at {@code app/jcl/COMBTRAN.jcl:28} is expressed in the sort utility's
     * ONE-based positions, so position 1 is zero-based offset 0 -- which is where
     * {@code app/cpy/CVTRA05Y.cpy:5} places {@code TRAN-ID PIC X(16)}, and which agrees with the
     * key-sequenced master's {@code KEYS(16 0)}. The same conversion is verifiable a second time in the
     * reporting driver: {@code app/jcl/TRANREPT.jcl:41-42} declares {@code TRAN-CARD-NUM,263,16,ZD} and
     * {@code TRAN-PROC-DT,305,10,CH}, one-based 263 and 305, which are the copybook's zero-based 262 and
     * 304 exactly.</p>
     *
     * @return the temporary file holding the combined output, never {@code null}
     * @throws IllegalStateException if the file cannot be created or written
     */
    private Path writeOrderedTransactionsToTemporaryFile() {
        final Path staged;
        try {
            staged = Files.createTempFile(STAGING_FILE_PREFIX, ".dat");
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "could not create the temporary file the combined output streams through",
                    unavailable);
        }

        try (OutputStream sink = Files.newOutputStream(staged)) {
            // WHY : Assumptions: the walk is closed by try-with-resources because the repository returns a
            //       cursor-backed stream. An unclosed cursor pins a pooled connection for the rest of the
            //       step, and the data-source configuration disables auto-commit precisely so these
            //       cursors stream rather than materialise, so leaking one costs the connection without
            //       producing any difference in the output that would reveal it.
            try (Stream<Transaction> rows = this.ledger.findAllByOrderByTransactionIdAsc()) {
                // WHY : Alternatives Considered: `rows.forEach(...)`, which is the shorter spelling and
                //       does not compile here -- the body writes to a stream and so throws a checked
                //       IOException, which a Consumer cannot declare. Adapting the stream to an Iterable
                //       and using a plain loop leaves the checked exception where the enclosing catch
                //       below converts it, instead of forcing a wrap inside the lambda and an unwrap
                //       outside it that would bury the real cause one level deeper.
                for (Transaction row : (Iterable<Transaction>) rows::iterator) {
                    // WHY : Assumptions: the whole 350-byte image the mapper returns is written, with no
                    //       trailing span trimmed. The pad at app/cpy/CVTRA05Y.cpy:18 is part of the
                    //       record, and the parity harness records the consequence of trimming it at
                    //       tests/helpers/golden_compare.py:22-28 -- a 350-byte record collapses to about
                    //       278 characters, which no reader of a fixed-width dataset can parse.
                    sink.write(TransactionRecordMapper.toRecord(row));
                }
            }
        } catch (IOException unwritable) {
            deleteQuietly(staged);
            throw new IllegalStateException(
                    "could not write the combined output to " + staged, unwritable);
        }

        return staged;
    }

    /**
     * Removes a temporary file, reporting rather than raising when it cannot be removed.
     *
     * <p>Assumptions: a failed deletion is logged and swallowed rather than raised, because it happens on
     * paths that are already failing and re-raising there would replace the real failure with a cleanup
     * one. The consequence -- a file left behind -- is bounded by the container's lifetime.</p>
     *
     * @param file the file to remove; must not be {@code null}
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException undeletable) {
            LOG.warn("event=batch.combine.temp-file-retained path={} reason={}",
                    file, undeletable.getClass().getName());
        }
    }
}
