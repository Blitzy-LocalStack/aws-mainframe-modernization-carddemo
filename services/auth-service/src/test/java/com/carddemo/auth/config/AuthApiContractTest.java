package com.carddemo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.auth.api.AuthController;
import com.carddemo.auth.api.UserController;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /**
     * The mapping annotations a handler may carry, each paired with the verb it means.
     *
     * <p>Assumptions: the four verbs the contract uses are enumerated, and a fifth annotation would be
     * absent from this map and so invisible to the mounted walk. That is the safe direction: an operation
     * mounted through an annotation this map omits reads as unmounted and fails the assertion, rather
     * than passing unnoticed.
     */
    private static final Map<Class<? extends Annotation>, String> MAPPING_VERBS = Map.of(
            GetMapping.class, "get",
            PostMapping.class, "post",
            PutMapping.class, "put",
            DeleteMapping.class, "delete");

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
     * Reduces a declared parameter list to the {@code (name, in)} pairs the specification identifies
     * parameters by, following any reference into the components section.
     *
     * <p>Assumptions: a reference is followed rather than compared as a string, because two parameters
     * are the same parameter when their name and location agree -- not when their references agree. A
     * document declaring one header inline and the same header by reference would carry it twice while
     * the two spellings differed, so comparing spellings would report no duplicate at all.</p>
     *
     * @param declared the value of a {@code parameters} key, which may be {@code null} when none are
     *     declared
     * @return one {@code name|in} entry per declared parameter, in document order and WITH repeats
     *     preserved, since the repeats are what this reduction exists to expose; never {@code null}
     * @throws IllegalStateException if an entry is neither a mapping nor resolvable, or if a reference
     *     names a component the document does not declare, either of which makes the contract
     *     unreadable rather than merely wrong
     */
    private List<String> parameterIdentities(Object declared) {
        if (declared == null) {
            return List.of();
        }
        if (!(declared instanceof List<?> entries)) {
            throw new IllegalStateException("a \"parameters\" key must hold a sequence");
        }
        List<String> identities = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new IllegalStateException("every declared parameter must be a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parameter = (Map<String, Object>) raw;
            Object reference = parameter.get("$ref");
            if (reference instanceof String pointer) {
                String component = pointer.substring(pointer.lastIndexOf('/') + 1);
                Map<String, Object> parameters = mapping(mapping(contract, "components"), "parameters");
                if (!parameters.containsKey(component)) {
                    throw new IllegalStateException(
                            "a parameter reference names an undeclared component: " + pointer);
                }
                parameter = mapping(parameters, component);
            }
            identities.add(parameter.get("name") + "|" + parameter.get("in"));
        }
        return identities;
    }

    /**
     * Asserts no operation carries one {@code (name, in)} parameter pair twice, whether within its own
     * list or by restating one it already inherits from its path item.
     *
     * <p>Refactoring Rationale: this gate exists because two operations in this document did exactly
     * that -- {@code listUsers} and {@code deleteUser} each declared the correlation header twice in
     * their own parameter list. The specification states that a parameter list MUST NOT contain
     * duplicates, so those documents were invalid, and the consequence was not theoretical: a
     * generator reading the list emits the header parameter twice, and a validating gateway can refuse
     * a request satisfying one copy of a required parameter but not the other. Neither repeat was
     * visible to a reader, because each sat at the far end of a long justification comment.
     *
     * <p>Assumptions: an inherited pair restated by an operation is treated as a duplicate here even
     * though the specification permits an operation to OVERRIDE an inherited parameter. The
     * distinction that matters is whether the restatement CHANGES anything: an override that differs
     * is a deliberate narrowing, whereas the identical declaration twice over leaves a reader unable
     * to tell which copy is authoritative and is the shape this document had. This gate therefore
     * refuses the restatement outright, and an operation that genuinely needs to narrow an inherited
     * parameter must say so by declaring the parameter only at the operation level -- which is the
     * convention every path item in this document already follows.
     *
     * <p>Trade-offs: the walk is declared in this module rather than shared with the sibling services
     * that publish their own contracts. The shared kernel's test artifact is deliberately restricted
     * to its architecture package -- each service POM records that restriction where it declares the
     * artifact -- so there is no test type this module and its siblings both see. The accepted cost is
     * that the sibling contract carrying the same defect gates it with its own copy of this walk; what
     * is bought is that neither module's build depends on widening a boundary that exists to keep the
     * kernel's test surface closed.
     */
    @Test
    @DisplayName("no operation declares one parameter identity twice, inherited or otherwise")
    void noOperationDeclaresOneParameterIdentityTwice() {
        Map<String, Object> paths = mapping(contract, "paths");

        assertThat(paths).as("the contract must publish at least one path").isNotEmpty();

        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            Map<String, Object> pathItem = mapping(paths, pathEntry.getKey());
            List<String> inherited = parameterIdentities(pathItem.get("parameters"));

            assertThat(inherited)
                    .as("path item %s must not declare one parameter identity twice",
                            pathEntry.getKey())
                    .doesNotHaveDuplicates();

            for (String method : HTTP_METHODS) {
                if (!pathItem.containsKey(method)) {
                    continue;
                }
                List<String> own =
                        parameterIdentities(mapping(pathItem, method).get("parameters"));

                assertThat(own)
                        .as("%s %s must not declare one parameter identity twice",
                                method.toUpperCase(java.util.Locale.ROOT), pathEntry.getKey())
                        .doesNotHaveDuplicates();
                // WHY : Assumptions: the inherited set is tested for emptiness first because the
                //       assertion below refuses an empty expectation outright rather than passing
                //       vacuously -- a path item declaring no parameters of its own would otherwise
                //       fail this case for a reason that has nothing to do with duplication.
                if (!inherited.isEmpty()) {
                    assertThat(own)
                            .as("%s %s must not restate a parameter it already inherits",
                                    method.toUpperCase(java.util.Locale.ROOT), pathEntry.getKey())
                            .doesNotContainAnyElementsOf(inherited);
                }
            }
        }
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
                        + " which denies every caller");
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
     * four session operations are open, which is the split the baseline's menu graph had.
     *
     * <p>Assumptions: ⚠️ Refactoring Rationale: the open set grew from three members to four when the
     * sign-out operation was published. It belongs in the open set for the same reason the renewal does,
     * argued once at the route: a caller closing a session may no longer hold a usable access token, and
     * gating revocation behind one would refuse it in exactly the case that most needs it -- an abandoned
     * session whose access token has expired and whose refresh token has weeks of life left. Its authority
     * is possession of the refresh token, which is all the pool's revocation operation accepts.
     */
    @Test
    @DisplayName("the five user operations are administrative and the four session operations are open")
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
                .as("only the operations a caller reaches WITHOUT a usable token may be reachable without"
                        + " one: the two that issue a token set, the one that renews it, and the one that"
                        + " revokes it -- on each of the last two the access token may already have"
                        + " expired")
                .containsExactlyInAnyOrder(
                        "post /api/v1/auth/signon",
                        "post /api/v1/auth/challenge",
                        "post /api/v1/auth/refresh",
                        "post /api/v1/auth/signout");
        assertThat(SecurityConfig.unauthenticatedPaths())
                .as("the chain's open list must be exactly those four paths and no subtree, and it is"
                        + " asserted against the contract's own set so neither side can gain a member"
                        + " without the other")
                .containsExactlyInAnyOrderElementsOf(
                        open.stream().map(name -> name.substring(name.indexOf(' ') + 1)).toList());
    }

    /**
     * Asserts that every operation the contract publishes is actually mounted by an adapter.
     *
     * <p>Assumptions: this closes the one dimension the sibling assertions above do not reach. They check
     * that the contract and the filter chain agree about which paths exist and what authority each needs,
     * and both agreed while three of the published operations answered 404 -- the contract declared them,
     * the chain opened or gated them, and no handler was mapped. Alignment between two descriptions of a
     * surface says nothing about whether the surface is there.
     *
     * <p>Assumptions: the mounted set is read from the mapping annotations rather than from a running
     * context, so the assertion needs no Spring container and cannot be satisfied by a stub. The two
     * adapter classes are named explicitly because the contract is this service's alone; a new adapter
     * would have to be enrolled here, which is the intended friction.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("every published operation is mounted by a handler, so none of them answers 404")
    void everyPublishedOperationIsMounted() {

        List<String> mounted = new ArrayList<>();
        for (Class<?> adapter : List.of(AuthController.class, UserController.class)) {
            String base = adapter.getAnnotation(RequestMapping.class).path()[0];
            for (Method handler : adapter.getDeclaredMethods()) {
                mountedOperation(base, handler).ifPresent(mounted::add);
            }
        }

        assertThat(mounted)
                .as("the mounted set and the published set must agree in both directions: a published"
                        + " operation with no handler answers 404 while three artifacts describe it as"
                        + " present, and a mounted operation the contract omits is an unpublished surface")
                .containsExactlyInAnyOrderElementsOf(operationsByPathAndMethod().keySet());
    }

    /**
     * Reports the operation key one handler method mounts, if it mounts one.
     *
     * <p>Assumptions: the key is composed in the same lower-case {@code "<verb> <path>"} form the contract
     * walk produces, so the two sets are directly comparable without normalising either at the comparison
     * site.
     *
     * @param base the adapter's class-level path prefix
     * @param handler the candidate handler method
     * @return the operation key, or empty when the method mounts no request mapping
     */
    private static Optional<String> mountedOperation(String base, Method handler) {

        for (Map.Entry<Class<? extends Annotation>, String> candidate : MAPPING_VERBS.entrySet()) {
            Annotation mapping = handler.getAnnotation(candidate.getKey());
            if (mapping == null) {
                continue;
            }
            String[] declared = pathOf(mapping);
            String suffix = declared.length == 0 ? "" : declared[0];
            return Optional.of(candidate.getValue() + " " + base + suffix);
        }
        return Optional.empty();
    }

    /**
     * Reads the {@code path} member of a mapping annotation without knowing its concrete type.
     *
     * <p>Alternatives Considered: a branch per annotation type reading {@code path()} directly, which is
     * type-safe. Rejected because the five annotations declare that member independently rather than
     * through a shared supertype, so a branch per type would be five near-identical blocks that a sixth
     * annotation would silently escape. Reflection over the member name treats all five uniformly.
     *
     * @param mapping the mapping annotation to read
     * @return the declared paths, empty when the annotation declares none
     * @throws IllegalStateException if the annotation publishes no readable {@code path} member, which
     *     would mean this walk had been pointed at an annotation that is not a request mapping
     */
    private static String[] pathOf(Annotation mapping) {

        try {
            return (String[]) mapping.annotationType().getMethod("path").invoke(mapping);
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException(
                    "a mapping annotation did not publish a path member: " + mapping, unreadable);
        }
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
     * Asserts the renewal token is bounded on both sides, by the same number, and generously.
     *
     * <p>Refactoring Rationale: the review found this property bounded on NEITHER side. The schema
     * declared {@code minLength: 1} and no maximum, the record annotated no {@code @Size}, and both
     * documents justified the absence by naming a transport request-size limit that did not exist -- so
     * the one operation in this document reachable without a credential accepted a body of any length.
     * The two sides are now checked against each other rather than each against its own prose, because a
     * bound declared in a contract that the record does not enforce is a promise a caller can generate a
     * valid request against and still be refused by.</p>
     *
     * <p>Assumptions: the bound is asserted to be at least a FLOOR as well as equal across the two
     * documents, and the floor is the point. This token is opaque and provider-sized, and the cost of a
     * bound too tight is a caller holding a valid token that cannot renew and must sign on again with a
     * credential it may no longer hold. A floor several times the largest token the pool has been
     * observed to issue is what makes that outcome unreachable, and it is asserted rather than left to
     * the constant's own comment.</p>
     *
     * @throws ReflectiveOperationException if the component or its backing field cannot be resolved,
     *     which a renamed component would cause and which should fail this test rather than skip it
     */
    @Test
    @DisplayName("the renewal token is bounded identically by the record and the committed schema")
    void theRenewalTokenIsBoundedOnBothSides() throws ReflectiveOperationException {
        Map<String, Object> declared = mapping(mapping(mapping(
                mapping(mapping(contract, "components"), "schemas"), "TokenRefreshRequest"),
                "properties"), "refreshToken");

        Integer published = (Integer) declared.get("maxLength");
        assertThat(published)
                .as("an unbounded token on the one operation reachable without a credential is the"
                        + " finding this case exists for")
                .isNotNull()
                .isGreaterThanOrEqualTo(REFRESH_TOKEN_FLOOR);

        Class<?> record = Class.forName("com.carddemo.auth.dto.TokenRefreshRequest");
        RecordComponent component = componentNamed(record, "refreshToken");
        Size enforced = declaredSize(record, component);
        assertThat(enforced)
                .as("the committed schema declares maxLength %s, so the record must enforce it -- a"
                        + " contract-valid body the service refuses is the disagreement a published"
                        + " contract exists to prevent", published)
                .isNotNull();
        assertThat(enforced.max())
                .as("the record's bound and the published bound must be one number")
                .isEqualTo(published);
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

    /**
     * Asserts that every request component this module annotates {@code @NotBlank} publishes a
     * machine-readable non-whitespace constraint under the same name in the same schema.
     *
     * <p>Refactoring Rationale: the review found the create body disagreeing with its record in exactly
     * the way the sign-on body had already been corrected for, which is what makes a hand-listed check
     * the wrong instrument. The test above names two properties of one schema; a third schema was added
     * with the same defect and no assertion noticed. This case therefore derives its expectation from the
     * ANNOTATIONS rather than from a list, so a component annotated {@code @NotBlank} in a future record,
     * or a component whose annotation is added later, is covered without this file being edited.</p>
     *
     * <p>Assumptions: a record component satisfies the contract in either of two ways, and both are
     * accepted because both are machine-readable. It may declare a non-whitespace {@code pattern}, or it
     * may declare an {@code enum} none of whose members is blank -- the latter is a strictly narrower
     * statement of the same rule, and requiring a pattern beside it would demand that a document restate
     * a domain constraint it already expresses more precisely.</p>
     *
     * <p>Assumptions: the record class name is the schema name. That correspondence is what the published
     * document already uses -- {@code SignOnRequest} and {@code CreateUserRequest} are named identically
     * on both sides -- so the mapping is read from the name rather than declared in a table that could
     * drift from it.</p>
     *
     * <p>Refactoring Rationale: the loop covers ALL FIVE request records of the package, where it covered
     * two. Three were missing for two different reasons and neither survives: the update body's record
     * did not exist when this case was written, and the challenge and renewal bodies' records were added
     * later with the operations they serve. A closed loop over two records is exactly the hand-maintained
     * list this case was introduced to replace, so leaving three out reproduced the defect it exists to
     * catch.</p>
     *
     * <p>Refactoring Rationale: each component is now asserted TWICE, once against the committed document
     * and once against the record's own schema annotation, because the review found the two disagreeing
     * in a way the document-side assertion alone cannot see. The document served from
     * {@code /v3/api-docs} is generated from the annotations, and a non-blank constraint contributes a
     * minimum length and no pattern, so a committed facet with no annotation behind it means the two
     * documents describe different shapes. The second assertion is what makes the annotation's removal a
     * failure rather than a silent regression -- it has to be, because a schema-documentation annotation
     * affects nothing a compiler or a runtime check would notice.</p>
     *
     * @throws ReflectiveOperationException never in practice; declared because the component types are
     *     resolved reflectively and a renamed record would surface here rather than as a silent skip
     */
    @Test
    @DisplayName("every @NotBlank request component publishes a non-whitespace constraint")
    void everyNotBlankComponentPublishesANonWhitespaceConstraint() throws ReflectiveOperationException {
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        int asserted = 0;

        for (String recordName : NOT_BLANK_REQUEST_RECORDS) {
            Class<?> record = Class.forName("com.carddemo.auth.dto." + recordName);
            Map<String, Object> properties = mapping(mapping(schemas, recordName), "properties");

            for (RecordComponent component : record.getRecordComponents()) {
                if (!isNotBlankAnnotated(record, component)) {
                    continue;
                }
                asserted++;
                Map<String, Object> declared = mapping(properties, component.getName());
                assertThat(declaresNonWhitespaceRule(declared))
                        .as("%s.%s is annotated @NotBlank, so the published %s schema must refuse a"
                                + " whitespace-only value by pattern or by enum rather than by prose"
                                + " alone", recordName, component.getName(), recordName)
                        .isTrue();

                // WHY : Assumptions: a component whose domain is an enum needs no annotation, because the
                //       generated document carries the enum from the constraint that declares it and the
                //       committed schema states the domain rather than a pattern. Requiring one would
                //       demand that a record publish a facet its own schema does not declare.
                if (declared.get("pattern") == null) {
                    continue;
                }
                assertThat(publishedPattern(record, component))
                        .as("%s.%s declares pattern in the committed schema, so the record must publish"
                                + " the identical string into the GENERATED document -- @NotBlank renders"
                                + " as minLength alone, so without the annotation the two documents"
                                + " describe different shapes", recordName, component.getName())
                        .isEqualTo(String.valueOf(declared.get("pattern")));
            }
        }

        // WHY : Assumptions: the count is asserted because a reflective loop that matched nothing would
        //       otherwise pass. Fourteen is the two sign-on components, the four of the create body, the
        //       three of the update body, the three of the challenge answer and the two of the renewal,
        //       and a change to any of the five records moves this number rather than silently emptying
        //       the loop.
        assertThat(asserted)
                .as("the reflective loop must actually have found the annotated components")
                .isEqualTo(14);
    }

    /**
     * Reads the pattern a record component publishes into the generated document, if it publishes one.
     *
     * <p>Assumptions: the annotation is looked for on the accessor and then on the backing field, in that
     * order, because a record's component annotations are propagated to whichever targets the annotation
     * declares and the schema annotation permits both. Checking one alone would report a component as
     * unannotated depending on which target the annotation happened to reach.</p>
     *
     * <p>Assumptions: an absent annotation and an annotation with the default empty pattern are both
     * reported as {@code null}, because the generated document carries no pattern in either case, and
     * that is the fact this assertion is about.</p>
     *
     * @param record the record class declaring the component; must not be {@code null}
     * @param component the component to read; must not be {@code null}
     * @return the pattern the component publishes, or {@code null} when it publishes none
     * @throws ReflectiveOperationException if the backing field cannot be resolved, which a renamed
     *     component would cause
     */
    private static String publishedPattern(Class<?> record, RecordComponent component)
            throws ReflectiveOperationException {

        Schema onAccessor = component.getAccessor().getAnnotation(Schema.class);
        Schema declared = onAccessor != null
                ? onAccessor
                : record.getDeclaredField(component.getName()).getAnnotation(Schema.class);

        if (declared == null || declared.pattern().isEmpty()) {
            return null;
        }
        return declared.pattern();
    }

    /**
     * The request records whose non-blank components this case holds to the committed document.
     *
     * <p>Assumptions: this is every record in {@code com.carddemo.auth.dto} that carries a request body,
     * which is five of the package's nine records -- the four remaining ones are responses, and a
     * response body's constraints are not evaluated on the way out. The list is named rather than
     * discovered by scanning the package because a scan would silently cover nothing if the package name
     * changed, whereas a named class that disappears fails to load.</p>
     */
    private static final List<String> NOT_BLANK_REQUEST_RECORDS = List.of(
            "SignOnRequest",
            "SignOnChallengeRequest",
            "TokenRefreshRequest",
            "CreateUserRequest",
            "UpdateUserRequest");

    /**
     * Asserts that no request property claims non-blankness in prose without also enforcing it.
     *
     * <p>Refactoring Rationale: this is the same defect stated from the document's own side, and it
     * catches the case the reflective test above cannot -- a property whose description asserts the value
     * must not be blank while no record component claims it, so there is no annotation to derive an
     * expectation from and the contradiction sits entirely inside the document. That is not hypothetical:
     * it was the state of the update body's two name properties, whose implementing record did not yet
     * exist when this case was written. The record exists now and the reflective case above covers it, but
     * this one is retained because the situation recurs by construction -- every new schema in this
     * document is authored before the record that serves it, so between those two moments this case is the
     * only thing holding the description to the constraints beside it.</p>
     *
     * <p>Assumptions: the phrase searched for is the one this document actually uses, and it is searched
     * for case-insensitively because the sentence opens some descriptions and continues others. A
     * property that carries the claim and the constraint together is the passing case; a property that
     * carries only the claim is the defect.</p>
     */
    @Test
    @DisplayName("no request property asserts non-blankness in prose without enforcing it")
    void nonBlanknessClaimedInProseIsAlwaysEnforced() {
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        List<String> unenforced = new ArrayList<>();

        for (String schemaName : schemas.keySet()) {
            Map<String, Object> schema = mapping(schemas, schemaName);
            if (!(schema.get("properties") instanceof Map)) {
                continue;
            }
            Map<String, Object> properties = mapping(schema, "properties");
            for (String propertyName : properties.keySet()) {
                Map<String, Object> declared = mapping(properties, propertyName);
                String description = String.valueOf(declared.getOrDefault("description", ""));
                boolean claimsNonBlank = description.toLowerCase(Locale.ROOT).contains("not be blank");
                if (claimsNonBlank && !declaresNonWhitespaceRule(declared)) {
                    unenforced.add(schemaName + "." + propertyName);
                }
            }
        }

        assertThat(unenforced)
                .as("each of these properties tells a reader it must not be blank while admitting a value"
                        + " of spaces, which is the disagreement F03 was raised against")
                .isEmpty();
    }

    /**
     * Reports whether a record component carries the not-blank constraint on any of its three carriers.
     *
     * <p>Assumptions: the component itself is NOT one of them, and that is the whole reason this helper
     * exists rather than a direct call. {@code @NotBlank} declares its targets as method, field,
     * constructor, parameter, annotation type and type use, and {@code ElementType.RECORD_COMPONENT} is
     * not among them, so the compiler propagates the annotation to the accessor, the backing field and the
     * canonical constructor parameter but leaves the record component itself bare. Reading
     * {@code RecordComponent.getAnnotation} therefore returns null for every component of every record in
     * this module -- which is exactly what the count assertion in the caller was written to catch, and did.
     * </p>
     *
     * <p>Trade-offs: both the accessor and the field are consulted rather than only the accessor. One
     * would be sufficient for the current compiler behaviour, and consulting both costs a reflective
     * lookup that never runs more than a handful of times; what it buys is that the helper does not depend
     * on which permitted carrier a future compiler chooses to propagate to.</p>
     *
     * @param record the record class declaring the component; must not be {@code null}
     * @param component the component to test; must not be {@code null}
     * @return {@code true} when the accessor or the backing field carries {@code @NotBlank}
     * @throws ReflectiveOperationException if the backing field cannot be resolved, which would mean the
     *     class is not the record this test believes it to be
     */
    private static boolean isNotBlankAnnotated(Class<?> record, RecordComponent component)
            throws ReflectiveOperationException {

        if (component.getAccessor().getAnnotation(NotBlank.class) != null) {
            return true;
        }
        return record.getDeclaredField(component.getName()).getAnnotation(NotBlank.class) != null;
    }

    /**
     * Reports whether a declared property refuses a whitespace-only value by a machine-readable rule.
     *
     * <p>Assumptions: an enum satisfies the rule when every member carries a non-whitespace character,
     * which is checked rather than assumed -- an enum admitting a single space would be a domain
     * constraint that permits exactly the value under discussion.</p>
     *
     * @param declared the property's declared schema; must not be {@code null}
     * @return {@code true} when a pattern refuses a whitespace-only value, or an enum admits only
     *     non-blank members; {@code false} when neither holds
     */
    private static boolean declaresNonWhitespaceRule(Map<String, Object> declared) {
        Object declaredPattern = declared.get("pattern");
        if (declaredPattern != null) {
            Pattern pattern = Pattern.compile(String.valueOf(declaredPattern));
            if (!pattern.matcher("   ").find() && !pattern.matcher("\t\n ").find()) {
                return true;
            }
        }
        Object declaredEnum = declared.get("enum");
        if (declaredEnum instanceof List<?> members && !members.isEmpty()) {
            return members.stream().allMatch(member -> !String.valueOf(member).isBlank());
        }
        return false;
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
     * <p>Assumptions: the four are {@code items}, {@code firstKey}, {@code lastKey} and
     * {@code hasNext} -- the closed set {@code com.carddemo.common.web.PageResponse} declares -- read
     * from the shared type rather than restated as a literal list where the type can be reached,
     * because the whole defect this asserts against was a contract that named members the type does
     * not declare. Refactoring Rationale: this Javadoc said five while its display name and its
     * assertion said four; the arity is stated once now, from the record.</p>
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
        //       separators carrying nine or more digits, because that is the shape of every protected
        //       identifier this system holds and this identity is echoed on the response and written
        //       to every log line. A contract broader than the filter documents requests the service
        //       rejects, so a client built from it fails at run time on a value the document said was
        //       fine. This equality is what caught the widening: the filter's floor moved from
        //       thirteen digits to nine and this assertion failed on the published pattern until the
        //       seven documents were corrected to match, which is the whole reason it compares a
        //       vector set against the predicate instead of restating the regex.
        // WHY : Assumptions: the vectors cover both sides of the boundary rather than only the refused
        //       side, and they name each protected width rather than only the widest. Refused: the
        //       card number in contiguous and all three separated forms, the nine-digit customer and
        //       national identifier width, the eleven-digit account identifier width contiguous and
        //       separated, and two intermediate all-digit runs. Accepted: an eight-digit run and an
        //       eight-digit separated date, both below the floor; a value carrying a letter; a
        //       minted-shaped value; and a value of full width made only of letters. Each is a
        //       realistic identity a caller or this service supplies.
        for (String vector : List.of("4111111111111111", "4111-1111-1111-1111",
                "4111.1111.1111.1111", "4111_1111_1111_1111", "1234567890123",
                "123456789", "00000000011", "000-0000-0011", "2022-07-18-0930",
                "12345678", "2022-07-18", "a1b2c3d4e5f6a7b8c9d0e1f2", "CD0123456789ABCDEF012345",
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

    /**
     * The smallest renewal-token bound that cannot refuse a token this pool legitimately issues.
     *
     * <p>Assumptions: four thousand and ninety-six, which is roughly twice the longest refresh token
     * this pool has been observed to issue. It is a FLOOR on the published bound rather than the bound
     * itself, because the contract deliberately does not restate the provider's own sizing -- what must
     * hold is that the bound cannot lock out a caller holding a valid token, which is what a floor
     * expresses. It is the same shape of assertion, and for the same reason, as
     * {@link #PROVIDER_PASSWORD_FLOOR}.</p>
     */
    private static final int REFRESH_TOKEN_FLOOR = 4096;

    /**
     * Resolves one named component of a record.
     *
     * @param record the record class to read; must not be {@code null}
     * @param name the component name to find; must not be {@code null}
     * @return the named component, never {@code null}
     * @throws IllegalStateException if the record declares no component of that name, which a rename
     *     would cause and which must fail rather than silently assert nothing
     */
    private static RecordComponent componentNamed(Class<?> record, String name) {
        for (RecordComponent component : record.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        throw new IllegalStateException(record.getName() + " declares no component named " + name);
    }

    /**
     * Reads the size constraint a record component enforces, if it enforces one.
     *
     * <p>Assumptions: the accessor is consulted before the backing field, for the same reason
     * {@link #publishedPattern} consults both -- a record's component annotations are propagated to
     * whichever targets the annotation itself declares, and reading one alone would report an annotated
     * component as unannotated depending on which target the annotation happened to reach. The
     * constraint is NOT declared for the record-component target, so it never survives on the component
     * itself and reading that would always report an absence.</p>
     *
     * @param record the record class declaring the component; must not be {@code null}
     * @param component the component to read; must not be {@code null}
     * @return the size constraint the component enforces, or {@code null} when it enforces none
     * @throws ReflectiveOperationException if the backing field cannot be resolved, which a renamed
     *     component would cause
     */
    private static Size declaredSize(Class<?> record, RecordComponent component)
            throws ReflectiveOperationException {

        Size onAccessor = component.getAccessor().getAnnotation(Size.class);
        return onAccessor != null
                ? onAccessor
                : record.getDeclaredField(component.getName()).getAnnotation(Size.class);
    }

    /**
     * Asserts that every published operation declares the transport refusals this service can produce,
     * and that each of the three has a shape a client can bind to.
     *
     * <p>⚠️ Refactoring Rationale: all three were reachable and UNDECLARED. The shared advice in
     * {@code services/common-lib} answers a wrong method with 405, a body in the wrong media type with
     * 415 and an unsatisfiable {@code Accept} header with 406, on every route of every service, and this
     * contract declared none of them. A client generated from it had no branch for any of the three and a
     * hand-written one, {@code ui/src/api/client.ts}, could only report each as an unrecognised body. The
     * census is written as a loop rather than as a list of paths so that an operation added later is
     * covered without this case being edited.</p>
     *
     * <p>Assumptions: the operation count is asserted so the loop cannot pass vacuously. A scanner that
     * matched nothing -- because a path item gained a member, or because the document was restructured --
     * would otherwise assert nothing at all while reading as though it had checked every route.</p>
     */
    @Test
    @DisplayName("every operation declares the transport refusals the shared advice can produce")
    void theTransportRefusalsAreDeclaredWhereTheyCanOccur() {
        Map<String, Object> document = this.contract;
        Map<String, Object> paths = mapping(document, "paths");
        int operations = 0;

        for (String path : paths.keySet()) {
            Map<String, Object> methods = mapping(paths, path);
            for (String method : methods.keySet()) {

                // WHY : Assumptions: a path item carries members that are not operations -- a shared
                //       description, a shared parameter list -- so an operation is identified by carrying
                //       an operationId rather than by the key not being one of those. Naming the
                //       exclusions instead would need amending every time a path item gained a member.
                if (!(methods.get(method) instanceof Map)) {
                    continue;
                }
                Map<String, Object> operation = mapping(methods, method);
                if (!operation.containsKey("operationId")) {
                    continue;
                }
                operations++;
                Map<String, Object> responses = mapping(operation, "responses");
                assertThat(responses)
                        .as("%s %s must declare both refusals every route can produce", method, path)
                        .containsKeys("405", "406");
                if (operation.containsKey("requestBody")) {
                    assertThat(responses)
                            .as("%s %s accepts a body, so it can refuse the body's media type",
                                    method, path)
                            .containsKey("415");
                }
            }
        }

        assertThat(operations)
                .as("the census is not vacuous; every published operation was examined")
                .isEqualTo(9);

        Map<String, Object> declared = mapping(mapping(document, "components"), "responses");
        assertThat(declared)
                .as("each refusal is declared once and referenced, so the three cannot drift apart")
                .containsKeys("MethodNotAllowed", "NotAcceptable", "UnsupportedMediaType");
        assertThat(mapping(mapping(declared, "MethodNotAllowed"), "headers"))
                .as("a 405 names the methods the route does publish, which is what a client acts on")
                .containsKey("Allow");
    }
}
