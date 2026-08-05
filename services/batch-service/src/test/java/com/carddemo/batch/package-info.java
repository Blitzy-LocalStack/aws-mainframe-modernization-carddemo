/**
 * Test root of the batch bounded context, and the one test tree in this reactor whose results are
 * checked against committed reference output rather than against transcribed prose alone.
 *
 * <h2>What the three subpackages hold</h2>
 *
 * <p>Three subpackages, and no fourth:</p>
 *
 * <ul>
 *   <li><b>{@code com.carddemo.batch.service}</b> -- unit tests over the four
 *       transcribed-business-rule services of the production package of the same name. A test here
 *       needs neither a database nor a container: with the repositories supplied as test doubles,
 *       each rule is a function of its arguments, and constructor injection in the production
 *       package is what makes that substitution possible at all.</li>
 *   <li><b>{@code com.carddemo.batch.job}</b> -- tests over the seven Spring Batch job
 *       definitions. A test here asserts step ordering, reader, processor and writer wiring, and
 *       the arguments a job accepts, and delegates every business-rule assertion downward to the
 *       service tests. That split mirrors the production split deliberately, so one behaviour is
 *       asserted in one place and a rule change breaks one test rather than eight.</li>
 *   <li><b>{@code com.carddemo.batch.repository}</b> -- Testcontainers-backed integration tests
 *       over the eight Spring Data interfaces. These are the tests that require a real PostgreSQL
 *       instance, because what they cover is the migrated form of the baseline's record access and
 *       a test double cannot disagree with a query the way a database can.</li>
 * </ul>
 *
 * <p>Alternatives Considered: dividing the tree some other way, or leaving the list open. The
 * division above is by the environment a test needs rather than by the feature it covers, and it is
 * the division that keeps the fast tests fast: grouping a rule test beside a repository test would
 * put both behind the container start-up the second one requires, so the rule tests would stop
 * being runnable in the inner loop and would be run less often as a result. Leaving the list open
 * was rejected because the question a test author has to answer is "which of these does my test
 * belong in", and an open list answers it by inventing a fourth home, at which point the same
 * behaviour can be asserted in two places and the two can drift apart.</p>
 *
 * <p>This subtree carries one charter per package and no more: this one, and one in each of those
 * three subpackages, so four in all. This directory itself holds no test class, no shared base
 * class, no helper, no fixture and no resource -- everything that executes lives one level down, in
 * the subpackage whose environment it needs.</p>
 *
 * <p>Alternatives Considered: a shared base class or assertion helper at this level, which is where
 * one would naturally go if all three subpackages came to need it. Rejected, because such a helper
 * sits at the one level none of the three owns, and an assertion moved into it can afterwards be
 * weakened by an edit to the helper that reads as ordinary maintenance rather than as the
 * relaxation of a parity assertion that it would in fact be. Duplicating a few lines of set-up
 * across three subpackages is the accepted cost of keeping each assertion visible in the file that
 * depends on it.</p>
 *
 * <p>Assumptions: no charter file exists at {@code com/} or at {@code com/carddemo/} under this
 * test root, and none belongs there. Both are pure namespace directories holding no compilation
 * unit, so the presence check cannot fire in either while the content check would immediately
 * demand real Javadoc from any defensive stub placed there; the production charter at
 * {@code services/batch-service/src/main/java/com/carddemo/batch/package-info.java} records that
 * reasoning in full for the main source tree and it applies here unchanged. This directory is
 * where the compilation units of this test tree begin.</p>
 *
 * <h2>Why this tree is measured against committed bytes and not only against transcribed rules</h2>
 *
 * <p>The functional-parity oracle under {@code tests/} can drive the batch programs of the
 * reference baseline end to end, and it cannot do the same for the online ones:
 * {@code tests/README.md} records in its known-limitations section that the online {@code CO*}
 * programs cannot run end to end without a CICS runtime, which the runner does not have. The batch
 * chain is consequently the one place where reference output and migrated output can be laid side
 * by side and compared byte for byte after timestamp normalisation, and this is the only test tree
 * in {@code services/} that sits opposite such a comparison.</p>
 *
 * <p>Assumptions: that asymmetry is the reason a test in this subtree carries an obligation the
 * other modules' tests do not. Elsewhere a passing test means the transcribed rule behaves as the
 * test author read it; here a passing test additionally has to be reconcilable with committed
 * expectation output that no test in this tree may edit. The two can disagree, and when they do the
 * committed output is right and the assertion is wrong -- so a test author who finds a mismatch
 * revises the assertion or records a divergence, and never regenerates the expectation to match.
 * Regenerating it would convert the one independent check on this module into a restatement of
 * whatever the migrated code currently produces, which is indistinguishable from having no check at
 * all.</p>
 *
 * <p>Assumptions: the graded numeric result vocabulary belongs to that oracle suite and to the
 * batch container's own process exit status, which the orchestrating state machine reads. The two
 * runners that collect the classes in this tree, and the documentation gate that audits them, are
 * binary: each either passes or fails the build, no tolerance of any kind is configured for any of
 * them, and none may be described in graded terms. Borrowing the graded vocabulary for a build
 * result would let a real failure read as an accepted warning.</p>
 *
 * <h2>The name that decides which runner collects a test, and what a wrong one costs</h2>
 *
 * <p>Assumptions: this tree is split across two runners by class name alone, and neither runner is
 * configured with an include pattern anywhere in the reactor -- {@code services/pom.xml} and
 * {@code services/batch-service/pom.xml} both declare the integration-test runner as a bare
 * coordinate and both record that the default patterns already match. A unit-test class name
 * begins with {@code Test} or ends with {@code Test}, {@code Tests} or {@code TestCase}, and the
 * unit runner collects it during the Maven {@code test} phase, before the module is packaged and
 * with no database available. An integration-test class name begins with {@code IT} or ends with
 * {@code IT} or {@code ITCase}, and the integration runner collects it at
 * {@code integration-test} and asserts its result at {@code verify}. The {@code *RepositoryIT}
 * suffix this project uses for the repository subpackage falls in the second set and outside the
 * first, which is why it needs no configuration to work.</p>
 *
 * <p>Assumptions: the failure a wrong name produces is worth naming, because it is quiet rather
 * than loud. A class called {@code *RepositoryIntegrationTest} or {@code *RepositoryITest} ends in
 * {@code Test}, so the unit runner collects it in the {@code test} phase while the integration
 * runner never sees it: the container the test expected was never started, and the class fails on
 * connection rather than on its assertions, or is silently skipped by whatever guard the author
 * added to keep the build green. Either way the query the test was written to cover stops being
 * covered, and the build still reports success. The {@code Integration} and {@code I} spellings
 * both read as more explicit than {@code IT} and are both wrong for that reason.</p>
 *
 * <h2>Boundaries this tree does not cross</h2>
 *
 * <p>Alternatives Considered: a controller-test tier -- an {@code api} test subpackage holding
 * {@code *ControllerTest} classes, as the online modules of this reactor have -- was evaluated for
 * this module and is structurally impossible here, so its absence is a decision rather than an
 * omission. Three independent grounds each settle it. This module is started by an orchestrated
 * container task through the synchronous run-task integration and accepts its job selection and
 * business date as process arguments, so there is no request for such a test to send. The
 * production charter fixes a subpackage map for this context that contains no {@code api}
 * directory, so a controller test would be asserting against a package the plan does not create.
 * And {@code services/batch-service/pom.xml} declares no web starter, no API documentation
 * starter, no security starter and no resource-server starter, so a class referring to a controller
 * or to a mock web layer would not compile in this module at all. Writing the tier anyway would
 * cost a red build to discover what the dependency list already states.</p>
 *
 * <p>No layering rule is declared or configured anywhere in this tree, and no
 * {@code archunit.properties} belongs in it.</p>
 *
 * <p>Alternatives Considered: a local copy of the layering rules, or a local engine configuration
 * file to tune them for this module. Both are rejected, because the architecture invariants of the
 * migrated decomposition have exactly one owner -- the pinned gate under
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture}, which this module
 * executes over its own compiled classes through the reactor's shared runner execution. A second
 * copy here would be a second place a boundary could be relaxed while the first copy still read as
 * intact, and a local configuration file would let this module opt out of a rule the other eight
 * still answer to, with nothing in the shared gate able to report the divergence.</p>
 *
 * <p>Assumptions: no ignore file belongs in this module or anywhere in this tree, because ignore
 * rules for build output are already carried by the single repository-root {@code .gitignore} that
 * covers every module's {@code target} directory. A second ignore file here would only shadow that
 * one for this subtree, so a rule added at the root would silently stop applying where the local
 * file overlapped it.</p>
 *
 * <p>Assumptions: the reference baseline under {@code app/} and the oracle suite under
 * {@code tests/} are read as evidence and are never modified, re-pinned or regenerated by anything
 * in this tree. A test here cites a baseline program, copybook or job by path when it needs to
 * ground an assertion, and that citation is the whole extent of the relationship: the baseline
 * behaves as it behaves, the migrated Java implements what this module implements, and any
 * difference between the two is recorded as a documented divergence rather than described as a
 * change to either side.</p>
 *
 * <h2>Parameters, return values and exceptions: declared inapplicable</h2>
 *
 * <p>A package declaration accepts no parameter, yields no value and raises nothing, so this
 * charter carries no parameter, return or exception at-clause, and no authorship, availability or
 * revision at-clause either.</p>
 *
 * <p>Assumptions: the inapplicability is stated rather than left silent because the
 * user-specified Explainability rule lists a docstring that omits parameters, return values or
 * purpose among its forbidden patterns, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Fabricating the at-clauses instead would be worse than
 * useless: Javadoc has no parameter, return or exception concept for a package, and the shared
 * ruleset audits at-clause bodies for emptiness, so an invented clause would either be discarded or
 * reported. An exception clause whose body states that nothing is thrown is not an acceptable
 * stand-in either, because it would document an absence the construct is incapable of having.</p>
 *
 * <p>Assumptions: that exemption is this compilation unit's alone and does not travel into the
 * three subpackages, where a test class, a test method and a private helper alike do have
 * parameters, return values and thrown types to document. The obligation there rests on the rule's
 * docstring-elements clause, on the house convention the oracle suite states for every new test,
 * fixture builder, helper and mock, and on the shared ruleset's own at-clause validation, which
 * checks a Javadoc block's coverage of thrown types wherever such a block exists. It does not rest
 * on the rule's validation gate, which names purpose, parameters and return values and does not
 * mention exceptions -- citing the gate for an exception clause would attribute to it a
 * requirement it does not carry.</p>
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
 */
package com.carddemo.batch;
