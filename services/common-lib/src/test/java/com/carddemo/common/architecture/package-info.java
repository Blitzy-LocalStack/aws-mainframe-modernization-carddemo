/**
 * Owns the single build-enforced ArchUnit gate that every migrated CardDemo service module is held
 * to.
 *
 * <h2>Contract, and the tree state that satisfies it</h2>
 *
 * <p>Assumptions: this charter was authored one checkpoint ahead of the class it governs, so it
 * originally described a <b>target contract</b> rather than a measurement of the directory. That gap
 * is now closed. {@code LayeringRulesTest} is present beside this file, Surefire runs its six test
 * methods in this module, the jar plugin packages it into
 * {@code common-lib-<version>-tests.jar}, and the {@code architecture-rules} execution in
 * {@code services/pom.xml} re-runs it inside every module that consumes the shared kernel. Every
 * inventory and count below is therefore a description of files that exist, and
 * {@code mvn -f services/pom.xml test} is the authority that cannot go stale.</p>
 *
 * <p>Refactoring Rationale: while the class was absent this section had to warn a reader not to read
 * the inventory as present tense. Leaving that warning in place once the class landed would be worse
 * than having no warning at all, because a reader who believes a gate enforces nothing stops looking
 * for the enforcement that is in fact running, and the whole point of an executable invariant is
 * that a reader can trust it without re-deriving it.</p>
 *
 * <p>Alternatives Considered: withholding this charter until every class it governs
 * exists. Rejected, because the charter is what the authors of those classes work
 * from -- which type belongs here, which may not, what the closed set is -- so
 * writing it last would leave the package with no stated contract during exactly
 * the interval in which one is needed. The cost of authoring it first was that its
 * inventory read as present tense before it was true, which the paragraph above
 * now resolves in the only durable way: by the class being there.</p>
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
 * the other seven noticing. One copy in the first module of the reactor is what this package keeps,
 * and the section below is how that copy reaches the other eight.</p>
 *
 * <h2>How this one copy reaches the eight service modules</h2>
 *
 * <p>Refactoring Rationale: the build descriptors, and this file, previously described the rules as
 * <i>inherited</i> by every module because all eight depend on this one. Depending on a module
 * inherits nothing of its tests. Maven resolution hands a module's <b>main</b> classes to its
 * consumers and never its test classes, so a rule class living here was compiled once, evaluated
 * against this module alone, and never saw a single service class; each service POM carried the
 * ArchUnit engine, which is an assertion API with no rules in it. Every invariant described above was
 * therefore authored and unenforced outside this module, and a build that checked nothing reported
 * success. Three coupled declarations replace that inheritance claim with an execution, and all three
 * are load bearing:</p>
 *
 * <ul>
 *   <li>{@code services/common-lib/pom.xml} binds the jar plugin's test-jar goal, narrowed to this
 *       package, so this one class is published as an artifact. It is bound before the
 *       {@code package} phase deliberately, because Surefire's dependency scanner reads only files
 *       whose name ends in {@code .jar}, and an unpackaged reactor dependency resolves to a
 *       directory that the scanner passes over in silence.</li>
 *   <li>each of the eight service POMs declares that test artifact at test scope, its version and
 *       scope managed centrally so no module can widen the engine onto a production classpath.</li>
 *   <li>{@code services/pom.xml} declares a Surefire execution, {@code architecture-rules}, whose
 *       {@code dependenciesToScan} collects test classes from that artifact and runs them on the
 *       consuming module's own test classpath, with an include that narrows the scan to
 *       {@code LayeringRulesTest} alone.</li>
 * </ul>
 *
 * <p>Assumptions: the third declaration is what makes the rules meaningful rather than merely
 * present. A rule about a package can only be evaluated against classes an importer can see, and
 * running the class inside the consuming module is what puts that module's compiled classes in front
 * of it. Running it here instead would put only this module's classes there, which is the arrangement
 * that produced the gap.</p>
 *
 * <p>Assumptions: two obligations fall on {@code LayeringRulesTest} as a consequence, and both are
 * recorded here because neither is visible from the class itself. Its analysed packages must be
 * expressed so that the classes of whichever module is executing it are imported, rather than a fixed
 * single package root that would resolve to this module wherever it ran. And it must tolerate a rule
 * whose input set is empty, because the same class runs in nine modules and a given invariant does
 * not match a class in all of them: this module has no {@code domain} package at all, and ArchUnit
 * fails a rule whose {@code should} clause saw no classes unless the rule says otherwise. Without
 * that tolerance the gate would fail everywhere for a reason that has nothing to do with a violated
 * boundary, and the likeliest repair a hurried reader reaches for is to stop running the rules.</p>
 *
 * <p>Alternatives Considered: a dedicated aggregator module depending on all eight services and
 * running these rules once over a combined classpath was evaluated as a way to avoid publishing a
 * test artifact. It was rejected for two reasons. A violation would be reported against that module
 * rather than against the service that introduced it, so the failure would name the wrong owner; and
 * the module would need a Maven edge to all eight contexts, making it the only artifact in the
 * reactor coupled to every one of them, which is the coupling these very rules exist to forbid.</p>
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
 * <pre>
 * this directory: 13 java files = 12 tests + 1 charter
 * </pre>
 *
 * <ul>
 *   <li>{@code LayeringRulesTest} across 10 cases -- the layering invariants described above, together
 *       with the cases that guard against a vacuous pass. Assumptions: this is the ONE class the
 *       {@code architecture-rules} Surefire execution carries into the eight consumers of the shared
 *       kernel, so its nine cases run once here and once in each of them.</li>
 *   <li>{@code PackageCharterInventoryTest} across 4 cases -- holds every measured claim a package
 *       charter publishes to the directory the charter lives in.</li>
 *   <li>{@code SharedKernelInventoryTest} across 12 cases -- re-derives this module's class inventory
 *       from the directory and holds the kernel's own charters, roster and README to it.</li>
 *   <li>{@code ServiceCatalogInventoryTest} across 4 cases -- re-derives the service catalog's
 *       measurable current-state claims from the repository.</li>
 *   <li>{@code RuntimeConfigurationContractTest} across 5 cases -- asserts that every runtime setting a
 *       service requires is one the infrastructure delivers, the converse, and that every such setting is
 *       named by the Dockerfile header an operator reads. The fifth case is the image-closure one: an
 *       image header is the only place a task definition's variables are written down, so a header that
 *       omits a no-fallback name presents a contract an operator can follow and still get a task that
 *       will not stay up.</li>
 *   <li>{@code RuntimeDeletePrivilegeContractTest} across 2 cases -- asserts that every row-deleting
 *       call site is one the database permits, and that no table carries a delete grant nothing
 *       uses.</li>
 *   <li>{@code CrossSchemaPrivilegeContractTest} across 5 cases -- asserts that the batch role's
 *       cross-schema grants are exactly the schemas AND TABLES its code can reach. THREE govern the
 *       schema tier: no schema is granted that the service's own {@code search_path} omits, no schema
 *       on that path is left ungranted, and every schema an entity explicitly maps to is granted. TWO
 *       govern the table tier, which the schema tier cannot see: exactly one account-owned migration
 *       grants {@code UPDATE} on {@code account.accounts} to the batch role, and no other account
 *       table -- and no schema-wide or default privilege -- carries an account write.
 *       <p>Refactoring Rationale: the table tier was added because the schema tier passed while
 *       posting could not write the account master at all. The bootstrap document granted that
 *       privilege behind a guard testing for a table the service migrations had not yet created, so
 *       the guard was false on every first deployment; a text-level assertion over schema USAGE
 *       cannot detect that, which is why the definitive check is the live-engine
 *       {@code BatchAccountWriteGrantIT} in {@code account-service} and this pair is the cheap
 *       text-level companion to it.</p></li>
 *   <li>{@code ServiceReadmeInventoryTest} across 6 cases -- holds every inventory a service README
 *       publishes to that module's own trees, so a class added or removed cannot leave a stale count
 *       standing in prose. THREE govern the test census: the two published tiers, the total stated beside
 *       them, and an anti-vacuity floor over the READMEs that opt in. TWO govern the production tree: a
 *       published inventory of production types, package charters and packages, and a file listing a
 *       README delimits and calls closed, held to the tree in BOTH directions -- nothing listed that is
 *       absent, nothing present that is unlisted. The sixth refuses the return of a withdrawn second
 *       census marker. ⚠️ Refactoring Rationale: the production pair joined it because a review found
 *       the shared kernel's own README stating three production figures that disagreed with each other
 *       and with the tree, beside a note claiming its listing was closed while eight classes were absent
 *       from it -- two of them production security controls, which reads as two controls that do not
 *       exist rather than as two missing lines. The drift recurs in the direction the test census cannot
 *       see: a stale test census understates COVERAGE, which reads as a gap, while a stale production
 *       inventory understates the SURFACE. ⚠️ Refactoring Rationale: the sixth case exists because a
 *       SECOND production-census marker was published for a time and is withdrawn -- it restated this
 *       one's census in another spelling, so a README stated one census twice with nothing checking the
 *       two statements against each other. Its pattern and its case are gone, so a README reintroducing
 *       it would be measured by nothing at all, which is strictly worse than the redundancy the
 *       withdrawal removed; that case is what makes the removal hold.</li>
 *   <li>{@code DiagnosticRenderingRulesTest} across 3 cases -- holds every production record that carries
 *       a protected or unbounded component to declaring its own {@code toString()}, and refuses the one
 *       misuse the review found of the shared card masker: applying it to an account or customer
 *       identifier, which that function abbreviates to its last four characters while the observability
 *       contract requires such a value to be omitted. Assumptions: this class reads the SOURCE of all nine
 *       modules rather than importing the executing module's classes, and it therefore runs ONCE -- the
 *       {@code architecture-rules} execution carries only {@code LayeringRulesTest} into the services, so a
 *       classpath-based rule hosted here would have gated the kernel alone while reading as though it gated
 *       every module. ⚠️ Refactoring
 *       Rationale: it joined this package because the prose rule in
 *       {@code docs/architecture/observability.md} had no executable counterpart, and a review found
 *       twenty-four records across seven modules relying on the rendering the compiler generates -- one of
 *       them carrying a primary account number, an account identifier, a name, a six-component address, a
 *       credit score and a balance together.</li>
 *   <li>{@code PublishedContractClosureTest} across 8 cases -- holds all seven published contracts to the
 *       protocol outcomes the shared runtime produces and to one canonical error-model facet set: 405 on
 *       every operation, 406 wherever a body is returned, 413 and 415 wherever one is accepted, the
 *       correlation contract on both halves of every operation and in BOTH forms a response is published
 *       in -- inline and as a shared component, which are separate cases because 438 of the 555 published
 *       responses are references and a component that omits the header omits it from every one of them --
 *       a row ceiling on every page schema -- recognised by the keyset SHAPE rather than by a name suffix,
 *       because one service publishes its envelope as {@code PageResponse} -- one set of widths and bounds on
 *       the problem document, and the one narrowing that separates the correlation header from the body
 *       member that carries the empty string when no identity was supplied, and the closure of every
 *       response schema's {@code required} list over its own properties -- the pinned
 *       {@code default-property-inclusion} writes every record component, so a published property is never
 *       absent and a member that may hold no value is required AND nullable rather than optional; that
 *       eighth case is structural rather than textual because a search of the seven documents for the word
 *       "absent" returns thirty hits of which nearly all are legitimate, and a rule needing an exemption
 *       list to stay green is not a rule. Assumptions: it reads the DOCUMENTS from the
 *       filesystem rather than a generated client, so it runs once here and covers all seven; and it
 *       asserts publication only -- the runtime half is asserted by {@code ProtocolRefusalRenderingTest}
 *       and {@code RequestBodySizeFilterTest} in this module, and the two together are what make a
 *       published document a description of behaviour. ⚠️ Refactoring Rationale: it joined this package
 *       because a review measured 405 published on 10 operations of 61, 406 on 5, 415 on 5 of the 31 that
 *       accept a body and 413 on none -- while every one of those refusals was already produced centrally
 *       for every route.</li>
 *   <li>{@code ReleasedMigrationImmutabilityTest} across 2 cases -- holds every released Flyway
 *       migration in the repository to the exact bytes, and the exact Flyway checksum, it was released
 *       with, and holds the release record to the migration tree so a new migration cannot arrive
 *       unrecorded. Assumptions: it guards the UPGRADE path, which no other check in this reactor can
 *       see. Flyway's checksum covers a whole migration file, comments included, so an edit to an
 *       already-applied one makes every environment that ran it refuse to start under
 *       {@code validate-on-migrate} -- while every test that applies migrations from scratch stays
 *       green, because a fresh database has no recorded checksum to disagree with. ⚠️ Refactoring
 *       Rationale: it joined this package because that is exactly what happened. Two applied
 *       migrations were later edited to change only prose, and one of them was measured refusing to
 *       start with "Migration checksum mismatch for migration version 1: applied 561195120 / resolved
 *       -1455475555". Assumptions: it reads the repository TREE rather than the executing module's
 *       classpath, for the reason {@code DiagnosticRenderingRulesTest} states -- the rule covers all
 *       seven owning modules and only a tree-reading check hosted once can say so truthfully.</li>
 *   <li>{@code ApplicationContextWiringContractTest} across 3 cases -- asserts that each deployable
 *       could actually refresh its context, in the three ways one of them could not: a collaborator a
 *       module injects but never publishes, a component declaring two constructors and marking neither,
 *       and a module injecting the HTTP client builder without the Spring Boot 4 starter that publishes
 *       it. Assumptions: all three defects were found by RUNNING a bootable jar and none by any test,
 *       because every context-loading test in this reactor defines a narrow configuration of its own
 *       rather than the application -- which is correct for what those tests assert and is exactly why
 *       they cannot see production wiring.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: this section said two {@code .java} files and no others, and that
 * {@code LayeringRulesTest} declared six cases. Both figures are superseded: NINE further
 * repository-reading checks have landed beside it and the rule class now declares nine. The
 * nine are here rather than in each module for the reason the layering rules are -- the check is
 * identical everywhere, so nine copies would be nine files to keep in step -- and they read repository
 * FILES rather than importing service types, so the kernel's inward-only dependency rule is untouched.
 * The marker line above is re-measured against this directory by
 * {@code PackageCharterInventoryTest} itself, and each declared case count is re-measured against the
 * class it names, so this inventory cannot go stale again without failing.</p>
 *
 * <p>Assumptions: {@code LayeringRulesTest} remains the only class here carried into other modules, and
 * the package still deliberately carries no helper, no base class, no fixture and no resource, because a
 * rule split between a class and a helper can be
 * weakened by editing the helper, where the change reads as maintenance rather than as the
 * relaxation of an architectural constraint that it is. That class's name and this directory are
 * treated as a fixed external contract rather than an internal detail, since the build descriptors
 * name both and the services pipeline may invoke that class by name as a labelled step of its
 * own.</p>
 *
 * <p>Assumptions: authored here is not the same as executed here, and the distinction decides what
 * the other nine module descriptors say. Maven carries a module's MAIN classes to its consumers and
 * never its test classes, so single authorship delivers nothing on its own. Delivery is three
 * coupled declarations and all three are present in this reactor: {@code services/common-lib/pom.xml}
 * binds {@code maven-jar-plugin}'s {@code test-jar} goal at {@code process-test-classes}, narrowed by
 * an include to this directory alone; each of the eight service descriptors declares that artifact
 * with {@code <type>test-jar</type>} at test scope; and the {@code architecture-rules} Surefire
 * execution in {@code services/pom.xml} names {@code com.carddemo:common-lib} in
 * {@code dependenciesToScan} so the class is collected from that artifact and run on the consuming
 * module's own test classpath. That last part is the point: the rules see the classes of the module
 * they run in. Each service additionally declares the ArchUnit engine at test scope, because the
 * assertion API has to resolve wherever the class executes. Any one of the three missing leaves the
 * rules authored and unenforced, which is indistinguishable from not having written them.</p>
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
