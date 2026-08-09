package com.carddemo.authorization;

import com.carddemo.authorization.task.MaintenanceTaskRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The entry point of the pending-authorization context.
 *
 * <p>This is the migrated form of the long-running task the baseline runs as
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl}, together with the three online programs
 * {@code COPAUS0C}, {@code COPAUS1C} and {@code COPAUS2C} that view and mark the authorizations it
 * records. The baseline splits that work across a queue-driven task and three transactions in a shared
 * region; here it is one deployable that both consumes the queue and serves the endpoints, because the
 * data they contend over is the same data and splitting it would put a bounded context's tables behind
 * two independent writers.</p>
 *
 * <p>Assumptions: the baseline's TWO-PHASE COMMIT is gone rather than emulated. It exists there only
 * because the pending-authorization segments live in a hierarchical database and the fraud rows live in a
 * relational one, so a single decision has to span two resource managers. Both are one PostgreSQL schema
 * here, so the same decision is one local transaction -- the strongest available guarantee, reached by
 * removing a mechanism rather than by reproducing it. The simplification is recorded in
 * {@code docs/adr/ADR-004-messaging.md}.</p>
 *
 * <p>Assumptions: scheduling is enabled by {@code com.carddemo.authorization.config.SqsConfig} rather
 * than here, so the annotation sits with the outbox drain it exists for instead of on a class that would
 * otherwise carry no configuration at all.</p>
 */
@SpringBootApplication
public class AuthorizationApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: this constructor is explicit and protected rather than implicit and public.
     * The type exists to carry the annotation and the entry point, and nothing constructs it directly, so
     * a wider constructor would advertise a use it does not have.</p>
     */
    protected AuthorizationApplication() {
        // Assumptions: empty by design. The framework builds the context from the annotation on this
        // type; it never instantiates the type itself, so there is no state to establish here.
    }

    /**
     * Starts the context.
     *
     * @param args the command-line arguments, passed through so an operator can override any property
     *     on the command line exactly as they can on every other service in this repository; must not be
     *     {@code null}
     */
    public static void main(String[] args) {
        // WHY : Assumptions: a job selection DIVERTS this entry point into the maintenance runner before
        //       any service context is started, and the process then ends on the runner's exit status.
        //       Refactoring Rationale: the diversion is added because the extract load and the expiry
        //       purge had no production invocation path at all -- both documented themselves as
        //       orchestrator-invoked while nothing invoked them, so each was reachable only from its
        //       tests, and the purge is the only thing that bounds the growth of this schema's two
        //       largest tables. Alternatives Considered: a scheduled method inside the service context,
        //       which was rejected because it would run on every serving replica at once and would give
        //       the run no exit status for the orchestrator to branch on. The shape matches
        //       reporting-service's own entry point, because the same orchestrator invokes both.
        if (MaintenanceTaskRunner.isTaskInvocation(args)) {
            System.exit(MaintenanceTaskRunner.execute(args));
        }
        SpringApplication.run(AuthorizationApplication.class, args);
    }
}
