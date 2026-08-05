/**
 * Spring configuration for the Reporting and Statement bounded context of the migrated
 * CardDemo system.
 *
 * <h2>What this package holds</h2>
 *
 * <p>This package carries the Spring wiring for the reporting context and nothing else:
 * the stateless JWT security chain, the OpenAPI 3.1 document metadata, the read-only
 * datasource with its schema search path, and the AWS Step Functions client through which
 * an on-demand report execution is started. The context root charter one package up, in
 * {@code com.carddemo.reporting}, states the same four concerns for this package at its
 * L125 to L127, and the agreement between the two files is deliberate rather than
 * incidental: a reader who finds them disagreeing has found a defect in one of them.
 *
 * <p><strong>The roster is closed at four classes.</strong> Each owns one concern, and no
 * fifth configuration class is expected here:
 * <ul>
 *   <li>{@code SecurityConfig} -- the Cognito JWT resource server. It wires
 *       {@code com.carddemo.common.security.JwtRoleConverter} so that the
 *       {@code cognito:groups} claim becomes Spring Security authorities, applies
 *       group-claim authorization to this context's routes, and registers
 *       {@code com.carddemo.common.web.CorrelationIdFilter}.</li>
 *   <li>{@code DataSourceConfig} -- the database role holding {@code SELECT} and nothing
 *       else, the schema search path that role reads across, and the connection pool
 *       sizing.</li>
 *   <li>{@code OpenApiConfig} -- the OpenAPI 3.1 document metadata consumed by springdoc,
 *       whose starter is the dependency declared at this module's {@code pom.xml} L234.</li>
 *   <li>{@code StepFunctionsConfig} -- the client through which a
 *       {@code states:StartExecution} call starts a report execution. The AWS SDK Step
 *       Functions artifact backing it is declared at this module's {@code pom.xml}
 *       L280.</li>
 * </ul>
 *
 * <p>Alternatives Considered: folding these four concerns into the context root package,
 * or into one combined configuration class, rather than giving them a package of their
 * own. Rejected on the strength of the module's own dependency set, which shows the four
 * concerns arriving from four unrelated directions: the resource server from the security
 * and OAuth2 starters at {@code pom.xml} L185 and L190, the datasource from the JPA
 * starter and the driver at L153 and L220, the document metadata from springdoc at L234,
 * and the execution client from the AWS SDK at L280. One class holding all four would take
 * a change for any one of them and would make the blast radius of that change the whole
 * set; four classes in one package keep each concern separately readable and separately
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
 * tree walker, at {@code checkstyle.xml} L378, and requires that file to carry Javadoc. An
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
 * L730, with {@code failOnViolation} true at L819 and {@code violationSeverity} at warning
 * at L820, and it audits test sources too by way of L847. Because {@code validate}
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
 * {@code pom.xml} records the omission in prose at its L465 to L474 rather than leaving it
 * to be inferred. The substantive reason is the direction of control. This context starts
 * a state machine execution and returns; the step ledger that records which runs and which
 * steps completed belongs to batch-service, which owns it. A second job repository here
 * would be a second restart mechanism over the same runs, and two ledgers disagreeing
 * about whether a step finished is a worse position to be in than having exactly one.
 *
 * <p>Alternatives Considered: an {@code SqsConfig} class here, modelling the report request
 * as a queued message. Rejected, and again no queue starter is on the classpath, with this
 * module's {@code pom.xml} recording the reasoning at its L476 to L484. The baseline
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
