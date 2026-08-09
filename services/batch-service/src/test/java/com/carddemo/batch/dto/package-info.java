/**
 * Test charter for the job-boundary transfer shapes of the batch bounded context: the tests that
 * hold this module's argument contract to the spelling infrastructure depends on.
 *
 * <h2>Purpose: why a fourth test subpackage exists beside service, job and repository</h2>
 *
 * <p><b>Purpose.</b> This package holds unit tests over the types in
 * {@code com.carddemo.batch.dto}. A test here needs no database, no container and no application
 * context: a transfer shape is a value, and every assertion in this package is a function of its
 * own arguments.</p>
 *
 * <p>Assumptions: these tests defend representation rather than behaviour, and for this module
 * that distinction has teeth. Several shapes in this package carry values that are concatenated
 * verbatim into stored primary keys -- {@code BusinessDate} is the worked example, because
 * {@code app/cbl/CBACT04C.cbl:473-480} builds every generated interest transaction's identifier
 * by concatenating the ten-character business date with a six-digit suffix into
 * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:5} -- so a shape that quietly
 * re-rendered its own contents would produce records that are structurally valid and wrong. A
 * test here therefore pins its expectations to an immutable reference artifact under
 * {@code app/} or to a committed expectation file of the functional-parity oracle under
 * {@code tests/golden/}, never to a value chosen because it read well.</p>
 *
 * <p>Assumptions: the test root charter at
 * {@code services/batch-service/src/test/java/com/carddemo/batch/package-info.java} divides this
 * tree by the environment a test needs rather than by the feature it covers, and it names this
 * package alongside service, job and repository for that reason. This package sits at the same
 * no-container tier as the service tests and is separated from them on a different axis: a service
 * test exercises a business rule transcribed from a reference paragraph, while a test here
 * exercises a contract that leaves the module -- a token an orchestration state supplies, an exit
 * tier the orchestrator reads, a reject description that lands in a data file. Those two kinds of
 * assertion fail for different reasons and are read by different people, which is why they are not
 * collapsed into one package.</p>
 *
 * <p>Alternatives Considered: asserting the argument contract from the job tests instead, on the
 * ground that the jobs are what register under those tokens. Rejected, because a job test needs the
 * job beans to exist and a contract test does not: the token spelling is assertable the moment the
 * enumeration exists, which is earlier than the beans land, and it is exactly the interval in which
 * a misspelling is cheapest to catch. Folding the assertions into the job tests would also make a
 * token failure and a step-wiring failure indistinguishable in a build log.</p>
 *
 * <h2>The one obligation every test in this package carries</h2>
 *
 * <p>Assumptions: a contract that crosses the module boundary cannot be verified by reading this
 * module alone, so a test here asserts against the other side of the contract wherever the other
 * side is reachable from the test classpath. The argument tokens are the worked example: they are
 * declared by {@code com.carddemo.batch.BatchApplication} as the accepted set of the
 * {@code --job=} option and modelled again by {@code BatchJobName} for use after startup, and the
 * two are on the same classpath, so a test compares them directly rather than restating either
 * list. A test that instead spelled the seven tokens into its own literal array would pass while
 * both sides drifted together away from the orchestration definition, which is the failure the
 * comparison exists to prevent.</p>
 *
 * <p>Assumptions: reachable means reachable, and the classpath is only the easiest case of it. The
 * argument tokens happen to be declared by two classes this module compiles, so a test compares them
 * as objects; the other side of a contract is often somewhere a classpath cannot reach, and the
 * obligation does not lapse there. {@code DisclosureGroupSeedParityTest} is the worked example: it
 * holds the seeded reference rows this module's harness declares against the migration that owns the
 * table in {@code reference-service} and against the immutable extract under {@code app/}, neither of
 * which is on this module's classpath, so both are read by path from the repository root as
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/ServiceCatalogInventoryTest.java}
 * does. What is not permitted is the shortcut the obligation exists to forbid -- restating the other
 * side as a literal in the test -- and that is unaffected by how the other side is reached.</p>
 *
 * <p>Assumptions: a comparison against an artifact read by path carries a vacuity guard, because a
 * path read can fail in a way an object reference cannot. A parser that matched nothing, or a file
 * that moved, yields an empty collection, and an equality between two empty collections passes while
 * verifying nothing at all. A test here therefore asserts the expected shape of what it parsed --
 * a row count, a required key -- before comparing, so a silent parse failure reads as a failure.</p>
 *
 * <p>Assumptions: the reference implementation under {@code app/} and the parity oracle suite under
 * {@code tests/} are read as evidence and are never modified, re-pinned or regenerated by anything
 * in this package. A test here cites a reference program, copybook or job by path when it needs to
 * ground an assertion, and that citation is the whole extent of the relationship.</p>
 *
 * <p>Assumptions: the graded numeric result vocabulary of that oracle suite belongs to the oracle
 * and to the batch container's own process exit status, and to nothing else. The runner that
 * collects the classes in this package is binary: it passes or fails the build, and no tolerance of
 * any kind is configured for it. Borrowing the graded vocabulary for a build result would let a real
 * failure read as an accepted warning.</p>
 *
 * <h2>Naming, and what a wrong name costs</h2>
 *
 * <p>Assumptions: a class in this package is collected by the unit-test runner during the Maven
 * {@code test} phase, which requires its name to begin with {@code Test} or end with {@code Test},
 * {@code Tests} or {@code TestCase}. No include pattern is configured anywhere in the reactor, so
 * the default patterns are the whole of the contract. A class whose name ended in {@code IT} would
 * instead be collected by the integration runner at {@code integration-test}, after packaging, and
 * would not run at all in a plain {@code test} invocation -- so a no-container assertion would
 * silently stop being checked in the inner loop while the build still reported success.</p>
 *
 * <h2>Parameters, return values and exceptions: declared inapplicable</h2>
 *
 * <p>A package declaration accepts no parameter, yields no value and raises nothing, so this charter
 * carries no parameter, return or exception at-clause, and no authorship, availability or revision
 * at-clause either. The inapplicability is stated rather than left silent because the project's
 * single user-specified rule, Explainability, names at its line 39 a docstring that omits
 * parameters, return values or purpose among its forbidden patterns, and a reader has to be able to
 * tell a declared inapplicability from an oversight. Fabricating the at-clauses would be worse:
 * Javadoc has no parameter, return or exception concept for a package, and the shared rule set
 * audits at-clause bodies for emptiness, so an invented clause would either be discarded or
 * reported. That exemption is this compilation unit's alone and does not travel into the test
 * classes beside it, where a method and a private helper alike do have parameters, return values and
 * thrown types to document.</p>
 *
 * <h2>Why this charter exists, and the form it takes</h2>
 *
 * <p>Assumptions: the Explainability rule requires a docstring on every module entry point, and in
 * Java the entry point of a package is its package declaration, which only a
 * {@code package-info.java} can carry. Two Checkstyle modules enforce that independently and
 * neither is redundant: the file-set check at {@code config/checkstyle/checkstyle.xml:245} requires
 * this file to exist in any directory holding an audited source file, and the syntax-tree check at
 * {@code config/checkstyle/checkstyle.xml:378} requires it to carry Javadoc. The gate audits this
 * tree as well as the main tree, because {@code services/pom.xml} configures the documentation gate
 * to include the test source directory, so a test package without this file fails the build at the
 * Maven {@code validate} phase rather than passing quietly.</p>
 *
 * <p>Trade-offs: this file is restricted to printable ASCII, and where a cited source carries a
 * non-breaking hyphen or an em dash it uses an ASCII hyphen-minus or a pair of ASCII hyphens. The
 * reason is concrete rather than aesthetic: {@code tests/README.md} uses the non-breaking hyphen
 * inside the very words a justification label is spelled from, so text copied from there yields a
 * label that looks correct, greps wrong, and silently escapes an audit searching for the canonical
 * spelling. The accepted cost is typographically plainer prose.</p>
 *
 * <p>Assumptions: the four justification labels used here -- {@code Alternatives Considered:},
 * {@code Refactoring Rationale:}, {@code Assumptions:} and {@code Trade-offs:} -- are spelled
 * exactly as the Explainability rule presents them at its lines 31 to 34 and as
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes them: plural where the rule writes them plural,
 * unparenthesised, each closed by its own colon, and never wrapped in emphasis markup. The bare form
 * is used throughout this package and the emphasis form nowhere, because that standard requires that
 * the forms are never mixed inside one file. {@code Refactoring Rationale:} is deliberately unused
 * here, because that label applies when existing code is replaced and the Java in this module
 * replaces nothing: the reference COBOL stays byte-identical and keeps running.</p>
 */
package com.carddemo.batch.dto;
