package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.api.FraudController;
import com.carddemo.authorization.api.PendingAuthController;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that the contract this module publishes is actually REACHABLE: that the class-path folder
 * holding it is served, that the browser view is pointed at it, and that the two agree on its name.
 *
 * <p>Refactoring Rationale: this class exists because those settings disagreed. The contract was packaged
 * into the jar and named in prose as the interface of record, while
 * {@code spring.web.resources.static-locations} did not include {@code classpath:/openapi/} -- and the
 * framework serves static content only from the locations that key names. The file was therefore present in
 * the artifact and answered 404 at every URL. Nothing in the build noticed, because each half is valid on
 * its own: a YAML list is a valid list, and a resource on the class path is a resource on the class path.
 * Only the relationship between them was wrong.</p>
 *
 * <p>Assumptions: all three facts are read from the shipped artifacts -- the profile from the class path,
 * the contract from the class path -- so the assertions are about what is packaged rather than about
 * literals restated here. The one literal is the location constant below, which is the string the framework
 * itself matches on.</p>
 */
class ContractPublicationTest {

    /** The class-path location that must be served for the packaged contract to be reachable. */
    private static final String OPENAPI_LOCATION = "classpath:/openapi/";

    /** The base profile, packaged from {@code src/main/resources}. */
    private static final String BASE_PROFILE = "/application.yml";

    /**
     * Confirms the class-path folder holding the contract is among the served static locations.
     *
     * <p>Assumptions: the four framework defaults are asserted as well, because this key REPLACES the
     * default list rather than extending it. Dropping {@code classpath:/META-INF/resources/} would stop the
     * Swagger UI assets being served at all, since they ship inside a webjar under exactly that path, and
     * that failure would look like a broken page rather than a missing configuration line.</p>
     */
    @Test
    @DisplayName("the openapi class-path folder is served, and the four framework defaults are preserved")
    void openapiClassPathFolderIsServedAndDefaultsArePreserved() {
        List<String> locations = staticLocations();

        assertThat(locations)
                .as("without this location the packaged contract is reachable at no URL")
                .contains(OPENAPI_LOCATION);
        assertThat(locations)
                .as("this key replaces the default list, so every default must be restated")
                .contains("classpath:/META-INF/resources/", "classpath:/resources/",
                        "classpath:/static/", "classpath:/public/");
        assertThat(locations)
                .as("the class-path ROOT must not be served: it would publish application.yml itself")
                .doesNotContain("classpath:/", "classpath:");
    }

    /**
     * Confirms the browser view is pointed at the packaged contract and that the file it names exists.
     *
     * <p>Assumptions: the URL is resolved to a class-path resource and that resource is opened, so this
     * asserts reachability rather than agreement between two strings. A URL naming a file that is not
     * packaged would satisfy any assertion that only compared spellings.</p>
     *
     * @throws IllegalStateException if the packaged contract cannot be read once it has been located,
     *     which would mean the artifact is damaged rather than misconfigured
     */
    @Test
    @DisplayName("the swagger view names a contract that is packaged under the served location")
    void swaggerViewNamesAContractThatIsPackagedUnderTheServedLocation() {
        Map<String, Object> swaggerUi = section(BASE_PROFILE, "springdoc", "swagger-ui");
        Object url = swaggerUi.get("url");

        assertThat(url)
                .as("without this key the view displays the GENERATED document, not the contract of record")
                .isNotNull();
        String path = String.valueOf(url);
        assertThat(path)
                .as("the URL must be origin-relative, so it resolves against whatever host served the page")
                .startsWith("/")
                .doesNotContain("://");

        try (InputStream contract =
                ContractPublicationTest.class.getResourceAsStream("/openapi" + path)) {
            assertThat(contract)
                    .as("%s must resolve to a packaged contract under %s", path, OPENAPI_LOCATION)
                    .isNotNull();
        } catch (java.io.IOException problem) {
            throw new IllegalStateException("the packaged contract could not be read", problem);
        }

        assertThat(swaggerUi.get("disable-swagger-default-url"))
                .as("the bundled sample definition must be removed from the selector, or a reader can "
                        + "silently end up reading a document that describes nothing in this service")
                .isEqualTo(Boolean.TRUE);
    }

    /**
     * Confirms the packaged contract is the hand-authored 3.1 document, not an empty or generated stand-in.
     *
     * <p>Assumptions: the assertion is on the specification version and on the presence of at least one
     * published path. Those two together are what distinguish the authored contract from the document the
     * annotations alone would produce.</p>
     */
    @Test
    @DisplayName("the packaged contract is an openapi 3.1 document declaring at least one path")
    void packagedContractIsAnOpenApi31DocumentDeclaringAtLeastOnePath() {
        Map<String, Object> document = contract();

        assertThat(String.valueOf(document.get("openapi")))
                .as("the served document and the pinned api-docs version must agree on the specification")
                .startsWith("3.1");
        assertThat(document.get("paths"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .as("an empty paths object would mean the generated stand-in was packaged by mistake")
                .isNotEmpty();
    }

    /**
     * Confirms every published operation is served by a route a controller in this module declares.
     *
     * <p>Refactoring Rationale: this assertion exists because a review found the reverse condition. The
     * contract declared three operations, a sealed path selector and a body-versus-path key comparison,
     * and no controller existed for any of them -- so the document promised behaviour nothing served, and
     * the request record the comparison was written for had no production consumer at all. Neither half
     * could be detected by anything in the build: a YAML document is a valid YAML document whether or not
     * a handler answers it, and a Java record compiles whether or not anything constructs it.</p>
     *
     * <p>Assumptions: the comparison is between the document's own path keys and the mapping constants the
     * two controllers publish, so both sides are read rather than restated. Writing the three paths out as
     * literals here would let this test agree with itself while the document and the routes diverged, which
     * is the exact failure it exists to catch.</p>
     *
     * <p>Assumptions: the set is asserted to be EXACTLY the three, in both directions. A fourth path in the
     * document would be an operation no handler answers; a fourth route in the module would be behaviour
     * no client is told about, reachable and unreviewed.</p>
     */
    @Test
    @DisplayName("the three published paths are exactly the routes the two controllers declare")
    void publishedPathsAreExactlyTheRoutesTheControllersDeclare() {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) contract().get("paths");

        // WHY : Refactoring Rationale: the listing moved from PendingAuthController.BASE_PATH to that
        //   base plus SEARCH_PATH, and its method from GET to POST, because its REQUIRED account scope is
        //   an account identifier and a query string is part of the request line the load balancer writes
        //   into its access log before any application code runs. Composing the expectation from the
        //   controller's own constants rather than from a literal is what makes this assertion fail if
        //   only one of the two sides moves.
        assertThat(paths.keySet()).containsExactlyInAnyOrder(
                PendingAuthController.BASE_PATH + PendingAuthController.SEARCH_PATH,
                PendingAuthController.BASE_PATH + "/{key}",
                FraudController.FRAUD_PATH);

        assertThat(operationIdsOf(paths))
                .as("each published path declares exactly the operations the module serves")
                .containsExactlyInAnyOrder("listPendingAuthorizations", "getPendingAuthorization",
                        "setAuthorizationFraudState");
    }

    /**
     * Collects every operation identifier the document declares, across every path and method.
     *
     * @param paths the document's paths mapping; must not be {@code null}
     * @return the operation identifiers in document order, never {@code null}
     */
    private static List<String> operationIdsOf(Map<String, Object> paths) {
        List<String> identifiers = new java.util.ArrayList<>();
        for (Object pathItem : paths.values()) {
            if (!(pathItem instanceof Map<?, ?> methods)) {
                continue;
            }
            for (Object operation : methods.values()) {
                if (operation instanceof Map<?, ?> body && body.get("operationId") != null) {
                    identifiers.add(String.valueOf(body.get("operationId")));
                }
            }
        }
        return List.copyOf(identifiers);
    }

    /**
     * Confirms the pinned document version and the packaged contract cannot drift apart.
     */
    @Test
    @DisplayName("the pinned api-docs version matches the packaged contract's specification version")
    void pinnedApiDocsVersionMatchesThePackagedContract() {
        assertThat(String.valueOf(section(BASE_PROFILE, "springdoc", "api-docs").get("version")))
                .as("a pinned 3.0 generator beside a 3.1 contract reports drift that is not drift")
                .isEqualTo("openapi_3_1");
    }

    /**
     * Reads the served static locations from the packaged base profile.
     *
     * @return the configured locations in declaration order, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private List<String> staticLocations() {
        Object configured =
                section(BASE_PROFILE, "spring", "web", "resources").get("static-locations");
        assertThat(configured)
                .as("spring.web.resources.static-locations must be declared")
                .isInstanceOf(List.class);
        return List.copyOf((List<String>) configured);
    }

    /**
     * Reads the packaged contract the swagger view is pointed at.
     *
     * @return the parsed contract document, never {@code null}
     * @throws IllegalStateException if the contract is absent from the test class path or cannot be parsed
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> contract() {
        String path = String.valueOf(section(BASE_PROFILE, "springdoc", "swagger-ui").get("url"));
        try (InputStream document =
                ContractPublicationTest.class.getResourceAsStream("/openapi" + path)) {
            if (document == null) {
                throw new IllegalStateException("/openapi" + path + " is not on the test class path");
            }
            return (Map<String, Object>) new Yaml().load(document);
        } catch (java.io.IOException problem) {
            throw new IllegalStateException("/openapi" + path + " could not be read", problem);
        }
    }

    /**
     * Reads one nested mapping out of a packaged YAML profile.
     *
     * @param resource the class-path resource to read; must name a YAML document
     * @param path the mapping keys to descend, in order; must not be empty
     * @return the mapping at that path, never {@code null}
     * @throws IllegalStateException if the resource is absent from the test class path, or if any key on
     *     the path is missing or does not hold a mapping, either of which would mean this assertion was
     *     silently testing nothing
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String resource, String... path) {
        try (InputStream document = ContractPublicationTest.class.getResourceAsStream(resource)) {
            if (document == null) {
                throw new IllegalStateException(resource + " is not on the test class path");
            }
            Object current = new Yaml().load(document);
            StringBuilder walked = new StringBuilder();
            for (String key : path) {
                walked.append('/').append(key);
                if (!(current instanceof Map<?, ?> mapping) || !mapping.containsKey(key)) {
                    throw new IllegalStateException(resource + " has no mapping at " + walked);
                }
                current = ((Map<String, Object>) mapping).get(key);
            }
            if (!(current instanceof Map<?, ?>)) {
                throw new IllegalStateException(resource + walked + " is not a mapping");
            }
            return (Map<String, Object>) current;
        } catch (java.io.IOException problem) {
            throw new IllegalStateException(resource + " could not be read", problem);
        }
    }
}
