package com.carddemo.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>Refactoring Rationale: this class exists because the contract did not exist at all while two
 * shipped artifacts said it did. This module's {@code pom.xml} stated that
 * {@code src/main/resources/openapi/reporting-api.yaml} is the cross-boundary contract that
 * {@code ui/src/api/reporting.ts} is written against, and {@link OpenApiConfig} named the same file as
 * the contract of record and went as far as declining to state an operation count because there was no
 * list to count. Neither file was wrong about intent and both were wrong about fact. Authoring the
 * contract closes half of that gap; this class closes the other half, because a contract that is
 * packaged but unserved is indistinguishable from one that was never written, from any caller's point
 * of view.</p>
 *
 * <p>Assumptions: every fact is read from the shipped artifacts -- the profile from the class path,
 * the contract from the class path -- so the assertions are about what is packaged rather than about
 * literals restated here. The one literal is the location constant below, which is the string the
 * framework itself matches on.</p>
 *
 * <p>Alternatives Considered: asserting only that the resource is on the class path. Rejected because
 * that was already true before this work and told nobody anything: the framework serves static content
 * only from the locations {@code spring.web.resources.static-locations} names, so a resource present in
 * the jar and absent from that list answers 404 at every URL while every individual artifact stays
 * valid on its own -- a YAML list is a valid list, and a class-path resource is a class-path resource.
 * Only the relationship between them can be wrong, so the relationship is what is asserted.</p>
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
     * default list rather than extending it. Dropping {@code classpath:/META-INF/resources/} would stop
     * the browser view's assets being served at all, since they ship inside a webjar under exactly that
     * path, and that failure presents as a broken page rather than as a missing configuration line.</p>
     */
    @Test
    @DisplayName("the openapi class-path folder is served, and the four framework defaults are preserved")
    void openapiClassPathFolderIsServedAndDefaultsArePreserved() {
        List<String> locations = staticLocations();

        assertThat(locations)
                .as("the packaged contract is unreachable unless this location is served")
                .contains(OPENAPI_LOCATION);
        assertThat(locations)
                .as("this key replaces the defaults, so all four must be restated")
                .contains("classpath:/META-INF/resources/", "classpath:/resources/",
                        "classpath:/static/", "classpath:/public/");
    }

    /**
     * Confirms the browser view names a contract that is genuinely packaged under the served location.
     *
     * <p>Assumptions: the named document is OPENED rather than merely compared as a string, so this
     * asserts reachability rather than agreement between two spellings. A URL naming a file that is not
     * packaged would satisfy any assertion that only compared names.</p>
     */
    @Test
    @DisplayName("the swagger view names a contract that is packaged under the served location")
    void swaggerViewNamesAContractThatIsPackagedUnderTheServedLocation() {
        Map<String, Object> swaggerUi = section(BASE_PROFILE, "springdoc", "swagger-ui");
        String url = String.valueOf(swaggerUi.get("url"));

        assertThat(url)
                .as("the view must name a document under the served location, at its root")
                .startsWith("/")
                .doesNotContain("..");
        assertThat(contract())
                .as("the named document must be packaged, not merely named")
                .isNotEmpty();
        assertThat(swaggerUi.get("disable-swagger-default-url"))
                .as("the bundled sample must not share the selector with the contract of record")
                .isEqualTo(true);
    }

    /**
     * Confirms the packaged contract is the hand-authored 3.1 document, not an empty generated stand-in.
     *
     * <p>Assumptions: the specification version and the presence of at least one published path are
     * asserted together, because those two are what distinguish the authored contract from the document
     * the annotations produce at this checkpoint -- which carries an empty paths object, since this
     * module's controllers are authored at a later index.</p>
     */
    @Test
    @DisplayName("the packaged contract is an openapi 3.1 document declaring at least one path")
    void packagedContractIsAnOpenApi31DocumentDeclaringAtLeastOnePath() {
        Map<String, Object> document = contract();

        assertThat(String.valueOf(document.get("openapi")))
                .as("the served document and the pinned generator must agree on the specification")
                .startsWith("3.1");
        assertThat(document.get("paths"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .as("an empty paths object would mean a generated stand-in was packaged by mistake")
                .isNotEmpty();
    }

    /**
     * Confirms the pinned generated-document version and the packaged contract cannot drift apart.
     *
     * <p>Assumptions: this matters more here than it looks. This contract expresses six nullable
     * members as {@code oneOf} with {@code type: 'null'}, which is a 3.1 construct with no 3.0
     * spelling, so a generator left on its 3.0 default would report drift against every one of them and
     * the generated document would stop being a usable check on the authored one.</p>
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
     * Reads the packaged contract the browser view is pointed at.
     *
     * @return the parsed contract document, never {@code null}
     * @throws IllegalStateException if the contract is absent from the test class path or cannot be
     *     parsed
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
