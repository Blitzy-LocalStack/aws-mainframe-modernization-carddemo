package com.carddemo.authorization;

import com.carddemo.authorization.task.MaintenanceTaskRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Spring Boot entry point of the pending-authorization bounded context.
 *
 * <p><strong>Purpose.</strong> One deployable subsumes the eight COBOL programs under
 * {@code app/app-authorization-ims-db2-mq}, a reference tree measured at 40 files and 7,605 lines which is
 * read and never modified. Three CICS transactions reach four of those programs: {@code CP00} consumes
 * authorization requests from a message queue and drives no map at all, {@code CPVS} lists the pending
 * authorizations, and {@code CPVD} shows one in detail and marks it fraudulent. The remaining four are the
 * batch expiry-and-purge program, driven by job {@code jcl/CBPAUP0J.jcl}, and the three segment load and
 * unload utilities. Eight is the figure to carry: the extension's own README documents five of them and the
 * root README three, so a reader counting from either document undercounts. The per-program roster, with its
 * transaction wiring, its mapsets and the reason the fraud writer is the one a casual count misses, is set
 * out once in this package's charter and is deliberately not restated here. That charter documents the
 * package; this file documents the process.</p>
 *
 * <p><strong>Two modes, one image.</strong> The same image both serves requests and runs a single
 * orchestrated maintenance job, and the arguments decide which. Absent a job selection the process starts
 * the web application and runs until it is stopped; given one it runs that job in a non-web context and
 * terminates on the job's exit status. Both modes are needed because the orchestrator that dispatches the
 * segment load and the expiry purge reaches a container through its command arguments and through nothing
 * else, and the mode selection is owned entirely by {@link MaintenanceTaskRunner}.</p>
 *
 * <p><strong>What starting this class wires.</strong> Component scanning is rooted at this package, so it
 * reaches the eight subpackages beside this file and nothing outside them: the {@code api} controllers
 * {@code PendingAuthController} and {@code FraudController}; the {@code service} beans, among them
 * {@code PendingAuthSummaryService}, {@code PendingAuthDetailService}, {@code FraudMarkingService},
 * {@code AuthorizationDecisionService}, {@code AuthorizationRequestListener}, {@code OutboxPublisher},
 * {@code PurgeJob}, {@code LoadService} and {@code UnloadService}; the {@code repository} interfaces; the
 * {@code domain} entities; the {@code mapper} anti-corruption boundary; the {@code task} entry points; and
 * the {@code config} classes that supply the security, OpenAPI, datasource, queue and identity settings. No
 * inventory is closed with a count here, and that is deliberate: each subpackage carries its own charter and
 * is the authority for its own contents, so a count in this file could only rot. The shared kernel is not
 * scanned; its cross-cutting components arrive through
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, because a scan wide enough to reach them
 * would also reach another context's types.</p>
 *
 * <p><strong>Served surfaces.</strong> Over HTTP the process serves the pending-authorization summary and
 * detail reads and the endpoint that sets the fraud state, published as
 * {@code src/main/resources/openapi/authorization-api.yaml} and fronted by a resource server that validates
 * the identity provider's tokens, with the fraud action guarded by the {@code carddemo-admin} group claim
 * rather than by any field a client supplies. Over messaging it consumes a per-environment authorization
 * request queue and produces onto a per-environment reply queue, both ordered rather than best-effort and
 * each paired with its own dead-letter queue at a {@code maxReceiveCount} of 5; ordering is per card and
 * duplicate suppression is per transaction, as specification section 0.4.1.8 freezes them. In data it owns
 * the PostgreSQL {@code authorization} schema and the four tables in it, and no other context reads or
 * writes that schema. The schema is pinned for every connection by this module's own configuration, not by
 * any class in this tree, and no queue name, endpoint, account identifier or credential appears here:
 * every such value is resolved at startup from configuration owned by the infrastructure and resources
 * channels.</p>
 *
 * <p><strong>Operational contract this process satisfies.</strong> It listens on port 8080 as a non-root
 * user and answers a liveness probe at {@code /actuator/health}. That one port-and-path pair is depended on
 * from three places at once -- the image's own health check, the load balancer's target group, and the
 * security group that admits only that load balancer -- so the module's {@code Dockerfile} and
 * configuration own those values and this file states the contract without restating them. A change made in
 * one place alone presents as a task that never turns healthy rather than as an error.</p>
 *
 * <p><strong>Divergence D-5: the reply is published from an outbox.</strong> The baseline sends the reply
 * before the data lands. In {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} the poll loop opens at
 * L326 and performs the per-message paragraph at L330; inside it the decision is taken at L459, the reply is
 * put at L461 and the database writes follow at L463 to L465; the single {@code EXEC CICS SYNCPOINT} is last,
 * at L335. Neither message operation joins that commit, the get computing no-syncpoint options at L389 to
 * L391 and the put doing the same at L753 and L754, and each segment write logs its own failure and commits
 * regardless. The execution order is therefore put, then write, then commit, which makes a reply sent for
 * uncommitted data the structurally dominant outcome rather than a rare race. This process orders the same
 * work differently: it writes the reply as an outbox row inside the same transaction as the decision and
 * drains that row afterwards, so exactly the committed decisions are the ones a reply exists for. The
 * baseline behaves as described and remains the reference; the difference is registered as D-5. Note that
 * the account context's inquiry flow carries no outbox, because its get and its put are both under
 * syncpoint -- the two rulings are not interchangeable.</p>
 *
 * <p><strong>Divergence D-6: the distributed transaction collapses to a local one.</strong> Transaction
 * {@code CPVD} enters the detail program and reaches the fraud writer by {@code EXEC CICS LINK} at
 * {@code cbl/COPAUS1C.cbl} L248 to L252, so both run in one unit of work across two resource managers: the
 * detail program replaces a hierarchical segment with {@code EXEC DLI REPL} at L525 to L528 and commits at
 * L558, while the fraud writer issues its relational insert and update and contains no
 * {@code EXEC CICS SYNCPOINT} at all -- its only three {@code EXEC CICS} statements are at
 * {@code cbl/COPAUS2C.cbl} L91, L95 and L218 -- so it commits through the caller's syncpoint, with the
 * resource definitions coordinating the pair through {@code ACTION(BACKOUT)} and {@code DROLLBACK(YES)}. All
 * three tables that work touches live in the one {@code authorization} schema here, so there is one resource
 * manager and the two-phase commit is eliminated rather than emulated: a single local transaction carries
 * what the baseline coordinated across two managers, and the outbox row joins that same transaction. The
 * difference is registered as D-6.</p>
 *
 * <p><strong>How parity is judged, stated honestly.</strong> No golden master exists for any path in this
 * module, and none can be produced from the repository as it stands. The queue consumer cannot be compiled
 * at all, because the six vendor message-queue copybooks it copies are absent from the tree; the online
 * programs cannot run end to end without a CICS runtime, which the runner does not have; and the request
 * producer was never supplied by the baseline, only a stub under the existing suite's mocks. Parity here
 * therefore rests on the copybook and table contracts and on logic transcribed paragraph by paragraph, and
 * it is verified by this module's own tests against fixtures built from those layouts. Both divergences
 * above, and every other intentional difference in this context, are enumerated in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The migration adds a path, it does not remove
 * one.</p>
 */
// Refactoring Rationale: eight programs become one deployable, and what motivates that is a consequence
//   recorded in the baseline's own resource definitions rather than a preference.
//   app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd defines four programs, at L11, L18, L25 and L32, but
//   only three transactions: the fraud writer defined at L32 carries TRANSID(CPVD) at L36 and is named in
//   none of that file's DEFINE TRANSACTION stanzas, because DEFINE TRANSACTION(CPVD) at L39 to L40 resolves
//   to the detail program instead. One transaction therefore spans two programs. The DEFINETIME stamps show
//   the relational writer was attached last: the Db2 entry dates to 22/11/27 19:11:50 at L73, the detail
//   program to 23/03/13 at L30, the fraud writer to 23/03/24 11:15:11 at L37 and the Db2 transaction
//   binding to 23/03/24 11:16:32 at L77, the same day and 81 seconds apart, so before the fraud writer
//   existed transaction CPVD had no relational attachment and was purely hierarchical.
//   What one deployable removes is measurable serialisation. The Db2 entry sets THREADLIMIT(1) with
//   THREADWAIT(YES) at L72, so tasks queue for a single thread rather than failing fast, and
//   CONCURRENCY(QUASIRENT) on all four programs at L14, L21, L28 and L35 has the region run them one at a
//   time on the quasi-reentrant task control block. This process serves the same work on ordinary request
//   threads and scales by running more copies of itself, which it can do only because it holds no session
//   state. The unbounded-wait posture spans both tiers -- DTIMOUT(NO) on all three transaction stanzas at
//   L43, L53 and L63 leaves the online side with no deadlock timeout, and the loader job declares TIME=1440
//   at jcl/LOADPADB.JCL L2, twenty-four hours and so effectively no limit -- whereas this process bounds its
//   connect, read, statement and lock waits explicitly in configuration. That bound is a documented
//   improvement across both tiers, not a transcription.
//   Alternatives Considered: four deployables mapping the four program definitions one-to-one. Rejected
//   because all four touch the same three tables, so that mapping would put one schema behind four
//   independent writers and split a bounded context's ownership, which is the failure mode contexts exist
//   to prevent.
// Alternatives Considered: neither spring-boot-starter-batch nor a BatchConfig class appears in this
//   module, and the absence is a decision rather than an omission for a reader to supply. Both names are
//   written out here precisely so that a reader who goes looking for them finds this reasoning instead of
//   silence: specification section 0.4.1.2 annotates BatchConfig as belonging to the batch context alone,
//   and this module's pom.xml declares that starter nowhere. The rejected alternative is adding it here so
//   the purge and the loads could run as framework jobs; it would bring a JobRepository, and a JobRepository
//   owns metadata tables that the batch context already owns in its own schema, so one schema would end up
//   written by two deployables -- the same ownership split the previous entry rejects. What carries the
//   batch-shaped work instead is a scheduled or orchestrator-invoked service method, which suffices because
//   the baseline's own durability contract here is a periodic commit and not a framework:
//   cbl/CBPAUP0C.cbl checkpoints with EXEC DLI CHKP at L355 and, when that checkpoint fails, performs
//   9999-ABEND at L369, whose paragraph at L377 to L383 moves 16 to RETURN-CODE at L382. A commit interval
//   plus a non-zero exit status reproduces that contract, so the batch path is held to a stricter standard
//   than the online one without a job repository.
// Alternatives Considered: no module-info.java is declared here, and none exists anywhere in the reactor. A
//   Java Platform Module System descriptor would name the packages this module exports and the modules it
//   requires, which is a second expression of layering this reactor already asserts as a test:
//   services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java forbids a
//   cloud or web type inside a domain package, forbids importing another context's domain package, and
//   forbids double in the money path. A test cannot fall out of agreement with the code without failing the
//   build, whereas a descriptor asserting overlapping constraints drifts silently and then documents a
//   boundary nothing enforces. The same single-owner reasoning is why config/checkstyle/checkstyle.xml
//   configures no ImportControl module, and adding either one would create the second owner both avoid.
// Assumptions: the default component scan of the annotation below starts at THIS class's own package, so
//   every bean in the eight subpackages is discovered only because this class sits at
//   com.carddemo.authorization rather than one level above or below it. That string is an external contract
//   and not a local naming choice: specification section 0.5.3.1 sets it verbatim alongside the eight
//   sibling roots, and the layering test matches on it. Moving or renaming this class would therefore not
//   fail compilation. It would start a context holding no controller, no listener and no repository, which
//   is the harder failure to diagnose, because an empty context looks like a configuration problem rather
//   than a relocated class. Alternatives Considered: naming the base packages explicitly on the annotation.
//   Rejected because it asserts the same fact a second time, and two statements of one fact can disagree
//   while both look authoritative.
@SpringBootApplication
public class AuthorizationApplication {

    /**
     * Creates the application type.
     *
     * <p>Assumptions: explicit and protected rather than implicit and public. The type exists to carry the
     * annotation and the entry point and nothing constructs it directly, so a wider constructor would
     * advertise a use it does not have. Protected rather than private because the container subclasses a
     * configuration class to intercept its bean methods, and a private constructor would defeat that.</p>
     */
    protected AuthorizationApplication() {
        // Assumptions: empty by design. The framework builds the context from the annotation on this type;
        //   it never instantiates the type itself, so there is no state to establish here.
    }

    /**
     * Starts either the authorization service or one orchestrated maintenance task.
     *
     * <p>Assumptions: task mode terminates the process explicitly with the runner's status and service mode
     * does not terminate at all. A web application is expected to run until it is stopped, whereas a task
     * must both finish and report HOW it finished: returning normally from this method exits with zero
     * whatever the task did, because that is the status of a Java process that completes its main method,
     * and the orchestrator tests these states for equality with zero. A failed purge would then present as a
     * clean one and the chain would carry on past it.</p>
     *
     * @param args the command-line arguments. When any of them begins with
     *     {@link MaintenanceTaskRunner#JOB_OPTION} the process runs that one task and exits with its status;
     *     otherwise they are passed through to the web application unaltered, so an operator can override
     *     any property on the command line exactly as on every other service in this repository. Must not be
     *     {@code null}
     */
    public static void main(String[] args) {
        // Trade-offs: this entry point branches on its own arguments instead of starting the web application
        //   unconditionally, and the compromise accepted is that one class now has two exit paths.
        //   Refactoring Rationale: the branch exists because the segment load and the expiry purge otherwise
        //   have no production invocation path at all. Both are documented as orchestrator-invoked, and an
        //   orchestrator reaches a container task only through its command arguments, so without this branch
        //   a container started with a job selection would have listened for requests and never terminated
        //   -- reporting a timeout after its whole ceiling elapsed instead of reporting what it was asked to
        //   do -- and the purge is the only thing bounding the growth of this schema's two largest tables.
        //   Alternatives Considered: a scheduled method inside the serving context, rejected because it
        //   would run on every replica at once and would leave the run no exit status to branch on; and a
        //   second annotated class dedicated to task mode, rejected because the repackaging plugin resolves
        //   one main class by scanning, so a second would make the executable jar ambiguous and force the
        //   main class to be pinned in the module's pom.xml as a third place for the two to disagree. The
        //   shape matches the reporting context's entry point, because the same orchestrator invokes both.
        if (MaintenanceTaskRunner.isTaskInvocation(args)) {
            System.exit(MaintenanceTaskRunner.execute(args));
        }
        SpringApplication.run(AuthorizationApplication.class, args);
    }
}
