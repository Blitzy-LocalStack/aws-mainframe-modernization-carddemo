package com.carddemo.transaction.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.swagger.v3.oas.models.OpenAPI;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that the generated description refuses to name a contract a reader could not fetch, and that the
 * path this module's own profile declares is one it accepts.
 *
 * <p>Refactoring Rationale: this class exists because {@code springdoc.swagger-ui.url} was documented as
 * "must not be blank" and never checked. The absent-property case does fail on its own, since placeholder
 * resolution refuses it, but a property resolving to an empty string, to whitespace or to an absolute URL
 * resolved perfectly well and was written straight into the description text -- producing a document that
 * tells a reader which file governs while naming a file the reader cannot open. That reads as authoritative,
 * which is worse than carrying no pointer.</p>
 *
 * <p>Assumptions: the accepting case is taken from the shipped profile rather than from a literal, so a
 * check tightened past what the configuration declares fails here rather than at container start.</p>
 */
class OpenApiConfigTest {

    /** The base profile, packaged from {@code src/main/resources}. */
    private static final String BASE_PROFILE = "/application.yml";

    /**
     * Confirms the path this module's profile declares is accepted and reaches the description text.
     *
     * <p>Assumptions: the assertion looks for the configured path inside the description rather than only
     * checking that the bean was built, because naming the contract of record in the generated document is
     * the whole reason this class reads the property. A bean that built while dropping the pointer would
     * satisfy a construction-only assertion.</p>
     */
    @Test
    @DisplayName("the path the shipped profile declares is accepted and named in the description")
    void pathTheShippedProfileDeclaresIsAcceptedAndNamedInTheDescription() {
        String declared = configuredContractPath();

        OpenAPI description = new OpenApiConfig().transactionServiceOpenApi(declared);

        assertThat(description.getInfo().getDescription())
                .as("the generated description must name the contract of record it defers to")
                .contains(declared);
    }

    /**
     * The rejecting cases: values that resolve successfully and would each name an unfetchable contract.
     *
     * <p>Assumptions: every value below resolves without error, which is why none could be caught by
     * placeholder resolution. The absolute-URL cases are listed separately from the malformed ones because
     * they are the ones a reader would most reasonably think were fine: they are well-formed URLs, and they
     * are refused because pinning the contract to one host name breaks the moment the service is reached
     * through the load balancer, the gateway or a port-forward.</p>
     */
    @Test
    @DisplayName("a blank, absolute, nested or non-YAML contract path is refused")
    void blankAbsoluteNestedOrNonYamlContractPathIsRefused() {
        List<String> refused = List.of(
                "",
                "   ",
                "https://example.invalid/transaction-api.yaml",
                "//example.invalid/transaction-api.yaml",
                "transaction-api.yaml",
                "/openapi/transaction-api.yaml",
                "/transaction-api.json",
                "/transaction-api.yaml ");

        for (String path : refused) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("[%s] must not be written into the generated description", path)
                    .isThrownBy(() -> new OpenApiConfig().transactionServiceOpenApi(path))
                    .withMessageContaining("springdoc.swagger-ui.url");
        }

        assertThatExceptionOfType(IllegalStateException.class)
                .as("an unresolved property must be refused as clearly as a malformed one")
                .isThrownBy(() -> new OpenApiConfig().transactionServiceOpenApi(null))
                .withMessageContaining("springdoc.swagger-ui.url");
    }

    /**
     * Confirms the accepted shape is not narrower than it needs to be.
     *
     * <p>Assumptions: the {@code .yml} suffix and a hyphenated name are both legitimate spellings of a
     * served contract, so they are asserted here. A check that admitted only the exact current file name
     * would pass every assertion above while refusing a rename that was otherwise correct.</p>
     */
    @Test
    @DisplayName("both YAML suffixes and ordinary file-name characters are accepted")
    void bothYamlSuffixesAndOrdinaryFileNameCharactersAreAccepted() {
        for (String path : List.of("/transaction-api.yml", "/transaction_api.v2.yaml")) {
            assertThatCode(() -> new OpenApiConfig().transactionServiceOpenApi(path))
                    .as("[%s] is a legitimate served path and must be accepted", path)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Confirms the generated bean reports the 3.1 specification family the contract of record declares.
     *
     * <p>Assumptions: only the minor version is asserted. The generator re-derives the string it emits from
     * the model's specification version, so the patch digit belongs to it rather than to this class, and
     * asserting the full string here would pin a value this class does not own.</p>
     */
    @Test
    @DisplayName("the generated description reports the 3.1 specification family")
    void generatedDescriptionReportsThe31SpecificationFamily() {
        OpenAPI description = new OpenApiConfig().transactionServiceOpenApi(configuredContractPath());

        assertThat(description.getOpenapi())
                .as("a 3.0 document beside a 3.1 contract would drop the 3.1-only members set here")
                .startsWith("3.1");
    }

    /**
     * Reads the contract path this module's packaged profile declares.
     *
     * @return the declared path, never {@code null}
     * @throws IllegalStateException if the profile is absent from the test class path or declares no path,
     *     either of which would mean this class was asserting against nothing
     */
    @SuppressWarnings("unchecked")
    private String configuredContractPath() {
        try (InputStream document = OpenApiConfigTest.class.getResourceAsStream(BASE_PROFILE)) {
            if (document == null) {
                throw new IllegalStateException(BASE_PROFILE + " is not on the test class path");
            }
            Object current = new Yaml().load(document);
            for (String key : new String[] {"springdoc", "swagger-ui", "url"}) {
                if (!(current instanceof Map<?, ?> mapping) || !mapping.containsKey(key)) {
                    throw new IllegalStateException(
                            BASE_PROFILE + " does not declare springdoc.swagger-ui.url");
                }
                current = ((Map<String, Object>) mapping).get(key);
            }
            return String.valueOf(current);
        } catch (java.io.IOException problem) {
            throw new IllegalStateException(BASE_PROFILE + " could not be read", problem);
        }
    }
}
