package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.carddemo.authorization.dto.PendingAuthPageQuery;
import com.carddemo.common.money.MoneyModule;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds the request reader this module configures to the domains its published contract declares.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>⚠️ Refactoring Rationale: three separate leniencies were measured at this boundary, and each one
 * answered 200 to a body the published document refuses. An undeclared member was DISCARDED, so a listing
 * request carrying {@code Direction} with a capital D was answered with the forward page while the caller
 * had asked to move backward. A member NAMED TWICE was resolved by keeping the last occurrence, so a body
 * naming two accounts was answered for the second. And a JSON NUMBER was converted where the document
 * declares a string, so a numeric account scope was accepted. None of the three is visible in the answer:
 * every one produces a well-formed response that describes something other than what was asked.
 *
 * <p>⚠️ Assumptions: the reader under test is assembled here from the SAME three decisions the module
 * ships -- the two feature keys in {@code application.yml} and the coercion refusal in
 * {@link JsonReadConfig} -- rather than obtained from a started context. The first case below is what
 * makes that safe: it reads the packaged configuration document and asserts the two keys are set to the
 * values assembled here, so the assembly cannot describe a reader the module does not build. A started
 * context was rejected because this module's context resolves an identity provider's discovery document
 * eagerly, which no build agent can reach.
 *
 * <p>⚠️ Assumptions: the last case is not a formality. Money crosses every boundary in this migration as a
 * JSON STRING and is read into a {@code BigDecimal}, which is a string-shaped input to a numeric target --
 * the mirror image of what the coercion refusal forbids. A refusal written symmetrically would take the
 * money contract down with it, silently, on the cross-context account read that carries three amounts, so
 * the surviving direction is asserted alongside the refused one.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.
 */
class JsonReadConfigTest {

    /** The packaged base configuration document whose two reader keys this class mirrors. */
    private static final String BASE_DOCUMENT = "/application.yml";

    /** A body naming exactly the three declared members, which every case below varies from. */
    private static final String VALID_BODY = "{\"accountId\":\"20000000001\"}";

    /**
     * Builds the reader this module configures, from its three constituent decisions.
     *
     * <p>⚠️ Assumptions: {@link MoneyModule} is registered because the framework registers it as a module
     * bean in the running application, and the final case reads an amount. Omitting it here would leave
     * that case asserting the reader's default handling of {@code BigDecimal} rather than this migration's.
     *
     * @return a mapper configured as the running module's reader is, never {@code null}
     */
    private static JsonMapper configuredReader() {
        JsonMapper.Builder builder = JsonMapper.builder()
                .addModule(new MoneyModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        new JsonReadConfig().refuseNonTextualScalarsForTextTargets().customize(builder);
        return builder.build();
    }

    /**
     * Confirms the packaged document sets the two reader keys this class assembles by hand.
     *
     * <p>⚠️ Assumptions: both keys are asserted as the BOOLEAN {@code true} rather than as a present key,
     * because a key present and set false configures the leniency back on while still reading as
     * configured. The paths are spelled out rather than derived, since they are the two the framework binds
     * and a typo in either would leave the document silently at its default.
     *
     * @throws IOException if the packaged document cannot be read
     */
    @Test
    @DisplayName("the packaged configuration sets the two reader keys this test assembles")
    void packagedConfigurationSetsTheTwoReaderKeys() throws IOException {
        Map<String, Object> document = document(BASE_DOCUMENT);

        assertThat(nested(document, "spring", "jackson", "deserialization"))
                .as("an undeclared member must be refused, not discarded")
                .containsEntry("fail-on-unknown-properties", Boolean.TRUE);
        assertThat(nested(document, "spring", "jackson", "read"))
                .as("a member named twice must be refused, not resolved by position")
                .containsEntry("strict-duplicate-detection", Boolean.TRUE);

        // WHY : ⚠️ Assumptions: neither overlay may carry a reader key at all. The base document is loaded
        //       for every profile, so an overlay naming one of these keys would not narrow the setting --
        //       it would REPLACE it for that environment alone, and the environment it would most likely be
        //       relaxed in is the one where a client is first written against the contract.
        for (String overlay : new String[] {"/application-dev.yml", "/application-prod.yml"}) {
            assertThat(nested(document(overlay), "spring"))
                    .as("%s must not re-declare the reader configuration the base document sets", overlay)
                    .doesNotContainKey("jackson");
        }
    }

    /**
     * An undeclared member is refused rather than discarded.
     *
     * <p>⚠️ Assumptions: two shapes are exercised, an obviously foreign member and a MISSPELLING of a real
     * one, because they fail for the same reason and matter for different ones. The foreign member is the
     * contract violation; the misspelling is the measured defect, since dropping it also drops the value it
     * carried and the request then means something else.
     */
    @Test
    @DisplayName("an undeclared member and a misspelled member are both refused")
    void undeclaredAndMisspelledMembersAreRefused() {
        JsonMapper reader = configuredReader();

        assertThatExceptionOfType(DatabindException.class).isThrownBy(() ->
                reader.readValue("{\"accountId\":\"20000000001\",\"bogus\":\"x\"}",
                        PendingAuthPageQuery.class));
        assertThatExceptionOfType(DatabindException.class).isThrownBy(() ->
                reader.readValue("{\"accountId\":\"20000000001\",\"Direction\":\"previous\"}",
                        PendingAuthPageQuery.class));
    }

    /**
     * A member named twice is refused rather than resolved by position.
     *
     * <p>⚠️ Assumptions: the two occurrences carry DIFFERENT accounts, so a reader that resolves the
     * ambiguity silently produces an observably wrong query rather than a harmless duplicate. That is the
     * measured behaviour this refusal replaces: the answer described the second account.
     */
    @Test
    @DisplayName("a member named twice is refused rather than resolved by position")
    void aMemberNamedTwiceIsRefused() {
        JsonMapper reader = configuredReader();

        assertThatExceptionOfType(Exception.class).isThrownBy(() ->
                reader.readValue("{\"accountId\":\"20000000001\",\"accountId\":\"10000000101\"}",
                        PendingAuthPageQuery.class));
    }

    /**
     * A JSON number is refused where the contract declares a character field.
     *
     * <p>⚠️ Assumptions: the refusal is asserted for a value whose digits WOULD satisfy the member's
     * pattern once converted, because that is the only interesting case. A number that converts to
     * something the pattern rejects is refused either way, so it would pass this assertion against an
     * unconfigured reader and prove nothing.
     */
    @Test
    @DisplayName("a JSON number is refused for a member the contract declares as a string")
    void aJsonNumberIsRefusedForAStringMember() {
        JsonMapper reader = configuredReader();

        assertThatExceptionOfType(DatabindException.class).isThrownBy(() ->
                reader.readValue("{\"accountId\":20000000001}", PendingAuthPageQuery.class));
    }

    /**
     * A second JSON value after the body is refused, and that refusal is the reader's own default.
     *
     * <p>⚠️ Assumptions: this case is a PIN rather than a change. The current Jackson generation enables
     * the trailing-token refusal by default, which is why no key for it appears in the packaged document;
     * the pin exists so that a future switch of the reader's defaults -- the compatibility flag that
     * restores the previous generation's behaviour would do exactly that -- fails here instead of silently
     * admitting a body with a second value appended.
     */
    @Test
    @DisplayName("a trailing second JSON value is refused by the reader's own default")
    void aTrailingSecondJsonValueIsRefused() {
        JsonMapper reader = configuredReader();

        assertThatExceptionOfType(Exception.class).isThrownBy(() ->
                reader.readValue(VALID_BODY + " " + VALID_BODY, PendingAuthPageQuery.class));
    }

    /**
     * A conforming body still reads, and a money string still reads into an exact decimal.
     *
     * <p>⚠️ Assumptions: the two halves are asserted together on purpose. The first says the refusals
     * above did not close the ordinary path; the second says they did not close the direction this
     * migration depends on, in which a string-shaped input reaches a numeric target. A refusal that broke
     * the second would show up nowhere else in this class.
     */
    @Test
    @DisplayName("a conforming body reads, and a money string still reads into an exact decimal")
    void aConformingBodyReadsAndMoneyStringsStillRead() {
        JsonMapper reader = configuredReader();

        assertThatNoException().isThrownBy(() ->
                reader.readValue(VALID_BODY, PendingAuthPageQuery.class));
        assertThat(reader.readValue(VALID_BODY, PendingAuthPageQuery.class).accountId())
                .isEqualTo("20000000001");
        assertThat(reader.readValue("{\"amount\":\"9999999999.99\"}", MoneyHolder.class).amount())
                .as("money crosses as a string and must still reach an exact decimal")
                .isEqualByComparingTo(new BigDecimal("9999999999.99"));
    }

    /**
     * Reads and parses one packaged configuration document.
     *
     * @param resource the classpath resource name, leading slash included
     * @return the parsed document, never {@code null}
     * @throws IOException if the resource cannot be read, which would mean the module was packaged without
     *     its own configuration and no assertion here would be meaningful
     */
    private static Map<String, Object> document(String resource) throws IOException {
        try (InputStream stream = JsonReadConfigTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("the packaged %s must be readable", resource).isNotNull();
            return new Yaml().load(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Descends a parsed document through a key path, failing loudly when a level is absent.
     *
     * @param document the parsed document to descend
     * @param path the keys to descend through, outermost first
     * @return the mapping at the end of the path, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> document, String... path) {
        Object node = document;
        StringBuilder walked = new StringBuilder();
        for (String key : path) {
            walked.append('/').append(key);
            assertThat(node).as("the document must carry a mapping at %s", walked).isInstanceOf(Map.class);
            node = ((Map<String, Object>) node).get(key);
        }
        assertThat(node).as("the document must carry a mapping at %s", walked).isInstanceOf(Map.class);
        return (Map<String, Object>) node;
    }

    /**
     * A one-member carrier for the money-direction assertion.
     *
     * <p>⚠️ Assumptions: a local carrier is used rather than one of this module's published bodies because
     * none of them declares a money member -- every amount this context publishes travels outward, on a
     * response. The carrier therefore stands for the cross-context account read, whose three amounts arrive
     * as strings and are the values a symmetric coercion refusal would have broken.
     *
     * @param amount the amount as it arrives, a JSON string reaching an exact decimal
     */
    private record MoneyHolder(BigDecimal amount) {
    }
}
