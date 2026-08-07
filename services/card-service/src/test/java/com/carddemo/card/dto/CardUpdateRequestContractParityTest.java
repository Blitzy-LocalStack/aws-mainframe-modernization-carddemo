package com.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
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
 * <p>Refactoring Rationale: the review found two disagreements in this one schema, and both were
 * invisible to a compiler because each side of them is a line in a different kind of file. The embossed
 * name published {@code '^[A-Za-z ]+$'}, which admits a value of fifty spaces, while the record carries
 * {@code @NotBlank} beside the same character-set pattern and refuses one -- and a blank-padded field is
 * the ordinary shape of an unfilled 3270 input rather than an exotic case. The expiry date published
 * {@code format: date} alone, which admits 1949 and 2100, while the record encodes the window
 * {@code 88 VALID-YEAR VALUES 1950 THRU 2099} at {@code app/cbl/COCRDUPC.cbl:99} and refuses both. In each
 * case a caller could generate a body this document accepts and the service answers 400.</p>
 *
 * <p>Assumptions: parity is asserted by ACCEPTANCE over a value corpus, not by comparing the two
 * expressions as text. They are deliberately not identical -- the schema states as one pattern what the
 * record states as two constraints, because a property carries one pattern -- so a textual comparison would
 * fail on a correct implementation and would say nothing about what either side admits. Comparing what
 * they accept is also the stronger claim: it is the property a caller actually depends on.</p>
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
     * Reads a property's declared pattern as a compiled expression.
     *
     * @param property the property name; must be declared and must carry a pattern
     * @return the compiled pattern, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Pattern declaredPattern(String property) {
        Map<String, Object> declared = (Map<String, Object>) properties.get(property);
        assertThat(declared)
                .as("%s.%s must be declared by the published schema", SCHEMA_NAME, property)
                .isNotNull();
        Object declaredPattern = declared.get("pattern");
        assertThat(declaredPattern)
                .as("%s.%s must publish its domain as a pattern, because prose is not machine-readable",
                        SCHEMA_NAME, property)
                .isNotNull();
        return Pattern.compile(String.valueOf(declaredPattern));
    }

    /**
     * Reports whether the record admits a body carrying the given embossed name.
     *
     * @param embossedName the value to submit
     * @return {@code true} when no constraint is violated
     */
    private static boolean recordAdmitsName(String embossedName) {
        return validator.validate(new CardUpdateRequest(
                embossedName, "Y", VALID_MONTH, VALID_YEAR, VALID_VERSION)).isEmpty();
    }

    /**
     * Reports whether the record admits a body carrying the given expiry year.
     *
     * @param expirationYear the value to submit
     * @return {@code true} when no constraint is violated
     */
    private static boolean recordAdmitsYear(String expirationYear) {
        return validator.validate(new CardUpdateRequest(
                VALID_NAME, "Y", VALID_MONTH, expirationYear, VALID_VERSION)).isEmpty();
    }

    /**
     * Reports whether the record admits a body carrying the given expiry month.
     *
     * @param expirationMonth the value to submit
     * @return {@code true} when no constraint is violated
     */
    private static boolean recordAdmitsMonth(String expirationMonth) {
        return validator.validate(new CardUpdateRequest(
                VALID_NAME, "Y", expirationMonth, VALID_YEAR, VALID_VERSION)).isEmpty();
    }

    /**
     * Confirms the schema and the record agree on the four boundary years the review named.
     *
     * <p>Assumptions: 1949 and 2100 are the two values that were schema-valid and service-invalid before
     * the correction, and 1950 and 2099 are the two the window includes; asserting all four in one case
     * keeps the pair of bounds from drifting apart, because a window narrowed at one end alone would still
     * pass a test that checked only the other.</p>
     *
     * @param year the year to submit
     * @param admitted whether both sides must admit it
     */
    @ParameterizedTest(name = "year {0} admitted={1}")
    @CsvSource({"1949,false", "1950,true", "2099,true", "2100,false"})
    @DisplayName("schema and record agree at 1949, 1950, 2099 and 2100")
    void schemaAndRecordAgreeAtEveryYearBoundary(String year, boolean admitted) {
        assertThat(declaredPattern("expirationYear").matcher(year).matches())
                .as("the published pattern must %s %s", admitted ? "admit" : "refuse", year)
                .isEqualTo(admitted);
        assertThat(recordAdmitsYear(year))
                .as("the record must %s %s", admitted ? "admit" : "refuse", year)
                .isEqualTo(admitted);
    }

    /**
     * Confirms the schema and the record agree on the month domain at both of its edges.
     *
     * <p>Assumptions: 00 and 13 are the values outside the domain and 01 and 12 the values on it, and the
     * unpadded form 1 is asserted refused as well. The baseline's month test is a class condition over a
     * two-character field, so a single digit followed by nothing does not satisfy it; a contract that
     * admitted the unpadded form would produce a value the service refuses for a reason the caller could
     * not read from the document.</p>
     *
     * @param month the month component to submit
     * @param admitted whether both sides must admit it
     */
    @ParameterizedTest(name = "month {0} admitted={1}")
    @CsvSource({"00,false", "01,true", "12,true", "13,false", "1,false"})
    @DisplayName("schema and record agree on the month domain")
    void schemaAndRecordAgreeOnTheMonthDomain(String month, boolean admitted) {
        assertThat(declaredPattern("expirationMonth").matcher(month).matches()).isEqualTo(admitted);
        assertThat(recordAdmitsMonth(month)).isEqualTo(admitted);
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
     * Confirms the schema and the record agree that a whitespace-only embossed name is refused.
     *
     * <p>Assumptions: three widths of blank are submitted, including the full declared width, because the
     * value the schema used to admit was specifically a blank-padded screen field and the widest form is
     * the one a terminal would actually send.</p>
     *
     * @param blank the whitespace-only value to submit
     */
    @ParameterizedTest(name = "blank name of {0} characters")
    @ValueSource(strings = {" ", "     ", "                                                  "})
    @DisplayName("schema and record agree that an all-whitespace embossed name is refused")
    void schemaAndRecordAgreeThatABlankNameIsRefused(String blank) {
        assertThat(declaredPattern("embossedName").matcher(blank).matches())
                .as("the published pattern must refuse a name of only spaces")
                .isFalse();
        assertThat(recordAdmitsName(blank))
                .as("the record must refuse a name of only spaces")
                .isFalse();
    }

    /**
     * Confirms the schema and the record agree on names that carry blanks alongside letters.
     *
     * <p>Assumptions: this is the negative half of the whitespace rule and the half a careless tightening
     * breaks. The rule is presence of a letter, not absence of a blank, so a leading or trailing blank must
     * still be admitted -- the reference field is blank-padded and the baseline never trims it before its
     * letter test at {@code app/cbl/COCRDUPC.cbl:824-828}.</p>
     *
     * @param name the value to submit
     * @param admitted whether both sides must admit it
     */
    @ParameterizedTest(name = "name [{0}] admitted={1}")
    @CsvSource({
        "'JOHN Q PUBLIC',true",
        "'  JOHN',true",
        "'JOHN  ',true",
        "'A',true",
        "'JOHN O''NEILL',false",
        "'JOHN-PAUL',false",
        "'JOHN2',false",
    })
    @DisplayName("schema and record agree on every mixed name form")
    void schemaAndRecordAgreeOnMixedNameForms(String name, boolean admitted) {
        assertThat(declaredPattern("embossedName").matcher(name).matches()).isEqualTo(admitted);
        assertThat(recordAdmitsName(name)).isEqualTo(admitted);
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
        assertThat(recordAdmitsName("A".repeat(50))).isTrue();
        assertThat(recordAdmitsName("A".repeat(51))).isFalse();

        @SuppressWarnings("unchecked")
        Map<String, Object> declared = (Map<String, Object>) properties.get("embossedName");
        assertThat((Integer) declared.get("maxLength")).isEqualTo(50);
    }

    /**
     * Confirms the refusal sentences are the reference's own, character for character.
     *
     * <p>Assumptions: the two faults of the name are answered by two different sentences in the baseline,
     * at {@code app/cbl/COCRDUPC.cbl:181-182} for absence and at :183-184 for the character set, so a
     * single sentence covering both would tell a caller that supplied a hyphen that it supplied nothing.
     * A whitespace-only value raises the ABSENCE sentence, which is the behaviour the schema correction
     * publishes.</p>
     */
    @Test
    @DisplayName("the name refusals carry the reference sentences verbatim")
    void theNameRefusalsCarryTheReferenceSentences() {
        assertThat(messagesFor("   ")).containsExactly("Card name not provided");
        assertThat(messagesFor("JOHN-PAUL"))
                .containsExactly("Card name can only contain alphabets and spaces");
        assertThat(messagesFor(null)).containsExactly("Card name not provided");
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
