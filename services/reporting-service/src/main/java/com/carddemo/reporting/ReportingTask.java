package com.carddemo.reporting;

import java.util.Map;

/**
 * The executable contract a reporting task must satisfy to be dispatchable by the batch orchestrator.
 *
 * <h2>Why this interface exists</h2>
 *
 * <p>Refactoring Rationale: the nightly state machine already dispatches three commands at this
 * module's task definition -- {@code --job=generate-statements} and {@code --job=generate-reports} from
 * the daily chain, and {@code --job=generate-report} from the on-demand chain -- and before this
 * interface existed nothing in the module could receive any of them. The image's entry point started a
 * web server, so a dispatched state would have started a container that listened for requests and never
 * terminated; the state would then have sat until its own timeout expired and reported a timeout rather
 * than a missing implementation. An unrunnable command that fails after a timeout with a misleading
 * error is worse than one that fails immediately with a named one, so the argument contract and the bean
 * contract are both made explicit here.</p>
 *
 * <p>Assumptions: a task is resolved from the application context by BEAN NAME, and the name is exactly
 * the {@code --job=} token that selected it. That is why {@link ReportingTaskRunner#JOB_NAMES} is
 * simultaneously the set of accepted arguments and the set of names looked up: one list cannot drift
 * from the other. No bean carries any of these names yet, and the consequence is stated precisely on
 * that field -- a dispatched state fails with an unresolved-task code naming the token it asked for and
 * the names the context actually offers.</p>
 *
 * <p>Alternatives Considered: a Spring Batch {@code Job} per report, which is what the batch context
 * uses. Rejected for this module on the reasoning already recorded at the
 * {@code spring-boot-starter-batch} entry in its {@code pom.xml}: a second job repository here would be
 * a second, competing restart mechanism over the same runs, and two ledgers disagreeing about whether a
 * step completed is a worse position than having only one. A report is also idempotent in a way a
 * posting run is not -- it reads and writes an output object, so a rerun replaces its own output rather
 * than double-applying anything -- which is what makes a plain runnable sufficient here and insufficient
 * there.</p>
 *
 * <p>Trade-offs: the parameter map is {@code String} to {@code String} rather than a typed record per
 * job. What is given up is compile-time checking of the parameter set at the implementing end; what it
 * buys is that the runner needs no knowledge of which job takes which parameters beyond the validation
 * it already performs at the process boundary, so adding a fourth report does not change the runner's
 * signature. The keys are published as constants on {@link ReportingTaskRunner} so the map is not an
 * untyped agreement made twice.</p>
 */
@FunctionalInterface
public interface ReportingTask {

    /**
     * Runs this reporting task to completion.
     *
     * <p>Assumptions: an implementation signals failure by throwing rather than by returning a status.
     * The runner owns the mapping from an outcome to a process exit status, because that mapping is
     * read by the orchestrator's own choice predicates and has to be stated in exactly one place; an
     * implementation returning its own status would be a second, competing mapping.</p>
     *
     * @param parameters the validated parameters for this run, keyed by the constants published on
     *     {@link ReportingTaskRunner} -- {@link ReportingTaskRunner#BUSINESS_DATE_PARAMETER} for the two
     *     nightly tasks, and {@link ReportingTaskRunner#START_DATE_PARAMETER},
     *     {@link ReportingTaskRunner#END_DATE_PARAMETER} and
     *     {@link ReportingTaskRunner#REPORT_TYPE_PARAMETER} for the on-demand task; never {@code null}
     *     and never containing a {@code null} value
     * @throws Exception if the task could not be completed, in which case the runner reports the hard
     *     failure tier and logs the cause; the exception type is deliberately unrestricted because a
     *     report reads a database and writes an object store and both raise checked exceptions
     */
    void run(Map<String, String> parameters) throws Exception;
}
