package com.carddemo.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the reporting and statement context.
 *
 * <p>This is the migrated form of {@code app/cbl/CORPT00C.cbl} online and of the batch report and
 * statement programs {@code app/cbl/CBTRN03C.cbl}, {@code app/cbl/CBSTM03A.CBL} and
 * {@code app/cbl/CBSTM03B.CBL}. It is the only context that owns no tables: its reads go to the writer
 * through read-only cross-schema views under a database role holding select and nothing else.</p>
 *
 * <p>Refactoring Rationale: the ad-hoc report submission the baseline performs by writing job control
 * into a transient data queue mapped to the internal reader -- the {@code TDQUEUE(JOBS)} definition at
 * line 502 of {@code app/csd/CARDDEMO.CSD} -- becomes a state-machine execution start. The mechanism is
 * replaced rather than reproduced because nothing in the target submits text to a job entry subsystem,
 * and the substitution is recorded in {@code docs/architecture/batch-orchestration.md}.</p>
 *
 * <p>Assumptions: two documented divergences from the baseline belong to this context and both are
 * corrections rather than changes of intent. The statement generator's two unchecked tables -- which
 * overflow at 512 same-card transactions and at 52 distinct cards -- have no counterpart here, because
 * the migrated implementation holds no fixed-arity table at all; and the 133-column report keeps its
 * exact edit masks so its output bytes stay comparable against the golden masters. Both are registered
 * in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: component scanning is rooted at this package, so the shared kernel's cross-cutting
 * components are not scanned -- they arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}. A scan wide enough to reach them would
 * also reach another context's types, which is the coupling the layering test forbids.</p>
 *
 * <p>Refactoring Rationale: this image has TWO modes and the entry point chooses between them. It served
 * only the web application before, and that was a defect rather than a simplification: the nightly state
 * machine already dispatches {@code --job=generate-statements} and {@code --job=generate-reports} at this
 * module's task definition -- the very same definition the online service runs, since the environment
 * roots wire {@code reporting_task_definition_arn} to the reporting ECS service's own -- and the
 * on-demand machine dispatches {@code --job=generate-report}. A container started by any of those three
 * would have listened for requests and never terminated, so the state would have reported a TIMEOUT after
 * its whole ceiling elapsed instead of reporting that the command was not implemented. Task mode is
 * selected by the presence of {@code --job=} and is owned entirely by {@link ReportingTaskRunner}.</p>
 *
 * <p>Alternatives Considered: a second {@code @SpringBootApplication} class dedicated to task mode, which
 * would separate the two entry points completely. Rejected because the repackaging plugin resolves a
 * single main class by scanning, so a second one makes the executable jar ambiguous and the module would
 * need the main class pinned in its {@code pom.xml} -- a third place for the two to disagree. One entry
 * point that branches on its own arguments keeps the executable jar unambiguous and puts the choice
 * beside the contract it implements.</p>
 */
@SpringBootApplication
public class ReportingApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: explicit and protected rather than implicit and public. The type exists to carry
     * the annotation and the entry point and nothing constructs it directly, so a wider constructor
     * would advertise a use it does not have.</p>
     */
    protected ReportingApplication() {
        // Assumptions: empty by design. The framework builds the context from the annotation on this
        // type; it never instantiates the type itself, so there is no state to establish here.
    }

    /**
     * Starts either the reporting service or one orchestrated reporting task.
     *
     * <p>Assumptions: task mode terminates the process explicitly with the runner's status, and service
     * mode does not terminate at all. A web application is expected to run until it is stopped, whereas a
     * task must both finish and report HOW it finished: returning normally from this method would exit
     * with zero whatever the task did, because that is the status of a Java process that completes its
     * main method, and every gate the orchestrator places on these states tests for equality with zero.
     * A failed report would then present as a clean one and the chain would carry on.</p>
     *
     * @param args the command-line arguments. When any of them begins with
     *     {@link ReportingTaskRunner#JOB_OPTION} the process runs that one task and exits with its
     *     status; otherwise the arguments are passed through to the web application so an operator can
     *     override any property on the command line exactly as on every other service in this repository.
     *     Must not be {@code null}
     */
    public static void main(String[] args) {
        if (ReportingTaskRunner.isTaskInvocation(args)) {
            System.exit(ReportingTaskRunner.execute(args));
        }
        SpringApplication.run(ReportingApplication.class, args);
    }
}
