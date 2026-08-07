/**
 * Spring wiring for the transaction-service module, covering stateless request
 * security, published API metadata and datasource binding for the ledger
 * bounded context.
 *
 * <h2>Target contract, and the tree state at the checkpoint that authored it</h2>
 *
 * <p>Assumptions: every class name and inventory in this charter describes the
 * package's <b>target contract</b> as the migration plan assigns it, not the
 * set of files present beside this one. This charter is authored ahead of the
 * classes it governs, so at the checkpoint that authored it the directory holds
 * this charter and nothing else. A type named below that has no file yet is
 * therefore <b>planned</b>, not missing.</p>
 *
 * <p>Alternatives Considered: withholding this charter until the three classes
 * it governs exist. Rejected, because this charter is what the author of each
 * of those classes works from -- which type belongs here, which may not, and
 * what the closed set is -- so holding it back would leave the package with no
 * stated contract during exactly the interval in which one is needed. The cost
 * accepted is that the inventory reads as present tense unless the distinction
 * is declared, which is what the paragraph above is for.</p>
 *
 * <p><b>Purpose.</b> This package binds external concerns to the service while
 * the application context is being built, and it holds nothing else. Every
 * class in it configures a framework: none carries a business rule, none
 * reaches a database, none answers an HTTP request and none declares a record
 * layout. The validation chains transcribed from the baseline COBOL paragraphs
 * live in {@code service}, the keyset queries that replace the CICS browse live
 * in {@code repository}, and the fixed-width and masking concerns live in
 * {@code mapper}, so a reader asking why a transaction was refused, or why an
 * account number arrives truncated on a response, will not find the answer
 * here.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package
 * declaration accepts no parameter, returns no value and raises nothing, so
 * this charter carries no parameter, return or exception at-clause. The
 * inapplicability is declared rather than left silent, because the
 * Explainability rule forbids at its line 39 a docstring that omits parameters,
 * return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Exactly one of the four docstring elements
 * the rule enumerates at its lines 18 to 21 has a subject in this compilation
 * unit, and this paragraph accounts for the other three.
 *
 * <p>Assumptions: inventing those at-clauses would not merely add noise.
 * Javadoc has no parameter, return or exception concept for a package, and the
 * repository ruleset audits at-clause bodies for emptiness through its
 * {@code NonEmptyAtclauseDescription} check, so a fabricated at-clause would
 * either be discarded by the tool or reported as empty by it.
 *
 * <h2>The layering contract this package inherits</h2>
 *
 * <p>The charter one directory up, at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/package-info.java},
 * assigns a layer to each of this module's packages and states this one in
 * three words: {@code config} wires Spring. That contract is referenced here
 * rather than restated, because writing out the role of every sibling package a
 * second time would give one contract two statements that can drift apart, and
 * the parent charter is the file a reader of any sibling package opens anyway.
 *
 * <h2>The three configuration classes</h2>
 *
 * <p>Target contract: this package is to hold exactly three classes, each with
 * a narrow and separately testable responsibility. The enumeration below is the
 * closed set assigned to this package rather than a listing of the directory.
 *
 * <ul>
 *   <li>{@code SecurityConfig} builds the resource server filter chain that
 *       validates a signed Cognito token on every business request, permits only
 *       {@code /actuator/health} without one, and keeps every other actuator
 *       endpoint behind authentication. Trade-offs: the load balancer target
 *       group and the container health check poll that endpoint before any
 *       credential exists, so requiring a token there would fail every probe and
 *       remove a healthy task from service; the accepted cost is one read-only
 *       health route reachable without a credential, and it exposes neither
 *       business data nor the wider actuator surface. Alternatives Considered:
 *       permitting all of {@code /actuator/**} was rejected because the probes
 *       need only health status, while the other actuator endpoints can disclose
 *       operational detail to a caller with no identity. {@code SecurityConfig}
 *       reads the issuer from
 *       {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, which the
 *       module configuration resolves from an injected environment variable
 *       rather than from a committed value, and it registers
 *       {@code com.carddemo.common.security.JwtRoleConverter} to turn the
 *       {@code cognito:groups} claim carried by that token into Spring Security
 *       authorities. It also places
 *       {@code com.carddemo.common.web.CorrelationIdFilter} ahead of the
 *       authentication filter. Assumptions: that ordering is part of the
 *       filter's contract and not a preference, because a request rejected
 *       during authentication has to appear in the log under the same
 *       correlation identifier as one that succeeds. Registering the filter
 *       after authentication would leave every rejection uncorrelated, which is
 *       the case an operator most often needs to trace.</li>
 *   <li>{@code OpenApiConfig} supplies the OpenAPI 3.1 metadata this service
 *       publishes, aligned to the hand-authored contract committed at
 *       {@code services/transaction-service/src/main/resources/openapi/transaction-api.yaml}.
 *       It also contributes the {@code com.carddemo.common.money.MoneyModule}
 *       Jackson bean, and it is where
 *       {@code com.carddemo.common.error.GlobalExceptionHandler} and
 *       {@code com.carddemo.common.observability.MetricsConfig} are made
 *       visible to this module's application context. Assumptions: the
 *       committed contract is a published interface with a real consumer rather
 *       than developer convenience, because the browser client at
 *       {@code ui/src/api/transactions.ts} is written against that file, so the
 *       metadata declared here and the committed document are expected to agree
 *       rather than drift, and {@code ui/src/api/contracts.test.ts} fails the SPA
 *       build when they do not. Assumptions: the money module belongs
 *       beside that metadata because both concern how this service represents
 *       itself on the wire -- one describes the shape of a payload and the
 *       other fixes how a monetary amount is written into it -- so a change to
 *       either is read against the same committed contract.</li>
 *   <li>{@code DataSourceConfig} sizes the HikariCP pool and pins the JDBC
 *       {@code search_path} to the {@code ledger} schema, both read from
 *       configuration rather than written into code: the pool is described
 *       under {@code spring.datasource.hikari}, and the pinning is carried by
 *       {@code spring.datasource.hikari.connection-init-sql}, which the module
 *       configuration sets to {@code SET search_path TO ledger}. Assumptions:
 *       {@code ledger} is the only schema this context owns, so pinning the
 *       search path is what keeps an unqualified table name in a query
 *       resolving inside this context's own schema and nowhere else. That
 *       matters here in particular because the baseline reached other contexts'
 *       records directly: one CICS region shared one file set, so
 *       {@code app/cbl/COBIL00C.cbl} names the account master at line 41, reads
 *       it at lines 345 and 346 and rewrites it at lines 379 and 380, and
 *       {@code app/cbl/COTRN02C.cbl} names the account master and the card
 *       cross-reference at lines 40 to 42. Bill payment therefore writes across
 *       what is now a context boundary. A shared file handle has no counterpart
 *       on this side, so that write becomes a call to the owning context, and
 *       pinning every pooled connection makes the boundary a property of the
 *       connection rather than a naming habit.</li>
 * </ul>
 *
 * <p>Alternatives Considered: collecting the shared-kernel wiring into a fourth
 * class of its own, named for the framework it configures -- a Jackson
 * configuration for the money module, or a single holder for the shared beans.
 * Rejected. The parent charter names the contents of this package as exactly
 * three classes, and every one of the module shapes in this migration
 * enumerates the same three, so a fourth class here would make this module the
 * only one of the eight whose configuration package does not match the shape
 * its own charter declares. The divergence would be invisible in this module
 * and visible only to someone comparing two of them. The cost accepted is that
 * two responsibilities sit in {@code OpenApiConfig} rather than one, which is
 * why the paragraph above states what the two have in common instead of leaving
 * the pairing unexplained.
 *
 * <h2>How the shared kernel reaches this context</h2>
 *
 * <p>This is the most easily misread thing about this package, so it is stated
 * outright. Assumptions: the component scan implied by the annotation on this
 * module's application class is rooted at {@code com.carddemo.transaction},
 * while every shared-kernel type lives under {@code com.carddemo.common}, which
 * sits outside that root. Shared components are therefore never discovered
 * automatically, and each one that must become a bean is registered
 * deliberately by a class in this package. A reader who assumes the scan
 * reaches the shared kernel will look for a missing bean in the wrong tree
 * entirely.
 *
 * <p>Alternatives Considered: widening the scan root to {@code com.carddemo} to
 * make those registrations unnecessary. Rejected, because it would pull every
 * shared-kernel component into every service at once, including the ones a
 * given context has no use for, and would replace a short list of explicit
 * registrations that can be read in this package with an implicit set that
 * changes silently whenever the shared kernel gains a component. Trade-offs:
 * the price of the narrow root is that a newly added shared component has to be
 * registered by hand here. That price buys a registration list which changes
 * only when someone edits it.
 *
 * <p>The rest of the shared kernel needs no registration at all. Its records
 * and static utilities -- among them {@code ApiError}, {@code AbendDetail},
 * {@code PageResponse}, {@code TimestampFormatter} and the codec and validation
 * helpers -- are used as plain imports wherever they are needed, which is why
 * they appear in no list here.
 *
 * <h2>Deliberately absent from this package</h2>
 *
 * <p>Alternatives Considered: each item below is something a reader might
 * reasonably expect to find in a service configuration package. Each was
 * weighed and left out, and each is named with its own specific reason, so that
 * an absence is not mistaken for an oversight and filled in.
 *
 * <ul>
 *   <li>There is no {@code SqsConfig}. This context neither publishes nor
 *       consumes a message, and the build agrees rather than merely permitting
 *       the claim: {@code services/transaction-service/pom.xml} declares no
 *       {@code io.awspring.cloud} artifact and no
 *       {@code software.amazon.awssdk} artifact, and the module configuration
 *       defines no {@code spring.cloud.aws.*} key, so the types a listener
 *       container would be built from are not on this classpath and would not
 *       resolve. Assumptions: peer account-service does declare those starters,
 *       because it owns the inquiry listener that consumes a queue, so its
 *       configuration looks different on purpose and is not a template for this
 *       one. Messaging configuration is scoped to the two contexts that
 *       exchange messages.</li>
 *   <li>There is no {@code BatchConfig}. None of the four programs migrated
 *       into this context is a scheduled job, {@code spring-boot-starter-batch}
 *       is absent from this module's classpath and the module configuration
 *       defines no {@code spring.batch.*} key, so there is no job repository to
 *       declare. Assumptions: a batch starter added here would create job
 *       repository tables that the {@code ledger} schema does not own, which is
 *       a concrete failure rather than surplus configuration. The nightly jobs
 *       that sweep these tables belong to batch-service, and the two modules
 *       meet through the physical schema rather than through code.</li>
 *   <li>There is no {@code RestClientConfig}. The one synchronous hop this
 *       context makes -- the cross-context read of the card cross-reference
 *       owned by account-service, which the baseline satisfied from the shared
 *       file set named at {@code app/cbl/COTRN02C.cbl} lines 40 to 42 -- is
 *       issued by the service layer, and the client that carries its connect
 *       and read timeouts is built there. Alternatives Considered: promoting
 *       that client to a bean here was rejected because a single shared client
 *       would then carry one timeout budget for every caller, and the timeout
 *       is a property of the call being made rather than of the module making
 *       it; the accepted cost is that the value is set at the call site instead
 *       of in one place.</li>
 *   <li>There is no {@code logback-spring.xml} and no logging configuration
 *       class. Assumptions: the observability contribution this module makes is
 *       the metric tag set, and it arrives through
 *       {@code com.carddemo.common.observability.MetricsConfig}, which lives
 *       under a package named for its concern and not under any package named
 *       {@code config}. Filing it by Spring stereotype instead of by concern is
 *       the predictable wrong turn, because this module does have a
 *       {@code config} package and that package holds three unrelated classes;
 *       the parent charter records the same warning, and it is repeated here
 *       because this is the directory someone hunting for the class opens.
 *       Correlation identifiers reach the log through the filter
 *       {@code SecurityConfig} registers, so the two halves of request-scoped
 *       observability are registered in two places on purpose.</li>
 *   <li>There is no {@code module-info.java}, here or anywhere beneath this
 *       module. Assumptions: none exists in this repository at all, so adding
 *       one would introduce a module path to a build that resolves everything
 *       from the class path, and the shared kernel would then have to export
 *       each of its packages explicitly for eight consumers.</li>
 *   <li>There is no module-local Checkstyle configuration. Assumptions: the
 *       ruleset addresses its companion suppression file through
 *       {@code ${config_loc}}, which resolves to the directory holding the
 *       ruleset, and {@code services/pom.xml} supplies that value for the whole
 *       reactor. A local copy would fork that resolution, so this module could
 *       come to enforce something different from its eight siblings while every
 *       build still reported success.</li>
 *   <li>There is no {@code .gitignore} beneath this module. Assumptions: none
 *       is needed, because the repository-root file leaves its {@code target/}
 *       rule unanchored at line 92 and records at lines 88 to 91 that the rule
 *       is written that way precisely because every {@code target/} appears
 *       nested under {@code services/} and never at the repository root. A
 *       nested file here would restate a rule that already covers this
 *       directory.</li>
 * </ul>
 *
 * <h2>Import discipline</h2>
 *
 * <p>The classes in this package import Spring types, springdoc types and
 * {@code com.carddemo.common} types, and nothing else. In particular none of
 * them imports {@code api}, {@code service}, {@code repository},
 * {@code domain}, {@code dto} or {@code mapper}, from this module or from any
 * other. That is what leaves this package with no sibling dependency, and it is
 * why this charter and the three classes it governs can be written without
 * waiting on the rest of the module.
 *
 * <p>Trade-offs: declining to import a sibling package is a real restriction
 * rather than a description of what happens to be true, because wiring a bean
 * by naming its concrete type is ordinary practice and would work. It is
 * declined because a configuration class that reaches into {@code repository}
 * or {@code service} inverts the dependency this layering states: the layers
 * that hold behaviour would then be pinned by the layer that configures the
 * framework, and a change in either would have to be read against the other.
 * The cost accepted is that anything this package needs from a sibling has to
 * arrive as a framework-managed dependency rather than as a direct reference.
 *
 * <p>Refactoring Rationale: shared types are consumed from
 * {@code com.carddemo.common} and are never re-declared in this module. The
 * baseline compiles every program against a single copybook include path, so a
 * layout has one definition and cannot drift between two programs, and the
 * repository imposes that same discipline on its own COBOL tests at
 * {@code tests/README.md} lines 540 to 542, which resolve record layouts
 * through {@code cobc -I app/cpy} and never duplicate one. A second local copy
 * of a shared type would reintroduce exactly the drift that include path
 * forecloses, and the drift would be silent, because both copies would go on
 * compiling.
 *
 * <p>Assumptions: the layering test that will enforce part of this is
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * and what it does and does not reach is stated precisely here rather than
 * summarised, because overstating mechanical coverage would leave a reviewer
 * checking nothing while believing a tool had. Its domain-isolation rule is
 * scoped to classes in a {@code domain} package, so it does not constrain
 * {@code com.carddemo.transaction.config} at all, and this package may freely
 * import Spring web, servlet and security types. Its money-path rule is scoped
 * to {@code com.carddemo.common.money} and below, so the numeric-representation
 * discipline that governs an amount handled in this package is a review
 * obligation and not a mechanical one. The rule class is assigned to this
 * module by the migration plan and has no file yet -- this module's own POM
 * declares the ArchUnit engine at test scope so that the class is executable
 * when it lands, because the shared kernel binds no test-jar goal and so
 * publishes no test classes to inherit -- which means the import discipline
 * stated above is currently carried entirely by review.
 *
 * <h2>No exemption from the documentation gate reaches this package</h2>
 *
 * <p>Assumptions: the three classes assigned to this package cannot be exempted
 * from the Javadoc gate, and the two routes to exempting them are closed
 * independently of each other. The ruleset declines to set
 * {@code skipAnnotations}, even though the upstream documentation's own example
 * for {@code MissingJavadocType} sets it to the Spring Boot application and
 * configuration annotations, so a class in this package earns nothing from
 * carrying {@code @Configuration}. The companion suppression file separately
 * names a service application class and a configuration package among the
 * entries it forbids outright, so the same exemption cannot be obtained from
 * the suppressions side either.
 *
 * <p>Trade-offs: recording a refusal costs a paragraph that describes nothing
 * the code does, and it is written anyway because the temptation is specific
 * and documented. Copying that upstream example is the single most likely way
 * this gate gets switched off for precisely the module entry points and
 * configuration types the Explainability rule names at its line 15, and an
 * exemption nobody wrote down is indistinguishable from a genuine pass in a
 * build log. There is also no in-code escape to fall back on: the ruleset
 * enables none of the three comment-driven or annotation-driven suppression
 * filters, so neither a marker comment nor an annotation suppresses anything,
 * and every exemption has to be a durable entry in one reviewable file. The
 * repository already holds this position elsewhere, keeping its golden-master
 * update switch multiply guarded at {@code tests/README.md} lines 528 and 529
 * so that it cannot silently bless a regression.
 *
 * <p>Assumptions: a green documentation gate is necessary and NOT sufficient
 * for compliance, so a class in this package is not finished when the build
 * stops reporting violations. The rule's validation gate is conjunctive -- it
 * fails work missing either the docstring or the recorded rationale -- while
 * the ruleset mechanises only the docstring half, and the ruleset's own header
 * says so: it cannot judge whether a comment explains why rather than what,
 * cannot detect a comment that merely restates the code beside it, cannot
 * verify that one of the four categories was actually named or was written in
 * the canonical form, and cannot identify which implementation choices are
 * non-obvious. That second half is carried by the written convention and by
 * review, which is where the rule itself puts it. Reading a passing build as
 * compliance is the misreading this paragraph exists to prevent.
 *
 * <h2>Comment form for the classes this charter governs</h2>
 *
 * <p>Assumptions: an inline comment in a class in this package carries a
 * rationale and nothing else -- one of the four canonical labels the
 * Explainability rule names at its lines 31 to 34, its colon, the reason, and
 * what would differ under the alternative -- placed
 * immediately above the code it explains. Purpose belongs in the Javadoc, which
 * is where the language puts it. The twin form that pairs a line stating what
 * the code does with a line stating why belongs to fenced command blocks in
 * prose, where the repository uses it throughout {@code tests/README.md}, and
 * it is not used on a statement in a Java source. Alternatives Considered:
 * adopting that twin form here for symmetry with the prose was evaluated and
 * rejected, because a line above a statement saying what the statement does
 * restates it, which is the pattern the Explainability rule forbids at its line
 * 38, and because the written convention at
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} scopes the twin form to prose
 * command blocks and forbids a statement-level what-line in a Java source
 * outright. The parent charter records the same conclusion, so this paragraph
 * aligns this package with it rather than deciding the question again.
 *
 * <h2>Labelling of the rationale above</h2>
 *
 * <p>Assumptions: all four of the labels the Explainability rule names at its
 * lines 31 to 34 appear above, in the plural, unparenthesised, colon-terminated
 * form the rule words them in and with no emphasis markup, because the label is
 * read by a literal string search before it is read by a person and one
 * spelling is what makes that search complete. {@code Refactoring Rationale:}
 * is used once and only where it is accurate, and the restraint is worth
 * recording: that label is reserved for genuinely replaced code, and this
 * package replaces none, since the COBOL baseline it is derived from is read
 * and never modified and no earlier Java configuration existed here to
 * supersede. Its one use above is the single-sourcing of shared types, where
 * the approach it displaces -- re-declaring a shared type locally -- is a real
 * practice rather than a hypothetical one. Everywhere else that a choice here
 * differs from the baseline, {@code Alternatives Considered:} is the accurate
 * label and is the one used.
 *
 * <p>Trade-offs: this file is pure ASCII. The alternative was to reproduce the
 * typographic punctuation the migration prose uses, which would read closer to
 * that prose. ASCII was chosen because the house convention this charter cites
 * is written at {@code tests/README.md} lines 542 and 548 with a non-breaking
 * hyphen, and a non-breaking hyphen is indistinguishable from an ordinary one
 * on screen while behaving differently in a search, so copying a label from
 * there would turn {@code Trade-offs:} into a token that a search for the label
 * fails to find. Restricting the whole file to ASCII makes that failure mode
 * unreachable, at the cost of plainer punctuation, and the build declares UTF-8
 * for both the source encoding and the documentation gate's charset, so ASCII
 * is a strict subset of what is configured.
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else -- no type, no annotation, no import and no line comment. It
 * carries no authorship, version or release-marker at-clause either, because
 * the ruleset omits the whole Javadoc-formatting family that would ask for
 * them, and the version control history answers those three questions more
 * reliably than a comment maintained by hand.
 *
 * <p>Trade-offs: prose is wrapped at 80 columns to match the sibling charters
 * in this tree, even though the ruleset configures no line-length check and so
 * does not require it. Exactly four lines exceed that width deliberately and
 * should be left as they are. Three are inline code spans holding a path, which
 * cannot be broken because a line break inside one would insert the comment
 * margin into the rendered path. The fourth is the opening section heading,
 * kept character-for-character identical to the heading the sibling charters
 * use so that the three read as one convention; rewording it to save two
 * columns would buy a narrower file at the cost of the only thing that heading
 * is for.
 */
package com.carddemo.transaction.config;
