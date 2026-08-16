package com.carddemo.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts that every operation the contract publishes has a handler, and every handler an operation.
 *
 * <p>Purpose: this class exists because the two halves drifted. The committed contract declared eight
 * synchronous operations while the adapters of this package served one, and nothing failed: the sibling
 * {@code com.carddemo.auth.config.AuthApiContractTest} asserts a great deal ABOUT the document -- that
 * each operation names a published authority, that the declared authority is the one the filter chain
 * enforces, that the challenge exchange is published and discriminated -- but every one of those
 * assertions is satisfied by the document alone, so all of them passed while seven operations had no code
 * behind them. This class closes that gap by comparing the document against the mapped routes.
 *
 * <p>Assumptions: the census is BIDIRECTIONAL and that is the point rather than thoroughness for its own
 * sake. A one-way check that every published operation has a handler would let a handler exist that the
 * document never promised -- an undocumented route, reachable and unpublished -- and a one-way check the
 * other way would let the original defect recur. Comparing the two sets for equality is the only form
 * that refuses both.
 *
 * <p>Assumptions: the routes are read from the annotations rather than from a running application context,
 * so the census is a unit test that needs no server, no database and no identity provider. What it gives
 * up is that it proves a route is MAPPED rather than that it is reachable end to end; reachability is the
 * filter chain's concern and is asserted by the security tests beside this class.
 */
class OperationCensusTest {

    /** Classpath location of the contract this module publishes. */
    private static final String CONTRACT_RESOURCE = "/openapi/auth-api.yaml";

    /** The adapters whose mapped routes are compared against the document. */
    private static final List<Class<?>> ADAPTERS = List.of(AuthController.class, UserController.class);

    /**
     * Asserts the published operations and the mapped handlers are the same set, both ways.
     *
     * <p>Assumptions: an operation is identified by its method and path together rather than by its
     * {@code operationId}, because that pair is what a client actually calls and what the framework
     * actually maps. An identifier could agree while a path or a verb differed, which is exactly the drift
     * a caller would experience as a 404 or a 405.
     */
    @Test
    @DisplayName("every published operation has a handler and every handler a published operation")
    void thePublishedOperationsAndTheMappedHandlersAreTheSameSet() {

        Map<String, String> published = publishedOperations();
        Map<String, String> mapped = mappedRoutes();

        assertThat(mapped.keySet())
                .as("the mapped routes are exactly the published operations; a route present on one side"
                        + " only is either an unimplemented operation or an undocumented endpoint")
                .containsExactlyInAnyOrderElementsOf(published.keySet());
    }

    /**
     * Asserts the census covers the nine operations the package charter enumerates.
     *
     * <p>Assumptions: this guards the guard. The equality above would be satisfied by an EMPTY document
     * compared against no handlers, so a mistake in the document reader that returned nothing would make
     * the census vacuously green. Pinning the count to the charter's own figure is what stops that; nine
     * is the number the charter states and the number the contract's two tags account for, four plus five.
     *
     * <p>Assumptions: ⚠️ Refactoring Rationale: the figures moved from eight and three to nine and four
     * when the sign-out operation was published. They are restated as literals rather than derived from
     * the document, because deriving them from the same document the equality above reads would restore the
     * vacuity this case exists to refuse -- a reader returning nothing would then satisfy both sides.
     */
    @Test
    @DisplayName("the census covers the nine operations the charter enumerates, four open and five admin")
    void theCensusCoversAllNinePublishedOperations() {

        Map<String, String> published = publishedOperations();

        assertThat(published)
                .as("the contract publishes the nine operations the package charter enumerates")
                .hasSize(9);

        assertThat(published.values())
                .as("four operations are reachable without a token and five require the administrator")
                .filteredOn("none"::equals)
                .hasSize(4);
    }

    /**
     * Asserts each adapter carries the operations its charter assigns it, and not the other's.
     *
     * <p>Assumptions: the split is asserted because it is an AUTHORITY boundary and not a preference about
     * file size. The four session operations are reachable without a token and the five roster operations
     * require the administrator, so a handler that migrated from one adapter to the other would sit among
     * rules written for the opposite posture -- which is the mistake most likely to publish an
     * administrative operation without a token.
     *
     * <p>Assumptions: the fourth session operation ENDS a session where the other three issue one, and it
     * sits on this adapter rather than the roster one for the same reason they do: its authority is
     * possession of a refresh token rather than membership of the administrator group, so it belongs among
     * rules written for that posture. The asymmetry is deliberate and is argued at the route itself.
     */
    @Test
    @DisplayName("the session operations and the roster operations sit on the adapters the charter assigns")
    void eachAdapterCarriesTheOperationsItsCharterAssigns() {

        Set<String> signOnRoutes = routesOf(AuthController.class).keySet();
        Set<String> rosterRoutes = routesOf(UserController.class).keySet();

        assertThat(signOnRoutes)
                .as("the sign-on adapter carries exactly the four operations that open or close a session")
                .containsExactlyInAnyOrder(
                        "POST /api/v1/auth/signon",
                        "POST /api/v1/auth/challenge",
                        "POST /api/v1/auth/refresh",
                        "POST /api/v1/auth/signout");

        assertThat(rosterRoutes)
                .as("the roster adapter carries exactly the five administrative operations")
                .containsExactlyInAnyOrder(
                        "GET /api/v1/auth/users",
                        "POST /api/v1/auth/users",
                        "GET /api/v1/auth/users/{userId}",
                        "PUT /api/v1/auth/users/{userId}",
                        "DELETE /api/v1/auth/users/{userId}");
    }

    /**
     * Reads every operation the contract publishes, keyed by verb and path.
     *
     * @return the published operations keyed {@code "VERB /path"}, each mapped to its declared authority;
     *     never {@code null}
     */
    private static Map<String, String> publishedOperations() {

        Map<String, Object> paths = section(document(), "paths");
        Map<String, String> operations = new TreeMap<>();

        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> verbs = asMapping(path.getValue());
            for (Map.Entry<String, Object> verb : verbs.entrySet()) {
                // WHY : Assumptions: a path-level member that is not a verb -- description, or the shared
                //       parameters list -- is skipped by testing the key against the verb set rather than
                //       by assuming every member is an operation. The single-row path carries both.
                if (!VERBS.contains(verb.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Map<String, Object> operation = asMapping(verb.getValue());
                operations.put(verb.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(),
                        String.valueOf(operation.get("x-required-authority")));
            }
        }

        return operations;
    }

    /** The HTTP verbs a path member may name. */
    private static final Set<String> VERBS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /**
     * Reads every route the adapters map, keyed by verb and path.
     *
     * @return the mapped routes keyed {@code "VERB /path"}, each mapped to its handler method name; never
     *     {@code null}
     */
    private static Map<String, String> mappedRoutes() {
        Map<String, String> routes = new TreeMap<>();
        for (Class<?> adapter : ADAPTERS) {
            routes.putAll(routesOf(adapter));
        }
        return routes;
    }

    /**
     * Reads the routes one adapter maps, composing each from its class-level and method-level mappings.
     *
     * <p>Assumptions: the class-level prefix is read from the annotation rather than from the constant it
     * references, so the value asserted is the one the framework will actually use. Reading the constant
     * would agree with itself even if the annotation named something else.
     *
     * @param adapter the adapter to read; must not be {@code null}
     * @return the adapter's routes keyed {@code "VERB /path"}, each mapped to its handler method name;
     *     never {@code null}
     */
    private static Map<String, String> routesOf(Class<?> adapter) {

        RequestMapping classMapping = adapter.getAnnotation(RequestMapping.class);
        String prefix = classMapping == null || classMapping.path().length == 0
                ? ""
                : classMapping.path()[0];

        Map<String, String> routes = new LinkedHashMap<>();

        for (Method handler : adapter.getDeclaredMethods()) {
            for (Map.Entry<String, String[]> mapping : methodMappings(handler).entrySet()) {
                // WHY : Assumptions: a mapping declaring no path contributes the class-level prefix alone,
                //       which is how the collection-path operations are declared -- @GetMapping and
                //       @PostMapping with no path member sit directly on /api/v1/auth/users.
                String[] subpaths = mapping.getValue().length == 0 ? new String[] {""}
                        : mapping.getValue();
                for (String subpath : subpaths) {
                    routes.put(mapping.getKey() + " " + prefix + subpath, handler.getName());
                }
            }
        }

        return routes;
    }

    /**
     * Reads the verb-to-paths mappings one handler method declares.
     *
     * @param handler the method to inspect; must not be {@code null}
     * @return each verb the method maps, with the paths declared for it; never {@code null} and empty for a
     *     method that maps nothing
     */
    private static Map<String, String[]> methodMappings(Method handler) {

        Map<String, String[]> mappings = new LinkedHashMap<>();

        GetMapping get = handler.getAnnotation(GetMapping.class);
        if (get != null) {
            mappings.put("GET", get.path());
        }
        PostMapping post = handler.getAnnotation(PostMapping.class);
        if (post != null) {
            mappings.put("POST", post.path());
        }
        PutMapping put = handler.getAnnotation(PutMapping.class);
        if (put != null) {
            mappings.put("PUT", put.path());
        }
        DeleteMapping delete = handler.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            mappings.put("DELETE", delete.path());
        }

        return mappings;
    }

    /**
     * Parses the published contract from the classpath.
     *
     * @return the contract as a mapping; never {@code null}
     * @throws IllegalStateException if the contract is absent, unreadable, or not a mapping
     */
    private static Map<String, Object> document() {
        try (InputStream resource = OperationCensusTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(
                        "the published contract is absent from the classpath at " + CONTRACT_RESOURCE);
            }
            Object parsed = new Yaml().load(resource);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException(
                        "the published contract at " + CONTRACT_RESOURCE + " is not a mapping");
            }
            return asMapping(parsed);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "the published contract at " + CONTRACT_RESOURCE + " could not be read", failure);
        }
    }

    /**
     * Reads one required top-level section of the contract.
     *
     * @param document the parsed contract; must not be {@code null}
     * @param name the section to read; must not be {@code null}
     * @return the section as a mapping; never {@code null}
     * @throws IllegalStateException if the section is absent or is not a mapping
     */
    private static Map<String, Object> section(Map<String, Object> document, String name) {
        Object value = document.get(name);
        if (!(value instanceof Map)) {
            throw new IllegalStateException(
                    "the published contract declares no " + name + " mapping");
        }
        return asMapping(value);
    }

    /**
     * Narrows a parsed YAML node to a string-keyed mapping.
     *
     * @param value the node to narrow; must not be {@code null}
     * @return the node as a mapping; never {@code null}
     * @throws IllegalStateException if the node is not a mapping
     */
    private static Map<String, Object> asMapping(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalStateException("expected a mapping but found " + value);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) value;
        return mapping;
    }

    /**
     * Reports the members of one collection that are absent from another, for a failure message.
     *
     * <p>Assumptions: this exists so a failure names WHICH operations are missing rather than only that the
     * two sets differ. A census whose failure message lists nine routes on each side leaves a reader
     * diffing by eye, which is the work the test was meant to do.
     *
     * @param from the collection to read members from; must not be {@code null}
     * @param absentIn the collection to test membership against; must not be {@code null}
     * @return the members of the first absent from the second, in encounter order; never {@code null}
     */
    private static List<String> missing(Set<String> from, Set<String> absentIn) {
        List<String> gaps = new ArrayList<>(new LinkedHashSet<>(from));
        gaps.removeAll(absentIn);
        return gaps;
    }

    /**
     * Asserts the census reports the specific gaps rather than only that the sets differ.
     *
     * <p>Assumptions: this is the diagnostic quality of the census under test, not the census itself. It
     * runs the same comparison and asserts both directions are empty, which makes the failure message name
     * the unimplemented operations and the undocumented routes separately.
     */
    @Test
    @DisplayName("neither an unimplemented operation nor an undocumented route is present")
    void neitherSideCarriesAnythingTheOtherDoesNot() {

        Set<String> published = publishedOperations().keySet();
        Set<String> mapped = mappedRoutes().keySet();

        assertThat(missing(published, mapped))
                .as("operations the contract publishes with no handler behind them")
                .isEmpty();

        assertThat(missing(mapped, published))
                .as("routes the adapters map that the contract never published")
                .isEmpty();
    }
}
