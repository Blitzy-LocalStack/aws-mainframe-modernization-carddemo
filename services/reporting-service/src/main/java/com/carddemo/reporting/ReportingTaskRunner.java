package com.carddemo.reporting;

import com.carddemo.common.observability.LogSafeText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Runs this module as a one-shot orchestrated task rather than as a web service.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Refactoring Rationale: the nightly state machine dispatches three commands at this module's task
 * definition -- and that definition is the SAME one the online reporting service runs, because the
 * environment roots wire {@code reporting_task_definition_arn} to
 * {@code module.ecs_service["reporting"].task_definition_arn}. Before this class existed the image had
 * exactly one behaviour: start a web server and listen. A dispatched state therefore started a container
 * that never terminated, and the state reported a TIMEOUT after its whole ceiling had elapsed rather
 * than reporting that the command it sent was not implemented. The three states could not execute, and
 * the way they failed hid the reason. This class gives the image a second mode selected by the presence
 * of {@code --job=}, so a dispatched command is either run or refused immediately with a named code.</p>
 *
 * <p>Assumptions: one image and two modes, rather than a second image with a second entry point. The
 * mode is chosen by an argument because that is the only lever the orchestrator has: an ECS
 * {@code runTask} with container overrides can replace the command, and the task definition -- image,
 * role, log group, secrets and all -- is shared. A separate image would need a separate task definition,
 * a separate repository and a separate deployment, all to carry code that reads the same views through
 * the same role.</p>
 *
 * <p>Assumptions: there are exactly TWO outcome tiers here and not three. The batch context has a warn
 * tier because {@code app/cbl/CBTRN02C.cbl} sets a condition code of four at its line 230 when it has
 * written rejects; no reporting program does anything of the kind -- {@code CBTRN03C}, {@code CBSTM03A}
 * and {@code CBSTM03B} assign {@code RETURN-CODE} nowhere at all -- so a report either produced its
 * output or failed. That is why the orchestrator's statement and report gates test for equality with
 * zero and carry no warn branch.</p>
 *
 * <p>Trade-offs: the argument contract is validated before any context is built, so a malformed command
 * costs no database connection, no parameter-store lookup and no credential resolution. What is given up
 * is that the validation cannot consult anything the context supplies; what it buys is a usage failure
 * that can be exercised on a machine with no infrastructure at all, which is what the tests beside this
 * class do.</p>
 */
public final class ReportingTaskRunner {

    /** Journal for this entry point, named for the class so its events are attributable. */
    private static final Logger LOG = LoggerFactory.getLogger(ReportingTaskRunner.class);

    /**
     * Command-line option selecting the task to run, {@code --job=}.
     *
     * <p>Assumptions: the token is byte-identical to the one the state machine emits. The daily chain
     * builds it with {@code States.Array('--job=generate-statements', ...)} and the on-demand chain with
     * {@code States.Array('--job=generate-report', ...)}, both in
     * {@code infra/modules/step-functions-batch/main.tf}. A one-character disagreement compiles, builds
     * an image and starts a task, and fails only at dispatch -- which is why the usage diagnostic
     * enumerates the accepted set.</p>
     */
    public static final String JOB_OPTION = "--job=";

    /** Command-line option supplying the business date for a nightly task, {@code --business-date=}. */
    public static final String BUSINESS_DATE_OPTION = "--business-date=";

    /** Command-line option supplying the inclusive range start for the on-demand task. */
    public static final String START_DATE_OPTION = "--start-date=";

    /** Command-line option supplying the inclusive range end for the on-demand task. */
    public static final String END_DATE_OPTION = "--end-date=";

    /** Command-line option naming which report the on-demand task is to produce. */
    public static final String REPORT_TYPE_OPTION = "--report-type=";

    /** Parameter key carrying the business-date token to a task, {@code businessDate}. */
    public static final String BUSINESS_DATE_PARAMETER = "businessDate";

    /** Parameter key carrying the inclusive range start to a task, {@code startDate}. */
    public static final String START_DATE_PARAMETER = "startDate";

    /** Parameter key carrying the inclusive range end to a task, {@code endDate}. */
    public static final String END_DATE_PARAMETER = "endDate";

    /** Parameter key naming which report to produce, {@code reportType}. */
    public static final String REPORT_TYPE_PARAMETER = "reportType";

    /**
     * Exact character width of a date token, ten.
     *
     * <p>Assumptions: ten is the baseline's width and not a preference. The report driver supplies its
     * range as two ten-character tokens at {@code app/jcl/TRANREPT.jcl} lines 47 and 55, and the
     * orchestrator validates the same width before dispatching -- its choice states match
     * {@code "????-??-??"}. Accepting any other width here would let a token past this boundary that the
     * state machine had already refused, which would put two different contracts on one argument.</p>
     */
    public static final int DATE_TOKEN_LENGTH = 10;

    /**
     * The three accepted {@code --job=} tokens, in the order the orchestrator dispatches them.
     *
     * <p>Each token is also the name a {@link ReportingTask} bean must register under, so this list is
     * simultaneously the set of accepted arguments and the set of names looked up in the context. It is
     * a target contract: NO bean carries any of these names yet. What that means for a run is specific
     * rather than vague -- the command is accepted, the context starts, the lookup misses, and the run
     * ends in the hard-failure tier with {@link #ERROR_CODE_TASK_UNRESOLVED} naming both the token asked
     * for and the names the context offered.</p>
     *
     * <p>Assumptions: the first two are dispatched by the daily chain's {@code GenerateStatements} and
     * {@code GenerateReports} states and take a business date; the third is dispatched by the on-demand
     * chain's {@code GenerateAdHocReport} state and takes a date range and a report type. The
     * singular-versus-plural difference between {@code generate-reports} and {@code generate-report} is
     * the orchestrator's own and is preserved deliberately: the two are different units of work -- a
     * whole night's reports against one requested report -- and collapsing them would make the on-demand
     * request indistinguishable from the nightly one in every log line and every execution history.</p>
     */
    public static final List<String> JOB_NAMES =
            List.of("generate-statements", "generate-reports", "generate-report");

    /** Process exit status for a clean completion, zero. */
    public static final int EXIT_STATUS_CLEAN = 0;

    /**
     * Process exit status for a hard failure, eight.
     *
     * <p>Assumptions: eight rather than one or two, and the reason is the orchestrator's own predicates.
     * Every gate that consumes this status tests for equality with zero and routes everything else to
     * failure, so any non-zero value would be refused correctly -- but the batch context already
     * publishes eight for the same tier, and one repository-wide value for "this run failed" is what lets
     * an operator dashboard alarm on a single number across both contexts.</p>
     */
    public static final int EXIT_STATUS_HARD_FAILURE = 8;

    /** The stable code reported when the command line is malformed. */
    public static final String ERROR_CODE_USAGE = "CARDDEMO-REPORT-0001";

    /**
     * The stable code reported when no task bean carries the requested name.
     *
     * <p>Assumptions: this is a DIFFERENT code from {@link #ERROR_CODE_TASK_FAILED} because the remedial
     * action differs. An unresolved name means the implementation is absent or registered under another
     * name, which an operator cannot fix by rerunning; a failed task means the implementation ran and
     * did not finish, which a rerun may well resolve.</p>
     */
    public static final String ERROR_CODE_TASK_UNRESOLVED = "CARDDEMO-REPORT-0002";

    /** The stable code reported when the task was reached but did not complete. */
    public static final String ERROR_CODE_TASK_FAILED = "CARDDEMO-REPORT-0003";

    /**
     * The stable code reported when the run ended in a fatal virtual-machine error.
     *
     * <p>Assumptions: separate from {@link #ERROR_CODE_TASK_FAILED} for the reason the batch context
     * separates its own equivalent: an exception says the logic or the data was wrong and the same
     * inputs will fail again, whereas an error says the RUNTIME failed and the operator's next step is
     * the task's sizing rather than the night's data.</p>
     */
    public static final String ERROR_CODE_FATAL = "CARDDEMO-REPORT-0004";

    /**
     * The most links of a cause chain a diagnostic renders, being ten.
     *
     * <p>Assumptions: ten is chosen as a depth no genuine chain in this module reaches -- an assembly
     * invariant wrapped by a task, wrapped by the framework, is three -- while still bounding a chain
     * that a defective wrapper has made cyclic. Refactoring Rationale: it is a bound rather than an
     * unbounded walk because a diagnostic that loops turns a reportable failure into a hung task, and
     * the task is the thing the orchestrator is waiting on.</p>
     */
    static final int CAUSE_CHAIN_LIMIT = 10;

    /**
     * The value reported when a throwable carries no stack trace at all.
     *
     * <p>Assumptions: a sentinel rather than an empty value or a null, because a journal line reading
     * {@code origin=} or {@code origin=null} cannot be told from a formatting defect, whereas this
     * value states that the trace was absent. A virtual machine may omit a trace for an exception it
     * has thrown repeatedly.</p>
     */
    static final String ORIGIN_FRAME_UNAVAILABLE = "unavailable";

    /**
     * Prevents instantiation.
     *
     * <p>Assumptions: this type is a process entry point and holds no state, so an instance would carry
     * nothing and mean nothing.</p>
     *
     * @throws AssertionError always, so that reflective construction fails loudly rather than yielding an
     *     instance whose existence would suggest the type has behaviour tied to one
     */
    private ReportingTaskRunner() {
        throw new AssertionError("ReportingTaskRunner is a static entry point and is never instantiated");
    }

    /**
     * Reports whether a command line selects task mode rather than service mode.
     *
     * @param args the container command arguments; may be {@code null}, which selects service mode
     * @return {@code true} when any argument begins with {@link #JOB_OPTION}, in which case
     *     {@link #execute(String[])} owns the run; {@code false} when the image is to start its web
     *     application as before
     */
    public static boolean isTaskInvocation(String[] args) {
        if (args == null) {
            return false;
        }
        for (String argument : args) {
            if (argument != null && argument.startsWith(JOB_OPTION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates the command line, runs the selected task and computes the process exit status.
     *
     * <p>Separated from the entry point so the whole flow is expressible as a value rather than as a
     * side effect on the process, which is what lets the argument contract be exercised without a test
     * forking a process or being able to terminate the test runner.</p>
     *
     * @param args the container command arguments; may be {@code null}, which is treated as supplying no
     *     options and therefore fails with the usage diagnostic
     * @return {@link #EXIT_STATUS_CLEAN} when the task completed, and {@link #EXIT_STATUS_HARD_FAILURE}
     *     for a malformed command line, an unresolved task name, a failed task or a fatal error
     */
    public static int execute(String[] args) {
        final String jobName;
        final Map<String, String> parameters;
        try {
            jobName = requiredJobName(args);
            parameters = taskParameters(jobName, args);
        } catch (IllegalArgumentException rejection) {
            // WHY : Refactoring Rationale: the rejection text is neutralised before it is written,
            //       because it is the only value here assembled from the command line and every
            //       rejection quotes the offending token back. A token carrying a line feed would end
            //       this event's line and begin a second line the caller composed, level and event name
            //       and all, in a stream a collector parses one line per event -- the concern named
            //       CWE-117. Both destinations are neutralised, not just the log, because standard error
            //       is collected from a container as readily as the log stream is.
            String reason = LogSafeText.sanitize(rejection.getMessage());
            LOG.error("event=reporting.usage.rejected code={} reason={}", ERROR_CODE_USAGE, reason);
            System.err.println("carddemo reporting: " + reason);
            System.err.println(usage());
            return EXIT_STATUS_HARD_FAILURE;
        }
        return runInContext(args, jobName, parameters);
    }

    /**
     * Starts a non-web application context, runs the named task in it and closes it again.
     *
     * @param args the container command arguments, passed on so the framework's own command-line
     *     property source sees them and an operator can override any property exactly as in service mode
     * @param jobName the validated {@code --job=} token, one of {@link #JOB_NAMES}, which is also the
     *     bean name looked up
     * @param parameters the validated parameters for this run
     * @return the process exit status for the run
     */
    private static int runInContext(String[] args, String jobName, Map<String, String> parameters) {
        // WHY : Assumptions: WebApplicationType.NONE is the whole point of this method. The same
        //       annotated configuration that serves the REST API is reused, so the views, the datasource
        //       and the read-only role are configured identically; only the web server is suppressed.
        //       Leaving it on would start a listener the task does not need and, more importantly, would
        //       keep the context alive after the task finished, so the container would not exit and the
        //       orchestrator would see a timeout instead of a completion.
        // WHY : Alternatives Considered: a CommandLineRunner bean plus spring.main.web-application-type
        //       supplied as a property in the container override. Rejected because the property would
        //       have to be set correctly by every caller, and a caller that forgot it would reintroduce
        //       exactly the never-terminating container this class exists to remove. Deciding it here
        //       makes the mode a property of the argument rather than of the caller's discipline.
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                ReportingApplication.class).web(WebApplicationType.NONE).run(args)) {

            final ReportingTask task;
            try {
                task = context.getBean(jobName, ReportingTask.class);
            } catch (NoSuchBeanDefinitionException unresolved) {
                // WHY : Assumptions: the diagnostic names the context's OWN task names beside the one
                //       asked for. A message reporting only the miss sends a reader to the source to
                //       discover what was available, and the commonest cause of this failure is a bean
                //       registered under a neighbouring name rather than none at all.
                LOG.error("event=reporting.task.unresolved code={} job={} available={}",
                        ERROR_CODE_TASK_UNRESOLVED, LogSafeText.sanitize(jobName),
                        List.of(context.getBeanNamesForType(ReportingTask.class)));
                return EXIT_STATUS_HARD_FAILURE;
            }

            task.run(parameters);
            LOG.info("event=reporting.task.outcome outcome=CLEAN job={} exitStatus={}",
                    LogSafeText.sanitize(jobName), EXIT_STATUS_CLEAN);
            return EXIT_STATUS_CLEAN;

        } catch (Exception failure) {
            // WHY : Refactoring Rationale: the throwable is NOT handed to the journal. An earlier
            //       revision passed it as the trailing argument, which the logging facade renders as the
            //       full stack trace INCLUDING every message in the cause chain -- and the messages a
            //       failed statement run produces carry statement data: this module assembles customer
            //       names, street addresses and transaction descriptions, and a provider or an assembly
            //       invariant that fails while holding one of those puts it into a retained log stream
            //       that every holder of log access can read. The class chain and the origin frame
            //       answer where the failure came from without carrying what it was holding.
            // WHY : Trade-offs: the stack trace is given up, and that is a real diagnostic cost rather
            //       than a free win. What replaces it is the class of every throwable in the cause chain
            //       plus the declaring class, method and line of the frame the failure was raised at,
            //       which is what a reader actually navigates by; the frames between are recoverable
            //       from the source once the origin is known. Alternatives Considered: logging the trace
            //       and relying on each raised exception carrying no data -- which the statement
            //       assembly of this module now honours. Rejected because it cannot hold for a throwable
            //       this module did not raise: a driver-level failure may quote the row it was binding,
            //       and no discipline inside this module governs that message.
            LOG.error("event=reporting.task.failed code={} job={} exception={} origin={}",
                    ERROR_CODE_TASK_FAILED, LogSafeText.sanitize(jobName),
                    causeChainOf(failure), originFrameOf(failure));
            return EXIT_STATUS_HARD_FAILURE;
        } catch (Error fatal) {
            // WHY : Assumptions: an Error is caught rather than allowed to propagate, and the reason is
            //       the exit status. An uncaught throwable ends a Java process with status one, which
            //       the orchestrator's gates would refuse correctly but which would be indistinguishable
            //       from any other non-zero cause; catching it lets the run report the tier and the
            //       cause it belongs to before terminating.
            // WHY : Assumptions: the same withholding applies here as to the tier above, and for the
            //       same reason. A StackOverflowError raised inside a recursive assembly, or an
            //       OutOfMemoryError raised while a statement's records were held, can carry a message
            //       composed from whatever was in hand, and this line is written to the same retained
            //       stream. The two tiers are kept symmetrical so that neither becomes the one that
            //       leaks.
            LOG.error("event=reporting.task.fatal code={} job={} error={} origin={}", ERROR_CODE_FATAL,
                    LogSafeText.sanitize(jobName), causeChainOf(fatal), originFrameOf(fatal));
            return EXIT_STATUS_HARD_FAILURE;
        }
    }

    /**
     * Renders the class names of a throwable and of every throwable that caused it.
     *
     * <p>Assumptions: only the CLASS of each link is rendered and never its message, because the class is
     * the part that identifies the kind of failure and the message is the part that can quote statement
     * data. A caller reading {@code java.lang.IllegalStateException<-org.postgresql.util.PSQLException}
     * knows both which layer failed and how the failure surfaced, without having been shown a row.</p>
     *
     * <p>Assumptions: the walk is bounded by {@value #CAUSE_CHAIN_LIMIT} links and by identity, so a
     * self-referential or cyclic chain cannot make this method run away. A framework wrapper that set
     * itself as its own cause would otherwise loop, and a diagnostic that hangs is worse than one that
     * is short. Refactoring Rationale: the bound is a stated constant rather than a magic number, so a
     * reader can tell a truncated chain from a complete one.</p>
     *
     * <p>Assumptions: package-private rather than private, for the same reason
     * {@code BatchApplication.logJobOutcome} is: what a diagnostic line carries is a disclosure decision,
     * and the only way to assert that it carries no message is to call the thing that renders it. Reaching
     * it through {@link #main(String[])} would need a started application context, a datasource and a
     * migrated schema before a single line could be observed.</p>
     *
     * @param throwable the failure to render, of type {@code Throwable}; must not be {@code null}
     * @return the chain of class names joined by {@code <-}, oldest cause last, never {@code null} and
     *     never blank
     */
    static String causeChainOf(Throwable throwable) {
        StringBuilder chain = new StringBuilder(throwable.getClass().getName());
        Throwable cause = throwable.getCause();
        int links = 1;
        while (cause != null && cause != throwable && links < CAUSE_CHAIN_LIMIT) {
            chain.append("<-").append(cause.getClass().getName());
            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
            links++;
        }
        return chain.toString();
    }

    /**
     * Renders the frame a throwable was raised at, as a declaring class, a method and a line.
     *
     * <p>Assumptions: the ORIGIN frame is taken from the deepest cause rather than from the wrapper,
     * because the wrapper's own top frame is the place that caught and re-threw, which a reader already
     * knows from the event name. The deepest cause's top frame is where the failure actually happened.</p>
     *
     * <p>Assumptions: an absent stack trace answers a stated sentinel rather than {@code null}. A trace
     * can be empty when a virtual machine is configured to omit it for a repeatedly-thrown exception, and
     * a journal line reading {@code origin=null} is indistinguishable from a formatting defect.</p>
     *
     * <p>Assumptions: package-private for the same reason as {@link #causeChainOf(Throwable)}, and the
     * two are kept at the same visibility so that a reader does not conclude one of them is the tested
     * one and the other is not.</p>
     *
     * @param throwable the failure to locate, of type {@code Throwable}; must not be {@code null}
     * @return the declaring class, method and line of the origin frame, or
     *     {@value #ORIGIN_FRAME_UNAVAILABLE} when the throwable carries no stack trace; never
     *     {@code null}
     */
    static String originFrameOf(Throwable throwable) {
        Throwable deepest = throwable;
        int links = 1;
        while (deepest.getCause() != null && deepest.getCause() != deepest
                && links < CAUSE_CHAIN_LIMIT) {
            deepest = deepest.getCause();
            links++;
        }

        StackTraceElement[] frames = deepest.getStackTrace();
        if (frames.length == 0) {
            return ORIGIN_FRAME_UNAVAILABLE;
        }
        StackTraceElement frame = frames[0];
        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    /**
     * Extracts the mandatory {@code --job=} token and checks it against the accepted set.
     *
     * @param args the container command arguments; may be {@code null}
     * @return the requested task name, guaranteed to be one of {@link #JOB_NAMES}
     * @throws IllegalArgumentException if the option is absent, empty, or names a task this module does
     *     not accept
     */
    static String requiredJobName(String[] args) {
        String value = optionValue(args, JOB_OPTION);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(JOB_OPTION + " is required and was not supplied");
        }
        if (!JOB_NAMES.contains(value)) {
            throw new IllegalArgumentException(JOB_OPTION + " value '" + value
                    + "' is not one of " + JOB_NAMES);
        }
        return value;
    }

    /**
     * Validates the parameters the named task requires and returns them keyed for the task.
     *
     * <p>Assumptions: the required set is decided by the task name rather than by a single union of
     * every option, because the two nightly tasks and the on-demand task take disjoint parameters. A
     * union would accept a nightly command carrying a report type, and would accept an on-demand command
     * carrying a business date, both of which the orchestrator never sends and neither of which any task
     * would read.</p>
     *
     * @param jobName the validated task name, one of {@link #JOB_NAMES}
     * @param args the container command arguments; may be {@code null}
     * @return the parameters for the run, in a map whose iteration order is the order the options are
     *     declared so a logged rendering is stable; never {@code null}
     * @throws IllegalArgumentException if a required option is absent, or a date token is not exactly
     *     {@link #DATE_TOKEN_LENGTH} characters, or the report type is blank
     */
    static Map<String, String> taskParameters(String jobName, String[] args) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if ("generate-report".equals(jobName)) {
            parameters.put(START_DATE_PARAMETER, requiredDate(args, START_DATE_OPTION));
            parameters.put(END_DATE_PARAMETER, requiredDate(args, END_DATE_OPTION));
            parameters.put(REPORT_TYPE_PARAMETER, requiredReportType(args));
        } else {
            parameters.put(BUSINESS_DATE_PARAMETER, requiredDate(args, BUSINESS_DATE_OPTION));
        }
        return Map.copyOf(parameters);
    }

    /**
     * Extracts one mandatory date option and checks its width.
     *
     * @param args the container command arguments; may be {@code null}
     * @param option the option prefix to read
     * @return the date token, guaranteed to be exactly {@link #DATE_TOKEN_LENGTH} characters
     * @throws IllegalArgumentException if the option is absent or the token is the wrong width
     */
    private static String requiredDate(String[] args, String option) {
        String value = optionValue(args, option);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(option + " is required and was not supplied");
        }
        if (value.length() != DATE_TOKEN_LENGTH) {
            throw new IllegalArgumentException(option + " value '" + value + "' must be exactly "
                    + DATE_TOKEN_LENGTH + " characters");
        }
        return value;
    }

    /**
     * Extracts the mandatory report type.
     *
     * <p>Assumptions: the value is checked for presence only and not against a closed set. The
     * orchestrator itself only checks that the field is present, and the set of report types is a
     * property of the report implementation rather than of this boundary -- enumerating it here would
     * put the same list in two places with nothing holding them together.</p>
     *
     * @param args the container command arguments; may be {@code null}
     * @return the report type, guaranteed non-blank
     * @throws IllegalArgumentException if the option is absent or holds only whitespace
     */
    private static String requiredReportType(String[] args) {
        String value = optionValue(args, REPORT_TYPE_OPTION);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(REPORT_TYPE_OPTION + " is required and was not supplied");
        }
        return value;
    }

    /**
     * Reads the value of one option-form argument.
     *
     * @param args the container command arguments; may be {@code null}
     * @param option the option prefix, including its trailing equals sign
     * @return the text following the prefix on the first matching argument, or {@code null} when no
     *     argument carries the prefix
     */
    private static String optionValue(String[] args, String option) {
        if (args == null) {
            return null;
        }
        for (String argument : args) {
            if (argument != null && argument.startsWith(option)) {
                return argument.substring(option.length());
            }
        }
        return null;
    }

    /**
     * Renders the argument contract as operator-facing usage text.
     *
     * @return the usage text, enumerating every accepted task and the options each one requires; never
     *     {@code null}
     */
    static String usage() {
        List<String> lines = new ArrayList<>();
        lines.add("Usage: " + JOB_OPTION + "<name> [task options]");
        lines.add("Accepted " + JOB_OPTION + "values and the options each requires:");
        for (String name : JOB_NAMES) {
            if ("generate-report".equals(name)) {
                lines.add("  " + JOB_OPTION + name + "  " + START_DATE_OPTION + "<yyyy-mm-dd> "
                        + END_DATE_OPTION + "<yyyy-mm-dd> " + REPORT_TYPE_OPTION + "<type>");
            } else {
                lines.add("  " + JOB_OPTION + name + "  " + BUSINESS_DATE_OPTION + "<yyyy-mm-dd>");
            }
        }
        lines.add("Omit " + JOB_OPTION + "entirely to start the reporting service instead.");
        return String.join(System.lineSeparator(), lines);
    }
}
