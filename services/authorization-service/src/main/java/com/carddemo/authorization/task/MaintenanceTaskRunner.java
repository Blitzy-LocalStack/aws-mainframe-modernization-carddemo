package com.carddemo.authorization.task;

import com.carddemo.authorization.AuthorizationApplication;
import com.carddemo.common.observability.LogSafeText;
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

    /** The parameter name the business date is published under. */
    public static final String BUSINESS_DATE_PARAMETER = "businessDate";

    /** The parameter name the root-image extract path is published under. */
    public static final String ROOT_EXTRACT_PARAMETER = "rootExtract";

    /** The parameter name the prefixed-detail extract path is published under. */
    public static final String CHILD_EXTRACT_PARAMETER = "childExtract";

    /** The job name of the extract load. */
    public static final String LOAD_JOB = "load-authorizations";

    /** The job name of the expiry purge. */
    public static final String PURGE_JOB = "purge-authorizations";

    /** Every job name this runner accepts, in the order the usage text lists them. */
    public static final List<String> JOB_NAMES = List.of(LOAD_JOB, PURGE_JOB);

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
            LOG.error("event=authorization.task.failed code={} job={} exception={}",
                    ERROR_CODE_TASK_FAILED, LogSafeText.sanitize(jobName),
                    failure.getClass().getName());
            return EXIT_STATUS_HARD_FAILURE;
        } catch (Error fatal) {
            LOG.error("event=authorization.task.fatal code={} job={} error={}",
                    ERROR_CODE_TASK_FAILED, LogSafeText.sanitize(jobName), fatal.getClass().getName());
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
        if (PURGE_JOB.equals(jobName)) {
            parameters.put(BUSINESS_DATE_PARAMETER, requiredDate(args, BUSINESS_DATE_OPTION));
            return parameters;
        }
        parameters.put(ROOT_EXTRACT_PARAMETER, requiredValue(args, ROOT_EXTRACT_OPTION));
        parameters.put(CHILD_EXTRACT_PARAMETER, requiredValue(args, CHILD_EXTRACT_OPTION));
        return parameters;
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
                + "  " + JOB_OPTION + LOAD_JOB + " " + ROOT_EXTRACT_OPTION + "<path> "
                + CHILD_EXTRACT_OPTION + "<path>\n"
                + "  " + JOB_OPTION + PURGE_JOB + " " + BUSINESS_DATE_OPTION + "<YYYY-MM-DD>";
    }
}
