package com.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the update body's declared constraints and the published schema's to each other, value by value.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: this class used to hold the schema's DOMAIN constraints against the record's,
 * value by value, and both sides have since been withdrawn -- so the subject of the parity changed and the
 * cases changed with it. The reason for the withdrawal is the one the review named: a declarative
 * constraint runs BEFORE the service, so whichever of the two layers declares a domain decides the outcome
 * for it, and the domain logic the service transcribes from {@code app/cbl/COCRDUPC.cbl} distinguishes
 * states that no schema and no annotation can express -- a BLANK field, which earns the literal asterisk
 * the reference writes at {@code :1263-1272}, from a merely unacceptable one, which earns the highlight
 * alone; and all four attributes reported together rather than the first fault only. Keeping the
 * declarative domains would have kept that transcription unreachable for every caller arriving over
 * HTTP.</p>
 *
 * <p>Assumptions: what this class now asserts is therefore the LAYERING rather than the domains. Three
 * claims: the schema and the record agree on every declared WIDTH, which is a transport fact both may
 * state; neither declares a domain, so the service is reached; and the record still admits every
 * domain-violating value, which is the positive form of the same claim and the one that would fail if a
 * constraint were reinstated. The domains themselves are asserted where they are enforced, in
 * {@code CardUpdateServiceTest}, and over HTTP in {@code CardUpdateHttpValidationTest}.</p>
 *
 * <p>Assumptions: the schema is read from the CLASSPATH so that this asserts against the artifact the
 * service publishes rather than against a source file that may not be the one packaged.</p>
 */
class CardUpdateRequestContractParityTest {

    /** Classpath location of the contract this module publishes. */
    private static final String CONTRACT_RESOURCE = "/openapi/card-api.yaml";

    /** The schema whose properties are held against the record's constraints. */
    private static final String SCHEMA_NAME = "CardUpdateRequest";

    /** A conforming embossed name, used wherever the case under test varies another component. */
    private static final String VALID_NAME = "JOHN Q PUBLIC";

    /** A conforming expiry date inside the published window. */
    private static final String VALID_MONTH = "12";

    /**
     * A conforming expiry year.
     *
     * <p>Refactoring Rationale: the expiry was one {@code VALID_DATE} constant while the body carried an
     * assembled ISO date. It carries the month and the year as SEPARATE members now, because
     * {@code app/cbl/COCRDUPC.cbl} edits each behind its own validation flag -- {@code FLG-CARDEXPMON-*}
     * at L73 to L76 and {@code FLG-CARDEXPYEAR-*} at L77 to L80 -- so one assembled member could not
     * carry the two independent per-field refusals the screen reports. No day member exists at all: the
     * baseline renders its day field non-display and never validates it, so a caller-supplied day was a
     * value the reference never accepts.</p>
     */
    private static final String VALID_YEAR = "2026";

    /** A conforming concurrency token, being the value a freshly loaded row carries. */
    private static final int VALID_VERSION = 0;

    /** The provider factory, opened once because building one is expensive and it is stateless. */
    private static ValidatorFactory factory;

    /** The provider under test. */
    private static Validator validator;

    /** The published schema's declared properties, read once. */
    private static Map<String, Object> properties;

    /**
     * Opens the validation provider and reads the published schema once for the whole class.
     *
     * @throws IllegalStateException if the contract is absent from the classpath or is not a mapping,
     *     because every case below would otherwise fail as a null dereference far from its cause
     */
    @BeforeAll
    static void openProviderAndReadContract() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
        properties = readProperties();
    }

    /**
     * Closes the validation provider, releasing the expression factory it holds.
     */
    @AfterAll
    static void closeProvider() {
        factory.close();
    }

    /**
     * Reads the declared properties of the schema under test from the packaged contract.
     *
     * @return the property mappings, keyed by property name; never empty
     * @throws IllegalStateException if the contract is absent from the classpath, is not a mapping, or
     *     cannot be read, because every case below would otherwise fail as a null dereference far from
     *     its cause
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readProperties() {
        try (InputStream resource =
                CardUpdateRequestContractParityTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {

            if (resource == null) {
                throw new IllegalStateException(
                        "the published contract is absent from the classpath at " + CONTRACT_RESOURCE);
            }
            Object document = new Yaml().load(resource);
            if (!(document instanceof Map)) {
                throw new IllegalStateException("the published contract is not a mapping");
            }
            Map<String, Object> components = (Map<String, Object>) ((Map<String, Object>) document)
                    .get("components");
            Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
            Map<String, Object> schema = (Map<String, Object>) schemas.get(SCHEMA_NAME);
            return (Map<String, Object>) schema.get("properties");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("the published contract could not be read", failure);
        }
    }

    /**
     * Reads one property's declared mapping from the published schema.
     *
     * @param property the property name; must be declared by the schema
     * @return the declared mapping, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> declared(String property) {
        Map<String, Object> declaredProperty = (Map<String, Object>) properties.get(property);
        assertThat(declaredProperty)
                .as("%s.%s must be declared by the published schema", SCHEMA_NAME, property)
                .isNotNull();
        return declaredProperty;
    }

    /**
     * Reports whether the record admits a body whose named component carries the given value.
     *
     * <p>Assumptions: the other three components are held at conforming values so that a violation, if one
     * is raised, can only belong to the component under test. Varying one component at a time is what makes
     * the boolean answer attributable; a case that varied two could not say which constraint fired.</p>
     *
     * @param property the component to vary: one of the four attribute names
     * @param value the value to place in it
     * @return {@code true} when no declarative constraint is violated
     * @throws IllegalArgumentException if the property is not one of the four attribute names, which is a
     *     programming error in the case rather than a fact about the record
     */
    private static boolean recordAdmits(String property, String value) {
        CardUpdateRequest body = switch (property) {
            case "embossedName" ->
                new CardUpdateRequest(value, "Y", VALID_MONTH, VALID_YEAR, VALID_VERSION);
            case "activeStatus" ->
                new CardUpdateRequest(VALID_NAME, value, VALID_MONTH, VALID_YEAR, VALID_VERSION);
            case "expirationMonth" ->
                new CardUpdateRequest(VALID_NAME, "Y", value, VALID_YEAR, VALID_VERSION);
            case "expirationYear" ->
                new CardUpdateRequest(VALID_NAME, "Y", VALID_MONTH, value, VALID_VERSION);
            default -> throw new IllegalArgumentException("not an attribute of this body: " + property);
        };
        return validator.validate(body).isEmpty();
    }

    /**
     * Confirms each attribute publishes its declared width and publishes no domain constraint.
     *
     * <p>Assumptions: the four constraint keywords named here are the complete set a domain could be
     * expressed with for a string property of this shape -- a pattern, an enumeration, a minimum length or
     * a format -- so asserting the absence of all four is what makes the claim exhaustive rather than a
     * check on whichever one happened to be there before. A property that reinstated any one of them would
     * decide the outcome for some value before the service saw it, which is the defect this whole
     * arrangement exists to prevent.</p>
     *
     * @param property the schema property to inspect
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"embossedName", "activeStatus", "expirationMonth", "expirationYear"})
    @DisplayName("the schema declares a width for each attribute and no domain")
    void theSchemaDeclaresAWidthAndNoDomainForEachAttribute(String property) {
        Map<String, Object> declaredProperty = declared(property);

        assertThat(declaredProperty.get("maxLength"))
                .as("%s.%s must still publish its declared width, which is a transport fact", SCHEMA_NAME,
                        property)
                .isNotNull();
        assertThat(declaredProperty)
                .as("%s.%s must publish no domain constraint, because the service owns the domain and a"
                        + " declarative one would decide the outcome before the service is reached",
                        SCHEMA_NAME, property)
                .doesNotContainKeys("pattern", "enum", "minLength", "format");
    }

    /**
     * Confirms the schema's declared width and the record's declared width agree, attribute by attribute.
     *
     * <p>Assumptions: the width is the one constraint both layers may legitimately state, because it is a
     * property of the field the reference terminal could hold rather than a rule about the value: the 3270
     * input physically could not carry more characters, so the reference has no sentence for an over-long
     * value and neither does the service. A caller that sends one therefore gets the declarative refusal,
     * and this case is what keeps the two declarations of that single width from drifting.</p>
     *
     * @param property the schema property name
     * @param width the width both sides must declare
     */
    @ParameterizedTest(name = "{0} is bounded at {1}")
    @CsvSource({"embossedName,50", "activeStatus,1", "expirationMonth,2", "expirationYear,4"})
    @DisplayName("schema and record declare the same width for every attribute")
    void schemaAndRecordDeclareTheSameWidth(String property, int width) {
        assertThat((Integer) declared(property).get("maxLength")).isEqualTo(width);
        assertThat(recordAdmits(property, "A".repeat(width))).isTrue();
        assertThat(recordAdmits(property, "A".repeat(width + 1)))
                .as("one character past the declared width must be refused declaratively")
                .isFalse();
    }

    /**
     * Confirms the record admits every value the four reference domains refuse, so the service classifies
     * it rather than the validator.
     *
     * <p>Assumptions: these are exactly the values that used to be refused declaratively, listed as the
     * positive form of the claim above. A blank name and an all-zeros month are the two that matter most,
     * because the reference classifies both as BLANK rather than as unacceptable and answers them with a
     * different sentence and with the asterisk marker; a validator that refused them first would make that
     * distinction unobservable. The year bounds and the status domain are included so that reinstating any
     * one constraint fails a case rather than passing silently.</p>
     *
     * @param property the schema property name the value belongs to
     * @param value the domain-violating value the record must nonetheless admit
     */
    @ParameterizedTest(name = "{0}=[{1}] reaches the service")
    @CsvSource({
        "embossedName,' '",
        "embossedName,JOHN-PAUL",
        "embossedName,'0000'",
        "activeStatus,X",
        "activeStatus,''",
        "expirationMonth,00",
        "expirationMonth,13",
        "expirationMonth,1",
        "expirationYear,1949",
        "expirationYear,2100",
        "expirationYear,0000",
    })
    @DisplayName("every domain-violating value passes the declarative layer and reaches the service")
    void everyDomainViolatingValueReachesTheService(String property, String value) {
        assertThat(recordAdmits(property, value))
                .as("%s carrying [%s] must reach CardUpdateService, which owns the reference domain and"
                        + " the sentence that refuses it", property, value)
                .isTrue();
    }

    /**
     * Confirms neither side carries an expiry DAY member at all.
     *
     * <p>Refactoring Rationale: this replaces a case that asserted the schema and the record agreed the
     * day was "bounded by shape alone", which required both to carry a day and admitted the thirty-first
     * of February on the ground that no baseline paragraph validates a day. The premise was right and the
     * conclusion was inverted: a field the reference never validates is a field the reference does not let
     * a user set, and {@code app/cbl/COCRDUPC.cbl} confirms it by writing the OLD day back to the screen
     * at L1110 and L1123 with the new-day move commented out at L1122. A caller-settable unvalidated day
     * was therefore a widening of the reference, so no member carries one and the stored date keeps the
     * day it already held.</p>
     */
    @Test
    @DisplayName("neither the schema nor the record carries an expiry day")
    void neitherSideCarriesAnExpiryDay() {
        assertThat(properties.keySet())
                .as("a day member would be a caller-settable field the reference never validates")
                .containsExactlyInAnyOrder("embossedName", "activeStatus", "expirationMonth",
                        "expirationYear", "version");
        assertThat(CardUpdateRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("embossedName", "activeStatus", "expirationMonth", "expirationYear",
                        "version");
    }

    /**
     * Confirms the declared width bounds the name on both sides and the schema publishes the same bound.
     *
     * <p>Assumptions: the width is the copybook's, {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:8}, and it is asserted on the schema as well as on the record because a
     * maximum length is the one constraint a code generator maps to a check on every mapping.</p>
     */
    @Test
    @DisplayName("fifty characters are accepted and fifty-one refused, on both sides")
    void theDeclaredNameWidthBoundsBothSides() {
        assertThat(recordAdmits("embossedName", "A".repeat(50))).isTrue();
        assertThat(recordAdmits("embossedName", "A".repeat(51))).isFalse();

        @SuppressWarnings("unchecked")
        Map<String, Object> declared = (Map<String, Object>) properties.get("embossedName");
        assertThat((Integer) declared.get("maxLength")).isEqualTo(50);
    }

    /**
     * Confirms the declarative layer carries NO sentence for any attribute fault, because it raises none.
     *
     * <p>Refactoring Rationale: this case used to assert that the record's own violation messages were the
     * reference sentences -- {@code 'Card name not provided'} for an absent value and
     * {@code 'Card name can only contain alphabets and spaces'} for a bad character set, from
     * {@code app/cbl/COCRDUPC.cbl:181-182} and {@code :183-184}. Both sentences are still carried
     * verbatim and both are still asserted, but by the layer that now reports them: they are declared as
     * public constants on {@code CardUpdateService} and are asserted in that service's own tests and over
     * HTTP. Asserting them here would mean the record still refused these values, which is exactly what it
     * must no longer do.</p>
     *
     * <p>Assumptions: an EMPTY violation list is the assertion, and the two sentences are named in the
     * message so that a reader who reinstates a constraint sees which behaviour they have taken away.</p>
     */
    @Test
    @DisplayName("no attribute fault raises a declarative violation, so no sentence is emitted here")
    void noAttributeFaultRaisesADeclarativeViolation() {
        assertThat(messagesFor("   "))
                .as("an absent name must reach the service, which answers 'Card name not provided'")
                .isEmpty();
        assertThat(messagesFor("JOHN-PAUL"))
                .as("a bad character set must reach the service, which answers 'Card name can only"
                        + " contain alphabets and spaces'")
                .isEmpty();
        assertThat(messagesFor(null))
                .as("an omitted name is the same BLANK state as a whitespace one and is classified by the"
                        + " service, not refused here")
                .isEmpty();
    }

    /**
     * Reads the violation messages a body carrying the given embossed name raises.
     *
     * @param embossedName the value to submit
     * @return every violation message, in no defined order
     */
    private static List<String> messagesFor(String embossedName) {
        return validator.validate(new CardUpdateRequest(
                embossedName, "Y", VALID_MONTH, VALID_YEAR, VALID_VERSION))
                .stream()
                .map(ConstraintViolation::getMessage)
                .toList();
    }

    /**
     * Confirms a conforming body raises no violation, so every refusal above is attributable.
     */
    @Test
    @DisplayName("a conforming body raises no violation")
    void aConformingBodyRaisesNoViolation() {
        assertThat(validator.validate(
                new CardUpdateRequest(VALID_NAME, "Y", VALID_MONTH, VALID_YEAR, 3))).isEmpty();
        assertThat(validator.validate(
                new CardUpdateRequest(VALID_NAME, "N", VALID_MONTH, VALID_YEAR, 0))).isEmpty();
    }

    /**
     * Confirms the diagnostic rendering withholds the cardholder's name and keeps the rest.
     *
     * <p>Assumptions: asserted in this class rather than in a rendering-specific one because the refusal
     * path above is precisely where the exposure arises -- the framework puts the bound target into the
     * message of the exception a failed constraint raises, so a body that fails validation is a body whose
     * rendered form reaches a log. The three retained components are asserted present as well, because a
     * rendering that carried nothing would be safe and useless.</p>
     */
    @Test
    @DisplayName("the diagnostic rendering withholds the embossed name")
    void theDiagnosticRenderingWithholdsTheEmbossedName() {
        String rendered = new CardUpdateRequest(VALID_NAME, "Y", VALID_MONTH, VALID_YEAR, 3).toString();

        assertThat(rendered).doesNotContain(VALID_NAME)
                .doesNotContain("JOHN")
                .contains("CardUpdateRequest[")
                .contains("embossedName=REDACTED")
                .contains("activeStatus=Y")
                .doesNotContain(VALID_YEAR)
                .contains("version=3");
    }

    /**
     * Confirms narrowing the rendering left equality and hashing untouched.
     *
     * <p>Assumptions: this is the assertion that keeps the redaction from becoming a behavioural change.
     * Two bodies differing only in the withheld component print identically and must still compare
     * unequal, because a record's identity is its components and not its printed form.</p>
     */
    @Test
    @DisplayName("redacting the rendering does not merge two distinct bodies")
    void redactingTheRenderingDoesNotMergeDistinctBodies() {
        CardUpdateRequest first = new CardUpdateRequest("JOHN Q PUBLIC", "Y", VALID_MONTH, VALID_YEAR, 3);
        CardUpdateRequest second = new CardUpdateRequest("JANE R PUBLIC", "Y", VALID_MONTH, VALID_YEAR, 3);

        assertThat(first.toString()).isEqualTo(second.toString());
        assertThat(first).isNotEqualTo(second);
        assertThat(first.embossedName()).isEqualTo("JOHN Q PUBLIC");
        assertThat(second.embossedName()).isEqualTo("JANE R PUBLIC");
    }
}
