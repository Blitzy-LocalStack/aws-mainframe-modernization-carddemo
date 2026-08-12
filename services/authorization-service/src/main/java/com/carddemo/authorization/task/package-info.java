/**
 * The maintenance-job entry points this service is invoked as, rather than served as.
 *
 * <h2>What this package is for</h2>
 *
 * <p>Three of this service's capabilities are jobs rather than requests: loading the pending-authorization
 * extracts, exporting them, and purging authorizations that have expired. Each runs for as long as its input
 * takes, ends with a status a scheduler branches on, and must run exactly once per invocation. This package
 * holds the four things that make that possible -- the
 * {@link com.carddemo.authorization.task.AuthorizationTask} contract, the three beans that implement it, the
 * {@link com.carddemo.authorization.task.ExtractStore} that resolves the locations two of them read and
 * write, and the {@link com.carddemo.authorization.task.MaintenanceTaskRunner} process entry point that
 * resolves a job by name and translates its outcome into an exit status.</p>
 *
 * <h2>Why it exists at all</h2>
 *
 * <p>Refactoring Rationale: it was added because no job had a production invocation path. The service
 * package's own documentation described them as orchestrator-invoked, and the orchestrator had no state for
 * any of them -- no schedule, no controller, no runner and no state in the batch state machine -- so each was
 * reachable only from its own tests. The concrete cost was not hypothetical: the expiry purge is the only
 * thing that bounds the growth of {@code pending_auth_summary} and {@code pending_auth_detail}, so with no
 * way to invoke it those tables grew without limit in any deployment that used the service as its
 * documentation described.</p>
 *
 * <p>Refactoring Rationale: this package was first added with TWO tasks, and this section said "neither job"
 * where it now says "no job". The export was a third capability in exactly the same state and was missed,
 * for a reason worth recording rather than quietly correcting: the load and the purge are named in the
 * service package's list of orchestrator-invoked members and the export is not, so a reading that took that
 * list as the inventory found two. The set-equality case in this package's test now reads the DIRECTORY
 * rather than any list, which is why the third task could not be added without being published.</p>
 *
 * <p>Refactoring Rationale: {@link com.carddemo.authorization.task.ExtractStore} was added with the export
 * because a job entry point alone does not make a capability usable. The load's own documentation asserted
 * that the orchestrator staged its inputs into this task's filesystem; that was false -- a Fargate volume is
 * shared only within one task definition and {@code infra/modules/ecs-service} creates one per service -- so
 * the load's inputs were undeliverable and an export written to such a path would have been discarded with
 * the container. Both directions now name a location that outlives the task.</p>
 *
 * <h2>The contract with the orchestrator</h2>
 *
 * <p>Assumptions: the invocation shape is deliberately IDENTICAL to reporting-service's -- the same
 * {@code --job=} option, the same resolve-a-bean-by-name dispatch, the same two exit statuses. The same
 * orchestrator invokes both through the same container-override mechanism, so two conventions for one thing
 * would leave an operator guessing which service takes which form.</p>
 *
 * <p>Assumptions: the exit status carries only two values, clean and hard failure. A state machine branches
 * on a run that completed or one that did not, and has no third behaviour to attach to a warn tier, so a
 * third status would be one the caller silently treated as success.</p>
 *
 * <p>Assumptions: every job parameter is validated BEFORE the application context starts. A mistyped job
 * name or a missing extract path then costs a usage message rather than a container start-up and a database
 * connection, and the refusal reads as the argument error it is rather than as a job failure.</p>
 *
 * <h2>What is tested here</h2>
 *
 * <p>The runner's argument handling, its job-name resolution and its exit-status translation are asserted
 * directly. That every published job name resolves to a bean, and that no bean implements the contract
 * without being published, is asserted mechanically rather than by review -- a job that compiles but cannot
 * be invoked is exactly the defect this package was added to remove, so it is the one property that must
 * not be re-introducible.</p>
 */
package com.carddemo.authorization.task;
