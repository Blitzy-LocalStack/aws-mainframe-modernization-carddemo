package com.carddemo.common.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the three architectural invariants of the migrated CardDemo decomposition as an executable
 * gate, together with the two guards that keep the gate from passing on an empty graph or against
 * the wrong set of ownership roots.
 *
 * <p>The three gate families are named A1, A2 and A3 throughout this class and in the failure output,
 * so a continuous integration log identifies which boundary was crossed without anyone having to read
 * the source. A1 keeps a {@code ..domain..} package clear of AWS SDK, Spring Web and Jakarta Servlet
 * types. A2 forbids one bounded context from reaching into another's domain model. A3 forbids binary
 * floating point anywhere in the money path.</p>
 *
 * <p>Assumptions: all three invariants are invisible at the point where they are broken, which is why
 * each is a build step rather than a review convention. Nothing fails to compile when a domain entity
 * imports a queue client, and nothing fails at run time until a rounded amount reaches a statement, so
 * a boundary crossed by a change that reviewed cleanly stays crossed until somebody re-reads the
 * design. The package charter beside this class records the same reasoning at greater length.</p>
 *
 * <p>Assumptions: this one class is executed in every module of the reactor, not only in the module
 * that declares it. The shared kernel publishes its architecture package as a test artifact and the
 * {@code architecture-rules} Surefire execution scans that artifact into each service, running it on
 * that service's own test classpath. Two properties of this class follow from that and are load
 * bearing. The analysed root is the whole {@code com.carddemo} namespace resolved from the executing
 * module's classpath, so the classes in front of the rules are the classes of whichever module is
 * running; and A1 and A2 tolerate an empty subject set, because a given invariant does not match a
 * class in all nine modules.</p>
 *
 * <p>Trade-offs: that empty-subject tolerance is granted to A1 and A2 individually and is deliberately
 * not a global setting. An {@code archunit.properties} file switching off empty-subject failure would
 * apply to A3 as well, and A3 is the one rule whose subject set must never be empty: the money package
 * lives in the shared kernel, which is on every module's classpath, so an empty money subject means
 * the import found nothing rather than that there was nothing to find. The non-vacuity guard below
 * covers the same failure from the other direction, for the graph as a whole.</p>
 *
 * <p>Assumptions: this gate constrains the SHAPE of the migrated Java and asserts nothing about what
 * it computes. Functional parity with the reference baseline is owned by the COBOL suite at the
 * repository root, which is read as evidence and never modified. An implementation can honour every
 * boundary here, pass this gate, and still return the wrong cent.</p>
 *
 * <p>Alternatives Considered: grading this gate's outcome the way the reference COBOL suite grades
 * its own, on a mainframe condition-code rubric in which a warning-level result is the current green
 * state. Rejected. That tolerance exists there for a specific and documented reason, namely two
 * baseline programs carrying a record-key defect in immutable source that no compiler flag can fix, and
 * it is what lets that suite report an honest green while naming a blocker it is forbidden to repair.
 * Importing the same tolerance here would buy nothing and would cost the gate its meaning: a crossed
 * boundary would be reported as a tolerated warning, and the build would go green having found a
 * violation. The outcome here is therefore binary, and an architectural or documentation violation
 * fails the Maven build outright.</p>
 */
class LayeringRulesTest {

    /**
     * The package root every rule is evaluated over, resolved from the executing module's classpath.
     *
     * <p>Assumptions: naming the whole namespace rather than one module's package is what makes a
     * single authored class meaningful in nine modules. ArchUnit resolves a package root to every
     * classpath location that carries it, so in a service module this picks up that service's compiled
     * classes alongside the shared kernel it depends on. Naming {@code com.carddemo.common} instead
     * would resolve to the shared kernel wherever it ran, and the eight services would then be gated
     * by a rule that had never seen one of their classes.</p>
     */
    private static final String ANALYSED_ROOT = "com.carddemo";

    /**
     * The shared-kernel ownership root, modelled apart from the eight service roots.
     *
     * <p>Assumptions: the shared kernel is the one root every service may depend on, being the Java
     * analogue of compiling every reference program against one copybook include path. It is held in
     * its own constant rather than inside the service list because the two are treated differently by
     * A2: a dependency INTO this root is always legal, whereas this root acquiring a dependency on a
     * service-owned domain type inverts the reactor's dependency arrow and is a violation.</p>
     */
    private static final String SHARED_KERNEL_ROOT = "com.carddemo.common";

    /**
     * The eight service ownership roots, in the order the reactor builds them.
     *
     * <p>Assumptions: these roots are fixed by the migration and restated in the build descriptors,
     * which is what allows a cross-context dependency to be detected from a package name alone with no
     * annotation, registry or module descriptor to keep in step with the source. Each root owns exactly
     * one bounded context and one database schema, so a type beneath one of them has exactly one
     * owner.</p>
     */
    private static final List<String> SERVICE_ROOTS = List.of(
            "com.carddemo.auth",
            "com.carddemo.account",
            "com.carddemo.card",
            "com.carddemo.transaction",
            "com.carddemo.reference",
            "com.carddemo.batch",
            "com.carddemo.authorization",
            "com.carddemo.reporting");

    /**
     * All nine ownership roots, derived from the two declarations above rather than restated.
     *
     * <p>Refactoring Rationale: an earlier shape of this kind of rule enumerates its roots once per
     * condition. Nine literals repeated across three conditions is nine places for a tenth context to
     * be added in two of them and forgotten in the third, and the resulting gap reports success. Every
     * root string in this class appears exactly once, in {@code SHARED_KERNEL_ROOT} or in
     * {@code SERVICE_ROOTS}, and every ownership decision reads this derived list.</p>
     */
    private static final List<String> OWNERSHIP_ROOTS =
            Stream.concat(Stream.of(SHARED_KERNEL_ROOT), SERVICE_ROOTS.stream()).toList();

    /**
     * The package identifier of this gate's own package, excluded from the analysed set.
     *
     * <p>Assumptions: the shared kernel publishes this package as a classified test artifact so the
     * eight services can run the gate, and a JAR is not a test-output directory. ArchUnit's
     * test-exclusion option matches build output directories only, so without this identifier the
     * class holding the rules would be imported as a subject of the rules in every service. The
     * trailing wildcard covers a subpackage that the package charter forbids, so the exclusion cannot
     * be sidestepped by adding one.</p>
     */
    private static final String OWN_PACKAGE_IDENTIFIER = "com.carddemo.common.architecture..";

    /**
     * The package identifier selecting every domain package, wherever it sits beneath a service root.
     *
     * <p>Assumptions: the leading and trailing wildcards make this a match on a whole package segment
     * rather than on a substring, so {@code com.carddemo.batch.domain} matches while a package named
     * {@code domainservice} does not. That is the property the two import prohibitions rest on.</p>
     */
    private static final String DOMAIN_PACKAGE_IDENTIFIER = "..domain..";

    /**
     * The single package segment that marks a package as holding a bounded context's domain model.
     *
     * <p>Assumptions: A2 resolves ownership itself rather than through a package pattern, so it needs
     * the bare segment as well as the identifier above. It is compared against whole dot-delimited
     * segments and never with a substring test, which is what stops {@code subdomain} or
     * {@code domainevents} from being read as a domain package.</p>
     */
    private static final String DOMAIN_PACKAGE_SEGMENT = "domain";

    /**
     * The package identifier of the money path, the only place A3 applies.
     *
     * <p>Assumptions: exact fixed-point money is a property of this package's types, so the rule is
     * scoped to it and to nothing else. Broadening it to every numeric context in the reactor would
     * reject a legitimate ratio, a percentage or an elapsed-time measurement and would teach a reader
     * to treat the gate as noise, which is how a gate stops working.</p>
     */
    private static final String MONEY_PACKAGE_IDENTIFIER = "com.carddemo.common.money..";

    /**
     * The infrastructure and transport package roots a domain type may not reach for.
     *
     * <p>Alternatives Considered: banning all of {@code org.springframework..} and all of
     * {@code jakarta..} from domain packages was evaluated and rejected. The domain entities of this
     * migration are JPA mappings of the reference record layouts, so they legitimately carry
     * {@code jakarta.persistence} annotations, and a service's configuration, web and error adapters
     * legitimately carry Spring Web and Servlet types outside any domain package. A wider ban would
     * reject the persistence mapping the entities are built from and would exceed the boundary this
     * rule is drawn to protect.</p>
     *
     * <p>Assumptions: both AWS SDK generations are named because both are importable. The v2 artifacts
     * publish under {@code software.amazon.awssdk} and the v1 artifacts under {@code com.amazonaws},
     * and naming only the current one would leave the older coordinate as an unguarded route into a
     * domain package.</p>
     */
    private static final List<String> FORBIDDEN_INFRASTRUCTURE_PACKAGE_IDENTIFIERS = List.of(
            "software.amazon.awssdk..",
            "com.amazonaws..",
            "org.springframework.web..",
            "jakarta.servlet..");

    /**
     * The four binary floating-point type names forbidden throughout the money path.
     *
     * <p>Assumptions: the names are the ones ArchUnit reports for a raw type, so a primitive appears
     * as its keyword and a wrapper as its fully qualified name. Both forms are listed because boxing
     * loses exactness exactly as the primitive does while reading as a different type in source.</p>
     *
     * <p>Assumptions: this class is the only file in the money subtree permitted to contain these
     * tokens. Naming them in a production money type in order to make the rule reachable would put the
     * prohibited construct into the very package the rule protects.</p>
     */
    private static final Set<String> FORBIDDEN_BINARY_FLOATING_POINT_TYPES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    /**
     * The simple name of a compiled package descriptor, which carries no member for a rule to inspect.
     *
     * <p>Assumptions: ArchUnit imports a {@code package-info} class like any other, so a package
     * holding nothing but its own charter still yields one imported class. The non-vacuity guard
     * discounts descriptors when it asserts that the money path contributed something, because a
     * descriptor would satisfy a bare non-empty assertion while leaving the money rule with no field,
     * parameter or return type to examine.</p>
     */
    private static final String PACKAGE_DESCRIPTOR_SIMPLE_NAME = "package-info";

    /**
     * Every production class beneath {@link #ANALYSED_ROOT} that the executing module can see.
     *
     * <p>Assumptions: the import happens once for the class rather than once per test, because reading
     * and resolving the byte code of a whole namespace is the expensive part of this gate and every
     * test below needs the same graph. A failure here surfaces as an initialisation error on every test
     * in the class, which is louder than a silently reduced subject set.</p>
     */
    private static final JavaClasses PRODUCTION_CLASSES = importProductionClasses();

    /**
     * Rule A1: a domain type may not reach for an AWS SDK, Spring Web or Jakarta Servlet type.
     *
     * <p>Refactoring Rationale: this boundary was carried as design prose in the architecture documents
     * and enforced by reading. Prose cannot fail a build, so a violation survived exactly as long as no
     * reviewer happened to recall the constraint. Expressing it as a rule replaces recollection with a
     * build step, and it keeps working as domain packages continue to be introduced.</p>
     *
     * <p>Assumptions: the boundary earns its keep because of what it costs to lose. A domain class that
     * imports a queue client or a servlet type can no longer be exercised without standing up the thing
     * it imported, so a unit test of a posting rule becomes an integration test of a queue client and
     * the rule stops being what the test measures. The direction matters for a migration too: the logic
     * transcribed from the reference baseline is the asset being preserved, so it is kept clear of the
     * parts of the target platform likeliest to be replaced.</p>
     *
     * <p>Trade-offs: empty-subject failure is switched off for this rule alone, and the concession is
     * narrow on purpose. The shared kernel declares no {@code domain} package at all, so when this gate
     * runs there the subject set is legitimately empty and the layer is absent rather than unchecked.
     * Granting the same tolerance globally, through a properties file, would extend it to the money
     * rule, where an empty subject set means the import resolved nothing and is the exact failure the
     * non-vacuity guard is written to catch.</p>
     */
    private static final ArchRule DOMAIN_IS_ISOLATED_FROM_FRAMEWORK_AND_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage(DOMAIN_PACKAGE_IDENTIFIER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FORBIDDEN_INFRASTRUCTURE_PACKAGE_IDENTIFIERS.toArray(String[]::new))
            .as("A1: no class in a " + DOMAIN_PACKAGE_IDENTIFIER
                    + " package should depend on an AWS SDK, Spring Web or Jakarta Servlet type")
            .because("a domain type must stay expressible without a transport or an infrastructure"
                    + " client, so that a business rule can be exercised without standing up the"
                    + " platform it happens to be deployed on")
            .allowEmptyShould(true);

    /**
     * Selects a class that reaches a domain model at all, whoever owns it.
     *
     * <p>Assumptions: the selection is deliberately wider than the prohibition, and narrowing it to
     * violating classes would be a mistake rather than an optimisation. ArchUnit prints the subject set
     * of a rule alongside its result, so selecting every class that touches a domain model makes the
     * population the rule actually examined legible; a predicate that pre-filtered violations would
     * report an empty subject set on a healthy reactor and say nothing about coverage.</p>
     *
     * <p>Assumptions: the reachability question is asked of ArchUnit's dependency graph rather than of
     * import statements, so a dependency expressed through a field type, a method or constructor
     * signature, an annotation, a supertype or a generic type argument is found on the same terms as a
     * plain import. A fully qualified reference with no import statement at all is found too, which is
     * the case a text search over source files misses.</p>
     */
    private static final DescribedPredicate<JavaClass> DEPEND_ON_A_DOMAIN_PACKAGE = describe(
            "depend on a class in a package with a '" + DOMAIN_PACKAGE_SEGMENT + "' segment",
            candidate -> candidate.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency ->
                            containsDomainSegment(dependency.getTargetClass().getPackageName())));

    /**
     * Rule A2: no bounded context may reach into another bounded context's domain model.
     *
     * <p>Refactoring Rationale: this replaces an implicit coupling convention with an ownership
     * contract the build checks. Under the convention, one service reads another's domain type directly
     * and the table behind that type acquires a second owner, which is the precise failure the bounded
     * contexts of this migration were drawn to prevent. Nothing about that failure is visible at the
     * import site, and by the time it shows up it shows up as two services disagreeing about a
     * schema.</p>
     *
     * <p>Alternatives Considered: four other shapes were evaluated. Fifty-six pairwise rules, one per
     * ordered pair of the eight service roots, were rejected because a ninth context would need
     * sixteen new rules and the set drifts silently when only some are added. A raw substring test for
     * {@code domain} was rejected because it also matches {@code subdomain} and
     * {@code domainevents}, so the rule would either over-report or be quietly loosened to compensate.
     * Checkstyle's {@code ImportControl} module was rejected because it cannot express rule A3 at all,
     * so one charter would end up split across two engines, and once two engines enforce overlapping
     * halves of a constraint it is whichever is cheaper to silence that gets silenced. A second rule
     * class beside this one was rejected because a rule split across two files can be weakened by
     * editing the other one, where the change reads as maintenance.</p>
     *
     * <p>Trade-offs: empty-subject failure is switched off for this rule as it is for A1, and for the
     * same reason rather than as a habit. The shared kernel declares no domain package, so nothing in
     * it can select as a class that reaches one; a module in which no class touches a domain model has
     * an absent relationship rather than an unchecked one. A3 keeps the default strict behaviour, and
     * the non-vacuity guard covers the graph as a whole.</p>
     */
    private static final ArchRule NO_SERVICE_DEPENDS_ON_ANOTHER_SERVICES_DOMAIN = classes()
            .that(DEPEND_ON_A_DOMAIN_PACKAGE)
            .should(new ForeignServiceDomainDependencyCondition())
            .as("A2: no class under one CardDemo package root should depend on a "
                    + DOMAIN_PACKAGE_SEGMENT + " class owned by a different CardDemo package root")
            .because("a bounded context's domain model is the in-memory shape of the schema it owns, so"
                    + " a second context reading it directly gives that schema a second owner and the"
                    + " two can then disagree about it without either build noticing")
            .allowEmptyShould(true);

    /**
     * Rule A3: no type in the money path declares binary floating point in any member position.
     *
     * <p>Refactoring Rationale: exact fixed-point money is an architectural invariant of this migration
     * rather than a review preference, and it is mechanised because the failure it prevents is silent
     * rather than loud. Money in the reference baseline is zoned decimal with a sign overpunch, so every
     * amount is exact to the cent. A binary floating-point type cannot represent most exact cent values,
     * so an amount routed through one comes back plausible and slightly wrong; it then surfaces at the
     * far end of the pipeline as an unexplained one-cent difference against a golden master rather than
     * as an error at the conversion that caused it. A rule naming the forbidden types is the only form
     * of this constraint that reports the cause instead of the symptom.</p>
     *
     * <p>Assumptions: this rule keeps ArchUnit's default strict behaviour on an empty subject set, and
     * the asymmetry with A1 and A2 is the point rather than an oversight. The money path lives in the
     * shared kernel, which every module of the reactor compiles against, so its types are on the
     * classpath of all nine executions. An empty subject set here therefore cannot mean the layer is
     * absent; it can only mean the import resolved nothing, and failing is the correct response to
     * that.</p>
     */
    private static final ArchRule MONEY_PATH_DECLARES_NO_BINARY_FLOATING_POINT = classes()
            .that()
            .resideInAPackage(MONEY_PACKAGE_IDENTIFIER)
            .should(new BinaryFloatingPointMemberCondition())
            .as("A3: no type in " + MONEY_PACKAGE_IDENTIFIER
                    + " should declare a field, a parameter or a return type of a binary floating-point"
                    + " type")
            .because("an exact fixed-point amount routed through a binary floating-point type comes back"
                    + " plausible and slightly wrong, and the difference then surfaces as an"
                    + " unexplained cent at the end of the pipeline rather than at the conversion that"
                    + " caused it");

    /**
     * Imports the production classes of whichever module is executing this gate.
     *
     * <p>Assumptions: two exclusions are applied and neither one covers the other. The predefined
     * test-exclusion option removes compiled test output, whose locations are build directories such
     * as {@code target/test-classes}; it does not remove a JAR, and the shared kernel publishes this
     * gate's own package as a JAR so the services can run it. The package predicate removes that JAR's
     * content. Applying only the first leaves this class among its own subjects in eight of nine
     * modules; applying only the second leaves every other test class in the executing module among
     * them.</p>
     *
     * <p>Alternatives Considered: adding the predefined option that excludes JARs outright. Rejected,
     * because the shared kernel reaches a service either as a JAR or as a reactor output directory
     * depending on which lifecycle phase the build stopped at, so excluding JARs would make the money
     * rule's subject set depend on how the build was invoked. Excluding one named package leaves that
     * behaviour identical under every invocation.</p>
     *
     * @return the imported production classes, described so that a rule failure names the scope it was
     *     evaluated over
     */
    private static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ANALYSED_ROOT)
                .that(not(resideInAPackage(OWN_PACKAGE_IDENTIFIER)))
                .as("production classes under " + ANALYSED_ROOT + " visible to the executing module");
    }

    /**
     * Asserts that this gate has something to gate, so that none of the three rules below can report
     * success by having examined nothing.
     *
     * <p>Refactoring Rationale: a rule set evaluated over an empty graph passes every assertion it
     * makes, and the build log of such a run is indistinguishable from the log of a run that verified
     * the whole reactor. That is the failure mode this guard exists for, and it is a realistic one
     * rather than a theoretical one: a mistyped analysed root, a module whose classes were not compiled
     * before the rules ran, or a shared kernel missing from a service's test classpath all produce it,
     * and each of them is invisible in the output of the rules themselves.</p>
     *
     * <p>Assumptions: the second assertion is what proves the exclusion in
     * {@link #importProductionClasses()} actually took effect, and it is not covered by the first. The
     * shared kernel publishes this gate's own package as a test artifact, so in a service module the
     * class holding these rules is on the classpath as ordinary JAR content. An unfiltered import would
     * therefore be non-empty even in a module that compiled nothing at all, and the first assertion
     * would pass while every rule measured the test instead of the code under test.</p>
     *
     * <p>Assumptions: the third assertion discounts compiled package descriptors. A package holding
     * only its own charter still yields one imported class, which satisfies a bare non-empty assertion
     * while leaving the money rule with no field, parameter or return type to inspect.</p>
     *
     * <p>Trade-offs: the failure text names package identifiers and the executing module's classpath
     * scope, and nothing else. It deliberately does not print the imported class list, a resolved
     * filesystem location or any value read at run time, because a gate's diagnostic is read from build
     * logs that are retained and shared more widely than the build itself.</p>
     */
    @Test
    @DisplayName("guard: the imported production graph is non-vacuous and excludes this gate's own package")
    void importedProductionGraphIsNotVacuous() {
        assertThat(PRODUCTION_CLASSES)
                .withFailMessage(
                        "No production class was imported from root '%s'. Every architecture rule would"
                                + " then pass without evaluating anything. Confirm that the executing"
                                + " module compiled its main classes and that the shared kernel is on"
                                + " its test classpath.",
                        ANALYSED_ROOT)
                .isNotEmpty();

        assertThat(PRODUCTION_CLASSES.that(resideInAPackage(OWN_PACKAGE_IDENTIFIER)))
                .withFailMessage(
                        "Package '%s' reached the analysed set. It holds this gate itself, so the rules"
                                + " would be measuring the test rather than the code under test.",
                        OWN_PACKAGE_IDENTIFIER)
                .isEmpty();
        assertThat(PRODUCTION_CLASSES.contain(LayeringRulesTest.class))
                .withFailMessage(
                        "The class declaring these rules reached the analysed set, so at least one rule"
                                + " would be evaluated against the gate instead of against production"
                                + " code.")
                .isFalse();

        assertThat(declaredTypesIn(MONEY_PACKAGE_IDENTIFIER))
                .withFailMessage(
                        "No declared type was imported from money path '%s', so rule A3 would have no"
                                + " member to inspect. The money path lives in the shared kernel, which"
                                + " is on every module's test classpath, so an empty selection means the"
                                + " import resolved nothing rather than that there was nothing to"
                                + " find.",
                        MONEY_PACKAGE_IDENTIFIER)
                .isNotEmpty();
    }

    /**
     * Asserts that the ownership contract A2 decides violations from is exactly the nine roots the
     * migration fixed, so a root invented, dropped or misspelled cannot quietly change what A2 reports.
     *
     * <p>Refactoring Rationale: A2 resolves ownership from a package name, so the root list is the whole
     * of its configuration. A misspelled root matches no package, contributes no owner and therefore
     * reports no violation, which reads in a build log exactly like a boundary that was checked and
     * found intact. This guard turns the root list from data nobody re-reads into a checked contract, and
     * it is kept separate from the non-vacuity guard above because the two catch different faults: that
     * one catches an empty graph, this one catches a populated graph measured against the wrong roots.
     * </p>
     *
     * <p>Assumptions: every clause here states one property the ownership resolution below depends on.
     * The count is nine because the migration draws eight bounded contexts plus the shared kernel. The
     * shared kernel is asserted first because it is derived first and is the one root a dependency may
     * always point into. Duplicates are rejected because a repeated root would leave the first-match
     * resolution reading as ambiguous even though it stays deterministic. And every root is required to
     * sit beneath the analysed root, since a root outside it could never be resolved from the imported
     * graph at all.</p>
     *
     * <p>Trade-offs: the count is asserted as a literal, so adding a tenth bounded context fails this
     * test until the number is updated. That is the intent rather than a maintenance cost -- a new
     * ownership root is a change to the decomposition, and it should not reach the reactor without the
     * architecture gate acknowledging it.</p>
     */
    @Test
    @DisplayName("guard: the ownership contract is exactly the nine fixed CardDemo roots")
    void ownershipContractIsExactlyNineFixedRoots() {
        assertThat(OWNERSHIP_ROOTS)
                .withFailMessage(
                        "The ownership contract that rule A2 resolves owners from no longer holds nine"
                                + " roots. A root that is absent or misspelled matches no package and so"
                                + " reports no violation, which is indistinguishable from an intact"
                                + " boundary.")
                .hasSize(9);
        assertThat(OWNERSHIP_ROOTS).startsWith(SHARED_KERNEL_ROOT);
        assertThat(OWNERSHIP_ROOTS).doesNotHaveDuplicates();
        assertThat(SERVICE_ROOTS).hasSize(8);
        assertThat(OWNERSHIP_ROOTS)
                .allSatisfy(root -> assertThat(root).startsWith(ANALYSED_ROOT + "."));
    }

    /**
     * Checks rule A1, that no domain type depends on an AWS SDK, Spring Web or Jakarta Servlet type.
     *
     * <p>Alternatives Considered: banning those roots across the whole module rather than inside
     * {@code ..domain..} packages. Rejected, and the rejection is specific rather than stylistic. The
     * shared kernel's correlation filter is a servlet filter and its exception handler is a web
     * controller advice, so a module-wide ban would reject two adapters that are correct exactly as
     * written; and every service's configuration package legitimately holds Spring Web types. The
     * boundary this migration draws is around the domain layer, so the rule is drawn there too.</p>
     *
     * <p>Assumptions: ArchUnit's own violation text names the origin class, the target type and the
     * member the dependency was declared on, which is what a reader needs in order to remove it. That
     * text carries no value read at run time.</p>
     */
    @Test
    @DisplayName("A1: a ..domain.. package depends on no AWS SDK, Spring Web or Jakarta Servlet type")
    void domainPackagesAreIsolatedFromFrameworkAndInfrastructure() {
        DOMAIN_IS_ISOLATED_FROM_FRAMEWORK_AND_INFRASTRUCTURE.check(PRODUCTION_CLASSES);
    }

    /**
     * Checks rule A2, that a domain model is read only by the bounded context that owns it and never by
     * another.
     *
     * <p>Assumptions: three dependency shapes stay legal and each is permitted for its own reason, not
     * as a blanket exception. A context reads its own domain model throughout its repositories,
     * services and mappers. Every context reads the shared kernel, which is shared deliberately, being
     * the Java analogue of compiling every reference program against one copybook include path. And a
     * package outside the nine roots belongs to a third-party library, which has no CardDemo ownership
     * to cross. The condition below records each of those decisions at the line that makes it.</p>
     *
     * <p>Assumptions: the rule also runs in the direction that is easy to overlook. The shared kernel
     * depending on a service-owned domain type inverts the reactor's dependency arrow, and it would
     * compile, because Maven would resolve nothing new: the kernel already sits on that service's
     * classpath. The condition treats the kernel as an ordinary root for that reason.</p>
     */
    @Test
    @DisplayName("A2: no CardDemo package root depends on a domain class owned by a different root")
    void noRootDependsOnAnotherRootsDomainModel() {
        NO_SERVICE_DEPENDS_ON_ANOTHER_SERVICES_DOMAIN.check(PRODUCTION_CLASSES);
    }

    /**
     * Checks rule A3, that the money path declares no binary floating point in a field, in any parameter
     * position or in a return type.
     *
     * <p>Assumptions: all three member positions are checked because each one alone is sufficient to
     * lose exactness, and they fail differently. A field is where an amount is stored inexactly. A
     * parameter is where an exact amount is handed in already rounded, so the loss happened in the
     * caller and the money type never sees the original value. A return type is where an exact internal
     * amount is published inexactly, which is the case a reader is least likely to look for, because the
     * arithmetic inside the method can be entirely correct.</p>
     *
     * <p>Assumptions: the money path is the only scope this rule is given. Widening it to every numeric
     * context in the reactor would reject a legitimate ratio, an interest percentage held as a
     * non-monetary quantity, or an elapsed-time measurement, and a gate that fails on correct code is
     * one that gets switched off.</p>
     */
    @Test
    @DisplayName("A3: the money path declares no float, double, Float or Double member")
    void moneyPathDeclaresNoBinaryFloatingPoint() {
        MONEY_PATH_DECLARES_NO_BINARY_FLOATING_POINT.check(PRODUCTION_CLASSES);
    }

    /**
     * Selects the declared types of a package, discounting the compiled package descriptor.
     *
     * <p>Assumptions: a descriptor is identified by its simple name, which the compiler fixes as
     * {@code package-info} and which no ordinary Java type can carry, since a hyphen is not legal in an
     * identifier. Matching on the name is therefore exact rather than heuristic.</p>
     *
     * @param packageIdentifier an ArchUnit package identifier selecting the packages to read, for
     *     example {@code com.carddemo.common.money..}
     * @return the imported classes in those packages that declare members, in no particular order
     */
    private static List<JavaClass> declaredTypesIn(String packageIdentifier) {
        return PRODUCTION_CLASSES.that(resideInAPackage(packageIdentifier)).stream()
                .filter(candidate -> !PACKAGE_DESCRIPTOR_SIMPLE_NAME.equals(candidate.getSimpleName()))
                .toList();
    }

    /**
     * Resolves which of the nine CardDemo ownership roots owns a package.
     *
     * <p>Assumptions: a root matches on an exact package name or on the root followed by a dot, and
     * never on a bare prefix. The distinction is load bearing rather than pedantic: a bare prefix test
     * would attribute a hypothetical {@code com.carddemo.accounting} package to the
     * {@code com.carddemo.account} root, and the rule would then report a boundary crossing between a
     * context and itself while missing the real owner. Anchoring on the dot separator makes the match a
     * package-boundary match.</p>
     *
     * <p>Assumptions: the nine roots are mutually exclusive as declared, so the first match is the only
     * match and iteration order carries no meaning. If a tenth root were ever nested beneath an
     * existing one, that property would no longer hold and this resolution would have to be revisited
     * rather than extended.</p>
     *
     * @param packageName the fully qualified package name to resolve, as ArchUnit reports it; the empty
     *     string for the default package
     * @return the owning root drawn from the nine, or an empty optional when the package lies outside
     *     the ownership contract altogether
     */
    private static Optional<String> ownershipRootOf(String packageName) {
        for (String root : OWNERSHIP_ROOTS) {
            if (packageName.equals(root) || packageName.startsWith(root + ".")) {
                return Optional.of(root);
            }
        }
        return Optional.empty();
    }

    /**
     * Reports whether a package name carries the domain segment as a complete dot-delimited segment.
     *
     * <p>Assumptions: the comparison is against whole segments because a substring test on the same
     * word is wrong in both directions. It would match {@code com.carddemo.batch.subdomain} and
     * {@code com.carddemo.batch.domainevents}, neither of which is a domain model under the naming this
     * migration fixed, and a rule that over-reports gets loosened until it under-reports.</p>
     *
     * @param packageName the fully qualified package name to inspect, as ArchUnit reports it
     * @return {@code true} when one of the package's dot-delimited segments is exactly the domain
     *     segment
     */
    private static boolean containsDomainSegment(String packageName) {
        for (String segment : packageName.split("\\.")) {
            if (DOMAIN_PACKAGE_SEGMENT.equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records every direct dependency that crosses from one CardDemo ownership root onto a domain class
     * owned by a different root.
     *
     * <p>Assumptions: a condition is used rather than the built-in dependency syntax because the
     * legality of a dependency here is relative to its origin. Whether reaching
     * {@code com.carddemo.batch.domain} is permitted depends entirely on which root the reading class
     * belongs to, and the built-in syntax describes a target set without reference to the origin. The
     * condition also lets each crossing be reported on its own line, so a class with three of them
     * yields three findings rather than one that has to be re-run to be understood.</p>
     *
     * <p>Trade-offs: the type is nested and private, so this gate stays a single file as its package
     * charter requires. The cost is a longer file; the alternative was a package-level helper class,
     * which would let the ownership resolution be weakened in a file whose name does not say it holds
     * an architectural constraint.</p>
     */
    private static final class ForeignServiceDomainDependencyCondition extends ArchCondition<JavaClass> {

        /**
         * Creates the condition with the description ArchUnit appends to the rule text it prints.
         *
         * <p>Assumptions: the description is passed to a formatting constructor, so it must contain no
         * format specifier. It is assembled from the centralized domain segment rather than retyped, so
         * the rule text cannot drift from the segment the condition actually matches.</p>
         */
        private ForeignServiceDomainDependencyCondition() {
            super("not depend on a " + DOMAIN_PACKAGE_SEGMENT
                    + " class owned by a different CardDemo package root");
        }

        /**
         * Inspects one class's direct dependencies and records each ownership boundary it crosses.
         *
         * <p>Assumptions: the early return and the four skips below are the whole of what this rule
         * permits, and each is justified at the line that performs it rather than in a list here, so
         * that removing one is visibly the removal of a stated permission.</p>
         *
         * <p>Trade-offs: the message names the two classes and the two roots and nothing else. It omits
         * the member the dependency was declared on, which would shorten the search for it, because a
         * member name in a mapping or persistence layer can name a field of the record layouts this
         * migration carries across, and build logs are retained and shared more widely than the build.
         * A fully qualified class name is enough to find the file.</p>
         *
         * <p>Trade-offs: one violation is reported per crossed pair of classes rather than per code-level
         * reference, so the number of findings matches the number of distinct crossings a reader has to
         * remove. Because the message deliberately omits the member, reporting each reference separately
         * would emit several character-identical lines for one crossing, and a reader cannot tell a
         * repeated line from a second finding. The reference count is given up for that reason; opening
         * the named class shows every reference at once.</p>
         *
         * @param sourceClass the class whose direct dependencies are inspected
         * @param events the collector each boundary crossing is reported to as a separate violation
         */
        @Override
        public void check(JavaClass sourceClass, ConditionEvents events) {
            Set<String> alreadyReported = new HashSet<>();

            Optional<String> sourceRoot = ownershipRootOf(sourceClass.getPackageName());
            if (sourceRoot.isEmpty()) {
                // Assumptions: a class in the analysed namespace that no root covers sits directly in
                // com.carddemo or in some package the nine roots do not reach. There is no context to
                // attribute a crossing to, so reporting one would name an owner that does not exist.
                return;
            }

            for (Dependency dependency : sourceClass.getDirectDependenciesFromSelf()) {
                JavaClass targetClass = dependency.getTargetClass();
                String targetPackage = targetClass.getPackageName();

                if (!containsDomainSegment(targetPackage)) {
                    // Assumptions: only a domain model is owned exclusively. A context's api, dto,
                    // mapper or config package is not what this rule protects, and the reactor carries
                    // no Maven edge between two services for such a dependency to arise from anyway.
                    continue;
                }

                Optional<String> targetRoot = ownershipRootOf(targetPackage);
                if (targetRoot.isEmpty()) {
                    // Assumptions: a package named for a domain model outside the nine roots belongs to
                    // a third-party library, which has no CardDemo ownership to cross. Reporting it
                    // would fail this gate on a dependency's own choice of package name.
                    continue;
                }

                if (!SERVICE_ROOTS.contains(targetRoot.get())) {
                    // Assumptions: the shared kernel is shared on purpose, so a type beneath it is
                    // available to all nine roots even were it to sit in a domain package. Only the
                    // eight service roots own a domain model exclusively.
                    continue;
                }

                if (targetRoot.get().equals(sourceRoot.get())) {
                    // Assumptions: a context reads its own domain model throughout its repositories,
                    // services and mappers, so a same-root dependency is the ordinary case and not an
                    // exception carved out of the rule.
                    continue;
                }

                if (!alreadyReported.add(targetClass.getName())) {
                    // Assumptions: one class reaches another through several references at once - a
                    // field, a parameter, a call - and ArchUnit reports each as its own dependency. They
                    // are one crossing to remove, and the message below names no member, so reporting
                    // them separately would emit character-identical lines for a single finding.
                    continue;
                }

                events.add(SimpleConditionEvent.violated(
                        sourceClass,
                        "class " + sourceClass.getName() + " owned by root " + sourceRoot.get()
                                + " depends on " + DOMAIN_PACKAGE_SEGMENT + " class "
                                + targetClass.getName() + " owned by root " + targetRoot.get()));
            }
        }
    }

    /**
     * Records every field, parameter and return type in the money path that is declared with a binary
     * floating-point type.
     *
     * <p>Alternatives Considered: expressing this with ArchUnit's built-in member syntax. The field and
     * return-type halves are expressible that way, but the parameter half is not: the built-in
     * parameter predicate matches a code unit's COMPLETE parameter list, so it answers "is this method's
     * signature exactly one double" and not "does any parameter of this method have that type". A method
     * taking an exact amount and an inexact rate would pass it. Writing the parameter half by hand and
     * the other two through the syntax would split one invariant across two mechanisms, so all three are
     * inspected here.</p>
     *
     * <p>Assumptions: an array or varargs declaration is caught through its element type, so
     * {@code double[]} and {@code double...} are reported exactly as a bare {@code double} is. Storing
     * amounts in an array of a binary floating-point type loses precision on the same terms as a single
     * field does, and the array form is the one an exact-name check would let through.</p>
     *
     * <p>Trade-offs: the type is nested and private, keeping this gate a single file as its package
     * charter requires, at the cost of a longer file. A package-level helper would let the forbidden set
     * be edited in a file whose name does not announce that it holds an architectural constraint.</p>
     */
    private static final class BinaryFloatingPointMemberCondition extends ArchCondition<JavaClass> {

        /**
         * Creates the condition with the description ArchUnit appends to the rule text it prints.
         *
         * <p>Assumptions: the description is passed to a formatting constructor, so it must contain no
         * format specifier. The four forbidden type names are deliberately not interpolated into it: the
         * rule text stays readable, and the names stay in the one centralized set that the check reads,
         * so text and behaviour cannot drift apart.</p>
         */
        private BinaryFloatingPointMemberCondition() {
            super("declare no field, no parameter and no return type of a binary floating-point type");
        }

        /**
         * Inspects one money-path type and records each member position declared with a forbidden type.
         *
         * <p>Assumptions: code units are read rather than methods and constructors separately, because
         * that set covers both, so a constructor taking an inexact amount is caught on the same terms as
         * a method taking one. Return types are read from methods alone, a constructor having none.</p>
         *
         * <p>Assumptions: only members DECLARED by this type are inspected. Inherited members belong to
         * the type that declares them, and that type is either in the money path, where this rule
         * already reaches it, or outside it, where reporting it here would name a class the money path
         * does not own.</p>
         *
         * @param moneyClass the money-path type whose declared members are inspected
         * @param events the collector each offending member position is reported to as its own violation
         */
        @Override
        public void check(JavaClass moneyClass, ConditionEvents events) {
            for (JavaField field : moneyClass.getFields()) {
                reportIfBinaryFloatingPoint(
                        field.getRawType(), "field " + field.getFullName(), moneyClass, events);
            }

            for (JavaCodeUnit codeUnit : moneyClass.getCodeUnits()) {
                List<JavaClass> parameterTypes = codeUnit.getRawParameterTypes();
                for (int index = 0; index < parameterTypes.size(); index++) {
                    reportIfBinaryFloatingPoint(
                            parameterTypes.get(index),
                            "parameter at index " + index + " of " + codeUnit.getFullName(),
                            moneyClass,
                            events);
                }
            }

            for (JavaMethod method : moneyClass.getMethods()) {
                reportIfBinaryFloatingPoint(
                        method.getRawReturnType(),
                        "return type of " + method.getFullName(),
                        moneyClass,
                        events);
            }
        }

        /**
         * Records one violation when a declared type resolves to a forbidden binary floating-point type.
         *
         * <p>Assumptions: the declared type is reduced to its base component type before the comparison,
         * which leaves a non-array type unchanged and unwraps an array of any depth in one step. That is
         * what makes an array and a varargs declaration reportable without a second code path.</p>
         *
         * <p>Trade-offs: the message names the declaring class, the member signature and the two type
         * names, and nothing else. It carries no field value, no identifier and no run-time reading,
         * because a gate's diagnostic is read from build logs that are retained and shared more widely
         * than the build itself. A signature is enough to find the declaration.</p>
         *
         * @param declaredType the raw type as declared on the member, possibly an array type
         * @param memberDescription the member being inspected, named by its signature and, for a
         *     parameter, by its index within that signature
         * @param declaringClass the money-path type that declares the member, used as the violation's
         *     corresponding object
         * @param events the collector the violation is added to
         */
        private static void reportIfBinaryFloatingPoint(
                JavaClass declaredType,
                String memberDescription,
                JavaClass declaringClass,
                ConditionEvents events) {

            JavaClass elementType = declaredType.getBaseComponentType();
            if (!FORBIDDEN_BINARY_FLOATING_POINT_TYPES.contains(elementType.getName())) {
                return;
            }

            events.add(SimpleConditionEvent.violated(
                    declaringClass,
                    "class " + declaringClass.getName() + " declares " + memberDescription
                            + " with type " + declaredType.getName()
                            + ", which resolves to forbidden binary floating-point type "
                            + elementType.getName()));
        }
    }
}
