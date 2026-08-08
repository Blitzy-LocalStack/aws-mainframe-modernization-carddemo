package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.mapper.CustomerMapper.CustomerIdentifierProtection;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Proves the protected-identifier port the customer mapper requires is satisfiable, and what it stores.
 *
 * <p>Purpose: the mapper is a {@code @Component} whose constructor requires
 * {@link CustomerIdentifierProtection}, and no implementation of that port existed anywhere in the
 * reactor. Every context refresh that scanned the mapper package therefore failed with
 * {@code NoSuchBeanDefinitionException}, and the module's build did not notice: its start-up test drives
 * an {@code ApplicationContextRunner} over the AWS autoconfigurations only and never scans that package,
 * so the missing bean was invisible to a green build.</p>
 *
 * <p>Refactoring Rationale: the first case below is therefore the REGRESSION GUARD, and it is written to
 * scan the mapper package explicitly rather than to assert the bean in isolation. Asserting only that the
 * configuration publishes a bean would pass even if the mapper were later changed to require a second
 * port that nothing supplied -- which is the same defect again. Scanning is what makes the case fail for
 * the same reason the deployment would.</p>
 *
 * <p>Assumptions: the key-management client is SUBSTITUTED throughout, which the configuration allows
 * because both of its beans are conditional on no bean of their type already existing. No case here
 * reaches a key, an account or a network, so the encipherment's framing is assertable in an offline
 * build.</p>
 */
class CustomerIdentifierProtectionConfigTest {

    /** The alias the test profile names; it resolves nowhere and is never used as a key. */
    private static final String TEST_KEY_PROPERTY =
            "carddemo.security.customer-identifier.key-id=alias/carddemo-customer-identifier-test";

    /** The wrapped data key the substituted client returns, whose length the framing must carry. */
    private static final byte[] WRAPPED_KEY = "wrapped-data-key-bytes".getBytes(StandardCharsets.UTF_8);

    /** The data key material the substituted client returns, at the length the key spec declares. */
    private static final byte[] KEY_MATERIAL = new byte[32];

    /** The framing's two-byte length prefix. */
    private static final int LENGTH_PREFIX_BYTES = 2;

    /** The initialisation vector length the framing carries, matching the configuration's constant. */
    private static final int VECTOR_BYTES = 12;

    /** The authentication tag length in bytes that Galois/Counter Mode appends to the ciphertext. */
    private static final int TAG_BYTES = 16;

    /**
     * Confirms a context that scans the mapper package starts, which it previously could not.
     *
     * <p>Assumptions: the assertion is on the MAPPER bean rather than on the port, because the mapper is
     * what failed to be created. A context holding the port but not the mapper would mean the port had
     * been published under a type the mapper does not ask for, which is the other half of the same
     * defect.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a context scanning the mapper package starts and holds the customer mapper")
    void aContextScanningTheMapperPackageStarts() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CustomerMapper.class);
            assertThat(context).hasSingleBean(CustomerIdentifierProtection.class);
        });
    }

    /**
     * Confirms the published envelope is self-framing and carries no plaintext.
     *
     * <p>Assumptions: the framing is asserted by ARITHMETIC over the parts rather than against a recorded
     * byte length, so the case states the format rather than a measurement of it. The two-byte prefix is
     * then read back and required to equal the wrapped key's own length, which is what proves the prefix
     * describes the field that follows it and not a constant the reader assumed.</p>
     *
     * <p>Assumptions: the envelope is additionally required to contain the clear identifier NOWHERE, as a
     * byte subsequence. That is the one assertion that fails if a future change ever framed the plaintext
     * alongside the ciphertext, and no length or structural check would catch it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the envelope is self-framing, length-prefixed and contains no plaintext")
    void theEnvelopeIsSelfFramingAndCarriesNoPlaintext() {
        runner().run(context -> {
            CustomerIdentifierProtection protection =
                    context.getBean(CustomerIdentifierProtection.class);
            String clear = "123456789";

            byte[] envelope = protection.encrypt(clear, "ssn_encrypted");

            int expected = LENGTH_PREFIX_BYTES + WRAPPED_KEY.length + VECTOR_BYTES
                    + clear.length() + TAG_BYTES;
            assertThat(envelope).hasSize(expected);
            int declaredKeyLength = ((envelope[0] & 0xFF) << 8) | (envelope[1] & 0xFF);
            assertThat(declaredKeyLength).isEqualTo(WRAPPED_KEY.length);
            assertThat(new String(envelope, StandardCharsets.ISO_8859_1))
                    .as("the envelope must not carry the clear identifier anywhere")
                    .doesNotContain(clear);
        });
    }

    /**
     * Confirms two encipherments of the same identifier produce different envelopes.
     *
     * <p>Assumptions: this is what a fresh initialisation vector per value buys, and it is asserted
     * because the consequence of losing it is severe and silent. Deterministic ciphertext would let anyone
     * holding the column decide whether two customers share a national identifier, and whether a given
     * identifier is present, without deciphering anything -- the column would leak equality while
     * appearing to be encrypted.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the same identifier enciphers to a different envelope each time")
    void theSameIdentifierEnciphersDifferentlyEachTime() {
        runner().run(context -> {
            CustomerIdentifierProtection protection =
                    context.getBean(CustomerIdentifierProtection.class);
            Set<String> envelopes = new HashSet<>();
            for (int attempt = 0; attempt < 4; attempt++) {
                envelopes.add(new String(protection.encrypt("123456789", "ssn_encrypted"),
                        StandardCharsets.ISO_8859_1));
            }
            assertThat(envelopes).hasSize(4);
        });
    }

    /**
     * Confirms a blank column name is refused, since it selects the encryption context.
     *
     * <p>Assumptions: the refusal is on the column rather than tolerated with a default, because the
     * column name is authenticated additional data. An envelope bound to a blank context could be moved
     * between the two protected columns and would still decipher, which defeats the binding the context
     * exists to provide.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a blank column name is refused because it selects the encryption context")
    void aBlankColumnNameIsRefused() {
        runner().run(context -> {
            CustomerIdentifierProtection protection =
                    context.getBean(CustomerIdentifierProtection.class);
            assertThat(catchThrowableOf(protection, "123456789", " "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowableOf(protection, "", "ssn_encrypted"))
                    .isInstanceOf(IllegalArgumentException.class);
        });
    }

    /**
     * Calls the protection and returns whatever it threw.
     *
     * @param protection the boundary to call; must not be {@code null}
     * @param clear the clear value to offer
     * @param field the column name to offer
     * @return the throwable raised, or {@code null} when the call completed
     */
    private static Throwable catchThrowableOf(CustomerIdentifierProtection protection, String clear,
            String field) {
        try {
            protection.encrypt(clear, field);
            return null;
        } catch (RuntimeException raised) {
            return raised;
        }
    }

    /**
     * Builds a runner that scans the mapper package and substitutes the key-management client.
     *
     * @return the configured runner, never {@code null}
     */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CustomerIdentifierProtectionConfig.class))
                .withUserConfiguration(ScanMappersWithSubstitutedKms.class)
                .withPropertyValues(TEST_KEY_PROPERTY);
    }

    /**
     * Scans the mapper package and supplies a substituted key-management client.
     *
     * <p>Assumptions: the substitution is possible only because the configuration's client bean is
     * conditional on no bean of its type already existing, which is the property that keeps the
     * production wiring path -- not a test-only variant of it -- the one under test here.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackages = "com.carddemo.account.mapper")
    static class ScanMappersWithSubstitutedKms {

        /**
         * Creates the configuration.
         *
         * <p>Assumptions: written out because the documentation gate requires a docstring on every
         * constructor. The class holds no state.</p>
         */
        ScanMappersWithSubstitutedKms() {
            // Assumptions: empty by design; the bean method below is everything this class contributes.
        }

        /**
         * Supplies a key-management client that returns a fixed data key without a network call.
         *
         * @return the substituted client, never {@code null}
         */
        @Bean
        KmsClient kmsClient() {
            KmsClient kms = mock(KmsClient.class);
            when(kms.generateDataKey(any(GenerateDataKeyRequest.class)))
                    .thenReturn(GenerateDataKeyResponse.builder()
                            .plaintext(SdkBytes.fromByteArray(KEY_MATERIAL))
                            .ciphertextBlob(SdkBytes.fromByteArray(WRAPPED_KEY))
                            .build());
            return kms;
        }
    }
}
