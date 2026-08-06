package com.carddemo.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Verifies the three common tags this configuration contributes, and pins the two behaviours a
 * deployer has to know about that are invisible from the tag contract alone.
 *
 * <p>Assumptions: every test here uses a plain {@link SimpleMeterRegistry} and constructs the
 * configuration directly rather than starting an application context. The class documentation records
 * that compiling against {@code micrometer-core} alone is a deliberate property of the design, so
 * exercising it without a context is the test that demonstrates that property rather than merely
 * relying on it.</p>
 *
 * <p>Assumptions: the sixteen-digit value used in the masking test is assembled from a repeated digit
 * rather than written as a literal, matching the convention the masker's own test states, so no
 * fixture in this repository is a card-number-shaped constant a scanner would have to triage.</p>
 */
class MetricsConfigTest {

    /** The service value the fixtures label with, one of the eight migrated context names. */
    private static final String SERVICE = "auth-service";

    /** The environment value the fixtures label with. */
    private static final String ENVIRONMENT = "prod";

    /** The version value the fixtures label with, standing in for a deployed image identity. */
    private static final String VERSION = "1.0.0";

    /**
     * Registers a meter against a registry filtered by the supplied configuration and returns its tags.
     *
     * @param configuration the configuration whose filter is installed before the meter is registered,
     *     a {@link MetricsConfig}
     * @param meterName the meter name to register, a {@link String}
     * @param meterOwnTags alternating key and value strings the meter declares for itself, which may be
     *     empty
     * @return the resulting tag set as a {@link Map} keyed by tag name
     */
    private static Map<String, String> tagsOf(
            MetricsConfig configuration, String meterName, String... meterOwnTags) {
        // WHY : Assumptions: the filter is installed BEFORE the meter is created because a filter is
        //       consulted at registration. Installing it afterwards is a real Micrometer behaviour but
        //       a different one, and mixing the two orderings in one helper would make every assertion
        //       below depend on an ordering the caller cannot see.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(configuration.commonTagsMeterFilter());
        Counter counter = Counter.builder(meterName).tags(meterOwnTags).register(registry);
        return counter.getId().getTags().stream()
            .collect(Collectors.toMap(io.micrometer.core.instrument.Tag::getKey,
                io.micrometer.core.instrument.Tag::getValue));
    }

    /**
     * Confirms the closed set of exactly three tags lands on a meter that declares none of its own.
     */
    @Test
    @DisplayName("a plain meter carries exactly the three common tags and no others")
    void plainMeterCarriesExactlyTheThreeCommonTags() {
        Map<String, String> tags = tagsOf(new MetricsConfig(SERVICE, ENVIRONMENT, VERSION), "carddemo.plain");

        assertThat(tags).containsOnly(
            org.assertj.core.api.Assertions.entry(MetricsConfig.SERVICE_TAG, SERVICE),
            org.assertj.core.api.Assertions.entry(MetricsConfig.ENVIRONMENT_TAG, ENVIRONMENT),
            org.assertj.core.api.Assertions.entry(MetricsConfig.VERSION_TAG, VERSION));
    }

    /**
     * Confirms a meter's own tag wins over the common tag of the same key, for each of the three keys.
     *
     * <p>Assumptions: this pins the direction of precedence, which the class documentation records as
     * the opposite of what a reader may expect from a mechanism described as unconditional. The
     * direction decides whether the three keys are reserved at every call site, so it is asserted for
     * all three keys rather than sampled on one.</p>
     *
     * @param collidingKey the tag key the meter declares for itself, a {@link String}
     */
    @ParameterizedTest
    @CsvSource({"service", "environment", "version"})
    @DisplayName("a meter's own tag wins over the common tag of the same key")
    void meterOwnTagWinsOverTheCommonTag(String collidingKey) {
        Map<String, String> tags = tagsOf(
            new MetricsConfig(SERVICE, ENVIRONMENT, VERSION),
            "carddemo.collide." + collidingKey,
            collidingKey, "declared-at-the-call-site");

        assertThat(tags).hasSize(3);
        assertThat(tags).containsEntry(collidingKey, "declared-at-the-call-site");
    }

    /**
     * Confirms every colliding key resolves to the call site's value when all three collide at once.
     */
    @Test
    @DisplayName("all three keys colliding at once each resolve to the meter's own value")
    void everyCollidingKeyResolvesToTheMeterOwnValue() {
        Map<String, String> tags = tagsOf(
            new MetricsConfig(SERVICE, ENVIRONMENT, VERSION),
            "carddemo.collide.all",
            MetricsConfig.SERVICE_TAG, "own-service",
            MetricsConfig.ENVIRONMENT_TAG, "own-environment",
            MetricsConfig.VERSION_TAG, "own-version");

        assertThat(tags).containsOnly(
            org.assertj.core.api.Assertions.entry(MetricsConfig.SERVICE_TAG, "own-service"),
            org.assertj.core.api.Assertions.entry(MetricsConfig.ENVIRONMENT_TAG, "own-environment"),
            org.assertj.core.api.Assertions.entry(MetricsConfig.VERSION_TAG, "own-version"));
    }

    /**
     * Pins that operator-supplied tag values are emitted verbatim and are not masked or redacted.
     *
     * <p>Assumptions: this asserts a LIMITATION deliberately, so the contract a deployer relies on is
     * build-enforced rather than inferred from the absence of a call. The class documentation records
     * why no detector is applied: all three values are deployment identities drawn from small closed
     * sets, so a detector would guess at a shape it cannot know, and both failure directions are worse
     * than trusting the input.</p>
     *
     * <p>Trade-offs: pinning this costs a failing test if masking is ever introduced here, which is the
     * intent -- introducing it silently would destroy the attribution the tags exist to provide for any
     * legitimate value the detector happened to match.</p>
     */
    @Test
    @DisplayName("an operator-supplied tag value is emitted verbatim and is never masked")
    void operatorSuppliedTagValuesAreEmittedVerbatim() {
        String cardNumberShapedValue = "4".repeat(1) + "1".repeat(11) + "2345";
        String secretShapedValue = "sk-live-not-a-real-credential";

        Map<String, String> tags = tagsOf(
            new MetricsConfig(cardNumberShapedValue, ENVIRONMENT, secretShapedValue),
            "carddemo.verbatim");

        assertThat(tags).containsEntry(MetricsConfig.SERVICE_TAG, cardNumberShapedValue);
        assertThat(tags).containsEntry(MetricsConfig.VERSION_TAG, secretShapedValue);
        assertThat(tags.get(MetricsConfig.SERVICE_TAG)).doesNotContain("*");
    }

    /**
     * Confirms a value the deployment did not usefully supply becomes the documented fallback.
     *
     * @param supplied the raw value under test, which the CSV source may leave absent to mean
     *     {@code null}
     */
    @ParameterizedTest
    @CsvSource(value = {"NULL", "''", "'   '", "'\t'"}, nullValues = "NULL")
    @DisplayName("a blank, empty, whitespace or absent value becomes the documented fallback")
    void unsuppliedValuesBecomeTheDocumentedFallback(String supplied) {
        Map<String, String> tags = tagsOf(
            new MetricsConfig(supplied, supplied, supplied), "carddemo.unsupplied");

        assertThat(tags).containsOnly(
            org.assertj.core.api.Assertions.entry(MetricsConfig.SERVICE_TAG, MetricsConfig.UNSPECIFIED),
            org.assertj.core.api.Assertions.entry(
                MetricsConfig.ENVIRONMENT_TAG, MetricsConfig.UNSPECIFIED),
            org.assertj.core.api.Assertions.entry(MetricsConfig.VERSION_TAG, MetricsConfig.UNSPECIFIED));
    }

    /**
     * Confirms the filter is consulted when a meter is registered rather than when the filter is built.
     *
     * <p>Assumptions: the class documentation states universality is a property of the filter and not
     * of bean-creation order, and this is the observable form of that statement. A meter registered
     * before the filter is installed carries no common tags, which is why the framework installs
     * filters during registry post-processing and why no meter in these services is created earlier.</p>
     */
    @Test
    @DisplayName("the filter applies to meters registered after it, not to ones registered before")
    void filterAppliesAtRegistrationTime() {
        MetricsConfig configuration = new MetricsConfig(SERVICE, ENVIRONMENT, VERSION);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        Counter registeredBefore = registry.counter("carddemo.before.filter");
        registry.config().meterFilter(configuration.commonTagsMeterFilter());
        Counter registeredAfter = registry.counter("carddemo.after.filter");

        Function<Meter, Integer> tagCount = meter -> meter.getId().getTags().size();
        assertThat(tagCount.apply(registeredBefore)).isZero();
        assertThat(tagCount.apply(registeredAfter)).isEqualTo(3);
    }

    /**
     * Confirms the published tag names and the fallback value are the exact strings the contract names.
     *
     * <p>Assumptions: these four constants are read by dashboards, alert definitions and queries that
     * live outside this repository, so a rename is a breaking change to an external consumer rather
     * than an internal detail. Asserting the literal values makes such a rename fail here first.</p>
     */
    @Test
    @DisplayName("the published tag names and the fallback value are fixed strings")
    void publishedNamesAreFixedStrings() {
        assertThat(MetricsConfig.SERVICE_TAG).isEqualTo("service");
        assertThat(MetricsConfig.ENVIRONMENT_TAG).isEqualTo("environment");
        assertThat(MetricsConfig.VERSION_TAG).isEqualTo("version");
        assertThat(MetricsConfig.UNSPECIFIED).isEqualTo("unspecified");
        assertThat(MetricsConfig.SERVICE_PROPERTY).isEqualTo("spring.application.name");
        assertThat(MetricsConfig.ENVIRONMENT_PROPERTY).isEqualTo("carddemo.environment");
        assertThat(MetricsConfig.VERSION_PROPERTY).isEqualTo("carddemo.version");
    }
}
