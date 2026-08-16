/**
 * Holds the cross-cutting wiring of the reference-data context, and no business rule.
 *
 * <p>Every type in this package binds one external concern to the service while the application
 * context is assembled: which tokens are accepted and which authority a route demands, which schema
 * an unqualified table name resolves in, which client publishes a reply message, and what the served
 * interface document says about itself. None of them decides a business outcome. The transaction-type
 * validation transcribed from the baseline maintenance screens, the disclosure-rate fallback and the
 * keyset browse behaviour all live in the {@code service}, {@code repository} and {@code mapper}
 * packages of this context, so a reader asking why a category delete was refused, or which rate a
 * missing group resolves to, will not find the answer here.</p>
 *
 * <h2>Orientation</h2>
 *
 * <p>This context owns the {@code reference} database schema and answers for the baseline's
 * transaction-type inquiry and maintenance screens, its batch reference update, the synchronous half of
 * its date conversion and its date-edit utility. That enumeration is authoritative in this module's
 * {@code com.carddemo.reference} charter, which also lists the layer subpackages and what each one
 * holds. This charter names the context only far enough to orient a reader and defers to that one.</p>
 *
 * <p>On parameters, return values and exceptions: a package declaration accepts no argument, returns
 * no value and raises nothing, so this charter deliberately carries none of the three corresponding
 * at-clauses. Assumptions: the inapplicability is stated rather than left silent, because the project
 * Explainability rule lists a docstring that omits its parameters or return values among its
 * forbidden patterns, and a reader has to be able to tell a declared inapplicability from an
 * oversight. Javadoc models no parameter, return or exception concept for a package, and the
 * repository ruleset audits at-clause bodies for emptiness, so an invented empty at-clause would be
 * reported rather than credited.</p>
 *
 * <h2>The three configuration types, and what each one owns</h2>
 *
 * <p>Assumptions: the inventory below is the package contract the migration plan assigns, and each
 * entry carries its own marker for whether that contract is discharged on disk. Naming a type here is
 * neither a claim that it exists nor permission to put its concern somewhere else; the present
 * contents are read from the directory, or from
 * {@code mvn -f services/reference-service/pom.xml test}, rather than from this comment.</p>
 *
 * <p>Refactoring Rationale: the revision this replaces described the package as holding "two
 * concerns" and named a datasource binding without naming the type contracted to hold it, while saying
 * nothing at all about the queue client then sitting beside it or the interface-document metadata.
 * A charter is consulted precisely to learn the division of ownership without opening every file, so
 * an inventory shorter than the contract sends a reader to the wrong package for a binding and, worse
 * here, reads as licence to put a new binding wherever it happens to fit. Per-entry markers are what
 * let one file be complete about the contract and honest about the directory at the same time.</p>
 *
 * <dl>
 *   <dt>{@code SecurityConfig} -- LANDED</dt>
 *   <dd>The stateless resource-server chain, and everything deciding whether a request is authorised:
 *       how a presented token is decoded and checked, the conversion of the group claim into Spring
 *       Security authorities, and the posture of the management routes. It registers
 *       {@code com.carddemo.common.security.JwtRoleConverter} through an authentication-converter
 *       bean, which is what makes the two admitted identity values reach an authorisation decision as
 *       authorities rather than as a value a caller supplied. Assumptions: the write split is by HTTP
 *       METHOD rather than by path, because every other context reads the seeded lookup rows to
 *       validate an address while the maintenance screens are the only writers; a path-based split
 *       would have to enumerate every reference resource and would silently admit a write to one
 *       added afterwards. Assumptions: the group named by {@code HEALTH_PATH} is reachable with no
 *       credential, because the load-balancer target group and the container health check both poll
 *       it before any credential exists, and demanding a token there would withdraw a healthy task
 *       from service; the management routes behind it, including the one named by
 *       {@code METRIC_SCRAPE_PATH}, are granted by NETWORK POSITION rather than by any authority, so
 *       a tokenless collector on the task can read them and nothing off the task can. Whatever no
 *       rule matches is denied rather than merely required to be authenticated.</dd>
 *
 *   <dt>{@code DataSourceConfig} -- LANDED</dt>
 *   <dd>The pool this whole context reads and writes through, and the startup proof that the
 *       connections it hands out resolve an unqualified table name in the {@code reference} schema. It
 *       binds the pool from {@code spring.datasource.hikari}, which keeps the ceiling, the idle floor
 *       and the connection-initialization statement per-profile values rather than compiled ones, and
 *       it registers one callback that asks the server {@code SELECT current_schema()} and compares
 *       the answer with {@code spring.flyway.default-schema}. Assumptions: those are two INDEPENDENT
 *       configuration keys, and comparing them is what makes the check falsifiable -- a check deriving
 *       its expectation from the same key that set the path could never fail. Assumptions: the failure
 *       it exists for is otherwise silent and lands in a DIFFERENT service, because PostgreSQL accepts
 *       a search path naming a schema that does not exist and every other context reads this one's
 *       seeded lookup rows to validate an address, so a wrong path surfaces as a validation refusal
 *       several hops from its cause.</dd>
 *
 * </dl>
 *
 * <p>⚠️ Refactoring Rationale: the roster above enumerated FOUR types and now enumerates three, because
 * {@code SqsConfig} is withdrawn rather than left standing empty. It existed to supply the queue client
 * that this context's date-inquiry consumer published its reply and its error output with, and that
 * consumer is gone: the baseline drives both inquiry programs from ONE request destination,
 * {@code DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE')} at {@code app/app-vsam-mq/README.md} L53, and a queue
 * admits exactly one owning consumer, so the queue is owned by the account context and answered there
 * for both function codes. With no producer and no consumer left in this module there is nothing for a
 * queue client to serve, and a bean that nothing injects is not inert -- it would keep a messaging
 * dependency, a region requirement and a credential path on a module that needs none of the three, and
 * it would read as evidence of a message flow a reader would then go looking for. Assumptions: the
 * corresponding {@code spring.cloud.aws} keys and the three inquiry queue-name variables are withdrawn
 * from {@code application.yml} in the same change, so no configuration survives its consumer either;
 * {@code ReferenceServiceStructureTest} asserts the module declares no queue consumer, which is what
 * keeps the withdrawal from being reversed by accident.</p>
 *
 * <p>Refactoring Rationale: {@code DataSourceConfig} was listed here as PLANNED and then recorded as
 * WITHDRAWN, on the argument that the schema pin and the pool sizing are both declared in this module's
 * {@code application.yml} and that a type here would restate them. That withdrawal is REVERSED and the
 * type has landed: the migration plan assigns this package a datasource binding, and the argument was
 * right about the declaration while being wrong about the check. The type restates no setting. It asks
 * the server what the session resolved and compares that answer with a schema name declared on a
 * different key, so what it asserts is that the pin took EFFECT rather than that it was requested, and
 * those are different facts. Alternatives Considered: leaving the effect-check to an integration test
 * against a real engine, which is what the withdrawal proposed instead. Rejected, because a test proves
 * the pin on the engine the test starts, while a startup callback proves it on the engine the
 * deployment is pointed at -- including a cluster whose search path was altered at the role or database
 * level after the image was built and tested. The set above is therefore closed at FOUR types, and the
 * closed set is five compilation units: this descriptor and the four types.
 *
 * <dl>
 *   <dt>{@code OpenApiConfig} -- LANDED</dt>
 *   <dd>The metadata of the interface document this service serves, aligned to the contract committed
 *       under this module's {@code src/main/resources/openapi} directory. Assumptions: the
 *       specification level of the served document is already pinned declaratively in this module's
 *       {@code application.yml}, where the api-docs key selects the 3.1 form and the contract
 *       directory is added to the served static locations; this type adds the document's own
 *       title, revision and security scheme and not the specification level. Trade-offs: that
 *       contract is authored by hand and is already compared against this module's handlers in both
 *       directions by its routing contract test, so metadata supplied here is expected to agree with a
 *       file that is checked rather than to become a second description of the same interface. The
 *       cost accepted is that the two are kept in step deliberately instead of one being generated
 *       from the other.</dd>
 * </dl>
 *
 * <h2>Why the entry point registers none of this</h2>
 *
 * <p>{@code ReferenceApplication}, the annotated entry point one directory above, registers neither
 * the shared correlation filter nor the authority converter, and imports neither. Assumptions: each
 * filter has exactly one owner, and each owner is named rather than implied. The security chain and
 * its ordering are owned by {@code SecurityConfig} in this package, while the shared correlation
 * filter's registration and its order constant are owned once in
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, which the framework loads from the
 * shared module's registration resource rather than by scanning -- the scan for this service is rooted
 * at {@code com.carddemo.reference}, and every shared kernel type lives outside that root, so no
 * shared component is ever discovered by scanning.</p>
 *
 * <p>Alternatives Considered: registering those two on the entry point instead, which is the shape a
 * reader arriving from another codebase tends to expect. Rejected, because filter ordering would then
 * be split across two files, and a filter registered in two places has no single reviewable order:
 * the position it ends up in is decided by bean ordering rather than by anything written down. The
 * consequence is silent as well as split -- a duplicate registration puts one filter in the chain
 * twice, and because that filter's own once-per-request guard makes the duplicate harmless, there is
 * nothing for a failing test to report. Trade-offs: what is given up is the convenience of reading
 * every registration in one place; what is bought is that each registration has exactly one file able
 * to change it.</p>
 *
 * <h2>What this package deliberately does not hold</h2>
 *
 * <p>There is no batch-job configuration here, and this module declares no batch starter. Assumptions:
 * the baseline's reference update reads a sequential input and is labelled business logic in its own
 * source, so its migrated form is an ordinary service method that the interface and the batch chain
 * both invoke, and not the owner of a durable job repository. Alternatives Considered: giving this
 * module a job repository so that the update could be expressed as a chunk-oriented step. Rejected,
 * because a second job repository in the reactor would put batch state in two schemas and leave a
 * resumed run having to decide which of them it was resuming from; chunk-oriented batch belongs to the
 * batch context, which owns that state and the restart ledger beside it.</p>
 *
 * <p>There is no exception advice here either. The mapping that turns a restricted delete into a 409
 * is inherited from {@code com.carddemo.common.error.GlobalExceptionHandler}, so that status and its
 * wording stay identical across every service. Assumptions: that advice, the meter customiser and the
 * money codec module all reach this context through the same shared registration named above, each
 * guarded by a missing-bean condition, which is why a service genuinely needing to replace one
 * declares a bean of that type rather than switching the registration off. Declaring any of them again
 * here would give one concern two owners and make which of them answers a given request depend on bean
 * ordering rather than on anything written down.</p>
 *
 * <h2>What this package answers for in the baseline</h2>
 *
 * <p>The baseline expresses the equivalent of this package as CICS resource definitions rather than as
 * program code, which is why a wiring package has a lineage at all.
 * {@code app/app-transaction-type-db2/csd/CRDDEMOD.csd} binds transaction {@code CTLI} to program
 * {@code COTRTLIC} at L25-L26 and transaction {@code CTTU} to program {@code COTRTUPC} at L35-L36,
 * declares one {@code DB2ENTRY(CARDDEMO)} at L45-L50 carrying the plan name, the authorisation type, a
 * rollback attribute and a thread limit of one, and attaches both transactions to that entry through
 * the two {@code DB2TRAN} definitions at L51-L60.
 * {@code app/app-vsam-mq/csd/CRDDEMOM.csd} binds transaction {@code CDRD} to program
 * {@code CODATE01} at L27-L28. Everything beneath {@code app/} is the behavioural oracle for this
 * migration: it is read and is never modified.</p>
 *
 * <p>The Java expresses those same bindings as beans of this package together with this module's
 * configuration resources. The identity a transaction ran under becomes a validated token claim, the
 * single-threaded connection entry becomes a sized pool, and the per-transaction resource attachment
 * becomes one schema pin at the connection boundary. Assumptions: the admitted identity domain is the
 * pair of values the shared communication area defines, and {@code app/cpy/COCOM01Y.cpy} declares
 * {@code CDEMO-USER-TYPE} at L26 with its two condition names at L27-L28; this package is where those
 * two values become the authority a guarded route is matched against, so no caller supplies its own.
 * The re-entry discriminator {@code CDEMO-PGM-CONTEXT} at L29-L31 has no counterpart, which is what
 * the stateless session policy in {@code SecurityConfig} records. Each such divergence is registered
 * in {@code docs/architecture/cobol-to-service-traceability.md}, which this charter cites and does not
 * maintain.</p>
 */
package com.carddemo.reference.config;
