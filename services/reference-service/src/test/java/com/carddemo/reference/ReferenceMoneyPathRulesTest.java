// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/ReferenceMoneyPathRulesTest.java
// -----------------------------------------------------------------------------
// The module-scoped money-path gate for the reference-data bounded context. It
// makes three assertions about this module that the shared architecture gate in
// the shared kernel is structurally unable to make: that this module's OWN
// compiled classes were the ones examined, that no member position under
// com.carddemo.reference declares a binary floating-point type, and that the
// disclosure interest rate leaves this service as a JSON string rather than as
// a JSON number.
//
// WHY (non-obvious design decisions):
//   (1) The shared gate reaches this module over a four-link cross-module
//       delivery chain, and that chain has already come apart here once. This
//       module's own pom.xml records the break in its own words: nothing
//       analysed com.carddemo.reference at all, and the rate-path prohibition
//       was unenforced here while the reactor stayed green. A test class in this
//       module's own src/test/java is collected by the standard lifecycle with
//       no declaration anywhere, so its reach is one link rather than four.
//   (2) The shared gate's non-vacuity guard anchors its "did the import find
//       anything" assertion on a shared-kernel package that arrives as ordinary
//       jar content on every module's test classpath. It therefore cannot tell
//       "this module was examined" from "something was examined". The guard
//       below anchors on com.carddemo.reference, which is the only anchor that
//       separates those two outcomes for this module.
//   (3) The shared gate reads DECLARED MEMBER TYPES and cannot read
//       serialisation form. A rate re-typed from the shared money type to
//       BigDecimal passes it untouched, because BigDecimal is not forbidden,
//       while emitting a JSON number that destroys exactness at the boundary.
// =============================================================================
package com.carddemo.reference;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.MoneyModule;
import com.carddemo.reference.domain.DisclosureGroup;
import com.carddemo.reference.dto.DisclosureGroupRateResponse;
import com.carddemo.reference.mapper.DisclosureGroupMapper;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the exactness of this module's rate path, in the two respects that the shared architecture
 * gate cannot reach and with the proof that this module's own classes were the ones inspected.
 *
 * <p>The value being guarded is the disclosure interest rate. It is one of exactly two operands in
 * the reference baseline's monthly interest calculation, which {@code app/cbl/CBACT04C.cbl} performs
 * at line 464 to line 465 inside the {@code 1300-COMPUTE-INTEREST} paragraph at line 462, computing
 * {@code WS-MONTHLY-INT} as {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Its stored picture is
 * {@code DIS-INT-RATE PIC S9(04)V99} in {@code app/cpy/CVTRA02Y.cpy}, so it is exact to two decimal
 * places at rest. This service owns that value and publishes it; the multiplication and the division
 * are performed in the batch context. An inexact rate leaving here is therefore not a local defect:
 * it becomes a wrong cent on every interest transaction the batch context generates, in a service
 * with nothing wrong with it. That asymmetry is the reason the assertion belongs at the owner of the
 * rate rather than at the consumer of it.</p>
 *
 * <h2>Why this gate exists beside the shared one</h2>
 *
 * <p>Refactoring Rationale: the shared gate's floating-point rule DOES cover this module today, and
 * this class is not written on the contrary assumption. Its rule is scoped to the whole analysed
 * namespace, it inspects fields, every parameter and return types, and it recurses into generic type
 * arguments; the main tree's domain charter states plainly that the prohibition here is enforced by
 * the build rather than only by review. What this class adds is not a missing prohibition but a
 * missing PROOF, and the distinction is the whole justification. The shared rule reaches this module
 * only by being delivered into it: the shared kernel must bind a test-jar goal, this module must
 * declare that test-jar at test scope, the aggregator's Surefire execution must select the rule class
 * by a literal file-name include out of a scanned dependency, and a profile must arm that execution's
 * no-tests guard. That is four links maintained in two other files, and it has already failed for
 * this module once. This module's own build descriptor records the failure in its own words: the
 * assertion engine had been declared while the rule class was assumed to arrive by inheritance,
 * Maven passes a module's main classes to its consumers and never its test classes, and so nothing
 * analysed {@code com.carddemo.reference} at all. The rate-path prohibition read as enforced and was
 * not. A class in this module's own test source tree is collected because of where it sits and what
 * it is named, which is one link and not four.</p>
 *
 * <p>Refactoring Rationale: the second and larger gap is that the shared gate cannot detect its own
 * absence FROM THIS MODULE. Its non-vacuity guard asserts that the import found classes at the shared
 * namespace root and that the shared money package contributed a declared type. Both of those are
 * satisfied by the shared kernel alone, because ArchUnit's test-exclusion option filters build output
 * directories and not jar content, so the kernel's compiled classes are in the analysed set of every
 * module that depends on it. If this module's own classes were ever missing from that set, every
 * assertion in that guard would still pass and every one of its rules would still report success,
 * having examined the kernel and nothing else. The guard below asserts instead that named classes of
 * THIS module's rate path are present in the analysed set, which is the only assertion that
 * distinguishes "this module was examined" from "something was examined".</p>
 *
 * <p>Assumptions: this class COMPLEMENTS the shared gate and is careful not to overlap it anywhere
 * else. It does not re-assert that a domain type stays clear of AWS SDK, Spring Web or Jakarta
 * Servlet types, and it does not re-assert that no bounded context reaches into another's domain
 * model. Both of those are owned in the shared kernel, and a second owner would drift from the first
 * without either copy failing. Nor does it restate this module's other contracts: the delete that is
 * refused while a category still references a type, and the substitution of the {@code 'DEFAULT'}
 * disclosure group that the baseline performs at {@code app/cbl/CBACT04C.cbl} line 437, are asserted
 * by the api, repository and service test packages that own them.</p>
 *
 * <p>Trade-offs: the two rules below take every type under this module as their subject rather than
 * naming the handful of classes on the rate path. Naming them would report a smaller and more
 * obviously relevant failure surface, and it was rejected because the rate path is not a closed set:
 * a transfer object, a mapper or a service introduced by a subsequent change would carry an amount
 * and would sit outside a list nobody remembered to extend, which is the failure mode a gate is
 * supposed to remove rather than reproduce. The cost accepted is that a legitimate binary floating-point quantity anywhere in
 * this module -- a statistical measure, say -- would now fail here and would have to be admitted
 * deliberately at the declaration below rather than introduced quietly in a service. No such
 * quantity exists in this module as specified.</p>
 *
 * <p>Assumptions: this gate constrains the SHAPE and the WIRE FORM of the rate and asserts nothing
 * about arithmetic. It cannot see whether a product was formed before a quotient, which is the
 * property the baseline calculation depends on and which belongs to the batch context that performs
 * it. Functional parity with the baseline is owned by the COBOL suite at the repository root, which
 * this class reads as evidence and never modifies. A rate can satisfy every assertion here and still
 * be the wrong rate.</p>
 *
 * <p>Assumptions: the outcome of every assertion in this class is binary. A violation fails the Maven
 * build outright, and no warning tier is tolerated at any of them. The graded condition-code rubric
 * that the reference COBOL suite aggregates its own layers with, in which a warning-level aggregate
 * is that suite's documented green state, exists there for a documented reason that has no analogue
 * here and is deliberately not imitated.</p>
 */
class ReferenceMoneyPathRulesTest {

    /**
     * The package root of this module, and the one scope every rule in this class is evaluated over.
     *
     * <p>Assumptions: this names THIS module rather than the shared namespace the kernel's gate
     * analyses. Naming the shared namespace would pull the kernel's own types into the subject set,
     * which would make a failure here ambiguous about which module owned it and would duplicate a
     * rule the kernel already applies to itself.</p>
     */
    private static final String MODULE_ROOT = "com.carddemo.reference";

    /**
     * {@link #MODULE_ROOT} with the recursive suffix an ArchUnit package identifier requires.
     *
     * <p>Assumptions: it is derived from the root above rather than written out a second time, so the
     * scope the classes are imported at and the scope the rules are evaluated at cannot drift apart.
     * A rule scoped to a namespace the importer never read would pass by examining nothing.</p>
     */
    private static final String MODULE_ROOT_IDENTIFIER = MODULE_ROOT + "..";

    /**
     * The four binary floating-point type names forbidden at every member position in this module.
     *
     * <p>Assumptions: the names are the ones ArchUnit reports for a raw type, so a primitive appears
     * as its keyword and a wrapper as its fully qualified name. All four are listed because boxing
     * loses exactness exactly as the primitive does while reading as a different type in source, and
     * a list naming only the primitives would leave the wrapper as an unguarded route.</p>
     *
     * <p>Assumptions: this class is the one file in this module permitted to contain these tokens.
     * Naming them in a production type in order to make a rule reachable would put the prohibited
     * construct into the very code the rule protects.</p>
     */
    private static final Set<String> FORBIDDEN_BINARY_FLOATING_POINT_TYPES =
            Set.of("float", "double", "java.lang.Float", "java.lang.Double");

    /**
     * The serialised property name the published rate is carried under.
     *
     * <p>Assumptions: it is held as a constant because the serialisation assertion reads the property
     * out of a parsed document by name, so a rename on the transfer object would otherwise turn a
     * real contract break into a missing-property failure whose message named the wrong cause.</p>
     */
    private static final String RATE_PROPERTY = "interestRate";

    /**
     * A rate drawn from the seed data, in the plain decimal text the wire form carries.
     *
     * <p>Assumptions: this is a real value rather than an invented one. The disclosure seed at
     * {@code app/data/ASCII/discgrp.txt} holds fifty-one rows of exactly fifty bytes, and fifteen of
     * them carry the rate token whose six zoned-decimal digits are 001500 under a positive-zero sign
     * overpunch, which decodes at the declared scale of two to this amount. Asserting against a value
     * the seed actually contains is what keeps this fixture answerable to the baseline rather than
     * only to itself.</p>
     */
    private static final String SEEDED_RATE_TEXT = "15.00";

    /**
     * The blank-padded account group used to build the representative response.
     *
     * <p>Assumptions: the composite key component is exactly as wide as its column declares, because
     * the identity type refuses any other width, and the padding is part of the stored key rather
     * than incidental whitespace.</p>
     */
    private static final String SAMPLE_ACCT_GROUP_ID = "ZEROPCT   ";

    /** The two-character transaction type used to build the representative response. */
    private static final String SAMPLE_TRAN_TYPE_CD = "01";

    /**
     * The four-digit transaction category used to build the representative response.
     *
     * <p>Assumptions: the leading zeros are retained because the source picture is numeric and
     * zero-filled, and the identity type refuses a value that has lost them.</p>
     */
    private static final String SAMPLE_TRAN_CAT_CD = "0001";

    /**
     * The mapper configured exactly as the serialising boundary configures it.
     *
     * <p>Assumptions: the shared money module is registered and nothing else is, because the module is
     * what binds the string form of an amount. Building a bare mapper here would assert against a
     * configuration the application does not run, and building one with extra modules would let an
     * unrelated setting mask the contract under test.</p>
     */
    private static final JsonMapper JSON = JsonMapper.builder().addModule(new MoneyModule()).build();

    /**
     * Every production class of THIS module that the executing test classpath can see.
     *
     * <p>Assumptions: the import happens once for the class rather than once per test, because
     * resolving the byte code of the namespace is the expensive part and both rules need the same
     * graph. A failure here surfaces as an initialisation error on every test in the class, which is
     * louder than a quietly reduced subject set.</p>
     */
    private static final JavaClasses MODULE_PRODUCTION_CLASSES = importModuleProductionClasses();

    /**
     * Rule R1: no type in this module declares a field of a binary floating-point type.
     *
     * <p>Assumptions: the subject is fields rather than classes, so ArchUnit reports the offending
     * FIELD as the violated element and its message names the declaration directly. A class-scoped
     * rule would report the class and leave the reader to find which of its members offended.</p>
     *
     * <p>Assumptions: ArchUnit's default strict behaviour on an empty subject set is kept, and
     * nothing here relaxes it. This module declares entities, transfer objects, mappers and services
     * that all carry fields, so an empty subject set cannot mean there was nothing to find; it can
     * only mean the import resolved nothing, and failing is the correct answer to that.</p>
     */
    private static final ArchRule MODULE_DECLARES_NO_BINARY_FLOATING_POINT_FIELD = fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(MODULE_ROOT_IDENTIFIER)
            .should(new NoBinaryFloatingPointFieldType())
            .as("R1: no field declared under " + MODULE_ROOT_IDENTIFIER
                    + " should have a binary floating-point type")
            .because("the disclosure rate this module owns is an operand of the baseline interest"
                    + " calculation, so an inexact field here becomes a wrong cent on every interest"
                    + " transaction the batch context generates rather than a fault in this service");

    /**
     * Rule R2: no method or constructor in this module admits or returns a binary floating-point type.
     *
     * <p>Assumptions: code units are taken as the subject rather than methods and constructors
     * separately, because that one set covers both. A constructor accepting an inexact rate is caught
     * on the same terms as a method accepting one, and a constructor's absent return type is a
     * {@code void} that no prohibition matches, so it needs no special case.</p>
     *
     * <p>Assumptions: strict empty-subject behaviour is kept here for the same reason as R1. Every
     * class in this module declares at least a constructor, so an empty subject set here reports a
     * failed import rather than an absent layer.</p>
     */
    private static final ArchRule MODULE_SIGNATURES_DECLARE_NO_BINARY_FLOATING_POINT = codeUnits()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(MODULE_ROOT_IDENTIFIER)
            .should(new NoBinaryFloatingPointInSignature())
            .as("R2: no method or constructor declared under " + MODULE_ROOT_IDENTIFIER
                    + " should declare a parameter or a return type of a binary floating-point type")
            .because("an amount converted at a signature boundary is converted silently, and the"
                    + " resulting value is plausible and slightly wrong rather than rejected");

    /**
     * Asserts that this gate has this module's own code to gate, so no rule below can report success
     * by having examined only the shared kernel or nothing at all.
     *
     * <p>This method returns no value; it either completes or fails the build.</p>
     *
     * <p>Refactoring Rationale: a rule evaluated over an empty or wrong graph passes every assertion
     * it makes, and the build log of such a run cannot be told apart from the log of a run that
     * verified everything. The shared gate carries a guard of the same kind, and this one is not a
     * copy of it: that guard anchors on the shared namespace root and on the shared money package,
     * both of which the shared kernel satisfies on its own as jar content on this module's classpath.
     * It is therefore silent about the one thing that matters here, which is whether the classes of
     * THIS module reached the analysed set.</p>
     *
     * <p>Assumptions: the rate path is asserted by naming its three classes rather than by counting
     * the imported set. A count would have to be revised every time a class was added, and a count
     * that had gone stale would fail a healthy build; the three named classes are the entity that
     * stores the rate, the mapper that is the single place it becomes a money value, and the transfer
     * object that publishes it, so their joint presence is what makes the two rules meaningful.</p>
     *
     * <p>Trade-offs: the failure text names package identifiers and class names and nothing else. It
     * deliberately prints no imported class list, no resolved filesystem location and no value read at
     * run time, because a gate's diagnostic is read from build logs that are retained and shared more
     * widely than the build that produced them.</p>
     */
    @Test
    @DisplayName("guard: this module's own rate-path classes are in the analysed set")
    void moduleProductionGraphIsNotVacuous() {
        assertThat(MODULE_PRODUCTION_CLASSES)
                .withFailMessage(
                        "No production class was imported from root '%s'. Both rules in this class"
                                + " would then pass without evaluating anything. Confirm that this"
                                + " module compiled its main classes before its tests ran.",
                        MODULE_ROOT)
                .isNotEmpty();

        // WHY : Assumptions: the shared kernel is on this module's test classpath as jar content, and
        //       ArchUnit's test-exclusion option filters build OUTPUT DIRECTORIES rather than jar
        //       entries. So an assertion that merely found classes under a shared root would be
        //       satisfied by the kernel even in a module that compiled nothing of its own. Naming
        //       classes this module declares is what closes that hole, and it is the single assertion
        //       in this file that the shared gate cannot make on this module's behalf.
        assertThat(MODULE_PRODUCTION_CLASSES.contain(DisclosureGroup.class))
                .withFailMessage(
                        "The entity that stores the disclosure rate did not reach the analysed set, so"
                                + " rules R1 and R2 never inspected the type that owns the rate"
                                + " column. Confirm that root '%s' resolved to this module's own"
                                + " compiled classes and not only to the shared kernel.",
                        MODULE_ROOT)
                .isTrue();
        assertThat(MODULE_PRODUCTION_CLASSES.contain(DisclosureGroupMapper.class))
                .withFailMessage(
                        "The mapper that is the single place the stored rate becomes a money value did"
                                + " not reach the analysed set, so the one conversion on the rate path"
                                + " was not inspected by rules R1 and R2.")
                .isTrue();
        assertThat(MODULE_PRODUCTION_CLASSES.contain(DisclosureGroupRateResponse.class))
                .withFailMessage(
                        "The transfer object that publishes the rate did not reach the analysed set, so"
                                + " the member positions on the published contract were not inspected"
                                + " by rules R1 and R2.")
                .isTrue();

        // WHY : Assumptions: this class sits in the same package it analyses, so the exclusion of test
        //       output is what stops the gate becoming its own subject. Were it ever to reach the
        //       analysed set, the forbidden type names held above as data would be read as
        //       declarations and the rules would report on the gate instead of on the code.
        assertThat(MODULE_PRODUCTION_CLASSES.contain(ReferenceMoneyPathRulesTest.class))
                .withFailMessage(
                        "The class declaring these rules reached the analysed set, so both rules would"
                                + " be evaluated against this gate instead of against production"
                                + " code.")
                .isFalse();
    }

    /**
     * Applies rule R1 to this module's imported classes.
     *
     * <p>This method returns no value; it either completes or fails the build.</p>
     *
     * <p>Assumptions: the rule is applied to the graph imported once for this class rather than to a
     * graph imported here, so that the guard above and this assertion are demonstrably speaking about
     * the same set of classes.</p>
     */
    @Test
    @DisplayName("R1: no field in this module has a binary floating-point type")
    void moduleDeclaresNoBinaryFloatingPointField() {
        MODULE_DECLARES_NO_BINARY_FLOATING_POINT_FIELD.check(MODULE_PRODUCTION_CLASSES);
    }

    /**
     * Applies rule R2 to this module's imported classes.
     *
     * <p>This method returns no value; it either completes or fails the build.</p>
     *
     * <p>Assumptions: as with R1, the shared graph is used so that the non-vacuity guard covers this
     * assertion as well as the other.</p>
     */
    @Test
    @DisplayName("R2: no signature in this module admits or returns a binary floating-point type")
    void moduleSignaturesDeclareNoBinaryFloatingPoint() {
        MODULE_SIGNATURES_DECLARE_NO_BINARY_FLOATING_POINT.check(MODULE_PRODUCTION_CLASSES);
    }

    /**
     * Asserts that the published disclosure rate is carried as a JSON string and never as a JSON
     * number.
     *
     * <p>This method returns no value; it either completes or fails the build.</p>
     *
     * <p>Assumptions: the contract holds because of WHERE the shared money module binds its
     * serialiser. That module registers the serialiser against the money TYPE, not against
     * {@code BigDecimal}, and the serialiser writes the amount with a string-emitting call. So the
     * string form is a property of the transfer object's declared type rather than of the mapper that
     * populated it. Re-typing this property to {@code BigDecimal} or to any other numeric type would
     * leave the serialiser unbound, emit a JSON number, and break the contract with no compile error
     * and no failure from the shared architecture gate, which reads declared types and cannot read
     * wire form. That silent path is the whole reason this assertion is written at the wire.</p>
     *
     * <p>Assumptions: a JSON number is the specific harm rather than an untidy one. Most clients parse
     * a JSON number into an IEEE-754 double, which cannot represent most exact cent values, so the
     * rate would arrive plausible and slightly wrong at the boundary the caller actually reads. That
     * value is then multiplied before being divided in the baseline calculation cited on this class,
     * so the error is scaled by a balance rather than staying at the size it entered as.</p>
     *
     * <p>Assumptions: the rate is compared by VALUE and its scale is asserted separately, because
     * neither assertion implies the other. Numeric comparison is deliberately scale-insensitive, so a
     * rate that arrived at scale 0 or 4 would satisfy it, while
     * {@code new BigDecimal("15").equals(new BigDecimal("15.00"))} is false, so equality would reject
     * a value that is numerically correct. The pair is what makes this equivalent to the column
     * contract instead of to half of it, and it is why equality is not used on either line.</p>
     *
     * <p>Assumptions: the fixture is built through the mapper rather than by calling the transfer
     * object's constructor directly, because the mapper is the single place the stored rate becomes a
     * money value. Constructing the response by hand would assert the serialiser against a value this
     * service never produces that way.</p>
     */
    @Test
    @DisplayName("the published disclosure rate is a JSON string and never a JSON number")
    void publishedRateIsCarriedAsAJsonStringAndNeverAsAJsonNumber() {
        DisclosureGroupRateResponse published = representativeRateResponse();

        assertThat(published.interestRate().amount())
                .as("the published rate must equal the seeded rate by value, at whatever scale each"
                        + " side is spelled")
                .isEqualByComparingTo(SEEDED_RATE_TEXT);
        assertThat(published.interestRate().amount().scale())
                .as("the published rate must be carried at the scale its owning column declares")
                .isEqualTo(DisclosureGroup.INTEREST_RATE_SCALE);

        JsonNode rate = JSON.readTree(JSON.writeValueAsString(published)).get(RATE_PROPERTY);

        assertThat(rate)
                .withFailMessage(
                        "Property '%s' is absent from the serialised rate response, so the wire form"
                                + " of the rate could not be inspected. Confirm the property name on"
                                + " the published transfer object.",
                        RATE_PROPERTY)
                .isNotNull();
        assertThat(rate.isString())
                .withFailMessage(
                        "Property '%s' was not serialised as a JSON string. An exact rate carried as a"
                                + " JSON number is parsed into a binary floating-point double by most"
                                + " clients, which loses exactness at the boundary the caller reads."
                                + " Confirm the property is declared as the shared money type, which"
                                + " is the type the shared money module binds its serialiser to.",
                        RATE_PROPERTY)
                .isTrue();
        assertThat(rate.isNumber())
                .withFailMessage(
                        "Property '%s' was serialised as a JSON number. The published rate must be a"
                                + " JSON string of plain decimal text.",
                        RATE_PROPERTY)
                .isFalse();
        assertThat(rate.asString())
                .as("the wire form must carry the rate as plain decimal text at its declared scale")
                .isEqualTo(SEEDED_RATE_TEXT);
    }

    /**
     * Imports this module's production classes, with test output excluded.
     *
     * <p>Assumptions: test output is excluded so that this gate cannot be satisfied by its own test
     * class. That matters more here than it would elsewhere, because this class sits in the very
     * package it analyses and holds the forbidden type names as data; without the exclusion the rules
     * would have the gate itself among their subjects.</p>
     *
     * <p>Assumptions: the imported set is described, so a rule failure names the scope it was
     * evaluated over rather than leaving a reader to infer it from the rule text.</p>
     *
     * @return this module's production classes, described by the scope they were resolved from
     */
    private static JavaClasses importModuleProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(MODULE_ROOT)
                .as("production classes under " + MODULE_ROOT + " visible to this module's test run");
    }

    /**
     * Builds a rate response through the one mapper that converts a stored rate into a money value.
     *
     * <p>Assumptions: the identity components are supplied at exactly their declared widths, with the
     * account group blank-padded to ten characters and the category zero-filled to four digits,
     * because the identity type refuses any other shape. Those widths are part of the stored composite
     * key rather than presentation, so a fixture that trimmed them would not be a row this service
     * could ever have read.</p>
     *
     * <p>Assumptions: the response is reported as not having come from the substituted group, because
     * this fixture stands for an exact hit on the group that was asked for. The substitution path has
     * its own assertions in the packages that own it and is not re-asserted here.</p>
     *
     * @return a populated rate response carrying the seeded rate at its declared scale
     */
    private static DisclosureGroupRateResponse representativeRateResponse() {
        DisclosureGroup stored = new DisclosureGroup(
                new DisclosureGroup.DisclosureGroupId(
                        SAMPLE_ACCT_GROUP_ID, SAMPLE_TRAN_TYPE_CD, SAMPLE_TRAN_CAT_CD),
                new BigDecimal(SEEDED_RATE_TEXT));
        return DisclosureGroupMapper.toResponse(SAMPLE_ACCT_GROUP_ID, stored, false);
    }

    /**
     * Reports a member position whose declared type is a forbidden binary floating-point type.
     *
     * <p>Assumptions: the declared type is reduced to its base component type before being matched, so
     * an array of any depth and a varargs declaration are both reported without a second code path.
     * A non-array type is left unchanged by that reduction, which is why one call covers every
     * shape.</p>
     *
     * <p>Trade-offs: the reduction reads the RAW type, so a forbidden type appearing only as a generic
     * type argument -- a list of doubles, say -- is not reported here. That depth is deliberately not
     * reimplemented: the shared architecture gate already walks type arguments, wildcard bounds and
     * type-variable bounds across this module among others, and a second, shallower copy of that walk
     * would be the kind of duplicate that can be weakened in one place while the other still reads as
     * enforcing it. What this file adds over that gate is the module-anchored proof above and the wire
     * form below, not a second traversal.</p>
     *
     * <p>Trade-offs: the message names the declaring class, the member signature and the offending
     * type, and nothing else. It prints no field value and no identifier read at run time, because a
     * gate's diagnostic is read from build logs retained more widely than the build itself, and a
     * signature is already enough to find the declaration.</p>
     *
     * @param declaredType the raw type as declared at the member position, possibly an array type
     * @param memberDescription the member position being inspected, named by its signature and, for a
     *     parameter, by its index within that signature
     * @param owner the member that declares the position, used as the violation's corresponding object
     *     so ArchUnit reports the declaration rather than its class
     * @param events the collector the violation is added to
     */
    private static void reportIfBinaryFloatingPoint(
            JavaClass declaredType,
            String memberDescription,
            JavaMember owner,
            ConditionEvents events) {

        String elementName = declaredType.getBaseComponentType().getName();
        if (!FORBIDDEN_BINARY_FLOATING_POINT_TYPES.contains(elementName)) {
            return;
        }
        events.add(SimpleConditionEvent.violated(
                owner,
                "class " + owner.getOwner().getName() + " declares " + memberDescription
                        + " with type " + declaredType.getName()
                        + ", which resolves to forbidden binary floating-point type " + elementName));
    }

    /**
     * Reports any field of this module declared with a binary floating-point type.
     *
     * <p>Assumptions: only the field's own declared type is read. An inherited field belongs to the
     * type that declares it, and that type is either in this module, where this condition already
     * reaches it, or outside it, where reporting it here would name a class this module does not
     * own.</p>
     */
    private static final class NoBinaryFloatingPointFieldType extends ArchCondition<JavaField> {

        /** Describes the condition in the terms ArchUnit prints alongside a violation. */
        private NoBinaryFloatingPointFieldType() {
            super("not be declared with a binary floating-point type");
        }

        /**
         * Inspects one field and reports it when its declared type is forbidden.
         *
         * <p>Assumptions: a field contributes exactly one position to inspect, so unlike a signature
         * it needs no iteration and cannot hide a forbidden type behind a permitted one.</p>
         *
         * @param field the field whose declared type is inspected
         * @param events the collector the violation is reported to when the type is forbidden
         */
        @Override
        public void check(JavaField field, ConditionEvents events) {
            reportIfBinaryFloatingPoint(
                    field.getRawType(), "field " + field.getFullName(), field, events);
        }
    }

    /**
     * Reports any method or constructor of this module whose signature admits or returns a binary
     * floating-point type.
     *
     * <p>Alternatives Considered: expressing this with ArchUnit's {@code haveRawParameterTypes}
     * predicate, which is the obvious way to phrase it and is WRONG for this purpose. That predicate
     * matches an ENTIRE parameter list against the types supplied to it rather than asking whether any
     * one parameter is of a given type. A rule built on it can only be satisfied by a signature whose
     * whole list matches, so a method declaring a permitted type alongside a forbidden one -- the
     * ordinary shape of a mapper or a service method, which takes an identifier and an amount --
     * passes undetected. A condition that walks the list position by position, as the one below does,
     * is what makes a mixed parameter list reportable, and the proof that it is reportable was taken
     * by introducing exactly such a signature and observing the failure.</p>
     */
    private static final class NoBinaryFloatingPointInSignature extends ArchCondition<JavaCodeUnit> {

        /** Describes the condition in the terms ArchUnit prints alongside a violation. */
        private NoBinaryFloatingPointInSignature() {
            super("declare no parameter and no return type of a binary floating-point type");
        }

        /**
         * Inspects one method or constructor and reports every offending position in its signature.
         *
         * <p>Assumptions: each parameter is inspected on its own and reported with its index, so a
         * signature carrying more than one forbidden parameter yields one violation per position
         * instead of one for the signature. A reader fixing the declaration then sees every position
         * that has to change rather than rediscovering the next one on the following build.</p>
         *
         * <p>Assumptions: the return type is read from the code unit rather than from methods alone.
         * A constructor's return type is {@code void}, which no prohibition matches, so reading it
         * unconditionally is safe and removes the need to distinguish the two kinds here.</p>
         *
         * @param codeUnit the method or constructor whose signature is inspected
         * @param events the collector each offending position is reported to as its own violation
         */
        @Override
        public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
            List<JavaClass> parameterTypes = codeUnit.getRawParameterTypes();
            for (int index = 0; index < parameterTypes.size(); index++) {
                reportIfBinaryFloatingPoint(
                        parameterTypes.get(index),
                        "the parameter at index " + index + " of " + codeUnit.getFullName(),
                        codeUnit,
                        events);
            }

            reportIfBinaryFloatingPoint(
                    codeUnit.getRawReturnType(),
                    "the return type of " + codeUnit.getFullName(),
                    codeUnit,
                    events);
        }
    }
}
