package com.carddemo.reference.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies that the contract this module publishes is actually REACHABLE where it is meant to be, and
 * that it is not claimed to be reachable where it is not.
 *
 * <p>Purpose: two independent halves have to agree before a packaged contract can be fetched, and each
 * half is valid configuration on its own. {@code spring.web.resources.static-locations} decides whether
 * the file is served at all, and the filter chain decides whether a request for it is admitted; a
 * mismatch in either direction produces a 404 or a 403 that reads as a missing service rather than as a
 * missing configuration line. This class reads both halves out of the shipped artifacts and requires
 * them to line up.</p>
 *
 * <p>Refactoring Rationale: this class was authored because a review found the third relationship --
 * between the chain's grant and the DEPLOYED ROUTE -- documented incorrectly and covered by nothing. The
 * chain grants five documentation patterns and the module's Javadoc claimed a token-bearing caller could
 * fetch them, without recording that neither environment root routes any of those patterns to this
 * service. The grant is correct and stays; what was wrong was the reachability claim, and what was
 * missing was any test that would notice either side moving. Both are addressed here: the grant is
 * checked against the addresses {@code application.yml} pins, and the edge boundary is pinned in the one
 * direction that is safe to assert from inside a service module.</p>
 *
 * <p>Assumptions: every fact is read from the packaged class path -- the profile and the contract both --
 * so the assertions describe what is shipped rather than literals restated here. The two exceptions are
 * the served-location constant, which is the string the framework itself matches on, and the edge route
 * prefix, which is a deployment fact this module cannot read and which is therefore named with the file
 * that owns it.</p>
 */
class ContractPublicationTest {

    /**
     * The path matcher whose semantics the chain's request matchers carry.
     *
     * <p>Assumptions: the chain's patterns are Ant patterns, so they are matched with the framework's own
     * matcher rather than compared as strings. Comparing spellings would pass for the exact addresses and
     * say nothing about either subtree, which is where the two configured paths actually expand.</p>
     */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** The class-path location that must be served for the packaged contract to be reachable. */
    private static final String OPENAPI_LOCATION = "classpath:/openapi/";

    /** The base profile, packaged from {@code src/main/resources}. */
    private static final String BASE_PROFILE = "/application.yml";

    /**
     * The only path prefix either environment root forwards to this service's target group.
     *
     * <p>Assumptions: this is a deployment fact and it is restated here because a service module cannot
     * read the Terraform that owns it. It is the {@code reference} entry of {@code local.online_services}
     * in {@code infra/envs/dev/main.tf} and in the prod root, whose {@code paths} list is exactly
     * {@code /api/v1/reference} and {@code /api/v1/reference/*}. If that entry ever gains a documentation
     * route, this constant and the assertion below are what have to be revisited together.</p>
     */
    private static final String EDGE_ROUTE_PREFIX = "/api/v1/reference";

    /**
     * Confirms the class-path folder holding the contract is among the served static locations.
     *
     * <p>Assumptions: the four framework defaults are asserted as well, because this key REPLACES the
     * default list rather than extending it. Dropping {@code classpath:/META-INF/resources/} would stop
     * the interactive view's assets being served at all, since they ship inside a webjar under exactly
     * that path, and that failure would look like a broken page rather than a missing configuration
     * line.</p>
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
        } catch (IOException problem) {
            throw new IllegalStateException("the packaged contract could not be read", problem);
        }

        assertThat(swaggerUi.get("disable-swagger-default-url"))
                .as("the bundled sample definition must be removed from the selector, or a reader can "
                        + "silently end up reading a document that describes nothing in this service")
                .isEqualTo(Boolean.TRUE);
    }

    /**
     * Confirms the packaged contract is the hand-authored 3.1 document, not an empty or generated stand-in.
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
     * Confirms the filter chain grants every address these keys pin, so a configured document is a
     * reachable one.
     *
     * <p>Purpose: this closes the half of the reachability problem that lives inside the service. The
     * chain ends in a deny-all catch-all, so an address that no rule names answers 403 to a valid token of
     * either group -- which would leave the packaged contract served from a static location, named by the
     * view, and fetchable by nobody.</p>
     *
     * <p>Assumptions: the addresses are READ from the profile and matched against the chain's own
     * published pattern list, which is what makes this a drift check rather than a spelling comparison:
     * moving {@code springdoc.api-docs.path} or {@code springdoc.swagger-ui.path} without extending the
     * chain fails here instead of returning a refusal to whatever fetches the document.</p>
     *
     * <p>Assumptions: the two DERIVED paths are asserted as well as the three configured ones. The view
     * fetches its own settings from a child of the document path and loads its assets from the webjar
     * subtree, so a grant covering only the configured addresses would leave the page loading and then
     * failing to render -- a harder outcome to diagnose than a refusal.</p>
     */
    @Test
    @DisplayName("the filter chain grants every documentation address these keys pin")
    void filterChainGrantsEveryAddressTheseKeysPin() {
        Map<String, Object> apiDocs = section(BASE_PROFILE, "springdoc", "api-docs");
        Map<String, Object> swaggerUi = section(BASE_PROFILE, "springdoc", "swagger-ui");
        String documentPath = String.valueOf(apiDocs.get("path"));
        String viewPath = String.valueOf(swaggerUi.get("path"));
        String contractPath = String.valueOf(swaggerUi.get("url"));
        List<String> granted = SecurityConfig.documentationPaths();

        for (String pinned : List.of(documentPath, viewPath, contractPath,
                documentPath + "/swagger-config", "/swagger-ui/index.html")) {
            assertThat(granted.stream().anyMatch(pattern -> MATCHER.match(pattern, pinned)))
                    .as("%s is configured to be served but no chain rule grants it, so it answers the"
                            + " deny-all catch-all", pinned)
                    .isTrue();
        }
    }

    /**
     * Pins the documented boundary: these addresses are served in-VPC and are NOT routed at the edge.
     *
     * <p>Purpose: this is the assertion the review found missing. The chain's grant and the deployed route
     * map are owned by different files in different languages, so nothing connected them and the Javadoc's
     * reachability claim drifted from the deployment without any build noticing.</p>
     *
     * <p>Assumptions: the safe direction to assert from inside a service module is that none of the
     * granted documentation patterns lies under the one prefix the edge forwards. That is a property of
     * this module's own configuration, so it is checkable here; whether the Terraform still forwards only
     * that prefix is a property of the Terraform, and it is named on {@link #EDGE_ROUTE_PREFIX} rather
     * than guessed at. Asserting this direction is what makes the two facts fail together: if someone
     * relocates a documentation path under {@code /api/v1/reference/} to make it edge-reachable, this test
     * fails and sends them to the Javadoc that explains why the route is deliberately absent, instead of
     * letting an interactive console appear at the internet edge as a side effect of a path change.</p>
     */
    @Test
    @DisplayName("no granted documentation path lies under the prefix the edge routes to this service")
    void noGrantedDocumentationPathLiesUnderTheEdgeRoutedPrefix() {
        for (String pattern : SecurityConfig.documentationPaths()) {
            assertThat(pattern.startsWith(EDGE_ROUTE_PREFIX))
                    .as("%s lies under %s, which both environment roots forward from the public edge."
                            + " Serving an API description -- and, for the view, a request-issuing console"
                            + " -- at the edge is a wider surface than the deployment accepts for a"
                            + " document already committed to this repository. Either revisit the"
                            + " reachability rationale on SecurityConfig.DOCUMENTATION_PATHS together with"
                            + " the route map in infra/envs/*/main.tf, or keep the path off that prefix",
                            pattern, EDGE_ROUTE_PREFIX)
                    .isFalse();
        }
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
        } catch (IOException problem) {
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
        } catch (IOException problem) {
            throw new IllegalStateException(resource + " could not be read", problem);
        }
    }
}
