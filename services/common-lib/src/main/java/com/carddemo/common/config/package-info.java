/**
 * Owns the contracts a deployment is held to before a service does any work: the environment
 * variables its active configuration reads, checked at startup and named when absent, and the
 * database identity the schema migrator assumes before it creates anything.
 *
 * <p><b>Purpose.</b> Every one of the eight bounded contexts states its deployment inputs as bare
 * environment placeholders in its own {@code application.yml} -- the datasource URL, the migrator
 * credentials, the issuer URI, the queue addresses, the key identifiers. Nothing in Spring Boot
 * requires such a placeholder to resolve: the relaxed binder resolves what it can and binds the
 * literal {@code ${NAME}} text for what it cannot, so a deployment that forgot a variable starts
 * normally and fails later, in a component that has no idea a variable exists. This package holds the
 * one check that turns that class of defect into a startup refusal naming the variable, and it holds
 * it once for all eight services rather than eight times.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package declaration accepts no
 * parameter, yields no value and raises nothing, so this charter carries no parameter, return or
 * exception at-clause. The inapplicability is declared rather than passed over in silence, because
 * the Explainability rule's line 39 forbids a docstring that omits parameters, return values or
 * purpose, and a reader has to be able to tell a declared inapplicability from an oversight.
 *
 * <h2>What this package owns</h2>
 *
 * <ul>
 *   <li><b>{@code RequiredEnvironmentVariablePostProcessor}</b> -- reads the environment Spring Boot
 *       has finished loading, finds every property whose winning value still contains an
 *       environment-variable-shaped placeholder with no default that nothing can supply, and refuses
 *       the start. It is registered through {@code META-INF/spring.factories} under
 *       {@code org.springframework.boot.EnvironmentPostProcessor} and ordered last, so the
 *       configuration it reads is the configuration the application will bind.</li>
 *   <li><b>{@code MissingEnvironmentVariablesException}</b> -- the refusal, carrying one message that
 *       names every absent variable together with the property that reads it.</li>
 *   <li><b>{@code FlywayOwnerRoleCallback}</b> -- assumes the schema's owning role on every
 *       connection Flyway opens, so that a table, index or constraint a migration creates is owned
 *       by the role that owns the schema and not by the identity that ran the migration. It is
 *       registered as a {@code Callback} bean by
 *       {@code CardDemoCommonAutoConfiguration.FlywayOwnerRoleConfiguration}, conditional on Flyway
 *       being on the classpath and on the owning role being configured, so a context that migrates
 *       nothing gets no callback at all.</li>
 *   <li><b>{@code FlywayOwnerRoleDataSourceCustomizer}</b> -- replaces the DataSource Flyway was
 *       configured with by one that assumes that same owning role on every connection it hands out,
 *       BEFORE the engine wraps it. It is registered as a
 *       {@code FlywayConfigurationCustomizer} bean by
 *       {@code CardDemoCommonAutoConfiguration.FlywayOwnerRoleDataSourceConfiguration} under the same
 *       property, and it is the half of the control that makes the ownership stick -- see its own
 *       documentation for the engine behaviour that defeats a callback acting alone.</li>
 *   <li><b>{@code MigrationOwnerRole}</b> -- the configured role name, validated against a strict
 *       identifier allow-list and rendered once as the quoted statement that assumes it, so that the
 *       two classes above reach the server through one check.</li>
 * </ul>
 *
 * <p>This package's own share of the module's class canon, and the directory it describes:
 *
 * <pre>
 * this package: config 5 production + 1 charter = 6 compilation units
 * this directory: 6 java files = 5 classes + 1 charter
 * </pre>
 *
 * <p>Both lines are re-derived from the directory on every build -- the first by
 * {@code SharedKernelInventoryTest} and the second by {@code PackageCharterInventoryTest} -- so a
 * class added here without an edit to this charter fails the build rather than ageing quietly.
 *
 * <h2>Why the check lives here and not in each service</h2>
 *
 * <p>Alternatives Considered: a per-service declaration of the keys that service requires, written
 * either as {@code @ConfigurationProperties} with {@code @NotBlank} members or through
 * {@code ConfigurableEnvironment.setRequiredProperties}. Rejected for a reason that is easy to miss:
 * both mechanisms test whether a PROPERTY is present, and in this failure the property IS present.
 * It holds the unresolved placeholder text, which is a perfectly good {@code String} as far as
 * binding is concerned, so neither mechanism sees the defect. The second reason is arithmetic: the
 * eight services declare between six and sixteen environment inputs each, and a hand-maintained list
 * beside each {@code application.yml} is one more document to keep in step with it. Reading the
 * placeholders out of the configuration that declares them keeps a single source.
 *
 * <p>Trade-offs: the check is a framework hook rather than application code, so it runs outside every
 * mechanism this project uses for the rest of its behaviour -- no bean, no context, no dependency
 * injection, and a registration file rather than an annotation. That is accepted because the defect
 * it addresses is only diagnosable BEFORE the context refreshes: once binding has bound a literal
 * placeholder into a datasource property, every subsequent failure is a consequence and none of them
 * carries the variable's name.
 *
 * <h2>Why this package exists at all, given the root charter's earlier position</h2>
 *
 * <p>Refactoring Rationale: the root charter recorded that this module had no configuration package
 * and gave a reason worth preserving -- a package named for a mechanism becomes a bucket, which is
 * why {@code MetricsConfig} sits beside the concern it configures rather than in a package of its
 * own. That reasoning is unchanged and {@code MetricsConfig} has not moved. What changed is that a
 * contract now exists which belongs to no concern in the module: the environment check is not a money
 * rule, not a codec, not a web filter and not an observability decision -- it is a statement about
 * what a deployment must supply before any of those can run. Its subject is the STARTUP CONTRACT, and
 * the name of this package is read that way: what a service is held to before it serves. A class
 * whose only claim to membership is a name ending in {@code Config} still does not belong here.
 *
 * <p>Assumptions: the subject is deliberately narrow, so that this package does not become the bucket
 * the root charter warned against. Two questions decide membership. Does the contract hold for every
 * bounded context identically, and does it have to be settled before the service does any of the work
 * it was deployed to do? A concern that fails either test belongs beside the concern it serves.
 *
 * <p>Refactoring Rationale: the second question read "before the application context refreshes",
 * which was the true boundary while this package held one member, and admitting
 * {@code FlywayOwnerRoleCallback} showed it to be the wrong boundary rather than a boundary to bend.
 * Schema migration runs DURING the refresh, as a bean initialising, so the callback fails the
 * question as it was written while satisfying everything the question was there to protect: the role
 * a migration's objects end up owned by is identical across the seven migrating services, it is
 * settled before a single request is served, and getting it wrong is discovered later and elsewhere,
 * as a privilege failure in a service that never ran the migration. The question is therefore
 * restated in terms of the work a deployment exists to do rather than in terms of one framework
 * lifecycle event. Alternatives Considered: a separate {@code database} subpackage for the callback,
 * rejected because one class does not carry a concern -- the callback is not a persistence contract,
 * it holds no schema and no entity, and the thing it actually states is what a deployment must be
 * true of before its data layer is usable, which is this package's subject. Trade-offs: the widened
 * question admits slightly more than the narrow one did, and the guard against that is the first
 * question rather than the second -- a contract that any single bounded context could reasonably
 * answer differently is not a startup contract, whichever lifecycle phase it is settled in.
 */
package com.carddemo.common.config;
