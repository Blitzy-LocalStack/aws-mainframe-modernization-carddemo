/**
 * Spring wiring for the batch-service module, covering datasource binding and the
 * connection-level schema contract for the batch bounded context.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: this charter describes the package's <b>target contract</b> as the
 * migration plan assigns it, and it distinguishes what is present from what is planned
 * rather than reading uniformly in the present tense. At the checkpoint that authored
 * it the directory holds this charter and {@code DataSourceConfig}. {@code BatchConfig}
 * is named below as <b>planned</b>, not missing, and the reason it is not present yet
 * is stated rather than left to be inferred.</p>
 *
 * <p><b>Purpose.</b> This package binds external concerns to the module while the
 * application context is being built, and it holds nothing else. No class in it carries
 * a business rule: the posting validation chain, the interest formula and the control
 * break transcribed from the baseline COBOL paragraphs belong to {@code service}, the
 * chunk-oriented readers and writers to {@code job}, and the fixed-width and packed
 * decimal concerns to {@code mapper}. A reader asking why a transaction was rejected,
 * or how a monthly interest figure was reached, will not find the answer here.</p>
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package declaration
 * accepts no parameter, returns no value and raises nothing, so this charter carries no
 * parameter, return or exception at-clause. The inapplicability is declared rather than
 * left silent, because the Explainability rule forbids a docstring that omits parameters
 * or return values and a reader has to be able to tell a declared inapplicability from
 * an oversight.</p>
 *
 * <h2>The closed set</h2>
 *
 * <p>{@code DataSourceConfig} — present. Binds the HikariCP pool from
 * {@code spring.datasource.hikari} and verifies, once, before any step runs, that the
 * connection's effective schema is the one Flyway was configured to migrate. That check
 * carries more weight in this module than in any sibling, because this is the only
 * module whose connections initialise a FIVE-schema search path
 * ({@code batch, ledger, account, card, reference}); a reordered path still resolves an
 * unqualified write, against a real table in the wrong schema, so the failure would be a
 * plausible row rather than an error.</p>
 *
 * <p>{@code BatchConfig} — planned. Its contract is the chunk-oriented step definitions
 * and their reader, processor and writer wiring. Assumptions: it is deliberately absent
 * at this checkpoint rather than authored empty, because a step definition has nothing to
 * define until the jobs exist: {@code job} currently holds only its own charter, and the
 * seven job types the plan assigns it — pre-posting, posting, interest, backup, combine,
 * export and import — are later-index artifacts. Alternatives Considered: authoring the
 * class now with the job repository and transaction manager registered in it. Rejected on
 * two counts: Spring Boot already auto-configures both from the data source, so the
 * registration would restate a framework default and then have to be kept in step with
 * it; and a configuration class whose stated purpose is chunk-oriented steps, holding no
 * step, reads to the next author as though the steps were considered and omitted.</p>
 *
 * <p>{@code OpenApiConfig} and {@code SecurityConfig} — deliberately excluded, not
 * planned. Alternatives Considered: exposing an administrative endpoint that triggers a
 * job over HTTP, which would need both. Rejected because the only invocation path in the
 * target architecture is a synchronous run-task call from a state-machine state that
 * passes job selection and the business date as command overrides. An HTTP trigger would
 * add a second invocation path and with it a second authorization surface to design,
 * test and defend, for no operational gain — and two paths that can start the same
 * nightly job are two paths that can start it twice.</p>
 *
 * <p>{@code SqsConfig} — planned, and only where a job publishes or consumes. Trade-offs:
 * its listener will not auto-start, because a batch job runs on command rather than on
 * message arrival, and a listener that started with the context would begin consuming
 * before the state machine had selected a job.</p>
 *
 * <p>Assumptions: the actuator health group this module exposes exists so the container
 * HEALTHCHECK in {@code services/batch-service/Dockerfile} can probe a datasource-aware
 * response while a job is running. It is not an administrative surface and nothing in
 * this package may make it one: no endpoint here starts, stops or parameterises a job,
 * and the process exit status remains the only outcome the orchestrator reads.</p>
 */
package com.carddemo.batch.config;
