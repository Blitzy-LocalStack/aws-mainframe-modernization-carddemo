/**
 * Charter for the anti-corruption-boundary tests of the pending credit-card authorization context.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every class here is a PURE unit test of the mapping edge. Each one constructs its subject directly,
 * reads at most a committed byte image from this module's test resources, and asserts on the value that
 * comes back. Nothing in this package starts a Spring application context and nothing reaches a database:
 * there is no full-context test, no persistence slice, no web slice and no container anywhere in this
 * directory. A test that needs one of those is in the wrong package rather than in need of a new
 * dependency, and the parent charter at {@code com/carddemo/authorization/package-info.java} says where
 * each belongs -- orchestration and transaction boundaries in {@code .service}, the single persistence
 * integration test in {@code .fixtures}, and status selection and body binding at the HTTP edge in
 * {@code .api}.
 *
 * <p>Refactoring Rationale: the planned division of labour sent database constraint and index assertions
 * to a sibling {@code .repository} TEST package. No such package exists and none is to be created. The
 * production tree does have a {@code .repository} package, which is what makes the mistake easy, but the
 * one integration test that exercises persistence is
 * {@code .fixtures.PendingAuthFraudDomainRepositoryIT}, placed with the fixture tests that supply its
 * rows. The parent charter records the same asymmetry and its reason. Naming a package that is not there
 * would send a reader looking for it and would invite them to create it, splitting one integration test's
 * concerns across two directories.
 *
 * <p>This package is FOUNDATIONAL to the rest of this module's test tree. The record widths, the field
 * offsets, the numeric regimes, the one spelling correction and the masking rules are all established
 * here, and {@code .service}, {@code .api}, {@code .dto} and {@code .domain} take them as given rather
 * than re-deriving them. An error here therefore does not fail here; it propagates as a plausible wrong
 * value into every package that trusts this one.
 *
 * <h2>What executes here</h2>
 *
 * <p>Seven test classes occupy this package as this charter is written, and together they cover the four
 * responsibilities the parent charter assigns to it -- segment conversion, the wire fixtures, the date
 * pivot, and what a mapper is permitted to expose:
 *
 * <ul>
 *   <li>{@code SegmentConversionContractTest} -- every public conversion of the two segment mappers,
 *       held against the committed binary images. This is the class that pins the two declared widths:
 *       the thirteen components of {@code cpy/CIPAUSMY.cpy} L19 to L31 sum to exactly 100, and
 *       {@code cpy/CIPAUDTY.cpy} L19 to L54 to exactly 200. A width read wrongly does not fail; it
 *       shifts every field after it.</li>
 *   <li>{@code AuthorizationMessageMapperTest} -- that the structured payloads and the delimited wire
 *       records remain ONE contract, across the eighteen request fields of {@code cpy/CCPAURQY.cpy} L19
 *       to L36 and the six reply fields of {@code cpy/CCPAURLY.cpy} L19 to L24.</li>
 *   <li>{@code AuthorizationWireFixtureTest} and {@code AuthRequestWireFixtureTest} -- the committed
 *       request-wire images, and the one wire width this migration publishes. They are what stops two
 *       fixtures from describing two different wires with nothing to reveal the disagreement.</li>
 *   <li>{@code PendingAuthDetailDatePivotFixtureTest} -- both branches of the century pivot. The
 *       reference application stores two-digit years and never widens them, so the migration supplies a
 *       pivot and every stored year takes one of two paths through it; only one committed record pair
 *       reaches both.</li>
 *   <li>{@code MapperRenderingExposureTest} and {@code PendingAuthViewMapperTest} -- what a mapper is
 *       permitted to expose, and that the projections published at the HTTP edge are exactly the ones
 *       the service contract document declares.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the planned inventory for this package was five files naming four test
 * classes, three of which do not exist -- a per-mapper test for the detail segment, one for the summary
 * segment and one for the fraud mapper. What is on disk is organised by CONTRACT rather than by mapper,
 * which is why one class covers every segment conversion and two cover the wire images. The planned
 * inventory also assumed four production mappers when there are five, the fifth being the view mapper
 * whose test is named above. Publishing the planned list would have told a reader that three named
 * classes were missing and that an existing one was surplus, and the likely response -- creating the
 * three and deleting the seventh -- would have duplicated coverage that already exists and removed
 * coverage that nothing else provides.
 *
 * <p>Alternatives Considered: organising this package as one test class per mapper was the planned shape,
 * and it was rejected in favour of organising it per contract. Five mappers would have yielded five
 * classes, but several of the contracts asserted here span more than one mapper -- the two segment mappers
 * share a single record-width and offset contract, and the wire images are meaningful only as a matched
 * set -- so a per-mapper split would have driven the same width assertion into two classes with nothing to
 * keep the two copies in agreement. The cost of the shape actually chosen is that a reader looking for the
 * tests covering one mapper must consult the responsibility list above instead of matching a file name,
 * which is precisely why that list rather than the total is the durable part of this section.
 *
 * <p>Assumptions: the count above is a CENSUS of what is on disk and not a budget, and the distinction is
 * deliberate. The parent charter fixes no leaf-class count anywhere in this tree, and it records why: the
 * production charter one tree away carried an exact type count that was already wrong when it was read,
 * and it dropped the number rather than correcting it, because the point that sentence existed to make
 * did not depend on it. The same holds here. What is durable is the set of responsibilities and which
 * class carries each, because that is what a new test has to be placed against; the total is not. A
 * genuinely new contract at this boundary may be added as an eighth class, and a helper serving a single
 * class stays private INSIDE that class rather than becoming a shared type, so that a reader of one test
 * can see everything it depends on without opening a second file.
 *
 * <h2>Naming and reporting</h2>
 *
 * <p>A class in this directory runs only if its name ends {@code Test}, which is what the standard
 * Surefire binding selects during the test phase; it reports into {@code target/surefire-reports}. No
 * class here may end {@code IT}. That suffix is claimed by Failsafe, which asserts after packaging in the
 * verify phase and reports into {@code target/failsafe-reports}, and in this module exactly one class
 * carries it -- ending {@code RepositoryIT}, in {@code .fixtures}. Both report directories are the
 * plugin defaults and {@code services/pom.xml} records that neither may be relocated, because the
 * continuous integration workflow collects precisely those two paths. Moving either would leave the
 * build green while the workflow published nothing.
 *
 * <p>Assumptions: the gates on this side are BINARY. Maven, Surefire and Checkstyle pass or fail, and the
 * graded return-code rubric the reference COBOL suite aggregates is never imported or emulated here. That
 * rubric assigns its warning level to soft outcomes including a layer that collected no tests, so a
 * warn-tolerant gate would report success over a package in which nothing ran -- indistinguishable from
 * one in which everything passed. The parent charter and {@code services/pom.xml} both record the same
 * prohibition.
 *
 * <h2>Non-duplication: the shared kernel owns the codec internals</h2>
 *
 * <p>The codecs this package drives are proved once, in {@code services/common-lib}. Re-proving them here
 * would leave two suites asserting one contract, so a change to that contract would fail twice and a
 * reader could not tell which assertion was the authority. These four subjects belong there and must not
 * be restated here:
 *
 * <ul>
 *   <li>The delimited authorization codec's tests own the exhaustive eighteen-field request and six-field
 *       reply contract, the declared field-width sums, the comma offsets, and the regression that pins
 *       the reply payload at 63 rather than 62.</li>
 *   <li>The packed-decimal codec's tests own the closure proofs for the hundred-byte summary segment and
 *       the two-hundred-byte detail segment, the seven-byte width of a packed {@code S9(10)V99}, the
 *       eight-byte composite key, and the sign-nibble and pad-nibble policies.</li>
 *   <li>The fixed-width codec's tests own every record-layout assertion and the layout registry, together
 *       with the policy on retaining rather than dropping a padding field. There is deliberately no
 *       separate test class for the layout descriptor itself.</li>
 *   <li>The zoned-decimal codec's tests own the sign-overpunch table.</li>
 * </ul>
 *
 * <p>Assumptions: the sign-overpunch table does not apply ANYWHERE in this context, which is worth
 * stating because it applies almost everywhere else in this migration. Not one signed zoned field exists
 * in either segment. What arrives instead is packed decimal, at {@code cpy/CIPAUSMY.cpy} L19 for the
 * account key and L23 to L26 and L29 to L30 for four balances and two amounts, and at
 * {@code cpy/CIPAUDTY.cpy} L20 to L21 for the composite key and L34 to L35 for the two amounts; a
 * two-byte binary counter, at {@code cpy/CIPAUSMY.cpy} L27 to L28; and unsigned DISPLAY numerics that
 * carry no sign at all, the customer identifier declared {@code PIC 9(09)} at
 * {@code cpy/CIPAUSMY.cpy} L20 among them. A test written here against overpunch behaviour would be
 * asserting a decoding regime these records never use, and it would pass while proving nothing about
 * them.
 *
 * <p>What this package owns EXCLUSIVELY, so that its absence here is its absence everywhere: the
 * positional split of the five-slot account-status array at {@code cpy/CIPAUSMY.cpy} L22 into five
 * discrete columns, the fixed arity being what the schema then enforces; the composition of the
 * authorization timestamp from the separate six-character date and time parts at
 * {@code cpy/CIPAUDTY.cpy} L22 to L23; the deliberate ABSENCE of trimming on the twenty-two-character
 * merchant name at L40, where a trailing blank is content and not padding; the one field-spelling change
 * this context makes, from the baseline's misspelled merchant category code -- which appears twice, at
 * {@code cpy/CCPAURQY.cpy} L28 and {@code cpy/CIPAUDTY.cpy} L36 -- to a corrected reader-facing name;
 * primary-account-number masking; and the two divergences named below.
 *
 * <p>Assumptions: the assertions here sit at the SERVICE BOUNDARY. The question a test in this package
 * asks is what this context does with a decoded value and what it is willing to publish, never whether
 * the decoding itself was correct. A mapper is asserted to ROUTE through the shared codec and to preserve
 * the contract end to end; the codec's internals are somebody else's subject.
 *
 * <h2>What is inherited</h2>
 *
 * <p>The layering rules are authored once, in the shared kernel's test tree, and re-run against this
 * module's own compiled classes by a dedicated Surefire execution declared in {@code services/pom.xml}
 * that scans the published test artifact rather than copying it. Selecting nothing there is a build
 * failure rather than a silent success. Three of those rules are worth naming here because each is
 * regularly assumed to reach further or less far than it does. The first forbids a domain package from
 * depending on a cloud software development kit, web or servlet type, and is scoped to any package
 * matching a domain segment. The second forbids one bounded context from reaching into another's domain
 * model, across the fixed set of package roots. The third forbids a binary floating-point member in a
 * field, in any parameter position or in a return type.
 *
 * <p>Refactoring Rationale: an earlier reading held that the third rule was scoped to the shared money
 * package alone, that this context's money path was consequently NOT covered by inheritance, and that
 * every test here therefore had to assert the prohibition for itself. That reading is wrong, and
 * correcting it is the most consequential thing in this charter. The rule selects classes residing in the
 * analysed root identifier, which is the {@code com.carddemo} root followed by the subpackage wildcard,
 * so {@code com.carddemo.authorization} is inside its subject set; the money package identifier the
 * earlier reading mistook for the scope is used only as the anchor for that rule's own
 * non-empty-subject check, and the rule's own documentation says so at its declaration. Acting on the
 * superseded reading would have cost twice over: these tests would have re-proved a rule they already
 * inherit, and a reader would have believed this module unprotected while it was protected.
 *
 * <p>Assumptions: inheritance covers the DECLARATION of an inexact type and not the ARITHMETIC done with
 * an exact one, and that boundary is what decides which money assertions remain this package's own. That
 * an amount carries a scale of two, that rounding is half-up, and that an amount crosses a boundary as a
 * string rather than as a bare number are properties of values and of payloads, not of declared member
 * types, and no import graph can see any of them. They are asserted here, and the last of the three
 * matters most at this edge: a bare number in a payload is parsed into a binary floating-point value by
 * most clients, which discards exactness at the one boundary a user actually sees.
 *
 * <h2>Fixtures</h2>
 *
 * <p>Four committed images are read from this module's test resources by the classes above: the canonical
 * request wire, the amount variants, the decode-only receiver wire, and the detail record pair carrying
 * both date formats. The reply images are read here too, by the segment conversion class.
 *
 * <p>Assumptions: the Explainability obligation for those images is DISCHARGED rather than outstanding,
 * and by three artifacts jointly. The fixtures directory carries its own README, written per file. One
 * contract test in {@code .fixtures} loads every resource and asserts each one's width, its fields, its
 * final record and its failure path. And each consuming test states, in its own {@code Assumptions:}
 * block, the layout and the length invariant it relies upon. The third is not optional because the README
 * exists: a README cannot say which invariant a particular test depends on, so a test that consumes an
 * image without stating that invariant is incomplete even though the directory is documented. No count of
 * fixture files is fixed in this charter, for the same reason no leaf-class count is.
 *
 * <p>Assumptions: two fixture conventions here are load-bearing and neither may be tidied. A recorded
 * byte image carries NO trailing newline, so that its length equals its content length and a byte-array
 * comparison is meaningful, while its comma-delimited sibling does carry one. And a fixture whose name
 * declares it decode-only is exactly that: its re-encoding is deliberately not byte-identical, so it may
 * never be used as an encoding oracle. One of the four images this package reads is of that kind, which
 * is why the distinction is repeated here rather than left to the fixtures README.
 *
 * <h2>Divergences</h2>
 *
 * <p>The two divergences this package owns are both carried by material read here, and both need a
 * {@code Refactoring Rationale:} at whichever test asserts them -- that label being the correct one even
 * for a newly authored test, because a divergence test is an assertion about a REPLACED behaviour and the
 * label's requirement is to state what was wrong with the approach it replaces. The first is the money
 * token parsed through a receiver one character too narrow, where an over-long token now fails loudly
 * instead of losing its final digit. The second is the one logical amount with two textual forms, the
 * wire carrying the zero-SUPPRESSED edited rendering and the stored row the zero-FILLED one, both of
 * which are asserted because asserting either alone would let the other drift.
 *
 * <p>Assumptions: the first of the two depends on the HUNDREDTHS digit and not on the amount's
 * magnitude. A conforming money token is always fourteen characters, so a move into a thirteen-character
 * receiver always discards the fourteenth; the VALUE is corrupted only when that discarded character is
 * non-zero. Of the amounts available in this module's fixtures only the all-nines variant satisfies that,
 * so an assertion of this divergence must use the second record of the amount-variants image. Written
 * against the canonical request image the test passes while proving nothing, which is worse than having
 * no test at all because it reads as coverage. The authoritative wording of both divergences lives in the
 * parent charter and in {@code docs/architecture/cobol-to-service-traceability.md}; this charter points at
 * them and does not restate them, so that there is one place to correct.
 *
 * <p>Assumptions: neither divergence authorises a change to the baseline. Everything under {@code app} is
 * reference material and the behavioural oracle, so finding a defect there creates an obligation to
 * document it and never an obligation to repair it. A test may assert what this context does instead; it
 * may not assert that the baseline has been altered, because it has not been.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Assumptions: this file is required by user-specified Rule 1, whose scope clause names a module entry
 * point, and in Java a package declaration is that entry point with {@code package-info.java} the only
 * construct able to carry Javadoc for one. What makes the requirement MECHANICAL is a pair of Checkstyle
 * modules that divide the work, and the two halves are not interchangeable. The checker-level module
 * audits the file system and requires this file to be PRESENT in any directory contributing a Java source
 * the gate processes, so the seven classes beside it are what make its presence compulsory. The module
 * inside the tree walker requires the file, once present, to CARRY Javadoc, which is why a bare package
 * statement would not satisfy the gate. Neither can be waived: the rule set configures no in-source
 * suppression filter of any kind, so no annotation or magic comment can bypass a violation from within a
 * Java file, and the file-based filter fails closed with its optional flag false. The two sanctioned
 * suppressions cover generated sources and fixture RESOURCES only, and the second states in its own
 * comment that it must never be widened to the Java test tree. Enforcement over this tree further depends
 * on the parent POM's test-source flag, which resolves on; Rule 1 would bind regardless, so this charter
 * would be required even if that flag resolved off.
 *
 * <p>Assumptions: the gate is bound to the {@code validate} phase, so a missing or empty charter stops
 * the reactor BEFORE any class in this directory is compiled. The practical consequence is the reason
 * this file is authored first: the failure presents as a documentation violation naming a directory
 * rather than as a compilation error naming a class, so an author who added a test class first would be
 * looking for a fault in code that had not yet been compiled.
 *
 * <p>Trade-offs: every justification above sits inside this Javadoc block rather than beside the
 * statement it explains, which departs from the letter of Rule 1's adjacency requirement and is
 * nonetheless the only placement this file admits. A package declaration has no statements, so there is
 * nothing for a comment to sit adjacent TO, and the requirement is satisfied vacuously rather than
 * waived. The cost accepted is that these entries sit further from the behaviour they govern than an
 * inline comment would; the compensation is that each one names the file, and where useful the line, that
 * settles it. Read the labelled entries as the rationale half of a conjunctive gate that fails work
 * missing EITHER the docstring or the rationale, and not as evidence that the half was skipped for want
 * of somewhere to put it.
 *
 * <p>Assumptions: of the four content elements Rule 1 enumerates, only Purpose applies to a package
 * declaration -- it accepts no parameters, yields no value and raises nothing -- so the other three are
 * INAPPLICABLE rather than omitted, and no at-clause is fabricated to stand in for one. Writing a
 * placeholder against a parameter or an exception that cannot exist would itself trip the rule's
 * prohibition on documentation that says nothing.
 */
package com.carddemo.authorization.mapper;
