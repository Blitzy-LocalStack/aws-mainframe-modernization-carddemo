package com.carddemo.batch;

import java.util.Collection;
import java.util.List;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Process entry point of the CardDemo batch bounded context, which runs exactly one named job per
 * container task and reports that job's outcome as the process exit status.
 *
 * <h2>Two contracts live in this one class</h2>
 *
 * <p>Everything downstream of this module reads its interface from here. The image must declare an
 * exec-form entry point so that a container command appends to it; the orchestration state that
 * starts a task supplies that command; and the choice predicate that gates the next state in the
 * nightly chain reads the status this class publishes. The two contracts are
 * therefore stated in full below rather than left to be inferred from the code, because an ambiguity
 * here propagates outward into infrastructure that this repository's Java cannot correct.</p>
 *
 * <h2>Contract one: the argument contract</h2>
 *
 * <p>Two options, both required, neither defaulted:</p>
 *
 * <ul>
 *   <li>{@code --job=<name>} selects the job. {@code <name>} is exactly one of the seven tokens in
 *       {@link #JOB_NAMES}: {@code preflight-daily-transactions}, {@code post-transactions},
 *       {@code calculate-interest}, {@code backup-transactions}, {@code combine-transactions},
 *       {@code export} and {@code import}. The token is the name a job bean registers under, so it
 *       is looked up rather than switched on.</li>
 *   <li>{@code --business-date=<token>} supplies the business date as a job parameter under the key
 *       {@link #BUSINESS_DATE_PARAMETER}. The token is an OPAQUE ten-character value: it is
 *       validated for width and character class only and is then forwarded exactly as received.</li>
 * </ul>
 *
 * <p>Assumptions: the business-date token is a passthrough rather than a date in one canonical
 * layout, and the baseline is unambiguous about it. The reference program declares the field
 * {@code PARM-DATE PIC X(10)} at {@code app/cbl/CBACT04C.cbl:178}, which is alphanumeric, and
 * builds its deterministic transaction identifiers by concatenation with no formatting at all:
 * {@code app/cbl/CBACT04C.cbl:474} reads {@code ADD 1 TO WS-TRANID-SUFFIX} and lines 476 to 480
 * read {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID END-STRING}.
 * Whatever the caller supplied is emitted verbatim, and both forms are committed as expectation
 * files: {@code tests/golden/interest/happy_path/transact.expected} begins
 * {@code 2024-01-15000001}, the ISO form, while
 * {@code tests/golden/interest/e2e_interest_cycle_transactions.expected} begins
 * {@code 2022071800000001}, the compact form injected by {@code app/jcl/INTCALC.jcl:22} as
 * {@code PARM='2022071800'}. Both are ten characters plus a six-digit suffix. Reformatting the
 * token into either single layout would therefore change the identifiers of one of those two
 * committed scenarios and break its golden comparison, so this class reformats neither.</p>
 *
 * <p>Assumptions: the reference program's own linkage carries a halfword length prefix ahead of the
 * date -- {@code app/cbl/CBACT04C.cbl:176} opens {@code 01 EXTERNAL-PARMS.}, line 177 declares
 * {@code PARM-LENGTH PIC S9(04) COMP} and line 178 declares {@code PARM-DATE PIC X(10)}, so the
 * date sits at offset two of the parameter area. That prefix is an artefact of how the reference
 * operating system passes a parameter string and has no analogue in a process argument list, so it
 * is dropped rather than modelled. Only the ten-character payload crosses into this module.</p>
 *
 * <p>Assumptions: neither option carries a default, and that is a requirement rather than an
 * oversight. The orchestration state passes both as container command overrides, so a default here
 * would give one task two sources of truth that can disagree, and the one that lost would do so
 * silently. The module's own configuration agrees from the other side: its {@code application.yml}
 * disables the framework's startup job launcher and states that no property in it selects a job,
 * defaults a job, or defaults or reformats a business date. An absent or unrecognised option is
 * consequently a hard failure with a diagnostic that enumerates every accepted value, never a
 * fallback to something plausible.</p>
 *
 * <p>Assumptions: no clock is read on the business path of this module. A business date arrives as
 * a parameter precisely so that a rerun of a given night reproduces its predecessor byte for byte,
 * which is what makes golden comparison possible at all. The only legitimate clock reads anywhere
 * in this module are the record timestamps written by a posting or accrual step, and those reach
 * their writer through an injected {@link java.time.Clock} declared in
 * {@code com.carddemo.batch.config} so that a test can pin it. This class reads no clock of any
 * kind.</p>
 *
 * <h2>Contract two: the exit-status contract</h2>
 *
 * <p>A job reports its outcome to the orchestrator through the process exit status and through
 * nothing else. Three tiers exist, and each one is pinned by committed expectation files rather than
 * chosen here:</p>
 *
 * <ul>
 *   <li>{@link #EXIT_STATUS_CLEAN} -- clean completion with no rejects. The posting program leaves
 *       its return code untouched when its reject counter is zero, and ten of the fourteen
 *       {@code return_code.expected} files under {@code tests/golden/} record {@code 0}: the
 *       {@code happy_path}, {@code boundary_exact_limit}, {@code boundary_expiry_equal},
 *       {@code empty_input} and {@code zero_balance} posting scenarios, all three interest scenarios
 *       ({@code happy_path}, {@code default_fallback} and {@code zero_balance}) and both
 *       provisioning scenarios.</li>
 *   <li>{@link #EXIT_STATUS_SOFT_WARN} -- the work completed and produced rejects, and downstream
 *       states may still run. Its single origin in the whole baseline is
 *       {@code app/cbl/CBTRN02C.cbl:229}, which reads {@code IF WS-REJECT-COUNT > 0}, and line 230,
 *       which reads {@code MOVE 4 TO RETURN-CODE}. The remaining four expectation files record
 *       {@code 4} and every one of them is a posting reject scenario:
 *       {@code reject_100_card_missing}, {@code reject_101_acct_missing},
 *       {@code reject_102_overlimit} and {@code reject_103_expired}.</li>
 *   <li>{@link #EXIT_STATUS_HARD_FAILURE} or greater -- a hard failure, on which the state's catch
 *       handler routes to failure notification. The baseline expresses the same tier through its
 *       {@code APPL-RESULT} field, in which {@code 8} is the value pre-set before an operation so
 *       that a silent no-op still reads as failure, {@code 12} marks an input-output failure and
 *       leads to the abend paragraph, and {@code 16} is an end-of-file sentinel rather than a
 *       severity.</li>
 * </ul>
 *
 * <p>Assumptions: the warn tier belongs to transaction posting alone. {@code app/cbl/CBACT04C.cbl}
 * contains no {@code RETURN-CODE} statement anywhere and neither does {@code app/cbl/CBTRN01C.cbl},
 * which additionally contains no {@code WRITE} statement and receives no parameter at all --
 * {@code app/cbl/CBTRN01C.cbl:154} is a bare {@code PROCEDURE DIVISION.} with no {@code USING}
 * clause, and no job among the thirty-eight members of {@code app/jcl/} names that program, so its
 * migrated step is a pure validation pass with no dataset and no return code of its own.
 * Transaction posting is likewise driven without a parameter: {@code app/jcl/POSTTRAN.jcl:23} reads
 * {@code //STEP15 EXEC PGM=CBTRN02C} and carries no {@code PARM}. This class therefore never
 * manufactures a {@code 4}; it only reports the one a job declared.</p>
 *
 * <p>Refactoring Rationale: the mechanism that consumes this status is replaced, and the
 * replacement inverts the sense of the original, which is the easiest error to make in the whole
 * translation. A job-control condition code is a SKIP predicate. The only such gate in the baseline
 * is {@code app/jcl/TRANBKP.jcl:51}, which reads {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)} --
 * "skip this step when 4 is less than the accumulated return code" -- so the step RUNS when the
 * code is 4 or lower. An orchestration choice is a RUN predicate, so the equivalent gate is spelled
 * {@code rc <= 4} with the comparison the other way round. What was wrong with carrying the
 * original spelling across is concrete: a state predicate written {@code rc > 4} to mirror the
 * {@code COND} keyword would run the consuming step exactly when the baseline skipped it and skip
 * it exactly when the baseline ran it, and every clean night would look correct because a clean
 * night reports {@code 0} either way.</p>
 *
 * <p>Trade-offs: the graded numeric scale is quarantined to this one surface. The parity oracle
 * under {@code tests/} grades 0, 2, 4, 8 and 16 and treats a warn-level aggregate as its green
 * state, but the Java build tooling is binary -- the build, the documentation gate and both test
 * runners each either pass or fail -- so describing a Java build as warn-level green would hide a
 * real failure behind a borrowed vocabulary. The container's process exit status is the single
 * place a graded numeric legitimately survives, and it is numeric only because the orchestrator
 * reads it. The cost of quarantining the two vocabularies this strictly is that two adjacent parts
 * of one repository count success differently and a reader has to know which one is speaking; the
 * cost of not doing it is a build that reports success while failing.</p>
 *
 * <h2>What this class is not</h2>
 *
 * <p>This module is argument-driven and it is not a server. It publishes no controller, no interface
 * contract and no route, and its {@code pom.xml} declares neither a web starter nor an actuator, so
 * the framework deduces a non-web application from the empty classpath with no property asserting
 * it. A job runs to completion, the process exits, and the invoking state reads the status. Nothing
 * in this class may turn the task into a long-running process, because an embedded listener is
 * non-daemon: it would hold the interpreter open after the final step had finished, the task would
 * never reach a terminal state, and the nightly chain would stall on a step that had in fact
 * succeeded.</p>
 *
 * <p>Assumptions: standard output belongs to the running job and is not touched here. Transaction
 * posting emits two counter lines whose spacing is part of the observable contract:
 * {@code app/cbl/CBTRN02C.cbl:227} reads {@code DISPLAY 'TRANSACTIONS PROCESSED :'} with one space
 * before the colon and line 228 reads {@code DISPLAY 'TRANSACTIONS REJECTED  :'} with two, so the
 * colons align in the job log. Every diagnostic this class writes goes to standard error instead,
 * which keeps it off the stream those lines occupy.</p>
 *
 * <h2>Parameters, return values and exceptions at type level: declared inapplicable</h2>
 *
 * <p>A class declaration accepts no parameter, yields no value and raises nothing, so this block
 * carries no parameter, return or exception at-clause, and no authorship, availability or revision
 * at-clause either. The inapplicability is stated rather than left silent because the project's
 * single user-specified rule, Explainability, names at its line 39 a docstring that omits parameters,
 * return values or purpose among its forbidden patterns, and a reader has to be able to tell a
 * declared inapplicability from an oversight. The three elements that do apply to a type are
 * accounted for above; the fourth is accounted for on each method below.</p>
 */
@SpringBootApplication
public class BatchApplication {

    /**
     * Command-line option selecting the job to run, {@code --job=}.
     *
     * <p>Assumptions: the prefix form with an embedded equals sign is what the framework recognises
     * as an option argument, so the same token both selects the job here and appears in the
     * framework's own command-line property source. It is declared once and consumed by the parser,
     * the validator and the diagnostic alike.</p>
     */
    public static final String JOB_OPTION = "--job=";

    /**
     * Command-line option supplying the business date, {@code --business-date=}.
     *
     * <p>The value is the opaque ten-character token described on this class, forwarded to the job as
     * a parameter under {@link #BUSINESS_DATE_PARAMETER}.</p>
     */
    public static final String BUSINESS_DATE_OPTION = "--business-date=";

    /**
     * Job-parameter key under which the business-date token reaches a job, {@code businessDate}.
     *
     * <p>Assumptions: the key is part of the contract between this entry point and every job in
     * {@code com.carddemo.batch.job}, so it is published here rather than restated in each job. It
     * is also an IDENTIFYING parameter, which is what makes one night's run a distinct job instance
     * from the next night's and lets a repeat of the same night be recognised instead of silently
     * duplicating work.</p>
     */
    public static final String BUSINESS_DATE_PARAMETER = "businessDate";

    /**
     * Exact character width of the business-date token, ten.
     *
     * <p>Assumptions: the width is the baseline's, not a preference.
     * {@code app/cbl/CBACT04C.cbl:178} declares {@code PARM-DATE PIC X(10)}, and the identifiers
     * derived from it by the concatenation at lines 476 to 480 are ten characters plus a six-digit
     * suffix in every committed expectation file. A token of any other width would shift that
     * suffix and change every identifier the run produces.</p>
     */
    public static final int BUSINESS_DATE_LENGTH = 10;

    /**
     * The seven accepted {@code --job=} tokens, in nightly-chain order followed by the two
     * unscheduled jobs.
     *
     * <p>Each token is the name a job bean registers under, so this list is simultaneously the set
     * of accepted arguments and the set of names looked up in the registry.</p>
     *
     * <p>Assumptions: the set is closed at seven and the tokens are byte-identical to the names the
     * charter of {@code com.carddemo.batch.job} requires its beans to register under. The
     * consequence of a one-character disagreement is specific: the module compiles, the image
     * builds, the task starts, and the run then fails inside the orchestration state with an
     * unresolved-job error, which is why the diagnostic produced by {@link #usage()} enumerates
     * this list and the resolution step reports the registry's own contents when a lookup
     * misses.</p>
     *
     * <p>Trade-offs: the entry point holds this list itself rather than reaching for a shared type.
     * It is the process boundary at which a container command becomes Java, and it has to be able
     * to reject a malformed command BEFORE an application context exists, so it cannot depend on
     * anything the context supplies. The accepted cost is that the closed set is expressed here and
     * modelled again inside the module for use by code that runs after startup; what that buys is a
     * usage failure that costs no database connection, no parameter lookup and no credential
     * resolution, and that can therefore be exercised on a machine with none of them.</p>
     */
    public static final List<String> JOB_NAMES = List.of(
            "preflight-daily-transactions",
            "post-transactions",
            "calculate-interest",
            "backup-transactions",
            "combine-transactions",
            "export",
            "import");

    /**
     * Batch exit code a job sets when it completed and produced rejects,
     * {@code COMPLETED_WITH_WARNINGS}.
     *
     * <p>Assumptions: the framework's own exit-status vocabulary has no such constant -- it ships
     * {@code COMPLETED}, {@code NOOP}, {@code FAILED}, {@code STOPPED}, {@code EXECUTING} and
     * {@code UNKNOWN} and nothing else -- so this token is a custom exit code that the posting job
     * sets on its own execution, and the token is published here because this class is what reads
     * it. A job that spelled it differently would complete with rejects and report clean, which is
     * precisely the parity failure the four reject expectation files exist to catch.</p>
     */
    public static final String EXIT_CODE_COMPLETED_WITH_WARNINGS = "COMPLETED_WITH_WARNINGS";

    /**
     * Process exit status for a clean completion, zero.
     */
    public static final int EXIT_STATUS_CLEAN = 0;

    /**
     * Process exit status for a completion that produced rejects, four.
     *
     * <p>Assumptions: the value is fixed by {@code app/cbl/CBTRN02C.cbl:230} ({@code MOVE 4 TO
     * RETURN-CODE}) and by the four {@code return_code.expected} files that record it. Collapsing
     * this tier into either neighbour changes observable pipeline behaviour: folded into zero, a
     * night that rejected transactions would be indistinguishable from a clean one; folded into the
     * failure tier, the consuming step gated on a code of four or lower would stop running on any
     * night that rejected anything, which the baseline gate at {@code app/jcl/TRANBKP.jcl:51}
     * explicitly allows.</p>
     */
    public static final int EXIT_STATUS_SOFT_WARN = 4;

    /**
     * Process exit status for a hard failure, eight.
     *
     * <p>Assumptions: every failure this class can detect reports in this tier, and that includes a
     * malformed command line. The alternative worth naming is the parity oracle's own usage tier,
     * which is two; two is rejected here because the orchestration gate is {@code rc <= 4}, so a
     * usage failure reported as two would SATISFY the gate and the chain would continue past a step
     * that never ran a single record. Eight is the lowest value that the gate refuses.</p>
     */
    public static final int EXIT_STATUS_HARD_FAILURE = 8;

    /**
     * Runs the one job named on the command line and terminates the process with that job's status.
     *
     * <p><b>The argument contract.</b> Two options are read from {@code args}, both required and
     * neither defaulted. {@code --job=<name>} selects the job, and {@code <name>} must be exactly one
     * of {@code preflight-daily-transactions}, {@code post-transactions},
     * {@code calculate-interest}, {@code backup-transactions}, {@code combine-transactions},
     * {@code export} or {@code import}. {@code --business-date=<token>} supplies the business date,
     * and {@code <token>} must be exactly ten characters, each one an ASCII digit or an ASCII
     * hyphen-minus; it is forwarded to the job verbatim under the parameter key
     * {@link #BUSINESS_DATE_PARAMETER} and is never reformatted, so {@code 2024-01-15} and
     * {@code 2022071800} are both accepted and each is emitted as supplied. Any other argument is
     * ignored by this class and left to the framework's own command-line property source. An absent,
     * blank, repeated or unrecognised option is a hard failure: a diagnostic naming every accepted
     * value is written to standard error and the process terminates in the
     * {@link #EXIT_STATUS_HARD_FAILURE} tier without an application context ever being created.</p>
     *
     * <p><b>The exit-status contract, which stands in for a return value.</b> This method returns no
     * value because it does not return at all: it ends by calling {@link System#exit(int)}, and the
     * status it passes there IS its result, read by the orchestration state that started the task.
     * {@link #EXIT_STATUS_CLEAN} means the job completed with no rejects.
     * {@link #EXIT_STATUS_SOFT_WARN} means it completed and produced rejects, the tier whose single
     * origin in the baseline is {@code app/cbl/CBTRN02C.cbl:229} reading
     * {@code IF WS-REJECT-COUNT > 0} and line 230 reading {@code MOVE 4 TO RETURN-CODE}, and on which
     * the consuming step still runs. {@link #EXIT_STATUS_HARD_FAILURE} or greater means the run
     * failed, whether while validating the command line, while starting the context, or while the job
     * itself was executing.</p>
     *
     * <p>Assumptions: the status must survive the process boundary intact, which places one
     * requirement on the image that carries this class: its entry point must be declared in exec
     * form, so that the container command appends to it and the interpreter is process one with no
     * intervening shell to discard the value. A wrapper script would substitute its OWN status for
     * this one, and the damage is specific rather than theoretical: a shell that ends on an
     * unchecked command reports zero, so a night whose posting step abended would present to the
     * orchestrator as a clean night and the chain would carry on into interest accrual over
     * unposted transactions.</p>
     *
     * @param args the container command arguments, expected to carry {@code --job=<name>} naming one
     *     of the seven tokens in {@link #JOB_NAMES} and {@code --business-date=<token>} carrying the
     *     opaque ten-character business-date token; a {@code null} or empty array is treated as
     *     supplying neither option and therefore fails with the usage diagnostic
     */
    public static void main(String[] args) {
        // WHY : Assumptions: the process terminates here explicitly rather than by falling off the
        //       end of this method. Returning normally would exit with zero whatever the job did,
        //       because the exit status of a Java process that completes its main method is zero,
        //       and the warn and failure tiers would then be unobservable to the orchestrator.
        System.exit(execute(args));
    }

    /**
     * Validates the command line, runs the selected job and computes the process exit status.
     *
     * <p>Separated from {@link #main(String[])} so that the whole flow is expressible as a value
     * instead of as a side effect on the process. That separation is what lets the argument contract
     * and the exit-status mapping be exercised directly, without a test having to fork a process to
     * observe a status or being able to terminate the test runner by invoking the entry point.</p>
     *
     * @param args the container command arguments, as received by {@link #main(String[])}; may be
     *     {@code null}, which is treated as an empty argument list
     * @return the process exit status: {@link #EXIT_STATUS_CLEAN} on a clean completion,
     *     {@link #EXIT_STATUS_SOFT_WARN} on a completion that produced rejects, and
     *     {@link #EXIT_STATUS_HARD_FAILURE} on any failure, including a malformed command line
     */
    static int execute(String[] args) {
        final String jobName;
        final String businessDate;
        // WHY : Assumptions: the command line is validated BEFORE any context is built, so a
        //       malformed command costs no database connection, no parameter-store lookup and no
        //       credential resolution, and its diagnostic cannot be buried under a startup failure
        //       caused by infrastructure that the run was never going to need.
        try {
            jobName = requiredJobName(args);
            businessDate = requiredBusinessDate(args);
        } catch (IllegalArgumentException rejection) {
            System.err.println("carddemo batch: " + rejection.getMessage());
            System.err.println(usage());
            return EXIT_STATUS_HARD_FAILURE;
        }
        return runInContext(args, jobName, businessDate);
    }

    /**
     * Starts the application context, runs the named job in it and closes it again.
     *
     * @param args the container command arguments, passed on so the framework's own command-line
     *     property source sees them
     * @param jobName the validated {@code --job=} token, one of {@link #JOB_NAMES}
     * @param businessDate the validated business-date token, forwarded verbatim
     * @return the process exit status for the run, never {@link #EXIT_STATUS_CLEAN} unless the job
     *     actually completed cleanly
     */
    private static int runInContext(String[] args, String jobName, String businessDate) {
        ConfigurableApplicationContext context = null;
        // WHY : Assumptions: the status is pre-set to the failure tier and only lowered by an
        //       observed outcome, which is the baseline's own idiom -- its APPL-RESULT field is set
        //       to 8 BEFORE an operation so that an operation which silently does nothing still
        //       reads as a failure. Initialising to zero instead would report success for any path
        //       that reached the return statement without a job execution to judge.
        int exitStatus = EXIT_STATUS_HARD_FAILURE;
        try {
            context = SpringApplication.run(BatchApplication.class, args);
            exitStatus = startJob(context, jobName, businessDate);
        } catch (Throwable failure) {
            // WHY : Assumptions: every throwable is caught, including an Error, because the process
            //       exit status is the only channel by which this container reports anything and the
            //       interpreter's own status for an uncaught throwable is 1. One satisfies the
            //       orchestration gate rc <= 4, so a step killed by an out-of-memory error would be
            //       read as a soft outcome and the chain would continue past it. Catching here
            //       cannot mask the failure: exitStatus was pre-set above and is never lowered on
            //       this path.
            System.err.println("carddemo batch: job '" + jobName + "' did not complete: " + failure);
            // WHY : Assumptions: the trace is emitted by this class because nothing else will. The
            //       framework reports a STARTUP failure through its own analysers, but a throwable
            //       raised after the context is running is reported by no framework path, so a
            //       hard-failure tier without a trace would leave the failure-notification state
            //       with nothing to route on.
            failure.printStackTrace(System.err);
        }
        if (context == null) {
            return exitStatus;
        }
        final int reported = exitStatus;
        // WHY : Alternatives Considered: calling close on the context and then System.exit with the
        //       value was evaluated and rejected. The framework's own exit helper closes the context
        //       AND consults every exit-code generator the context declares, taking the greatest
        //       magnitude, so a generator contributed by a starter can still raise the status of a
        //       run this class judged clean. Closing by hand would silently drop that contribution.
        return SpringApplication.exit(context, () -> reported);
    }

    /**
     * Resolves the requested job by name and starts it with the business date as a job parameter.
     *
     * <p>Alternatives Considered: a switch over imported job classes was evaluated and rejected. It
     * would couple this entry point to all seven job implementations, invert the natural direction
     * of the dependency -- an entry point should wire, not know -- and make adding a job an edit to
     * this file rather than an addition beside its siblings. Resolution by name keeps the coupling
     * at the one string the orchestration state already supplies, which is why this class imports
     * nothing from {@code com.carddemo.batch.job}.</p>
     *
     * @param context the running application context, used to obtain the job operator and the job
     * @param jobName the validated {@code --job=} token, which is also the name the job bean
     *     registers under
     * @param businessDate the validated business-date token, forwarded verbatim as an identifying
     *     job parameter under {@link #BUSINESS_DATE_PARAMETER}
     * @return the process exit status derived from the resulting job execution, or
     *     {@link #EXIT_STATUS_CLEAN} when this business date has already completed
     * @throws JobExecutionException when the job cannot be started at all: because an execution of
     *     this instance is already running, because the parameters are invalid for the job, or
     *     because the instance is not restartable
     */
    private static int startJob(ConfigurableApplicationContext context, String jobName,
            String businessDate) throws JobExecutionException {
        JobOperator operator = context.getBean(JobOperator.class);
        Job job = resolveJob(context, jobName);
        // WHY : Assumptions: the business date is added as an IDENTIFYING parameter, the third
        //       argument being true, so it participates in the identity of the job instance. That is
        //       what makes each night a distinct instance and lets a redrive of one night be
        //       recognised as a repeat instead of posting the same feed twice. Adding it as
        //       non-identifying would make every run of a job the same instance, and the second run
        //       of any job would be refused whatever date it was given.
        JobParameters parameters = new JobParametersBuilder()
                .addString(BUSINESS_DATE_PARAMETER, businessDate, true)
                .toJobParameters();
        try {
            JobExecution execution = operator.start(job, parameters);
            return exitStatusOf(execution);
        } catch (JobInstanceAlreadyCompleteException alreadyDone) {
            // WHY : Assumptions: a repeat of a business date that already completed is a no-op that
            //       reports success, which is the module's restart contract rather than leniency.
            //       An orchestration redrive resumes a failed execution from the state that failed,
            //       so a state that had in fact succeeded before the failure downstream of it gets
            //       invoked a second time. Reporting a hard failure there would fail the chain on a
            //       step whose work is already committed and would make redrive unusable; the run
            //       ledger this module keeps is what records that the work was done once.
            System.err.println("carddemo batch: job '" + jobName + "' has already completed for "
                    + BUSINESS_DATE_PARAMETER + " '" + businessDate + "'; nothing to do: "
                    + alreadyDone.getMessage());
            return EXIT_STATUS_CLEAN;
        }
    }

    /**
     * Finds the registered job carrying the requested name.
     *
     * <p>Assumptions: the set searched here IS the job registry's content. The framework publishes
     * a job operator as a bean but publishes no job registry as one -- the registry the operator
     * holds is built by a protected factory method that carries no bean annotation -- and that
     * registry is populated from exactly the context's own job beans once the singletons have been
     * instantiated. Reading the beans directly therefore resolves against the same names the
     * operator would report, from the one source that is reachable as a bean.</p>
     *
     * <p>Alternatives Considered: asking the operator for its registered names was the first form
     * of this method and was replaced. Every name-addressed and identifier-addressed method on that
     * interface, its name enumeration included, is deprecated and marked for removal; only the
     * typed start call this class uses to launch is not. Keeping the call would have compiled today
     * and then failed to compile on the release that removes it, and it would have done so in the
     * one method that decides whether a nightly step can start at all.</p>
     *
     * @param context the running application context, searched for the job bean carrying the name
     * @param jobName the validated {@code --job=} token to resolve
     * @return the registered job whose name equals {@code jobName}
     * @throws IllegalStateException when no registered job reports that name, with the names that
     *     are registered listed in the message
     */
    private static Job resolveJob(ConfigurableApplicationContext context, String jobName) {
        Collection<Job> registered = context.getBeansOfType(Job.class).values();
        for (Job candidate : registered) {
            if (jobName.equals(candidate.getName())) {
                return candidate;
            }
        }
        throw new IllegalStateException("no job is registered under the name '" + jobName
                + "'; the registry holds " + registered.stream().map(Job::getName).sorted().toList()
                + ". A job bean must register under the token exactly, because this name is an"
                + " orchestration contract and not an internal label");
    }

    /**
     * Maps a finished job execution onto one of the three process exit-status tiers.
     *
     * @param execution the finished job execution to judge, whose batch status and exit code together
     *     decide the tier
     * @return {@link #EXIT_STATUS_CLEAN} when the execution completed without the warning exit code,
     *     {@link #EXIT_STATUS_SOFT_WARN} when it completed carrying
     *     {@link #EXIT_CODE_COMPLETED_WITH_WARNINGS}, and {@link #EXIT_STATUS_HARD_FAILURE} when it
     *     did not complete
     */
    static int exitStatusOf(JobExecution execution) {
        BatchStatus status = execution.getStatus();
        ExitStatus exitStatus = execution.getExitStatus();
        // WHY : Assumptions: the batch status is consulted before the exit code because the two
        //       answer different questions and only one of them is authoritative about completion. A
        //       failed execution can still be carrying whatever exit code a step set before the
        //       failure, so reading the exit code first would let a run that abended mid-chunk report
        //       the warn tier and the chain would continue over half-posted work.
        if (status != BatchStatus.COMPLETED) {
            return EXIT_STATUS_HARD_FAILURE;
        }
        if (EXIT_CODE_COMPLETED_WITH_WARNINGS.equals(exitStatus.getExitCode())) {
            return EXIT_STATUS_SOFT_WARN;
        }
        // WHY : Assumptions: a completed execution that did nothing is clean, not a warning. The
        //       framework marks an execution with no work as NOOP, and the committed expectation
        //       file for the empty posting feed records a return code of 0, so an empty feed must
        //       report clean and any other reading would fail that scenario.
        return EXIT_STATUS_CLEAN;
    }

    /**
     * Extracts the mandatory {@code --job=} token and checks it against the accepted set.
     *
     * @param args the container command arguments to search, which may be {@code null}
     * @return the requested job name, guaranteed to be one of {@link #JOB_NAMES}
     * @throws IllegalArgumentException when the option is absent, blank, supplied more than once, or
     *     carries a value that is not one of the seven accepted tokens
     */
    static String requiredJobName(String[] args) {
        String value = optionValue(args, JOB_OPTION);
        if (value == null) {
            throw new IllegalArgumentException(JOB_OPTION + " is required and was not supplied");
        }
        if (!JOB_NAMES.contains(value)) {
            throw new IllegalArgumentException(JOB_OPTION + " value '" + value
                    + "' is not a job this module runs");
        }
        return value;
    }

    /**
     * Extracts the mandatory {@code --business-date=} token and checks its shape.
     *
     * <p>The token is returned exactly as received. Nothing here parses it into a date, normalises a
     * separator or pads a field, because the value is concatenated into deterministic transaction
     * identifiers downstream and the committed expectation files exercise two different ten-character
     * spellings of it.</p>
     *
     * @param args the container command arguments to search, which may be {@code null}
     * @return the business-date token, exactly as supplied
     * @throws IllegalArgumentException when the option is absent, supplied more than once, or carries
     *     a value that is not exactly {@link #BUSINESS_DATE_LENGTH} characters of ASCII digits and
     *     ASCII hyphen-minus
     */
    static String requiredBusinessDate(String[] args) {
        String value = optionValue(args, BUSINESS_DATE_OPTION);
        if (value == null) {
            throw new IllegalArgumentException(BUSINESS_DATE_OPTION
                    + " is required and was not supplied");
        }
        if (!isWellFormedBusinessDate(value)) {
            throw new IllegalArgumentException(BUSINESS_DATE_OPTION + " value '" + value
                    + "' is not exactly " + BUSINESS_DATE_LENGTH
                    + " characters of ASCII digits and hyphen-minus");
        }
        return value;
    }

    /**
     * Reads the single value of one option from an argument list.
     *
     * @param args the container command arguments to search, which may be {@code null} and is then
     *     treated as carrying no options at all
     * @param option the option prefix to match, including its trailing equals sign
     * @return the text following the prefix, or {@code null} when the option is absent or its value
     *     is blank
     * @throws IllegalArgumentException when the option appears more than once
     */
    private static String optionValue(String[] args, String option) {
        if (args == null) {
            return null;
        }
        String found = null;
        for (String argument : args) {
            if (argument == null || !argument.startsWith(option)) {
                continue;
            }
            // WHY : Assumptions: a repeated option is rejected rather than resolved by taking the
            //       first or the last. Both silently discard half of an ambiguous instruction, and
            //       the two conventions disagree with each other, so a command assembled from two
            //       overlapping sources would run whichever job the convention happened to favour
            //       and the operator would have no way to tell which from the log.
            if (found != null) {
                throw new IllegalArgumentException(option + " was supplied more than once");
            }
            found = argument.substring(option.length());
        }
        // WHY : Assumptions: a present-but-blank option is indistinguishable from an absent one for
        //       the caller's purposes, so both take the same path and produce the same diagnostic.
        //       Treating a blank value as supplied would push an empty job name into the registry
        //       lookup, whose failure names the registry rather than the missing argument.
        if (found == null || found.isBlank()) {
            return null;
        }
        return found;
    }

    /**
     * Tests whether a business-date token has the accepted width and character class.
     *
     * @param token the candidate business-date token, never {@code null}
     * @return {@code true} when the token is exactly {@link #BUSINESS_DATE_LENGTH} characters and
     *     every character is an ASCII digit or an ASCII hyphen-minus, {@code false} otherwise
     */
    private static boolean isWellFormedBusinessDate(String token) {
        if (token.length() != BUSINESS_DATE_LENGTH) {
            return false;
        }
        for (int index = 0; index < BUSINESS_DATE_LENGTH; index++) {
            char character = token.charAt(index);
            // WHY : Assumptions: the digit test is written against the ASCII range rather than
            //       delegated to the platform's is-a-digit predicate, which accepts every decimal
            //       digit in Unicode. An Arabic-Indic or fullwidth digit would pass that predicate,
            //       be forwarded verbatim as this contract requires, and then occupy more than one
            //       byte in a fixed-width record, shifting every field after it.
            boolean asciiDigit = character >= '0' && character <= '9';
            if (!asciiDigit && character != '-') {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the usage diagnostic that accompanies every rejected command line.
     *
     * <p>Alternatives Considered: writing the seven tokens out as literal text in this message was
     * evaluated and rejected in favour of enumerating {@link #JOB_NAMES}. A set edited in one place
     * and listed in another drifts, and the way it drifts is the worst available: the diagnostic
     * would advertise a value the parser rejects, or omit one it accepts, and an operator following
     * the message would be sent to a second failure.</p>
     *
     * @return the multi-line usage text, naming both options, all seven accepted job tokens, the
     *     shape of the business-date token and the three exit-status tiers
     */
    static String usage() {
        String newline = System.lineSeparator();
        StringBuilder message = new StringBuilder();
        message.append("Usage: ").append(JOB_OPTION).append("<name> ")
                .append(BUSINESS_DATE_OPTION).append("<token>").append(newline)
                .append(newline)
                .append("  ").append(JOB_OPTION)
                .append("<name> is required. <name> must be exactly one of:").append(newline);
        for (String name : JOB_NAMES) {
            message.append("      ").append(name).append(newline);
        }
        message.append(newline)
                .append("  ").append(BUSINESS_DATE_OPTION)
                .append("<token> is required. <token> must be exactly ")
                .append(BUSINESS_DATE_LENGTH).append(newline)
                .append("      characters, each one an ASCII digit or an ASCII hyphen-minus. It is")
                .append(newline)
                .append("      forwarded to the job verbatim and is never reformatted, so both")
                .append(newline)
                .append("      2024-01-15 and 2022071800 are accepted and each is emitted exactly")
                .append(newline)
                .append("      as supplied.").append(newline)
                .append(newline)
                .append("  Neither option has a default. Exit status: ").append(EXIT_STATUS_CLEAN)
                .append(" clean, ").append(EXIT_STATUS_SOFT_WARN)
                .append(" completed with rejects, ").append(EXIT_STATUS_HARD_FAILURE)
                .append(" or more hard failure.");
        return message.toString();
    }
}
