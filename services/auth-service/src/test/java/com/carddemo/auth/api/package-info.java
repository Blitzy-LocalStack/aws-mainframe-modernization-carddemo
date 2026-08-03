/**
 * Web-layer slice tests for the two REST adapters of the auth bounded context.
 *
 * <p>Two classes execute here and nothing else does. {@code AuthControllerTest} is the slice test
 * for {@code com.carddemo.auth.api.AuthController}, whose behavioural specification is the 260-line
 * sign-on program at {@code app/cbl/COSGN00C.cbl}. {@code UserControllerTest} is the slice test for
 * {@code com.carddemo.auth.api.UserController}, whose specification is four programs rather than
 * one: {@code app/cbl/COUSR00C.cbl} at 695 lines, {@code app/cbl/COUSR01C.cbl} at 299,
 * {@code app/cbl/COUSR02C.cbl} at 414 and {@code app/cbl/COUSR03C.cbl} at 359. Both are built with
 * {@code @WebMvcTest}, so the web layer stands up in isolation and every collaborator beneath it is
 * supplied as a mock.</p>
 *
 * <h2>The adapter charter, and the line it draws</h2>
 *
 * <p>The {@code api} package owns exactly three concerns: transport validation, HTTP status mapping
 * and delegation. It owns no business rule and reaches no store. That boundary is what fixes the
 * assertion set in this package to six subjects and no seventh, namely HTTP status, response-body
 * shape, verbatim message text, per-field error keys, authority enforcement and statelessness.</p>
 *
 * <p>The corollary is the more useful half, because it is what stops a reader hunting here for an
 * assertion that was never meant to be here. The transcribed validation chains, the per-program
 * validation orders read as behaviour, the dirty-check semantics of the update path and the
 * identity-exchange failure mapping are all asserted in the sibling
 * {@code com.carddemo.auth.service} package against a mocked repository. A rule asserted in neither
 * place would be genuinely untested; a rule asserted once, there, is correctly placed, and
 * asserting it twice would give a later change two places to update and one place to forget.</p>
 *
 * <h2>Two transport contracts, both derived rather than invented</h2>
 *
 * <p>Statelessness is the first, and it is a decomposition rather than a deletion. The reference
 * session structure at {@code app/cpy/COCOM01Y.cpy} opens with {@code 01 CARDDEMO-COMMAREA.} on
 * line 19 and carries four navigation fields on lines 21 through 24, an eight-character user
 * identifier on line 25, a one-character user type on line 26 whose only two condition names are
 * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'.} on line 27 and {@code 88 CDEMO-USRTYP-USER VALUE 'U'.}
 * on line 28, and a re-entry discriminator on lines 29 through 31. The sign-on program detects first
 * entry by testing {@code IF EIBCALEN = 0} on line 80 and ends every turn by returning that whole
 * structure to the terminal on lines 98 through 102, which is precisely what makes the client the
 * holder of the continuation. Three of those groups survive into the target and one does not:
 * navigation becomes client-side routing, identity becomes a validated token claim, selection
 * becomes a request path, and the re-entry discriminator has no counterpart at all, because a
 * stateless handler has no earlier turn to remember.</p>
 *
 * <p>The assertions that follow are negative ones, which is why they have to be written on purpose
 * or they are simply absent: no response carries a next-program field, no handler depends on a
 * prior call having happened, and the user type is read from the token rather than from anything the
 * caller supplied. That last one is a security property rather than a tidiness one. The reference
 * structure is storage the client echoes back, so a client could in principle assert its own type,
 * whereas a signed claim is not something a client can assert. The reference branch it replaces is
 * visible on lines 230 through 239 of the sign-on program, where a single test of the admin
 * condition name selects between two program transfers.</p>
 *
 * <p>Per-field error reporting is the second, and here the target is strictly wider than the
 * reference rather than equivalent to it. {@code app/cbl/COUSR01C.cbl} validates its input inside a
 * single {@code EVALUATE TRUE} spanning lines 117 to 151, holding five blank-field branches at
 * lines 118, 124, 130, 136 and 142 and a catch-all at line 148. Because it is one
 * {@code EVALUATE}, it short-circuits: the first failing field wins, one message reaches the screen
 * and the remaining fields are never examined. The highlight accompanying that message is templated
 * at {@code app/cpy/CSSETATY.cpy} lines 17 to 27, where lines 18 and 19 test the not-OK and blank
 * flags for a field, line 20 additionally requires the re-entry condition, lines 21 and 22 move the
 * red attribute into the field, and lines 23 to 25 place a literal asterisk in it when it is
 * blank.</p>
 *
 * <p>The target answers with a structured per-field error array instead, so one response can name
 * every failing field at once. Two consequences follow, and both are placement decisions rather
 * than coverage decisions. This package asserts the error keys, because which field an error
 * attaches to is a transport contract. The order in which the reference program would have
 * discovered those fields is behaviour, and it is asserted in the sibling service package. And the
 * re-entry condition on line 20 has no target counterpart, because the flag it tests is the
 * discriminator that the paragraph above shows disappearing, so error presentation here is driven
 * purely by the response body.</p>
 *
 * <p>Message text is asserted character for character, and the reason is a width discrepancy a
 * reader would otherwise try to reconcile. The sign-on program declares its message work field as
 * {@code 05 WS-MESSAGE PIC X(80) VALUE SPACES.} on line 38, while the two shared constants it moves
 * into that field on lines 89 and 93 are each declared {@code PIC X(50)}, at
 * {@code app/cpy/CSMSG01Y.cpy} lines 18 and 20. The declared widths disagree by thirty characters
 * for one and the same message. Asserting a width would therefore encode whichever of the two
 * declarations a test author happened to read first, whereas asserting the text encodes what a user
 * actually sees, and that is the contract being preserved.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Assumptions: these are slice tests, so the web layer is instantiated alone and the service
 * layer beneath it arrives as a mock. Nothing in this package starts a database, starts a container,
 * applies a schema migration or reaches a managed identity pool, and no assertion here may be
 * written as though one of those were available. The consequence is a division of labour rather
 * than a gap: repository behaviour and the schema constraints this service owns, among them the
 * two-value domain expressed as {@code CHECK (user_type IN ('A','U'))} in the migration
 * {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql}, are verified by
 * {@code UserRepositoryIT} in the sibling {@code com.carddemo.auth.repository} test package, which
 * runs under Failsafe against a real container. That two-value domain is not introduced by the
 * migration either. It is the reference declaration already cited above, at
 * {@code app/cpy/COCOM01Y.cpy} lines 26 to 28, which is why the claim does not rest on the
 * migration alone.</p>
 *
 * <p>Alternatives Considered: a full {@code @SpringBootTest} was evaluated for this package and
 * rejected. It boots the persistence layer and runs the schema migration in order to reach a
 * controller, so every assertion about an HTTP status code, a response-body shape or a message
 * string would acquire a database-container dependency it has no use for, and the package would
 * stop running altogether on any host unable to start one. Serialisation and routing are the
 * concerns actually under test here, and coupling them to schema availability makes an adapter
 * regression and an infrastructure outage indistinguishable in the report. That specific confusion
 * is the damage being avoided, not a general preference for smaller contexts.</p>
 *
 * <p>Trade-offs: mocking the service layer means this package cannot observe that a controller and
 * its collaborator agree on anything beyond the signature the mock was written against. The cost is
 * accepted, and it is accepted at a known location rather than an unknown one: a drift between the
 * adapter and the service surfaces at compile time or in the sibling service package, and never as
 * a silently passing assertion here.</p>
 *
 * <h2>The explainability obligation has no test carve-out</h2>
 *
 * <p>Two authorities establish that, and citing only one would leave the point arguable. The project
 * Explainability rule states its validation gate at line 43: every new or modified function must
 * have a docstring giving purpose, parameters and return values, every non-obvious implementation
 * decision must have an inline comment explaining why that approach was chosen using at least one
 * of the rule's four named categories, and code missing either fails review. The rule scopes that
 * to functions, classes and module entry points without qualification, and a test class is a class.
 * {@code tests/README.md} reaches the same conclusion from the other direction, for test code
 * specifically: its line 544 names "Every new test, fixture builder, helper, mock, and runner
 * routine", and its line 549 closes the requirement with "This is a hard review gate."</p>
 *
 * <p>What that means here is a checklist rather than a posture. Every class, every {@code @Test}
 * method and every {@code private} helper in the two classes of this package carries Javadoc; a
 * param tag appears for every parameter, for every component of every {@code record} and for every
 * type parameter; a return tag appears on every method returning a value; and a throws tag appears
 * for every exception a signature declares. A nested {@code record}, should either class declare
 * one, is a type in its own right and needs its own block with one param tag per component.</p>
 *
 * <h2>Three places where the machine is looser than the rule, and one where it is not</h2>
 *
 * <p>The first is overriding methods. {@code MissingJavadocMethod} leaves its allowed-annotations
 * list at the default of {@code Override}, so the audit tolerates a wholly missing Javadoc block on
 * an overriding method. The rule does not, at either line 15 or line 43. An inheritDoc tag on its
 * own satisfies none of the rule's lines 18 through 21 either, since it supplies no purpose, no
 * parameter description and no return description of its own.</p>
 *
 * <p>The second is private members. {@code MissingJavadocMethod} runs at package scope, and
 * Checkstyle orders its scopes public, then protected, then package, then private, so a
 * package-level scope cannot reach a {@code private} method at all. Its sibling
 * {@code JavadocMethod} does list private among its access modifiers, and the rule's lines 15 and
 * 43 carry no visibility qualifier, so a private helper here is documented in full. The trap is the
 * half-measure specifically: a private helper given a summary but no param tags is worse placed
 * than one given nothing at all, because the completeness module can see it and will then report
 * every tag it lacks.</p>
 *
 * <p>The third runs the other way, and is recorded so that nobody gold-plates it.
 * {@code JavadocVariable} is deliberately absent from the audit, so a field -- a {@code MockMvc}, an
 * {@code ObjectMapper}, a mocked collaborator bean -- needs no Javadoc block. The rule agrees,
 * because it scopes its docstring requirement to functions, classes and module entry points, and a
 * field is none of the three. A field that genuinely needs explaining takes an adjacent inline
 * comment under the rule's line 27, which is the placement the rule asks for in any case.</p>
 *
 * <p>The one place the machine is not the looser of the two is annotations. The skipped-annotations
 * list stays at its default, which exempts generated code and nothing else, so {@code @WebMvcTest},
 * {@code @Test}, {@code @MockitoBean} and {@code @Import} confer no exemption whatsoever on the
 * types and methods they decorate.</p>
 *
 * <p>Trade-offs: the audit governing this file cannot check the half of the rule that matters most,
 * and that compromise is stated rather than hidden. The forbidden-summary-fragment pattern on
 * {@code SummaryJavadoc} reads Javadoc summaries only and never reads an inline comment, so the
 * rule's line 27 on adjacency, line 28 on explaining why rather than what, line 38 on not restating
 * the code beside a comment and line 40 on not leaving a non-obvious choice undocumented are not
 * mechanically checkable at any severity. A build can therefore complete this gate while carrying
 * restate-the-code prose throughout, and that code still fails review under line 43. A green gate
 * is evidence about docstring presence and completeness and about nothing else; the reasoning half
 * is carried by review and by {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <h2>There is no executable oracle for this context</h2>
 *
 * <p>Every expected value in this package is derived by reading the reference source and its
 * symbolic maps, not by running something and capturing what came out. That is a property of the
 * repository rather than a preference exercised here. {@code tests/README.md} scopes its
 * golden-master tier on line 29 to the daily batch chain and to nothing else, and it records on
 * lines 83 and 84 that "Online CO* CICS programs cannot run end-to-end without a CICS runtime
 * (absent on the runner)". A census over all 590 lines of that file for the sign-on program, the
 * four user programs and the security file returns nothing at all, and broadening the census to the
 * whole existing suite returns nothing either: not one of them is named anywhere in it. There is
 * therefore no captured output for this context to be compared against, and no test here may imply
 * that there is one.</p>
 *
 * <p>Two disciplines follow. Every expected value cites the line that produced it, so that a
 * reviewer can re-derive the value instead of trusting it, and that line is confirmed by reading
 * the file, because a remembered line number is exactly the sort of claim that decays quietly.
 * And where the target does not reproduce the reference, the framing stays factual: the baseline
 * does one thing, the Java encodes another, and the divergence is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The reference source is never
 * described here as having been repaired, since it is not modified at all. The same file states on
 * lines 35 and 36 that its own tests encode the documented rules and do not redefine them, and this
 * package holds itself to that verb.</p>
 *
 * <h2>This gate is binary</h2>
 *
 * <p>Maven, Checkstyle, Surefire and JUnit each report pass or fail, with no tolerated middle band.
 * The documentation gate is bound to the {@code validate} phase with a fail-on-violation flag and a
 * violation threshold of warning, so it stops the build before compilation on a developer's own
 * machine and not only in a pipeline. The graded return-code rubric that the existing suite follows
 * -- five codes, aggregated to the worst code seen, whose usage code is listed last and out of
 * numeric sequence because it aborts immediately and never enters aggregation -- is documented in
 * section 8 of {@code tests/README.md} and governs that suite alone. It has no application to this
 * module, and a build here is never reported as having passed with tolerated warnings, because no
 * such state exists for it: the violation threshold is warning, so a warning is a failure.</p>
 *
 * <h2>Precedence when the three authorities disagree</h2>
 *
 * <p>The Explainability rule binds first, {@code config/checkstyle/checkstyle.xml} second and
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} third. The ordering only ever matters in one
 * direction, because the audit is nowhere stricter than the rule. Wherever the machine is the more
 * lenient of the two, as at each of the three gaps above, the rule is what gets satisfied and the
 * leniency is left untaken.</p>
 *
 * <h2>Contents of this package</h2>
 *
 * <p>Three files and no fourth: {@code AuthControllerTest.java}, {@code UserControllerTest.java}
 * and this descriptor. There is deliberately no abstract base class, no suite aggregator, no
 * separate fixture builder and no test-scoped configuration class. Two test classes need no shared
 * scaffolding, and standing any of it up would move one class's setup into a second file, leaving a
 * reader two places to look and no way to tell which one applied.</p>
 *
 * <p>The auth service's declared test inventory is four classes in total: the two here, plus
 * {@code UserServiceTest} and {@code UserRepositoryIT} in their own sibling packages. Neither
 * {@code com} nor {@code com.carddemo} carries a descriptor of this kind, in this module or in the
 * shared library, and that omission is deliberate rather than overlooked. Each of those directories
 * contains no processed {@code .java} file, so {@code JavadocPackage} has nothing to fire on there
 * and the rule's line 15 has no entry point to attach to; adding one would document a package that
 * declares nothing.</p>
 *
 * <h2>Why this descriptor exists</h2>
 *
 * <p>The rule requires a docstring on every module entry point at its line 15, and in Java the
 * entry point of a package is the package declaration. {@code package-info.java} is the only
 * compilation unit able to carry package-level Javadoc, so this file is load-bearing rather than
 * decorative. Two audit modules enforce that requirement from opposite sides, and neither is
 * redundant. {@code JavadocPackage} runs at the file-set level and asserts only that this file
 * exists beside the audited sources in its directory; it never reads the contents, so on its own it
 * would pass a file holding a bare package statement or an ordinary block comment.
 * {@code MissingJavadocPackage} runs inside the syntax-tree walker and asserts that the declaration
 * carries a Javadoc block, so on its own it would never notice the file missing. Remove either and
 * the requirement is half-enforced. The gate is additionally configured to audit test sources
 * alongside main sources, which is why this directory falls inside it exactly as a main-tree
 * package does, and why this file is not optional here.</p>
 *
 * <p>One structural consequence is worth stating plainly, because it explains an omission that
 * would otherwise resemble the very thing the rule forbids. This compilation unit contains a single
 * statement, so the decision rationale the validation gate asks for has no adjacent executable code
 * to sit beside; it is carried inside this block, under the rule's own category labels, which is
 * the only placement a package makes available. The parameter, return-value and exception elements
 * of the rule's docstring specification describe callable code, and a package declaration is not
 * callable: it takes no argument, yields no value and raises nothing. Those elements are therefore
 * omitted deliberately rather than written out empty, and that is not a stylistic preference. The
 * completeness module auditing this build reports an at-clause carrying no description as a
 * violation in its own right, so an empty tag would fail the very gate it was added to satisfy.</p>
 */
package com.carddemo.auth.api;
