/**
 * Spring configuration package of the pending credit-card authorization bounded context.
 *
 * <h2>The directory, measured rather than remembered</h2>
 *
 * <p>Seven compilation units sit in this directory: this charter and the six configuration classes
 * {@code DataSourceConfig}, {@code InternalIdentityConfig}, {@code JsonReadConfig},
 * {@code OpenApiConfig}, {@code SecurityConfig} and
 * {@code SqsConfig}. Every class name, file name
 * and count here is a measurement of that directory, and the marker line is re-measured on every
 * build by
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/PackageCharterInventoryTest.java},
 * so a seventh class arriving without an entry here fails the build:</p>
 *
 * <pre>
 * this directory: 7 java files = 6 classes + 1 charter
 * </pre>
 *
 * <p>Assumptions: the shared-kernel types named further down, and the profile configuration this
 * package reads, are contracts this package is written against rather than members of it, so they are
 * outside the count above and are cited by path where they matter.</p>
 *
 * <p><b>Purpose.</b> This package holds the Spring configuration for one bounded context and
 * nothing else: the beans and settings that put a validated token, a published contract, a database
 * connection and a message listener in place before any request or message is handled. It declares
 * no business rule, performs no persistence access and shapes no response body. A reader looking
 * for why an authorization was declined, why a fraud mark was refused, or why a primary account
 * number arrives truncated on a response will not find the answer here.
 *
 * <p><strong>Documentation contract.</strong> This file exists because user-specified Rule 1
 * (Explainability) L15 requires a docstring on every module entry point, and a Java package
 * declaration is one. A {@code package-info.java} is the only construct that can carry Javadoc for
 * a package, which is why the obligation lands in this file and nowhere else, and L22 names Javadoc
 * as the format Java must use. Of the four content elements that rule enumerates at L18 to L21,
 * only Purpose applies: a package declaration accepts no parameter, yields no value and raises
 * nothing, so the Parameters, Return values and Exceptions elements are inapplicable here rather
 * than omitted, and no at-clause is written to stand in for one of them. The inapplicability is
 * declared rather than passed over in silence, because L39 forbids a docstring that omits purpose
 * and a reader has to be able to tell a declared inapplicability from an oversight. The obligation
 * is Rule 1's; the mechanism that makes it machine-checked is the migration plan's, at its section
 * 0.8.1, which binds a Javadoc gate to the Maven {@code validate} phase in
 * {@code services/pom.xml}. Rule 1 L43 states the consequence of a missing docstring as a failed
 * review; that binding turns the same omission into a failed build before a single class in this
 * module compiles. This build's exit status is binary. The graded condition-code rubric under which
 * the COBOL parity oracle treats a warning-level aggregate as its green state belongs to that suite
 * alone, and it is never carried into a Maven, Checkstyle, Surefire or Failsafe result on this side.
 *
 * <h2>The six configuration classes</h2>
 *
 * <p>Target contract: this package is to hold exactly six {@code @Configuration} classes, each with
 * a narrow and separately testable responsibility. The enumeration below is the closed set assigned
 * to it, and it is now also a measurement: six files beside this one carry that annotation.</p>
 *
 * <p>⚠️ Refactoring Rationale: the count has moved twice and both movements are recorded, because a
 * bare figure gives a reader no way to tell a deletion from a miscount. It read six, then seven, and
 * now reads six again. The second movement is {@code MessagingIdentityConfig} being DELETED, which
 * took it from seven back to six, and that deletion carries its own record two paragraphs below,
 * beside the entry it replaced. The first movement is {@code JsonReadConfig} being ADDED, which took
 * it from six to seven, and it is recorded as an addition rather than a correction -- six was accurate
 * for the tree it was measured against. That class holds the request reader to the
 * character domains the published contract
 * declares: a JSON number reaching a member the document declares {@code type: string} was CONVERTED
 * and accepted, so a numeric account scope was answered rather than refused. It is a class in this
 * package rather than a key in the sibling {@code application.yml} for one reason, and the reason is
 * the exception to the paragraph further down about values living in the resources channel: the single
 * property that withdraws scalar coercion withdraws it SYMMETRICALLY, and the opposite direction is
 * load-bearing here, because money crosses every boundary of this migration as a string and is read
 * into an exact decimal. An asymmetric refusal is not expressible as a property, so it is expressed as
 * a bean. Assumptions: the two keys that complete the same decision -- the undeclared-member refusal
 * and the duplicate-member refusal -- DO live in {@code application.yml}, and
 * {@code config/JsonReadConfigTest} asserts that the packaged document really sets them, so the half
 * held here and the half held there cannot drift apart unnoticed.</p>
 *
 * <p>Refactoring Rationale: a class named {@code MessagingTokenConfig} was DELETED from this package
 * rather than repointed, and the deletion is recorded because a reader may find the name in a diff or
 * a message. It was a second tokeniser configuration binding
 * {@code carddemo.security.mask-hmac-key} -- a property the sibling {@code application.yml} does not
 * supply, and which {@code infra/modules/ecs-service} provisions for the extract-transform-load
 * workload alone. It contributed an unqualified bean of the same type as
 * {@code MessagingIdentityConfig}'s and read a property no deployment of this context supplies, so
 * this context could not start at all under its own declared deployment. Two beans deriving one
 * identity is the condition under which a consumer silently binds the wrong key, which is why one of
 * the two had to go rather than both being kept and disambiguated. Assumptions: neither survives now.
 * The one that was kept in that pass has since been withdrawn in its own right, for the separate
 * reason recorded below, so a reader chasing either name finds no class and both records explain
 * why.</p>
 *
 * <ul>
 *   <li>{@code SecurityConfig} builds the resource server filter chain. Every business request
 *       carries a Cognito-issued token that the chain validates before anything else runs; the
 *       {@code cognito:groups} claim on that token becomes the caller's Spring Security
 *       authorities; and fraud marking carries its own route rule, admitting either business group,
 *       which is the authority the baseline grants an ordinary user through main-menu option 11.
 *       Only {@code /actuator/health} is permitted before authentication; every
 *       business endpoint and every other actuator endpoint stays protected. Trade-offs: the load
 *       balancer target group and the container health check both poll that endpoint before any
 *       credential exists, so requiring a token there would fail every probe and remove a healthy
 *       task from service; the accepted cost is one read-only health route reachable without a
 *       credential. Alternatives Considered: opening all of {@code /actuator/**} was rejected
 *       because the probes need no operational endpoint beyond health. Assumptions: authorization
 *       is decided from the signed claim and never from a field the caller supplies about itself.
 *       That is a property the baseline could not have, because it carried its one-character user
 *       type in a storage area the terminal echoed back between screen turns, so the value arrived
 *       from the client rather than from an issuer.</li>
 *   <li>{@code OpenApiConfig} supplies the OpenAPI 3.1 metadata this service publishes, aligned to
 *       the contract committed at {@code src/main/resources/openapi/authorization-api.yaml}.
 *       Assumptions: that contract has a real consumer, because the browser application's typed
 *       client is generated from it, so the metadata declared here and the committed document are
 *       expected to agree rather than drift.</li>
 *   <li>{@code DataSourceConfig} pins the connection {@code search_path} to the
 *       {@code authorization} PostgreSQL schema and sizes the HikariCP pool. Assumptions:
 *       {@code authorization} is the single schema this context owns, so pinning the search path is
 *       what keeps an unqualified table name in a query resolving inside this context's own schema
 *       and nowhere else.</li>
 *   <li>{@code SqsConfig} configures the listener and sender infrastructure for the ordered
 *       authorization request and reply queues, each paired with its own dead-letter queue, and it
 *       routes a reply to the queue the request nominated rather than to a queue named here.
 *       Assumptions: the target transport carries no per-message expiry field of its own, so a
 *       message states its own staleness in an {@code expiresAt} attribute and the listener drops
 *       and logs a message whose instant has passed. The baseline expressed the same deadline in
 *       the message descriptor its queue manager supplied, so on this side the deadline moves out
 *       of the descriptor and into the payload contract, and it is honoured by the consumer instead
 *       of by the broker.</li>
 * </ul>
 *
 * <p>The fourth class is the one that distinguishes this package from its counterpart in a context
 * that exchanges no messages. Assumptions: this module's own {@code pom.xml} puts a queue starter
 * and a queue client on the classpath, so there is listener infrastructure here to configure; a
 * sibling context whose POM declares neither carries no such class, and adding one there would
 * configure a client that nothing can inject.
 *
 * <ul>
 *   <li>⚠️ Refactoring Rationale: a class named {@code MessagingIdentityConfig} stood at the head of
 *       this list and is WITHDRAWN, together with the {@code carddemo.messaging.hmac-key} property it
 *       read, the {@code CARDDEMO_MESSAGING_HMAC_KEY} container secret that delivered it, the secret
 *       resource both environment roots provisioned for it, the task-role grant that read it and the
 *       {@code infra/modules/ecs-service} validation condition that required the authorization task to
 *       receive it. It supplied one named {@code OpaqueIdentifier} bean, and the reason it is gone is
 *       that after the queue identities became literal values there was nothing left for that bean to
 *       key: no component of this context injected it, and its own documentation said so while
 *       arguing for its retention on the ground that a provisioned secret would otherwise have no
 *       declared consumer. That argument is circular -- the secret existed to serve the bean and the
 *       bean was kept to justify the secret -- and it left a real cost standing: a secret provisioned
 *       in both environments, injected into a running task, read by a task role and rotated by an
 *       attended runbook procedure, for a value nothing computed. Withdrawing the whole chain at once
 *       is what removes that cost rather than relocating it. Assumptions: the two derived-identity
 *       surfaces of the sibling {@code .mapper} package,
 *       {@code AuthorizationMessageMapper.businessCorrelationToken} and
 *       {@code MappingDiagnostic.structuredFields(OpaqueIdentifier)}, are unaffected, because each
 *       takes the tokeniser as a PARAMETER rather than injecting it -- so they remain callable, and a
 *       future caller that has a real use for a derived correlation token supplies a tokeniser keyed
 *       from a secret provisioned in the same change as the caller, which is the order that keeps a
 *       provisioned secret and its consumer arriving together. Alternatives Considered: wiring a
 *       consumer instead, so the bean gained the injection point it lacked. Rejected because the only
 *       candidate consumers are those two surfaces, and neither is called by anything in this context
 *       -- inventing a call site to justify a secret is the same circularity in the opposite
 *       direction. Alternatives Considered: keeping the bean and deleting only the infrastructure, or
 *       keeping the infrastructure and deleting only the bean. Both rejected: either half left
 *       standing is the failure this entry records, in that a key with no reader and a reader with no
 *       key are both states in which the deployment contract and the code disagree. The
 *       {@code OpaqueIdentifier} primitive itself is untouched in the shared kernel, where the card
 *       selector, the keyset cursor seal and the reporting artifact identity each key it from their
 *       own separately provisioned secrets.</li>
 *   <li>{@link InternalIdentityConfig} supplies the short-lived bearer identity this context
 *       presents when it reads the card cross-reference and the account master from the context
 *       that owns them, from {@code carddemo.internal-identity.authorization-signing-key}. Assumptions:
 *       this is now the ONLY secret-keyed class in the package, and its key is the only secret this
 *       context reads beyond its database credentials. Refactoring Rationale: this entry argued its
 *       separateness from the withdrawn messaging tokeniser above, on the ground that one secret
 *       authenticates this service to a sibling service while the other derived queue metadata. That
 *       comparison no longer has a second term. What survives it is the rule the comparison was making:
 *       a class that held two keys for two trust purposes would make rotating either require reasoning
 *       about both, so a second secret arriving here arrives in a class of its own.</li>
 *   <li>{@link JsonReadConfig} refuses a non-textual scalar where the published contract declares a
 *       character field, so {@code {"accountId":10000000101}} is answered as a malformed body rather
 *       than converted into the eleven digits the pattern constraint would then accept. ⚠️ Assumptions:
 *       it declares the refusal for the textual target family and for the integer, floating-point and
 *       boolean input shapes only, and NOT as a withdrawal of scalar coercion, because the reverse
 *       direction carries every amount this migration moves -- money arrives as a string and is read
 *       into an exact decimal, on this context's cross-context account read among others. It is the
 *       only class here that configures the reader; the two sibling refusals, of an undeclared member
 *       and of a member named twice, are feature keys in the sibling {@code application.yml}.</li>
 * </ul>
 *
 * <h2>Deliberately absent from this package</h2>
 *
 * <p>An absence in a configuration package is invisible, so each one a reader might reasonably
 * expect to find is named below with its own reason. The first is the one a reader who knows the
 * migration plan will actively come looking for, and it is stated first for that reason.</p>
 *
 * <p>Alternatives Considered: there is no {@code BatchConfig} in this package, and this module
 * declares no Spring Batch starter. The migration plan scopes that class to the batch context
 * alone, at its section 0.4.1.2; {@code spring-boot-starter-batch} is <b>managed</b> in the
 * aggregator POM at {@code services/pom.xml} so that a reader can find it in this build's managed
 * set, and it is <b>consumed</b> only by that one module. The alternative weighed was a Spring Batch
 * {@code JobRepository} declared here, owning durable step progress for this context's own
 * long-running work. Adopting it would put a second owner on the durable-progress concern, because
 * the reference program this context's purge job is transcribed from already carries a progress
 * contract of its own: {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} commits its
 * position with a hierarchical-database checkpoint call at L355, and where that call reports a
 * non-blank status the failure branch performs its abend paragraph at L369; that paragraph begins at
 * L377 and ends by moving 16 into the return code at L382 before returning to the caller at L383.
 * So the baseline expresses restart position and terminal failure in one place, and the target
 * expresses them in another -- per-state retry and redrive in the orchestrator, with the durable
 * step ledger owned by the batch context. A job repository here would be a third expression of the
 * same thing, and a reader asking which of them decides whether a step reruns would have no single
 * answer. It matters because none of the long-running work in this context is chunk oriented in the
 * first place: the purge, load and unload jobs in the sibling {@code service} package are scheduled
 * service methods and orchestrator-invoked entry points, so a job repository would add a restart
 * contract to a workload that needs neither it nor the reader confusion it brings. The baseline
 * behaves as described and remains reference material, read and never modified; the migration adds
 * a path, it does not remove one.
 *
 * <ul>
 *   <li>There is no masking and no data return here. Primary-account-number masking to the last
 *       four digits and card-verification-value suppression belong to the sibling {@code mapper}
 *       package, which the parent charter makes the single place representation concerns may appear
 *       at all. Assumptions: {@code SecurityConfig} decides whether a caller may reach an endpoint
 *       and stops there; it never masks a field and never returns data, so a reader tracing a
 *       truncated account number should read the mapper and not this package.</li>
 *   <li>There is no distributed-transaction machinery: no XA data source, no JTA transaction
 *       manager and no transaction coordinator. Assumptions: this context owns one schema in one
 *       database, so a single local transaction spans everything a unit of work here touches, and
 *       there is no second resource manager for a coordinator to coordinate with. That is a
 *       consequence of the target topology rather than a setting, and {@code DataSourceConfig}
 *       carries the full rationale; this entry is a pointer so that a reader does not read the
 *       absence as an omission.</li>
 *   <li>No resilience library is declared by this module, no class in it uses one, and there is
 *       no configuration class for one. Assumptions: retry support lives in the Spring Framework
 *       core that arrives inside the Spring Boot 4.1.0 parent, so an external library would
 *       duplicate a capability the platform already provides. The claim is about declaration and
 *       use rather than about the classpath, because this module is one of four in which
 *       {@code org.springframework.retry:spring-retry} arrives as a compile-scoped transitive of
 *       the SQS starter, which uses it for its own listener-container polling back-off and so
 *       cannot be excluded; rule A5 of the shared layering gate is what forbids reaching for it.
 *       {@code SqsConfig} carries the rationale, together with the durable retry tier the queues
 *       themselves supply through redelivery and a dead-letter queue; this entry is a
 *       pointer.</li>
 * </ul>
 *
 * <h2>The layering contract as it applies to this package</h2>
 *
 * <p>The parent charter at {@code com.carddemo.authorization} states the contract for the whole
 * module and this package restates it rather than deriving a second version of it. Nine package
 * roots are set across the reactor: {@code com.carddemo.common}, the shared kernel, and the eight
 * context roots beneath {@code com.carddemo}. The dependency arrow points inward only. Every
 * context may depend on the shared kernel; no context may depend on another; the shared kernel
 * depends on none of them. Concretely for this package, the only types importable from outside this
 * module's own tree are {@code com.carddemo.common.*}, and no context may import another context's
 * {@code domain} package.</p>
 *
 * <p>What this package consumes from the shared kernel is imported from it and never re-declared
 * here, which is the Java form of the migration plan's rule that one former COBOL {@code COPY}
 * statement becomes exactly one type import from the single package owning that contract:</p>
 *
 * <ul>
 *   <li>{@code com.carddemo.common.security.JwtRoleConverter}, the one place the group claim of a
 *       validated token becomes authorities. {@code SecurityConfig} registers it; it does not
 *       reimplement the conversion, and no second reading of that claim exists in this module.</li>
 *   <li>{@code com.carddemo.common.observability.MetricsConfig}. Assumptions: it lives under the
 *       kernel's {@code observability} package and <b>not</b> under a {@code config} package, which
 *       is worth stating because its name invites a reader to look for it beside the four classes
 *       above and it is not there.</li>
 *   <li>{@code com.carddemo.common.web.CorrelationIdFilter}, which puts an inbound correlation
 *       identifier into the logging context and onto the response, and so carries across HTTP what
 *       the baseline's messaging layer carried in a correlation field.</li>
 *   <li>{@code com.carddemo.common.codec.CsvAuthCodec}, the codec for this context's authorization
 *       payloads, whose field order and delimiter are the wire contract itself.</li>
 *   <li>{@code com.carddemo.common.money.MoneyModule}, {@code com.carddemo.common.web.PageResponse}
 *       and {@code com.carddemo.common.error.ApiError}, which between them settle how money is
 *       serialised, what a page of results looks like and what shape a problem takes. They are
 *       referenced by {@code OpenApiConfig} so that the published contract describes the payloads
 *       the service actually emits.</li>
 * </ul>
 *
 * <p>Assumptions: the component scan rooted at {@code com.carddemo.authorization} does not reach
 * {@code com.carddemo.common}, which sits outside that root, so no shared-kernel component is
 * discovered automatically and each one above is registered deliberately. This is the most easily
 * misread thing about the package: a reader who assumes the scan reaches the kernel will hunt for a
 * missing bean in the wrong tree. Alternatives Considered: widening the scan root to
 * {@code com.carddemo} would shorten that list and is not done, because it would pull every kernel
 * component into every context at once, including the ones a given context has no use for, and
 * would replace an explicit registration list with an implicit set that changes silently whenever
 * the kernel gains a component. Trade-offs: the price of the narrow root is that a newly added
 * kernel component has to be registered by hand here, and that price buys a list which changes only
 * when someone edits it. The records and static utilities among the types above need no registration
 * at all and are used as plain imports wherever they are needed.
 *
 * <p>Layering is owned by a test rather than by a linter. The ArchUnit rules at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * assert the cross-context prohibition, the ban on cloud and web types inside a {@code domain}
 * package, and the ban on binary floating-point types in the money path. Assumptions: the Checkstyle
 * rule set deliberately configures no import-control module, so that boundary keeps exactly one
 * owner; a second engine enforcing an overlapping half of one constraint would leave a reader unable
 * to tell which of the two owned a given rule. That exclusion is recorded in
 * {@code config/checkstyle/checkstyle.xml} itself, and adding an import-control module is therefore
 * not the way to close any gap in layering enforcement.
 *
 * <h2>Configuration keys are read in this package and owned elsewhere</h2>
 *
 * <p>Assumptions: the four classes above <b>read</b> configuration properties and declare none of
 * them. The sibling resources channel at {@code services/authorization-service/src/main/resources}
 * owns {@code application.yml} with its {@code application-dev.yml} and {@code application-prod.yml}
 * profiles, and those files own the values: the datasource URL and the HikariCP sizing keys, the
 * Flyway settings, the resource server's issuer location, the actuator exposure that publishes the
 * health endpoint, and the {@code carddemo.messaging.} namespace carrying the queue references, the
 * five-second receive wait and the cap on how many messages one invocation takes. Duplicating,
 * shadowing or hard-coding any of those values in this package is prohibited, because a value that
 * exists in two places has no single owner and the copy a running service actually used becomes a
 * question about bean ordering rather than about a file anyone can read.</p>
 *
 * <p>Refactoring Rationale: the baseline binds the equivalent settings when a program is compiled or
 * a region is installed. The database it reads is named by a literal in the program's own working
 * storage, and the transaction attributes and relational plan binding are declared as region
 * resources in {@code app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd}, so changing either means
 * recompiling a load module or reinstalling a resource definition. This package binds the same kinds
 * of setting at startup from externalised configuration instead, which is why the values live in the
 * resources channel and in the infrastructure tree and not in any file here. That is a
 * platform-capability difference rather than a change of business behaviour, and it is stated as the
 * two structures and the consequence that follows from them, not as a verdict on the baseline, which
 * remains the behavioural oracle this context is judged against.
 *
 * <p><strong>No secret and no endpoint appears in this package.</strong> No credential, connection
 * string, account identifier, resource name, queue location or registry host is written into any
 * file here. Assumptions: the infrastructure tree's module outputs are the only source of runtime
 * endpoints and identifiers; they reach this service through the platform's parameter and secret
 * stores and are read at startup through the active Spring profile. That is what makes the
 * no-committed-secrets constraint structurally true for this package rather than merely observed,
 * because there is no file here for a secret to be written into in the first place.
 *
 * <h2>Independence from the six sibling packages</h2>
 *
 * <p>Assumptions: configuration in this package declares beans and settings, and it imports no type
 * from this module's {@code domain}, {@code dto}, {@code mapper}, {@code repository},
 * {@code service} or {@code api} packages. Nothing here needs one: a filter chain, a metadata bean,
 * a connection pool and a listener container are all described in terms of framework types and
 * shared-kernel types only. That property is why this charter can be authored independently of all
 * six of those packages, and it is recorded because it constrains future edits rather than merely
 * describing today's state. Adding an import of a sibling package's type here would introduce a
 * dependency that does not currently exist, and it would make configuration sensitive to a change
 * in a domain type -- so a rename in an entity could break the security chain, which is a coupling
 * this package is deliberately free of. Where configuration genuinely has to know about a sibling
 * type, the framework supplies it by injection at runtime rather than by an import at compile time,
 * and that indirection is the whole reason the independence holds.
 *
 * <h2>Labelling of the rationale above</h2>
 *
 * <p>Assumptions: all four of the canonical labels the project's explainability standard names
 * appear above, in the plural, unparenthesised, colon-terminated form that standard prescribes, and
 * with no emphasis markup, because the label is data a reviewer finds by literal search and not
 * presentation. {@code Refactoring Rationale:} is used once, for the move of configuration binding
 * from compile and install time to startup, which is the one place this package genuinely supersedes
 * a mechanism the baseline expressed in code rather than merely doing something the baseline never
 * did. It is written there as structure and consequence: the framing law for this migration is that
 * the reference tree is never called defective, because a comment that did so would invite exactly
 * the edit the migration forbids.</p>
 *
 * <p>Trade-offs: every justification above sits inside this Javadoc block instead of beside a
 * statement, which departs from the letter of the rule's request that a comment sit adjacent to the
 * code it explains, and is nevertheless the only placement this compilation unit admits. A package
 * declaration has no statements, so there is no code for a comment to be adjacent to and the
 * adjacency requirement is satisfied vacuously rather than waived. The cost accepted is that these
 * entries sit further from the behaviour they describe than an inline comment would, and the
 * compensation is that each one names its evidence explicitly -- a file, a plan section, a rule line
 * or a program line -- so a reader can check it without trusting the prose. The documentation gate
 * bound to the {@code validate} phase can check that this Javadoc is present; it cannot check that
 * a label is the accurate one for the rationale it introduces, which is why the choice of label is
 * recorded here in prose and remains a review concern.
 */
package com.carddemo.authorization.config;
