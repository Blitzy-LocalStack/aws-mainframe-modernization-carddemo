package com.carddemo.authorization.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.codec.CsvAuthCodec.AuthRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the published authorization wire contract to the codec that implements it.
 *
 * <p>Assumptions: every fact asserted here is read from a shipped artifact. The character policy comes
 * from the packaged contract on the test class path, the accept-or-refuse verdict comes from calling the
 * codec, and the canonical payload comes from the committed fixture. Nothing restates a literal that
 * either side owns, so a change on either side that breaks the agreement fails this class rather than
 * passing it.</p>
 *
 * <p>Refactoring Rationale: the constraint these assertions cover was published in a form that
 * contradicted the codec. The pattern admitted the ISO control ranges while the codec's decode path
 * refused them across a whole payload, so the document promised something the wire would not carry. The
 * codec's encode path was corrected to refuse them at the field, and this class is what stops the two
 * halves parting company again.</p>
 *
 * <p>Trade-offs: the codec is exercised through the merchant-name member rather than through all
 * twenty-four. Every member is validated by the same routine, so one member reaches the policy under
 * test; driving all of them would multiply the case count by twenty-four to re-exercise one code path.
 * The cost is that a member wired to a DIFFERENT routine would go unnoticed here, which is why the
 * membership assertion below reads the reference on every member separately.</p>
 */
class WireCharacterConstraintTest {

    /** The packaged contract of record for this context, read from the test class path. */
    private static final String CONTRACT_RESOURCE = "/openapi/authorization-api.yaml";

    /** The committed canonical request payload, one line, no trailing delimiter. */
    private static final String CANONICAL_FIXTURE = "/fixtures/auth-request-canonical-wire170.csv";

    /** The schema every wire member must reference for its character policy. */
    private static final String SHARED_CONSTRAINT = "WireCharacterField";

    /** The JSON-pointer form of that reference, as it appears in the document. */
    private static final String SHARED_CONSTRAINT_REF = "#/components/schemas/" + SHARED_CONSTRAINT;

    /** Transmitted length of the request payload: 153 declared characters plus 17 separators. */
    private static final int REQUEST_WIRE_LENGTH = 170;

    /** Members of the request message, one per field of the reference copybook. */
    private static final int REQUEST_MEMBER_COUNT = 18;

    /** Members of the reply message, one per field of the reference copybook. */
    private static final int REPLY_MEMBER_COUNT = 6;

    /**
     * Confirms the published character policy and the codec reach the same verdict on one value.
     *
     * <p>Assumptions: the candidate is placed in the merchant-name member, whose declared width of
     * twenty-two exceeds every candidate below, so a refusal can only come from the character policy
     * and never from the width check that runs beside it.</p>
     *
     * @param description a printable label for the candidate, so a control character never reaches the
     *     surefire report, whose XML cannot carry one
     * @param candidate the value to offer to both sides
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("wireCandidates")
    @DisplayName("the published constraint and the codec agree on every candidate")
    void publishedConstraintAndCodecAgreeOnEveryCandidate(String description, String candidate) {
        boolean publishedVerdict = publishedConstraint().matcher(candidate).matches();
        boolean codecVerdict = codecAccepts(candidate);

        assertThat(publishedVerdict)
                .as("%s: the contract says %s and the codec says %s", description,
                        publishedVerdict ? "accept" : "refuse", codecVerdict ? "accept" : "refuse")
                .isEqualTo(codecVerdict);
    }

    /**
     * Confirms the candidate table drives both verdicts, so agreement is not agreement on nothing.
     *
     * <p>Assumptions: a table that happened to be all-accepted, or all-refused, would satisfy the
     * comparison above against a pattern that accepted everything, or nothing, at all. This asserts the
     * table itself is discriminating before its verdicts are trusted.</p>
     */
    @Test
    @DisplayName("the candidate table exercises both verdicts, so the comparison discriminates")
    void candidateTableExercisesBothVerdicts() {
        List<String> accepted = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        wireCandidates().forEach(argument -> {
            String candidate = (String) argument.get()[1];
            (codecAccepts(candidate) ? accepted : refused).add((String) argument.get()[0]);
        });

        assertThat(accepted).as("a table the codec accepts entirely cannot detect an over-wide pattern")
                .isNotEmpty();
        assertThat(refused).as("a table the codec refuses entirely cannot detect an over-narrow pattern")
                .isNotEmpty();
    }

    /**
     * Confirms every member of both wire messages carries the shared character constraint.
     *
     * <p>Assumptions: the reference is read per member rather than counted in aggregate. A member added
     * later without the reference is the failure this guards, and an aggregate count would pass as soon
     * as the totals matched however the references were distributed.</p>
     */
    @Test
    @DisplayName("all twenty-four wire members reference the shared character constraint")
    void allWireMembersReferenceTheSharedConstraint() {
        assertThat(constrainedMembers("AuthorizationRequestMessage"))
                .as("every request member must be bound by the wire character policy")
                .hasSize(REQUEST_MEMBER_COUNT);
        assertThat(constrainedMembers("AuthorizationReplyMessage"))
                .as("every reply member must be bound by the wire character policy")
                .hasSize(REPLY_MEMBER_COUNT);
    }

    /**
     * Confirms no published example violates the constraint the same document places on it.
     *
     * <p>Assumptions: an example is the value a reader copies first, so an example that its own schema
     * would reject teaches the wrong shape and is refused at runtime by the codec. This walks the
     * examples actually present rather than requiring one on every member.</p>
     *
     * <p>Trade-offs: BOTH example keywords are read, the singular {@code example} and the JSON Schema
     * {@code examples} array, and this document genuinely uses each in different places -- the two money
     * members carry the singular form while the abend members carry the array. Reading only one keyword
     * would make this assertion pass by finding nothing the moment an author switched forms, which is a
     * worse failure than a slightly wider walk.</p>
     */
    @Test
    @DisplayName("every published wire example satisfies the published constraint")
    void everyPublishedWireExampleSatisfiesThePublishedConstraint() {
        Pattern constraint = publishedConstraint();
        List<String> offenders = new ArrayList<>();
        int examined = 0;
        for (String schema : List.of("AuthorizationRequestMessage", "AuthorizationReplyMessage")) {
            for (Map.Entry<String, Map<String, Object>> member : members(schema).entrySet()) {
                for (Object example : declaredExamples(member.getValue())) {
                    examined++;
                    if (!constraint.matcher(String.valueOf(example)).matches()) {
                        offenders.add(schema + "." + member.getKey() + " = " + example);
                    }
                }
            }
        }

        assertThat(offenders)
                .as("an example its own schema rejects is a value the codec will refuse at runtime")
                .isEmpty();
        assertThat(examined)
                .as("this walk must reach at least the two money examples, or it proves nothing")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * Confirms the committed canonical fixture is the declared length and survives a round trip.
     *
     * <p>Assumptions: length and round trip are asserted together because either alone is satisfiable
     * by a wrong payload. A fixture of the right length whose amount field is a character too wide
     * still decodes, since the parse splits on delimiters rather than on offsets, and a fixture that
     * round-trips at the wrong length would leave the published arithmetic unverified.</p>
     */
    @Test
    @DisplayName("the canonical fixture is 170 characters and re-encodes byte for byte")
    void canonicalFixtureIsTheDeclaredLengthAndReEncodesUnchanged() {
        String payload = canonicalPayload();

        assertThat(payload.length())
                .as("153 declared characters plus 17 separators is the transmitted length of this wire")
                .isEqualTo(REQUEST_WIRE_LENGTH);
        assertThat(CsvAuthCodec.encodeRequest(CsvAuthCodec.decodeRequest(payload)))
                .as("the fixture must be a payload this repository's own producer would emit")
                .isEqualTo(payload);
    }

    /**
     * Supplies the candidate values, labelled so no control character reaches the test report.
     *
     * <p>Assumptions: the table is populated at the boundaries of every range either side expresses --
     * the delimiter and the printable characters flanking it, the top of the low control range and the
     * space above it, the delete character, the top of the upper control range and the no-break space
     * above it, and the single-byte ceiling with a supplementary code point beyond it. Two range
     * expressions that disagree do so at a boundary.</p>
     *
     * @return one argument pair per candidate: a printable label and the value itself
     */
    private static Stream<Arguments> wireCandidates() {
        return Stream.of(
                Arguments.of("plain printable value", "ACME HARDWARE"),
                Arguments.of("embedded delimiter", "ACME, HARDWARE"),
                Arguments.of("grouped money spelling", "1,250.00"),
                Arguments.of("punctuation either side of the delimiter", "A!\"#$%&'()*+-./Z"),
                Arguments.of("plus sign, top of the first range", "ACME+HARDWARE"),
                Arguments.of("accented letter in the upper single-byte range", "CAF\u00c9 BAR"),
                Arguments.of("single-byte ceiling", "A\u00ffZ"),
                Arguments.of("supplementary code point above the ceiling", "ACME \ud83d\ude00"),
                Arguments.of("horizontal tab", "ACME\tSTORE"),
                Arguments.of("line feed", "ACME\nSTORE"),
                Arguments.of("carriage return", "ACME\rSTORE"),
                Arguments.of("null", "ACME\u0000STORE"),
                Arguments.of("unit separator, top of the low control range", "ACME\u001fSTORE"),
                Arguments.of("delete", "ACME\u007fSTORE"),
                Arguments.of("next line, top of the upper control range", "ACME\u009fSTORE"),
                Arguments.of("no-break space, first character above it", "ACME\u00a0STORE"),
                Arguments.of("empty value", ""),
                Arguments.of("interior spaces", "ACME  STORE"));
    }

    /**
     * Collects the example values one member declares, under either example keyword.
     *
     * @param member the member schema to read; must not be {@code null}
     * @return every declared example, in document order, empty when the member declares none
     */
    private static List<Object> declaredExamples(Map<String, Object> member) {
        List<Object> declared = new ArrayList<>();
        Object singular = member.get("example");
        if (singular != null) {
            declared.add(singular);
        }
        if (member.get("examples") instanceof List<?> array) {
            array.forEach(declared::add);
        }
        return declared;
    }

    /**
     * Reads the character policy the packaged contract publishes.
     *
     * @return the compiled pattern from the shared constraint schema, never {@code null}
     * @throws IllegalStateException if the schema or its pattern is absent, which would mean this
     *     comparison was silently asserting nothing
     */
    private static Pattern publishedConstraint() {
        Object pattern = schema(SHARED_CONSTRAINT).get("pattern");
        if (pattern == null) {
            throw new IllegalStateException(SHARED_CONSTRAINT + " publishes no pattern");
        }
        return Pattern.compile(String.valueOf(pattern));
    }

    /**
     * Offers one value to the codec in a character member and reports whether it is accepted.
     *
     * <p>Assumptions: the refusal is raised by the record's canonical constructor rather than by the
     * encode call, because that is where the codec validates every member; the construction is
     * therefore inside the guarded block. The encode call follows it so an accepted value is also
     * proved to reach a payload.</p>
     *
     * @param merchantName the value to offer; must be within the member's declared width so that only
     *     the character policy can refuse it
     * @return {@code true} when the codec accepts the value, {@code false} when it refuses it
     */
    private static boolean codecAccepts(String merchantName) {
        AuthRequest canonical = CsvAuthCodec.decodeRequest(canonicalPayload());
        try {
            AuthRequest candidate = new AuthRequest(
                    canonical.authDate(), canonical.authTime(), canonical.cardNum(),
                    canonical.authType(), canonical.cardExpiryDate(), canonical.messageType(),
                    canonical.messageSource(), canonical.processingCode(),
                    canonical.transactionAmount(), canonical.merchantCategoryCode(),
                    canonical.acquirerCountryCode(), canonical.posEntryMode(),
                    canonical.merchantId(), merchantName, canonical.merchantCity(),
                    canonical.merchantState(), canonical.merchantZip(), canonical.transactionId());
            CsvAuthCodec.encodeRequest(candidate);
            return true;
        } catch (CsvAuthCodec.AuthMessageFormatException refused) {
            return false;
        }
    }

    /**
     * Reads the committed canonical payload from the test class path.
     *
     * <p>Assumptions: the bytes are decoded as ISO-8859-1 rather than UTF-8. This wire is single-byte
     * by construction, and that charset maps each byte to the code point of the same value, so a byte
     * above 0x7F survives as itself instead of becoming a replacement character.</p>
     *
     * @return the payload with its line terminator removed, never {@code null}
     * @throws IllegalStateException if the fixture is absent from the test class path or unreadable
     */
    private static String canonicalPayload() {
        try (InputStream fixture =
                WireCharacterConstraintTest.class.getResourceAsStream(CANONICAL_FIXTURE)) {
            if (fixture == null) {
                throw new IllegalStateException(CANONICAL_FIXTURE + " is not on the test class path");
            }
            return new String(fixture.readAllBytes(), StandardCharsets.ISO_8859_1).strip();
        } catch (IOException problem) {
            throw new IllegalStateException(CANONICAL_FIXTURE + " could not be read", problem);
        }
    }

    /**
     * Names the members of one wire schema that reference the shared character constraint.
     *
     * @param schemaName the wire message schema to inspect; must be present in the contract
     * @return the member names carrying the reference, in document order
     */
    private static List<String> constrainedMembers(String schemaName) {
        List<String> constrained = new ArrayList<>();
        members(schemaName).forEach((name, member) -> {
            Object composition = member.get("allOf");
            if (composition instanceof List<?> parts && parts.stream().anyMatch(
                    part -> part instanceof Map<?, ?> reference
                            && SHARED_CONSTRAINT_REF.equals(reference.get("$ref")))) {
                constrained.add(name);
            }
        });
        return constrained;
    }

    /**
     * Reads the properties of one wire schema.
     *
     * @param schemaName the schema to inspect; must be present in the contract
     * @return the schema's properties keyed by member name, never {@code null}
     * @throws IllegalStateException if the schema declares no properties, which would mean this
     *     assertion was walking an empty map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> members(String schemaName) {
        Object properties = schema(schemaName).get("properties");
        if (!(properties instanceof Map<?, ?>)) {
            throw new IllegalStateException(schemaName + " declares no properties");
        }
        return (Map<String, Map<String, Object>>) properties;
    }

    /**
     * Reads one schema out of the packaged contract.
     *
     * @param schemaName the schema to read; must be present under {@code components/schemas}
     * @return the schema as a mapping, never {@code null}
     * @throws IllegalStateException if the contract is absent from the test class path, cannot be read,
     *     or declares no such schema
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String schemaName) {
        try (InputStream document =
                WireCharacterConstraintTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (document == null) {
                throw new IllegalStateException(CONTRACT_RESOURCE + " is not on the test class path");
            }
            Map<String, Object> contract = (Map<String, Object>) new Yaml().load(document);
            Object schemas = ((Map<String, Object>) contract.get("components")).get("schemas");
            Object schema = ((Map<String, Object>) schemas).get(schemaName);
            if (!(schema instanceof Map<?, ?>)) {
                throw new IllegalStateException(CONTRACT_RESOURCE + " declares no schema " + schemaName);
            }
            return (Map<String, Object>) schema;
        } catch (IOException problem) {
            throw new IllegalStateException(CONTRACT_RESOURCE + " could not be read", problem);
        }
    }
}
