/**
 * Owns the single build-enforced ArchUnit gate that every migrated CardDemo service module inherits.
 *
 * <p>Three invariants of the migrated decomposition are asserted here as an executable test rather
 * than described as a convention: a {@code ..domain..} type may not reach for an AWS SDK, web or
 * servlet type; no service may import another service's domain package; and no binary
 * floating-point type may appear anywhere in the money path. They are grouped in one package because
 * they share the property that makes a convention insufficient for any of them. Each is invisible at
 * the point where it is broken. Nothing fails to compile when a domain class quietly imports a queue
 * client, and nothing fails at run time until a rounded amount reaches a statement, so a boundary
 * can be crossed by a change that reviews cleanly and then stays crossed for as long as nobody
 * re-reads the design.</p>
 *
 * <h2>Domain isolation from AWS SDK, web and servlet types</h2>
 *
 * <p>A {@code ..domain..} package holds the entities the migration derives from the reference record
 * layouts, and it is the one layer that has to stay expressible without a transport or an
 * infrastructure client. Under the alternative, a domain class that imports a queue or servlet type
 * can no longer be exercised without standing up the thing it imported: a unit test of a posting
 * rule becomes an integration test of a queue client, and the rule stops being what the test
 * actually measures. The prohibition also runs in the direction that matters most for a migration.
 * Business logic transcribed from the baseline is the asset being preserved, so it is deliberately
 * kept clear of the parts of the target platform that are the likeliest to be replaced.</p>
 *
 * <h2>Cross-service domain isolation across the nine fixed package roots</h2>
 *
 * <p>The package roots are fixed at {@code com.carddemo.common} together with the eight service
 * roots {@code auth}, {@code account}, {@code card}, {@code transaction}, {@code reference},
 * {@code batch}, {@code authorization} and {@code reporting}. Because they are fixed, a
 * cross-service import is detectable from a package name alone, with no annotation, registry or
 * module descriptor to keep in step with the source. Only {@code com.carddemo.common} is shared, and
 * it is shared deliberately, being the Java analogue of compiling every reference program against
 * one copybook include path. Under the alternative, one service reads another's domain type directly
 * and the table behind that type acquires a second owner, which is precisely the failure the bounded
 * contexts of this migration were drawn to prevent.</p>
 *
 * <h2>No binary floating-point type in the money path</h2>
 *
 * <p>Money in the reference baseline is exact fixed point, so migrated code carries it as a scaled
 * decimal and never as {@code float} or {@code double}. This invariant is enforced mechanically
 * because the failure it prevents is silent rather than loud: a binary floating-point type cannot
 * represent most exact cent values, so an amount routed through one comes back plausible and
 * slightly wrong. It then surfaces at the far end of the pipeline as an unexplained difference of a
 * single cent against a golden master, rather than as an error at the conversion that caused it. A
 * rule naming the forbidden types is the only form of this constraint that reports the cause instead
 * of the symptom.</p>
 *
 * <h2>Decision record</h2>
 *
 * <p>Refactoring Rationale: these three invariants were carried as design prose before this package
 * existed, stated in the build descriptors and the architecture documents and enforced by reading.
 * Prose cannot fail a build, so under that arrangement a violation survived exactly as long as no
 * reviewer happened to recall the constraint, and the documents drifted from the code they described
 * with nothing able to detect the drift. Moving the invariants into an executable test in one owned
 * location replaces recollection with a build step, so the rule and the code it constrains are
 * checked against each other on every run. This descriptor is what makes that ownership discoverable
 * from the source tree, rather than inferable only from a specification held outside it.</p>
 *
 * <p>Alternatives Considered: two other placements were evaluated. Checkstyle's
 * {@code ImportControl} module would express the two import prohibitions through the documentation
 * gate this build already configures, needing no further dependency, and it is deliberately absent
 * from the shared ruleset for two reasons. It cannot express the money-type prohibition at all, so
 * one charter would end up split across two engines; and once two engines enforce overlapping halves
 * of a single constraint, a reader can no longer tell which of them owns a given boundary, and it is
 * whichever is cheaper to silence that gets silenced. Distributing the rules instead, each into the
 * module it constrains, was rejected because the layering invariants apply identically to all eight
 * services: eight copies drift, and a constraint held in eight places can be relaxed in one without
 * the other seven noticing. Keeping one copy in the first module of the reactor, which all eight
 * depend on, makes inheritance the delivery mechanism.</p>
 *
 * <p>Assumptions: this descriptor exists solely because the effective Maven Checkstyle configuration
 * audits test sources, which brings the file-level package documentation checks to bear on this
 * directory exactly as they bear on the main source tree. {@code services/pom.xml} sets
 * {@code includeTestSourceDirectory} on the Checkstyle execution it binds to the Maven
 * {@code validate} phase, and {@code mvn -f services/common-lib/pom.xml help:effective-pom} resolves
 * that setting to {@code true} while resolving the audited test source root to
 * {@code src/test/java}, so this directory is governed in full. The ruleset then acts on this file
 * through a pair of checks of which neither is redundant: {@code JavadocPackage} requires a
 * {@code package-info.java} to exist in any directory holding audited source, while
 * {@code MissingJavadocPackage} requires that file to carry Javadoc on its package declaration. An
 * empty descriptor, or one holding nothing but an ordinary block comment, satisfies the first and
 * fails the second, so an empty {@code package-info.java} is not an acceptable way to clear the
 * first check. A package declaration is also the module entry point the project Explainability rule
 * names, and {@code package-info.java} is the only compilation unit able to carry package-level
 * Javadoc, so this file is required rather than decorative. That rule's parameter, return value and
 * exception elements describe callable code and have no counterpart on a package declaration, so
 * they are omitted here deliberately rather than written out empty; an at-clause carrying no
 * description would itself be reported by {@code NonEmptyAtclauseDescription}.</p>
 *
 * <p>Trade-offs: two compromises are accepted knowingly. Auditing test sources on the same terms as
 * main sources means this descriptor, and every fixture builder in the tree, has to be documented;
 * that cost is accepted because the tests in this migration are where the parity contract with the
 * reference baseline is actually written down, which makes them the classes a later reader most
 * needs explained. The second is narrower, and it is the one way this gate can be weakened without
 * failing: the invariants are matched by package name, so renaming a package out of the
 * {@code ..domain..} shape stops the rule matching it, and the build stays green while the boundary
 * that rule protected quietly disappears. That is tolerated because the nine roots are fixed by the
 * migration and restated in the build descriptors, which makes such a rename a visible edit to a
 * declared contract rather than an unremarked refactor.</p>
 *
 * <h2>Contents of this package</h2>
 *
 * <p>Two {@code .java} files and no others: this descriptor, and {@code LayeringRulesTest}, which
 * holds the invariants described above. The package deliberately carries no helper, no base class,
 * no second rule class, no fixture and no resource, because a rule split between a class and a
 * helper can be weakened by editing the helper, where the change reads as maintenance rather than as
 * the relaxation of an architectural constraint that it is. The class name and this directory are
 * treated as a fixed external contract rather than an internal detail, since the build descriptors
 * name both and the services pipeline may invoke that class by name as a labelled step of its
 * own.</p>
 *
 * <h2>Why no reference baseline artifact is cited above</h2>
 *
 * <p>Assumptions: every other package in this migration derives from a named baseline artifact, a
 * copybook, a program, a mapset or a job, and cites it. This one cites none, and that absence is a
 * finding rather than an oversight. The invariants here are properties of the target decomposition
 * into separately deployable contexts, and the baseline had no such decomposition to violate: its
 * programs shared one address space and one set of files, so no service boundary existed there to be
 * crossed and no copybook describes one. There is consequently nothing in the baseline to cite, and
 * attaching an arbitrary program reference to close the apparent gap would assert a lineage that
 * does not exist.</p>
 *
 * <p>Assumptions: the reference suite at the repository root is the functional parity oracle for
 * this migration. It is read as evidence, never modified, and nothing in this package stands in for
 * it. That division of labour matters most when a result is being interpreted, because this gate
 * constrains the shape of the migrated Java and asserts nothing whatever about what it computes. A
 * green run here is therefore not evidence of parity: an implementation can honour every boundary
 * described above, pass this gate, and still return the wrong cent, which that suite would catch
 * and this one never will. Reading a passing architecture gate as a passing migration is the one
 * mistake this paragraph exists to prevent.</p>
 */
package com.carddemo.common.architecture;
