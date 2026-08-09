package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts that this service's two profile overlays read their configuration the same way -- from environment
 * variables the task definition sets, with no parameter path claimed by either -- and that neither overlay
 * carries a deployment value or relaxes what the base document closes.
 *
 * <p>Refactoring Rationale: this summary previously said the overlays read "from a parameter path whose
 * resolver and client are both on the classpath", which is the arrangement {@link
 * #noDocumentClaimsAParameterStoreLocation()} exists to REFUSE. The resolver and client genuinely are both
 * on the classpath, and {@link #theParameterStoreClientIsOnTheClasspath()} asserts it, but that pairing is why
 * a claimed location fails legibly rather than a reason to claim one. Stating it as the way configuration
 * arrives inverted the class's own conclusion in the first sentence a reader meets.</p>
 *
 * <h2>Purpose</h2>
 *
 * <p>Refactoring Rationale: the two overlays disagreed. Production imported
 * {@code optional:aws-parameterstore:/carddemo/<env>/authorization/} and development declined it, and every
 * rationale in the module -- the base document's, both overlays', and the POM's -- described a classpath
 * that had since changed: the parameter-store starter had been added, so the reason development gave for
 * declining (that no artifact supplied the SSM client the claimed prefix needs) had stopped being true. The
 * divergence meant a value delivered through the parameter subtree was exercised for the first time in
 * production. Separately, the development overlay relaxed the public health aggregate to
 * {@code when-authorized} with no roles, which authorizes any authenticated caller, so the dependency
 * detail naming the authorization database was available to any holder of a user-pool token.</p>
 *
 * <p>Assumptions: the overlays are read as YAML documents rather than through a started context, and that is
 * deliberate. What is under test is the CONFIGURATION the module ships -- whether the two documents agree,
 * and what each declares -- and a started context would resolve placeholders against whatever the test
 * environment supplies, so it would report the environment's answer rather than the document's.</p>
 *
 * <p>Alternatives Considered: asserting the imports by starting each profile and reading the resulting
 * environment. Rejected because an {@code optional:} parameter-store location silently contributes nothing
 * when no AWS account is reachable, which is the case on a build agent -- so a started context cannot tell
 * a declared import from an absent one, which is precisely the difference this class exists to police.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * at-clause; the convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
class ProfileConfigurationTopologyTest {

    /** The shared defaults document every profile overlay must name first. */
    private static final String SHARED_DEFAULTS = "classpath:/carddemo-common-defaults.yml";

    /**
     * The location prefix that must appear in NO document's import list.
     *
     * <p>Assumptions: this constant names something asserted ABSENT, not something read. It was documented
     * as "the parameter path this context reads", which described the withdrawn arrangement rather than the
     * one under test.</p>
     */
    private static final String PARAMETER_STORE_PREFIX = "aws-parameterstore:";


    /**
     * Loads one packaged configuration document.
     *
     * @param resource the classpath resource name, leading slash included
     * @return the parsed document; never {@code null}
     * @throws IOException if the resource cannot be read, which would mean the module was built without its
     *     own resources and no assertion here would be meaningful
     */
    private static Map<String, Object> document(String resource) throws IOException {
        try (InputStream stream = ProfileConfigurationTopologyTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("the packaged %s must be readable", resource).isNotNull();
            return new Yaml().load(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Reads the {@code spring.config.import} list from one document.
     *
     * @param resource the classpath resource name, leading slash included
     * @return the declared imports in declaration order; never {@code null}
     * @throws IOException if the resource cannot be read
     */
    @SuppressWarnings("unchecked")
    private static List<String> imports(String resource) throws IOException {
        Map<String, Object> spring = (Map<String, Object>) document(resource).get("spring");
        Map<String, Object> config = (Map<String, Object>) spring.get("config");
        Object declared = config.get("import");
        return declared instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(declared));
    }

    /**
     * Reads the health aggregate's detail setting from one document.
     *
     * @param resource the classpath resource name, leading slash included
     * @return the configured value, or {@code null} when the document declares none
     * @throws IOException if the resource cannot be read
     */
    @SuppressWarnings("unchecked")
    private static Object showDetails(String resource) throws IOException {
        Map<String, Object> management = (Map<String, Object>) document(resource).get("management");
        if (management == null) {
            return null;
        }
        Map<String, Object> endpoint = (Map<String, Object>) management.get("endpoint");
        Map<String, Object> health = endpoint == null ? null : (Map<String, Object>) endpoint.get("health");
        return health == null ? null : health.get("show-details");
    }

    /**
     * Verifies each overlay imports the shared defaults, first and alone.
     *
     * <p>Assumptions: the shared defaults are asserted to be FIRST rather than merely present. A profile's
     * import list replaces the base document's single import rather than extending it, so omitting the
     * defaults would drop the console pattern that carries the correlation identifier in that profile
     * alone -- and that is the profile where a request which cannot be traced across two services costs
     * the most to investigate.</p>
     *
     * <p>Assumptions: the list is asserted to hold NOTHING ELSE, and specifically to declare no
     * {@code aws-parameterstore:} location. This context reads no parameter subtree: every deployment
     * value reaches it as an environment variable the task definition sets, which is what the base
     * document's datasource, issuer and queue placeholders bind to. reference-service, the other context
     * whose only AWS artifact is the queue starter, takes the identical shape.</p>
     *
     * <p>Refactoring Rationale: these overlays each declared such a location and no longer do, and the
     * withdrawal is a startup correction rather than a simplification. The queue starter brings
     * {@code spring-cloud-aws-autoconfigure}, whose {@code spring.factories} registers a resolver for
     * the {@code aws-parameterstore} prefix, so the location was claimed here and the resolver then
     * built its own client -- a construction that resolves a region through the default provider chain.
     * This module deliberately declines to pin {@code spring.cloud.aws.region.static}, resolving region
     * and credentials from the task instead, so environment preparation raised
     * {@code SdkClientException} wherever the chain could not answer, for the dev and the prod profile
     * alike. {@code ProfileConfigImportTest} is the case that reproduces it.</p>
     *
     * <p>Assumptions: the {@code optional:} marker does not cover that failure, which is why the decision
     * had to be made about the location rather than about the marker. The marker suppresses a claimed
     * location holding no resource; it does not suppress a resolver that throws while building its
     * client.</p>
     *
     * <p>Alternatives Considered: keeping the location on both profiles and pinning the region to
     * {@code ${AWS_REGION}} the way account-service and batch-service do -- both of which declare the
     * location and both of which pin the region in their base document, so the pairing is the
     * repository's established shape for a context that READS a subtree. Rejected here on cost against
     * benefit: it adds a startup dependency on a resolvable region to a context that reads nothing from
     * the subtree, and it would stop a service for a missing environment variable whose every value is
     * already delivered by another route. The lower-risk option is taken.</p>
     *
     * @param profile the overlay to check
     * @throws IOException if the resource cannot be read
     */
    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("each overlay imports the shared defaults, first and alone")
    void eachOverlayImportsTheSharedDefaultsAlone(String profile) throws IOException {
        List<String> declared = imports("/application-" + profile + ".yml");

        assertThat(declared).containsExactly(SHARED_DEFAULTS);
        assertThat(declared)
                .as("a claimed parameter-store location aborts environment preparation without a "
                        + "resolvable region, and the optional marker does not cover it")
                .noneMatch(entry -> entry.contains(PARAMETER_STORE_PREFIX));
    }

    /**
     * Verifies no document in this module claims a parameter-store location.
     *
     * <p>Assumptions: the base document is checked as well as the two overlays, because the resolver is
     * registered by the queue starter for the whole module and a location claimed in any document reaches
     * it. Checking only the overlays would let the same failure return through the document they inherit
     * from, which is the one place a reader adding a shared value would naturally put it.</p>
     *
     * @throws IOException if a resource cannot be read
     */
    @Test
    @DisplayName("no document in this module claims a parameter-store location")
    void noDocumentClaimsAParameterStoreLocation() throws IOException {
        for (String resource : List.of("/application.yml", "/application-dev.yml",
                "/application-prod.yml")) {
            assertThat(imports(resource))
                    .as("%s must claim no parameter-store location", resource)
                    .noneMatch(entry -> entry.contains(PARAMETER_STORE_PREFIX));
        }
    }

    /**
     * Verifies both overlays read their configuration through the same topology.
     *
     * <p>Assumptions: the two lists are compared after the environment segment is normalised away, because
     * the segment is the ONE thing that must differ and everything else is the thing that must not. Comparing
     * the raw strings would fail on the difference the design requires; comparing nothing would allow the
     * divergence the finding reported.</p>
     *
     * @throws IOException if either resource cannot be read
     */
    @Test
    @DisplayName("both overlays read configuration through one topology, differing only in the environment")
    void bothOverlaysShareOneTopology() throws IOException {
        List<String> development = imports("/application-dev.yml").stream()
                .map(entry -> entry.replace("dev", "<env>")).toList();
        List<String> production = imports("/application-prod.yml").stream()
                .map(entry -> entry.replace("prod", "<env>")).toList();

        assertThat(development)
                .as("a value delivered through the parameter subtree must be exercised in development "
                        + "before it is relied on in production")
                .isEqualTo(production);
    }

    /**
     * Verifies the resolver and its client stay paired, so a location declared later cannot half-resolve.
     *
     * <p>Assumptions: the class is looked up by name rather than imported, because importing it would make
     * this test pass or fail at compile time and the property under test is a RUNTIME classpath property.</p>
     *
     * <p>Assumptions: this pairing is asserted even though no document in this module now declares such a
     * location, which the two cases above establish. The resolver arrives whether or not anything wants
     * it -- the queue starter brings {@code spring-cloud-aws-autoconfigure}, and its
     * {@code spring.factories} registers the {@code aws-parameterstore:} prefix for the whole module --
     * so the prefix is claimable at all times and the only question is what happens to a claim. With the
     * client present the answer is a clear region or credential failure naming the provider chain; with
     * it absent the answer is {@code NoClassDefFoundError} from inside environment preparation, before
     * any logging is configured. The {@code optional:} marker covers neither, because it suppresses a
     * location that is not found rather than a resolver that cannot build itself.</p>
     *
     * <p>Trade-offs: the accepted cost is one declared dependency this module reads nothing through. It
     * is accepted because removing it would leave the resolver registered and unaccompanied, which turns
     * the most likely future edit -- a reader adding a shared value under the module's own subtree --
     * from a legible misconfiguration into a class-loading crash.</p>
     */
    @Test
    @DisplayName("the parameter-store resolver and its client stay paired on the classpath")
    void theParameterStoreClientIsOnTheClasspath() {
        assertThat(classPresent("software.amazon.awssdk.services.ssm.SsmClient"))
                .as("spring-cloud-aws-starter-parameter-store must remain declared: the queue starter "
                        + "already brings the resolver that claims the aws-parameterstore prefix")
                .isTrue();
        assertThat(classPresent("io.awspring.cloud.autoconfigure.config.parameterstore"
                + ".ParameterStoreConfigDataLocationResolver"))
                .as("the resolver that claims the location prefix must be present alongside its client")
                .isTrue();
    }

    /**
     * Reports whether a class can be loaded by name.
     *
     * @param name the fully qualified class name
     * @return {@code true} when the class is loadable, {@code false} when it is absent
     */
    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, ProfileConfigurationTopologyTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            // WHY : Assumptions: absence is a RESULT here and not a failure, which is why it is answered
            //       with a value rather than propagated. The caller asserts on presence and states what the
            //       absence would mean, so rethrowing would report a class-loading fault where the test
            //       means to report a missing dependency.
            return false;
        }
    }

    /**
     * Verifies no overlay relaxes the health aggregate the base document closes.
     *
     * <p>Assumptions: {@code never} is required rather than merely "not always", because the intermediate
     * value is the one that was wrong here. With no {@code roles} key the framework authorizes any
     * authenticated caller, so {@code when-authorized} disclosed the dependency detail to every holder of a
     * user-pool token -- and a roles key cannot repair it, since the framework's role check prepends its
     * {@code ROLE_} prefix while this system's authorities are the provider's group names verbatim.</p>
     *
     * @param profile the overlay to check
     * @throws IOException if the resource cannot be read
     */
    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("no overlay relaxes the public health aggregate the base document closes")
    void noOverlayRelaxesThePublicHealthAggregate(String profile) throws IOException {
        Object configured = showDetails("/application-" + profile + ".yml");

        assertThat(configured)
                .as("the health subtree is granted permitAll for the probe, so any detail there is "
                        + "reachable without a credential")
                .isIn(null, "never");
    }

    /**
     * Verifies the base document closes the aggregate too, so the overlays are agreeing with it.
     *
     * @throws IOException if the resource cannot be read
     */
    @Test
    @DisplayName("the base document closes the health aggregate that both overlays agree with")
    void theBaseDocumentClosesTheHealthAggregate() throws IOException {
        assertThat(showDetails("/application.yml")).isEqualTo("never");
    }

    /**
     * Verifies neither overlay carries a deployment value of any kind.
     *
     * <p>Assumptions: the forbidden keys are named individually rather than matched by a pattern over
     * values, because a pattern over values cannot tell a hostname from a pool size and would either miss
     * the disclosure or refuse the sizing these overlays exist to carry. Each key below is one an overlay
     * could plausibly be tempted to pin, and each would be a deployment value committed to the source
     * tree.</p>
     *
     * @param profile the overlay to check
     * @throws IOException if the resource cannot be read
     */
    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("neither overlay pins a datasource, an issuer, a queue or a key identifier")
    void neitherOverlayPinsADeploymentValue(String profile) throws IOException {
        Map<String, Object> overlay = document("/application-" + profile + ".yml");

        assertThat(flatten(overlay))
                .as("deployment values reach the task as environment variables the task definition sets "
                        + "from Parameter Store and from Secrets Manager container secrets")
                .doesNotContainKeys(
                        "spring.datasource.url",
                        "spring.datasource.username",
                        "spring.datasource.password",
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "carddemo.messaging.pauth-request-queue",
                        "carddemo.messaging.pauth-reply-queue",
                        "carddemo.messaging.hmac-key",
                        "carddemo.internal-identity.authorization-signing-key",
                        "carddemo.account-context.base-url");
    }

    /**
     * Flattens a configuration document into dotted keys.
     *
     * @param tree the parsed document
     * @return every leaf keyed by its dotted path; never {@code null}
     */
    private static Map<String, Object> flatten(Map<String, Object> tree) {
        Map<String, Object> flat = new java.util.TreeMap<>();
        flatten("", tree, flat);
        return flat;
    }

    /**
     * Walks one subtree, recording each leaf under its dotted path.
     *
     * @param prefix the path accumulated so far, empty at the root
     * @param node the subtree to walk
     * @param flat the accumulating map, written to in place
     */
    private static void flatten(String prefix, Object node, Map<String, Object> flat) {
        if (node instanceof Map<?, ?> children) {
            for (Map.Entry<?, ?> child : children.entrySet()) {
                String name = prefix.isEmpty()
                        ? String.valueOf(child.getKey())
                        : prefix + "." + child.getKey();
                flatten(name, child.getValue(), flat);
            }
        } else if (!prefix.isEmpty()) {
            flat.put(prefix, node);
        }
    }
}
