/**
 * Spring configuration for the Reporting and Statement bounded context of the migrated
 * CardDemo system.
 *
 * <h2>What this package holds</h2>
 *
 * <p>This package carries the Spring wiring for the reporting context and nothing else:
 * the stateless JWT security chain and its decoder, the OpenAPI 3.1 document metadata, the
 * read-only datasource with its schema search path, the AWS Step Functions client through which
 * an on-demand report execution is started, the object-store client the artifact writers publish
 * through, and the keyed tokeniser an artifact object key is derived through. The context root
 * charter one package up, in {@code com.carddemo.reporting}, states the same concerns for this
 * package in its package charter list, and the agreement between the two files is deliberate rather
 * than incidental: a reader who finds them disagreeing has found a defect in one of them. The
 * cross-reference is by file rather than by line, because a line number in another file is the part
 * of a cross-reference that rots first.
 *
 * <h2>The roster, measured against this directory</h2>
 *
 * <pre>
 * this directory: 8 java files = 7 classes + 1 charter
 * </pre>
 *
 * <p>Refactoring Rationale: this section has now been corrected three times, and the marker line
 * above is what ends the sequence. It first recorded {@code OpenApiConfig} and
 * {@code StepFunctionsConfig} as planned and not yet authored; both landed. It then closed the roster
 * at five classes and stated that no sixth was expected; two more landed --
 * {@code ObjectStoreConfig}, without which nothing in the module could publish an artifact at all,
 * and {@code ArtifactIdentityConfig}, which keys the tokeniser an object key is derived through. A
 * closed-set claim that outlives the directory is the worst of both readings: a reader auditing the
 * roster concludes a landed and load-bearing file is surplus, and a reader auditing the directory
 * concludes the charter cannot be trusted. The marker line is the form {@code common-lib}'s
 * {@code PackageCharterInventoryTest} re-measures against this directory on every build, and the same
 * test requires each member enumerated below to be a file here.</p>
 *
 * <p><strong>The roster is seven classes, each owning one concern.</strong> A configuration concern
 * that is not one of these seven does not belong here:
 * <ul>
 *   <li>{@code JwtDecoderConfig} -- LANDED. The token decoder. Assumptions: it is a member of this
 *       roster in its own right rather than an implementation detail of the filter chain, because
 *       what it does is not optional: one Cognito
 *       user pool issues tokens for every app client registered against it and mints two
 *       token kinds per sign-in, so signature, issuer, audience and time checks alone
 *       accept an identity token and a token minted for an unrelated client. This class
 *       composes the token-kind, minting-client and scope checks into the decision itself.
 *       Alternatives Considered: folding it into {@code SecurityConfig}. Rejected because
 *       neither check is expressible as a property in any release of this framework, so
 *       the code has to live somewhere, and putting it in the filter-chain class would mix
 *       the question "is this credential acceptable" into the class that answers "what may
 *       this credential reach" -- two decisions with different reasons to change.</li>
 *   <li>{@code SecurityConfig} -- LANDED. The Cognito JWT resource server. It wires
 *       {@code com.carddemo.common.security.JwtRoleConverter} so that the
 *       {@code cognito:groups} claim becomes Spring Security authorities, applies
 *       group-claim authorization to this context's routes, and registers
 *       {@code com.carddemo.common.web.CorrelationIdFilter}.</li>
 *   <li>{@code DataSourceConfig} -- LANDED. The database role holding {@code SELECT} and nothing
 *       else, the schema search path that role reads across, and the connection pool
 *       sizing.</li>
 *   <li>{@code OpenApiConfig} -- LANDED. The OpenAPI 3.1 document metadata consumed by
 *       springdoc, whose starter is the dependency declared at this module's {@code pom.xml}
 *       L225. It contributes exactly three members to the served document -- the information
 *       block, one bearer scheme and the document-level requirement naming that scheme -- and
 *       no path, operation, schema or response. Assumptions: that emptiness is deliberate and
 *       is not an unfinished edge. The contract of record is the hand-authored
 *       {@code src/main/resources/openapi/reporting-api.yaml}, which the browser client is
 *       written against, and the served document is a CHECK on it rather than a second source
 *       of truth. The two are held together by
 *       {@code com.carddemo.reporting.api.ReportingApiContractTest}, which compares the
 *       published operation set, the declared authorities and the declared response headers
 *       against the handlers and the filter chain.</li>
 *   <li>{@code StepFunctionsConfig} -- LANDED. The client through which a
 *       {@code states:StartExecution} call starts a report execution and a
 *       {@code states:DescribeExecution} call reads what became of one. The AWS SDK Step
 *       Functions artifact backing it is declared at this module's {@code pom.xml} L271.
 *       Assumptions: BOTH actions are named because they take different resources -- the start
 *       names the state machine, the describe names that machine's execution ARNs -- so a task
 *       role granted only the first serves submissions and fails every status read.
 *       Assumptions: it declares a call timeout and nothing else -- no region, no credential
 *       provider and no endpoint override appear in source, because all three are resolved
 *       from the task environment the deployment supplies, and hard-coding any of them would
 *       make one environment's value compiled into every environment's image.</li>
 *   <li>{@code ObjectStoreConfig} -- LANDED. The object-store client the artifact writers publish
 *       through and the request edge reads back through. This context declares an output bucket and two
 *       key prefixes in its configuration, so without this bean nothing in the module could reach them
 *       and the orchestrated {@code GenerateStatements} and {@code GenerateReports} states would run to
 *       completion having stored nothing. Assumptions: the read half is named as well as the write
 *       half, because {@code com.carddemo.reporting.service.ArtifactStore} issues {@code HeadObject}
 *       and both whole-object and ranged {@code GetObject} through this same bean -- all three
 *       authorized by {@code s3:GetObject} -- so a task role granted writes alone reports every
 *       produced artifact as absent. Assumptions: no region, credential provider or endpoint is named
 *       here, for the same reason the execution client names none.</li>
 *   <li>{@code ArtifactIdentityConfig} -- LANDED. The keyed tokeniser an artifact object key is derived
 *       through. Assumptions: it is configuration rather than a service because an object key is not a
 *       private thing -- the store writes it to its own access log, indexes it for listing and reports
 *       it in a bucket inventory, none of which a content-encryption key reaches -- so the tokeniser
 *       has to be in place before any writer runs, and it is what keeps an account identifier and every
 *       part of a card number out of a key.</li>
 *   <li>{@code ObjectStoreConfig} -- the object-store client the two artifact writers publish
 *       through and the artifact read side answers from. Assumptions: it exists because this context
 *       declares an output bucket and two key prefixes in its configuration and had no client bean to
 *       reach them with, so the orchestrated statement and report states would have run this image and
 *       stored nothing. Like the Step Functions client it declares no region, credential provider or
 *       endpoint override in source, for the same reason.</li>
 *   <li>{@code ArtifactIdentityConfig} -- the keyed tokeniser that names a stored statement artifact
 *       without naming its cardholder. Assumptions: an object key is not a private thing -- the store
 *       writes it into its own access log for every request that touches the object -- so the key is
 *       derived through a secret-keyed token rather than from the account or card it belongs to. The
 *       key arrives as a required property with no default, and the bean refuses to build without it,
 *       because an unkeyed digest of a short structured input can be confirmed by enumeration.</li>
 * </ul>
 *
 * <p>Alternatives Considered: folding these concerns into the context root package,
 * or into one combined configuration class, rather than giving them a package of their
 * own. Rejected on the strength of the module's own dependency set, which shows the
 * concerns arriving from unrelated directions: the resource server from the security
 * and OAuth2 starters at {@code pom.xml} L176 and L190, the datasource from the JPA
 * starter and the driver at L153 and L220, the document metadata from springdoc at L234,
 * and the execution client from the AWS SDK at L280. One class holding every concern would take
 * a change for any one of them and would make the blast radius of that change the whole
 * set; separate classes in one package keep each concern separately readable and separately
 * testable while still grouping them where a reader looks for wiring. Keeping them in the
 * context root package was rejected for a second and sharper reason: that package is the
 * entry point, and mixing wiring into it removes the one location that can be read as a
 * pure statement of the context's charter.
 *
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point,
 * and in Java the entry point of a package is its package declaration, which only
 * {@code package-info.java} can carry -- so this file is load-bearing rather than decorative. Two
 * Checkstyle modules enforce that independently and neither is redundant: {@code JavadocPackage}
 * inspects the file set and requires this file to exist in any directory holding an audited source
 * file, while {@code MissingJavadocPackage} inspects the parsed tree and requires it to carry Javadoc.
 * A charter reduced to a bare package statement satisfies the first and fails the second, which is
 * why prose is the deliverable and the file's mere existence is not.
 *
 * <p>Assumptions: this compilation unit holds one statement, so the rationale the rule's
 * inline-comment half asks for has no adjacent executable line to sit beside and is carried inside
 * this block under the four canonical labels -- the only placement a package makes available. No
 * parameter, return or exception at-clause appears, because a package declaration accepts no
 * argument, yields no value and raises nothing, and {@code NonEmptyAtclauseDescription} would report
 * an invented tag with an empty body; omitting them is therefore the compliant reading of the rule
 * rather than a departure from it. The written convention every block here follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.
 * <h2>Build interlock</h2>
 *
 * <p>Two Checkstyle modules act on this file and they are not redundant.
 * {@code JavadocPackage} sits at Checker level, because it inspects the file system rather
 * than a parsed tree, and it requires a {@code package-info.java} to be present in every
 * directory holding a processed Java file. {@code MissingJavadocPackage} sits inside the
 * tree walker, at {@code checkstyle.xml} L371, and requires that file to carry Javadoc. An
 * empty {@code package-info.java} satisfies the first and fails the second, so both are
 * needed to express the actual requirement, and the rule set says so in its own words at
 * L236 to L243 and again at L369 to L376. A third module reaches the prose written here:
 * {@code SummaryJavadoc} at L505 requires a summary sentence, and it leaves its sentence
 * terminator at the default, which the rule set records at L497 to L503 as meaning that a
 * period not followed by whitespace does not end a sentence. Every summary in this file
 * therefore closes with a period followed by a line break, which matters here because the
 * charter is dense with dotted names whose internal periods the module does not see.
 *
 * <p>Assumptions: the gate is inherited and is never configured from this subtree. The
 * {@code maven-checkstyle-plugin} execution named {@code checkstyle-documentation-gate} is
 * declared once, in {@code services/pom.xml}, bound to the {@code validate} phase at its
 * L721, with {@code failOnViolation} true at L810 and {@code violationSeverity} at warning
 * at L811, and it audits test sources too by way of L838. Because {@code validate}
 * precedes compilation, a violation here stops the build before the compiler runs, on a
 * local build and inside the container image build alike. No suppression filter of any
 * kind is wired into the rule set, so a finding in this file cannot be waived from inside
 * a source file by a comment or by an annotation; the suppressions companion reaches
 * generated sources and test fixtures only, and it states at its L263 that hand-written
 * production sources are never suppressed. The exit status of this build is binary. The
 * graded condition-code rubric under which the COBOL parity suite treats a warning-level
 * aggregate as its own green state belongs to that suite alone and is never carried into a
 * Maven, Checkstyle, Surefire or Failsafe gate on this side.
 *
 * <h2>Wired here, owned elsewhere</h2>
 *
 * <p>Assumptions: the shared kernel types this package wires all live under
 * {@code com.carddemo.common} and are used from there rather than re-declared here, so
 * this package depends on four external contracts it does not control.
 * {@code com.carddemo.common.security.JwtRoleConverter} carries no stereotype annotation
 * of any kind, which means component scanning will never discover it and
 * {@code SecurityConfig} has to wire it explicitly; a reader who expects scanning to pick
 * it up will find no bean and no error message explaining the absence.
 * {@code com.carddemo.common.web.CorrelationIdFilter} is registered by the consuming
 * service for the same underlying reason, namely that the shared kernel ships no
 * application class and no configuration resource of its own and therefore cannot register
 * anything on its own behalf. {@code com.carddemo.common.error.GlobalExceptionHandler},
 * the common metrics configuration and the common money serialisation module all sit
 * outside the {@code com.carddemo.reporting} scan root, so they are brought in one package
 * up by the context root application class rather than here; this package must not
 * duplicate those registrations and must not widen the scan root to reach them, because
 * two registrations of one handler is an ambiguity the container reports late and
 * obscurely. Finally {@code com.carddemo.common.time.TimestampFormatter} is registered by
 * nobody at all: it is a static utility, neither imported as a configuration class nor
 * exposed as a bean nor injected, and its absence from this package is correct.
 *
 * <h2>Deliberately absent</h2>
 *
 * <p>Nothing is being removed and nothing is deprecated; the following simply never appear
 * in this package, each for a stated reason.
 *
 * <p>Alternatives Considered: a {@code BatchConfig} class here, giving this context its own
 * batch job repository. Rejected, and the module's dependency set already reflects the
 * decision: no batch starter is on this module's classpath, and this module's
 * {@code pom.xml} records the omission in prose at its L456 to L465 rather than leaving it
 * to be inferred. The substantive reason is the direction of control. This context starts
 * a state machine execution and returns; the step ledger that records which runs and which
 * steps completed belongs to batch-service, which owns it. A second job repository here
 * would be a second restart mechanism over the same runs, and two ledgers disagreeing
 * about whether a step finished is a worse position to be in than having exactly one.
 *
 * <p>Alternatives Considered: an {@code SqsConfig} class here, modelling the report request
 * as a queued message. Rejected, and again no queue starter is on the classpath, with this
 * module's {@code pom.xml} recording the reasoning at its L467 to L475. The baseline
 * submission this context encodes was not a request and reply exchange: the queue defined
 * at {@code app/csd/CARDDEMO.CSD} L499 to L505 is declared {@code TYPEFILE(OUTPUT)} at
 * L502, write-only with no reply queue anywhere in the definition, so modelling it as an
 * exchange would add a correlation and reply path that the contract being encoded never
 * had. This context consumes none of the migrated queues. An empty configuration class
 * would be worse than no class, because its presence implies a wiring decision that has
 * not in fact been taken.
 *
 * <p>Trade-offs: no bean of the JSON object mapper type is declared in this package, nor
 * any builder or builder-customiser for one, and the constraint is absolute rather than
 * stylistic. Declaring any of the three causes Spring Boot to back off its own JSON
 * auto-configuration, at which point the shared money serialisation module is never
 * registered and every money field silently serialises as a JSON number instead of a
 * string. Most clients parse a JSON number into an IEEE-754 binary floating point value,
 * so the exactness the rest of the chain preserves would be surrendered at the one hop a
 * user actually reads, and transformation rule T3 is breached. The reason this is recorded
 * as a constraint rather than left to care is the failure signature: such a build compiles,
 * the service starts, and every test that does not inspect raw JSON still passes. The
 * compromise accepted is that any future need to customise JSON handling in this context
 * has to be met by contributing a module to the shared kernel instead of by declaring a
 * mapper here, which is less direct and is the price of keeping one serialisation
 * authority for the whole reactor.
 *
 * <p>Also absent, with shorter reasons: request handling, which belongs to the {@code api}
 * package; business behaviour, which belongs to {@code service}; persistence types, which
 * belong to {@code domain} and {@code repository}; and transport shapes, which belong to
 * {@code dto} and {@code mapper}. No {@code module-info.java}, this build being classpath
 * based rather than modular. No {@code package.html}, superseded by the file you are
 * reading. No Lombok configuration, no module-local copy of the rule set or its
 * suppressions companion, and no import-control module, the package boundary having a
 * single enforcement site: the {@code architecture-rules} Surefire execution declared in
 * {@code services/pom.xml}, which scans the shared kernel's test artifact into every module and
 * selects the layering rules by the simple name {@code LayeringRulesTest}.
 *
 * <p>Alternatives Considered: giving this context a schema of its own, with tables, indexes
 * and a Flyway migration directory under this module, so that its datasource configuration
 * would look like every other service's. Rejected because this context reads and never
 * writes, and every store it reads is already owned elsewhere. It owns no table, no index,
 * no view, no constraint, no grant and no migration artifact, and a
 * {@code db/migration} directory appearing under this module would itself be a defect
 * rather than an addition. What {@code DataSourceConfig} configures is therefore a role
 * holding {@code SELECT} and nothing else, reading through cross-schema views that other
 * contexts' migrations create. Copying those stores locally to obtain a private schema
 * would create a second truth for figures whose entire purpose is to restate the first one
 * exactly, and a report disagreeing with the ledger it reports on is a support case rather
 * than a feature.
 *
 * <h2>Legacy platform contract</h2>
 *
 * <p>The CICS resource definitions at {@code app/csd/CARDDEMO.CSD}, 505 lines and
 * reference material that is never modified, expressed at the platform level several of the
 * concerns this package now expresses in code. Data access was declared per file, the
 * first such stanza beginning at L88, each naming its own dataset and each carrying
 * {@code JOURNAL(NO)} and {@code RECOVERY(NONE)}, at L94 and L96 in that first stanza; the
 * datasource configured here reaches an encrypted, backed-up store instead, and the
 * divergence is a deliberate one recorded in the migration's divergence register.
 * Executable code was reached through a load library, {@code LIBRARY(CARDDLIB)} defined at
 * L489 and enabled at L490, whose dataset is named at L491; note that a second library
 * definition beginning at L494 names the same dataset at L496 but is
 * {@code STATUS(DISABLED)}, so L491 is the enabled one and a citation of L496 would point
 * at the inactive twin. Report submission was a write to an extra-partition transient data
 * queue, {@code TDQUEUE(JOBS)} defined across L499 to L505, described at L500 as
 * submitting jobs from CICS, declared {@code DDNAME(INREADER)} with
 * {@code ERROROPTION(IGNORE)} at L501 and {@code RECORDSIZE(80)} at L502. The online
 * program this context encodes writes to that queue at {@code app/cbl/CORPT00C.cbl} L517
 * and L518, reached through transaction {@code CR00} wired to it at {@code CARDDEMO.CSD}
 * L409 and L410; the program states its own function at its L5 and L6 as printing
 * transaction reports by submitting a batch job from online through that queue. Here the
 * same request becomes the {@code StartExecution} call that {@code StepFunctionsConfig}
 * provides the client for, which is why an AWS SDK Step Functions artifact sits on this
 * module's classpath and no queue starter does. That design was a complete and functioning
 * operational model for its platform; this package encodes the same contract on a
 * different one.
 */
package com.carddemo.reporting.config;
