package com.carddemo.common.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the three architecture invariants of the migrated decomposition as executable gates.
 *
 * <p>Assumptions: the package charter beside this class states what the three invariants are and why a
 * convention is insufficient for each; this class states how each one is decided. The three are kept in
 * separately named tests so a failing build names the boundary that broke rather than reporting one
 * aggregate failure, and the shared inputs -- the imported class graph and the nine ownership roots --
 * are declared once so no rule can be evaluated against a different graph or a different root set from
 * its siblings.</p>
 *
 * <h2>Where these rules run, and against which classes</h2>
 *
 * <p>Assumptions: this class is published as a test artifact and is collected into every service
 * module's own test run, so it executes nine times and imports a DIFFERENT graph each time -- the
 * classes of whichever module is executing it, plus the shared kernel it depends on. That is the whole
 * point of the arrangement: a rule about a module's packages can only be decided against classes an
 * importer in that module can see. The imported package root is therefore the shared prefix of all nine
 * ownership roots rather than any one of them, because naming one root would resolve to the same module
 * wherever this ran.</p>
 *
 * <p>Trade-offs: two of the three rules permit their selected subject set to be empty and the third
 * does not, and the asymmetry is deliberate rather than a relaxation applied for convenience. The
 * shared kernel has no domain package at all and holds no service code, so in the module where this
 * class is authored the subjects of the first two rules are legitimately empty and a strict rule would
 * fail for the absence of a layer rather than for a violated boundary -- the likeliest repair for which
 * is to stop running the gate. The money path, by contrast, exists in every graph this class can be
 * imported into, so its rule stays strict and is what makes a broken classpath a failure instead of a
 * pass. The independent guard below closes the same hole from the other side.</p>
 */
class LayeringRulesTest {

    /**
     * The package prefix the whole migrated decomposition sits under.
     *
     * <p>Assumptions: this is the prefix imported rather than any single ownership root, for the reason
     * recorded on the class: naming one root would resolve to one module wherever this class ran, and
     * the rules have to see the executing module.</p>
     */
    private static final String MIGRATION_ROOT = "com.carddemo";

    /**
     * The shared-kernel ownership root, which services may consume and which may consume no service.
     */
    private static final String SHARED_KERNEL_ROOT = "com.carddemo.common";

    /**
     * The eight service ownership roots, fixed by the migration plan.
     *
     * <p>Assumptions: because the roots are fixed, ownership is decidable from a package name alone,
     * with no annotation, registry or module descriptor to keep in step with the source. They are
     * declared once here and used for both ownership resolution and the validation of that resolution,
     * so a root cannot be honoured by one rule and unknown to another.</p>
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
     * The nine ownership roots: the shared kernel followed by the eight services.
     */
    private static final List<String> OWNERSHIP_ROOTS = concatRoots();

    /**
     * The package segment that marks a domain package.
     *
     * <p>Assumptions: this is matched as a whole dot-delimited segment and never as a raw substring. A
     * substring test would also match a package named {@code domainevents} and would miss nothing, so
     * it fails in the direction that reports violations against classes the rule was never about.</p>
     */
    private static final String DOMAIN_SEGMENT = "domain";

    /**
     * The money path, which is the one package the fixed-point invariant governs.
     */
    private static final String MONEY_ROOT = "com.carddemo.common.money";

    /**
     * The package-matching form of the money path, for the two rules that select on it.
     */
    private static final String MONEY_PACKAGE_PATTERN = MONEY_ROOT + "..";

    /**
     * The four binary floating-point types the money path may not name in any member position.
     *
     * <p>Assumptions: both the primitives and their boxes are listed, because a boxed value converts to
     * its primitive silently and a rule naming only the primitives would be satisfied by a signature
     * that took the box. The names are held here rather than added to the production money classes to
     * support the rule, so this is the only file in the subtree that spells them.</p>
     */
    private static final Set<String> FORBIDDEN_MONEY_TYPES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    /**
     * The dependency roots a domain package may not reach for.
     *
     * <p>Assumptions: the two AWS roots are both listed because the current and the previous generation
     * of the vendor's Java clients occupy different package roots, and a domain class could reach either
     * one. The web and servlet roots are named specifically rather than as the whole framework root,
     * because this migration's own adapters -- the correlation filter and the shared advice -- use web
     * and servlet types legitimately from outside a domain package, and a module-wide ban would reject
     * them.</p>
     */
    private static final List<String> FORBIDDEN_DOMAIN_DEPENDENCIES = List.of(
            "software.amazon.awssdk..",
            "com.amazonaws..",
            "org.springframework.web..",
            "jakarta.servlet..");

    /**
     * The production classes of the module executing this class, imported once.
     */
    private static final JavaClasses PRODUCTION_CLASSES = importProductionClasses();

    /**
     * Establishes that the rules below have a graph to be decided against.
     *
     * <p>Assumptions: a rule whose subject set is empty reports success, so a classpath that imported
     * nothing would make every gate in this file pass while checking nothing -- the failure mode that is
     * indistinguishable from having no gate. This guard is independent of all three rules and is what
     * makes their individual empty-subject tolerances safe: the tolerance says a LAYER may be absent,
     * this says the GRAPH may not be. The count is asserted against the ownership roots rather than
     * against a number, so a module with a different class count does not need this test edited.</p>
     *
     * <p>Assumptions: the assertion explicitly excludes this class's own package from what counts,
     * because this class is published as a test artifact and an artifact on a module's test classpath is
     * not excluded by the library's own test-location filter. Without that exclusion the guard could be
     * satisfied by the rule class having imported itself, which would be a graph containing exactly the
     * thing that is not under test.</p>
     */
    @Test
    @DisplayName("the imported production graph is not empty")
    void importedProductionGraphIsNotEmpty() {
        List<String> importedNames = PRODUCTION_CLASSES.stream()
                .map(JavaClass::getFullName)
                .filter(name -> !name.startsWith(LayeringRulesTest.class.getPackageName()))
                .filter(LayeringRulesTest::isOwnedByTheMigration)
                .toList();

        assertThat(importedNames)
                .as("no production class was imported under %s; the architecture gates would then all"
                        + " pass while checking nothing, so the classpath of the executing module is"
                        + " what needs inspecting rather than these rules", MIGRATION_ROOT)
                .isNotEmpty();
    }

    /**
     * Establishes that the money path the strict rule governs is present in the imported graph.
     *
     * <p>Assumptions: the strict rule below is the one that cannot pass vacuously, and it can only carry
     * that weight if its subject set is non-empty in every module this class runs in. The money path
     * lives in the shared kernel, and every module depends on the shared kernel, so the subject set is
     * present wherever the gate runs; asserting it separately is what turns that reasoning into a build
     * step.</p>
     */
    @Test
    @DisplayName("the money path is present in the imported graph")
    void moneyPathIsPresentInTheImportedGraph() {
        List<String> moneyClasses = PRODUCTION_CLASSES.stream()
                .map(JavaClass::getFullName)
                .filter(name -> name.startsWith(MONEY_ROOT + "."))
                .toList();

        assertThat(moneyClasses)
                .as("the money path %s carried no class, so the fixed-point rule below would pass"
                        + " without inspecting anything", MONEY_ROOT)
                .isNotEmpty();
    }

    /**
     * Establishes that the nine ownership roots are exactly the ones the migration fixed.
     *
     * <p>Assumptions: the ownership rule decides which service owns a class by prefix, so a root
     * invented, dropped or misspelled here would silently change what that rule considers a violation. A
     * misspelled root matches nothing and therefore reports nothing, which is the failure that looks
     * like a passing gate.</p>
     */
    @Test
    @DisplayName("the ownership contract is exactly nine fixed roots")
    void ownershipContractIsExactlyNineFixedRoots() {
        assertThat(OWNERSHIP_ROOTS).hasSize(9);
        assertThat(OWNERSHIP_ROOTS).startsWith(SHARED_KERNEL_ROOT);
        assertThat(OWNERSHIP_ROOTS).doesNotHaveDuplicates();
        assertThat(SERVICE_ROOTS).hasSize(8);
        assertThat(OWNERSHIP_ROOTS)
                .allSatisfy(root -> assertThat(root).startsWith(MIGRATION_ROOT + "."));
    }

    /**
     * Gate A1: a domain package may not depend on an AWS SDK, web or servlet type.
     *
     * <p>Assumptions: the subject is every class in any {@code ..domain..} package and the prohibition
     * is scoped to exactly that. A domain package holds the entities derived from the reference record
     * layouts, and it is the one layer that has to stay expressible without a transport or an
     * infrastructure client -- because a domain class that imports a queue client can no longer be
     * exercised without standing up the thing it imported, at which point a unit test of a posting rule
     * has become an integration test of a queue client and the rule has stopped being what the test
     * measures.</p>
     *
     * <p>Trade-offs: the empty-subject tolerance is applied to this rule alone among the framework
     * prohibitions, because the module this class is authored in has no domain package. Without it the
     * gate would fail there for the absence of a layer rather than for a violated boundary. The guard
     * tests above are what keep that tolerance from becoming a way for the whole file to pass
     * vacuously.</p>
     */
    @Test
    @DisplayName("A1: no domain class depends on an AWS SDK, web or servlet type")
    void domainPackagesDependOnNoFrameworkOrInfrastructureType() {
        noClasses()
                .that().resideInAPackage("..%s..".formatted(DOMAIN_SEGMENT))
                .should().dependOnClassesThat()
                .resideInAnyPackage(FORBIDDEN_DOMAIN_DEPENDENCIES.toArray(String[]::new))
                .because("a domain type is derived from a reference record layout and must stay"
                        + " expressible without a transport or an infrastructure client, so that a test"
                        + " of a business rule remains a test of that rule")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    /**
     * Gate A2: no ownership root may depend on another root's domain class.
     *
     * <p>Assumptions: ownership is resolved by exact root boundary and {@code domain} is matched as a
     * whole package segment, so the rule cannot be satisfied or violated by a package name that merely
     * contains one of those strings. Dependencies are read from the library's own model rather than from
     * source text, so a dependency expressed through a field, a parameter, a return type, an
     * annotation, a supertype or a signature is covered without this rule enumerating those
     * positions.</p>
     *
     * <p>Assumptions: three relationships are deliberately permitted. A class may depend on a domain
     * class its OWN root owns, which is ordinary layering inside a bounded context. Any root may depend
     * on the shared kernel, which is shared on purpose and is the Java analogue of compiling every
     * reference program against one copybook include path. And a dependency on a package outside the
     * nine roots is ignored, because a third-party type has no owner in this contract to cross a
     * boundary of.</p>
     *
     * <p>Assumptions: the shared kernel depending on a service-owned domain class is a violation in the
     * same rule rather than a separate one. The dependency arrow points inward toward the kernel and
     * never outward from it, so the kernel reaching into a service is the same ownership breach as one
     * service reaching into another -- and it is the more damaging direction, because the kernel is on
     * every module's classpath.</p>
     */
    @Test
    @DisplayName("A2: no ownership root depends on another root's domain class")
    void noOwnershipRootDependsOnAnotherRootsDomain() {
        classes()
                .that(new ForeignDomainDependencyPredicate())
                .should(new NoForeignDomainDependencyCondition())
                .because("a domain type is owned by exactly one bounded context, and a second context"
                        + " reading it gives the table behind it a second owner, which is the failure"
                        + " these boundaries were drawn to prevent")
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    /**
     * Gate A3: the money path may name no binary floating-point type in any member position.
     *
     * <p>Assumptions: this rule keeps the library's strict empty-subject behaviour, unlike the two
     * above, and that is what makes it the file's load-bearing gate. Money in the reference baseline is
     * exact fixed point, and the failure a binary type causes is silent rather than loud: most exact
     * cent values have no binary representation, so an amount routed through one returns plausible and
     * slightly wrong, surfacing at the far end of the pipeline as an unexplained one-cent difference
     * against a golden master rather than as an error at the conversion that caused it.</p>
     *
     * <p>Assumptions: fields, every parameter of every method and constructor, and every method return
     * type are all inspected, because a value can enter or leave through any of them and a rule covering
     * only one position would be satisfied by a signature that used another. Each violation is reported
     * separately so a class with several is reported as several rather than as one.</p>
     */
    @Test
    @DisplayName("A3: the money path carries no binary floating-point type")
    void moneyPathCarriesNoBinaryFloatingPointType() {
        classes()
                .that().resideInAPackage(MONEY_PACKAGE_PATTERN)
                .should(new NoBinaryFloatingPointCondition())
                .because("the reference baseline carries money as exact fixed point, and a binary"
                        + " floating-point type returns a plausible value that is wrong by a cent"
                        + " rather than failing at the conversion that introduced it")
                .check(PRODUCTION_CLASSES);
    }

    /**
     * Imports the production classes of whichever module is executing this class.
     *
     * <p>Assumptions: two exclusions are applied and both are required. The library's own test-location
     * filter removes classes compiled into a module's test output directory, which is what keeps this
     * class and its siblings out of the graph in the module they are authored in. It does NOT remove a
     * packaged test artifact, because that is a jar rather than a test output directory -- so the second
     * exclusion removes any archive whose name marks it as one. Without it, this very class would be
     * imported as production code in each of the eight service modules that resolve the shared kernel's
     * test artifact, and a rule about production layering would be evaluated partly against the rules
     * themselves.</p>
     *
     * @return the imported production classes under {@link #MIGRATION_ROOT}, never {@code null}
     */
    private static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(LayeringRulesTest::isNotAPublishedTestArtifact)
                .importPackages(MIGRATION_ROOT);
    }

    /**
     * Reports whether a location is something other than a published test artifact.
     *
     * @param location the classpath location the importer is considering; never {@code null}
     * @return {@code false} for an archive whose name marks it as a module's published test classes,
     *     otherwise {@code true}
     */
    private static boolean isNotAPublishedTestArtifact(Location location) {
        return !location.contains("-tests.jar") && !location.contains("-test.jar");
    }

    /**
     * Builds the nine ownership roots from the shared kernel and the eight service roots.
     *
     * @return an immutable list whose first element is the shared kernel root, never {@code null}
     */
    private static List<String> concatRoots() {
        List<String> roots = new java.util.ArrayList<>(SERVICE_ROOTS.size() + 1);
        roots.add(SHARED_KERNEL_ROOT);
        roots.addAll(SERVICE_ROOTS);
        return List.copyOf(roots);
    }

    /**
     * Resolves the ownership root that owns a fully qualified type name.
     *
     * <p>Assumptions: the comparison is against the root itself or the root followed by a dot, never a
     * bare prefix test. A bare prefix would make {@code com.carddemo.cardholder} appear to be owned by
     * the card root, which is the class of mistake that reports a violation against a package the
     * contract never named.</p>
     *
     * @param typeName the fully qualified name of the type whose owner is being resolved
     * @return the owning root, or {@code null} when the type lies outside all nine roots
     */
    private static String ownerOf(String typeName) {
        for (String root : OWNERSHIP_ROOTS) {
            if (typeName.equals(root) || typeName.startsWith(root + ".")) {
                return root;
            }
        }
        return null;
    }

    /**
     * Reports whether a type name lies inside one of the nine ownership roots.
     *
     * @param typeName the fully qualified name of the type to classify
     * @return {@code true} when one of the nine roots owns it, otherwise {@code false}
     */
    private static boolean isOwnedByTheMigration(String typeName) {
        return ownerOf(typeName) != null;
    }

    /**
     * Reports whether a package name carries {@code domain} as a whole segment.
     *
     * @param packageName the dot-delimited package name to inspect; never {@code null}
     * @return {@code true} when one of its segments is exactly {@code domain}, otherwise {@code false}
     */
    private static boolean isDomainPackage(String packageName) {
        for (String segment : packageName.split("\\.")) {
            if (segment.equals(DOMAIN_SEGMENT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Selects the classes that depend on a domain class owned by a different ownership root.
     *
     * <p>Assumptions: the selection and the assertion below deliberately apply the same test. Selecting
     * only the classes that already violate the rule is what lets this gate carry its own
     * empty-subject tolerance without weakening anything: the tolerance then means no class violates
     * it, rather than no class was looked at. The independent guard tests establish that classes WERE
     * looked at.</p>
     */
    private static final class ForeignDomainDependencyPredicate
            extends com.tngtech.archunit.base.DescribedPredicate<JavaClass> {

        /**
         * Describes the selection in the terms a failure report will use.
         */
        private ForeignDomainDependencyPredicate() {
            super("depend on a domain class owned by another ownership root");
        }

        /**
         * Reports whether a class depends on a domain class another root owns.
         *
         * @param candidate the class to inspect; never {@code null}
         * @return {@code true} when at least one of its direct dependencies is a foreign domain class
         */
        @Override
        public boolean test(JavaClass candidate) {
            return !foreignDomainDependenciesOf(candidate).isEmpty();
        }
    }

    /**
     * Asserts that a class depends on no domain class owned by a different ownership root.
     */
    private static final class NoForeignDomainDependencyCondition extends ArchCondition<JavaClass> {

        /**
         * Describes the condition in the terms a failure report will use.
         */
        private NoForeignDomainDependencyCondition() {
            super("depend on no domain class owned by another ownership root");
        }

        /**
         * Records one violation for each foreign domain dependency a class carries.
         *
         * <p>Assumptions: the message names the source class, its root, the target class and the target
         * root, and nothing else. That is enough to locate and correct the breach while disclosing no
         * value, and naming both roots is what tells a reader which boundary was crossed rather than
         * only that one was.</p>
         *
         * @param candidate the class being checked; never {@code null}
         * @param events the sink the library collects violations in; never {@code null}
         */
        @Override
        public void check(JavaClass candidate, ConditionEvents events) {
            String sourceRoot = ownerOf(candidate.getFullName());
            for (JavaClass target : foreignDomainDependenciesOf(candidate)) {
                events.add(SimpleConditionEvent.violated(candidate,
                        "%s (owned by %s) depends on the domain class %s (owned by %s)".formatted(
                                candidate.getFullName(), sourceRoot, target.getFullName(),
                                ownerOf(target.getFullName()))));
            }
        }
    }

    /**
     * Collects the domain classes a class depends on that a different ownership root owns.
     *
     * @param candidate the class whose direct dependencies are read; never {@code null}
     * @return the foreign domain classes it depends on, empty when there are none
     */
    private static Set<JavaClass> foreignDomainDependenciesOf(JavaClass candidate) {
        String sourceRoot = ownerOf(candidate.getFullName());
        if (sourceRoot == null) {
            return Set.of();
        }

        java.util.Set<JavaClass> foreign = new java.util.LinkedHashSet<>();
        for (com.tngtech.archunit.core.domain.Dependency dependency
                : candidate.getDirectDependenciesFromSelf()) {
            JavaClass target = dependency.getTargetClass();
            String targetRoot = ownerOf(target.getFullName());

            // WHY : Assumptions: a self-dependency and a dependency on a type outside the nine roots
            //       are both skipped before any domain test runs. The first is what a class does to its
            //       own nested types and would otherwise be reported against itself; the second has no
            //       owner in this contract, so there is no boundary for it to cross.
            if (targetRoot == null || targetRoot.equals(sourceRoot)) {
                continue;
            }
            if (isDomainPackage(target.getPackageName())) {
                foreign.add(target);
            }
        }
        return foreign;
    }

    /**
     * Asserts that a class names no binary floating-point type in any member position.
     */
    private static final class NoBinaryFloatingPointCondition extends ArchCondition<JavaClass> {

        /**
         * Describes the condition in the terms a failure report will use.
         */
        private NoBinaryFloatingPointCondition() {
            super("declare no field, parameter or return type that is float, double, Float or Double");
        }

        /**
         * Records one violation for each member position that names a forbidden type.
         *
         * <p>Assumptions: the three position families are inspected separately and each is reported
         * with the position it was found in, because the correction differs: a field is a
         * representation choice, a parameter is an accepted input, and a return type is a published
         * output that a caller may already be routing through a binary type of its own.</p>
         *
         * @param candidate the class being checked; never {@code null}
         * @param events the sink the library collects violations in; never {@code null}
         */
        @Override
        public void check(JavaClass candidate, ConditionEvents events) {
            for (JavaField field : candidate.getFields()) {
                if (isForbidden(field.getRawType())) {
                    events.add(SimpleConditionEvent.violated(candidate,
                            "the field %s is declared %s".formatted(
                                    field.getFullName(), field.getRawType().getName())));
                }
            }

            for (JavaCodeUnit codeUnit : candidate.getCodeUnits()) {
                List<JavaClass> parameterTypes = codeUnit.getRawParameterTypes();
                for (int position = 0; position < parameterTypes.size(); position++) {
                    JavaClass parameterType = parameterTypes.get(position);
                    if (isForbidden(parameterType)) {
                        events.add(SimpleConditionEvent.violated(candidate,
                                "%s takes %s at parameter position %d".formatted(
                                        codeUnit.getFullName(), parameterType.getName(), position)));
                    }
                }
            }

            for (JavaMethod method : candidate.getMethods()) {
                if (isForbidden(method.getRawReturnType())) {
                    events.add(SimpleConditionEvent.violated(candidate,
                            "%s returns %s".formatted(
                                    method.getFullName(), method.getRawReturnType().getName())));
                }
            }
        }

        /**
         * Reports whether a type is one of the four forbidden binary floating-point types.
         *
         * @param type the type to classify; never {@code null}
         * @return {@code true} when its name is in {@link #FORBIDDEN_MONEY_TYPES}
         */
        private static boolean isForbidden(JavaClass type) {
            return FORBIDDEN_MONEY_TYPES.contains(type.getName());
        }
    }
}
