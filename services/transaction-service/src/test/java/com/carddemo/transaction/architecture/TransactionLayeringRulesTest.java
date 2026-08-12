package com.carddemo.transaction.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Evaluates the three layering families A1, A2 and A3 against the compiled CardDemo classes on this
 * module's own test classpath, strictly and with every subject set asserted to be non-empty.
 *
 * <p>Purpose: the shared kernel publishes one rules class that runs in nine modules, so its own A1
 * and A2 carry {@code allowEmptyShould(true)} -- the kernel declares no {@code domain} package at
 * all, and ArchUnit fails a rule whose expectation saw no classes unless the rule says otherwise. A
 * tolerant rule reports the same pass whether it examined every class in the module or none of them.
 * This class evaluates the same three boundaries with that tolerance removed, narrows the money
 * family to {@code com.carddemo.transaction} so its subject set cannot be satisfied by kernel classes
 * alone, and asserts before any of them runs that each family had subjects here. A pass reported by
 * this class therefore means a boundary was checked rather than skipped.
 *
 * <p><b>Parameters, return values, exceptions or errors.</b> A JUnit test class is instantiated by
 * the engine and takes no parameters; every test method below returns {@code void} and declares no
 * exception, and each member that does take a parameter or return a value documents it at its own
 * declaration. The inapplicability is stated rather than left silent, because the Explainability rule
 * names a docstring that omits parameters or return values among its forbidden patterns, and a reader
 * has to be able to tell a declared inapplicability from an oversight.
 *
 * <p>Assumptions: this class is ADDITIVE to the shared gate and never a replacement for it. The
 * shared rules class reaches this module through four cooperating declarations, all present in this
 * reactor: {@code services/common-lib/pom.xml} binds {@code maven-jar-plugin}'s {@code test-jar} goal
 * narrowed to the shared architecture package; {@code services/transaction-service/pom.xml} consumes
 * that artifact with {@code <type>test-jar</type>} at test scope and declares
 * {@code com.tngtech.archunit:archunit-junit5} at test scope beside it, because test scope is not
 * transitive and a discovered rules class without the assertion API fails to load rather than failing
 * an assertion; and the {@code architecture-rules} Surefire execution in {@code services/pom.xml}
 * names the shared kernel in {@code dependenciesToScan} so that class is collected from the artifact
 * and evaluated against this module's compiled classes. What that arrangement cannot do is assert
 * that the shared families matched anything here, which is the gap this class closes.
 *
 * <p>Assumptions: the simple name is a contract rather than a preference, and the package charter
 * beside this file states the reservation in full. It must never be a second class named
 * {@code LayeringRulesTest}, because the {@code architecture-rules} execution selects the shared
 * class through an {@code includes} element whose pattern ends in that simple name written as a
 * literal, with no package qualifier to tell two classes apart. A second class of that name in this
 * module would be matched by the same pattern, so an execution meant to run one shared class would
 * run two whose reports are distinguishable only by the module they ran in.
 *
 * <p>Alternatives Considered: extending or otherwise reusing the shared rules class instead of
 * declaring these families here. Rejected on a concrete property of that class rather than on taste:
 * its A1 and A2 are empty-tolerant by necessity and its money family is scoped to the whole analysed
 * root, so an evaluation of it in this module cannot distinguish "this module compiled and complied"
 * from "this module's classes never reached the importer". The three families below are the same
 * boundaries evaluated under ArchUnit's strict default over an analysed graph this class asserts the
 * shape of.
 *
 * <p>Trade-offs: the money family's condition is exposed to this package so that
 * {@link MoneyPathGateProofTest} can prove the family bites by evaluating the same condition object
 * rather than a re-declaration of it. The accepted cost is that a member of this class is read from
 * outside it. The alternative was for the proof to rebuild the condition, which would prove that a
 * copy of the gate can fail while saying nothing about the gate the build actually runs -- and the
 * copy and the gate then drift apart silently while the proof keeps passing.
 *
 * <p>Assumptions: this gate is binary. A Maven, JUnit, Checkstyle or ArchUnit outcome here passes or
 * it fails. The graded condition-code rubric under which a warning-level result is a green state
 * belongs to the COBOL parity oracle under {@code tests/} alone, and no tolerance, warning tier or
 * arithmetic on a return code is introduced here.
 */
class TransactionLayeringRulesTest {

    /**
     * The package root of every class this migration owns, and the root the importer scans.
     *
     * <p>Assumptions: the analysed graph is deliberately wider than this module. The shared kernel is
     * on this module's compile classpath, so scanning the whole root puts both halves of the
     * dependency arrow in front of family A2 -- a service reaching a sibling's domain model, and the
     * kernel acquiring a dependency on a service's domain model. Scanning only
     * {@code com.carddemo.transaction} would leave the second direction unobservable here, and would
     * also leave A1's tolerance of the kernel's legitimate transport adapters unverified rather than
     * verified.</p>
     */
    private static final String ANALYSED_ROOT = "com.carddemo";

    /**
     * The package root of the classes this module itself compiles.
     */
    private static final String MODULE_ROOT = "com.carddemo.transaction";

    /**
     * The ArchUnit package identifier selecting this module's own classes and every subpackage.
     */
    private static final String MODULE_ROOT_IDENTIFIER = MODULE_ROOT + "..";

    /**
     * The shared kernel's root, which every service may depend on and which owns no service's data.
     *
     * <p>Assumptions: the kernel is modelled as a shared kernel on purpose. It is the Java analogue
     * of compiling every reference program against one copybook include path, so a dependency on it
     * is not a boundary crossing, while a dependency from it onto a service's domain model is -- that
     * would make the kernel depend on data it does not own and invert the arrow the decomposition
     * draws.</p>
     */
    private static final String SHARED_KERNEL_ROOT = "com.carddemo.common";

    /**
     * The eight service roots of the migrated decomposition, each owning its own schema.
     *
     * <p>Assumptions: the roots are fixed by the decomposition rather than discovered at run time, so
     * a crossing is decidable from a package name alone with no annotation, registry or manifest to
     * keep in step with the source.</p>
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
     * The nine ownership roots family A2 resolves ownership against: the shared kernel and the eight
     * services.
     *
     * <p>Assumptions: these nine are the WHOLE ownership contract. A package outside them is
     * third-party and out of scope for A2, which is why an unresolved root makes a class a non-subject
     * rather than a violation.</p>
     */
    private static final List<String> OWNERSHIP_ROOTS = concatenatedOwnershipRoots();

    /**
     * The ArchUnit package identifier of a domain model, matched as a segment in any owning root.
     */
    private static final String DOMAIN_PACKAGE_IDENTIFIER = "..domain..";

    /**
     * The one package segment that names a domain model.
     *
     * <p>Assumptions: ownership is resolved against a DOT-DELIMITED segment rather than against a
     * substring. A containment test on the raw package name would also match
     * {@code com.carddemo.transaction.domainhelper} and a nested {@code subdomain} package, so a
     * boundary crossing could be reported against a package that holds no domain model at all.</p>
     */
    private static final String DOMAIN_PACKAGE_SEGMENT = "domain";

    /**
     * The ArchUnit package identifier of every architecture test package, excluded from the graph.
     */
    private static final String ARCHITECTURE_PACKAGE_IDENTIFIER = "..architecture..";

    /**
     * The account context's domain package, which this module reaches over HTTP and never by import.
     */
    private static final String ACCOUNT_CONTEXT_DOMAIN_IDENTIFIER = "com.carddemo.account.domain..";

    /**
     * The infrastructure and transport roots a domain type may not depend on.
     *
     * <p>Assumptions: both AWS SDK generations are named because both are importable, the v2
     * artifacts publishing under {@code software.amazon.awssdk} and the v1 artifacts under
     * {@code com.amazonaws}. Naming only the current one would leave the older coordinate as an
     * unguarded route into a domain package.</p>
     */
    private static final List<String> FORBIDDEN_INFRASTRUCTURE_PACKAGE_IDENTIFIERS = List.of(
            "software.amazon.awssdk..",
            "com.amazonaws..",
            "org.springframework.web..",
            "jakarta.servlet..");

    /**
     * The four binary floating-point type names forbidden on this module's classes.
     *
     * <p>Assumptions: the names are the ones the importer reports for a raw member type, so a
     * primitive appears as its keyword and a wrapper as its fully qualified name. Both forms are
     * listed because a member declared with the wrapper reaches binary floating-point arithmetic by
     * the same route as the primitive while reading as a different type in source, so a family naming
     * only the primitives would pass a class that had merely boxed the defect.</p>
     */
    private static final Set<String> FORBIDDEN_BINARY_FLOATING_POINT_TYPES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    /**
     * The simple name of a compiled package descriptor, which carries no member for a rule to
     * inspect.
     */
    private static final String PACKAGE_DESCRIPTOR_SIMPLE_NAME = "package-info";

    /**
     * The simple-name suffixes by which the two runners of this reactor collect a test class.
     *
     * <p>Assumptions: these are the suffixes Surefire and Failsafe select on in this reactor, so a
     * class carrying one of them in the analysed graph means a test source reached the importer.
     * Asserting their absence is what proves the exclusion below actually took effect.</p>
     */
    private static final List<String> TEST_CLASS_NAME_SUFFIXES = List.of("Test", "IT");

    /**
     * Every production CardDemo class the executing build put on this module's test classpath.
     */
    private static final JavaClasses ANALYSED_CLASSES = importAnalysedClasses();

    /**
     * Family A1: no domain type in the analysed graph depends on infrastructure or transport.
     *
     * <p>Refactoring Rationale: the ports-and-adapters boundary this migration draws around the
     * domain layer was, in the reference baseline, nothing at all -- a COBOL program addresses VSAM,
     * the terminal and the queue from the same procedure division, so there was no boundary to
     * preserve and one had to be established. Establishing it as a review convention would leave it
     * decaying as the domain grows, one plausible import at a time, with no build ever objecting.
     * Expressed as this rule it is an assertion the build makes, so the boundary cannot decay
     * silently.</p>
     *
     * <p>Alternatives Considered: banning {@code org.springframework..} module-wide instead of banning
     * the transport packages inside {@code ..domain..}. Rejected on named code rather than on
     * principle: {@code com.carddemo.common.web.CorrelationIdFilter} and
     * {@code com.carddemo.common.error.GlobalExceptionHandler} are in the analysed graph and use web
     * and servlet types correctly, because an adapter is where transport belongs, and
     * {@code com.carddemo.transaction.config} holds a resource-server chain that is correct exactly as
     * written. A module-wide ban would reject all three, and a rule that fails on correct code is one
     * that gets switched off.</p>
     *
     * <p>Assumptions: the value of the boundary is testability. The domain of this module holds the
     * entities transcribed from the reference record layouts, and a unit test of a transcribed
     * validation rule can only run without standing up infrastructure while those entities import
     * none. The domain does depend on {@code jakarta.persistence} and {@code org.hibernate}, which is
     * why the prohibition lists the transport and infrastructure-client roots by name instead of
     * banning frameworks as a category.</p>
     */
    private static final ArchRule DOMAIN_IS_ISOLATED_FROM_FRAMEWORK_AND_INFRASTRUCTURE = noClasses()
            .that()
            .resideInAPackage(DOMAIN_PACKAGE_IDENTIFIER)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FORBIDDEN_INFRASTRUCTURE_PACKAGE_IDENTIFIERS.toArray(String[]::new))
            .as("A1: no class in a " + DOMAIN_PACKAGE_IDENTIFIER + " package under " + ANALYSED_ROOT
                    + " depends on an AWS SDK, Spring Web or Jakarta Servlet type")
            .because("a domain type must stay expressible without a transport or an infrastructure"
                    + " client, so a transcribed business rule can be exercised without standing up"
                    + " the platform it happens to be deployed on");

    /**
     * Selects a class that reaches a domain model at all, whoever owns it.
     *
     * <p>Assumptions: the selection is deliberately wider than the prohibition, and narrowing it to
     * violating classes would be a mistake rather than an economy. ArchUnit prints the subject set of
     * a rule beside its result, so selecting every class that touches a domain model makes the
     * population the rule examined legible; a predicate that pre-filtered violations would report an
     * empty subject set on a healthy module and say nothing about coverage.</p>
     *
     * <p>Assumptions: reachability is asked of ArchUnit's dependency graph rather than of import
     * statements, so a dependency expressed through a field type, a method or constructor signature,
     * an annotation, a supertype or a generic type argument is found on the same terms as a plain
     * import. A fully qualified reference with no import statement at all is found too, which is the
     * case a text search over source files misses.</p>
     */
    private static final DescribedPredicate<JavaClass> DEPEND_ON_A_DOMAIN_PACKAGE = describe(
            "depend on a class in a package with a " + DOMAIN_PACKAGE_SEGMENT + " segment",
            candidate -> candidate.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency ->
                            containsDomainSegment(dependency.getTargetClass().getPackageName())));

    /**
     * Family A2: no CardDemo root depends on a domain model owned by a different CardDemo root.
     *
     * <p>Refactoring Rationale: the reference baseline has no bounded contexts to keep apart. Every
     * online program {@code COPY}s whatever record layout it needs from one shared include path, so
     * ownership of a record was a convention among programmers and nothing a build could check. The
     * migration turns each context's record layouts into a schema one service owns; expressing that
     * ownership as this rule replaces the convention with a test-enforced contract, so one service
     * cannot silently take a second share in another's domain model.</p>
     *
     * <p>Assumptions: a bounded context's domain model is the in-memory shape of the schema it owns,
     * so a second context reading it directly gives that schema a second owner, and the two can then
     * disagree about a column's type or scale with neither build noticing.</p>
     *
     * <p>Assumptions: the prohibition runs in BOTH directions across the nine roots, and the two
     * neighbours a reader will ask about are the reason it has to. First, {@code batch-service} and
     * this module are two views of one physical {@code ledger} schema, and the parent POM states the
     * boundary: no module may declare a Maven dependency on another service module, the only permitted
     * intra-reactor dependency is {@code common-lib}, and cross-schema reach is granted in SQL rather
     * than resolved in Maven. So a {@code batch} class depending on {@code transaction.domain}, or the
     * reverse, is a breach even though the two agree on column names, types and scale -- agreement on
     * a table is exactly what makes the import look harmless. Second, the shared kernel may be
     * depended upon by any service and must never acquire a dependency on a service's domain model,
     * which is the direction that would invert the arrow the decomposition draws.</p>
     *
     * <p>Alternatives Considered: four approaches were evaluated and rejected. (i) Nine duplicated
     * pairwise rules, one per root: rejected because nine copies of one invariant drift, and the copy
     * that drifts is the one nobody re-reads. (ii) A containment test on the package name: rejected
     * because it matches unintended packages such as {@code transaction.domainhelper} and a nested
     * {@code subdomain}, so it would report a crossing against a package holding no domain model.
     * (iii) A Checkstyle {@code ImportControl} file: rejected because it is deliberately absent from
     * {@code config/checkstyle/checkstyle.xml} so that layering has exactly one owner, and two
     * enforcement points for one invariant drift apart -- and it would see only import statements, not
     * the annotation, supertype and signature dependencies the graph above reports. (iv) A second rule
     * class in this package: rejected because a rule split across two files can be weakened by editing
     * the one that reads as maintenance.</p>
     */
    private static final ArchRule NO_ROOT_DEPENDS_ON_ANOTHER_ROOTS_DOMAIN_MODEL = classes()
            .that(DEPEND_ON_A_DOMAIN_PACKAGE)
            .should(new ForeignRootDomainDependencyCondition())
            .as("A2: no class under one of the " + OWNERSHIP_ROOTS.size() + " CardDemo ownership roots"
                    + " depends on a " + DOMAIN_PACKAGE_SEGMENT
                    + " class owned by a different one of them")
            .because("a context's domain model is the in-memory shape of the schema it owns, so a"
                    + " second context reading it directly gives that schema a second owner and the"
                    + " two can then disagree about it without either build noticing");

    /**
     * Family A2, applied to the one sibling context this module exchanges data with at run time.
     *
     * <p>Assumptions: the account context is reached over HTTP and never by import.
     * {@code com.carddemo.transaction.service.RestAccountContextClient} calls the cross-reference
     * lookup with a {@code RestClient} and answers with the module-local
     * {@code AccountContextClient.CardXref} record, which is this module's own shape for the two
     * fields it needs rather than the owning context's entity. Stating that as its own assertion, and
     * not only as a case of the rule above, means the report of a breach names the neighbour instead
     * of leaving a reader to work out which root was crossed.</p>
     *
     * <p>Trade-offs: this assertion covers the Java dependency and deliberately not the physical
     * schema. {@code com.carddemo.transaction.repository.AccountBalanceRepository} does reach
     * {@code account.accounts}, in native SQL and mapping no entity, so that bill payment stays one
     * ACID commit rather than a saga with an observable half-posted state; that is a landed and
     * separately documented decision, recorded in the repository package charter, granted narrowly in
     * {@code data-migration/sql/V0__schemas_and_roles.sql} and checked column by column by
     * {@code com.carddemo.transaction.repository.AccountBalanceSchemaAgreementTest}. Asserting "no
     * access to the account schema" here would therefore fail correct code and pull a boundary that
     * already has an owner into a second one.</p>
     */
    private static final ArchRule NO_DEPENDENCY_ON_THE_ACCOUNT_CONTEXT_DOMAIN_MODEL = noClasses()
            .that()
            .resideInAPackage(MODULE_ROOT_IDENTIFIER)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ACCOUNT_CONTEXT_DOMAIN_IDENTIFIER)
            .as("A2: no class in " + MODULE_ROOT_IDENTIFIER + " depends on a class in "
                    + ACCOUNT_CONTEXT_DOMAIN_IDENTIFIER)
            .because("the card cross-reference this module needs is fetched from the owning context"
                    + " over HTTP and mapped into a local record, so importing that context's entity"
                    + " would replace a versioned contract with a compile-time coupling");

    /**
     * The condition family A3 decides violations from, exposed so its proof evaluates this object.
     *
     * <p>Alternatives Considered: re-expressing the same predicate inside
     * {@link MoneyPathGateProofTest}. Rejected because two copies of one invariant drift, and the
     * proof would then verify the copy rather than the gate the build runs. The house precedent is the
     * reference test suite's own instruction that a record layout be resolved through the compiler
     * copybook path and never duplicated, in {@code tests/README.md} section 12 at lines 534 to 537,
     * which keeps one layout single-sourced from {@code app/cpy/} for the same reason.</p>
     *
     * <p>Assumptions: package-private visibility confines the exposure to this package, whose closed
     * roster is this class, its charter and the two proof tests. What is shared is the part that
     * decides what counts as a violation; what stays proof-local is a literal package identifier a
     * reader verifies by eye. Holding one instance in a field rather than building one per caller is
     * what makes the rule below and the proof evaluate the same object instead of two conditions that
     * are merely equal today.</p>
     */
    static final ArchCondition<JavaClass> MONEY_PATH_CONDITION =
            new BinaryFloatingPointMemberCondition();

    /**
     * Family A3: nothing this module compiles declares a binary floating-point member.
     *
     * <p>Assumptions: the family is grounded in the baseline rather than in a preference for decimals.
     * Every monetary value in this context is zoned decimal with a sign overpunch at exact scale 2,
     * declared {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} line 10 and
     * {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy} line 9, and it is carried
     * onward as {@code NUMERIC(11,2)}. An IEEE-754 binary floating-point type cannot represent most
     * exact cent values, so an amount routed through one comes back plausible and slightly wrong and
     * surfaces at the far end of the pipeline as an unexplained one-cent difference against a golden
     * master rather than as an error at the conversion that caused it. That specific silent failure is
     * what this family prevents.</p>
     *
     * <p>Assumptions: the scope is every class this module compiles, not only the three packages that
     * hold an amount today. In this context a monetary value traverses {@code domain},
     * {@code dto}, {@code mapper}, {@code service}, {@code api} and {@code repository} -- it is stored
     * on an entity, converted by a mapper, reduced inside a repository statement, published on a
     * record and returned by a controller -- so a narrower selection would leave the positions an
     * amount passes through on its way out of the module ungated.</p>
     *
     * <p>Assumptions: this family keeps ArchUnit's strict default and must never be relaxed with
     * {@code allowEmptyShould(true)}. Its subject set is this module's whole compiled tree, so an
     * empty selection cannot mean the layer is legitimately absent; it can only mean the import
     * resolved nothing, and a money rule that reports green over nothing is worse than no rule at
     * all.</p>
     *
     * <p>Assumptions: the rule is phrased with {@code noClasses} and its condition reports a match as
     * SATISFIED, and the two halves of that pairing have to agree. Verified against ArchUnit 1.4.2 by
     * evaluating both spellings: {@code noClasses} inverts every event its condition produces, so
     * pairing it with a condition that reports a match as a violation yields a rule that finds an
     * offending declaration and reports no violation at all. Family A2 above pairs {@code classes}
     * with a violation-reporting condition for the same reason in the opposite direction. The
     * mis-pairing is silent in both directions, which is why {@link MoneyPathGateProofTest} evaluates
     * this exact condition against a declaration authored to be rejected and against one authored to
     * be accepted.</p>
     */
    private static final ArchRule MONEY_PATH_DECLARES_NO_BINARY_FLOATING_POINT = noClasses()
            .that()
            .resideInAPackage(MODULE_ROOT_IDENTIFIER)
            .should(MONEY_PATH_CONDITION)
            .as("A3: no type in " + MODULE_ROOT_IDENTIFIER + " declares a float, double, Float or"
                    + " Double field, constructor or method parameter, or return type")
            .because("an exact fixed-point amount routed through a binary floating-point type comes"
                    + " back plausible and slightly wrong, and the difference then surfaces as an"
                    + " unexplained cent at the end of the pipeline rather than at the conversion"
                    + " that caused it");


    /**
     * Asserts that the analysed graph exists, holds no test source, and gives each of the three
     * families below a non-empty subject set.
     *
     * <p>Refactoring Rationale: this is the assertion the shared gate cannot make for this module.
     * That class runs in nine modules and so must tolerate an empty subject set, which leaves it
     * unable to separate "no violation here" from "no subjects here". Both outcomes print the same
     * line in a build log, and the second is what a mistyped root, an uncompiled module or a missing
     * shared kernel produces -- each of them invisible in the output of the rules themselves.</p>
     *
     * <p>Assumptions: the second assertion is what proves the exclusion in
     * {@link #importAnalysedClasses()} took effect, and the first does not cover it. The shared kernel
     * publishes its architecture package as a test artifact, so in this module that package arrives as
     * ordinary JAR content that no location-based test exclusion removes; ArchUnit's predefined
     * options name the compiler output directories of the two build tools and have no notion of a test
     * JAR. An unfiltered import would therefore be non-empty even in a module that compiled nothing,
     * and the first assertion would pass while every family measured the shared test instead of the
     * code under test. That package also carries a fixture that declares binary floating point on
     * purpose, so leaving it in the graph would additionally break family A3 by construction.</p>
     *
     * <p>Assumptions: package descriptors are discounted where a family needs members to inspect. A
     * package holding nothing but its own charter still yields one imported class, which satisfies a
     * bare non-empty assertion while leaving a family with no field, parameter or return type to
     * examine. The descriptor is identified by the simple name the compiler fixes, which no ordinary
     * Java type can carry because a hyphen is not legal in an identifier, so the match is exact rather
     * than heuristic.</p>
     *
     * <p>Trade-offs: every failure text here names package identifiers, the classpath scope and the
     * suffixes matched, and nothing read at run time. A gate's diagnostic is read from build logs that
     * are retained and shared more widely than the build itself, so it carries no imported class list
     * and no resolved filesystem location. The accepted cost is that a reader who trips the second
     * assertion has to re-run the importer to see which class survived.</p>
     */
    @Test
    @DisplayName("guard: the analysed graph is non-vacuous, holds no test source, and feeds A1, A2 and A3")
    void analysedGraphIsNotVacuous() {
        assertThat(ANALYSED_CLASSES)
                .withFailMessage(
                        "No class was imported from '%s'. Every family below would then pass without"
                                + " evaluating anything. Confirm that this module compiled its main"
                                + " classes and that the shared kernel is on its test classpath"
                                + " before the gate ran.",
                        ANALYSED_ROOT)
                .isNotEmpty();

        assertThat(namesOfAnalysedClassesThatAreTestSources())
                .withFailMessage(
                        "A test source survived into the analysed graph. The importer excludes the"
                                + " compiler test-output directories and every '%s' package, and one of"
                                + " those exclusions no longer covers what reached it: a class whose"
                                + " simple name ends in one of %s is present. Re-run the importer to"
                                + " see which, because a family evaluated over a test source measures"
                                + " the gate instead of the code under test.",
                        ARCHITECTURE_PACKAGE_IDENTIFIER, TEST_CLASS_NAME_SUFFIXES)
                .isEmpty();

        assertThat(declaredTypesIn(DOMAIN_PACKAGE_IDENTIFIER))
                .withFailMessage(
                        "No declared type was imported from a '%s' package, so family A1 would have no"
                                + " subject. This context owns four entities transcribed from the"
                                + " reference record layouts, so an empty selection means the package"
                                + " was renamed out of the matched shape rather than that there was"
                                + " nothing to find.",
                        DOMAIN_PACKAGE_IDENTIFIER)
                .isNotEmpty();

        assertThat(ANALYSED_CLASSES.that(DEPEND_ON_A_DOMAIN_PACKAGE))
                .withFailMessage(
                        "No analysed class reaches a package with a '%s' segment, so family A2 would"
                                + " have no subject. A repository of this module names an entity of it"
                                + " in its own type signature, so an empty selection means the"
                                + " dependency graph resolved nothing rather than that the boundary is"
                                + " uncrossed.",
                        DOMAIN_PACKAGE_SEGMENT)
                .isNotEmpty();

        assertThat(declaredTypesIn(MODULE_ROOT_IDENTIFIER))
                .withFailMessage(
                        "No declared type was imported from '%s', so family A3 would have no member to"
                                + " inspect and would report green over an empty tree. Confirm this"
                                + " module's main classes were compiled before the gate ran.",
                        MODULE_ROOT_IDENTIFIER)
                .isNotEmpty();
    }

    /**
     * Asserts that the ownership contract family A2 resolves against is exactly the nine fixed roots,
     * and that a domain package is recognised by segment rather than by substring.
     *
     * <p>Refactoring Rationale: the nine roots and the segment matcher are the two inputs that decide
     * every A2 verdict, and neither is visible in a passing report. A tenth root added by an edit here
     * would silently widen what counts as "own root" and stop reporting a real crossing, and a matcher
     * quietly turned into a containment test would start reporting crossings against packages that
     * hold no domain model. Asserting both here means such an edit fails a named test instead of
     * changing the meaning of a green build.</p>
     *
     * <p>Assumptions: the shared kernel is deliberately not one of the service roots, and the
     * assertion states that separately. The list of nine is the union of the kernel and the eight
     * services, so a kernel that appeared in both would make every kernel-to-service dependency look
     * like a same-root dependency and disable the one direction of the prohibition that protects the
     * kernel from taking a dependency on data it does not own.</p>
     *
     * <p>Assumptions: the near-miss package names probed below are constructed rather than observed.
     * They are the two shapes a containment test would wrongly match -- a sibling package whose name
     * begins with the segment, and a nested package whose name ends with it -- and neither exists in
     * this reactor, which is exactly why the matcher has to be probed instead of inferred from a
     * passing build.</p>
     */
    @Test
    @DisplayName("guard: the ownership contract is exactly the nine fixed roots, matched by segment")
    void ownershipContractIsExactlyTheNineFixedRoots() {
        assertThat(OWNERSHIP_ROOTS)
                .withFailMessage(
                        "The A2 ownership contract is no longer the shared kernel plus the eight"
                                + " service roots of the migrated decomposition. A root added here"
                                + " widens what counts as a class's own root, so a real crossing stops"
                                + " being reported; a root removed here makes a whole context's"
                                + " packages unowned and therefore exempt.")
                .containsExactly(
                        SHARED_KERNEL_ROOT,
                        "com.carddemo.auth",
                        "com.carddemo.account",
                        "com.carddemo.card",
                        "com.carddemo.transaction",
                        "com.carddemo.reference",
                        "com.carddemo.batch",
                        "com.carddemo.authorization",
                        "com.carddemo.reporting");

        assertThat(SERVICE_ROOTS)
                .withFailMessage(
                        "The shared kernel '%s' appears among the service roots. It would then be"
                                + " treated as a context that owns data, and the direction of the"
                                + " prohibition that stops the kernel depending on a service's domain"
                                + " model would no longer be evaluated.",
                        SHARED_KERNEL_ROOT)
                .doesNotContain(SHARED_KERNEL_ROOT);

        assertThat(SERVICE_ROOTS)
                .withFailMessage(
                        "The root of this module, '%s', is absent from the service roots, so a class of"
                                + " this module would resolve to no owner and family A2 would exempt"
                                + " the module it exists to police.",
                        MODULE_ROOT)
                .contains(MODULE_ROOT);

        assertThat(ownershipRootOf(MODULE_ROOT + ".domain"))
                .withFailMessage(
                        "A package inside '%s' no longer resolves to it as its owning root. Ownership"
                                + " is resolved by exact match or by the root followed by a dot, and a"
                                + " subpackage that fails to resolve is exempted from A2 rather than"
                                + " checked by it.",
                        MODULE_ROOT)
                .contains(MODULE_ROOT);

        assertThat(ownershipRootOf("com.carddemo.transactional.domain"))
                .withFailMessage(
                        "A package that merely starts with the characters of '%s' resolved to it as an"
                                + " owning root. Ownership must be decided on a dot boundary, or a"
                                + " third-party package with a similar prefix would be drawn into the"
                                + " nine-root contract.",
                        MODULE_ROOT)
                .isEmpty();

        assertThat(containsDomainSegment(MODULE_ROOT + "." + DOMAIN_PACKAGE_SEGMENT))
                .withFailMessage(
                        "A package whose last segment is exactly '%s' is no longer recognised as a"
                                + " domain model, so family A2 would stop selecting the packages it"
                                + " exists to protect.",
                        DOMAIN_PACKAGE_SEGMENT)
                .isTrue();

        assertThat(containsDomainSegment(MODULE_ROOT + "." + DOMAIN_PACKAGE_SEGMENT + "helper"))
                .withFailMessage(
                        "A package whose segment merely begins with '%s' was recognised as a domain"
                                + " model. The matcher has become a containment test, so A2 would"
                                + " report a boundary crossing against a package that holds no domain"
                                + " model at all.",
                        DOMAIN_PACKAGE_SEGMENT)
                .isFalse();

        assertThat(containsDomainSegment(MODULE_ROOT + ".sub" + DOMAIN_PACKAGE_SEGMENT))
                .withFailMessage(
                        "A package whose segment merely ends with '%s' was recognised as a domain"
                                + " model, so the matcher is testing for a substring rather than for a"
                                + " dot-delimited segment.",
                        DOMAIN_PACKAGE_SEGMENT)
                .isFalse();
    }

    /**
     * Checks family A1, that no domain type in the analysed graph depends on infrastructure or
     * transport.
     *
     * <p>Assumptions: the check runs over the whole analysed graph rather than over this module alone,
     * so the shared kernel's transport adapters are present while it runs. They are not subjects,
     * because they reside outside every {@code ..domain..} package, and their presence is what makes
     * this a demonstration that the prohibition is scoped to the domain layer rather than an assertion
     * that it is.</p>
     */
    @Test
    @DisplayName("A1: a ..domain.. type depends on no AWS SDK, Spring Web or Jakarta Servlet type")
    void domainPackagesAreIsolatedFromFrameworkAndInfrastructure() {
        DOMAIN_IS_ISOLATED_FROM_FRAMEWORK_AND_INFRASTRUCTURE.check(ANALYSED_CLASSES);
    }

    /**
     * Checks family A2, that no CardDemo root reaches a domain model owned by a different root.
     *
     * <p>Assumptions: the rule is asserted rather than assumed to be prevented by the build. A
     * dependency on a sibling's domain type needs no new Maven resolution whenever two modules already
     * share a classpath -- which is exactly the situation this module is in with the shared kernel, and
     * would be in with any module a future POM edit put beside it -- so the boundary is checked in the
     * graph and not inferred from the dependency list.</p>
     */
    @Test
    @DisplayName("A2: no CardDemo root depends on a domain class owned by a different root")
    void noRootDependsOnAnotherRootsDomainModel() {
        NO_ROOT_DEPENDS_ON_ANOTHER_ROOTS_DOMAIN_MODEL.check(ANALYSED_CLASSES);
    }

    /**
     * Checks that this module reaches the account context without importing its domain model.
     *
     * <p>Assumptions: this is the crossing most likely to be attempted, because the two contexts
     * genuinely share data -- the card cross-reference keyed by card number and by account identifier.
     * Naming it in its own assertion means a breach is reported as the account boundary rather than as
     * one anonymous crossing among nine roots.</p>
     */
    @Test
    @DisplayName("A2: this module reaches the account context over HTTP, not by importing its domain")
    void noDependencyOnTheAccountContextDomainModel() {
        NO_DEPENDENCY_ON_THE_ACCOUNT_CONTEXT_DOMAIN_MODEL.check(ANALYSED_CLASSES);
    }

    /**
     * Checks family A3, that nothing this module compiles declares a binary floating-point member.
     *
     * <p>Assumptions: all three member positions are inspected because each alone loses exactness and
     * they fail differently. A field stores an amount inexactly. A parameter accepts an amount that was
     * already rounded, so the loss happened in the caller and the exact type never saw the original
     * value. A return type publishes an exact internal amount inexactly, which is the position a
     * reader is least likely to look for, because the arithmetic inside the method can be entirely
     * correct.</p>
     */
    @Test
    @DisplayName("A3: nothing this module compiles declares a float, double, Float or Double")
    void moneyPathDeclaresNoBinaryFloatingPoint() {
        MONEY_PATH_DECLARES_NO_BINARY_FLOATING_POINT.check(ANALYSED_CLASSES);
    }


    /**
     * Exposes the condition family A3 decides violations from, so that its proof evaluates this exact
     * condition rather than a copy.
     *
     * <p>Trade-offs: the exposure is a field and a method for one shared object, which is one member
     * more than the minimum. Accepted deliberately: the field is what pins a single instance, so the
     * enforcement rule above and the proof beside it evaluate the same object rather than two
     * conditions that merely agree today, while the method is the name {@link MoneyPathGateProofTest}
     * binds to in its own evaluation and in its Javadoc link. Collapsing the pair would rename a member
     * a sibling file reads, and that file is not this authoring's to edit.</p>
     *
     * <p>Assumptions: the proof pairs this condition with a selection naming its own package, because a
     * fixture cannot be placed in a package this family protects without putting the prohibited
     * construct into the production tree, at which point the enforcement above would fail by
     * construction. Only the condition is shared; the selection stays proof-local.</p>
     *
     * @return the condition family A3 applies, for evaluation against a deliberately violating subject
     */
    static ArchCondition<JavaClass> moneyPathCondition() {
        return MONEY_PATH_CONDITION;
    }

    /**
     * Builds the nine-root ownership contract from the shared kernel and the eight service roots.
     *
     * <p>Assumptions: the union is computed once from the two lists above rather than written out a
     * third time, so the kernel and the services are each named in exactly one place and the contract
     * cannot disagree with itself. The guard above asserts the resulting order and content, which is
     * what makes computing it here safe rather than merely shorter.</p>
     *
     * @return the shared kernel followed by the eight service roots, in declaration order and
     *     unmodifiable
     */
    private static List<String> concatenatedOwnershipRoots() {
        List<String> roots = new ArrayList<>();
        roots.add(SHARED_KERNEL_ROOT);
        roots.addAll(SERVICE_ROOTS);
        return List.copyOf(roots);
    }

    /**
     * Imports the CardDemo classes on this module's test classpath, excluding every test source.
     *
     * <p>Assumptions: two exclusions are needed and neither covers the other. ArchUnit's predefined
     * test exclusion names the compiler output directories of the two build tools, which removes this
     * module's own compiled tests; the shared kernel's architecture package arrives as JAR content
     * instead, so it is removed by package identifier. Together they keep this class, its two proof
     * siblings and the shared gate's own fixtures out of the analysed set.</p>
     *
     * <p>Assumptions: that combined exclusion is load-bearing twice over. It keeps the families focused
     * on production code, and it is what lets the two proof tests declare deliberately violating
     * subjects in the test tree at all -- the shared gate ships a fixture that declares binary floating
     * point on purpose, and family A3 would reject it, so an unfiltered graph would fail this build
     * over a class authored to be rejected.</p>
     *
     * <p>Alternatives Considered: excluding JAR content wholesale with the importer's archive option
     * rather than by package. Rejected because the shared kernel's production classes arrive in a JAR
     * too, and dropping them would take {@code com.carddemo.common.web} and
     * {@code com.carddemo.common.error} out of the graph -- the very adapters whose presence
     * demonstrates that family A1 is scoped to the domain layer -- and would leave the kernel-facing
     * direction of family A2 with nothing to evaluate.</p>
     *
     * @return the analysed production classes, described by the scope they were drawn from
     */
    private static JavaClasses importAnalysedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ANALYSED_ROOT)
                .that(not(resideInAPackage(ARCHITECTURE_PACKAGE_IDENTIFIER)))
                .as("production classes under " + ANALYSED_ROOT + " visible to " + MODULE_ROOT);
    }

    /**
     * Selects the declared types of a package identifier, discounting compiled package descriptors.
     *
     * @param packageIdentifier the ArchUnit package identifier to select from
     * @return the imported classes of that identifier that are not package descriptors, possibly empty
     */
    private static List<JavaClass> declaredTypesIn(String packageIdentifier) {
        return ANALYSED_CLASSES
                .that(resideInAPackage(packageIdentifier))
                .that(not(simpleName(PACKAGE_DESCRIPTOR_SIMPLE_NAME)))
                .stream()
                .toList();
    }

    /**
     * Lists the analysed classes whose simple name marks them as a test source.
     *
     * <p>Assumptions: the suffix test is applied to the simple name rather than to the location,
     * because the location-based exclusions are the very thing this list exists to verify and a check
     * expressed in the same terms as the mechanism it audits would pass whenever that mechanism
     * failed.</p>
     *
     * @return the fully qualified names of any surviving test sources, in stable order and empty on a
     *     correctly filtered graph
     */
    private static List<String> namesOfAnalysedClassesThatAreTestSources() {
        return ANALYSED_CLASSES.stream()
                .filter(candidate -> TEST_CLASS_NAME_SUFFIXES.stream()
                        .anyMatch(suffix -> candidate.getSimpleName().endsWith(suffix)))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    /**
     * Resolves which of the nine ownership roots owns a package, if any of them does.
     *
     * <p>Assumptions: a root owns a package when the name is the root exactly or the root followed by a
     * dot. The trailing dot is what makes the test a boundary test: a plain prefix test would let
     * {@code com.carddemo.transactional} resolve to {@code com.carddemo.transaction} and draw an
     * unrelated package into the ownership contract.</p>
     *
     * @param packageName the fully qualified package name to resolve
     * @return the owning root, or empty when the package lies outside the nine-root contract and is
     *     therefore third-party as far as family A2 is concerned
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
     * Reports whether a package name carries {@code domain} as a dot-delimited segment.
     *
     * <p>Assumptions: the name is split on the package separator and the segments are compared whole.
     * That is what distinguishes a domain model from a package that merely contains those characters,
     * such as a sibling {@code domainhelper} or a nested {@code subdomain}, both of which a containment
     * test would match and neither of which holds a domain model.</p>
     *
     * @param packageName the fully qualified package name to inspect
     * @return {@code true} when one whole segment of the name is the domain segment
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
     * Reports whether a raw type is one of the four forbidden binary floating-point types.
     *
     * @param rawType the resolved raw type of a field, parameter or return position
     * @return {@code true} when the type is forbidden on this module's classes
     */
    private static boolean isForbiddenBinaryFloatingPoint(JavaClass rawType) {
        return FORBIDDEN_BINARY_FLOATING_POINT_TYPES.contains(rawType.getName());
    }

    /**
     * Reports a dependency from a class under one ownership root onto a domain class owned by a
     * different root, in whichever direction it runs.
     *
     * <p>Assumptions: the whole prohibition lives in one condition rather than in one rule per pair of
     * roots, so the nine roots are declared once and the direction of the arrow is decided by comparing
     * the two resolved roots instead of by enumerating the pairs. Nine pairwise rules would express the
     * same invariant nine times, and the copy that drifts is the one nobody re-reads.</p>
     *
     * <p>Assumptions: a dependency onto the shared kernel is permitted whatever it points at, and a
     * dependency from the kernel onto a service's domain model is not. That asymmetry is the arrow the
     * decomposition draws: the kernel is the Java analogue of one shared copybook include path, so
     * every service may compile against it, while the kernel owning no schema means it has no business
     * knowing the in-memory shape of anyone else's.</p>
     */
    private static final class ForeignRootDomainDependencyCondition extends ArchCondition<JavaClass> {

        /**
         * Describes the condition in the terms ArchUnit prints beside a violation.
         */
        private ForeignRootDomainDependencyCondition() {
            super("depend on a " + DOMAIN_PACKAGE_SEGMENT
                    + " class owned by a different CardDemo ownership root");
        }

        /**
         * Adds one event for each distinct foreign domain class the candidate depends on.
         *
         * <p>Assumptions: violations are collapsed to one per target class. A single foreign type
         * reached through a field, a constructor parameter and a return type is one boundary crossing
         * and is reported once, because three events for one crossing would read as three problems to
         * repair. Family A3 deliberately does the opposite and reports each member position
         * separately, because there the position is the defect rather than the target.</p>
         *
         * <p>Assumptions: a candidate outside the nine roots is skipped rather than reported. Such a
         * class is third-party as far as this contract goes, and this gate has no standing to constrain
         * how a library depends on anything.</p>
         *
         * @param sourceClass the analysed class whose outgoing dependencies are inspected
         * @param events the collector this condition adds one event per distinct crossing to
         */
        @Override
        public void check(JavaClass sourceClass, ConditionEvents events) {
            Optional<String> sourceRoot = ownershipRootOf(sourceClass.getPackageName());
            if (sourceRoot.isEmpty()) {
                return;
            }
            Set<String> alreadyReported = new LinkedHashSet<>();
            for (Dependency dependency : sourceClass.getDirectDependenciesFromSelf()) {
                JavaClass targetClass = dependency.getTargetClass();
                if (!containsDomainSegment(targetClass.getPackageName())) {
                    continue;
                }
                Optional<String> targetRoot = ownershipRootOf(targetClass.getPackageName());
                if (targetRoot.isEmpty() || targetRoot.get().equals(sourceRoot.get())
                        || targetRoot.get().equals(SHARED_KERNEL_ROOT)) {
                    continue;
                }
                if (!alreadyReported.add(targetClass.getName())) {
                    continue;
                }
                events.add(SimpleConditionEvent.violated(
                        sourceClass,
                        "A2: " + sourceClass.getFullName() + " under root " + sourceRoot.get()
                                + " depends on " + DOMAIN_PACKAGE_SEGMENT + " class "
                                + targetClass.getFullName() + " owned by root " + targetRoot.get()));
            }
        }
    }

    /**
     * Reports every field, callable parameter and return type declared with a binary floating-point
     * type.
     *
     * <p>Alternatives Considered: expressing this family with the fluent predicate that matches a
     * callable's raw parameter types. Rejected on a verified property of the ArchUnit 1.4.2 API rather
     * than on style: that predicate matches an ENTIRE parameter list, so used as a contains-any test it
     * accepts a callable whose second parameter of three is a {@code double} and the family reports
     * green over exactly the declaration it exists to reject. This condition therefore walks the
     * parameter list by index, which also lets the diagnostic name the position that has to change.</p>
     *
     * <p>Assumptions: raw types are compared by name rather than by class literal, so a primitive and
     * its wrapper are both decided by one lookup and neither needs a class the importer has already
     * resolved to be loaded again.</p>
     */
    private static final class BinaryFloatingPointMemberCondition extends ArchCondition<JavaClass> {

        /**
         * Describes the condition in the terms ArchUnit prints beside a violation.
         */
        private BinaryFloatingPointMemberCondition() {
            super("declare a float, double, Float or Double field, constructor or method parameter,"
                    + " or return type");
        }

        /**
         * Adds one event per offending member position on the candidate.
         *
         * <p>Assumptions: each position is reported separately, so a class that declares an inexact
         * field and an inexact return type produces two events. The position is what has to change, and
         * one collapsed event would name a class while leaving a reader to find which of its members
         * was meant. That is the opposite of the collapsing family A2 performs, where a single foreign
         * type reached three ways is still one boundary crossing.</p>
         *
         * <p>Assumptions: constructors are inspected on the same terms as methods, because the
         * candidate set includes records and entities whose only way in is a constructor, so a
         * parameter-only rule over methods would leave the entry point of an immutable type ungated.
         * The code units ArchUnit reports cover both, together with static initialisers.</p>
         *
         * <p>Assumptions: an offending position is reported as SATISFIED rather than as violated,
         * because the rule that owns this condition is phrased with {@code noClasses} and ArchUnit
         * inverts every event such a rule's condition produces. Reporting a match as a violation here
         * would leave the family finding the declaration and reporting nothing, and the report of that
         * broken gate is a clean pass.</p>
         *
         * @param moneyClass the analysed class whose declared members are inspected
         * @param events the collector this condition adds one event per offending position to
         */
        @Override
        public void check(JavaClass moneyClass, ConditionEvents events) {
            for (JavaField field : moneyClass.getFields()) {
                if (isForbiddenBinaryFloatingPoint(field.getRawType())) {
                    events.add(SimpleConditionEvent.satisfied(
                            field,
                            "A3: field " + field.getFullName() + " is declared "
                                    + field.getRawType().getName()));
                }
            }
            for (JavaCodeUnit codeUnit : moneyClass.getCodeUnits()) {
                if (isForbiddenBinaryFloatingPoint(codeUnit.getRawReturnType())) {
                    events.add(SimpleConditionEvent.satisfied(
                            codeUnit,
                            "A3: " + codeUnit.getFullName() + " returns "
                                    + codeUnit.getRawReturnType().getName()));
                }
                List<JavaClass> parameterTypes = codeUnit.getRawParameterTypes();
                for (int index = 0; index < parameterTypes.size(); index++) {
                    JavaClass parameterType = parameterTypes.get(index);
                    if (isForbiddenBinaryFloatingPoint(parameterType)) {
                        events.add(SimpleConditionEvent.satisfied(
                                codeUnit,
                                "A3: " + codeUnit.getFullName() + " declares parameter " + index
                                        + " as " + parameterType.getName()));
                    }
                }
            }
        }
    }
}

