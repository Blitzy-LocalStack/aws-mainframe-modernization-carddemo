/**
 * Owns the transaction-service module's own build-enforced ArchUnit layering
 * gate, together with the two tests that prove that gate can fail.
 *
 * <h2>Target contract, and the tree state at this checkpoint</h2>
 *
 * <p>Assumptions: every class name, inventory and count in this charter
 * describes the package's <b>target contract</b> as the migration plan assigns
 * it, not the set of files present beside this one today. The migration lands
 * its artifacts in plan order and this charter is authored first, so at the
 * checkpoint that authored it this directory holds this charter and nothing
 * else. A class named below that has no file is therefore <b>planned</b>, not
 * missing, and a count below is a target total rather than a measurement of the
 * directory. The parent test charter at
 * {@code services/transaction-service/src/test/java/com/carddemo/transaction/package-info.java}
 * and the shared kernel's architecture charter at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/package-info.java}
 * both draw that same distinction, so a reader meets one convention across the
 * three files rather than three readings of it.
 *
 * <p>Alternatives Considered: withholding this charter until the three test
 * classes it governs exist. Rejected, because this file is what the authors of
 * those classes work from -- which simple name is reserved and why, which rule
 * families belong here, what the closed set of files is -- so writing it last
 * would leave the package with no stated contract during exactly the interval
 * in which one is needed. The cost of authoring it first is that the inventory
 * reads as present tense unless the distinction is declared, which is what the
 * paragraph above is for; that sentence is the single place a reader has to
 * look to tell a target from a measurement.
 *
 * <h2>Purpose, and the rule elements with no subject here</h2>
 *
 * <p><b>Purpose.</b> This package holds the layering gate this module is held
 * to by its own build, and the two tests whose whole job is to demonstrate that
 * the gate fails when a boundary is crossed. Three things live here and nothing
 * else: one rules class carrying the module-scoped layering invariants, and two
 * gate-proving tests. The invariants themselves are not invented here. They are
 * the invariants of the migrated decomposition, stated once in the shared
 * kernel's architecture charter cited above, and this package is where they are
 * evaluated against {@code com.carddemo.transaction} classes rather than
 * against prose.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A package
 * declaration accepts no parameters, returns no value and raises nothing, so
 * this charter carries no parameter, return or exception at-clause. The
 * inapplicability is declared rather than left silent, because the
 * Explainability rule's line 39 names a docstring that omits parameters, return
 * values or purpose among its forbidden patterns, and a reader has to be able
 * to tell a declared inapplicability from an oversight.
 *
 * <p>Assumptions: writing those at-clauses out anyway would not be harmless
 * padding. Javadoc has no parameter, return or exception concept for a package,
 * so an invented at-clause is either discarded or, if given no body to make it
 * look complete, reported by {@code NonEmptyAtclauseDescription} in
 * {@code config/checkstyle/checkstyle.xml}. In particular no exception
 * at-clause naming an absence is written here: an at-clause whose body is a
 * word for nothing is not a legal at-clause, and the rule's parenthetical
 * allowance for exceptions "where applicable" at its line 21 is a permission to
 * omit the element, not an instruction to assert emptiness with it. The rule
 * enumerates four docstring elements at its lines 18 to 21 and exactly one of
 * the four has a subject in this compilation unit; this paragraph and the one
 * above it account for the other three.
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
 * <p>Assumptions: the shared rules class at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * does reach this module and does run against this module's compiled classes.
 * Three cooperating declarations carry it, and all three are present in this
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
 * collected from that artifact and run on this module's own test classpath.
 * This package is therefore ADDITIVE to that class and never a replacement for
 * it, as the parent test charter rules.
 *
 * <p>Assumptions: what the shared class cannot do for this module is the reason
 * this package exists, and it rests on three properties of that class that are
 * recorded in its own charter rather than visible from a call site. First, one
 * class runs in nine modules, so it has to tolerate a rule whose input set is
 * empty -- the shared kernel itself has no {@code domain} package at all, and
 * ArchUnit fails a rule whose expectation saw no classes unless the rule says
 * otherwise. An empty-tolerant rule passes when it matches nothing, so on its
 * own it cannot separate "no violation here" from "no subjects here", and the
 * shared charter names that as the one way this gate weakens without any build
 * turning red. Second, the {@code architecture-rules} execution narrows its
 * scan with an {@code includes} element naming {@code LayeringRulesTest} alone,
 * and a module cannot widen that back, because Maven resolves an execution's
 * own configuration ahead of the plugin-level configuration a module declares.
 * Third, the shared architecture package is a closed set of two files that
 * admits no second rule class, on the stated ground that a rule split across
 * two files can be weakened by editing the one that reads as maintenance. Taken
 * together those three leave a module-scoped invariant with nowhere in the
 * shared artifact to live, and leave the shared families with no way to assert
 * that they matched anything here. Both gaps are closed inside this package or
 * not at all.
 *
 * <p>Refactoring Rationale: an earlier statement of this same reasoning is
 * deliberately NOT repeated here, and the correction is recorded rather than
 * silently applied. That statement held that the shared rules could not be
 * delivered as a dependency at all, because the shared kernel bound no
 * {@code test-jar} goal, so its rules class reached only its own
 * {@code target/test-classes} and a {@code <type>test-jar</type>} dependency on
 * it would not resolve. That was true of an earlier state of the reactor and is
 * false of this one: the goal is bound now, and
 * {@code services/transaction-service/pom.xml} carries its own note recording
 * the same supersession beside the dependency that consumes the artifact. Two
 * consequences follow. The reason this package exists is the vacuity and
 * narrowing argument above, not an unreachable dependency. And a charter that
 * asserted the old premise would contradict three descriptors and two sibling
 * charters that a reader can check in a minute, which is the failure mode the
 * Explainability rule's line 41 is aimed at: a rationale that reads as specific
 * while being wrong is worse than none, because the next reader trusts it
 * precisely because it names a mechanism.
 *
 * <p>Assumptions: the goal's binding phase is part of why the premise changed,
 * and it is worth knowing when reading a green build. The shared kernel binds
 * {@code test-jar} at {@code process-test-classes} rather than at that goal's
 * default {@code package} phase, because Surefire's dependency scanner reads
 * only files whose name ends in {@code .jar}; an unpackaged reactor dependency
 * is satisfied from a directory, which that scanner ignores in silence. Under
 * the default binding a plain {@code mvn test} at the reactor root would run no
 * shared rule at all while reporting success.
 *
 * <h2>The reserved simple name is a contract, not a style</h2>
 *
 * <p>The rules class here is named {@code TransactionLayeringRulesTest}. It
 * must never be a second class named {@code LayeringRulesTest}. That simple
 * name is already taken by the shared class cited above, and the parent test
 * charter records the same reservation.
 *
 * <p>Assumptions: the reservation is load-bearing against a literal string. The
 * {@code architecture-rules} Surefire execution in {@code services/pom.xml}
 * selects the shared class through an {@code includes} element whose pattern
 * ends in {@code LayeringRulesTest.java} -- a simple name written as a literal,
 * with no package qualifier to tell two classes apart. A second class of that
 * simple name in this module would be matched by that same pattern, so an
 * execution meant to run one shared class would run two different classes whose
 * reports are distinguishable only by the module they ran in, and a reader
 * could no longer tell which of the two produced a given result. A distinct
 * simple name keeps the inherited gate and this module's own gate separable in
 * the runner, in the report and in the log. The shared kernel's charter treats
 * that class name and its directory as a fixed external contract for the same
 * reason, and this package honours it from the other side.
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
 * from the gate's presence. The parent test charter states the failure they
 * exist to rule out: a gate nobody has watched fail is indistinguishable from a
 * gate with no subjects.
 *
 * <p>Assumptions: that indistinguishability is not hypothetical here, it is the
 * exact consequence of the empty-set tolerance described above, because a rule
 * carrying that tolerance reports the same pass whether it examined every class
 * in the module or none of them. A family that silently matched nothing and a
 * family that genuinely found nothing to complain about therefore emit an
 * identical result, and the likeliest repair a hurried reader reaches for when
 * a layering gate fails noisily is to stop running it. Proving the negative
 * once, in the build, removes the ambiguity in the direction that matters: a
 * proof test that stops failing its violating subject is itself a failure, so
 * the erosion becomes visible instead of quiet.
 *
 * <p>Assumptions: {@code KeysetPaginationGateProofTest} guards a boundary this
 * module owns rather than a general one, which is why it belongs here and not
 * in the shared artifact. The list screens of this context page by key and
 * never by offset, and that is what the baseline does rather than an
 * improvement on it. {@code app/cbl/COTRN00C.cbl} fills exactly ten screen rows
 * under a loop that runs until its index reaches 11 at line 297, and then
 * performs an ELEVENTH read at line 308 whose outcome alone sets the next-page
 * indicator at lines 310 and 312; {@code app/cbl/COCRDLIC.cbl} carries the
 * browse cursor across a screen turn as a last-key and a first-key pair with a
 * next-page indicator beside them, at lines 229 to 244. The cursor in the
 * baseline is therefore already a keyset cursor. Under offset paging,
 * concurrent inserts silently skip and repeat rows because the offset is
 * counted against a result set that has changed, so substituting one would
 * change observable behaviour and not merely the implementation.
 *
 * <h2>Which runner executes these classes, and where their reports land</h2>
 *
 * <p>All three classes in this package end in {@code Test}, so Surefire
 * collects them and they run at the {@code test} phase. The
 * {@code RepositoryIT} classes of the sibling {@code repository} package end in
 * {@code IT}, so Failsafe collects those and runs them at
 * {@code integration-test} with their result asserted at {@code verify}. The
 * full statement of that split, and of why the suffix rather than a second
 * source root carries it, belongs to the parent test charter and is cited
 * rather than restated.
 *
 * <p>Assumptions: the consequence for this package is a prohibition. Reports
 * land in the DEFAULT directories,
 * {@code services/transaction-service/target/surefire-reports} and
 * {@code services/transaction-service/target/failsafe-reports}, which are the
 * paths the continuous integration pipeline collects from. No class in this
 * package may set a reports directory, no module-level configuration may be
 * added that relocates or renames either one, and there is no separate
 * {@code src/it} source root to add. Relocating either directory would leave
 * the build green while the pipeline published nothing, which is the one
 * failure mode here that looks like success.
 *
 * <h2>The closed set of files in this directory</h2>
 *
 * <p>Target contract: four {@code .java} files and no others -- this charter,
 * {@code TransactionLayeringRulesTest}, {@code MoneyPathGateProofTest} and
 * {@code KeysetPaginationGateProofTest} -- and no subdirectory beneath it. This
 * charter is one of the six the parent test charter's count canon admits across
 * this test subtree, so the figure here can be re-checked against that one
 * rather than argued again.
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
 * <h2>The gate over this file, and the bar on relaxing it</h2>
 *
 * <p>Assumptions: this charter is required rather than decorative, and the
 * requirement is mechanical as well as textual. The Explainability rule's scope
 * clause at its line 15 attaches the docstring obligation to a module entry
 * point, which in Java is the package declaration, and
 * {@code package-info.java} is the only compilation unit able to carry
 * package-level Javadoc; the rule states no exemption for test sources anywhere
 * in its text. The inherited gate agrees:
 * {@code mvn -f services/transaction-service/pom.xml help:effective-pom}
 * resolves {@code includeTestSourceDirectory} to {@code true} on the Checkstyle
 * execution bound to the Maven {@code validate} phase, and
 * {@code config/checkstyle/suppressions.xml} confines its whole charter to
 * generated sources and test fixture material, declining to suppress
 * {@code src/test/java} at all. This directory is governed in full.
 *
 * <p>Assumptions: the checks that bear on this file are named here rather than
 * counted, because published descriptions of this ruleset disagree with one
 * another about how many modules it holds, and a tally that can be contradicted
 * is worth less to a reader than the names it was summarising. None of the
 * three named next is redundant on the others. {@code JavadocPackage} requires
 * a {@code package-info.java} to EXIST in any directory holding audited source.
 * {@code MissingJavadocPackage} requires that file to CARRY Javadoc on its
 * package declaration, so a charter reduced to a bare package statement
 * satisfies the first and fails this one, which is why an empty file is not a
 * way to clear the first. And {@code SummaryJavadoc} requires a first sentence
 * that ends with a period, because that module's sentence-terminator property
 * is left at its default, while the same check rejects placeholder markers and
 * the rule's own two examples of a vague rationale outright.
 *
 * <p>Assumptions: there is no in-code escape from that gate. None of the three
 * comment-driven or annotation-driven suppression filters is enabled in
 * {@code config/checkstyle/checkstyle.xml}, so neither a marker comment nor an
 * annotation suppresses anything; a suppression has to be a durable entry in
 * the companion file, whose charter does not reach test sources. Skipping the
 * plugin, lowering its violation threshold, tolerating its failure or pointing
 * it at another suppressions file are therefore all breaches of the rule rather
 * than build-configuration choices, and none is available to this package.
 *
 * <p>Assumptions: the gate here is BINARY -- a Maven, JUnit, Checkstyle or
 * ArchUnit outcome passes or it fails. The graded condition-code rubric under
 * which a warning-level result is a green state belongs exclusively to the
 * COBOL parity oracle under {@code tests/}, where it exists because two
 * baseline programs carry a record-key defect in immutable reference source
 * that is out of scope for this migration. No graded tolerance, no warning tier
 * and no arithmetic on a return code may be introduced in this package; no
 * outcome of this module's build may be described in those terms; and this gate
 * is never wired into the oracle's own workflow, which is reference material
 * like the suite it runs. Importing that rubric here would let a failed
 * layering assertion be reported as an acceptable outcome, which is the one
 * thing a gate must never be able to do.
 *
 * <h2>Citation discipline, and the two forms it takes in this file</h2>
 *
 * <p>Assumptions: every path and line number written above was verified on disk
 * before it was written, never recalled and never inferred from a neighbouring
 * file. An invented citation is exactly the unsupported claim the
 * Explainability rule forbids, and it is worse in a test tree than elsewhere,
 * because a comment naming a line that does not say what the comment claims
 * will be trusted by the next reader precisely because it looks specific.
 *
 * <p>Trade-offs: the citations here deliberately take two different shapes, and
 * the split is by whether the cited file can move. Everything under
 * {@code app/} is immutable reference material for this migration, so those
 * citations carry line numbers and will keep resolving. The build descriptors
 * under {@code services/} are not immutable, so they are cited by the
 * identifiers they declare -- an execution id, a goal, an element name --
 * rather than by line, on the ground that a line-numbered citation into a file
 * still being extended decays into a confident reference to the wrong line. The
 * cost is that a reader has to search a descriptor for the named element
 * instead of jumping straight to it; what it buys is a citation that survives
 * the next edit to that descriptor.
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
 * <h2>Authoring notes for this file</h2>
 *
 * <p>Assumptions: the four rationale labels used above -- Alternatives
 * Considered, Refactoring Rationale, Assumptions and Trade-offs -- are TYPED in
 * their plural, unparenthesised, colon-terminated form and are never copied out
 * of {@code tests/README.md}. That file carries a non-breaking hyphen 106 times
 * across 77 lines, and its own list of the four categories in its section 12
 * renders the compromise label with that character rather than with an ordinary
 * hyphen. A non-breaking hyphen is indistinguishable from an ordinary one on
 * screen while behaving differently in a search, so a label copied from there
 * becomes a token that a search for the category fails to find -- which makes a
 * rationale that was genuinely written read as absent to the audit looking for
 * it. {@code docs/CODE_DOCUMENTATION_STANDARD.md} carries the full statement of
 * the convention, including its ban on emphasis markup around a label, and is
 * cited rather than restated; where that written standard and the linter
 * configuration disagree, the linter configuration is authoritative, since it
 * is the one that fails the build.
 *
 * <p>Trade-offs: this file is pure ASCII with no byte-order mark and Unix line
 * endings. The alternative was to reproduce the typographic punctuation the
 * migration prose uses, which would read closer to that prose; ASCII was chosen
 * because it makes the non-breaking-hyphen failure described above unreachable
 * in this file by construction rather than by care. The cost is plainer
 * punctuation, so a double hyphen stands in for a dash throughout.
 *
 * <p>Trade-offs: this file holds one Javadoc block and one package declaration
 * and nothing else -- no type, no annotation, no import, no field and no line
 * comment. Because there are no statements to sit beside, the rationale the
 * rule's inline-comment half requires is carried inside this Javadoc as
 * labelled sentences; the twin-comment idiom this tree uses for command blocks
 * has no place inside a Javadoc block. The file also carries no authorship,
 * version or release-marker at-clause, because the ruleset omits the whole
 * Javadoc-formatting family that would ask for them and the version control
 * history answers those three questions more reliably than a comment maintained
 * by hand. Prose is wrapped at 80 columns to match the sibling charters in this
 * tree, even though no line-length check is enabled; the few lines that exceed
 * it are inline code spans holding paths that cannot be broken, because a line
 * break inside one would insert this comment's margin into the rendered path.
 */
package com.carddemo.transaction.architecture;
