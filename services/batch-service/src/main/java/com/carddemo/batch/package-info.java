/**
 * Batch bounded context of the CardDemo mainframe-to-AWS migration: the Spring
 * Batch re-expression of the nightly z/OS batch pipeline.
 *
 * <p><b>Purpose.</b> This package and its seven subpackages own the migrated
 * form of the batch work that ran under JCL and JES2 against VSAM: the
 * daily-transaction preflight, transaction posting, interest accrual, the
 * transaction backup and combine steps, and the export and import round trip.
 * Each of those was a job step selected by name, handed its inputs through
 * data definition statements and its business date through a parameter, and
 * judged by the return code it left behind. That shape is preserved
 * deliberately. The platform underneath it changed; the observable behaviour
 * on top of it did not.</p>
 *
 * <p><b>This module is argument-driven, not request-driven.</b> A job here is
 * started by an AWS Step Functions state that runs an ECS Fargate task through
 * the synchronous run-task integration, and the state's container overrides
 * carry the job token and the business date as process arguments. There is no
 * business controller, published interface contract or route by which an HTTP
 * caller can start work. The sole listener serves actuator health while the job
 * runs; the task then exits and the invoking state reads the process status
 * described under <em>The exit-status contract</em> below.</p>
 *
 * <p>Trade-offs: {@code spring-boot-starter-web} and
 * {@code spring-boot-starter-actuator} are carried only so the Dockerfile probe
 * can detect an unreachable datasource during a run. A process-only probe was
 * rejected because a live JVM says nothing about whether the selected job can
 * reach its data. The accepted cost is a listener in a one-shot task;
 * {@link com.carddemo.batch.BatchApplication} closes the context and calls
 * {@code System.exit} with the job result, so the listener cannot keep a
 * completed task alive.</p>
 *
 * <p><b>This is the only module measured directly against the golden-master
 * parity oracle, which raises the bar for everything inside it.</b> The COBOL
 * three-layer functional-parity oracle suite under {@code tests/} can drive
 * the batch programs end to end, and it cannot do the same for the online
 * ones: {@code tests/README.md} records in its known-limitations section that
 * the online {@code CO*} programs cannot run end to end without a CICS
 * runtime, which the runner does not have. The batch chain is therefore the
 * one place where COBOL output and Java output can be laid side by side and
 * compared byte for byte after timestamp normalisation. Every other module
 * under {@code services/} is held to its transcribed rules; this one is
 * additionally held to committed bytes, and where prose and committed bytes
 * disagree the bytes are right.</p>
 *
 * <h2>What each subpackage owns</h2>
 *
 * <p>Seven subpackages, and no eighth. The list is closed, so the question "which
 * subpackage does this belong in" keeps a definite answer as the tree grows.</p>
 *
 * <ul>
 *   <li><b>{@code job}</b> -- the Spring Batch job definitions. A type here wires readers,
 *       processors, writers and step ordering, and delegates every business rule downward.</li>
 *   <li><b>{@code service}</b> -- the transcribed COBOL business rules. This is where a COBOL
 *       paragraph becomes a named method, which is what lets the traceability matrix cite
 *       paragraph-to-method pairs rather than gesture at a file.</li>
 *   <li><b>{@code repository}</b> -- data access. Every file verb of the baseline lands here and
 *       nowhere else.</li>
 *   <li><b>{@code domain}</b> -- the entity mappings for the tables this module owns and for the
 *       granted tables it reaches.</li>
 *   <li><b>{@code dto}</b> -- the job argument and result records, taken field for field from the
 *       copybook layouts.</li>
 *   <li><b>{@code mapper}</b> -- the hand-written anti-corruption layer. This is the only place
 *       copybook representation concerns are permitted to appear: fixed widths, sign overpunch,
 *       packed decimal, dropped {@code FILLER} and the baseline field-name misspellings.</li>
 *   <li><b>{@code config}</b> -- datasource, batch and messaging wiring. There is deliberately no
 *       {@code SecurityConfig}: this module opens no business listener, so there is no filter chain
 *       for one to configure.</li>
 * </ul>
 *
 * <p>Each of those seven carries its own package charter. A Java package declaration can carry
 * documentation only in a {@code package-info.java}, so the Javadoc block is this file's entire
 * reason to exist rather than decoration on it.</p>
 *
 * <h2>Boundaries this package does not cross</h2>
 *
 * <p>Assumptions: this module owns the {@code batch} schema and nothing else. It reaches
 * {@code ledger} and {@code account} under narrowly-scoped cross-schema write grants and
 * {@code reference} under {@code SELECT} alone, so a write attempted outside those grants is
 * refused by the database rather than caught by a convention.
 * {@link com.carddemo.batch.config.DataSourceConfig} records the four schemas and their grants.</p>
 *
 * <p>Assumptions: no type here imports another service's {@code domain} package, and the
 * prohibition is an ArchUnit rule in {@code common-lib} rather than a review convention. Where this
 * module and another need the same table, they agree through the physical schema and never through
 * code.</p>
 *
 * <p>Trade-offs: the posting unit of work spans three tables in two schemas and commits as ONE
 * transaction, which is the documented exception to database-per-service purity. A saga with
 * compensating reversals was rejected because it would make a posted transaction with an unposted
 * balance an observable intermediate state the reference system cannot produce, and the golden
 * masters would correctly flag it. The design is recorded in
 * {@code docs/architecture/batch-orchestration.md}.</p>
 *
 * <h2>Invariants every file in this subtree inherits</h2>
 *
 * <p>Assumptions: money is {@code BigDecimal} at scale 2 under one rounding contract,
 * {@code com.carddemo.common.money.Money#GENERAL_ROUNDING}; {@code double} and {@code float} are
 * forbidden in the money path and an architecture test asserts it. The reference accrual truncates
 * where this module rounds half up, which is registered as divergence C-ROUNDING in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Arithmetic order is preserved under
 * transformation rule T4: a product is formed at full precision and only then divided.</p>
 *
 * <p>Assumptions: a business date arrives as a job parameter and is never read from the clock. That
 * is what makes a rerun reproducible, and it is the property the golden comparison depends on --
 * {@code app/jcl/INTCALC.jcl} injects the date on its {@code PARM} for the same reason.</p>
 *
 * <p>Assumptions: every step is idempotent through the {@code batch.batch_run} ledger, whose
 * uniqueness constraint over the run and step pair is the idempotency key. The reference pipeline
 * expresses no restart or checkpoint contract at all -- its one {@code RESTART=} directive is
 * commented out -- so this is a capability the target adds rather than a port.</p>
 *
 * <h2>The exit-status contract</h2>
 *
 * <p>Assumptions: the process status is the interface to the orchestrator, and it follows the
 * mainframe condition-code convention rather than the Unix one: 0 success, 4 a soft warning such as
 * a business-rule reject correctly written, 8 a failure, 16 an abend.
 * {@link com.carddemo.batch.BatchApplication} closes the context and calls {@code System.exit} with
 * that value, and the invoking Step Functions state branches on it. A non-zero status is therefore
 * not automatically a failure, which is the one thing a reader arriving from a Unix convention will
 * get wrong.</p>
 *
 * <p>Assumptions: this file is restricted to ASCII. {@code tests/README.md} uses a non-breaking
 * hyphen inside the very justification labels this charter reproduces, so copying from there would
 * yield a label that looks right, greps wrong and silently fails an audit searching for the
 * canonical spelling.</p>
 */
package com.carddemo.batch;
