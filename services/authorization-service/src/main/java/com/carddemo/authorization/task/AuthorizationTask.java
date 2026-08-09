package com.carddemo.authorization.task;

import java.util.Map;

/**
 * One maintenance job this service can be invoked as, rather than served as.
 *
 * <p>Assumptions: a task is a bean named for the job it performs, so the runner resolves it by NAME from
 * the application context and no dispatch table has to be kept in step with the set of implementations.
 * The consequence is that adding a job is adding a bean; forgetting to register it fails at invocation
 * with the available names listed, rather than compiling into a silently unreachable class.</p>
 *
 * <p>Refactoring Rationale: this interface exists because the two maintenance services it fronts had NO
 * production entry point at all. Their own package documentation described them as invoked by an
 * orchestrator, and nothing invoked them -- no schedule, no controller, no runner and no state in the
 * batch state machine -- so the load and the purge were reachable only from their tests. The shape is
 * taken from {@code com.carddemo.reporting.ReportingTask} deliberately: the two services are invoked the
 * same way by the same orchestrator, and two different conventions for the same thing would be a trap.
 * </p>
 *
 * <p>Alternatives Considered: exposing each as an authenticated endpoint on the service's own web tier.
 * Rejected because both jobs run for as long as their input takes, which is unbounded from a caller's
 * point of view, and a request-scoped invocation gives the orchestrator no way to distinguish a job still
 * running from one whose connection was dropped. A task invocation ends with a process exit status, which
 * is exactly what a state machine branches on.</p>
 */
@FunctionalInterface
public interface AuthorizationTask {

    /**
     * Runs the job to completion.
     *
     * @param parameters the already-validated job parameters, keyed by the names the runner publishes;
     *     never {@code null}
     * @throws Exception if the job cannot complete, which the runner reports as a hard failure exit
     *     status rather than allowing to escape the process untranslated
     */
    void run(Map<String, String> parameters) throws Exception;
}
