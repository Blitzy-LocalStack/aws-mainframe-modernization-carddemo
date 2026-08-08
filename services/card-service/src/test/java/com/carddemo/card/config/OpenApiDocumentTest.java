package com.carddemo.card.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that the document {@link OpenApiConfig} publishes declares the specification version whose
 * members it carries, and that its security requirement keeps the shape the committed contract states.
 *
 * <p>Assumptions: the bean is exercised directly rather than through a running context, because every
 * member asserted here is set by that method from a constant on the class. A context would add a
 * datasource, an issuer and a token decoder to reach the same objects.</p>
 *
 * <p>Assumptions: two members of this document -- the summary on the information block and the SPDX
 * identifier on the licence -- exist only in OpenAPI 3.1, and whether they reach a reader is a property
 * of the SERIALISER rather than of the document. Both serialisers are run here against the one document
 * so that what each does with those members is recorded rather than assumed. The mechanism by which the
 * two version carriers differ under cloning is measured once, in account-service's
 * {@code config/OpenApiDocumentTest.java}, against the same library; it is not restated here.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception tag.</p>
 */
class OpenApiDocumentTest {

    /** The committed contract this module publishes, read to compare declared versions. */
    private static final String CONTRACT_RESOURCE = "/openapi/card-api.yaml";

    /**
     * Confirms the document declares 3.1 in both of the places that carry a version.
     *
     * <p>Assumptions: the flag and the version string are asserted separately because they are set
     * separately and either can be left at the model's default independently of the other. The default
     * of both is a 3.0 value, so an assertion that only compared the two to each other would pass on a
     * document that declared 3.0 twice.</p>
     *
     * <p>Refactoring Rationale: this case exists because the document was constructed without either,
     * while carrying two members that exist only in 3.1. Nothing failed -- the configured serialiser
     * emits both regardless -- but the served document declared 3.0.1 while the committed contract
     * declares 3.1.1, and no assertion anywhere compared the two.</p>
     */
    @Test
    @DisplayName("the published document declares the specification version its members come from")
    void publishedDocumentDeclaresTheSpecificationVersionItsMembersComeFrom() {
        OpenAPI published = new OpenApiConfig().cardServiceOpenApi();

        assertThat(published.getSpecVersion())
                .as("a 3.1 document must carry the 3.1 flag, not the model's 3.0 default")
                .isEqualTo(SpecVersion.V31);
        assertThat(published.getOpenapi())
                .as("the emitted version string must equal the committed contract's")
                .isEqualTo(String.valueOf(contract().get("openapi")));
        assertThat(published.getOpenapi()).startsWith("3.1.");
    }

    /**
     * Confirms the two 3.1-only members are present and that emitting them depends on the serialiser.
     *
     * <p>Assumptions: the mechanism is asserted rather than only the outcome, because the mechanism is
     * what a maintainer needs. The 3.1 serialiser emits both members; the 3.0 serialiser silently omits
     * both from the very same document. That is why the configuration key selecting the serialiser and
     * the version this document declares have to agree, and why neither alone is sufficient.</p>
     */
    @Test
    @DisplayName("the two 3.1-only members survive the 3.1 serialiser and are dropped by the 3.0 one")
    void theTwoSpecificMembersSurviveTheMatchingSerialiserOnly() {
        OpenAPI published = new OpenApiConfig().cardServiceOpenApi();

        assertThat(published.getInfo().getSummary()).isNotBlank();
        assertThat(published.getInfo().getLicense().getIdentifier()).isNotBlank();

        assertThat(Json31.pretty(published))
                .as("the configured serialiser must emit both 3.1-only members")
                .contains("\"summary\"")
                .contains("\"identifier\"");
        assertThat(Json.pretty(published))
                .as("the 3.0 serialiser drops both, which is the loss this document guards against")
                .doesNotContain("\"summary\"")
                .doesNotContain("\"identifier\"");
    }

    /**
     * Confirms the bearer requirement names its scheme and carries no scope in its list.
     *
     * <p>Assumptions: an empty list is asserted as an empty LIST rather than as absent, because the
     * requirement must still name the scheme -- one that named nothing would describe an
     * unauthenticated API. Under 3.1 a populated list on a non-oauth2 scheme is permitted and denotes
     * role names, so the empty list is a deliberate choice rather than the only legal value: those
     * names are defined as not exchanged in-band, and a populated list reads as an OAuth scope to every
     * 3.0-era reader and tool.</p>
     */
    @Test
    @DisplayName("the bearer requirement names one scheme with an empty list")
    void bearerRequirementNamesOneSchemeWithAnEmptyList() {
        OpenAPI published = new OpenApiConfig().cardServiceOpenApi();

        assertThat(published.getSecurity()).hasSize(1);
        Map<String, java.util.List<String>> requirement = published.getSecurity().get(0);
        assertThat(requirement).hasSize(1);
        assertThat(requirement.values().iterator().next())
                .as("a scope on an HTTP-scheme requirement is not this fleet's convention")
                .isEmpty();
        assertThat(published.getComponents().getSecuritySchemes())
                .containsOnlyKeys(requirement.keySet().iterator().next());
    }

    /**
     * Reads and parses the committed contract off the test class path.
     *
     * @return the parsed contract; never {@code null}
     * @throws IllegalStateException if the contract is absent or is not a mapping, either of which
     *     would mean the assertions above were comparing the bean with nothing
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() {
        try (InputStream stream = OpenApiDocumentTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(CONTRACT_RESOURCE + " is absent from the class path");
            }
            Object parsed = new Yaml().load(stream);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException(CONTRACT_RESOURCE + " did not parse to a mapping");
            }
            return (Map<String, Object>) parsed;
        } catch (IOException failure) {
            throw new IllegalStateException("could not read " + CONTRACT_RESOURCE, failure);
        }
    }
}
