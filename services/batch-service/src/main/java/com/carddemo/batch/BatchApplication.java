package com.carddemo.batch;

import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.dto.BatchErrorEvent;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchJobParameters;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.service.BatchErrorPublisher;
import com.carddemo.common.observability.LogSafeText;
import com.carddemo.common.observability.ThrowableDigest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
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
 * <h2>How a token becomes a running job, stated before either contract</h2>
 *
 * <p>Assumptions: <strong>the module registers exactly seven {@link Job} beans, one for each token in
 * {@link #JOB_NAMES}.</strong> {@code com.carddemo.batch.job} holds seven {@code @Configuration}
 * classes, and each contributes exactly one {@code @Bean} method returning a {@code Job} whose name
 * comes from the matching {@link BatchJobName} constant rather than from a literal. {@link #resolveJob}
 * therefore LOOKS A TOKEN UP rather than switching on it: it iterates the registered {@code Job}
 * beans, compares each bean's own {@code getName()} against the already-validated argument, and when
 * none matches raises with both the requested token and the registry's sorted contents.</p>
 *
 * <p>WHY the bean's NAME rather than its bean identifier is the contract: the identifier is a Java
 * detail a refactor may rename freely, while the token is published outward to the orchestration
 * definition and to every runbook that starts a task. Binding the lookup to the name keeps those two
 * independent, and the pairing is ASSERTED rather than assumed -- the module's job-registration
 * census builds a context over the seven configurations and fails the build when a declared token has
 * no bean, when a bean answers to an undeclared name, or when two beans answer to one name.</p>
 *
 * <p>Assumptions: registration is not the same as a completed run, and what remains is external to
 * this module rather than absent from it. A run needs the {@code ledger} and {@code account} schemas,
 * the seeded {@code 'DEFAULT'} disclosure-group rows and the cross-schema grants that other contexts
 * own; with no reachable database the process fails at startup mapping validation, naming the missing
 * table. Argument handling is the one exception and runs with no database, no credential and no
 * application context at all, because {@link #parseArguments} is called BEFORE the context is
 * started -- which is why a malformed command line costs a usage diagnostic rather than a connection
 * attempt. Trade-offs: publishing the closed token set as a constant here, instead of deriving it
 * from the registered beans at run time, is what keeps that pre-context rejection possible and keeps
 * a misspelled token distinguishable from an unregistered one; the accepted cost is the census test
 * above, which exists so the two lists cannot drift apart unnoticed.</p>
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
 * <p>Assumptions: the business date is required for ALL SEVEN jobs, not only for the one whose
 * reference step carries a {@code PARM}, and the reason is stated here because a reader who checks
 * the reference drivers will find only one of them passing a date. Three separate uses make it
 * mandatory, and they are cumulative rather than alternative:</p>
 *
 * <ul>
 *   <li>ONE job takes it from the baseline directly. {@code app/jcl/INTCALC.jcl:22} runs
 *       {@code PGM=CBACT04C,PARM='2022071800'}, and {@code app/cbl/CBACT04C.cbl:476-480}
 *       concatenates that value into every generated {@code TRAN-ID}, so for {@code calculate-interest}
 *       the token's bytes are part of the module's observable output.</li>
 *   <li>THREE more address a dataset generation by it. {@code post-transactions} creates a generation
 *       of the reject stream ({@code app/jcl/POSTTRAN.jcl:38}), {@code backup-transactions} creates
 *       one of the backup dataset ({@code app/jcl/TRANBKP.jcl:33}) and {@code combine-transactions}
 *       reads two current generations and creates one of the combined dataset
 *       ({@code app/jcl/COMBTRAN.jcl:24,26,37}). A generation's object-storage coordinate is
 *       {@code dt=YYYY-MM-DD/gen=NNNN}, so a step that could not name a date could not name where to
 *       read or write.</li>
 *   <li>ALL SEVEN are identified by it. {@link #startJob} adds the token as an IDENTIFYING job
 *       parameter, so it forms the identity of the job instance; that is what makes one night's run
 *       distinguishable from the next and what lets an orchestration redrive recognise an already
 *       completed night as a no-op instead of processing the same feed twice. Making the option
 *       optional for the three jobs with neither a {@code PARM} nor a generation -- the preflight and
 *       the export/import pair -- would give those three ONE job instance for all time, and the second
 *       run of any of them would then be refused whatever date it was given.</li>
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
 * translation. A job-control condition code is a SKIP predicate. The only gate in the baseline with
 * a non-zero threshold is {@code app/jcl/TRANBKP.jcl:51}, which reads
 * {@code //STEP10 EXEC PGM=IDCAMS,COND=(4,LT)} -- "skip this step when 4 is less than the
 * accumulated return code" -- so the step RUNS when the code is 4 or lower. An orchestration choice
 * is a RUN predicate, so the equivalent gate is spelled {@code rc <= 4} with the comparison the
 * other way round. What was wrong with carrying the original spelling across is concrete: a state
 * predicate written {@code rc > 4} to mirror the {@code COND} keyword would run the consuming step
 * exactly when the baseline skipped it and skip it exactly when the baseline ran it, and every clean
 * night would look correct because a clean night reports {@code 0} either way.</p>
 *
 * <p>Assumptions: <strong>that gate is NOT the consumer of this class's warn tier, and the two must
 * not be conflated.</strong> A {@code COND} parameter is evaluated against the return codes of
 * earlier steps IN THE SAME JOB and can see nothing outside it, so {@code TRANBKP.jcl:51} gates its
 * own job's cluster redefine against its own job's preceding steps -- {@code STEP05R}, a
 * {@code REPROC} copy, and {@code STEP05}, an {@code IDCAMS} delete whose code that step then
 * normalises to zero with {@code IF MAXCC LE 08 THEN SET MAXCC = 0} at L42 and L45. It cannot
 * observe {@code CBTRN02C}, which runs in a different job entirely: {@code app/jcl/POSTTRAN.jcl:23}
 * reads {@code //STEP15 EXEC PGM=CBTRN02C} and carries no {@code COND} at all, so nothing in the
 * baseline job control consumes posting's {@code 4} downstream. The warn tier's authority is
 * therefore the PROGRAM's own contract -- {@code app/cbl/CBTRN02C.cbl:229} testing
 * {@code IF WS-REJECT-COUNT > 0} and line 230 moving {@code 4} to {@code RETURN-CODE}, together with
 * the four committed {@code return_code.expected} files that record it -- and NOT an inherited
 * job-control gate. {@code TRANBKP.jcl:51} is cited above solely as the one place the baseline
 * demonstrates the inverted SENSE of a threshold comparison, which is the translation hazard being
 * described; the threshold value coinciding with posting's warn code is a coincidence and reading it
 * as a data path would invent a dependency the baseline does not have.</p>
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
 * <p>This module is argument-driven and publishes no business controller, interface contract or
 * administrative route. Its web and actuator starters exist solely so the Dockerfile can probe
 * datasource-aware health while a job runs. Trade-offs: carrying that listener adds a server to a
 * one-shot process, but this class closes the context and calls {@link System#exit(int)} with the
 * translated job result, so the listener cannot keep a completed task alive. Job selection remains
 * exclusively in the command arguments and no HTTP request can start work.</p>
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
     * <p>Each token is the name a job bean <em>must</em> register under, so this list is
     * simultaneously the set of accepted arguments and the set of names looked up in the registry.
     * All seven are carried by a registered bean today -- one {@code @Configuration} class per token
     * in {@code com.carddemo.batch.job} -- and the class-level documentation states what a run still
     * needs from other contexts once the lookup succeeds.</p>
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
     * The stable code reported when the command line is malformed.
     *
     * <p>Refactoring Rationale: the three codes here exist because every failure this class reports
     * used to be an unstructured sentence on standard error. A sentence cannot be alarmed on: the
     * operator dashboard that has to distinguish "the orchestrator passed the wrong arguments" from
     * "the job itself abended" would have to match on prose that any edit to a message silently
     * changes. A code is a contract -- it can be filtered, counted and alarmed on, and it survives
     * rewording of the message beside it.</p>
     *
     * <p>Assumptions: the codes are tiered by CAUSE, not by severity, because all three end in the
     * same hard-failure exit tier and the exit status therefore distinguishes none of them. This one
     * means the run never started and the fault is in what was passed to it.</p>
     */
    public static final String ERROR_CODE_USAGE = "CARDDEMO-BATCH-0001";

    /**
     * The stable code reported when the job was reached but did not complete.
     *
     * <p>Assumptions: this covers both a context that failed to start and an exception raised while
     * the job was running. The two are one code because the remedial action is the same -- read the
     * logged cause -- whereas the code below is a different code precisely because its remedial action
     * differs.</p>
     */
    public static final String ERROR_CODE_JOB_FAILED = "CARDDEMO-BATCH-0002";

    /**
     * The stable code reported when the run ended in a fatal virtual-machine error.
     *
     * <p>Assumptions: this is separate from {@link #ERROR_CODE_JOB_FAILED} because an
     * {@link Error} says something different about the run and calls for a different response. An
     * exception means the job logic or its data was wrong and a rerun with the same inputs will fail
     * the same way; an {@link Error} means the RUNTIME failed -- memory exhausted, a class that will
     * not link -- so the same inputs may well succeed on a task with more memory, and the operator's
     * next step is to look at the task's sizing rather than at the night's data.</p>
     */
    public static final String ERROR_CODE_FATAL = "CARDDEMO-BATCH-0003";

    /**
     * The stable code reported when the requested business date had already completed.
     *
     * <p>Assumptions: this is an OUTCOME code rather than an error code, and it is separate from the
     * three above because the run neither failed nor did any work. It exists so that a redrive no-op
     * is queryable: the restart contract this class implements depends on a repeated business date
     * reporting success, and without a code an operator cannot tell a state that re-ran and skipped
     * from a state that re-ran and posted the night a second time -- which is the one question a
     * redrive raises.</p>
     */
    public static final String OUTCOME_CODE_ALREADY_COMPLETE = "CARDDEMO-BATCH-0004";

    /**
     * The token the orchestration records on the warn edge, reused verbatim in the warn log line.
     *
     * <p>Assumptions: this string is NOT chosen here. The daily state machine's warn edge writes
     * {@code code = "POSTING_REJECTS_PRESENT"} into its execution state -- the {@code Pass} state
     * named {@code RecordPostingWarning} in {@code infra/modules/step-functions-batch/main.tf} -- and
     * emitting the same token means one search matches both the execution history and the log stream.
     * A second spelling for one outcome would leave an operator joining the two by eye, which is the
     * work this token exists to remove.</p>
     */
    public static final String WARNING_CODE_POSTING_REJECTS = "POSTING_REJECTS_PRESENT";

    /**
     * The widest a caller-supplied or job-supplied value may be when echoed into a diagnostic.
     *
     * <p>Assumptions: the bound is derived from the two values it applies to rather than chosen for
     * roundness. The widest legitimate job token is a job name and the widest legitimate date token is
     * {@code BUSINESS_DATE_LENGTH} characters, so sixty-four is comfortably above anything correct
     * while being far below what an unbounded echo admits.</p>
     *
     * <p>Trade-offs: a value longer than this is truncated and marked, so the diagnostic stops being a
     * faithful copy of what was passed. That is the point: an unbounded echo of an argument supplied by
     * whatever assembled the container command lets one malformed invocation write an arbitrarily large
     * record into a log stream billed by ingested volume, and a truncated value is still enough to
     * recognise the mistake.</p>
     */
    public static final int MAX_ECHOED_VALUE_LENGTH = 64;

    /**
     * The marker appended to a diagnostic value that was truncated at the echo bound.
     *
     * <p>Assumptions: truncation is MARKED rather than silent. An unmarked truncation is
     * indistinguishable from a value that really was that long, so an operator reading the diagnostic
     * would look for a sixty-four-character argument that was never passed.</p>
     */
    public static final String TRUNCATION_MARKER = "...[truncated]";

    /**
     * The environment variable naming the run this task belongs to.
     *
     * <p>Assumptions: the orchestrator supplies its execution name here through the container
     * override, so every line this process logs can be tied back to the state-machine execution that
     * started it. Without it the eleven tasks of one night's chain are eleven unrelated log streams and
     * nothing joins them.</p>
     */
    public static final String RUN_ID_VARIABLE = "CARDDEMO_BATCH_RUN_ID";

    /**
     * The step name a failure notification carries when no step of the job ever failed.
     *
     * <p>Assumptions: a failure raised before any step ran, or outside every step, genuinely has no step
     * name, and this token says so instead of substituting one that would read as real. The alternative
     * considered was reusing the job's own token, which is shorter and needs no constant. It was rejected
     * because the payload's documented use is to join its run identifier and step name to the durable
     * step ledger row for that pair, and a job token in the step position joins to nothing while looking
     * exactly like a value that would -- so a reader would conclude the ledger had lost a row. A token
     * that is obviously not a step name sends the reader to the run's log lines, which is where the
     * evidence for this class of failure actually is.</p>
     *
     * <p>Trade-offs: it is published rather than private so that the test asserting this substitution
     * compares against the same value the production path emits, which is the same reason the tokens on
     * the payload record itself are published.</p>
     */
    public static final String STEP_NAME_NO_STEP = "(no-step)";

    /**
     * The logging-context key the run identifier is published under.
     *
     * <p>Assumptions: this is the SAME key the shared correlation filter uses for a web request, and
     * deliberately so. The one console pattern in {@code carddemo-common-defaults.yml} renders that key,
     * so a batch line and a request line carry their identifier in the same position and one query
     * finds both. Choosing a batch-specific key would have needed a second pattern.</p>
     */
    public static final String CORRELATION_MDC_KEY = "correlationId";

    /**
     * The logger for this entry point.
     *
     * <p>Assumptions: failures are written through the logging pipeline rather than to standard error,
     * so they carry the timestamp, level, correlation identifier and logger name the shared pattern
     * emits, and so the platform's log driver receives one structured record per failure instead of an
     * unattributed sentence followed by an unattributed stack trace. The USAGE diagnostic below is the
     * one exception and says why at its own call site.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(BatchApplication.class);

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
        final BatchJobParameters parameters;
        // WHY : Assumptions: the command line is validated BEFORE any context is built, so a
        //       malformed command costs no database connection, no parameter-store lookup and no
        //       credential resolution, and its diagnostic cannot be buried under a startup failure
        //       caused by infrastructure that the run was never going to need.
        // WHY : Refactoring Rationale: the two validators below are the module's ONE argument parser,
        //       and their result is carried in BatchJobParameters rather than in two loose strings.
        //       An earlier revision had a second, independent parser on that record -- thoroughly
        //       tested and never reached by this method -- so its test suite could stay green while
        //       the parsing that actually ran diverged from the parsing that was certified. The two
        //       had in fact already diverged on a real rule: the record's parser admitted six of the
        //       seven jobs with no business date, while this method has always required one for every
        //       job. That parser is gone; the record now receives what this method parsed, so exactly
        //       one implementation reads the argument vector.
        // WHY : Assumptions: constructing the record here is what makes its invariants run in
        //       production rather than only under test -- non-null holders, a target generation that
        //       must agree with the business date, and the accrual job's own requirement of a date.
        //       Those checks raise the same IllegalArgumentException the validators do, so the single
        //       catch below reports them through the same usage diagnostic.
        // WHY : Assumptions: BatchJobName.resolve cannot fail here, because requiredJobName has
        //       already refused any token outside JOB_NAMES and that list is the same seven tokens the
        //       enumeration declares. The order is therefore load-bearing: resolving first would echo
        //       an unsanitised token from the enumeration's own message, whereas requiredJobName
        //       neutralises the value before it reaches any message at all.
        try {
            parameters = parseArguments(args);
        } catch (IllegalArgumentException rejection) {
            // WHY : Assumptions: this ONE diagnostic goes to standard error as well as to the log,
            //       and it is the only one that does. It is raised before any context exists, so the
            //       logging configuration this module ships has not been applied and the logger below
            //       may still be writing through a default appender at a default level -- while an
            //       operator running the image by hand to discover the argument contract needs the
            //       usage text on the terminal whatever the logging state is. Every failure raised
            //       AFTER the context is up goes to the logger alone.
            // WHY : Refactoring Rationale: the rejection text is neutralised before it is written,
            //       because it is the ONLY value in this class assembled from the command line and
            //       every rejection message quotes the offending token back. A token carrying a line
            //       feed would therefore end this event's line and begin a second line that the
            //       caller composed -- level, event name and all -- in a stream that a collector
            //       parses one line per event, which is the concern named CWE-117. Both destinations
            //       are neutralised rather than only the log: standard error is collected from the
            //       container as readily as the log stream is, so sanitising one and not the other
            //       would leave the same forged record reachable through the other channel.
            // WHY : Alternatives Considered: rejecting the argument without echoing it, which would
            //       remove the value from the line entirely. Rejected because the whole purpose of
            //       this diagnostic is to tell an operator which token was wrong, and a refusal that
            //       does not name it sends them to the usage text with no idea which of their two
            //       arguments to change. Neutralising keeps the token readable and keeps the record
            //       one record. The usage text itself is a compile-time constant and needs nothing.
            String reason = LogSafeText.sanitize(rejection.getMessage());
            LOG.error("event=batch.usage.rejected code={} reason={}", ERROR_CODE_USAGE, reason);
            System.err.println("carddemo batch: " + reason);
            System.err.println(usage());
            return EXIT_STATUS_HARD_FAILURE;
        }
        return runInContext(args, parameters.jobName().token(),
                parameters.requireBusinessDate().token());
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
        // WHY : Assumptions: the run identifier is published into the logging context BEFORE the
        //       context is built, so the framework's own startup lines carry it too. Publishing it
        //       after startup would leave the lines most useful during a failed start -- the ones that
        //       name an unreachable database or an unresolvable parameter -- as the only lines in the
        //       night's chain that nothing joins to an execution.
        // WHY : Trade-offs: an absent variable yields the literal below rather than a generated
        //       value. A generated identifier would be unique and would correlate with nothing, so a
        //       reader would have no way to tell an un-orchestrated run from an orchestrated one whose
        //       override was missing; a fixed literal says which of the two it is.
        MDC.put(CORRELATION_MDC_KEY, runIdentifier());
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
        } catch (Exception failure) {
            // WHY : Assumptions: an Exception means the job logic or the night's data was wrong, so
            //       the code reported is the job-failed code and the operator's next step is to read
            //       the cause. The digest is BOUND TO A NAMED PLACEHOLDER rather than appended as the
            //       facade's trailing throwable, which is what keeps the type chain and the frames
            //       inside the same structured record as the job name and the business date instead of
            //       arriving as a separate rendered stack the pipeline attributes to nothing.
            // WHY : ⚠️ Refactoring Rationale: this paragraph said the throwable itself "is passed to
            //       the logger as the LAST argument", and it was -- bound to the failureDigest={}
            //       placeholder, where the facade renders it with String.valueOf and therefore with
            //       Throwable.toString(): the type AND THE MESSAGE. So the very disclosure the
            //       paragraph below argues against was being made under a parameter name that claimed
            //       the opposite, and both statements stood in the same comment. The call now passes
            //       ThrowableDigest.of(failure) and the claim is true.
            // WHY : Assumptions: the type chain and the frames are emitted by this class because
            //       nothing else will. The framework reports a STARTUP failure through its own
            //       analysers, but a throwable raised after the context is running is reported by no
            //       framework path, so a hard-failure tier with no fault information at all would
            //       leave the failure-notification state with nothing to route on.
            // WHY : Refactoring Rationale: the throwable is NOT passed as a trailing argument. The
            //       facade renders a trailing throwable with its own message, every cause's message and
            //       the frames, and this is the generic boundary that fires for failures nobody
            //       anticipated -- so the messages reaching it are composed by whichever library failed
            //       and their content is unbounded by construction. A driver reports the statement it
            //       could not run, a parser quotes the token it could not read: either can carry an
            //       account identifier or a whole record. ThrowableDigest keeps the type chain and the
            //       frames, which are facts about code and can hold no request value, and drops the
            //       messages, which are the entire disclosure channel.
            // WHY : Trade-offs: an operator loses the driver's own explanation of a failure and must
            //       reproduce it with debug logging raised for the specific package. That is a scoped and
            //       auditable act; the alternative is that every unanticipated failure logs whatever the
            //       failing library chose to quote.
            LOG.error("event=batch.job.failed code={} job={} businessDate={} fault={} failureDigest={}",
                    ERROR_CODE_JOB_FAILED, jobName, businessDate, failure.getClass().getName(),
                    ThrowableDigest.of(failure));
            // WHY : Assumptions: this path publishes with the no-step stand-in because it is reached
            //       when the job could not be STARTED -- an instance already running, parameters the
            //       job refuses, an instance that is not restartable -- or when a throwable escaped
            //       outside every step, so there is no step execution to name. The context is tested
            //       for null rather than assumed present because a startup failure leaves it unset,
            //       and a startup failure is the one case where there is no producer to publish
            //       through: nothing has been configured yet.
            if (context != null) {
                publishFailureNotification(context, jobName, STEP_NAME_NO_STEP, exitStatus);
            }
        } catch (Error fatal) {
            // WHY : Refactoring Rationale: an Error is caught SEPARATELY, where a single catch of
            //       Throwable previously covered both. Catching it at all remains necessary and the
            //       original reasoning stands: the exit status is the only channel this container has,
            //       and the interpreter's own status for an uncaught throwable is 1, which satisfies
            //       the orchestration gate of four or less -- so a step killed by an out-of-memory
            //       error would present to the orchestrator as a SOFT outcome and the chain would
            //       carry on into interest accrual over unposted transactions. What the single catch
            //       lost was the distinction: an Error says the RUNTIME failed rather than the job,
            //       so the same inputs may well succeed on a task with more memory and the operator's
            //       next step is the task's sizing rather than the night's data. A separate code is
            //       what lets a dashboard tell those two apart.
            // WHY : Trade-offs: the context is deliberately NOT handed to the framework's exit helper
            //       on this path -- see the guard below. After an Error the runtime may be unable to
            //       complete a normal shutdown, and an exit helper that itself fails would replace a
            //       reported hard failure with an unreported one.
            // WHY : ⚠️ Refactoring Rationale: the Error was passed as a FIFTH argument to a message
            //       carrying four placeholders, so the facade treated it as the trailing throwable and
            //       rendered its message, every cause's message and the frames. An Error's message is
            //       composed by the runtime or by whatever library raised it -- a linkage fault names
            //       the symbol it could not resolve, and an assertion carries whatever value the
            //       failing code chose to quote -- so the channel is unbounded here for exactly the
            //       reason it is unbounded in the arm above. The digest is bound to a named placeholder
            //       instead, which keeps the type chain and the frames and drops the messages.
            // WHY : Assumptions: the same digest treatment is applied on BOTH arms although only one of
            //       them handles application faults. An Error reaching here has already crossed every
            //       job, step, reader and writer in the run, so any of them may be in its cause chain
            //       and the two arms cannot be assumed to differ in what their messages could contain.
            LOG.error("event=batch.job.fatal code={} job={} businessDate={} fault={} failureDigest={}",
                    ERROR_CODE_FATAL, jobName, businessDate, fatal.getClass().getName(),
                    ThrowableDigest.of(fatal));
            // WHY : Assumptions: NO failure notification is published on this path, and the omission is
            //       deliberate rather than an oversight in the branch above. An Error means the RUNTIME
            //       failed -- exhausted memory, a linkage fault, a stack overflow -- so the same
            //       reasoning that keeps the framework's exit helper off this path keeps a network call
            //       off it: after an Error the runtime may be unable to allocate the buffers a send
            //       needs, and an attempt that itself failed would add a second unrelated fault to the
            //       one record an operator has. Trade-offs: the cost is that the sink carries no message
            //       for the most severe class of failure. It is accepted because the exit status still
            //       reports the hard tier, which is the channel the orchestrator's catch route reads, and
            //       because this branch's own log line names the fatal code that distinguishes a runtime
            //       failure from a job failure.
            return clearContextAndReturn(exitStatus);
        }
        if (context == null) {
            return clearContextAndReturn(exitStatus);
        }
        final int reported = exitStatus;
        // WHY : Alternatives Considered: calling close on the context and then System.exit with the
        //       value was evaluated and rejected. The framework's own exit helper closes the context
        //       AND consults every exit-code generator the context declares, taking the greatest
        //       magnitude, so a generator contributed by a starter can still raise the status of a
        //       run this class judged clean. Closing by hand would silently drop that contribution.
        return clearContextAndReturn(SpringApplication.exit(context, () -> reported));
    }

    /**
     * Clears the run identifier from the logging context and passes an exit status straight through.
     *
     * <p>Trade-offs: this exists instead of a {@code finally} block, and the difference is observable.
     * A {@code finally} attached to the try above would run BEFORE the framework's exit helper is
     * called, so the shutdown lines and any exit-code generator's output -- the last lines of a failing
     * run, and the ones an operator reads first -- would be the only lines in the night's chain
     * carrying no run identifier. Clearing at each return point keeps the identifier attached for the
     * whole of the process's logging life. The cost is that a new return path has to remember to call
     * this, which is why every return in the caller goes through it.</p>
     *
     * <p>Assumptions: clearing matters even though the process exits immediately afterwards, because
     * the caller is also invoked directly by tests that share one thread; a leaked entry would attach
     * one test's run identifier to the next test's log lines.</p>
     *
     * @param exitStatus the status to return unchanged
     * @return {@code exitStatus}, unmodified
     */
    private static int clearContextAndReturn(int exitStatus) {
        MDC.remove(CORRELATION_MDC_KEY);
        return exitStatus;
    }

    /**
     * Resolves the identifier that ties every line this process logs to the run that started it.
     *
     * <p>Assumptions: the value is read from the environment rather than from a program argument,
     * because the orchestrator supplies it as a container environment override while the two program
     * arguments are the job's own contract. Keeping them in different channels means a change to the
     * observability wiring cannot alter the argument contract that
     * {@link #execute(String[])} validates.</p>
     *
     * <p>Refactoring Rationale: the supplied value is neutralised as well as trimmed, and the two do
     * different work. Trimming removes the surrounding whitespace an override picks up from the
     * orchestration definition it was written in; neutralising replaces any ISO control character
     * inside it. Trimming alone is not enough, because it leaves an interior line feed untouched, and
     * this value is placed into the diagnostic context that EVERY line this process logs carries --
     * so one malformed override would forge a second line under every one of them, for the whole run.
     * That is the concern named CWE-117, and this is the widest-reach instance of it in this module:
     * the other sanitised value affects one line, this one affects all of them.</p>
     *
     * <p>Assumptions: an environment override is outside this process's control even though an
     * orchestrator supplies it. The value travels from a state-machine definition through a container
     * override, so it can be set by anything that can edit either, and treating it as trusted because
     * of where it usually comes from is the assumption this neutralisation withdraws.</p>
     *
     * @return the orchestrator's run identifier when {@link #RUN_ID_VARIABLE} is set to a non-blank
     *     value, trimmed and with every ISO control character replaced by a space, and the fixed token
     *     {@code unorchestrated} otherwise, which distinguishes a manual run from an orchestrated run
     *     whose override was omitted
     */
    private static String runIdentifier() {
        String supplied = System.getenv(RUN_ID_VARIABLE);
        return supplied == null || supplied.isBlank()
                ? "unorchestrated"
                : LogSafeText.sanitize(supplied.trim());
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
        // WHY : Assumptions: the business date is IDENTIFYING and the run identifier is NOT, and the
        //       asymmetry is load-bearing rather than incidental. The business date is what makes a job
        //       instance the run of a particular day, so a second submission for the same day finds the
        //       completed instance and is refused by the branch below -- which is the migrated form of
        //       resubmitting a job that has already run. Were the run identifier identifying as well,
        //       every redrive would present a NEW instance, nothing would be found to refuse, and a
        //       redriven state machine execution would post the same day's transactions twice.
        // WHY : Alternatives Considered: omitting the run identifier from the parameters entirely and
        //       having each job read the container variable itself. Declined because a job would then
        //       read process state rather than its own parameters, so the same job run twice in one
        //       container with different variables would be indistinguishable in the job repository --
        //       and the durable step ledger and the generation reservation both key on this value.
        JobParameters parameters = new JobParametersBuilder()
                .addString(BUSINESS_DATE_PARAMETER, businessDate, true)
                .addString(BatchConfig.RUN_ID_PARAMETER, runIdentifier(), false)
                .toJobParameters();
        try {
            JobExecution execution = operator.start(job, parameters);
            int exitStatus = exitStatusOf(execution);
            logJobOutcome(jobName, businessDate, execution, exitStatus);
            // WHY : Assumptions: the notification is published from HERE, where the execution is still
            //       in hand, because this is the only point at which the name of the step that actually
            //       failed can be read. The caller sees the exit status alone, so a notification
            //       published there would have to substitute a stand-in for every failure rather than
            //       only for the ones with no step.
            publishFailureNotification(context, jobName, failingStepNameOf(execution), exitStatus);
            return exitStatus;
        } catch (JobInstanceAlreadyCompleteException alreadyDone) {
            // WHY : Assumptions: a repeat of a business date that already completed is a no-op that
            //       reports success, which is the module's restart contract rather than leniency.
            //       An orchestration redrive resumes a failed execution from the state that failed,
            //       so a state that had in fact succeeded before the failure downstream of it gets
            //       invoked a second time. Reporting a hard failure there would fail the chain on a
            //       step whose work is already committed and would make redrive unusable.
            // WHY : Trade-offs: this branch is the COARSER of the module's two restart authorities, and
            //       the granularity it offers is the business date rather than the step. The finer one
            //       is BatchStepLedger over batch.batch_run, keyed by the run and step pair, and it
            //       returns an already-COMPLETED step's recorded return code without running the step
            //       body again. Refactoring Rationale: this comment previously said that ledger was
            //       "authored but has no caller yet, because the job beans that would call it are not
            //       authored either", and reported a mid-chain redrive of one state as NOT
            //       distinguishable from a first attempt. Both halves are now false and were false
            //       while the sentence still read that way: all seven job beans exist and every one of
            //       them goes through the ledger -- PostTransactionsJob, PreflightDailyTransactionsJob,
            //       CalculateInterestJob, BackupTransactionsJob and CombineTransactionsJob call runStep
            //       directly, while ExportJob and ImportJob wrap their step through LedgerGuardedStep.
            //       Understating a guarantee reads exactly as badly as overstating one: an operator
            //       relying on this comment would have concluded a redrive re-runs committed work and
            //       would have avoided the redrive the restart design exists to make safe. What this
            //       branch still uniquely covers is a repeat of a whole business date whose job
            //       INSTANCE already completed, which the step ledger cannot see because no step of it
            //       is entered at all.
            // WHY : Refactoring Rationale: this branch now writes to the LOG as well, and previously
            //       wrote only to standard error. Standard error alone broke this class's own logging
            //       invariant in the one place it mattered most: the line carried no level, no
            //       timestamp and -- decisively -- no correlation identifier, even though the mapped
            //       diagnostic context is populated on this path, so a redrive no-op could not be
            //       joined to the execution that caused it and could not be counted at all. The
            //       redrive behaviour this branch implements is precisely the scenario the restart
            //       contract depends on, so it is the last outcome that should be unqueryable.
            // WHY : Trade-offs: warn rather than info, and the tier is deliberate. The process still
            //       reports the clean exit status, so nothing downstream changes; but a state that did
            //       no work when an operator expected a night to post is worth surfacing, and warn is
            //       the lowest level a filtered operational view is guaranteed to show.
            // WHY : Assumptions: the standard-error line is KEPT beside the log line rather than
            //       replaced by it, for the same reason the usage diagnostic keeps one: an operator
            //       running this image by hand reads the terminal, and this is the one outcome that
            //       looks like a silent success from the exit status alone.
            LOG.warn("event=batch.job.already-complete code={} job={} businessDate={} outcome={}",
                    OUTCOME_CODE_ALREADY_COMPLETE, jobName, businessDate,
                    BatchReturnCode.CLEAN.name());
            System.err.println("carddemo batch: job '" + jobName + "' has already completed for "
                    + BUSINESS_DATE_PARAMETER + " '" + businessDate + "'; nothing to do: "
                    + alreadyDone.getMessage());
            return EXIT_STATUS_CLEAN;
        }
    }

    /**
     * Publishes one failure notification to the terminal error sink, when a producer is configured.
     *
     * <p><b>Purpose.</b> This is the production call site the module's queue configuration was authored
     * for. Refactoring Rationale: before it existed the configuration validated an address, framed a
     * media type, bounded two source identifiers and built send requests that nothing sent -- the
     * binding's only callers were its own tests -- so the orchestration's failure-notification state had
     * a queue to watch and nothing ever arrived on it. One call here is what turns that from a described
     * capability into a delivered one.</p>
     *
     * <p>Assumptions: the producer is looked up through a provider rather than injected, because this
     * class is a static entry point that runs BEFORE any context exists and must keep working when the
     * sink is not configured at all. The whole queue configuration is gated on the sink's address being
     * supplied, so in a deployment that supplies no address there is no producer bean to inject and the
     * lookup finds nothing. Alternatives Considered: requiring the bean and letting its absence fail the
     * run. Rejected outright -- it would make a deployment that chose not to publish diagnostics unable
     * to run a batch job at all, and it would fail it on the failure path, replacing a graded, reported
     * outcome with an unreported one.</p>
     *
     * <p>Assumptions: only a tier that stops the chain is published, and the partition is evaluated
     * through {@link BatchReturnCode#permitsDownstreamRun()} rather than by comparing against the failure
     * constant. That is the same predicate the orchestration gate evaluates and the same one the payload
     * record's constructor enforces, so this guard and that refusal cannot come to disagree about which
     * tiers reach the sink. Restating the partition as an equality test here would put a second spelling
     * of it one edit away from contradicting the first. The concrete consequence is that the warn tier is
     * NOT published: the reference reaches it by design at {@code app/cbl/CBTRN02C.cbl:229-230}, where a
     * non-zero reject count selects a code of four on a run that did its job correctly, and raising an
     * operator for that spends the sink's credibility the first time it happens.</p>
     *
     * <p>Assumptions: the run identifier is carried as BOTH the run identifier and the correlation
     * identity, and they are the same value by design rather than by omission. The entry point publishes
     * this value into the logging context before the context is built, so every line the run emits
     * carries it, and the sink's correlation attribute is bound to that same key -- which is what lets a
     * reader move from one notification to the whole run's log stream with one value.</p>
     *
     * @param context the running application context, from which the producer is resolved if one is
     *     configured; must not be {@code null}
     * @param jobName the validated {@code --job=} token, resolved back to its enumerated form for the
     *     payload; must not be {@code null} and must be one of {@link #JOB_NAMES}
     * @param stepName the name of the step that failed, or {@link #STEP_NAME_NO_STEP} when the failure
     *     belongs to no step; must not be {@code null} and must not be blank
     * @param exitStatus the graded exit status for the run, published only when its tier stops the chain
     */
    // WHY : Assumptions: package-private rather than private, for the same reason logJobOutcome below
    //       is. The two properties worth proving about this method -- that the warn tier is NOT
    //       published and that a suppressed publication does not escape -- are invisible in the
    //       returned status of either caller, so a test that drove it through main would assert
    //       against a value that was already correct before this method existed.
    static void publishFailureNotification(ConfigurableApplicationContext context,
            String jobName, String stepName, int exitStatus) {
        BatchReturnCode tier = BatchReturnCode.fromNumericValue(exitStatus);
        if (tier.permitsDownstreamRun()) {
            return;
        }
        // WHY : Trade-offs: the whole publication is wrapped, and the swallow here is deliberately
        //       BROADER than the producer's own. The producer swallows a transport fault, but the two
        //       steps ahead of it can also fail -- resolving the bean can raise a context fault while a
        //       context is closing, and assembling the payload raises an argument fault if a value this
        //       method supplied is refused. Both are on the failure path, where the exit status is the
        //       only channel the orchestrator reads, so a throwable escaping here would replace a
        //       graded, reported failure with an unreported one. Assumptions: the fault is reported as
        //       its type chain and never as its message, because a message on this path is composed by
        //       whatever refused the value.
        try {
            context.getBeanProvider(BatchErrorPublisher.class).ifAvailable(publisher ->
                    publisher.publish(BatchErrorEvent.withoutAbendDetail(runIdentifier(), stepName,
                            BatchJobName.resolve(jobName), tier, runIdentifier())));
        } catch (RuntimeException suppressed) {
            LOG.error("event=batch.error.notify-failed job={} step={} returnCode={} failure={}",
                    jobName, stepName, exitStatus, ThrowableDigest.of(suppressed));
        }
    }

    /**
     * Names the step that failed, or reports that no step did.
     *
     * <p>Assumptions: the first step execution whose batch status is not {@code COMPLETED} is taken as
     * the failure, rather than the last or every one of them. A job stops at its first failing step --
     * nothing in this module declares a step that continues past a failure -- so the first non-completed
     * step is the one that failed and any step after it never ran. Alternatives Considered: collecting
     * every non-completed step name and joining them. Rejected because the payload carries ONE step name
     * by contract, and a joined value in that position would be neither a step name nor a list a
     * consumer could split reliably, while joining to the durable step ledger -- which holds a row per
     * step of the run -- already gives a reader every step's outcome.</p>
     *
     * <p>Trade-offs: a job that completed every step and still reported a failure tier yields the
     * no-step stand-in. That combination is reachable -- an execution can carry a failed exit status
     * assembled after its last step -- and naming a completed step for it would be worse than admitting
     * there is no failing step to name.</p>
     *
     * @param execution the finished job execution; must not be {@code null}
     * @return the name of the first step whose batch status is not {@link BatchStatus#COMPLETED}, or
     *     {@link #STEP_NAME_NO_STEP} when every step completed or none ran, never {@code null}
     */
    // WHY : Assumptions: package-private for the same reason as the method above. Which step a
    //       notification names is not observable from anything either caller returns, so the
    //       substitution rule can only be asserted by calling this directly.
    static String failingStepNameOf(JobExecution execution) {
        return execution.getStepExecutions().stream()
                .filter(step -> step.getStatus() != BatchStatus.COMPLETED)
                .map(StepExecution::getStepName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(STEP_NAME_NO_STEP);
    }

    /**
     * Reports the outcome of a finished job execution at the level its tier deserves.
     *
     * <p>Refactoring Rationale: before this method existed, every log site in this class was on a
     * failure path, so a clean night and a night that correctly rejected transactions produced
     * IDENTICAL output -- none at all -- and so did a job that completed with a failed batch status
     * without raising anything. The graded outcome was durably recorded in two places, the execution
     * state of the orchestration and the run ledger, and in neither of them can an operator watching a
     * log or metric stream see it: distinguishing the tiers required opening an execution history or
     * querying the database. One line per finished execution makes the tier visible where the rest of
     * the night's evidence already is, and makes the warn tier countable.</p>
     *
     * <p>Assumptions: the tier is resolved through {@link BatchReturnCode} rather than compared against
     * the three integer constants again here. That type owns the tier vocabulary and its own
     * documentation of what each tier means, so naming the tier from it keeps one spelling; a local
     * comparison chain would be a second place for the tiers to be described and to drift. The
     * resolution cannot reject its input, because {@link #exitStatusOf(JobExecution)} yields exactly
     * the three values that type models.</p>
     *
     * <p>Assumptions: the warn line carries the orchestration's own warn token, so the log record and
     * the execution state can be searched with one string. It does not carry a reject COUNT, and the
     * reason is that this class cannot know one: the count lives inside the posting step, which
     * publishes it as the execution's exit description. That description is echoed here -- bounded and
     * sanitised, because an exit description is job-supplied text and a failed step's description can
     * carry a whole stack trace -- so the count appears in the line as soon as a job records one, and
     * no placeholder is emitted meanwhile.</p>
     *
     * <p>Trade-offs: the level differs by tier, which means one grep for a single level does not find
     * every outcome. That is preferred to one level for all three: an operational view filtered at warn
     * would then either show every clean night or hide the warn tier, and the whole purpose of the line
     * is that the middle tier is neither invisible nor indistinguishable from a failure.</p>
     *
     * <p>Assumptions: package-private rather than private, for the same reason
     * {@link #exitStatusOf(JobExecution)} is: the mapping from a finished execution to the line an
     * operator reads is a contract worth asserting directly, and asserting it through a started job
     * would need a job repository, a datasource and a migrated schema before it could observe a single
     * line.</p>
     *
     * @param jobName the validated job token that ran, reported so a line names its own state
     * @param businessDate the business-date token the run was given, reported so a rerun of one night
     *     can be told from the run of the next
     * @param execution the finished job execution, read for its batch status and its exit status only
     * @param exitStatus the process exit status {@link #exitStatusOf(JobExecution)} derived from that
     *     execution, one of the three declared tiers
     */
    static void logJobOutcome(String jobName, String businessDate, JobExecution execution,
            int exitStatus) {

        BatchReturnCode tier = BatchReturnCode.fromNumericValue(exitStatus);
        ExitStatus frameworkStatus = execution.getExitStatus();
        String detail = sanitiseForDiagnostic(frameworkStatus.getExitDescription());

        switch (tier) {
            case CLEAN -> LOG.info(
                    "event=batch.job.outcome outcome={} exitStatus={} job={} businessDate={}"
                            + " batchStatus={} exitCode={}",
                    tier.name(), exitStatus, jobName, businessDate, execution.getStatus(),
                    frameworkStatus.getExitCode());
            case SOFT_WARN -> LOG.warn(
                    "event=batch.job.outcome outcome={} code={} exitStatus={} job={}"
                            + " businessDate={} batchStatus={} exitCode={} detail={}",
                    tier.name(), WARNING_CODE_POSTING_REJECTS, exitStatus, jobName, businessDate,
                    execution.getStatus(), frameworkStatus.getExitCode(), detail);
            case HARD_FAILURE -> LOG.error(
                    "event=batch.job.outcome outcome={} code={} exitStatus={} job={}"
                            + " businessDate={} batchStatus={} exitCode={} detail={}",
                    tier.name(), ERROR_CODE_JOB_FAILED, exitStatus, jobName, businessDate,
                    execution.getStatus(), frameworkStatus.getExitCode(), detail);
            default -> LOG.error(
                    "event=batch.job.outcome outcome=unmodelled exitStatus={} job={}"
                            + " businessDate={}",
                    exitStatus, jobName, businessDate);
        }
    }

    /**
     * Bounds and sanitises a value before it is echoed into a diagnostic.
     *
     * <p>Refactoring Rationale: the values this is applied to are the only two in this class that
     * neither originate here nor are validated before being echoed -- a rejected command-line argument
     * and a job-supplied exit description -- and both previously reached the log verbatim and unbounded.
     * A line feed inside such a value splits one log record into two, so a value carrying one can forge
     * a second record that looks like a line this system emitted; an unbounded value lets a single
     * malformed invocation write an arbitrarily large record into a stream billed by volume. Bounding
     * and stripping removes both without removing the diagnostic.</p>
     *
     * <p>Assumptions: a control character is REPLACED rather than deleted, so the value's length and
     * the position of the offending byte are both still readable. Deleting it would silently join the
     * text either side, which changes what the reader believes was passed.</p>
     *
     * <p>Trade-offs: every character above the printable ASCII range is replaced too, not only the two
     * that split a record. A code point that renders identically to an ASCII character would otherwise
     * let a diagnostic read as one token while being another, which is the same reason the correlation
     * filter in the shared kernel restricts its identity alphabet to ASCII. The cost is that a
     * legitimately non-ASCII argument is shown as masked bytes; no argument this module accepts is
     * non-ASCII, because both option contracts are digits, hyphen-minus and lower-case letters.</p>
     *
     * @param value the text to bound and sanitise, which may be {@code null} when a job recorded no
     *     exit description
     * @return the empty string when {@code value} is {@code null}, otherwise the value with every
     *     character outside printable ASCII replaced and, when it exceeded
     *     {@link #MAX_ECHOED_VALUE_LENGTH}, truncated to that width and marked with
     *     {@link #TRUNCATION_MARKER}
     */
    static String sanitiseForDiagnostic(String value) {
        if (value == null) {
            return "";
        }

        boolean truncated = value.length() > MAX_ECHOED_VALUE_LENGTH;
        int retained = truncated ? MAX_ECHOED_VALUE_LENGTH : value.length();
        StringBuilder sanitised = new StringBuilder(retained + TRUNCATION_MARKER.length());

        for (int index = 0; index < retained; index++) {
            char character = value.charAt(index);
            // WHY : Assumptions: the accepted range is the printable ASCII block, space through tilde,
            //       tested by explicit comparison rather than by the platform's is-printable predicate.
            //       That predicate answers for the whole of Unicode, so it would admit the code points
            //       this replacement exists to exclude.
            sanitised.append(character >= ' ' && character <= '~' ? character : '?');
        }

        if (truncated) {
            sanitised.append(TRUNCATION_MARKER);
        }

        return sanitised.toString();
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
     * Parses the container command line into the one parameter record this module runs a job from.
     *
     * <p>This is the module's ONLY argument parser, and the sequence {@link #execute(String[])} runs.
     *
     * <p>Assumptions: it is PUBLIC, and the reason is the contract rather than the tests. The container
     * command line is this module's externally-supplied interface -- an orchestration state definition
     * outside this repository composes it -- and the record this returns is a public type in a
     * different package. A package-private parser would therefore be unreachable from the package that
     * owns the shape it produces, which is where its cases belong.
     *
     * <p>Alternatives Considered: keeping it package-private and moving every parsing case into this
     * class's own test. That is equally correct and was rejected only on blast radius: it would relocate
     * seventeen cases across two files to gain no property this visibility does not already give. What
     * was NOT acceptable, and is the reason this method exists at all, is a test that reassembles these
     * three steps for itself -- that would be a second parser again, differing from the first only in
     * who wrote it, which is precisely the defect being removed.
     *
     * <p>Refactoring Rationale: this method exists because there were previously TWO parsers. The
     * record this returns carried its own {@code fromArguments} factory, thoroughly tested and never
     * called by production, and the two had already diverged on a real rule -- that factory admitted
     * six of the seven jobs with no business date, while this class has always required one for every
     * job. A test suite covering the unused factory could therefore stay green while the parsing that
     * actually ran drifted away from it. Extracting the production sequence into one named method,
     * rather than leaving it inline in {@code execute}, is what lets the tests certify the code that
     * runs instead of a copy of it: a test that reassembled these three steps itself would be a second
     * implementation again, differing only in who wrote it.
     *
     * <p>Assumptions: the ORDER is load-bearing. {@link #requiredJobName(String[])} refuses any token
     * outside {@link #JOB_NAMES} and neutralises the offending value before it reaches a message,
     * whereas {@code BatchJobName.resolve} echoes its argument unsanitised; validating first therefore
     * means the enumeration's own diagnostic is unreachable from a hostile argument. The two token
     * sets are the same seven values, so resolution after validation cannot fail.
     *
     * <p>Assumptions: the business date is REQUIRED here for every job, which is stricter than the
     * record's own invariant -- that invariant demands one only for the accrual job, whose generated
     * transaction identifiers concatenate the token. The stricter rule is kept because it is the
     * behaviour this entry point has always had, and it SUBSUMES the record's rule rather than
     * conflicting with it: a run that supplies a date for every job necessarily supplies one for the
     * accrual job. Relaxing it to match the record was the alternative and was rejected as a
     * behavioural change to the deployed contract, which no finding asks for.
     *
     * <p>Assumptions: no target generation is supplied from a command line, because no option carries
     * one. A job that creates a generation knows its own family and builds the coordinate itself, so
     * the holder is empty here rather than guessed.
     *
     * @param args the container command arguments; may be {@code null} or empty, both of which are
     *     rejected as a missing job option
     * @return the parsed parameters, never {@code null}
     * @throws IllegalArgumentException when the job option is absent or names an unknown job, when the
     *     business-date option is absent or malformed, or when the record's own invariants refuse the
     *     combination
     */
    public static BatchJobParameters parseArguments(String[] args) {
        BatchJobName resolvedJob = BatchJobName.resolve(requiredJobName(args));
        BusinessDate resolvedDate = new BusinessDate(requiredBusinessDate(args));

        return new BatchJobParameters(resolvedJob, Optional.of(resolvedDate), Optional.empty());
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
            // WHY : Refactoring Rationale: the rejected value is bounded and sanitised before it
            //       reaches the message, and the message is what the usage diagnostic echoes to the
            //       log and to standard error. Sanitising HERE rather than at the echo site is what
            //       makes every consumer of this exception safe -- including a future caller that
            //       catches it and reports it somewhere this class does not know about -- and it is
            //       the sanitisation, not the echo, that was the defect.
            throw new IllegalArgumentException(JOB_OPTION + " value '" + sanitiseForDiagnostic(value)
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
            // WHY : Assumptions: the same sanitisation as the job token above, and it matters more
            //       here. A malformed date is the likelier of the two rejections to carry something
            //       unexpected, because a date is assembled by whatever built the container command
            //       rather than chosen from a fixed set of seven tokens.
            throw new IllegalArgumentException(BUSINESS_DATE_OPTION + " value '"
                    + sanitiseForDiagnostic(value)
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
                // WHY : Assumptions: this message names the option and nothing else, so it carries no
                //       caller-supplied text and needs no sanitisation. Echoing the two conflicting
                //       values was considered and rejected: they are the values in dispute, so a
                //       reader gains nothing from them that the option name does not already give,
                //       and echoing them would put two untrusted strings into a diagnostic instead of
                //       none.
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
