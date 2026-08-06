package com.carddemo.transaction.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Evaluates the three layering families A1, A2 and A3 against this module's own compiled classes,
 * with the empty-set tolerance the shared gate needs removed.
 *
 * <p>Purpose: the shared kernel publishes one rules class that runs in nine modules, so it has to
 * tolerate a family whose subject set is empty -- the kernel itself declares no {@code domain}
 * package at all. A tolerant family reports the same pass whether it examined every class in the
 * module or none of them. This class evaluates the same three families against
 * {@code com.carddemo.transaction} and asserts, first, that each family actually had subjects here,
 * so a pass reported by this module means a boundary was checked rather than skipped.
 *
 * <p>Assumptions: this class is ADDITIVE to the shared gate and never a replacement for it. The
 * {@code architecture-rules} Surefire execution declared in {@code services/pom.xml} narrows its
 * scan with an {@code includes} element naming the shared class alone, and a module cannot widen
 * that back, because Maven resolves an execution's own configuration ahead of plugin-level
 * configuration a module declares. The shared families therefore still run here through that
 * execution; what they cannot do is assert that they matched anything in this module, which is the
 * gap this class closes.
 *
 * <p>Assumptions: the simple name is a contract rather than a preference. It must never be a second
 * class named {@code LayeringRulesTest}, because the shared execution selects its class through an
 * {@code includes} pattern ending in that simple name written as a literal, with no package
 * qualifier to tell two classes apart. A second class of that name in this module would be matched
 * by the same pattern, so an execution meant to run one class would run two whose reports are
 * distinguishable only by the module they ran in.
 *
 * <p>Alternatives Considered: relying on the shared gate alone and adding nothing here. Rejected on
 * the empty-set argument above, and on a second ground that is specific to this module: the money
 * families the shared class enforces are scoped to the shared kernel's own money package, so no
 * shared rule inspects a member declared on this context's entities, transfer objects or mappers --
 * which is exactly where an amount transcribed from
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10 is carried.
 *
 * <p>Trade-offs: the three rules are exposed to this package so that
 * {@link MoneyPathGateProofTest} can prove the money family bites by evaluating the same rule object
 * rather than a re-declaration of it. The accepted cost is that a member of this class is read from
 * outside it. The alternative was for the proof to rebuild the condition, which would prove that a
 * copy of the gate can fail while saying nothing about the gate the build actually runs -- the one
 * outcome a proof test must not produce.
 *
 * <p>Assumptions: this gate is binary. A Maven, JUnit or ArchUnit outcome here passes or it fails.
 * The graded condition-code rubric under which a warning-level result is a green state belongs to
 * the COBOL parity oracle under {@code tests/} alone, and no tolerance, warning tier or arithmetic
 * on a return code is introduced here.
 */
class TransactionLayeringRulesTest {

    /**
     * The package root of every class this module owns.
     */
    private static final String MODULE_ROOT = "com.carddemo.transaction";

    /**
     * The package identifier of a domain model, matched by segment in any owning root.
     */
    private static final String DOMAIN_PACKAGE_IDENTIFIER = "..domain..";

    /**
     * The package identifiers in which this context carries money.
     *
     * <p>Assumptions: an amount reaches three layers of this module and no others. It is stored on an
     * entity in {@code domain}, published on a record in {@code dto}, and converted between the two in
     * {@code mapper}. Naming the three rather than the whole module is what keeps the family from
     * rejecting a legitimate non-monetary quantity elsewhere, and a gate that fails on correct code is
     * one that gets switched off.</p>
     */
    private static final List<String> MONEY_CARRYING_PACKAGE_IDENTIFIERS =
            List.of(MODULE_ROOT + ".domain..", MODULE_ROOT + ".dto..", MODULE_ROOT + ".mapper..");

    /**
     * The infrastructure roots a domain type may not depend on.
     *
     * <p>Assumptions: both AWS SDK generations are named because both are importable, the v2 artifacts
     * publishing under {@code software.amazon.awssdk} and the v1 artifacts under
     * {@code com.amazonaws}. Naming only the current one would leave the older coordinate as an
     * unguarded route into a domain package.</p>
     */
    private static final List<String> FORBIDDEN_INFRASTRUCTURE_PACKAGE_IDENTIFIERS = List.of(
            "software.amazon.awssdk..",
            "com.amazonaws..",
            "org.springframework.web..",
            "jakarta.servlet..");

    /**
     * The domain packages of the seven sibling contexts, which this module may not import.
     *
     * <p>Assumptions: the eight service roots and the shared kernel are fixed by the migrated
     * decomposition, so a crossing is detectable from a package name alone with no annotation or
     * registry to keep in step with the source. The shared kernel is deliberately absent from this
     * list: it is the Java analogue of compiling every reference program against one copybook include
     * path, so importing from it is not a crossing.</p>
     */
    private static final List<String> FOREIGN_DOMAIN_PACKAGE_IDENTIFIERS = List.of(
            "com.carddemo.auth.domain..",
            "com.carddemo.account.domain..",
            "com.carddemo.card.domain..",
            "com.carddemo.reference.domain..",
            "com.carddemo.batch.domain..",
            "com.carddemo.authorization.domain..",
            "com.carddemo.reporting.domain..");

    /**
     * The four binary floating-point type names forbidden wherever money is carried.
     *
     * <p>Assumptions: the names are the ones the importer reports for a raw member type, so a
     * primitive appears as its keyword and a wrapper as its fully qualified name. Both forms are
     * listed because boxing loses exactness exactly as the primitive does while reading as a different
     * type in source.</p>
     */
    private static final Set<String> FORBIDDEN_BINARY_FLOATING_POINT_TYPES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    /**
     * The simple name of a compiled package descriptor, which carries no member for a rule to inspect.
     */
    private static final String PACKAGE_DESCRIPTOR_SIMPLE_NAME = "package-info";

    /**
     * Every production class of this module that the executing build compiled.
     */
    private static final JavaClasses MODULE_CLASSES = importModuleClasses();

    /**
     * Family A1: no domain type of this module depends on infrastructure or transport.
     */
    private static final ArchRule DOMAIN_IS_ISOLATED_FROM_FRAMEWORK_AND_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage(DOMAIN_PACKAGE_IDENTIFIER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FORBIDDEN_INFRASTRUCTURE_PACKAGE_IDENTIFIERS.toArray(String[]::new))
            .as("A1: no class in a " + DOMAIN_PACKAGE_IDENTIFIER + " package of " + MODULE_ROOT
                    + " depends on an AWS SDK, Spring Web or Jakarta Servlet type");

    /**
     * Family A2: no class of this module depends on a sibling context's domain model.
     */
    private static final ArchRule NO_DEPENDENCY_ON_A_FOREIGN_DOMAIN_MODEL = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FOREIGN_DOMAIN_PACKAGE_IDENTIFIERS.toArray(String[]::new))
            .as("A2: no class in " + MODULE_ROOT
                    + " depends on a domain class owned by a different CardDemo package root");

    /**
     * The condition family A3 decides violations from, held separately so its proof can evaluate it.
     */
    private static final ArchCondition<JavaClass> DECLARES_A_BINARY_FLOATING_POINT_MEMBER =
            declareABinaryFloatingPointMember();

    /**
     * Family A3: nothing that carries money in this module declares a binary floating-point member.
     */
    private static final ArchRule MONEY_PATH_DECLARES_NO_BINARY_FLOATING_POINT = noClasses()
            .that()
            .resideInAnyPackage(MONEY_CARRYING_PACKAGE_IDENTIFIERS.toArray(String[]::new))
            .should(DECLARES_A_BINARY_FLOATING_POINT_MEMBER)
            .as("A3: no type in " + MONEY_CARRYING_PACKAGE_IDENTIFIERS
                    + " declares a float, double, Float or Double field, parameter or return type");

    /**
     * Asserts that each of the three families had subjects in this module, so none of them can report
     * success by having examined nothing.
     *
     * <p>Refactoring Rationale: this is the assertion the shared gate cannot make. That class runs in
     * nine modules and so must tolerate an empty subject set, which leaves it unable to separate "no
     * violation here" from "no subjects here". Both outcomes print the same line in a build log, and
     * the second one is what a silently mis-scoped or renamed package produces. Asserting the subject
     * counts turns this module's pass into evidence.
     *
     * <p>Assumptions: package descriptors are discounted. A package holding nothing but its own
     * charter still yields one imported class, which satisfies a bare non-empty assertion while
     * leaving a family with no field, parameter or return type to inspect. The descriptor is
     * identified by its simple name, which the compiler fixes and which no ordinary Java type can
     * carry because a hyphen is not legal in an identifier, so the match is exact rather than
     * heuristic.
     *
     * <p>Trade-offs: the failure text names package identifiers and nothing read at run time. A
     * gate's diagnostic is read from build logs that are retained and shared more widely than the
     * build itself, so it carries no imported class list and no resolved filesystem location.
     */
    @Test
    @DisplayName("guard: all three families had subjects in this module")
    void everyFamilyHasSubjectsInThisModule() {
        assertThat(MODULE_CLASSES)
                .withFailMessage(
                        "No production class was imported from '%s'. Every family below would then pass"
                                + " without evaluating anything. Confirm that this module compiled its"
                                + " main classes before the gate ran.",
                        MODULE_ROOT)
                .isNotEmpty();

        assertThat(declaredTypesIn(MODULE_ROOT + ".domain.."))
                .withFailMessage(
                        "No declared type was imported from the domain package of '%s', so family A1"
                                + " would have no subject. This context owns four entities transcribed"
                                + " from the reference record layouts, so an empty selection means the"
                                + " package was renamed out of the matched shape rather than that there"
                                + " was nothing to find.",
                        MODULE_ROOT)
                .isNotEmpty();

        MONEY_CARRYING_PACKAGE_IDENTIFIERS.forEach(identifier ->
                assertThat(declaredTypesIn(identifier))
                        .withFailMessage(
                                "No declared type was imported from money-carrying package '%s', so"
                                        + " family A3 would have no member to inspect there.",
                                identifier)
                        .isNotEmpty());
    }

    /**
     * Checks family A1, that no domain type of this module depends on infrastructure or transport.
     *
     * <p>Alternatives Considered: banning those roots across the whole module rather than inside
     * {@code ..domain..}. Rejected specifically rather than stylistically: this module's
     * {@code config} package legitimately holds Spring Web types, and a module-wide ban would reject
     * a resource-server configuration that is correct exactly as written. The boundary the migration
     * draws is around the domain layer, so the rule is drawn there too.
     *
     * <p>Assumptions: the value of the boundary is testability. The domain of this module holds the
     * entities derived from the reference record layouts, and a unit test of a transcribed validation
     * rule can only run without standing up infrastructure while those entities import none.
     */
    @Test
    @DisplayName("A1: a ..domain.. type of this module depends on no AWS SDK, Spring Web or Servlet type")
    void domainPackagesAreIsolatedFromFrameworkAndInfrastructure() {
        DOMAIN_IS_ISOLATED_FROM_FRAMEWORK_AND_INFRASTRUCTURE.check(MODULE_CLASSES);
    }

    /**
     * Checks family A2, that this module reads no sibling context's domain model.
     *
     * <p>Assumptions: this module and the batch context agree through the physical schema and never
     * through code. The batch context legitimately writes rows into the {@code ledger} schema this
     * module owns, and it declares its own local mappings for those tables rather than importing
     * these entities, so the agreement is at the table level in both directions.
     *
     * <p>Assumptions: the rule runs in the direction that is easy to overlook. A dependency on a
     * sibling's domain type would compile without Maven resolving anything new whenever the two
     * modules already share a classpath, which is why the prohibition is asserted rather than assumed
     * to be prevented by the build.
     */
    @Test
    @DisplayName("A2: this module depends on no domain class owned by a different CardDemo root")
    void noDependencyOnAForeignDomainModel() {
        NO_DEPENDENCY_ON_A_FOREIGN_DOMAIN_MODEL.check(MODULE_CLASSES);
    }

    /**
     * Checks family A3, that nothing carrying money in this module declares a binary floating-point
     * member.
     *
     * <p>Assumptions: the family is grounded in the baseline rather than in a preference for decimals.
     * The money fields of the two records this context owns are zoned decimal, declared
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10 and
     * {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy} line 9, so every value they
     * hold is an exact number of cents. A binary floating-point type cannot represent most exact cent
     * values, so an amount routed through one returns plausible and slightly wrong and surfaces far
     * downstream as an unexplained one-cent difference rather than as an error at the conversion that
     * caused it.
     *
     * <p>Assumptions: all three member positions are inspected because each alone loses exactness and
     * they fail differently. A field stores an amount inexactly. A parameter accepts an amount already
     * rounded, so the loss happened in the caller and the exact type never saw the original value. A
     * return type publishes an exact internal amount inexactly, which is the case a reader is least
     * likely to look for because the arithmetic inside the method can be entirely correct.
     */
    @Test
    @DisplayName("A3: nothing carrying money in this module declares a float, double, Float or Double")
    void moneyPathDeclaresNoBinaryFloatingPoint() {
        MONEY_PATH_DECLARES_NO_BINARY_FLOATING_POINT.check(MODULE_CLASSES);
    }

    /**
     * Exposes the condition family A3 decides violations from, so that its proof test evaluates this
     * exact condition rather than a copy.
     *
     * <p>Trade-offs: a condition object is read from outside the class that declares it, and the
     * package selection it is paired with is not. Accepted, because the selection is a literal list of
     * package identifiers that a reader can verify by eye, whereas the condition is the part that
     * decides what counts as a violation and the part that can be got subtly wrong. The alternative was
     * for the proof to rebuild the condition, which would demonstrate only that a copy of the gate can
     * fail -- and the copy and the gate can then drift apart silently while the proof keeps passing.
     *
     * <p>Assumptions: the proof pairs this condition with a selection naming its own package, because a
     * fixture cannot be placed in a money-carrying production package without putting the prohibited
     * construct into a package the gate protects. Widening the selection instead would make the
     * enforcement above evaluate the fixture and fail by construction.
     *
     * @return the condition family A3 applies, for evaluation against a deliberately violating subject
     */
    static ArchCondition<JavaClass> moneyPathCondition() {
        return DECLARES_A_BINARY_FLOATING_POINT_MEMBER;
    }

    /**
     * Builds the condition that reports a binary floating-point field, parameter or return type.
     *
     * <p>Assumptions: raw types are compared by name rather than by class literal, so a primitive and
     * its wrapper are both reachable through one lookup and neither requires loading a class the
     * importer has already resolved.
     *
     * @return a condition satisfied by a class declaring at least one such member, never {@code null}
     */
    private static ArchCondition<JavaClass> declareABinaryFloatingPointMember() {
        return new ArchCondition<>("declare a float, double, Float or Double field, parameter or"
                + " return type") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                for (JavaField field : type.getFields()) {
                    if (isForbidden(field.getRawType())) {
                        events.add(SimpleConditionEvent.satisfied(field,
                                "A3: field " + field.getFullName() + " is declared "
                                        + field.getRawType().getName()));
                    }
                }
                for (JavaCodeUnit codeUnit : type.getCodeUnits()) {
                    if (isForbidden(codeUnit.getRawReturnType())) {
                        events.add(SimpleConditionEvent.satisfied(codeUnit,
                                "A3: " + codeUnit.getFullName() + " returns "
                                        + codeUnit.getRawReturnType().getName()));
                    }
                    codeUnit.getRawParameterTypes().stream()
                            .filter(TransactionLayeringRulesTest::isForbidden)
                            .forEach(parameterType -> events.add(SimpleConditionEvent.satisfied(
                                    codeUnit,
                                    "A3: " + codeUnit.getFullName() + " accepts a parameter declared "
                                            + parameterType.getName())));
                }
            }
        };
    }

    /**
     * Reports whether a raw type is one of the four forbidden binary floating-point types.
     *
     * @param rawType the resolved raw type of a field, parameter or return position; must not be
     *     {@code null}
     * @return {@code true} when the type is forbidden wherever money is carried
     */
    private static boolean isForbidden(JavaClass rawType) {
        return FORBIDDEN_BINARY_FLOATING_POINT_TYPES.contains(rawType.getName());
    }

    /**
     * Selects the declared types of a package, discounting the compiled package descriptor.
     *
     * @param packageIdentifier the ArchUnit package identifier to select from; must not be
     *     {@code null}
     * @return the imported classes of that package that are not package descriptors
     */
    private static JavaClasses declaredTypesIn(String packageIdentifier) {
        return MODULE_CLASSES
                .that(resideInAnyPackage(packageIdentifier))
                .that(not(JavaClass.Predicates.simpleName(PACKAGE_DESCRIPTOR_SIMPLE_NAME)));
    }

    /**
     * Imports this module's compiled production classes, excluding every test source.
     *
     * <p>Assumptions: the test exclusion is what keeps this class, its two proof siblings and their
     * deliberately violating fixtures out of the analysed set. Without it, family A3 would be
     * evaluated against a fixture authored to break it and this gate would fail by construction.
     *
     * @return the module's production classes, never {@code null}
     */
    private static JavaClasses importModuleClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(MODULE_ROOT)
                .that(not(resideInAPackage(MODULE_ROOT + ".architecture..")))
                .as("production classes of " + MODULE_ROOT + " visible to the executing module");
    }
}
