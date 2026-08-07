package com.carddemo.authorization.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts that what the two queue payload records actually SERIALIZE to is the property set the
 * published contract declares, and that the emitted document deserializes back to the same value.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: a sibling test already compares the contract's property names against
 * the records' component names through a hand-maintained table. That comparison is necessary but it
 * is not sufficient, and the gap it left is precisely the defect this class was added for. Six
 * published property names -- {@code cardNum}, {@code transactionAmt}, {@code acqrCountryCode},
 * {@code authRespCode}, {@code authRespReason} and {@code approvedAmt} -- deliberately differ from
 * the Java component names that carry them. A table can record that intent, but only running the
 * codec proves the intent reached the wire. Before the {@code @JsonProperty} annotations were
 * applied, the table-based test passed while the codec emitted {@code cardNumber},
 * {@code transactionAmount}, {@code acquirerCountryCode}, {@code authResponseCode},
 * {@code authResponseReason} and {@code approvedAmount} -- six names no declared consumer reads.
 * This class fails in that state.</p>
 *
 * <p>Alternatives Considered: driving the assertion from a checked-in golden JSON document was
 * rejected. A golden fixture records what the codec emitted on the day it was captured, so a
 * contract edit that the codec does not follow leaves the golden and the codec agreeing with each
 * other and both disagreeing with the contract -- the same blind spot in a new place. Reading the
 * published contract at run time means the contract itself is the expectation, so editing the
 * contract without editing the record fails here rather than at a consumer.</p>
 *
 * <p>Assumptions: the mapper is built with only {@link MoneyModule} registered, matching the module
 * the shared auto-configuration contributes. A full application context would also satisfy the
 * assertions, but it would additionally apply framework-wide codec settings, so a failure could no
 * longer be attributed to these two records. The narrower mapper keeps the subject of the test the
 * records rather than the container.</p>
 *
 * <p>Trade-offs: the contract is read from the classpath rather than from a repository-relative
 * path, so this asserts against the document PACKAGED with the service. A working-directory path
 * would keep passing after the contract stopped being copied into the artifact, which is the one
 * failure a consumer would actually experience.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class AuthorizationPayloadSerializationTest {

    /**
     * The classpath location of the published contract.
     */
    private static final String CONTRACT_RESOURCE = "/openapi/authorization-api.yaml";

    /**
     * The contract schema that governs the request payload's serialized form.
     */
    private static final String REQUEST_SCHEMA = "AuthorizationRequestMessage";

    /**
     * The contract schema that governs the reply payload's serialized form.
     */
    private static final String REPLY_SCHEMA = "AuthorizationReplyMessage";

    /**
     * A card number whose masked rendering is distinguishable from its full value.
     */
    private static final String CARD_NUMBER = "4111111111112345";

    /**
     * The Java component names that must NEVER appear as serialized property names.
     *
     * <p>Assumptions: this is the exact complement of the six deliberate abbreviations. Asserting
     * their ABSENCE as well as the presence of the published names is what catches a codec
     * configured to emit both spellings -- a state in which a presence-only assertion passes while
     * every message carries six redundant fields that the contract forbids.</p>
     */
    private static final List<String> COMPONENT_NAMES_NEVER_ON_THE_WIRE =
            List.of("cardNumber", "transactionAmount", "acquirerCountryCode",
                    "authResponseCode", "authResponseReason", "approvedAmount");

    /**
     * The codec under test, carrying the one module the shared auto-configuration contributes.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().addModule(new MoneyModule()).build();

    /**
     * Reads and parses the published contract from the classpath.
     *
     * @return the whole document as nested maps and lists; never {@code null}
     * @throws IllegalStateException if the resource is absent, which would mean the module ships no
     *     contract at all, or if it cannot be read or parsed as a mapping
     */
    private static Map<String, Object> loadContract() {
        try (InputStream resource =
                AuthorizationPayloadSerializationTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(
                        "the published contract is absent from the classpath at " + CONTRACT_RESOURCE);
            }
            Object parsed = new Yaml().load(resource);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException(
                        "the published contract at " + CONTRACT_RESOURCE + " is not a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> document = (Map<String, Object>) parsed;
            return document;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "the published contract at " + CONTRACT_RESOURCE + " could not be read", failure);
        }
    }

    /**
     * Returns one nested mapping by key.
     *
     * @param parent the enclosing mapping; must not be {@code null}
     * @param key the key to read
     * @return the nested mapping
     * @throws IllegalStateException if the key is absent or does not hold a mapping, so a structural
     *     assumption fails where it is made rather than as a cast far from its cause
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalStateException("expected a mapping at \"" + key + "\"");
        }
        return (Map<String, Object>) value;
    }

    /**
     * Returns the property names one named schema declares, in declaration order.
     *
     * @param schemaName the schema to read
     * @return the declared property names in declaration order; never {@code null}
     */
    private static List<String> declaredProperties(String schemaName) {
        Map<String, Object> schemas = mapping(mapping(loadContract(), "components"), "schemas");
        return List.copyOf(mapping(mapping(schemas, schemaName), "properties").keySet());
    }

    /**
     * Serializes a value and returns its top-level property names in emitted order.
     *
     * <p>Assumptions: the emitted ORDER is captured, not just the set. A record's serialized order
     * follows its component declaration order, and that order is the delimited wire order the
     * positional consumer at {@code cbl/COPAUA0C.cbl} L354-L374 reads by position. A set comparison
     * would still pass after two components were transposed, which is the one change that consumer
     * cannot survive.</p>
     *
     * @param payload the value to serialize; must not be {@code null}
     * @return the top-level property names in emitted order; never {@code null}
     */
    private static List<String> serializedProperties(Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> emitted =
                MAPPER.readValue(MAPPER.writeValueAsString(payload), Map.class);
        return List.copyOf(emitted.keySet());
    }

    /**
     * Builds a populated request payload whose every component carries a distinguishable value.
     *
     * @return a fully populated request payload; never {@code null}
     */
    private static AuthorizationRequestPayload requestPayload() {
        return new AuthorizationRequestPayload("260115", "143000", CARD_NUMBER, "01", "2812", "0100",
                "POS", "000000", Money.of("125.50"), "5411", "840", "05", "000000000",
                "CORNER STORE", "SEATTLE", "WA", "981010000", "000000000000001");
    }

    /**
     * Builds a populated reply payload whose every component carries a distinguishable value.
     *
     * @return a fully populated reply payload; never {@code null}
     */
    private static AuthorizationReplyPayload replyPayload() {
        return new AuthorizationReplyPayload(CARD_NUMBER, "000000000000001", "143000",
                "00", "0000", Money.of("125.50"));
    }

    /**
     * Verifies the request payload serializes to exactly the eighteen published names, in order.
     */
    @Test
    @DisplayName("the request payload serializes to the eighteen published names in wire order")
    void theRequestPayloadSerializesToThePublishedNamesInOrder() {
        assertThat(serializedProperties(requestPayload()))
                .as("the emitted document must carry the published names in the published order")
                .containsExactlyElementsOf(declaredProperties(REQUEST_SCHEMA));
    }

    /**
     * Verifies the reply payload serializes to exactly the six published names, in order.
     */
    @Test
    @DisplayName("the reply payload serializes to the six published names in wire order")
    void theReplyPayloadSerializesToThePublishedNamesInOrder() {
        assertThat(serializedProperties(replyPayload()))
                .as("the emitted document must carry the published names in the published order")
                .containsExactlyElementsOf(declaredProperties(REPLY_SCHEMA));
    }

    /**
     * Verifies the three abbreviated request names are emitted and their component names are not.
     */
    @Test
    @DisplayName("the request payload emits the abbreviated names and never the component names")
    void theRequestPayloadEmitsOnlyTheAbbreviatedNames() {
        List<String> emitted = serializedProperties(requestPayload());

        assertThat(emitted).contains("cardNum", "transactionAmt", "acqrCountryCode");
        assertThat(emitted)
                .as("a Java component name on the wire is a name no declared consumer reads")
                .doesNotContainAnyElementsOf(COMPONENT_NAMES_NEVER_ON_THE_WIRE);
    }

    /**
     * Verifies the three abbreviated reply names are emitted and their component names are not.
     */
    @Test
    @DisplayName("the reply payload emits the abbreviated names and never the component names")
    void theReplyPayloadEmitsOnlyTheAbbreviatedNames() {
        List<String> emitted = serializedProperties(replyPayload());

        assertThat(emitted).contains("authRespCode", "authRespReason", "approvedAmt");
        assertThat(emitted)
                .as("a Java component name on the wire is a name no declared consumer reads")
                .doesNotContainAnyElementsOf(COMPONENT_NAMES_NEVER_ON_THE_WIRE);
    }

    /**
     * Verifies the request payload survives a serialize-then-deserialize cycle unchanged.
     *
     * <p>Assumptions: the round trip is asserted in addition to the emitted names because the
     * annotation governs BOTH directions. A name applied for output only would satisfy every
     * assertion above and still reject the document it had just produced, which is the failure a
     * requester would see first.</p>
     */
    @Test
    @DisplayName("the request payload round-trips through its published form unchanged")
    void theRequestPayloadRoundTripsUnchanged() {
        AuthorizationRequestPayload original = requestPayload();

        AuthorizationRequestPayload restored = MAPPER.readValue(
                MAPPER.writeValueAsString(original), AuthorizationRequestPayload.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(restored.transactionAmount()).isEqualTo(Money.of("125.50"));
        assertThat(restored.acquirerCountryCode()).isEqualTo("840");
    }

    /**
     * Verifies the reply payload survives a serialize-then-deserialize cycle unchanged.
     */
    @Test
    @DisplayName("the reply payload round-trips through its published form unchanged")
    void theReplyPayloadRoundTripsUnchanged() {
        AuthorizationReplyPayload original = replyPayload();

        AuthorizationReplyPayload restored = MAPPER.readValue(
                MAPPER.writeValueAsString(original), AuthorizationReplyPayload.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.authResponseCode()).isEqualTo("00");
        assertThat(restored.authResponseReason()).isEqualTo("0000");
        assertThat(restored.approvedAmount()).isEqualTo(Money.of("125.50"));
    }

    /**
     * Verifies both amounts cross as JSON strings rather than as JSON numbers.
     *
     * <p>Assumptions: the quotation marks are asserted literally. A JSON number is routed through an
     * IEEE-754 binary approximation by most clients, which cannot retain every decimal cent
     * exactly, so the string form is the boundary that keeps the amount exact. Asserting the value
     * alone would pass for both forms and would therefore not detect the loss.</p>
     */
    @Test
    @DisplayName("both amounts cross as JSON strings so no client rounds them through a double")
    void bothAmountsCrossAsJsonStrings() {
        assertThat(MAPPER.writeValueAsString(requestPayload()))
                .contains("\"transactionAmt\":\"125.50\"")
                .doesNotContain("\"transactionAmt\":125.50");
        assertThat(MAPPER.writeValueAsString(replyPayload()))
                .contains("\"approvedAmt\":\"125.50\"")
                .doesNotContain("\"approvedAmt\":125.50");
    }

    /**
     * Verifies the full card number is absent from every rendering that is not the wire document.
     *
     * <p>Assumptions: the serialized document MUST carry the full value, because the requester pairs
     * its answer by it and the baseline transmits it at {@code cpy/CCPAURQY.cpy} L21. The record's
     * diagnostic rendering must NOT, because that rendering is what reaches a log. Asserting both in
     * one place keeps the two obligations from being confused for each other.</p>
     */
    @Test
    @DisplayName("the wire document carries the full card number and the log rendering does not")
    void theWireCarriesTheCardNumberAndTheLogRenderingDoesNot() {
        assertThat(MAPPER.writeValueAsString(requestPayload()))
                .as("the wire document is the contract and must carry the value verbatim")
                .contains("\"cardNum\":\"" + CARD_NUMBER + "\"");
        assertThat(requestPayload().toString())
                .as("the diagnostic rendering reaches a log and must not carry it")
                .doesNotContain(CARD_NUMBER);
        assertThat(replyPayload().toString()).doesNotContain(CARD_NUMBER);
    }
}
