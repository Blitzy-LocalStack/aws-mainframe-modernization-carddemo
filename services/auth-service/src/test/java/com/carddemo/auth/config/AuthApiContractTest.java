package com.carddemo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * Holds the published authentication contract and this module's enforced security rules to each other.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review that prompted this class found that five of this context's seven
 * operations - the ones that create, alter and delete the rows deciding who is an administrator - stated
 * their restriction in prose and in a tag name, with nothing anywhere enforcing or checking it. It also
 * found that the contract capped a password at eight characters, the width of a baseline record field
 * this migration deliberately does not carry forward, while the identity provider's configured policy
 * requires at least twelve - so every credential the pool would accept was one this contract refused,
 * and the operation was unusable rather than merely conservative. Neither defect was visible to a
 * compiler: an authority is a string in a document and a length is a number in a document.</p>
 *
 * <p>Assumptions: the contract is read from the CLASSPATH rather than from a source path, so this test
 * asserts against the artifact the service actually publishes. Reading the source tree through the file
 * system would pass while the packaged resource was stale or absent.</p>
 *
 * <p>Alternatives Considered: a running application context with a mock request per route, which is the
 * stronger form and is what a negative authorization test needs. It is not available at this checkpoint -
 * this module has no application class yet - and deferring the comparison until one exists would leave
 * the rules unverified during exactly the interval in which they were introduced. This test therefore
 * asserts the rule TABLE that {@link SecurityConfig#filterChain} builds its matchers from, which is the
 * same value.</p>
 */
class AuthApiContractTest {

    /** Classpath location of the contract this module publishes. */
    private static final String CONTRACT_RESOURCE = "/openapi/auth-api.yaml";

    /** The extension field naming the authority an operation requires. */
    private static final String AUTHORITY_FIELD = "x-required-authority";

    /** The published value marking an operation reachable without a token. */
    private static final String NO_AUTHORITY = "none";

    /**
     * The shortest password the identity provider can be configured to accept.
     *
     * <p>Assumptions: twelve is not chosen here. It is the floor the Cognito module's own input
     * validation enforces on {@code password_minimum_length} in
     * {@code infra/modules/cognito/variables.tf}, whose default is fourteen, so no environment can
     * configure a pool that accepts anything shorter. A contract admitting fewer characters than this
     * would refuse every credential the pool will accept.</p>
     */
    private static final int PROVIDER_PASSWORD_FLOOR = 12;

    /** The HTTP methods an operation may be declared under, so a path item's own keys are skipped. */
    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /** A concrete identifier of the published width, used to make a path template concrete. */
    private static final String SAMPLE_USER_ID = "USER0001";

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
        try (InputStream resource = AuthApiContractTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
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
     * Collects every operation in the document, keyed by its method and path.
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
                    operations.put(method + " " + pathEntry.getKey(), mapping(pathItem, method));
                }
            }
        }
        return operations;
    }

    /**
     * Substitutes a concrete identifier for every template variable in a path.
     *
     * @param pathTemplate the published path template
     * @return a concrete request path the security rules can be evaluated against
     */
    private static String concretePath(String pathTemplate) {
        return pathTemplate.replaceAll("\\{[^}]+}", SAMPLE_USER_ID);
    }

    /**
     * Asserts that the authority model publishes the open marker and the two group names the shared
     * converter recognises, and that every operation declares one of them.
     */
    @Test
    @DisplayName("every operation declares a required authority drawn from the published model")
    void everyOperationDeclaresAPublishedAuthority() {
        @SuppressWarnings("unchecked")
        List<String> admitted = (List<String>) mapping(contract, "x-authority-model").get("values");
        assertThat(admitted)
                .as("the model must publish the open marker and the two recognised group names")
                .containsExactlyInAnyOrder(
                        NO_AUTHORITY, JwtRoleConverter.USER_AUTHORITY, JwtRoleConverter.ADMIN_AUTHORITY);

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
    //       An operation declaring the open marker is checked against the chain's list of open paths
    //       rather than against its authority table, because the two answers mean different things:
    //       an unmatched path also reports no required authority, and it is NOT open.
    /**
     * Asserts that the authority each operation publishes is what the filter chain enforces for that
     * operation's path, and that each operation published as open is on the chain's open list.
     */
    @Test
    @DisplayName("each operation's declared authority is the one SecurityConfig enforces for its path")
    void declaredAuthorityMatchesEnforcedAuthority() {
        List<String> mismatches = new ArrayList<>();
        operationsByPathAndMethod().forEach((name, operation) -> {
            String pathTemplate = name.substring(name.indexOf(' ') + 1);
            String declared = String.valueOf(operation.get(AUTHORITY_FIELD));
            String path = concretePath(pathTemplate);
            if (NO_AUTHORITY.equals(declared)) {
                if (!SecurityConfig.unauthenticatedPaths().contains(path)) {
                    mismatches.add(name + ": contract says open, chain does not permit it");
                }
                return;
            }
            String enforced = SecurityConfig.requiredAuthorityFor(path);
            if (enforced == null) {
                mismatches.add(name + ": contract requires " + declared
                        + ", chain has no rule for " + path + " so it falls through to the catch-all,"
                        + " which admits any authenticated caller");
            } else if (!declared.equals(enforced)) {
                mismatches.add(name + ": contract says " + declared + ", chain enforces " + enforced);
            }
        });
        assertThat(mismatches)
                .as("the published authority and the enforced authority must agree per operation")
                .isEmpty();
    }

    /**
     * Asserts that exactly the five user-administration operations are administrative and exactly the
     * two token-issuing operations are open, which is the split the baseline's menu graph had.
     */
    @Test
    @DisplayName("the five user operations are administrative and the two token operations are open")
    void theAdministrativeAndOpenSetsAreExactlyAsPublished() {
        List<String> administrative = new ArrayList<>();
        List<String> open = new ArrayList<>();
        operationsByPathAndMethod().forEach((name, operation) -> {
            Object declared = operation.get(AUTHORITY_FIELD);
            if (JwtRoleConverter.ADMIN_AUTHORITY.equals(declared)) {
                administrative.add(name);
            } else if (NO_AUTHORITY.equals(declared)) {
                open.add(name);
            }
        });
        assertThat(administrative)
                .as("the user-administration operations are the ones the baseline reached from the"
                        + " administrative menu only")
                .containsExactlyInAnyOrder(
                        "get /api/v1/auth/users",
                        "post /api/v1/auth/users",
                        "get /api/v1/auth/users/{userId}",
                        "put /api/v1/auth/users/{userId}",
                        "delete /api/v1/auth/users/{userId}");
        assertThat(open)
                .as("only the operations a caller reaches BEFORE it holds a usable token may be"
                        + " reachable without one: the two that issue a token set and the one that"
                        + " renews it, whose access token may already have expired")
                .containsExactlyInAnyOrder(
                        "post /api/v1/auth/signon",
                        "post /api/v1/auth/challenge",
                        "post /api/v1/auth/refresh");
        assertThat(SecurityConfig.unauthenticatedPaths())
                .as("the chain's open list must be exactly those three paths and no subtree, and it is"
                        + " asserted against the contract's own set so neither side can gain a member"
                        + " without the other")
                .containsExactlyInAnyOrderElementsOf(
                        open.stream().map(name -> name.substring(name.indexOf(' ') + 1)).toList());
    }

    // WHY : Assumptions: the assertion is a FLOOR rather than an equality, because the contract
    //       deliberately does not restate the provider's policy - it bounds the value only so that an
    //       oversized body is not relayed onward. What must hold is that the bound cannot refuse a
    //       credential the pool accepts, which is what a floor expresses.
    /**
     * Asserts that the sign-on password bound admits every credential the identity provider's
     * configured policy can accept, and that the retired eight-character record width is gone.
     */
    @Test
    @DisplayName("the password bound admits every credential the provider's policy accepts")
    void passwordBoundAdmitsEveryProviderAcceptableCredential() {
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        Map<String, Object> password =
                mapping(mapping(mapping(schemas, "SignOnRequest"), "properties"), "password");
        assertThat((Integer) password.get("maxLength"))
                .as("a bound below the provider's own minimum length would refuse every credential it"
                        + " accepts; the retired baseline field was eight characters wide")
                .isGreaterThanOrEqualTo(PROVIDER_PASSWORD_FLOOR);
        assertThat(password.get("writeOnly"))
                .as("no operation returns a password, and the schema must say so")
                .isEqualTo(true);

        Map<String, Object> newPassword =
                mapping(mapping(mapping(schemas, "SignOnChallengeRequest"), "properties"),
                        "newPassword");
        assertThat((Integer) newPassword.get("maxLength"))
                .as("the password being SET is bounded on the same basis as the one being presented")
                .isGreaterThanOrEqualTo(PROVIDER_PASSWORD_FLOOR);
        assertThat(newPassword.get("writeOnly")).isEqualTo(true);
    }

    /**
     * Asserts the sign-on schema refuses exactly the whitespace-only values the record's constraints do.
     *
     * <p>Refactoring Rationale: the review found the two sides disagreeing. The schema declared
     * {@code minLength: 1} alone, which admits a value of eight spaces, while
     * {@link com.carddemo.auth.dto.SignOnRequest} annotates both components {@code @NotBlank}, which
     * refuses it -- so a caller generating a request from this contract could build a schema-VALID body
     * the service answers 400. That class of disagreement is the one a published contract exists to
     * prevent, and it is invisible to a compiler because one side is a number in a document.</p>
     *
     * <p>Assumptions: the declared pattern is applied as a PARTIAL match, using {@code find} rather than
     * {@code matches}, because JSON Schema patterns are unanchored. Asserting it with {@code matches}
     * would test a rule this contract does not state and would fail on every real identifier.</p>
     */
    @Test
    @DisplayName("the sign-on schema refuses whitespace-only values exactly as @NotBlank does")
    void signOnSchemaRefusesWhitespaceOnlyValues() {
        Map<String, Object> properties =
                mapping(mapping(mapping(mapping(contract, "components"), "schemas"), "SignOnRequest"),
                        "properties");

        for (String property : List.of("userId", "password")) {
            Map<String, Object> declared = mapping(properties, property);
            assertThat(declared.get("pattern"))
                    .as("%s must carry a non-whitespace pattern, because the record's @NotBlank"
                            + " constraint refuses a value of spaces that minLength alone admits",
                            property)
                    .isNotNull();

            Pattern pattern = Pattern.compile(String.valueOf(declared.get("pattern")));
            assertThat(pattern.matcher("   ").find())
                    .as("a value of only spaces must be refused by the schema, as the baseline refuses"
                            + " it at app/cbl/COSGN00C.cbl lines 118 and 123")
                    .isFalse();
            assertThat(pattern.matcher("\t\n ").find()).isFalse();
            assertThat(pattern.matcher(SAMPLE_USER_ID).find())
                    .as("a real value must still be admitted")
                    .isTrue();
            assertThat(pattern.matcher(" leading space kept ").find())
                    .as("the rule is presence of a non-whitespace character, not absence of whitespace")
                    .isTrue();
        }
    }

    // WHY : Assumptions: the challenge is asserted to exist because its ABSENCE was the defect, not a
    //       shortcoming of its shape. Every seed user is provisioned with a temporary password, so the
    //       first sign-on of every user raises this challenge; with no operation to answer it the
    //       outcome was reported as a server fault and no path to a permanent password existed.
    /**
     * Asserts that the challenge exchange is published, that it sits on the path the edge already
     * routes, and that the two sign-on outcomes are discriminated by a value rather than by absence.
     */
    @Test
    @DisplayName("the sign-on challenge exchange is published and its outcomes are discriminated")
    void signOnChallengeExchangeIsPublishedAndDiscriminated() {
        Map<String, Object> paths = mapping(contract, "paths");
        assertThat(paths)
                .as("the edge publishes POST /api/v1/auth/challenge as an unauthenticated route, so"
                        + " this contract must describe it")
                .containsKey("/api/v1/auth/challenge");

        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        Map<String, Object> challenge = mapping(schemas, "SignOnChallenge");
        @SuppressWarnings("unchecked")
        List<String> challengeRequired = (List<String>) challenge.get("required");
        assertThat(challengeRequired)
                .containsExactlyInAnyOrder("outcome", "challengeName", "session", "userId");

        Map<String, Object> challengeName =
                mapping(mapping(challenge, "properties"), "challengeName");
        @SuppressWarnings("unchecked")
        List<String> admittedChallenges = (List<String>) challengeName.get("enum");
        assertThat(admittedChallenges)
                .as("only the challenge a temporary password raises is contracted for")
                .containsExactly("NEW_PASSWORD_REQUIRED");

        assertThat(mapping(mapping(challenge, "properties"), "outcome").get("const"))
                .isEqualTo("CHALLENGE");
        assertThat(mapping(mapping(mapping(schemas, "SignOnResponse"), "properties"), "outcome")
                .get("const"))
                .as("the two outcomes must be told apart by a value a client can read, not by which"
                        + " members happen to be present")
                .isEqualTo("AUTHENTICATED");

        @SuppressWarnings("unchecked")
        Map<String, Object> signOnSuccess = (Map<String, Object>) mapping(
                mapping(mapping(mapping(paths, "/api/v1/auth/signon"), "post"), "responses"), "200");
        Map<String, Object> schema = mapping(
                mapping(mapping(signOnSuccess, "content"), "application/json"), "schema");
        assertThat(schema)
                .as("the success status carries both outcomes with an explicit discriminator")
                .containsKeys("oneOf", "discriminator");
        assertThat(mapping(schema, "discriminator").get("propertyName")).isEqualTo("outcome");
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
        Map<String, Object> page =
                mapping(mapping(mapping(contract, "components"), "schemas"), "PageResponse");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) page.get("required");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) page.get("properties");
        assertThat(required)
                .as("every member the shared envelope emits must be required")
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

        Map<String, Object> listParameters = mapping(contract, "paths");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> declared = (List<Map<String, Object>>) mapping(
                mapping(listParameters, "/api/v1/auth/users"), "get").get("parameters");
        List<String> references = declared.stream()
                .map(parameter -> String.valueOf(parameter.get("$ref")))
                .toList();
        assertThat(references)
                .as("the list operation must reference the shared paging parameters, so it cannot"
                        + " declare a differently named pair inline")
                .contains("#/components/parameters/Cursor",
                        "#/components/parameters/PagingDirection");

        Map<String, Object> direction =
                mapping(mapping(mapping(contract, "components"), "schemas"), "PageDirection");
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) direction.get("enum");
        assertThat(values).containsExactly("next", "previous");
    }

    /**
     * Asserts that the published correlation bound, character set and header name are exactly what the
     * shared filter enforces, and that the value the browser client previously sent is refused.
     */
    @Test
    @DisplayName("the correlation identity is bounded exactly as the shared filter enforces")
    void correlationIdentityMatchesTheSharedFilter() {
        Map<String, Object> correlationId =
                mapping(mapping(mapping(contract, "components"), "schemas"), "CorrelationId");
        assertThat(correlationId.get("maxLength"))
                .isEqualTo(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);

        Pattern declared = Pattern.compile(String.valueOf(correlationId.get("pattern")));
        assertThat(declared.matcher("A".repeat(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH))
                .matches()).isTrue();
        assertThat(declared.matcher("").matches()).isTrue();
        assertThat(declared.matcher("8f1c0e42-3a55-4d21-9b7e-6c0f2a9d4471").matches())
                .as("a thirty-six-character identity is what the filter refuses with 400")
                .isFalse();

        // WHY : Refactoring Rationale: the published pattern is compared against the filter's own
        //       public predicate over a vector set rather than being eyeballed. An earlier revision
        //       published only the bound and the character set, which is BROADER than what the filter
        //       accepts -- the filter additionally refuses a value made only of digits and the three
        //       separators carrying thirteen or more digits, because that is the shape of a primary
        //       account number and this identity is echoed on the response and written to every log
        //       line. A contract broader than the filter documents requests the service rejects, so a
        //       client built from it fails at run time on a value the document said was fine.
        // WHY : Assumptions: the vectors cover both sides of the boundary rather than only the refused
        //       side. The three separated forms and the contiguous form must be refused; a
        //       timestamp-like value, a value carrying a letter and a minted-shaped value must be
        //       accepted, and each of those is a realistic identity a caller or this service supplies.
        for (String vector : List.of("4111111111111111", "4111-1111-1111-1111",
                "4111.1111.1111.1111", "4111_1111_1111_1111", "1234567890123",
                "2022-07-18-0930", "a1b2c3d4e5f6a7b8c9d0e1f2", "CD0123456789ABCDEF012345",
                "123456789012", "A".repeat(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH))) {
            assertThat(declared.matcher(vector).matches())
                    .as("the published pattern must accept exactly what"
                            + " CorrelationIdFilter.isConformingCorrelationId accepts, and the two"
                            + " disagree on '%s'", vector)
                    .isEqualTo(CorrelationIdFilter.isConformingCorrelationId(vector));
        }

        Map<String, Object> requestHeader =
                mapping(mapping(mapping(contract, "components"), "parameters"),
                        "CorrelationIdHeader");
        assertThat(requestHeader.get("name")).isEqualTo(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(requestHeader.get("in")).isEqualTo("header");
    }

    /**
     * Asserts that an unenforceable rule is refused at construction rather than silently denying every
     * request it matches.
     */
    @Test
    @DisplayName("a rule naming an unrecognised authority cannot be constructed")
    void aRuleNamingAnUnrecognisedAuthorityIsRefused() {
        assertThatCode(() ->
                new SecurityConfig.AuthorityRule("/api/v1/auth/users", "carddemo-superuser"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() ->
                new SecurityConfig.AuthorityRule("  ", JwtRoleConverter.ADMIN_AUTHORITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
