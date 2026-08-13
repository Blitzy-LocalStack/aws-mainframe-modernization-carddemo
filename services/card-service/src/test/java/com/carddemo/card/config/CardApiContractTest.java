package com.carddemo.card.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.carddemo.card.api.CardController;
import com.carddemo.card.dto.CardPageQuery;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.common.security.SealedSelector;
import com.carddemo.common.web.CorrelationIdFilter;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * stronger form and is what a negative authorization test needs. This class does not use it, and the reason
 * is division of labour rather than availability: it asserts the rule TABLE that
 * {@link SecurityConfig#filterChain} builds its matchers from, which is a value a document can be compared
 * against, while {@code SecurityConfigTest} and {@code SecurityChainDispatchTest} stand the chain up and
 * drive requests through it. Refactoring Rationale: this paragraph read "this module has no application
 * class yet, so there is no context to stand up", which was true when it was written and is not now --
 * {@code CardApplication} exists and two sibling classes do stand a context up. The sentence is corrected
 * rather than deleted because the choice it explains is still the right one; only its stated reason had gone
 * stale.</p>
 *
 * <p>Assumptions: the RESPONSE shapes this document declares are held to the records that serialise into
 * them by {@link CardApiContractGateTest}, not here. That division is deliberate: this class owns the
 * document's agreement with the security rules and the shared constants, and that one owns its agreement
 * with the Java types and with its own published examples, so neither grows into a place where every
 * contract assertion is added by default.</p>
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
     * The declared width of a card number, which is the value the sealer's arithmetic is asserted over.
     *
     * <p>Assumptions: sixteen, from {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy} L5. It is
     * named rather than written inline so the one assertion that binds the contract's selector length to
     * the sealing envelope reads as being about a card number rather than about an arbitrary sixteen.</p>
     */
    private static final int CARD_NUMBER_LENGTH = 16;

    /**
     * The masked rendering of {@link #SAMPLE_ACCOUNT_NUMBER}, used as a value a path must refuse.
     *
     * <p>Assumptions: a masked rendering is the value a client is most likely to send by mistake,
     * because it is the only card-number-shaped value any response of this contract hands out, and it
     * identifies no row.</p>
     */
    private static final String SAMPLE_MASKED_NUMBER = "************0011";

    /**
     * A specimen selector of the published length and alphabet, used to make a path template concrete.
     *
     * <p>Assumptions: this is a synthetic string rather than a selector produced by the sealer, because
     * this test asserts the contract's declared SHAPE and holds no deployment key. A value that seals
     * nothing is exactly right for that: it satisfies the declared length and alphabet, so it exercises
     * the shape check, and it opens to nothing, so it cannot be mistaken for a real address.</p>
     *
     * <p>Refactoring Rationale: an earlier revision minted this by calling the CURSOR sealer with a
     * test-local key, on the ground that a real token cannot drift from the sealer's own output. The
     * selector is no longer sealed by that primitive -- a cursor expires and a row selector must not --
     * and the anti-drift property is kept a different way: {@link #theSelectorLengthMatchesTheSealer()}
     * asserts the declared length against {@code SealedSelector.sealedLengthFor(16)} directly, which
     * binds the literal to the arithmetic without this class holding a key at all.</p>
     */
    private static final String SAMPLE_SELECTOR =
            "fake-selector-example-not-a-real-sealed-value-0000000000000";

    /**
     * The number of characters the contract declares a selector to occupy.
     *
     * <p>Assumptions: 59 is what {@code SealedSelector.sealedLengthFor(16)} returns for the
     * sixteen-character card number. It is restated here so this test fails if the contract's declared
     * bound and the sealer's arithmetic ever part company.</p>
     */
    private static final int SELECTOR_LENGTH = 59;


    /**
     * The exact set of path templates and methods this contract is obliged to publish.
     *
     * <p>Assumptions: this is a CLOSED set, asserted as an equality rather than a containment, which
     * is what makes it catch an operation being ADDED as well as one going missing. The five
     * obligations are the three read-or-write actions of the baseline's three card programs --
     * app/cbl/COCRDLIC.cbl for the list, COCRDSLC.cbl for the detail and COCRDUPC.cbl for the update
     * -- plus the administrative reading of the detail, which is the one operation that renders a full
     * account number and therefore sits on its own prefix, plus the lookup that resolves a card number
     * supplied in a request body.</p>
     *
     * <p>Refactoring Rationale: the set was four entries keyed by {@code {cardNumber}} until a review
     * established that a card number in a request line is written verbatim into the load balancer's
     * access log, from inside the load balancer, before any application code runs -- so no masking this
     * service performs can bound it and access logging is mandatory in this deployment. The single-card
     * paths are now keyed by an opaque selector and the lookup is the operation that accepts the number
     * a user typed, in a body, which neither access log records.</p>
     */
    private static final List<String> CONTRACTED_OPERATIONS = List.of(
            // WHY : Refactoring Rationale: this entry was "get /api/v1/cards". The listing is a POST on
            //   a literal /search segment because its account narrowing is an account identifier, and a
            //   query string is part of the request line the load balancer writes into its access log
            //   itself, before any application code runs. The migration's sensitive-data logging
            //   contract names account identifiers alongside the primary account number, so the
            //   narrowing had to leave the request line exactly as the card number already had.
            "post /api/v1/cards/search",
            "post /api/v1/cards/lookup",
            "get /api/v1/cards/{cardKey}",
            "put /api/v1/cards/{cardKey}",
            "get /api/v1/admin/cards/{cardKey}");

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
        return pathTemplate.replaceAll("\\{[^}]+}", SAMPLE_SELECTOR);
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
                .containsExactly("get /api/v1/admin/cards/{cardKey}");
        assertThat(SecurityConfig.requiredAuthorityFor(
                        "/api/v1/admin/cards/" + SAMPLE_SELECTOR))
                .isEqualTo(JwtRoleConverter.ADMIN_AUTHORITY);
        assertThat(SecurityConfig.requiredAuthorityFor("/api/v1/cards/" + SAMPLE_SELECTOR))
                .as("the ordinary read must NOT require the administrator authority")
                .isEqualTo(JwtRoleConverter.USER_AUTHORITY);
        // WHY : Assumptions: the administrative widening is a property of the OPERATION and not of
        //       the value addressing it. The same selector an ordinary list row hands out is what
        //       reaches this path, so asserting both authorities against the SAME sample is what
        //       shows the selector is an identifier and not a capability: holding one does not move
        //       a caller across the authority boundary, and the boundary is the route.
        assertThat(SecurityConfig.requiredAuthorityFor("/api/v1/cards/lookup"))
                .as("resolving a typed number is an ordinary-user action: it discloses one masked"
                        + " row, which a list already discloses for every card")
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
    //       it is asserted as an equality because a per-operation assertion cannot see an operation
    //       that was added or one that quietly disappeared. It has caught both directions: an
    //       intermediate revision dropped the single-card paths while every remaining operation still
    //       read correctly, and the revision before this one carried a card-number query filter that no
    //       individual assertion objected to.
    /**
     * Asserts that the contract publishes exactly the five contracted operations and no others.
     */
    @Test
    @DisplayName("the contract publishes exactly the five contracted operations")
    void theContractPublishesExactlyTheFiveContractedOperations() {
        assertThat(operationsByPathAndMethod().keySet())
                .as("the published operation set is closed: an addition is as much a divergence as an"
                        + " omission, because each one is a route a client may be typed against")
                .containsExactlyInAnyOrderElementsOf(CONTRACTED_OPERATIONS);
    }

    /**
     * Asserts that every operation the contract publishes is actually mounted by an adapter.
     *
     * <p>Assumptions: this closes the dimension the assertion above does not reach. That one checks the
     * published SET is exactly the five contracted operations, and it held while this context served none
     * of them -- there was no adapter and no service at all, so every published route answered 404 while
     * the contract, the filter chain and the repository charter all described a working surface. A closed
     * published set says nothing about whether anything answers on it.
     *
     * <p>Assumptions: the mounted set is read from the mapping annotations rather than from a running
     * context, so the assertion needs no container and cannot be satisfied by a stub. The adapter class is
     * named explicitly, which is the intended friction: a second adapter would have to be enrolled here.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("every published operation is mounted by a handler, so none of them answers 404")
    void everyPublishedOperationIsMounted() {

        Map<Class<? extends Annotation>, String> verbs = Map.of(
                GetMapping.class, "get",
                PostMapping.class, "post",
                PutMapping.class, "put",
                DeleteMapping.class, "delete");

        List<String> mounted = new ArrayList<>();
        for (Method handler : CardController.class.getDeclaredMethods()) {
            for (Map.Entry<Class<? extends Annotation>, String> candidate : verbs.entrySet()) {
                Annotation mapping = handler.getAnnotation(candidate.getKey());
                if (mapping != null) {
                    mounted.add(candidate.getValue() + " " + declaredPath(mapping));
                }
            }
        }

        assertThat(mounted)
                .as("the mounted set and the published set must agree in both directions: a published"
                        + " operation with no handler answers 404 while three artifacts describe it as"
                        + " present, and a mounted operation the contract omits is an unpublished surface")
                .containsExactlyInAnyOrderElementsOf(operationsByPathAndMethod().keySet());
    }

    /**
     * Reads the single declared path of a mapping annotation without knowing its concrete type.
     *
     * <p>Alternatives Considered: a branch per annotation type reading {@code path()} directly, which is
     * type-safe. Rejected because the four annotations declare that member independently rather than
     * through a shared supertype, so a branch per type would be four near-identical blocks that a fifth
     * annotation would silently escape.
     *
     * @param mapping the mapping annotation to read
     * @return the one path the annotation declares
     * @throws IllegalStateException if the annotation publishes no readable {@code path} member, or
     *     declares none, either of which would mean this walk had been pointed at something that is not a
     *     mounted handler
     */
    private static String declaredPath(Annotation mapping) {

        try {
            String[] declared =
                    (String[]) mapping.annotationType().getMethod("path").invoke(mapping);
            if (declared.length == 0) {
                throw new IllegalStateException("a mounted handler declared no path: " + mapping);
            }
            return declared[0];
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException(
                    "a mapping annotation did not publish a path member: " + mapping, unreadable);
        }
    }

    /**
     * Asserts that a single card is addressed by an opaque sealed selector and that neither a card
     * number nor a masked rendering satisfies the parameter's own shape.
     */
    @Test
    @DisplayName("a single card is addressed by an opaque sealed selector")
    void aSingleCardIsAddressedByASealedSelector() {
        Map<String, Object> parameters = mapping(mapping(contract, "components"), "parameters");
        assertThat(parameters)
                .as("no parameter may carry a card number: a path segment and a query string are both"
                        + " written verbatim into the load balancer's access log")
                .doesNotContainKeys("CardNumberPath", "CardNumberFilter");

        Map<String, Object> pathParameter = mapping(parameters, "CardKeyPath");
        assertThat(pathParameter.get("name")).isEqualTo("cardKey");
        assertThat(pathParameter.get("in")).isEqualTo("path");
        assertThat(pathParameter.get("required")).isEqualTo(true);
        assertThat(mapping(pathParameter, "schema").get("$ref"))
                .as("the selector's shape is declared once as a schema and referenced, so the path"
                        + " parameter and the response members cannot describe different shapes")
                .isEqualTo("#/components/schemas/CardSelector");

        Map<String, Object> selector =
                mapping(mapping(mapping(contract, "components"), "schemas"), "CardSelector");
        assertThat(selector.get("minLength"))
                .as("the bound is an EXACT length, because a run of sixteen digits is itself valid"
                        + " URL-safe base64 and only the length separates the two")
                .isEqualTo(SELECTOR_LENGTH);
        assertThat(selector.get("maxLength")).isEqualTo(SELECTOR_LENGTH);

        Pattern declared = Pattern.compile(String.valueOf(selector.get("pattern")));
        assertThat(declared.matcher(SAMPLE_SELECTOR).matches())
                .as("a selector of the published length and alphabet is what addresses a card, so it"
                        + " must be accepted")
                .isTrue();
        assertThat(declared.matcher(SAMPLE_MASKED_NUMBER).matches())
                .as("a masked rendering identifies no row, and it is the value a client is most likely"
                        + " to send by mistake, so the shape check must refuse it")
                .isFalse();
        assertThat(declared.matcher(SAMPLE_ACCOUNT_NUMBER).matches())
                .as("a card number must not satisfy the selector shape, or the value this change"
                        + " removed from the request line could be put back by a client")
                .isFalse();

        // WHY : Refactoring Rationale: an uppercased selector is asserted STILL SHAPE-VALID, where an
        //       earlier revision asserted it refused on the ground that exactly one rendering of a
        //       selector may address a row. The conclusion is right and the mechanism named was wrong.
        //       The alphabet is URL-safe base64, which contains both cases, so no shape check can tell
        //       an uppercased token from a legitimate one that happens to carry capitals -- and a shape
        //       check that tried would refuse most genuine selectors. What actually makes exactly one
        //       rendering valid is the sealer: the token carries an authentication tag over its own
        //       bytes, so altering a single character makes it fail to OPEN and the request is refused
        //       with 400. Asserting the false claim here would have let the pattern be narrowed to one
        //       case, which would have broken every selector containing a capital.
        assertThat(declared.matcher(SAMPLE_SELECTOR.toUpperCase(Locale.ROOT)).matches())
                .as("the sealed alphabet carries both cases, so an altered token is refused by the"
                        + " sealer's authentication rather than by this shape")
                .isTrue();
        assertThat(declared.matcher(SAMPLE_SELECTOR.substring(1) + "+").matches())
                .as("a character outside the URL-safe alphabet is refused by the shape, which is what"
                        + " the shape is for")
                .isFalse();

        // WHY : Refactoring Rationale: the two schemas are asserted to publish DIFFERENT shapes, where
        //       an earlier revision required them to publish one. They are sealed by two different
        //       primitives on purpose. A row selector addresses a row that does not move and appears in
        //       a bookmarkable route, so it carries no lifetime and is a fixed-width sealing of the
        //       card's own key; a paging cursor names a POSITION, is bound to the query, the direction
        //       and the caller, and is deliberately short-lived. Sealing a selector with the cursor's
        //       expiring primitive would make a card URL stop resolving for a reason arising from
        //       nothing about the card, so the two shapes are declared separately and this assertion is
        //       what stops them being merged back together.
        assertThat(String.valueOf(selector.get("pattern")))
                .as("the selector and the cursor are minted by two different sealers, so one shape"
                        + " must not be published for both")
                .isNotEqualTo(String.valueOf(
                        mapping(mapping(mapping(contract, "components"), "schemas"), "CursorToken")
                                .get("pattern")));
    }

    /**
     * Asserts that the one place a caller supplies a full card number is a request body, and that the
     * body carrying it admits nothing else.
     */
    @Test
    @DisplayName("a full card number is accepted only in the lookup request body")
    void aFullCardNumberIsAcceptedOnlyInABody() {
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        Map<String, Object> lookup = mapping(schemas, "CardLookupRequest");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) lookup.get("properties");
        assertThat(properties.keySet())
                .as("the lookup body resolves one number and admits nothing beside it")
                .containsExactly("cardNumber");
        assertThat(lookup.get("additionalProperties")).isEqualTo(false);

        Pattern number = Pattern.compile(
                String.valueOf(mapping(properties, "cardNumber").get("pattern")));
        assertThat(number.matcher(SAMPLE_ACCOUNT_NUMBER).matches()).isTrue();
        assertThat(number.matcher(SAMPLE_MASKED_NUMBER).matches())
                .as("a masked rendering identifies no row here either")
                .isFalse();

        assertThat(mapping(mapping(contract, "paths"), "/api/v1/cards/lookup").keySet())
                .as("the lookup is a POST, because a GET body has no defined semantics for caches and"
                        + " intermediaries and the number would end up back in the request line")
                .containsExactly("post");

        @SuppressWarnings("unchecked")
        Map<String, Object> operation = (Map<String, Object>)
                mapping(mapping(contract, "paths"), "/api/v1/cards/lookup").get("post");
        assertThat(operation.get("operationId")).isEqualTo("lookupCard");
        assertThat(operation.containsKey("requestBody"))
                .as("a card number a user entered must travel in a body, because a body reaches no"
                        + " access log while a request line reaches every one of them")
                .isTrue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> declaredParameters =
                (List<Map<String, Object>>) operation.get("parameters");
        assertThat(declaredParameters)
                .as("the only parameter is the correlation header; a query or path parameter here would"
                        + " defeat the reason this operation is a POST")
                .allSatisfy(parameter -> assertThat(String.valueOf(parameter.get("$ref")))
                        .isEqualTo("#/components/parameters/CorrelationIdHeader"));
    }

    /**
     * Asserts that no published path template and no declared query parameter can carry a card number.
     *
     * <p>Refactoring Rationale: this replaces an assertion that built a concrete URL from every
     * template and required the shared masker to redact it. That assertion held the wrong thing to
     * account: the masker bounds this service's own log lines and error bodies, and the record this
     * finding is about is the load balancer's, written from the request line before any application
     * code runs. The property that actually closes it is that no template and no query parameter has a
     * place to put a card number at all, which is what is asserted here.</p>
     */
    @Test
    @DisplayName("no path template and no query parameter can carry a card number")
    void noRequestLineCanCarryACardNumber() {
        List<String> offending = new ArrayList<>();
        for (String template : mapping(contract, "paths").keySet()) {
            if (template.toLowerCase(Locale.ROOT).contains("cardnumber")) {
                offending.add("path " + template);
            }
        }
        mapping(mapping(contract, "components"), "parameters").forEach((name, declared) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameter = (Map<String, Object>) declared;
            if (!"path".equals(parameter.get("in")) && !"query".equals(parameter.get("in"))) {
                return;
            }
            if ("cardNumber".equals(parameter.get("name"))) {
                offending.add(parameter.get("in") + " parameter " + name);
            }
        });
        assertThat(offending)
                .as("a path segment and a query string are both persisted verbatim by the load"
                        + " balancer's mandatory access log, which no downstream masking can redact")

                .isEmpty();

        // WHY : Assumptions: the redaction is asserted as well as the absence, because the two protect
        //       different things. The absence keeps a number out of the routes this contract publishes;
        //       the redaction covers a number that reaches a request line some OTHER way -- a stale
        //       client typed against a withdrawn route, or a query parameter no longer declared -- and
        //       this service's own records still have to be safe when one does.
        // WHY : Refactoring Rationale: the specimen below was described as "the optional list filter",
        //       which this contract no longer has: the cardNumber query parameter was withdrawn in the
        //       same change that removed the number from every path, and the assertion above is what
        //       keeps it withdrawn. The specimen is retained as a WITHDRAWN-ROUTE probe rather than
        //       renamed away, because a stale client is exactly the caller that would still send it.
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(
                        "/api/v1/cards?cardNumber=" + SAMPLE_ACCOUNT_NUMBER))
                .as("a card number reaching a request line through a withdrawn route must not be"
                        + " retained by this service's own records")
                .doesNotContain(SAMPLE_ACCOUNT_NUMBER);
    }

    // WHY : Refactoring Rationale: a second test asserted the lookup operation separately, naming
    //       an operationId and a response schema -- lookupCardSelector and
    //       CardSelectorLookupResponse -- that this contract does not publish: the lookup answers
    //       with the whole CardDetail rather than a bare handle. Its two assertions that
    //       aFullCardNumberIsAcceptedOnlyInABody did not already make -- that the operation is
    //       named lookupCard and declares no parameter but the correlation header -- were folded
    //       into that test rather than kept in a second one, so the one operation has one test.
    /**
     * Asserts that every response shape carrying a card publishes the selector a client needs to
     * address it again.
     */
    @Test
    @DisplayName("every card response carries the selector that addresses it")
    void everyCardResponseCarriesItsSelector() {
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        for (String name : List.of("CardSummary", "CardDetailCore")) {
            Map<String, Object> schema = mapping(schemas, name);
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertThat(mapping(properties, "key").get("$ref"))
                    .as(name + " must address its card through the one declared selector schema")
                    .isEqualTo("#/components/schemas/CardSelector");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertThat(required)
                    .as("an optional selector would be an absence every client had to handle, on a"
                            + " value every response can produce")
                    .contains("key");
        }
    }

    // WHY : Refactoring Rationale: the shared masker is retained as DEFENCE IN DEPTH and is asserted
    //       here on the value it can actually bound -- a card number that reaches this service's own
    //       error body or log line, from a request body or from a stale client. It is deliberately no
    //       longer asserted against the published path templates, because none of them has anywhere to
    //       put a number and because the record that mattered was never one this service writes.
    /**
     * Asserts that the shared masker still redacts a card number reaching a diagnostic string.
     */
    @Test
    @DisplayName("the shared masker still redacts a card number reaching a diagnostic string")
    void theSharedMaskerStillRedactsACardNumberInADiagnosticString() {
        String diagnostic = "lookup refused for " + SAMPLE_ACCOUNT_NUMBER;
        assertThat(CardNumberMasker.maskEmbeddedCardNumbers(diagnostic))
                .as("a number supplied in a lookup body can still reach a log line or an error body,"
                        + " which is the exposure this masker does bound")
                .doesNotContain(SAMPLE_ACCOUNT_NUMBER);
    }

    /**
     * Asserts that the page envelope declares and requires exactly the four members the shared
     * response type carries, so no generated client receives an accessor for a member no service
     * emits and no strict client rejects a valid response for a member no service sends.
     *
     * <p>Assumptions: the four are {@code items}, {@code firstKey}, {@code lastKey} and
     * {@code hasNext} -- the closed set {@code com.carddemo.common.web.PageResponse} declares -- and
     * they are named here in the same order the record declares them, because the whole defect this
     * asserts against was a contract that named members the type does not declare. Refactoring
     * Rationale: this Javadoc said five while its own display name and its own assertion said four, so
     * the sentence a reader trusts disagreed with the sentence the build enforces; the arity is now
     * stated once, from the record.</p>
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
        // WHY : Refactoring Rationale: the paging inputs were components.parameters entries named
        //   Cursor and PagingDirection and are now MEMBERS of the CardPageQuery request body, so this
        //   assertion reads the schema rather than the parameter table. The property NAMES are what the
        //   original defect was about -- two request inputs named after the response's row identities,
        //   which are null on exactly the page whose cursors are not -- and a member name collides just
        //   as a parameter name did, so the assertion is unchanged in substance.
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        Map<String, Object> pageQuery = mapping(schemas, "CardPageQuery");
        @SuppressWarnings("unchecked")
        Map<String, Object> queryProperties = (Map<String, Object>) pageQuery.get("properties");
        assertThat(queryProperties.keySet())
                .as("paging is expressed as exactly one cursor plus one direction, alongside the"
                        + " optional account narrowing")
                .containsExactlyInAnyOrder("accountId", "cursor", "direction");
        assertThat(queryProperties.keySet())
                .as("no request member may be named after a row identity")
                .doesNotContain("ForwardCursor", "BackwardCursor", "firstKey", "lastKey");

        Map<String, Object> parameters = mapping(mapping(contract, "components"), "parameters");
        assertThat(parameters.keySet())
                .as("no paging input survives as a query parameter, which is what keeps the account"
                        + " narrowing out of the request line")
                .doesNotContain("Cursor", "PagingDirection", "AccountIdFilter");

        Map<String, Object> direction = mapping(schemas, "PageDirection");
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) direction.get("enum");
        assertThat(values)
                .as("the direction vocabulary is shared with the browser client and the sibling"
                        + " contracts")
                .containsExactly("next", "previous");
        assertThat(direction.get("default")).isEqualTo("next");

        // WHY : Refactoring Rationale: the enumeration above was declared and UNENFORCED, which was
        //   reported against the service: every spelling outside it read as the default and was answered
        //   with a forward page and no complaint. The expression the request record now declares is
        //   asserted to admit exactly the two published values and nothing else, so the document and the
        //   running constraint cannot state two vocabularies. Composing the expression FROM this list
        //   would satisfy the assertion whatever either side said, which is why the two are compared
        //   rather than derived from one another.
        assertThat(values)
                .allSatisfy(published -> assertThat(published)
                        .as("the published direction %s must satisfy the enforced domain", published)
                        .matches(CardPageQuery.DIRECTION_DOMAIN));
        assertThat(List.of("NEXT", "Previous", "PREVIOUS", "sideways", "", " previous", "previous "))
                .as("no other spelling may satisfy the enforced domain")
                .allSatisfy(rejected -> assertThat(rejected)
                        .doesNotMatch(CardPageQuery.DIRECTION_DOMAIN));
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
        // WHY : Refactoring Rationale: this assertion once read "and nothing that addresses a card",
        //       excluding a selector member from the row. It now REQUIRES one. What the exclusion was
        //       protecting -- that a list response discloses no card number -- is unaffected, because
        //       the selector is a random value generated at insert and not derived from the number,
        //       so no function takes a page of selectors back to a single digit of any card. What the
        //       exclusion cost was real: with no address on the row, every single-card operation had
        //       to be addressed by the number itself, which put that number into a path the load
        //       balancer's access log retains in full.
        assertThat(summaryProperties.keySet())
                .as("a list row carries the three values the baseline row displayed plus the opaque"
                        + " selector that addresses the card, and no card number in any form")
                .containsExactlyInAnyOrder("key", "displayCardNumber", "accountId", "activeStatus");
        assertThat(coreProperties.keySet())
                .as("a detail shape carries the selector too, so a caller that read one card can build"
                        + " its update route without returning to the list")
                .contains("key");
    }

    /**
     * Asserts the declared selector length is the length the sealer actually produces.
     *
     * <p>Assumptions: this is the anti-drift assertion the minted specimen used to provide, expressed
     * against the arithmetic rather than against a token. It needs no key, so it holds in a unit test,
     * and it fails if either the contract bound or the sealing envelope changes without the other.</p>
     */
    @Test
    @DisplayName("the declared selector length is the length the sealer produces")
    void theSelectorLengthMatchesTheSealer() {
        assertThat(SealedSelector.sealedLengthFor(CARD_NUMBER_LENGTH))
                .as("the contract declares %d characters; a change to the sealing envelope must be"
                        + " reflected in the contract and in the record constraint together",
                        SELECTOR_LENGTH)
                .isEqualTo(SELECTOR_LENGTH);
        assertThat(SAMPLE_SELECTOR).hasSize(SELECTOR_LENGTH);
    }

    /**
     * Asserts that every schema carrying a masked rendering constrains the masked FORM, so that a raw
     * sixteen-digit account number cannot satisfy one.
     *
     * <p>Refactoring Rationale: an earlier revision bounded those members by LENGTH alone, on the
     * reasoning that a sixteen-digit pattern would refuse the very value the member holds. The reasoning
     * was sound and the conclusion was not: a raw account number is itself exactly sixteen characters
     * wide, so it satisfied the bound and no part of the contract said otherwise. This assertion is what
     * keeps the corrected bound from being loosened back.</p>
     */
    @Test
    @DisplayName("every masked rendering constrains the masked form, not merely the width")
    void everyMaskedRenderingConstrainsTheMaskedForm() {
        Map<String, Object> schemas = mapping(mapping(contract, "components"), "schemas");
        List<String> permittingARawNumber = new ArrayList<>();
        for (String schemaName : List.of("CardSummary", "CardDetailCore")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) mapping(schemas, schemaName).get("properties");
            Object declared = mapping(properties, "displayCardNumber").get("pattern");
            if (declared == null
                    || Pattern.compile(String.valueOf(declared))
                            .matcher(SAMPLE_ACCOUNT_NUMBER).matches()) {
                permittingARawNumber.add(schemaName);
            }
        }
        assertThat(permittingARawNumber)
                .as("a schema whose masked member admits an unmasked number is one mapper mistake away"
                        + " from disclosing a primary account number on a non-administrative response")
                .isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> summaryProperties =
                (Map<String, Object>) mapping(schemas, "CardSummary").get("properties");
        assertThat(Pattern.compile(String.valueOf(
                        mapping(summaryProperties, "displayCardNumber").get("pattern")))
                        .matcher(SAMPLE_MASKED_NUMBER).matches())
                .as("and the masked rendering the mapper actually produces must still be accepted")
                .isTrue();

    }

    /**
     * Asserts every request schema is closed AND that this module is configured to enforce the closure.
     *
     * <p>Purpose: a schema declaring {@code additionalProperties: false} is a promise about what a body
     * may carry, and the library default is to discard an undeclared member silently, so the declaration
     * and the configuration are two halves of one rule. Runtime testing found them apart: a search body
     * carrying three undeclared members -- {@code pageNumber}, {@code size} and {@code offset}, the shape
     * of an offset paging model this service does not implement -- was answered 200 with the default
     * page, so a client that had guessed wrong got a plausible answer and no way to learn it had.</p>
     */
    // WHY : Assumptions: the configuration is read from this module's own application.yml rather than
    //       from a running context, so the case fails if the key is deleted from the file a deployment
    //       actually loads. A context-based assertion would pass on a mapper some test fixture had
    //       configured and would say nothing about the deployed service.
    // WHY : Trade-offs: the two halves are asserted in ONE case rather than two. They are separable
    //       facts, but neither is worth anything alone -- an enforced-but-open schema refuses nothing and
    //       a closed-but-unenforced schema documents a refusal that does not happen -- so a single case
    //       that fails on either is the honest granularity.
    @Test
    @DisplayName("every request schema is closed and this module enforces the closure")
    void everyRequestSchemaIsClosedAndTheClosureIsEnforced() {
        Map<String, Object> schemas = mapping(mapping(this.contract, "components"), "schemas");

        for (String requestSchema : List.of("CardPageQuery", "CardLookupRequest", "CardUpdateRequest")) {
            assertThat(mapping(schemas, requestSchema).get("additionalProperties"))
                    .as("%s is a request body schema and must be closed", requestSchema)
                    .isEqualTo(false);
        }

        assertThat(configuredJacksonDeserialization().get("fail-on-unknown-properties"))
                .as("the closure the schemas declare has to be enforced by this module's own"
                        + " configuration, because the library default discards an undeclared member")
                .isEqualTo(true);
    }

    /**
     * Asserts the three transport refusals this service can produce are declared on the operations that
     * can produce them, each with the {@code ApiError} shape.
     *
     * <p>Purpose: all three were undeclared while the shared advice could not produce them either, so
     * every one was answered as HTTP 500 with a CRITICAL severity. Declaring them and handling them are
     * two halves of one correction, and this case holds the declaration half.</p>
     */
    // WHY : Assumptions: 405 and 406 are required on EVERY operation and 415 only on the three that
    //       accept a body, because that is the difference between them: any path can be addressed with a
    //       verb it does not publish and any request can name an unacceptable Accept, whereas a route
    //       taking no body has no content type to refuse.
    @Test
    @DisplayName("the transport refusals are declared where they can occur")
    void theTransportRefusalsAreDeclaredWhereTheyCanOccur() {
        Map<String, Object> paths = mapping(this.contract, "paths");
        int operations = 0;

        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> methods = mapping(paths, path.getKey());
            for (String method : methods.keySet()) {

                // WHY : Assumptions: a path item carries members that are not operations -- a shared
                //       description, a shared parameter list -- so the operation is identified by
                //       carrying an operationId rather than by the key not being one of those. Naming the
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
                        .as("%s %s must declare both transport refusals every route can produce",
                                method, path.getKey())
                        .containsKeys("405", "406");
                if (operation.containsKey("requestBody")) {
                    assertThat(responses)
                            .as("%s %s accepts a body, so it can refuse the body's media type",
                                    method, path.getKey())
                            .containsKey("415");
                }
            }
        }

        assertThat(operations)
                .as("the census is not vacuous; every published operation was examined")
                .isEqualTo(5);

        Map<String, Object> declared = mapping(mapping(this.contract, "components"), "responses");
        assertThat(declared)
                .containsKeys("MethodNotAllowed", "NotAcceptable", "UnsupportedMediaType");
        assertThat(mapping(mapping(declared, "MethodNotAllowed"), "headers"))
                .as("a 405 names the methods the route does publish, which is what a client acts on")
                .containsKey("Allow");
    }

    /**
     * Reads this module's configured Jackson deserialization settings from its own base configuration.
     *
     * @return the mapping beneath {@code spring.jackson.deserialization}; never {@code null}
     * @throws IllegalStateException if the file is absent from the source tree, or if it does not carry
     *     the mapping, either of which means the setting this case asserts is not configured at all
     */
    // WHY : Assumptions: the file is read from the SOURCE tree rather than from the classpath, because
    //       the classpath copy is the build output and reading it would let a stale target directory pass
    //       a case about a file a deployment loads. The path is resolved relative to the module the test
    //       runs in, which is where the surefire working directory points.
    private static Map<String, Object> configuredJacksonDeserialization() {
        Path configuration = Path.of("src", "main", "resources", "application.yml");
        if (!Files.isRegularFile(configuration)) {
            throw new IllegalStateException(
                    "this module's base configuration is absent at " + configuration.toAbsolutePath());
        }

        try (InputStream stream = Files.newInputStream(configuration)) {
            Object parsed = new Yaml().load(stream);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException(configuration + " is not a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> document = (Map<String, Object>) parsed;
            return mapping(mapping(mapping(document, "spring"), "jackson"), "deserialization");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(configuration + " could not be read", failure);
        }
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
