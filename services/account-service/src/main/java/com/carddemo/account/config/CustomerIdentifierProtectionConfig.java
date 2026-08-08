package com.carddemo.account.config;

import com.carddemo.account.mapper.CustomerMapper.CustomerIdentifierProtection;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Supplies the implementation of the protected-identifier port the customer mapper declares.
 *
 * <p><b>Purpose.</b> {@code CustomerMapper} is a {@code @Component} whose constructor requires a
 * {@link CustomerIdentifierProtection}, and until this class existed no implementation of that interface
 * existed anywhere in the reactor. The consequence was not a missing feature but a context that could not
 * start at all: any refresh that scanned {@code com.carddemo.account.mapper} failed with
 * {@code NoSuchBeanDefinitionException} for the nested port type. The module's own build did not catch it,
 * because its start-up test drives an {@code ApplicationContextRunner} over the AWS autoconfigurations
 * only and never scans the mapper package, so the missing bean was invisible to every green build.</p>
 *
 * <p>Assumptions: this lives in the CONFIGURATION layer, which is where the port's own documentation says
 * an implementation belongs -- the interface is declared in the mapper package so that the mapping layer
 * states what it needs of a key provider without naming one, and an infrastructure type is admissible
 * here and not there. The mapper therefore still has no compile-time dependency on the key-management
 * client, and its unit tests still satisfy the port with a substitute and hold every width, composition
 * and masking decision assertable with no key material in reach.</p>
 *
 * <p>Assumptions: ENVELOPE encryption under a data key, not direct encryption under the customer-managed
 * key, and the framing is deliberately the same as the one
 * {@code com.carddemo.card.service.CardVerificationValueCipher} uses for the card verification value.
 * Two protected columns in two contexts enciphered two different ways would mean two formats to re-key,
 * two to audit and two to get wrong. What envelope encryption buys here is the same thing it buys there:
 * the clear identifier never travels to a second service and never enters that service's request path,
 * and throughput is not bound to one key-management call per byte.</p>
 *
 * <p>Assumptions: a FRESH data key per identifier, with no caching. A cached data key is plaintext key
 * material held in the process for as long as the cache lives, and the call it would save happens once
 * per customer write -- a path this context performs on an update, not on a read. The accepted cost is
 * one key-management call per identifier, so a customer update carrying both identifiers makes two.</p>
 *
 * <p>Assumptions: the encryption context names the PURPOSE and the COLUMN and never the customer. The
 * context is authenticated additional data and is recorded in the key-management service's audit trail
 * in the clear, so putting a customer identifier in it would publish, to a log, exactly the association
 * the ciphertext exists to conceal. Naming the column still binds a national-identifier envelope to that
 * column, so an envelope moved between the two columns fails its authentication check rather than
 * deciphering.</p>
 *
 * <p>Trade-offs: there is no deciphering member here, and its absence is deliberate rather than an
 * omission. {@code Customer} publishes no accessor for either clear identifier and every response
 * renders the fixed redaction marker, so nothing in this context has a value to decipher FOR; a decipher
 * method would be an unused route from ciphertext back to a national identifier. The card context does
 * publish one because a verification value has a verification use. When a re-key or an export needs one,
 * it is added with the test that proves the framing round-trips -- which is the same standard the card
 * context met.</p>
 *
 * <p><strong>Return value.</strong> Each member documents its own return value.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CustomerIdentifierProtectionConfig {

    /**
     * The authenticated-encryption transformation both protected identifiers are enciphered with.
     *
     * <p>Assumptions: Galois/Counter Mode, so the ciphertext carries an authentication tag and an altered
     * stored value fails to decipher rather than deciphering to different bytes. A mode without
     * authentication would let an edit to the column go undetected, and this column is one whose value
     * nothing downstream can sanity-check.</p>
     */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** The key algorithm the data key material is interpreted under. */
    private static final String KEY_ALGORITHM = "AES";

    /**
     * The authentication tag length in bits.
     *
     * <p>Assumptions: the maximum the mode admits. A shorter tag reduces the stored bytes by at most a
     * few and weakens exactly the property the mode was chosen for.</p>
     */
    private static final int TAG_LENGTH_BITS = 128;

    /**
     * The initialisation vector length in bytes.
     *
     * <p>Assumptions: twelve, the length this mode is specified for, matching the card context's framing.
     * A vector of another length is admissible to the provider but requires it to derive one internally,
     * which changes the framing without saying so.</p>
     */
    private static final int INITIALISATION_VECTOR_LENGTH = 12;

    /** The encryption-context key naming what the envelope is for. */
    private static final String CONTEXT_PURPOSE_KEY = "carddemo:purpose";

    /** The encryption-context value naming this context's protected identifiers. */
    private static final String CONTEXT_PURPOSE_VALUE = "customer-identifier";

    /** The encryption-context key naming which column the envelope belongs to. */
    private static final String CONTEXT_COLUMN_KEY = "carddemo:column";

    /**
     * Creates the configuration.
     *
     * <p>Assumptions: written out rather than left implicit because the documentation gate requires a
     * docstring on every constructor. The class holds no state.</p>
     */
    public CustomerIdentifierProtectionConfig() {
        // Assumptions: empty by design. Everything this class contributes is the two bean methods below.
    }

    /**
     * Supplies the synchronous key-management client the identifier protection uses.
     *
     * <p>Assumptions: region and credentials resolve through the SDK's own default provider chains rather
     * than being set here. In a deployed task the region arrives as an environment variable and the
     * credentials as the task role, which is the mechanism least-privilege depends on; naming either here
     * would hard-code a region into the image or open a route for a credential to be configured, and the
     * whole point of a task role is that no credential is configurable.</p>
     *
     * <p>Trade-offs: the SYNCHRONOUS client, because the protection obtains a data key and then enciphers
     * with it immediately, so it must hold the key before it can continue and would block on a future at
     * once were the client asynchronous. Blocking explicitly is the same wait without ambiguity about
     * which thread the continuation runs on.</p>
     *
     * <p>Assumptions: conditional on no other bean of this type, so a test or a future context can supply
     * a stub or a client pointed at an emulator without this class being modified or excluded. This is
     * what lets the module's own wiring test prove the port is satisfiable with no account reachable.</p>
     *
     * @return the key-management client, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    public KmsClient accountKmsClient() {
        return KmsClient.create();
    }

    /**
     * Supplies the protected-identifier boundary the customer mapper requires.
     *
     * <p>Assumptions: conditional on no other bean of this type, for the same reason the client is. A test
     * that wants to assert a composition rather than an encipherment substitutes its own and never
     * reaches a key.</p>
     *
     * @param kms the key-management client to obtain data keys from; must not be {@code null}
     * @param keyId the customer-managed key the data keys are wrapped under; must not be {@code null}
     * @return the protection boundary, never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean
    public CustomerIdentifierProtection customerIdentifierProtection(KmsClient kms,
            @Value("${carddemo.security.customer-identifier.key-id}") String keyId) {
        return new KmsEnvelopeIdentifierProtection(kms, keyId);
    }

    /**
     * Enciphers a customer identifier into a self-framing envelope under a per-value data key.
     *
     * <p>Assumptions: this is a private nested type rather than a separate class in the service package,
     * because it is infrastructure that only the bean method above constructs and nothing else may reach.
     * Publishing it would make a second, un-audited route to the encipherment available to any class in
     * the module.</p>
     */
    private static final class KmsEnvelopeIdentifierProtection
            implements CustomerIdentifierProtection {

        /** Obtains the per-value data keys. */
        private final KmsClient kms;

        /** The customer-managed key the data keys are wrapped under. */
        private final String keyId;

        /**
         * Supplies the initialisation vectors.
         *
         * <p>Assumptions: one instance per bean rather than one per call, because seeding a new
         * cryptographic random source per encipherment is the expensive part and reusing the instance is
         * the documented, thread-safe usage.</p>
         */
        private final SecureRandom random = new SecureRandom();

        /**
         * Creates the protection over a client and a key.
         *
         * @param kmsClient the key-management client; must not be {@code null}
         * @param customerManagedKeyId the key identifier or alias; must not be {@code null} or blank
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code customerManagedKeyId} is blank
         */
        private KmsEnvelopeIdentifierProtection(KmsClient kmsClient, String customerManagedKeyId) {
            this.kms = Objects.requireNonNull(kmsClient, "kmsClient must not be null");
            this.keyId = Objects.requireNonNull(customerManagedKeyId, "keyId must not be null");
            if (customerManagedKeyId.isBlank()) {
                throw new IllegalArgumentException("the customer-identifier key id must not be blank,"
                        + " because an unset key would encipher under the account's default key and"
                        + " produce envelopes no re-key could locate");
            }
        }

        /**
         * Enciphers one identifier and returns the envelope to store.
         *
         * <p>Assumptions: the framing is the enciphered data key, its own two-byte length ahead of it, the
         * initialisation vector, then the ciphertext. The length prefix is what makes the envelope
         * self-framing: the wrapped-key length is chosen by the key-management service and is not a
         * constant this class may assume, so a reader that split at a fixed offset would work until the
         * service changed its wrapping and then decipher garbage. Two bytes is sufficient because a
         * wrapped two-hundred-and-fifty-six-bit key is a few hundred bytes, and the value is written
         * big-endian so the framing does not depend on the platform.</p>
         *
         * <p>Assumptions: the clear text is enciphered EXACTLY as given, with no trimming and no numeric
         * conversion. Both identifiers admit a leading zero -- the national identifier is
         * {@code PIC 9(09)} and the government-issued identifier {@code PIC X(20)} -- so reducing either
         * to a number before enciphering would store ciphertext that deciphers to a different identifier
         * than the reference held, with nothing downstream able to detect it.</p>
         *
         * <p>Assumptions: the key material and the plaintext bytes are both cleared in a finally block.
         * They cannot be un-allocated, but leaving them intact keeps a national identifier and a live key
         * in the heap for whatever reads it next, including a heap dump taken for an unrelated reason.</p>
         *
         * @param clearText the identifier exactly as it is to be stored; must not be {@code null} or empty
         * @param field the column the value belongs to; must not be {@code null} or blank
         * @return the envelope to store, never {@code null} and never empty
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code clearText} is empty or {@code field} is blank
         * @throws IllegalStateException if the platform refuses the transformation, which is propagated
         *     rather than caught because a row written with an unprotected identifier is worse than a
         *     request that fails
         */
        @Override
        public byte[] encrypt(String clearText, String field) {
            Objects.requireNonNull(clearText, "clearText must not be null");
            Objects.requireNonNull(field, "field must not be null");
            if (clearText.isEmpty()) {
                throw new IllegalArgumentException("clearText must not be empty for column " + field);
            }
            if (field.isBlank()) {
                throw new IllegalArgumentException("field must name the column the value belongs to,"
                        + " because it selects the encryption context the envelope is bound to");
            }

            GenerateDataKeyResponse dataKey = this.kms.generateDataKey(GenerateDataKeyRequest.builder()
                    .keyId(this.keyId)
                    .keySpec(DataKeySpec.AES_256)
                    .encryptionContext(encryptionContext(field))
                    .build());

            byte[] keyMaterial = dataKey.plaintext().asByteArray();
            byte[] plaintext = clearText.getBytes(StandardCharsets.UTF_8);
            try {
                byte[] initialisationVector = new byte[INITIALISATION_VECTOR_LENGTH];
                this.random.nextBytes(initialisationVector);
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyMaterial, KEY_ALGORITHM),
                        new GCMParameterSpec(TAG_LENGTH_BITS, initialisationVector));
                return frame(dataKey.ciphertextBlob().asByteArray(), initialisationVector,
                        cipher.doFinal(plaintext));
            } catch (GeneralSecurityException refused) {
                // WHY : Trade-offs: the cause is attached but this message names NO value and not even
                //       the clear text's length. A security-provider message can quote the argument it
                //       rejected, and the argument here is a national identifier, so the wrapper states
                //       what failed and which column, and lets the cause carry the rest to a stack trace
                //       rather than into a message a handler might render into a response or a log.
                throw new IllegalStateException("the platform refused the " + TRANSFORMATION
                        + " transformation while protecting column " + field, refused);
            } finally {
                Arrays.fill(keyMaterial, (byte) 0);
                Arrays.fill(plaintext, (byte) 0);
            }
        }

        /**
         * Builds the authenticated additional data the envelope is bound to.
         *
         * @param field the column the value belongs to; must not be {@code null}
         * @return the encryption context, never {@code null}
         */
        private static Map<String, String> encryptionContext(String field) {
            return Map.of(CONTEXT_PURPOSE_KEY, CONTEXT_PURPOSE_VALUE, CONTEXT_COLUMN_KEY, field);
        }

        /**
         * Frames the three envelope parts into the single byte array the column stores.
         *
         * @param encipheredDataKey the wrapped data key; must not be {@code null}
         * @param initialisationVector the vector the ciphertext was produced under; must not be
         *     {@code null}
         * @param ciphertext the enciphered identifier including its authentication tag; must not be
         *     {@code null}
         * @return the framed envelope, never {@code null}
         * @throws IllegalStateException if the wrapped data key is longer than the two-byte length prefix
         *     can describe, which would make the envelope unreadable
         */
        private static byte[] frame(byte[] encipheredDataKey, byte[] initialisationVector,
                byte[] ciphertext) {
            if (encipheredDataKey.length > 0xFFFF) {
                throw new IllegalStateException("the wrapped data key is " + encipheredDataKey.length
                        + " bytes, which a two-byte length prefix cannot describe");
            }
            byte[] envelope = new byte[2 + encipheredDataKey.length + initialisationVector.length
                    + ciphertext.length];
            envelope[0] = (byte) (encipheredDataKey.length >>> 8);
            envelope[1] = (byte) encipheredDataKey.length;
            int cursor = 2;
            System.arraycopy(encipheredDataKey, 0, envelope, cursor, encipheredDataKey.length);
            cursor += encipheredDataKey.length;
            System.arraycopy(initialisationVector, 0, envelope, cursor, initialisationVector.length);
            cursor += initialisationVector.length;
            System.arraycopy(ciphertext, 0, envelope, cursor, ciphertext.length);
            return envelope;
        }
    }
}
