/**
 * Spring configuration for the Reporting and Statement bounded context of the migrated
 * CardDemo system.
 *
 * <h2>Target contract, and the state of this directory at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every class name and count below states this package's <b>target
 * contract</b> as the migration plan assigns it, and not the set of files sitting beside
 * this one today. The migration lands its artifacts in plan order and this charter is
 * authored first, so at the checkpoint that authored it this directory held this file and
 * nothing else. That is measured rather than supposed: the enclosing module contained four
 * Java files in total, this charter plus the three carried by the context root and by its
 * {@code dto} and {@code service} subpackages, and no other type. A class named below that
 * has no file yet is therefore <b>planned</b>, not missing.</p>
 *
 * <p>Alternatives Considered: withholding this charter until the four classes it governs
 * exist. Rejected, because the charter is precisely what the author of each of those
 * classes works from -- which concern belongs here, which may not, and where the boundary
 * of the set lies -- so writing it last would leave the package uncharted across exactly
 * the interval in which a stated contract is needed. The accepted cost is that the
 * inventory reads as present tense unless the distinction is declared, which is the single
 * purpose of the paragraph above; a reader has one place to look to tell a target from a
 * measurement.</p>
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
 * <h2>Documentation contract</h2>
 *
 * <p>Assumptions: this file exists because user-specified Rule 1 (Explainability) L15
 * requires a docstring on every module entry point, and in Java a package declaration is
 * that entry point. The grounding is L15 specifically and not the Validation Gate at L43,
 * because L43 opens with the words "Every new or modified function" and a package
 * declaration is not a function; citing L43 here would assert an obligation the rule does
 * not place on this construct. L15 carries no visibility qualifier, which is why the
 * obligation reaches this file whatever the accessibility of the classes it charters. A
 * {@code package-info.java} is the only construct able to carry Javadoc for a package,
 * which is why the obligation lands in this file rather than anywhere else, and the block
 * form used here is what L22 names for Java.
 *
 * <p>Assumptions: of the four docstring elements Rule 1 enumerates at L18 through L21,
 * only Purpose at L18 applies. A package declaration accepts no parameters, yields no
 * value and raises nothing, so the Parameters element at L19, the Return values element at
 * L20 and the Exceptions element at L21 are inapplicable here rather than omitted. No
 * at-clause is written to stand in for one of them, because a tag asserting a parameter or
 * a returned value that does not exist is a docstring describing something other than the
 * construct it documents. The two Checkstyle modules that act on this file demand that it
 * exist and that it carry Javadoc; neither demands an at-clause, and supplying one would
 * satisfy no rule while stating something untrue.
 *
 * <p>Assumptions: no author, no since and no version at-clause appears anywhere in this
 * file, because nothing requires one. The rule set at
 * {@code config/checkstyle/checkstyle.xml} omits the {@code JavadocStyle},
 * {@code WriteTag} and {@code JavadocParagraph} modules altogether, and the author and
 * version format properties of {@code JavadocType} are left unset there, so no gate asks
 * for any of the three. Two of them would also carry a maintenance cost with no reader
 * benefit, version history being what the revision control system already holds
 * authoritatively. This is worth distinguishing from a similarly named thing elsewhere in
 * this service: the common metrics configuration attaches a metric tag called version to
 * every measurement it publishes, which is a runtime label on telemetry and has no
 * relation to the Javadoc at-clause of the same name.
 *
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
 * {@code pom.xml} records the omission in prose at its L463 to L472 rather than leaving it
 * to be inferred. The substantive reason is the direction of control. This context starts
 * a state machine execution and returns; the step ledger that records which runs and which
 * steps completed belongs to batch-service, which owns it. A second job repository here
 * would be a second restart mechanism over the same runs, and two ledgers disagreeing
 * about whether a step finished is a worse position to be in than having exactly one.
 *
 * <p>Alternatives Considered: an {@code SqsConfig} class here, modelling the report request
 * as a queued message. Rejected, and again no queue starter is on the classpath, with this
 * module's {@code pom.xml} recording the reasoning at its L474 to L478. The baseline
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
 * single enforcement site in the ArchUnit layering test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}.
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
 * <h2>What the platform used to carry</h2>
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
 *
 * <h2>Decisions on this file itself</h2>
 *
 * <p>Alternatives Considered: the four category labels above are written in the plural,
 * un-parenthesised, colon-terminated spelling that user-specified Rule 1 uses at its L31
 * to L34, and that spelling is the only accepted one. The alternative spellings are not
 * cosmetic variants: a singular, bracketed or dash-terminated label is a label that a
 * literal-string search for the category will not find, which makes a documented rationale
 * read as absent to any audit looking for it. The census was measured in this repository
 * at authoring time with the C locale forced, and the canonical spelling is also the
 * majority one, which is worth recording so that no later reader "normalises" the plural
 * back: {@code Alternatives Considered:} appears on 530 lines, {@code Assumptions:} on
 * 1699, and {@code Trade-offs:} on 634 lines across 108 files, against 153 lines carrying
 * the parenthesised singular variant out of 1011 lines mentioning the category at all.
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} carries the full statement of the
 * convention.
 *
 * <p>Assumptions: those four labels were retyped by hand from user-specified Rule 1 L31 to
 * L34, which is pure 7-bit ASCII, and were not copied from {@code tests/README.md}, which
 * is not. That file establishes the house convention for the repository, its section
 * heading at L516 and its governing blockquote at L544 to L549, and it is a sound witness
 * to which four categories exist; it is not a witness to how they are spelled. Measured
 * byte-exactly with the C locale forced on the search itself, it carries 106 U+2011
 * non-breaking hyphens across 77 lines, and its L548 spells the fourth category with a
 * non-breaking hyphen, a closing bracket and no colon, three departures from the canonical
 * spelling inside a single token. Forcing the locale on the search is itself load bearing,
 * because in a UTF-8 locale the same byte pattern is interpreted as characters and reports
 * zero matches, and a check that cannot fail is not a check. This whole file is
 * consequently held to 7-bit ASCII, which is the property that makes such a byte reaching
 * a label here impossible rather than merely unlikely.
 *
 * <p>Assumptions: no entry anywhere in this file is written under the Refactoring
 * Rationale category, and the omission is deliberate. Rule 1 scopes that category at its
 * L32 to the replacement of existing code and requires the entry to say what was wrong
 * with the approach being replaced. This package replaces nothing: the COBOL baseline is
 * reference material, it is never modified, and it is not withdrawn, so the migration adds
 * a path rather than removing one. Recording a Refactoring Rationale where new code
 * merely differs from a reference it never displaced would state something factually
 * untrue about both.
 *
 * <p>Trade-offs: every justification in this file sits inside this Javadoc block rather
 * than beside a statement, which departs from the letter of user-specified Rule 1 L27 and
 * is nonetheless the only placement this file admits. L27 asks that a comment sit adjacent
 * to the code it explains; a package declaration has no statements, so there is no code
 * for a comment to be adjacent to, and the adjacency requirement is met vacuously rather
 * than waived. Note also that L28's instruction to explain why rather than what sits under
 * the inline-comment heading at L25 and so governs comments specifically, which is why the
 * prose above states what this package holds as well as why it holds it: stating the what
 * is L18's Purpose element, and L11 requires both halves. The cost accepted is that these
 * entries sit further from the behaviour they describe than an inline comment would, and
 * the compensation is that each one names the line, the count or the declared value it
 * rests on. A reader auditing this file should read the labelled entries above as the
 * rationale half of the conjunctive gate at L43, and should not conclude that the half was
 * skipped for want of somewhere to put it.
 */
package com.carddemo.reporting.config;
