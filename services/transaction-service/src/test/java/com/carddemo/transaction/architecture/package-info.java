/**
 * Owns the transaction-service module's own build-enforced ArchUnit layering
 * gate, together with the two tests that prove that gate can fail.
 *
 * <h2>Purpose, and the rule elements with no subject here</h2>
 *
 * <p><b>Purpose.</b> This package holds the layering gate this module is held
 * to by its own build, and the two tests whose whole job is to demonstrate that
 * the gate fails when a boundary is crossed. Three things live here and nothing
 * else: one rules class carrying the module-scoped layering invariants, and two
 * gate-proving tests. The invariants themselves are not invented here. They are
 * the invariants of the migrated decomposition, stated once in the shared
 * kernel's architecture charter, and this package is where they are evaluated
 * against {@code com.carddemo.transaction} classes rather than against prose.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package
 * declaration accepts no parameters, returns no value and raises nothing, so
 * this charter carries no parameter, return or exception at-clause. The
 * inapplicability is declared rather than left silent, because the
 * Explainability rule's line 39 names a docstring that omits parameters, return
 * values or purpose among its forbidden patterns, and a reader has to be able
 * to tell a declared inapplicability from an oversight. Assumptions: writing
 * those at-clauses out anyway would not be harmless padding -- Javadoc has no
 * parameter, return or exception concept for a package, so an invented
 * at-clause is either discarded or, given no body to make it look complete,
 * reported by {@code NonEmptyAtclauseDescription} in
 * {@code config/checkstyle/checkstyle.xml}.
 *
 * <h2>The three gate families this package owns</h2>
 *
 * <p>The rules class names its families {@code A1}, {@code A2} and {@code A3},
 * and the labels are carried into the assertion messages deliberately, so that
 * a reader who meets a failed build has a token to search for and lands on the
 * boundary that was crossed rather than on a rule engine's generic wording:
 *
 * <ul>
 *   <li><b>{@code A1}, domain purity.</b> No type in a {@code ..domain..}
 *       package may depend on an AWS SDK type, a Spring web type or a Jakarta
 *       servlet type. The domain of this module holds the entities derived from
 *       the reference record layouts, and it is the one layer that has to stay
 *       expressible with no transport and no infrastructure client attached to
 *       it. Under the alternative, a unit test of a transcribed posting or
 *       validation rule can no longer run without standing up whatever the
 *       domain class imported, and the rule stops being what the test
 *       measures.</li>
 *   <li><b>{@code A2}, cross-service domain isolation.</b> No service may
 *       import another service's domain package. The package roots are fixed at
 *       {@code com.carddemo.common} together with the eight service roots
 *       {@code auth}, {@code account}, {@code card}, {@code transaction},
 *       {@code reference}, {@code batch}, {@code authorization} and
 *       {@code reporting}, which makes nine, and only
 *       {@code com.carddemo.common} is shared. Because the roots are fixed, a
 *       crossing is detectable from a package name alone, with no annotation or
 *       registry to keep in step with the source. The shared kernel is modelled
 *       as a shared kernel on purpose: it is the Java analogue of compiling
 *       every reference program against one copybook include path, which is why
 *       importing from it is not a crossing while importing a sibling's domain
 *       is.</li>
 *   <li><b>{@code A3}, the money path.</b> Neither {@code double} nor
 *       {@code float}, and neither {@code java.lang.Double} nor
 *       {@code java.lang.Float}, may appear anywhere money is carried. The
 *       boxed pair is named alongside the primitives because a field or a
 *       collection element declared with the wrapper reaches binary
 *       floating-point arithmetic by exactly the same route, and a rule naming
 *       only the primitives would pass a class that had merely boxed the
 *       defect.</li>
 * </ul>
 *
 * <p>Assumptions: {@code A3} is grounded in the baseline this module was
 * transcribed from, not in a general preference for decimals. The money fields
 * of the two records this context owns are zoned decimal, declared
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10 and
 * {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy} line 9, so
 * every value they hold is an exact number of cents. A binary floating-point
 * type cannot represent most exact cent values, so an amount routed through one
 * comes back plausible and slightly wrong, and it surfaces at the far end of
 * the pipeline as an unexplained one-cent difference rather than as an error at
 * the conversion that caused it. A rule naming the forbidden types is the form
 * of this constraint that reports the cause instead of the symptom.
 *
 * <h2>Why this module owns a gate of its own</h2>
 *
 * <p>Assumptions: the shared rules class reaches this module by a delivery
 * mechanism of four cooperating declarations, all of them present in this
 * reactor: {@code services/common-lib/pom.xml} binds {@code maven-jar-plugin}'s
 * {@code test-jar} goal in its {@code architecture-rules-test-jar} execution,
 * narrowed by an {@code includes} element to the shared architecture package
 * alone; {@code services/transaction-service/pom.xml} declares that artifact
 * with {@code <type>test-jar</type>} at test scope, and declares
 * {@code com.tngtech.archunit:archunit-junit5} at test scope beside it because
 * test scope is not transitive and a discovered rules class without the
 * assertion API fails to load rather than failing an assertion; and the
 * {@code architecture-rules} Surefire execution in {@code services/pom.xml}
 * names the shared kernel in {@code dependenciesToScan} so the class is
 * collected from that artifact and evaluated against this module's own compiled
 * classes. Assumptions: the {@code test-jar} goal is bound at
 * {@code process-test-classes} rather than at its default {@code package} phase
 * because Surefire's dependency scanner reads only files whose name ends in
 * {@code .jar}; an unpackaged reactor dependency is satisfied from a directory,
 * which that scanner ignores in silence, so under the default binding a plain
 * {@code mvn test} at the reactor root would evaluate no shared rule at all
 * while reporting success. This package is ADDITIVE to that shared class and
 * never a replacement for it.
 *
 * <p>Assumptions: what the shared class cannot do for this module is the reason
 * this package exists, and it rests on three properties of that class recorded
 * in its own charter rather than visible from a call site. First, one class runs
 * in nine modules, so it has to tolerate a rule whose input set is empty -- the
 * shared kernel itself has no {@code domain} package at all, and ArchUnit fails
 * a rule whose expectation saw no classes unless the rule says otherwise. An
 * empty-tolerant rule passes when it matches nothing, so on its own it cannot
 * separate "no violation here" from "no subjects here". Second, the
 * {@code architecture-rules} execution narrows its scan with an
 * {@code includes} element naming {@code LayeringRulesTest} alone, and a module
 * cannot widen that back, because Maven resolves an execution's own
 * configuration ahead of the plugin-level configuration a module declares.
 * Third, the shared architecture package is a closed set that admits no second
 * rule class, on the stated ground that a rule split across two files can be
 * weakened by editing the one that reads as maintenance. Taken together those
 * three leave a module-scoped invariant with nowhere in the shared artifact to
 * live, and leave the shared families with no way to assert that they matched
 * anything here. Both gaps are closed inside this package or not at all.
 *
 * <h2>The reserved simple name is a contract, not a style</h2>
 *
 * <p>The rules class here is named {@code TransactionLayeringRulesTest}. It
 * must never be a second class named {@code LayeringRulesTest}, because that
 * simple name is reserved for the shared class and the parent test charter
 * records the same reservation.
 *
 * <p>Assumptions: the reservation is load-bearing against a literal string. The
 * {@code architecture-rules} Surefire execution selects the shared class
 * through an {@code includes} element whose pattern ends in
 * {@code LayeringRulesTest.java} -- a simple name written as a literal, with no
 * package qualifier to tell two classes apart. A second class of that simple
 * name in this module would be matched by the same pattern, so an execution
 * meant to run one shared class would run two different classes whose reports
 * are distinguishable only by the module they ran in. A distinct simple name
 * keeps the inherited gate and this module's own gate separable in the runner,
 * in the report and in the log.
 *
 * <p>Trade-offs: the coupling to a literal file name is accepted rather than
 * removed. The alternative was to widen the shared scan to every test class the
 * shared artifact carries, which would need no reserved name at all; it was
 * rejected because the shared kernel's own codec, money and timestamp suites
 * would then re-run once per service, and one defect in one module would be
 * reported eight times in eight places. The accepted cost is that two files now
 * name each other, so a rename of either has to be made in both.
 *
 * <h2>A gate that cannot fail is not a gate</h2>
 *
 * <p>Besides the rules class, this package holds {@code MoneyPathGateProofTest}
 * and {@code KeysetPaginationGateProofTest}. Each one constructs a subject that
 * violates a boundary and asserts that the corresponding rule rejects it, so
 * the evidence that the gate bites is produced by the build rather than assumed
 * from the gate's presence.
 *
 * <p>Assumptions: the need for that proof is the exact consequence of the
 * empty-set tolerance described above, because a rule carrying that tolerance
 * reports the same pass whether it examined every class in the module or none of
 * them. A family that silently matched nothing and a family that genuinely
 * found nothing to complain about therefore emit an identical result, and the
 * likeliest repair a hurried reader reaches for when a layering gate fails
 * noisily is to stop running it. Proving the negative once, in the build,
 * removes the ambiguity in the direction that matters: a proof test that stops
 * failing its violating subject is itself a failure, so the erosion becomes
 * visible instead of quiet.
 *
 * <p>Assumptions: {@code KeysetPaginationGateProofTest} guards a boundary this
 * module owns rather than a general one, which is why it belongs here and not
 * in the shared artifact. The list screens of this context page by key and
 * never by offset, and that is what the baseline does rather than an
 * improvement on it -- {@code app/cbl/COTRN00C.cbl} fills ten screen rows under
 * a loop that runs until its index reaches 11 at line 297 and then performs an
 * eleventh read at line 308 whose outcome alone sets the next-page indicator,
 * and {@code app/cbl/COCRDLIC.cbl} carries the browse cursor across a screen
 * turn as a last-key and first-key pair at lines 229 to 244. Under offset
 * paging, concurrent inserts silently skip and repeat rows because the offset is
 * counted against a result set that has changed, so substituting one would
 * change observable behaviour and not merely the implementation.
 *
 * <h2>Which runner executes these classes, and where their reports land</h2>
 *
 * <p>All three classes in this package end in {@code Test}, so Surefire
 * collects them and they run at the {@code test} phase, while the
 * {@code RepositoryIT} classes of the sibling {@code repository} package end in
 * {@code IT} and are collected by Failsafe at {@code integration-test} with
 * their result asserted at {@code verify}. The full statement of that split
 * belongs to the parent test charter and is cited rather than restated.
 *
 * <p>Assumptions: the consequence for this package is a prohibition. Reports
 * land in the DEFAULT directories,
 * {@code services/transaction-service/target/surefire-reports} and
 * {@code services/transaction-service/target/failsafe-reports}, which are the
 * paths the continuous integration pipeline is to collect from. No class in this
 * package may set a reports directory and no module-level configuration may
 * relocate or rename either one, because that would leave the build green while
 * the pipeline published nothing -- the one failure mode here that looks like
 * success.
 *
 * <h2>The closed set of files in this directory</h2>
 *
 * <p>This directory holds four {@code .java} files and no others -- this
 * charter, {@code TransactionLayeringRulesTest}, {@code MoneyPathGateProofTest}
 * and {@code KeysetPaginationGateProofTest} -- and no subdirectory beneath it.
 * This charter is one of the six the parent test charter's count canon admits
 * across this test subtree, so the figure here can be re-checked against that
 * one rather than argued again.
 *
 * <p>Assumptions: the closure extends past Java sources, and each exclusion is
 * an absence with a plausible alternative, recorded so that nobody restores one
 * as an apparent omission. No ArchUnit properties file is added, because a rule
 * relaxed through a configuration file is relaxed invisibly at the point where
 * anyone reads the rule. No module-local Checkstyle configuration and no
 * module-local suppressions file are added, because the gate over this tree has
 * exactly one owner and a second configuration would let a boundary be silenced
 * in the file least likely to be reviewed. No helper and no base class are
 * added, because a rule split between a class and a helper can be weakened by
 * editing the helper, where the change reads as maintenance rather than as the
 * relaxation of an architectural constraint that it is. And no ignore file is
 * added, since build output is excluded once at the repository root.
 *
 * <h2>This gate is binary</h2>
 *
 * <p>Assumptions: a Maven, JUnit, Checkstyle or ArchUnit outcome here passes or
 * it fails. The graded condition-code rubric under which a warning-level result
 * is a green state belongs exclusively to the COBOL parity oracle under
 * {@code tests/}, where it exists because two baseline programs carry a
 * record-key defect in immutable reference source that is out of scope for this
 * migration. No graded tolerance, no warning tier and no arithmetic on a return
 * code may be introduced in this package, and this gate is never wired into the
 * oracle's own workflow. Importing that rubric here would let a failed layering
 * assertion be reported as an acceptable outcome, which is the one thing a gate
 * must never be able to do.
 *
 * <p>Assumptions: there is no in-code escape from the documentation gate either.
 * None of the three comment-driven or annotation-driven suppression filters is
 * enabled in {@code config/checkstyle/checkstyle.xml}, so neither a marker
 * comment nor an annotation suppresses anything, and the companion suppressions
 * file does not reach test sources. Skipping the plugin, lowering its violation
 * threshold or pointing it at another suppressions file are breaches of the rule
 * rather than build-configuration choices.
 *
 * <h2>Citation discipline</h2>
 *
 * <p>Trade-offs: the citations above deliberately take two shapes, split by
 * whether the cited file can move. Everything under {@code app/} is immutable
 * reference material for this migration, so those citations carry line numbers
 * and will keep resolving. The build descriptors under {@code services/} are not
 * immutable, so they are cited by the identifiers they declare -- an execution
 * id, a goal, an element name -- rather than by line, because a line-numbered
 * citation into a file still being extended decays into a confident reference to
 * the wrong line. The cost is that a reader has to search a descriptor for the
 * named element instead of jumping straight to it.
 *
 * <p>Assumptions: nothing under {@code app/}, {@code tests/}, {@code scripts/}
 * or {@code samples/} is modified by this package, and the framing of any
 * difference from the baseline is constrained. Never write that anything in
 * {@code app/} was fixed, corrected, patched, remediated or repaired. The one
 * permitted framing is that the baseline does X, the Java implements Y, and the
 * divergence is documented -- wording that survives review because each of its
 * three clauses is independently checkable.
 *
 * <p>Assumptions: two identifier namespaces collide by number and are kept
 * textually distinct here. The user-specified rule is Explainability and is
 * cited by its line numbers. The migration plan's transformation rules are
 * numbered {@code T1} to {@code T10} and are always written with the T. "Rule
 * 1" never means {@code T1}, and writing one for the other points a reader at a
 * document that says something else entirely.
 *
 * <p>Trade-offs: this file is pure ASCII, so a double hyphen stands in for a
 * dash throughout. The alternative was to reproduce the typographic punctuation
 * the migration prose uses, which would read closer to that prose; ASCII was
 * chosen because a non-breaking hyphen is indistinguishable from an ordinary one
 * on screen while behaving differently in a search, so a rationale label copied
 * from prose that used one becomes a token an audit searching for the category
 * fails to find. {@code docs/CODE_DOCUMENTATION_STANDARD.md} carries the full
 * statement of the label convention and is cited rather than restated.
 */
package com.carddemo.transaction.architecture;
