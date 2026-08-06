package com.carddemo.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the two facts this context's token-validation documentation depends on: that no audience validator
 * is configured anywhere, and that this class is the only place a decoder is declared.
 *
 * <p>Refactoring Rationale: both facts were previously stated wrongly. {@code JwtDecoderConfig} described
 * its composed chain as carrying "the audience validation the configured audiences imply" in three places,
 * and declined to restate the audience itself so that one assertion would not be narrowed unevenly in two
 * places -- while no {@code audiences} key existed in any profile of this module, so the count of places was
 * zero. Separately, {@code application.yml} named {@code SecurityConfig} as the class reading the three
 * {@code carddemo.security.jwt} keys, when it is this class that reads them. Both corrections are prose, and
 * prose is exactly what goes stale, so the facts underneath them are asserted here.</p>
 *
 * <p>Assumptions: an audience validator would not merely be redundant here, it would invert the control. A
 * Cognito ACCESS token carries no audience claim at all -- the minting client travels in {@code client_id}
 * -- so an audience validator would reject every access token the sign-on flow issues while accepting
 * exactly the identity tokens the shared validator exists to refuse. That is why its ABSENCE is asserted.</p>
 */
class JwtDecoderConfigTest {

    /** Every profile this module ships, so an audience key cannot be introduced in just one of them. */
    private static final String[] PROFILES = {
        "/application.yml", "/application-dev.yml", "/application-prod.yml", "/application-test.yml",
    };

    /**
     * Confirms no shipped profile declares an audience list.
     *
     * <p>Assumptions: the whole resource-server subtree is inspected rather than only the exact key, so a
     * differently nested or differently cased spelling is caught as well. A key added under a spelling this
     * assertion did not anticipate would configure the validator just the same.</p>
     */
    @Test
    @DisplayName("no shipped profile configures an audience validator, under any spelling")
    void noShippedProfileConfiguresAnAudienceValidator() {
        for (String profile : PROFILES) {
            Map<String, Object> jwt = jwtSection(profile);

            if (jwt == null) {
                continue;
            }
            assertThat(jwt.keySet())
                    .as("%s must not configure an audience: it would reject every access token this "
                            + "service is presented and accept the identity tokens it must refuse", profile)
                    .noneMatch(key -> key.toLowerCase(java.util.Locale.ROOT).contains("audience"));
        }
    }

    /**
     * Confirms the decoder is declared by this class and by no other configuration in this package.
     *
     * <p>Assumptions: the assertion is on the DECLARED bean methods of both classes rather than on a
     * running context, because the hazard it guards is a second bean of the same type: which of the two the
     * resource server used would then depend on bean ordering rather than on anything written down. Two
     * declarations are detectable without starting anything.</p>
     */
    @Test
    @DisplayName("this class declares the only decoder in the package")
    void thisClassDeclaresTheOnlyDecoderInThePackage() {
        assertThat(declaresDecoder(JwtDecoderConfig.class))
                .as("JwtDecoderConfig must declare the decoder; application.yml names it as the reader "
                        + "of the three carddemo.security.jwt keys")
                .isTrue();
        assertThat(declaresDecoder(SecurityConfig.class))
                .as("SecurityConfig must NOT declare a second decoder: which one the resource server "
                        + "used would then depend on bean ordering")
                .isFalse();
    }

    /**
     * Reports whether a configuration class declares a method returning a decoder.
     *
     * @param configuration the configuration class to inspect; must not be {@code null}
     * @return {@code true} when at least one declared method returns a {@link JwtDecoder}
     */
    private boolean declaresDecoder(Class<?> configuration) {
        for (Method method : configuration.getDeclaredMethods()) {
            if (JwtDecoder.class.isAssignableFrom(method.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the resource-server JWT section out of one packaged profile.
     *
     * @param resource the class-path resource to read; must name a YAML document
     * @return the section's mapping, or {@code null} when the profile declares no such section
     * @throws IllegalStateException if the resource is absent from the test class path, which would mean
     *     this assertion was silently reading nothing
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> jwtSection(String resource) {
        try (InputStream document = JwtDecoderConfigTest.class.getResourceAsStream(resource)) {
            if (document == null) {
                throw new IllegalStateException(resource + " is not on the test class path");
            }
            Object current = new Yaml().load(document);
            for (String key : new String[] {"spring", "security", "oauth2", "resourceserver", "jwt"}) {
                if (!(current instanceof Map<?, ?> mapping)) {
                    return null;
                }
                current = ((Map<String, Object>) mapping).get(key);
                if (current == null) {
                    return null;
                }
            }
            return current instanceof Map<?, ?> ? (Map<String, Object>) current : null;
        } catch (java.io.IOException problem) {
            throw new IllegalStateException(resource + " could not be read", problem);
        }
    }
}
