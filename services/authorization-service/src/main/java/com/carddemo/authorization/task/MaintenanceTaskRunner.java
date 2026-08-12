package com.carddemo.authorization.task;

import com.carddemo.authorization.AuthorizationApplication;
import com.carddemo.authorization.service.PurgeJob;
import com.carddemo.authorization.service.UnloadService;
import com.carddemo.common.observability.FailureSummary;
import com.carddemo.common.observability.LogSafeText;
import com.carddemo.common.observability.ThrowableDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
 * The process entry point that runs this service as a maintenance job instead of as a web service.
 *
 * <p>Refactoring Rationale: this runner exists because the load and the purge had NO production invocation
 * path. Both services documented themselves as orchestrator-invoked, and the orchestrator had no state for
 * either: there was no schedule, no controller, no runner and no batch state, so each was reachable only
 * from its own tests. What that cost is not hypothetical -- the expiry purge is the only thing that bounds
 * the growth of the pending-authorization tables, so with no way to invoke it those tables grew without
 * limit in any deployment that used the service as documented.</p>
 *
 * <p>Assumptions: the shape is taken from {@code com.carddemo.reporting.ReportingTaskRunner} deliberately
 * and not reinvented. Both services are invoked by the same orchestrator through the same container
 * override mechanism, so the job option, the exit statuses and the resolve-a-bean-by-name dispatch are
 * identical on purpose; two conventions for one thing would leave an operator guessing which service takes
 * which form.</p>
 *
 * <p>Assumptions: the exit status is the CONTRACT with the orchestrator, and it is deliberately only two
 * values. A state machine branches on a clean run or a failed one and has no third behaviour to attach to
 * a warn tier, so introducing one here would produce a status the caller silently treats as success.</p>
 *
 * <p>Alternatives Considered: a scheduled method on each service, the way the outbox drain is scheduled.
 * Rejected because a schedule inside a web container runs the job on however many instances happen to be
 * serving -- the purge would then run concurrently with itself across replicas, each traversing the same
 * keyset window -- whereas a task invocation runs exactly once because the orchestrator starts exactly one
 * container. It also gives the run no exit status, so a failure would be visible only in a log.</p>
 */
public final class MaintenanceTaskRunner {

    /** The logger usage rejections and task outcomes are reported through. */
    private static final Logger LOG = LoggerFactory.getLogger(MaintenanceTaskRunner.class);

    /** The argument prefix that selects a job and marks the invocation as a task rather than a service. */
    public static final String JOB_OPTION = "--job=";

    /** The argument prefix carrying the purge's business date. */
    public static final String BUSINESS_DATE_OPTION = "--business-date=";

    /** The argument prefix carrying the path of the staged root-image extract. */
    public static final String ROOT_EXTRACT_OPTION = "--root-extract=";

    /** The argument prefix carrying the path of the staged prefixed-detail extract. */
    public static final String CHILD_EXTRACT_OPTION = "--child-extract=";

    /** The argument prefix carrying the export's record form, which the export alone accepts. */
    public static final String EXTRACT_FORM_OPTION = "--extract-form=";

    /**
     * The argument prefix carrying the purge's expiry threshold in days.
     *
     * <p>⚠️ Refactoring Rationale: this option and the two below exist because the purge's own validation
     * and ceilings were unreachable from any runtime entry point. {@code PurgeJob} publishes
     * {@code MAX_EXPIRY_DAYS}, the shared {@code MAX_CARD_FREQUENCY} and a documented refusal of a zero
     * threshold, and the reference program takes all three values on its control card at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L98 to L108 -- but the task passed the three
     * defaults unconditionally, so a run could only ever expire at five days and commit every five
     * summaries. An orchestrator state expressing the reference's parameter card had nothing to write into,
     * and the refusals the job documents could not be provoked by any operator.</p>
     *
     * <p>Assumptions: all three are OPTIONAL and are absent from the parameter map when the operator omits
     * them, so the defaults stay published by {@code PurgeJob} and are not restated here. A copy of a
     * default in this class would be a second place to change it, which is the same reasoning the export
     * form's own optionality already follows.</p>
     */
    public static final String EXPIRY_DAYS_OPTION = "--expiry-days=";

    /** The argument prefix carrying how many summaries the purge commits per window. */
    public static final String CHECKPOINT_FREQUENCY_OPTION = "--checkpoint-frequency=";

    /** The argument prefix carrying how many committed windows fall between purge progress reports. */
    public static final String PROGRESS_LOG_FREQUENCY_OPTION = "--progress-log-frequency=";

    /** The parameter name the business date is published under. */
    public static final String BUSINESS_DATE_PARAMETER = "businessDate";

    /** The parameter name the root-image extract path is published under. */
    public static final String ROOT_EXTRACT_PARAMETER = "rootExtract";

    /** The parameter name the prefixed-detail extract path is published under. */
    public static final String CHILD_EXTRACT_PARAMETER = "childExtract";

    /** The parameter name the export's record form is published under. */
    public static final String EXTRACT_FORM_PARAMETER = "extractForm";

    /** The parameter name the purge's expiry threshold is published under. */
    public static final String EXPIRY_DAYS_PARAMETER = "expiryDays";

    /** The parameter name the purge's commit window size is published under. */
    public static final String CHECKPOINT_FREQUENCY_PARAMETER = "checkpointFrequency";

    /** The parameter name the purge's progress-report interval is published under. */
    public static final String PROGRESS_LOG_FREQUENCY_PARAMETER = "progressLogFrequency";

    /** The job name of the extract load. */
    public static final String LOAD_JOB = "load-authorizations";

    /** The job name of the segment export. */
    public static final String UNLOAD_JOB = "unload-authorizations";

    /** The job name of the expiry purge. */
    public static final String PURGE_JOB = "purge-authorizations";

    /** Every job name this runner accepts, in the order the usage text lists them. */
    public static final List<String> JOB_NAMES = List.of(LOAD_JOB, UNLOAD_JOB, PURGE_JOB);

    /** The exit status of a run that completed. */
    public static final int EXIT_STATUS_CLEAN = 0;

    /** The exit status of a run that did not complete. */
    public static final int EXIT_STATUS_HARD_FAILURE = 8;

    /** The stable code a usage rejection is logged under. */
    public static final String ERROR_CODE_USAGE = "CARDDEMO-AUTHZ-0001";

    /** The stable code an unresolvable job name is logged under. */
    public static final String ERROR_CODE_TASK_UNRESOLVED = "CARDDEMO-AUTHZ-0002";

    /** The stable code a failed job is logged under. */
    public static final String ERROR_CODE_TASK_FAILED = "CARDDEMO-AUTHZ-0003";

    /** How many characters an ISO calendar date occupies. */
    static final int DATE_TOKEN_LENGTH = 10;

    /**
     * Refuses instantiation of a static entry point.
     *
     * @throws AssertionError always, because this class holds only static members
     */
    private MaintenanceTaskRunner() {
        throw new AssertionError(
                "MaintenanceTaskRunner is a static entry point and is never instantiated");
    }

    /**
     * Reports whether the process arguments select a maintenance job.
     *
     * <p>Assumptions: the test is the PRESENCE of the job option and nothing else, so a service start-up
     * cannot be diverted into a task by an unrelated argument. An argument count or a positional
     * convention was rejected for the same reason.</p>
     *
     * @param args the process arguments, which may be {@code null}
     * @return {@code true} when a job was selected
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
     * Runs the selected job in a non-web application context and returns its exit status.
     *
     * <p>Assumptions: the context is started with the web application type set to NONE, so a task
     * invocation binds no port. A task and a service instance are otherwise the same image and the same
     * configuration, and a task that bound the service port could not run alongside one.</p>
     *
     * <p>Assumptions: usage is validated BEFORE the context is started, so a mistyped job name or a
     * missing parameter costs a message rather than a container start-up and a database connection.</p>
     *
     * @param args the process arguments; must not be {@code null}
     * @return {@link #EXIT_STATUS_CLEAN} when the job completed, {@link #EXIT_STATUS_HARD_FAILURE}
     *     otherwise
     */
    public static int execute(String[] args) {
        final String jobName;
        final Map<String, String> parameters;
        try {
            jobName = requiredJobName(args);
            parameters = taskParameters(jobName, args);
        } catch (IllegalArgumentException rejection) {
            String reason = LogSafeText.sanitize(rejection.getMessage());
            LOG.error("event=authorization.usage.rejected code={} reason={}", ERROR_CODE_USAGE, reason);
            System.err.println("carddemo authorization: " + reason);
            System.err.println(usage());
            return EXIT_STATUS_HARD_FAILURE;
        }
        return runInContext(args, jobName, parameters);
    }

    /**
     * Starts the context, resolves the job by name, runs it and translates the outcome.
     *
     * <p>Assumptions: {@code Exception} and {@code Error} are caught SEPARATELY and both become the hard
     * failure status. Letting either escape would end the process on the default uncaught-exception status,
     * which an orchestrator reads as a failure without the stable code and cause chain that tell an
     * operator which of the two happened.</p>
     *
     * @param args the process arguments the context is started with; must not be {@code null}
     * @param jobName the already-validated job name; must not be {@code null}
     * @param parameters the already-validated job parameters; must not be {@code null}
     * @return the exit status the caller passes to the process
     */
    private static int runInContext(String[] args, String jobName, Map<String, String> parameters) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                AuthorizationApplication.class).web(WebApplicationType.NONE).run(args)) {
            final AuthorizationTask task;
            try {
                task = context.getBean(jobName, AuthorizationTask.class);
            } catch (NoSuchBeanDefinitionException unresolved) {
                LOG.error("event=authorization.task.unresolved code={} job={} available={}",
                        ERROR_CODE_TASK_UNRESOLVED, LogSafeText.sanitize(jobName),
                        List.of(context.getBeanNamesForType(AuthorizationTask.class)));
                return EXIT_STATUS_HARD_FAILURE;
            }
            task.run(parameters);
            LOG.info("event=authorization.task.outcome outcome=CLEAN job={} exitStatus={}",
                    LogSafeText.sanitize(jobName), EXIT_STATUS_CLEAN);
            return EXIT_STATUS_CLEAN;
        } catch (Exception failure) {
            // WHY : ⚠️ Refactoring Rationale: the whole CAUSE CHAIN is now recorded, where this line named
            //       only the outermost class. A maintenance job's failure is nearly always wrapped -- the
            //       purge raises its own abend type around a date-resolution or database fault, and the load
            //       raises a segment fault around a parse fault -- so the outermost name was the one piece of
            //       information an operator already had from the job they started, and the fault itself, the
            //       thing they needed, was discarded. The digest carries the chain of TYPES and the frames
            //       that raised them, plus the SQL state when a database link is in the chain, which is what
            //       turns "the purge failed" into "the purge failed on a numeric overflow at this
            //       statement".
            // WHY : ⚠️ Refactoring Rationale: the paragraph above claimed the digest carried "the SQL
            //       state when a database link is in the chain", and it did not -- the digest is a chain of
            //       type names and frames and holds no state code at all. The claim is corrected by making
            //       it true: the state code is now a field of its own, and the failure's own deepest
            //       message is a second field beside it. What the digest still does not carry is message
            //       text, which remains right for the digest and was wrong as the whole of this line.
            // WHY : ⚠️ Refactoring Rationale: getMessage() is still not used, and the reason the
            //       previous paragraph gave for that stands -- a driver's unique-violation message quotes
            //       the offending KEY VALUES, and on this schema those are a primary account number and an
            //       acquirer transaction identifier. What has changed is that withholding the message is no
            //       longer the only alternative: FailureSummary.databaseConditionOf shows a message only
            //       when some link in the chain carries a database state code, and then sanitises it, masks
            //       a card-shaped run and replaces every run of three or more digits -- so the WORDS reach
            //       the line and the VALUES do not. That is what turns "the purge failed" into "the purge
            //       failed on a numeric field overflow", which was the measured gap: diagnosing one
            //       otherwise meant re-running the job with driver debug logging enabled.
            // WHY : ⚠️ Assumptions: the message is DEFAULT-WITHHELD rather than default-shown, and this
            //       runner is exactly the site that needs that discipline -- it wraps whatever a task
            //       raised, so it cannot know who composed what it is holding. A cloud-client failure
            //       quotes credentials and signed locations that no digit rule recognises. The two tasks
            //       whose faults carry a field name worth reading log it themselves, where the provenance
            //       IS known: LoadService names the refused record's ordinal beside its mapper's own
            //       account of what was wrong, and PurgeJob names the window and account ordinals.
            LOG.error("event=authorization.task.failed code={} job={} failure={} detail={} sqlState={}",
                    ERROR_CODE_TASK_FAILED, LogSafeText.sanitize(jobName),
                    ThrowableDigest.of(failure), FailureSummary.databaseConditionOf(failure),
                    FailureSummary.sqlStateOrAbsent(failure));
            return EXIT_STATUS_HARD_FAILURE;
        } catch (Error fatal) {
            // WHY : ⚠️ Assumptions: the fatal arm carries the same two fields as the arm above, in the
            //       same positions, so one log query serves both. A virtual-machine error carries its own
            //       diagnosis in its message as often as a transport fault does -- an out-of-memory error
            //       names the pool it exhausted -- and there is no reason for the more serious arm to be the
            //       less informative one.
            LOG.error("event=authorization.task.fatal code={} job={} failure={} detail={} sqlState={}",
                    ERROR_CODE_TASK_FAILED, LogSafeText.sanitize(jobName), ThrowableDigest.of(fatal),
                    FailureSummary.databaseConditionOf(fatal), FailureSummary.sqlStateOrAbsent(fatal));
            return EXIT_STATUS_HARD_FAILURE;
        }
    }

    /**
     * Extracts the job name and refuses one this runner does not publish.
     *
     * @param args the process arguments; must not be {@code null}
     * @return the selected job name, which is one of {@link #JOB_NAMES}
     * @throws IllegalArgumentException if no job was selected or the selected one is not published
     */
    static String requiredJobName(String[] args) {
        String selected = valueOf(args, JOB_OPTION);
        if (selected == null || selected.isEmpty()) {
            throw new IllegalArgumentException("a job must be selected with " + JOB_OPTION);
        }
        if (!JOB_NAMES.contains(selected)) {
            throw new IllegalArgumentException(
                    "unknown job; the published jobs are " + String.join(", ", JOB_NAMES));
        }
        return selected;
    }

    /**
     * Collects and validates the parameters the selected job requires.
     *
     * <p>Assumptions: each job's parameters are validated HERE rather than inside the task, so every
     * refusal reaches the operator as a usage message with the option name they must correct. A task that
     * validated its own would refuse after the context had started, and the refusal would read as a job
     * failure rather than as a mistyped argument.</p>
     *
     * @param jobName the already-validated job name; must not be {@code null}
     * @param args the process arguments; must not be {@code null}
     * @return the parameters, keyed by the names this class publishes
     * @throws IllegalArgumentException if a required parameter is absent or malformed
     */
    static Map<String, String> taskParameters(String jobName, String[] args) {
        Map<String, String> parameters = new LinkedHashMap<>();
        // WHY : Refactoring Rationale: every job is named EXPLICITLY and an unrecognised one raises, where
        //       this method previously tested for the purge and treated everything else as the load. That
        //       shape was correct while there were two jobs and became a trap when a third was added: the
        //       export wants the same two location options as the load, so it would have fallen through
        //       and appeared to work while silently skipping any validation of its own -- and a fourth job
        //       wanting different options would have been handed the load's.
        switch (jobName) {
            case PURGE_JOB -> {
                parameters.put(BUSINESS_DATE_PARAMETER, requiredDate(args, BUSINESS_DATE_OPTION));
                // WHY : Assumptions: each of the three is put only when the operator wrote it, so an
                //       omitted option leaves the map without the key and the task applies the default the
                //       job publishes. Putting a default here instead would make this class a second
                //       publisher of three values PurgeJob already owns.
                // WHY : Assumptions: the value is validated as a positive integer HERE and its RANGE is
                //       left to PurgeParameters, which is the same division the business date already
                //       follows -- parsed here so a typo reaches the operator as a usage message naming the
                //       option, and bounded there so the ceiling stays stated once, beside the reference
                //       card field whose width it is.
                putIfPresent(parameters, EXPIRY_DAYS_PARAMETER,
                        optionalCount(args, EXPIRY_DAYS_OPTION));
                putIfPresent(parameters, CHECKPOINT_FREQUENCY_PARAMETER,
                        optionalCount(args, CHECKPOINT_FREQUENCY_OPTION));
                putIfPresent(parameters, PROGRESS_LOG_FREQUENCY_PARAMETER,
                        optionalCount(args, PROGRESS_LOG_FREQUENCY_OPTION));
            }
            case LOAD_JOB -> {
                parameters.put(ROOT_EXTRACT_PARAMETER, requiredValue(args, ROOT_EXTRACT_OPTION));
                parameters.put(CHILD_EXTRACT_PARAMETER, requiredValue(args, CHILD_EXTRACT_OPTION));
            }
            case UNLOAD_JOB -> {
                parameters.put(ROOT_EXTRACT_PARAMETER, requiredValue(args, ROOT_EXTRACT_OPTION));
                parameters.put(CHILD_EXTRACT_PARAMETER, requiredValue(args, CHILD_EXTRACT_OPTION));
                // WHY : Assumptions: the form is optional and is therefore absent from the map when the
                //       operator omitted it, rather than present with the default written in here. The
                //       default belongs to the exporter, which publishes it, and a copy of it in this
                //       method would be a second place to change it.
                String form = valueOf(args, EXTRACT_FORM_OPTION);
                if (form != null) {
                    parameters.put(EXTRACT_FORM_PARAMETER, requiredForm(form));
                }
            }
            default -> throw new IllegalArgumentException(
                    "no parameters are declared for job " + jobName);
        }
        return parameters;
    }

    /**
     * Records a parameter only when the operator supplied it.
     *
     * <p>Assumptions: an absent value leaves the key out of the map entirely rather than storing an empty
     * string, because the consuming task distinguishes "not stated" from "stated as nothing" by presence
     * alone -- a key holding an empty string would be parsed and would fail as a malformed number after the
     * container had started, which is precisely the class of refusal this runner exists to catch first.</p>
     *
     * @param parameters the map being assembled; must not be {@code null}
     * @param name the parameter name to record under; must not be {@code null}
     * @param value the value the operator wrote, or {@code null} when the option was omitted
     */
    private static void putIfPresent(Map<String, String> parameters, String name, String value) {
        if (value != null) {
            parameters.put(name, value);
        }
    }

    /**
     * Reads an optional count option and refuses one that is not a positive whole number.
     *
     * <p>Assumptions: only the SHAPE is checked here and the range is not, for the reason recorded at the
     * call site: the ceilings are properties of the reference parameter card's field widths and are stated
     * once, on the purge's own parameter type. What this method rules out is the class of mistake an
     * operator makes at the keyboard -- a non-numeric value, a negative one, a zero, or a value too wide for
     * an {@code int} -- so those reach them as a usage message naming the option rather than as a job
     * failure raised after a container has started.</p>
     *
     * @param args the process arguments; must not be {@code null}
     * @param option the option prefix to read; must not be {@code null}
     * @return the value as the operator wrote it, or {@code null} when the option was omitted
     * @throws IllegalArgumentException if the option is present but empty, not a whole number, or not
     *     positive
     */
    private static String optionalCount(String[] args, String option) {
        String value = valueOf(args, option);
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(option + " must not be empty when it is stated");
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(option + " must be a whole number", malformed);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(option + " must be greater than zero");
        }
        return value;
    }

    /**
     * Refuses an export form this service does not publish.
     *
     * <p>Assumptions: the value is converted here and the converted form discarded, exactly as the business
     * date is parsed and discarded above, so the refusal reaches the operator as a usage message naming the
     * admitted values instead of as a failure raised inside the job after a container has started.</p>
     *
     * @param value the form the operator wrote; must not be {@code null}
     * @return the value, which names a published form
     * @throws IllegalArgumentException if the value names no published form
     */
    private static String requiredForm(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    EXTRACT_FORM_OPTION + " is required and must not be empty");
        }
        UnloadService.UnloadForm.fromRequestParameter(value);
        return value;
    }

    /**
     * Reads a required option value.
     *
     * @param args the process arguments; must not be {@code null}
     * @param option the option prefix to read; must not be {@code null}
     * @return the value
     * @throws IllegalArgumentException if the option is absent or empty
     */
    private static String requiredValue(String[] args, String option) {
        String value = valueOf(args, option);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(option + " is required and must not be empty");
        }
        return value;
    }

    /**
     * Reads a required option value and refuses one that is not an ISO calendar date.
     *
     * <p>Assumptions: the date is parsed here and the parsed value discarded, so the refusal names the
     * option rather than surfacing a parse failure from inside the job. The width is checked as well as
     * the parse, because the parser accepts a longer date-time form that would then silently mean a
     * different instant than the operator wrote.</p>
     *
     * @param args the process arguments; must not be {@code null}
     * @param option the option prefix to read; must not be {@code null}
     * @return the value, which is a parseable ISO calendar date
     * @throws IllegalArgumentException if the option is absent, empty or not such a date
     */
    private static String requiredDate(String[] args, String option) {
        String value = requiredValue(args, option);
        if (value.length() != DATE_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    option + " must be an ISO calendar date of the form YYYY-MM-DD");
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException(
                    option + " must be an ISO calendar date of the form YYYY-MM-DD", malformed);
        }
        return value;
    }

    /**
     * Reads the value that follows one option prefix, taking the LAST occurrence.
     *
     * <p>Assumptions: the last occurrence wins, which matches the shell convention an operator will
     * expect when they repeat an argument to correct it on a re-run.</p>
     *
     * @param args the process arguments; must not be {@code null}
     * @param option the option prefix to read; must not be {@code null}
     * @return the value, or {@code null} when the option is absent
     */
    private static String valueOf(String[] args, String option) {
        String found = null;
        for (String argument : args) {
            if (argument != null && argument.startsWith(option)) {
                found = argument.substring(option.length());
            }
        }
        return found;
    }

    /**
     * Renders the usage text a refusal prints.
     *
     * @return the usage text, never {@code null}
     */
    static String usage() {
        return "usage:\n"
                + "  " + JOB_OPTION + LOAD_JOB + " " + ROOT_EXTRACT_OPTION + "<location> "
                + CHILD_EXTRACT_OPTION + "<location>\n"
                + "  " + JOB_OPTION + UNLOAD_JOB + " " + ROOT_EXTRACT_OPTION + "<location> "
                + CHILD_EXTRACT_OPTION + "<location> ["
                + EXTRACT_FORM_OPTION + String.join("|", UnloadService.UnloadForm.WIRE_VALUES) + "]\n"
                + "  " + JOB_OPTION + PURGE_JOB + " " + BUSINESS_DATE_OPTION + "<YYYY-MM-DD> ["
                + EXPIRY_DAYS_OPTION + "<1-" + PurgeJob.MAX_EXPIRY_DAYS + ">] ["
                + CHECKPOINT_FREQUENCY_OPTION + "<1-" + PurgeJob.MAX_CARD_FREQUENCY + ">] ["
                + PROGRESS_LOG_FREQUENCY_OPTION + "<1-" + PurgeJob.MAX_CARD_FREQUENCY + ">]\n"
                + "\n"
                + "a <location> is either s3://bucket/key or a filesystem path";
    }
}
