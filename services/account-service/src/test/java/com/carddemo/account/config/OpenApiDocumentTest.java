package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.security.InternalServiceToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that the document {@link OpenApiConfig} publishes declares the specification version it
 * carries members from, and that its security requirement has the shape the committed contract has.
 *
 * <p>Assumptions: the bean is exercised directly rather than through a running context, because every
 * member asserted here is set by that method from a constant on the class. A context would add a
 * datasource, an issuer and a token decoder to reach the same three objects.</p>
 *
 * <p>Assumptions: two of the members this document carries -- the summary on the information block and
 * the SPDX identifier on the licence -- exist only in OpenAPI 3.1, and whether a serialiser emits them
 * is a property of the SERIALISER rather than of the document. Both serialisers are therefore run here
 * against the one document, so what each does with those members is recorded rather than assumed.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception tag.</p>
 */
class OpenApiDocumentTest {

    /** The committed contract this module publishes, read to compare declared versions. */
    private static final String CONTRACT_RESOURCE = "/openapi/account-api.yaml";

    /** The scheme name both the generated document and the committed contract declare. */
    private static final String SCHEME_NAME = "internalServiceToken";

    /** The vendor-extension member that carries the default required scope on the scheme. */
    private static final String SCOPE_EXTENSION = "x-carddemo-required-scope";

    /**
     * The vendor-extension member that carries the customer-record scope on the scheme.
     *
     * <p>Assumptions: asserted separately from the member above rather than folded into one case, because
     * the property worth holding is that the two are DIFFERENT values -- a document publishing the same
     * scope twice under two names would describe the separation this test exists to pin while granting
     * nothing by it.</p>
     */
    private static final String CUSTOMER_MASTER_SCOPE_EXTENSION =
            "x-carddemo-customer-master-scope";

    /**
     * The operation identifiers the committed contract must gate on the customer-record scope.
     *
     * <p>Assumptions: named as a closed set rather than derived from the tag, because deriving it would
     * make the assertion agree with whatever the document said. These two are the operations that
     * disclose a whole customer record, and the finding this case answers was that they shared one scope
     * with the decision-path reads.</p>
     */
    private static final List<String> CUSTOMER_RECORD_OPERATIONS =
            List.of("listCustomers", "readCustomerRecord");

    /**
     * Confirms the document declares 3.1 in both places that carry a version.
     *
     * <p>Assumptions: the flag and the version string are asserted separately because they are set
     * separately and either can be left at the model's default independently of the other. The default
     * of both is a 3.0 value, so an assertion that only compared the two to each other would pass on a
     * document that declared 3.0 twice.</p>
     *
     * <p>Refactoring Rationale: this case exists because the document was constructed without the
     * specification flag while carrying two members that exist only in 3.1. Nothing failed -- the
     * configured serialiser emitted both members -- but the served document declared 3.0.1 while the
     * committed contract declared 3.1.0, and no assertion anywhere compared the two.</p>
     */
    @Test
    @DisplayName("the published document declares the specification version its members come from")
    void publishedDocumentDeclaresTheSpecificationVersionItsMembersComeFrom() {
        OpenAPI published = new OpenApiConfig().accountServiceOpenApi();

        assertThat(published.getSpecVersion())
                .as("a 3.1 document must carry the 3.1 flag, not the model's 3.0 default")
                .isEqualTo(SpecVersion.V31);
        assertThat(published.getOpenapi())
                .as("the emitted version string must equal the committed contract's")
                .isEqualTo(contractVersion());
        assertThat(published.getOpenapi()).startsWith("3.1.");
    }

    /**
     * Confirms the two 3.1-only members are present and that emitting them depends on the serialiser.
     *
     * <p>Assumptions: this case asserts the mechanism rather than only the outcome, because the
     * mechanism is what a maintainer needs to know. The 3.1 serialiser emits both members; the 3.0
     * serialiser silently omits both from the very same document. That is why the configuration key
     * selecting the serialiser and the flag set on the document have to agree, and why neither alone is
     * sufficient.</p>
     */
    @Test
    @DisplayName("the two 3.1-only members survive the 3.1 serialiser and are dropped by the 3.0 one")
    void theTwoSpecificMembersSurviveTheMatchingSerialiserOnly() {
        OpenAPI published = new OpenApiConfig().accountServiceOpenApi();

        assertThat(published.getInfo().getSummary()).isNotBlank();
        assertThat(published.getInfo().getLicense().getIdentifier()).isNotBlank();

        String asThirtyOne = Json31.pretty(published);
        assertThat(asThirtyOne)
                .as("the configured serialiser must emit both 3.1-only members")
                .contains("\"summary\"")
                .contains("\"identifier\"");

        String asThirty = Json.pretty(published);
        assertThat(asThirty)
                .as("the 3.0 serialiser drops both, which is the loss this document guards against")
                .doesNotContain("\"summary\"")
                .doesNotContain("\"identifier\"");
    }

    /**
     * Records which of the two version carriers survives being cloned, and which does not.
     *
     * <p>Assumptions: the publishing library assembles the served document by cloning this one through
     * an object mapper, so what a clone does to each carrier is what assembly does to it. The two
     * carriers do NOT behave alike, and this case exists to pin the difference rather than to assert a
     * preference: the version STRING survives every clone path, while the specification FLAG survives
     * swagger's own 3.1 mapper and is reset to the 3.0 default by a plain one.</p>
     *
     * <p>Refactoring Rationale: this case was first written asserting that a plain clone preserves the
     * flag, and it FAILED -- which is the whole reason the configuration sets the version string
     * explicitly rather than setting the flag and expecting the string to follow from it. Had the case
     * only asserted the string, the configuration would have kept a rationale that named the flag as
     * sufficient, and a later reader trusting that rationale would have removed the string.</p>
     *
     * <p>Assumptions: the 3.1-only members are asserted to survive BOTH clones, because they are
     * ordinary properties of the model and are never the thing at risk here. What decides whether they
     * reach a reader is which serialiser writes the document, which the case above covers.</p>
     *
     * @throws Exception if a round trip through either mapper fails, which would itself be the finding
     */
    @Test
    @DisplayName("the version string survives a clone where the specification flag does not")
    void versionStringSurvivesACloneWhereTheSpecificationFlagDoesNot() throws Exception {
        OpenAPI published = new OpenApiConfig().accountServiceOpenApi();

        ObjectMapper plain = new ObjectMapper();
        OpenAPI plainClone = plain.readValue(plain.writeValueAsBytes(published), OpenAPI.class);

        assertThat(plainClone.getOpenapi())
                .as("the version string is the carrier that survives assembly, so it must be set")
                .isEqualTo(published.getOpenapi());
        assertThat(plainClone.getSpecVersion())
                .as("a plain mapper resets the flag, which is why the string cannot be left implied")
                .isEqualTo(SpecVersion.V30);
        assertThat(plainClone.getInfo().getSummary()).isEqualTo(published.getInfo().getSummary());

        OpenAPI swaggerClone = Json31.mapper()
                .readValue(Json31.mapper().writeValueAsBytes(published), OpenAPI.class);

        assertThat(swaggerClone.getSpecVersion())
                .as("swagger's own 3.1 mapper does preserve the flag")
                .isEqualTo(SpecVersion.V31);
        assertThat(swaggerClone.getOpenapi()).isEqualTo(published.getOpenapi());
        assertThat(swaggerClone.getInfo().getLicense().getIdentifier())
                .isEqualTo(published.getInfo().getLicense().getIdentifier());
    }

    /**
     * Confirms the security requirement names the scheme and carries no scope in its list.
     *
     * <p>Assumptions: an empty list is asserted as an empty LIST rather than as absent, because the
     * requirement must still name the scheme -- a requirement that named nothing would describe an
     * unauthenticated API. Under 3.1 a populated list on a non-oauth2 scheme is permitted and denotes
     * role names, so this assertion pins a deliberate choice rather than a rule: the names are defined
     * as not exchanged in-band, so the list is documentation either way, and a populated one reads as an
     * OAuth scope to every 3.0-era reader and tool.</p>
     */
    @Test
    @DisplayName("the bearer requirement names the scheme with an empty list")
    void bearerRequirementNamesTheSchemeWithAnEmptyList() {
        OpenAPI published = new OpenApiConfig().accountServiceOpenApi();

        assertThat(published.getSecurity()).hasSize(1);
        assertThat(published.getSecurity().get(0)).containsOnlyKeys(SCHEME_NAME);
        assertThat(published.getSecurity().get(0).get(SCHEME_NAME))
                .as("the scope belongs on the scheme, not in this list")
                .isEmpty();
    }

    /**
     * Confirms the scope the filter chain enforces is published, and published from that same constant.
     *
     * <p>Assumptions: the assertion compares the extension's value to
     * the four scope constants the chain enforces rather than to literals, because the
     * property worth holding is that the document and the enforcing chain cannot disagree. Literals
     * here would keep passing while the enforced authorities changed.</p>
     *
     * <p>Refactoring Rationale: this case asserted ONE scope, and the extension now carries four. The
     * chain requires one authority per operation family -- the cross-reference, account and customer
     * decision reads each take their own, and the two whole-customer-record reads take the
     * customer-master scope -- so a document naming fewer would state that one token reaches every
     * internal operation, which is the escalation the split removed. Asserting all four in the order
     * the bean composes them is what keeps a later narrowing of the chain from leaving the document
     * behind.</p>
     */
    @Test
    @DisplayName("the scheme publishes every scope the enforcing chain demands")
    void schemePublishesTheScopeTheEnforcingChainDemands() {
        OpenAPI published = new OpenApiConfig().accountServiceOpenApi();
        SecurityScheme scheme = published.getComponents().getSecuritySchemes().get(SCHEME_NAME);

        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getExtensions())
                .as("dropping the scope from the requirement is only safe if it is published elsewhere")
                .containsEntry(SCOPE_EXTENSION,
                        List.of(InternalServiceToken.SCOPE_CARD_XREF_READ,
                                InternalServiceToken.SCOPE_ACCOUNT_READ,
                                InternalServiceToken.SCOPE_CUSTOMER_READ,
                                InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ));
    }

    /**
     * Confirms the committed contract carries the same requirement shape and the same published scope.
     *
     * <p>Assumptions: both halves are asserted because they are maintained separately -- this module
     * generates one and ships the other -- and a consumer reads the shipped file. A change to the bean
     * alone would leave the file describing a requirement no caller has to satisfy in that form.</p>
     */
    @Test
    @DisplayName("the committed contract agrees on the empty list and on the published scopes")
    void committedContractAgreesOnTheEmptyListAndOnThePublishedScope() {
        Map<String, Object> contract = contract();
        Map<String, Object> schemes = nested(nested(contract, "components"), "securitySchemes");
        Map<String, Object> scheme = nested(schemes, SCHEME_NAME);

        assertThat(scheme).containsEntry("type", "http");
        // WHY : Refactoring Rationale: the committed file must carry ALL FOUR scopes, in the same order the
        //       bean composes them. The two are maintained separately -- this module generates one
        //       document and ships another -- so asserting the shipped file against the same two
        //       constants is what keeps a consumer reading the file from being told that one credential
        //       reaches every internal operation.
        assertThat(scheme)
                .containsEntry(SCOPE_EXTENSION,
                        List.of(InternalServiceToken.SCOPE_CARD_XREF_READ,
                                InternalServiceToken.SCOPE_ACCOUNT_READ,
                                InternalServiceToken.SCOPE_CUSTOMER_READ,
                                InternalServiceToken.SCOPE_CUSTOMER_MASTER_READ));

        List<?> requirements = operationRequirements(contract);
        assertThat(requirements)
                .as("the contract must state a requirement on the operations it protects")
                .isNotEmpty();
        for (Object requirement : requirements) {
            Map<String, Object> entry = firstRequirement(requirement);
            assertThat(entry).containsOnlyKeys(SCHEME_NAME);
            assertThat((List<?>) entry.get(SCHEME_NAME))
                    .as("every operation-level list must be empty, matching the generated document")
                    .isEmpty();
        }
    }

    /**
     * Narrows one parsed security-requirement entry to a typed mapping.
     *
     * @param requirement the parsed {@code security} value of one operation, which the specification
     *     defines as a list of requirement objects
     * @return the first requirement object in that list; never {@code null}
     * @throws IllegalStateException if the list is empty or its first element is not a mapping, either
     *     of which would mean the operation declared a requirement carrying nothing
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRequirement(Object requirement) {
        List<?> requirements = (List<?>) requirement;
        if (requirements.isEmpty() || !(requirements.get(0) instanceof Map)) {
            throw new IllegalStateException("an operation declared a security requirement with no entry");
        }
        return (Map<String, Object>) requirements.get(0);
    }

    /**
     * Collects every operation-level security requirement declared in the committed contract.
     *
     * @param contract the parsed contract document
     * @return one entry per operation that declares a requirement; never {@code null}
     */
    private static List<?> operationRequirements(Map<String, Object> contract) {
        // WHY : Refactoring Rationale: the stream is filtered to the operations THIS scheme protects,
        //       and it was not. The contract publishes two credentials -- this one on the internal
        //       context operations and bearerAuth on the operations a browser reaches -- and it says so
        //       of itself, recording that they are different credentials and that neither substitutes
        //       for the other. Scanning every operation therefore asserted that a public operation
        //       required an internal service token, which is not what the document says and not what
        //       the module means; the assertion failed on the contract being complete rather than on
        //       it being wrong.
        // WHY : Assumptions: the filter is on the requirement naming this scheme rather than on a list
        //       of path names, so an internal operation added later is covered without this test being
        //       edited, and a public one is not swept in. The caller still asserts the result is
        //       NON-EMPTY, so a contract that stopped protecting anything with this scheme fails here
        //       rather than passing vacuously.
        return nested(contract, "paths").values().stream()
                .map(methods -> (Map<?, ?>) methods)
                .flatMap(methods -> methods.values().stream())
                .filter(Map.class::isInstance)
                .map(operation -> ((Map<?, ?>) operation).get("security"))
                .filter(List.class::isInstance)
                .filter(requirement -> ((List<?>) requirement).stream()
                        .filter(Map.class::isInstance)
                        .anyMatch(entry -> ((Map<?, ?>) entry).containsKey(SCHEME_NAME)))
                .toList();
    }

    /**
     * Reads the specification version the committed contract declares.
     *
     * @return the value of the contract's {@code openapi} member; never {@code null}
     */
    private static String contractVersion() {
        return String.valueOf(contract().get("openapi"));
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
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not read " + CONTRACT_RESOURCE, failure);
        }
    }

    /**
     * Descends one level into a parsed mapping.
     *
     * @param document the mapping to read
     * @param key the member to descend into
     * @return the nested mapping; never {@code null}
     * @throws IllegalStateException if the member is absent or is not itself a mapping
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> document, String key) {
        Object value = document.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalStateException(key + " is absent or is not a mapping");
        }
        return (Map<String, Object>) value;
    }
}
