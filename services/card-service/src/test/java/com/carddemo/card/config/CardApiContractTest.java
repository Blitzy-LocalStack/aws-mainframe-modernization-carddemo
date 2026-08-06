package com.carddemo.card.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.CorrelationIdFilter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the published card contract and this module's enforced security rules to each other.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review that prompted this class found four independent disagreements
 * between the card contract and the things written against it, and every one of them was invisible to
 * both the Java build and the TypeScript build because each crossed the wire as a string: the
 * administrative authority was prose and a tag name with nothing enforcing it; the path selector was a
 * primary account number where the browser client and the edge route table both carried an opaque
 * token; the paging inputs were named after the response's row identities rather than its cursors; and
 * the correlation identity was bounded at three different widths across three contracts and
 * illustrated with examples none of them admitted. A disagreement that no build can see needs a test
 * that can, and this is it.</p>
 *
 * <p>Assumptions: the contract is read from the CLASSPATH rather than from a source path, so this test
 * asserts against the artifact the service actually publishes. Reading
 * {@code src/main/resources/openapi/card-api.yaml} through the file system would pass while the
 * packaged resource was stale or absent.</p>
 *
 * <p>Alternatives Considered: a running application context with a mock request per route, which is the
 * stronger form and is what a negative authorization test would need. It is not available at this
 * checkpoint - this module has no application class yet, so there is no context to stand up - and
 * waiting for one would have left the rules unverified during exactly the interval in which they were
 * introduced. This test therefore asserts the rule TABLE that
 * {@link SecurityConfig#filterChain} builds its matchers from, which is the same value, and the
 * runtime assertion is added when the application class lands.</p>
 */
class CardApiContractTest {

    /** Classpath location of the contract this module publishes. */
    private static final String CONTRACT_RESOURCE = "/openapi/card-api.yaml";

    /** The extension field naming the authority an operation requires. */
    private static final String AUTHORITY_FIELD = "x-required-authority";

    /** The HTTP methods an operation may be declared under, so that a path item's own keys are skipped. */
    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /**
     * A specimen sixteen-digit primary account number, used to make a path template concrete.
     *
     * <p>Assumptions: this is a test-local literal and not a credential. It is the reserved test
     * prefix followed by a fixed tail, so it identifies no real card.</p>
     */
    private static final String SAMPLE_ACCOUNT_NUMBER = "4111111111110011";

    /**
     * The masked rendering of {@link #SAMPLE_ACCOUNT_NUMBER}, used as a value a path must refuse.
     *
     * <p>Assumptions: a masked rendering is the value a client is most likely to send by mistake,
     * because it is the only card-number-shaped value any response of this contract hands out, and it
     * identifies no row.</p>
     */
    private static final String SAMPLE_MASKED_NUMBER = "************0011";

    /**
     * The exact set of path templates and methods this contract is obliged to publish.
     *
     * <p>Assumptions: this is a CLOSED set, asserted as an equality rather than a containment, which
     * is what makes it catch an operation being ADDED as well as one going missing. The four
     * obligations are the three read-or-write actions of the baseline's three card programs --
     * app/cbl/COCRDLIC.cbl for the list, COCRDSLC.cbl for the detail and COCRDUPC.cbl for the update
     * -- plus the administrative reading of the detail, which is the one operation that renders a full
     * account number and therefore sits on its own prefix.</p>
     */
    private static final List<String> CONTRACTED_OPERATIONS = List.of(
            "get /api/v1/cards",
            "get /api/v1/cards/{cardNumber}",
            "put /api/v1/cards/{cardNumber}",
            "get /api/v1/admin/cards/{cardNumber}");

    /** The parsed contract, loaded once per test instance. */
    private final Map<String, Object> contract = loadContract();

    /**
     * Reads and parses the published contract from the classpath.
     *
     * @return the whole document as nested maps and lists; never {@code null}
     * @throws IllegalStateException if the resource is absent from the classpath, which would mean the
     *     service publishes no contract at all, or if it cannot be parsed
     */
    private static Map<String, Object> loadContract() {
        try (InputStream resource = CardApiContractTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
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
        } catch (java.io.IOException failure) {
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
     * @throws IllegalStateException if the key is absent or does not hold a mapping, because a
     *     structural assumption of this test would otherwise fail as a class cast far from its cause
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
     * Collects every operation in the document, keyed by its path and method.
     *
     * @return each operation with the path template it sits on, in document order; never empty
     */
    private Map<String, Map<String, Object>> operationsByPathAndMethod() {
        Map<String, Map<String, Object>> operations = new LinkedHashMap<>();
        Map<String, Object> paths = mapping(contract, "paths");
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            Map<String, Object> pathItem = mapping(paths, pathEntry.getKey());
            for (String method : HTTP_METHODS) {
                if (pathItem.containsKey(method)) {
                    operations.put(
                            method + " " + pathEntry.getKey(), mapping(pathItem, method));
                }
            }
        }
        return operations;
    }

    /**
     * Substitutes a concrete card number for every template variable in a path.
     *
     * <p>Assumptions: every template variable in this contract is the card number, so one substitution
     * value serves all of them. A rule pattern matches on segment structure rather than on the value in
     * a segment, so the substituted value only has to be a single non-empty segment.</p>
     *
     * @param pathTemplate the published path template
     * @return a concrete request path the security rules can be evaluated against
     */
    private static String concretePath(String pathTemplate) {
        return pathTemplate.replaceAll("\\{[^}]+}", SAMPLE_ACCOUNT_NUMBER);
    }

    /**
     * Asserts that the authority model publishes exactly the two group names the shared converter
     * recognises, and that every operation declares one of them.
     */
    @Test
    @DisplayName("every operation declares a required authority drawn from the published model")
    void everyOperationDeclaresAPublishedAuthority() {
        @SuppressWarnings("unchecked")
        List<String> admitted =
                (List<String>) mapping(contract, "x-authority-model").get("values");
        assertThat(admitted)
                .as("the authority model must publish the values operations may declare")
                .containsExactlyInAnyOrder(
                        JwtRoleConverter.USER_AUTHORITY, JwtRoleConverter.ADMIN_AUTHORITY);

        Map<String, Map<String, Object>> operations = operationsByPathAndMethod();
        assertThat(operations).as("the contract must declare at least one operation").isNotEmpty();
        operations.forEach((name, operation) ->
                assertThat(operation.get(AUTHORITY_FIELD))
                        .as("operation %s must declare %s", name, AUTHORITY_FIELD)
                        .isIn(admitted.toArray()));
    }

    // WHY : Assumptions: this is the assertion the whole class exists for. It compares two
    //       independently authored statements of one rule - the extension field on each operation and
    //       the rule table SecurityConfig builds its matchers from - so neither can be edited alone.
    //       Comparing the field against a literal instead would only restate the contract.
    /**
     * Asserts that the authority each operation publishes is the authority the filter chain enforces
     * for that operation's path, comparing two independently authored statements of one rule.
     */
    @Test
    @DisplayName("each operation's declared authority is the one SecurityConfig enforces for its path")
    void declaredAuthorityMatchesEnforcedAuthority() {
        List<String> mismatches = new ArrayList<>();
        operationsByPathAndMethod().forEach((name, operation) -> {
            String pathTemplate = name.substring(name.indexOf(' ') + 1);
            String declared = String.valueOf(operation.get(AUTHORITY_FIELD));
            String enforced = SecurityConfig.requiredAuthorityFor(concretePath(pathTemplate));
            String effective = enforced == null ? JwtRoleConverter.USER_AUTHORITY : enforced;
            if (!declared.equals(effective)) {
                mismatches.add(name + ": contract says " + declared + ", chain enforces " + effective);
            }
        });
        assertThat(mismatches)
                .as("the published authority and the enforced authority must agree per operation")
                .isEmpty();
    }

    /**
     * Asserts that exactly one operation is administrative and that it is the one returning a full
     * account number, so the authority is neither missing from it nor spread onto its neighbours.
     */
    @Test
    @DisplayName("the administrative path is the only one requiring the administrator authority")
    void onlyTheAdministrativePathRequiresTheAdministratorAuthority() {
        List<String> administrative = new ArrayList<>();
        operationsByPathAndMethod().forEach((name, operation) -> {
            if (JwtRoleConverter.ADMIN_AUTHORITY.equals(operation.get(AUTHORITY_FIELD))) {
                administrative.add(name);
            }
        });
        assertThat(administrative)
                .as("exactly one operation returns a full account number, so exactly one is"
                        + " administrative")
                .containsExactly("get /api/v1/admin/cards/{cardNumber}");
        assertThat(SecurityConfig.requiredAuthorityFor(
                        "/api/v1/admin/cards/" + SAMPLE_ACCOUNT_NUMBER))
                .isEqualTo(JwtRoleConverter.ADMIN_AUTHORITY);
        assertThat(SecurityConfig.requiredAuthorityFor("/api/v1/cards/" + SAMPLE_ACCOUNT_NUMBER))
                .as("the ordinary read must NOT require the administrator authority")
                .isEqualTo(JwtRoleConverter.USER_AUTHORITY);
    }

    // WHY : Refactoring Rationale: this assertion once existed because the administrative operation was
    //       a suffix INSIDE the card subtree, so the subtree rule also matched it and only the table's
    //       order kept an ordinary user out. The administrative path now carries its own prefix, so the
    //       shadowing is structurally impossible; the assertion is kept and STRENGTHENED to state that,
    //       because an ordering assertion protects a table as it stands while this one protects the
    //       property that made the ordering unnecessary.
    /**
     * Asserts that no card-subtree pattern can match the administrative path, and that the
     * administrative rule is still evaluated first.
     */
    @Test
    @DisplayName("no card-subtree rule can match the administrative path")
    void noCardSubtreeRuleCanMatchTheAdministrativePath() {
        assertThat(SecurityConfig.CARD_SUBTREE_PATH_PATTERN)
                .as("the subtree pattern must be rooted at the card collection")
                .isEqualTo("/api/v1/cards/**");
        assertThat(SecurityConfig.ADMIN_CARD_PATH_PATTERN)
                .as("the administrative pattern must sit outside that subtree, so no ordering of the"
                        + " rule table can grant an ordinary user the full-number reading")
                .doesNotStartWith("/api/v1/cards");

        List<String> patterns = SecurityConfig.authorityRules().stream()
                .map(SecurityConfig.AuthorityRule::pathPattern)
                .toList();
        assertThat(patterns.indexOf(SecurityConfig.ADMIN_CARD_PATH_PATTERN))
                .as("most-specific-first is the reading this table invites, so it is kept")
                .isLessThan(patterns.indexOf(SecurityConfig.CARD_SUBTREE_PATH_PATTERN));
    }

    // WHY : Refactoring Rationale: the obligation asserted here is the SET of published operations, and
    //       an intermediate revision of the contract drifted to five operations on three paths keyed by
    //       an opaque token, which left the two card-number paths and the administrative path
    //       unpublished while every individual operation still read correctly. A per-operation
    //       assertion cannot see that; only an equality against the closed set can.
    /**
     * Asserts that the contract publishes exactly the four contracted operations and no others.
     */
    @Test
    @DisplayName("the contract publishes exactly the four contracted operations")
    void theContractPublishesExactlyTheFourContractedOperations() {
        assertThat(operationsByPathAndMethod().keySet())
                .as("the published operation set is closed: an addition is as much a divergence as an"
                        + " omission, because each one is a route a client may be typed against")
                .containsExactlyInAnyOrderElementsOf(CONTRACTED_OPERATIONS);
    }

    /**
     * Asserts that a single card is addressed by its sixteen-digit number and that a masked rendering
     * is refused by the parameter's own shape before any handler runs.
     */
    @Test
    @DisplayName("a single card is addressed by its sixteen-digit number")
    void aSingleCardIsAddressedByItsNumber() {
        Map<String, Object> parameters = mapping(mapping(contract, "components"), "parameters");
        Map<String, Object> pathParameter = mapping(parameters, "CardNumberPath");
        assertThat(pathParameter.get("name")).isEqualTo("cardNumber");
        assertThat(pathParameter.get("in")).isEqualTo("path");
        assertThat(pathParameter.get("required")).isEqualTo(true);

        Map<String, Object> schema = mapping(pathParameter, "schema");
        assertThat(schema.get("minLength")).isEqualTo(16);
        assertThat(schema.get("maxLength")).isEqualTo(16);

        Pattern declared = Pattern.compile(String.valueOf(schema.get("pattern")));
        assertThat(declared.matcher(SAMPLE_ACCOUNT_NUMBER).matches())
                .as("the primary key of card.cards is what addresses a card, so it must be accepted")
                .isTrue();
        assertThat(declared.matcher(SAMPLE_MASKED_NUMBER).matches())
                .as("a masked rendering identifies no row, and it is the value a client is most likely"
                        + " to send by mistake, so the shape check must refuse it")
                .isFalse();
    }

    // WHY : Refactoring Rationale: a card number in a request line is written into access logs, browser
    //       history and referrer headers, and an intermediate revision of the contract answered that by
    //       removing the number from the URL entirely -- which cost the contract its two card-number
    //       paths and added a POST whose only purpose was to accept in a body what the URL could not
    //       carry. The exposure is answered instead by REDACTION, and this assertion is what holds the
    //       redaction to the paths the contract actually publishes: it builds a concrete URL from every
    //       published template and requires that the shared masker leaves no full number in it.
    /**
     * Asserts that the shared masker redacts a card number embedded in any published card path.
     */
    @Test
    @DisplayName("the shared masker redacts a card number in every published card path")
    void theSharedMaskerRedactsACardNumberInEveryPublishedPath() {
        List<String> unredacted = new ArrayList<>();
        for (String template : mapping(contract, "paths").keySet()) {
            String concrete = template.replace("{cardNumber}", SAMPLE_ACCOUNT_NUMBER);
            if (CardNumberMasker.maskEmbeddedCardNumbers(concrete).contains(SAMPLE_ACCOUNT_NUMBER)) {
                unredacted.add(concrete);
            }
        }
        assertThat(unredacted)
                .as("a path this contract publishes reaches the operational record through the shared"
                        + " advice, so none of them may still carry a full account number afterwards")
                .isEmpty();
    }

    /**
     * Asserts that the page envelope declares and requires exactly the four members the shared
     * response type carries, so no generated client receives an accessor for a member no service
     * emits and no strict client rejects a valid response for a member no service sends.
     *
     * <p>Assumptions: the four are read from the shared type rather than restated as a literal list
     * where the type can be reached, because the whole defect this asserts against was a contract that
     * named members the type does not declare.</p>
     */
    @Test
    @DisplayName("the page envelope declares and requires exactly the shared envelope's four members")
    void pageEnvelopeDeclaresExactlyTheSharedEnvelopeMembers() {
        Map<String, Object> page = mapping(mapping(mapping(contract, "components"), "schemas"),
                "CardPage");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) page.get("required");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) page.get("properties");
        assertThat(required)
                .as("every member the shared envelope always emits must be required")
                .containsExactlyInAnyOrder("items", "firstKey", "lastKey", "hasNext");
        assertThat(properties.keySet())
                .as("the required list and the declared properties must be the same set")
                .containsExactlyInAnyOrderElementsOf(required);
    }

    // WHY : Assumptions: the paging inputs are asserted by NAME because the defect they close was a
    //       naming collision: two request parameters named after the response's row identities, which
    //       are null on exactly the page whose cursors are not.
    /**
     * Asserts that paging is expressed as one cursor parameter and a lower-case direction, and that no
     * request parameter is named after a response row identity.
     */
    @Test
    @DisplayName("paging is one cursor plus a lower-case direction, and never a row identity")
    void pagingInputsAreOneCursorAndALowerCaseDirection() {
        Map<String, Object> parameters = mapping(mapping(contract, "components"), "parameters");
        assertThat(mapping(parameters, "Cursor").get("name")).isEqualTo("cursor");
        assertThat(mapping(parameters, "PagingDirection").get("name")).isEqualTo("direction");
        assertThat(parameters.keySet())
                .as("no request parameter may be named after a row identity")
                .doesNotContain("ForwardCursor", "BackwardCursor");

        Map<String, Object> direction =
                mapping(mapping(mapping(contract, "components"), "schemas"), "PageDirection");
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) direction.get("enum");
        assertThat(values)
                .as("the direction vocabulary is shared with the browser client and the sibling"
                        + " contracts")
                .containsExactly("next", "previous");
        assertThat(direction.get("default")).isEqualTo("next");
    }

    /**
     * Asserts that the published correlation bound, character set and header name are exactly what the
     * shared filter enforces, and that the value the browser client previously sent is refused.
     */
    @Test
    @DisplayName("the correlation identity is bounded exactly as the shared filter enforces")
    void correlationIdentityMatchesTheSharedFilter() {
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        Map<String, Object> correlationId = mapping(schemas, "CorrelationId");
        assertThat(correlationId.get("maxLength"))
                .as("the published bound must be the bound the shared filter enforces")
                .isEqualTo(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);

        Pattern declared = Pattern.compile(String.valueOf(correlationId.get("pattern")));
        String minted = "A".repeat(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);
        assertThat(declared.matcher(minted).matches())
                .as("an identity of the maximum length must be admitted")
                .isTrue();
        assertThat(declared.matcher("").matches())
                .as("the empty string is what an error body carries before any identity exists")
                .isTrue();
        assertThat(declared.matcher("8f1c0e42-3a55-4d21-9b7e-6c0f2a9d4471").matches())
                .as("a thirty-six-character identity is what the browser client used to send and"
                        + " what the filter refuses")
                .isFalse();

        Map<String, Object> header = mapping(mapping(contract, "components"), "parameters");
        Map<String, Object> requestHeader = mapping(header, "CorrelationIdHeader");
        assertThat(requestHeader.get("name")).isEqualTo(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(requestHeader.get("in")).isEqualTo("header");
    }

    // WHY : Assumptions: the disclosure boundary is asserted STRUCTURALLY - one named schema either
    //       has a member that can hold a full account number or it does not - because the defect it
    //       closes was one member meaning two things under a sentence that told them apart.
    /**
     * Asserts the disclosure boundary structurally: one named schema can carry a full account number
     * and the others are closed against it.
     */
    @Test
    @DisplayName("only the administrative detail schema can carry a full account number")
    void onlyTheAdministrativeDetailCarriesAFullAccountNumber() {
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");

        Map<String, Object> core = mapping(schemas, "CardDetailCore");
        @SuppressWarnings("unchecked")
        Map<String, Object> coreProperties = (Map<String, Object>) core.get("properties");
        assertThat(coreProperties.keySet())
                .as("the shared core must carry the masked rendering and no unmasked member")
                .contains("displayCardNumber")
                .doesNotContain("cardNumber");

        Map<String, Object> masked = mapping(schemas, "CardDetail");
        assertThat(masked.containsKey("properties"))
                .as("the masked shape adds no member of its own, so it can add no account number")
                .isFalse();
        assertThat(masked.get("unevaluatedProperties"))
                .as("the masked shape must be closed, or an account number could be added to it")
                .isEqualTo(false);

        Map<String, Object> administrative = mapping(schemas, "AdminCardDetail");
        @SuppressWarnings("unchecked")
        List<String> administrativeRequired = (List<String>) administrative.get("required");
        assertThat(administrativeRequired).containsExactly("cardNumber");
        assertThat(administrative.get("unevaluatedProperties")).isEqualTo(false);

        Map<String, Object> summary = mapping(schemas, "CardSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> summaryProperties = (Map<String, Object>) summary.get("properties");
        assertThat(summaryProperties.keySet())
                .as("a list row is the widest exposure in this context, so it carries the three values"
                        + " the baseline row displayed and nothing that addresses a card")
                .containsExactlyInAnyOrder("displayCardNumber", "accountId", "activeStatus");
    }

    /**
     * Asserts that an unenforceable rule is refused at construction rather than silently denying every
     * request it matches.
     */
    @Test
    @DisplayName("a rule naming an unrecognised authority cannot be constructed")
    void aRuleNamingAnUnrecognisedAuthorityIsRefused() {
        assertThatCode(() -> new SecurityConfig.AuthorityRule("/api/v1/cards", "carddemo-superuser"))
                .as("a rule naming an authority no token can carry would deny every request while"
                        + " reading as though it authorised some")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new SecurityConfig.AuthorityRule("  ", JwtRoleConverter.USER_AUTHORITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
