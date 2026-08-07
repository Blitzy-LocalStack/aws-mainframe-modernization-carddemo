package com.carddemo.card.service;

import com.carddemo.card.domain.EncryptedCvv;
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
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * The one boundary that enciphers and deciphers a card verification value.
 *
 * <h2>What this is for</h2>
 *
 * <p><b>Purpose.</b> The baseline holds {@code CARD-CVV-CD} as three display digits in the clear, at
 * {@code app/cpy/CVACT02Y.cpy} line 7, on files declared {@code JOURNAL(NO)} and {@code RECOVERY(NONE)}.
 * The migrated schema declares {@code cvv_encrypted BYTEA} instead, and until this class existed nothing
 * enciphered anything: the column name and the byte type do not by themselves encrypt, and a mapper
 * assigning the three digits straight across would have written plaintext under a name that said
 * otherwise. This class is the only place a verification value exists as plaintext in this service, and
 * {@link EncryptedCvv} is the only form in which it reaches the entity.</p>
 *
 * <p>Assumptions: ENVELOPE encryption, not direct encryption under the customer-managed key. The
 * key-management service returns a fresh data key per value, this class enciphers with that data key
 * locally and stores the enciphered data key alongside the ciphertext. The alternative -- calling the
 * service to encipher each three-byte value directly -- would be simpler to write and would put the
 * plaintext on the wire to a second service and into that service's request path, which is the one thing
 * this value must not travel through. It would also bind throughput to a service call per value with a
 * request quota attached.</p>
 *
 * <p>Alternatives Considered: caching data keys so that many values share one. Rejected because the
 * cached key becomes plaintext key material held in the process for as long as the cache lives, and
 * because the saving is against a call that happens once per card write -- a path this service performs at
 * most on a card creation, not on a read. The cost accepted is one key-management call per encipherment.</p>
 *
 * <h2>Why the encryption context does not name the card</h2>
 *
 * <p>Alternatives Considered: binding the ciphertext to its card by putting the card number in the
 * encryption context, which is the idiomatic way to stop an envelope being moved between rows. Rejected
 * outright, and the reason is specific rather than stylistic: an encryption context is NOT secret. It is
 * recorded in the key-management service's own request log and appears verbatim in the audit trail of
 * every encipher and decipher call, so a card number placed there would write a primary account number
 * into an audit stream that is retained longer than the data and read by more people. The context
 * therefore names the PURPOSE only, which still confines a ciphertext to this use -- an envelope produced
 * here cannot be deciphered by a caller supplying a different context -- without disclosing which card it
 * belongs to.</p>
 *
 * <p>Trade-offs: an envelope moved from one card row to another therefore still deciphers. That is
 * accepted because the row itself is the binding: the value has no read path in this service at all -- it
 * appears on none of the three card screens and in no request or response shape -- so a moved envelope is
 * a database-integrity fault reachable only by direct table access, and direct table access can move the
 * card number too. Recording the trade explicitly is what keeps a later reader from assuming a binding
 * that is not there.</p>
 *
 * <p><strong>Return value.</strong> Each member documents its own return value.</p>
 */
@Component
public class CardVerificationValueCipher {

    /**
     * The transformation the data key is used under.
     *
     * <p>Assumptions: an AUTHENTICATED mode, so that a modified ciphertext fails to decipher rather than
     * producing three plausible digits. That property is the reason the mode is named here rather than
     * left to a default: an unauthenticated mode would let a single flipped byte in the column yield a
     * different verification value with nothing detecting the change.</p>
     */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * The algorithm the data key material belongs to.
     */
    private static final String KEY_ALGORITHM = "AES";

    /**
     * The authentication tag length, in bits.
     *
     * <p>Assumptions: the full 128 bits rather than a truncated tag. A shorter tag is permitted by the
     * mode and reduces the work an adversary needs to forge a ciphertext that authenticates, which is
     * precisely the property the mode was chosen for.</p>
     */
    private static final int TAG_LENGTH_BITS = 128;

    /**
     * The key of the encryption-context entry naming what a ciphertext is for.
     */
    private static final String CONTEXT_PURPOSE_KEY = "carddemo:purpose";

    /**
     * The value of the encryption-context entry naming what a ciphertext is for.
     *
     * <p>Assumptions: a fixed literal, so that an envelope produced here cannot be deciphered by a caller
     * that asks under any other purpose. It names no card, for the audit-trail reason recorded on the
     * class.</p>
     */
    private static final String CONTEXT_PURPOSE_VALUE = "card-cvv";

    /**
     * The number of digits a verification value carries.
     *
     * <p>Assumptions: three, from {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy} line 7.
     * The width is asserted on the way in so that a value that is not a verification value is refused
     * before a key is fetched, rather than being enciphered and stored as though it were one.</p>
     */
    private static final int VERIFICATION_VALUE_DIGITS = 3;

    /**
     * The key-management client data keys are obtained from and enciphered data keys returned to.
     */
    private final KmsClient kms;

    /**
     * The identifier of the customer-managed key data keys are generated under.
     *
     * <p>Assumptions: an identifier supplied by the deployment and carrying no default. A default would
     * be a key this code chose on a deployment's behalf, and the wrong key is not a recoverable mistake
     * once values have been written under it.</p>
     */
    private final String keyId;

    /**
     * The source of initialisation vectors.
     *
     * <p>Assumptions: one instance held for the life of the bean rather than one per call. This generator
     * is thread-safe and seeds itself from the platform, and constructing one per call is a documented way
     * to make it slow without making it stronger.</p>
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * Creates the cipher boundary.
     *
     * @param kms the key-management client; must not be {@code null}
     * @param keyId the identifier, alias or ARN of the customer-managed key data keys are generated
     *     under; must not be {@code null} or blank
     * @throws NullPointerException if {@code kms} or {@code keyId} is {@code null}
     * @throws IllegalArgumentException if {@code keyId} is blank
     */
    public CardVerificationValueCipher(KmsClient kms,
            @Value("${carddemo.security.cvv.key-id}") String keyId) {
        this.kms = Objects.requireNonNull(kms, "kms must not be null");
        Objects.requireNonNull(keyId, "carddemo.security.cvv.key-id must not be null");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("carddemo.security.cvv.key-id must not be blank;"
                    + " a blank key identifier would be resolved by the key-management service as an"
                    + " absent argument and the failure would surface on the first card write rather"
                    + " than at start-up");
        }
        this.keyId = keyId;
    }

    /**
     * Enciphers a verification value into the envelope the column stores.
     *
     * <p>Assumptions: the plaintext bytes and the data key are both CLEARED before this method returns, on
     * every path including the failing one. Neither can be cleared from the immutable string a caller
     * passed in, which is why the argument is documented as the caller's own responsibility, but the two
     * copies this method makes are its own and are not left in the heap for a dump to pick up.</p>
     *
     * @param verificationValue the three digits to encipher; must not be {@code null} and must be exactly
     *     {@value #VERIFICATION_VALUE_DIGITS} digit characters. The caller owns the string and this method
     *     cannot clear it, a string being immutable
     * @return the envelope, never {@code null}
     * @throws NullPointerException if {@code verificationValue} is {@code null}
     * @throws IllegalArgumentException if the value is not exactly {@value #VERIFICATION_VALUE_DIGITS}
     *     digits
     * @throws IllegalStateException if the platform refuses the transformation or the key material, which
     *     is a deployment fault rather than a bad argument
     */
    public EncryptedCvv encipher(String verificationValue) {
        Objects.requireNonNull(verificationValue, "verificationValue is required");
        requireVerificationValueShape(verificationValue);

        GenerateDataKeyResponse dataKey = this.kms.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(this.keyId)
                .keySpec(DataKeySpec.AES_256)
                .encryptionContext(encryptionContext())
                .build());

        byte[] keyMaterial = dataKey.plaintext().asByteArray();
        byte[] plaintext = verificationValue.getBytes(StandardCharsets.US_ASCII);
        try {
            byte[] initialisationVector = new byte[EncryptedCvv.INITIALISATION_VECTOR_LENGTH];
            this.random.nextBytes(initialisationVector);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyMaterial, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_LENGTH_BITS, initialisationVector));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return EncryptedCvv.wrap(dataKey.ciphertextBlob().asByteArray(), initialisationVector,
                    ciphertext);
        } catch (GeneralSecurityException refused) {
            // Assumptions: the cause is attached but the message names no value. A security-provider
            // failure message can quote the argument it rejected, and the argument here is a
            // verification value, so the wrapper states what failed and lets the cause carry the rest
            // to a stack trace rather than into a log message a handler might render.
            throw new IllegalStateException(
                    "the platform refused the card verification value transformation " + TRANSFORMATION,
                    refused);
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Deciphers an envelope back to the verification value it holds.
     *
     * <p>Assumptions: this exists for a re-key and for a verification the migration does not yet perform,
     * and it is authored together with the encipherment rather than later because an envelope format with
     * only a writer cannot be shown to round-trip. Its own test is what proves the framing the entity
     * stores is the framing this method reads.</p>
     *
     * @param encrypted the envelope to decipher; must not be {@code null}
     * @return the verification value as its {@value #VERIFICATION_VALUE_DIGITS} digits, never {@code null}
     * @throws NullPointerException if {@code encrypted} is {@code null}
     * @throws IllegalStateException if the platform refuses the transformation, or the ciphertext fails
     *     its authentication check, which means the stored bytes were altered
     */
    public String decipher(EncryptedCvv encrypted) {
        Objects.requireNonNull(encrypted, "encrypted is required");

        DecryptResponse dataKey = this.kms.decrypt(DecryptRequest.builder()
                .keyId(this.keyId)
                .ciphertextBlob(SdkBytes.fromByteArray(encrypted.encipheredDataKey()))
                .encryptionContext(encryptionContext())
                .build());

        byte[] keyMaterial = dataKey.plaintext().asByteArray();
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyMaterial, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_LENGTH_BITS, encrypted.initialisationVector()));
            plaintext = cipher.doFinal(encrypted.ciphertext());
            return new String(plaintext, StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException refused) {
            // Assumptions: an authentication failure and a configuration failure are reported the same
            // way deliberately. Distinguishing them in the message would tell a caller which of the two
            // it hit, and the authentication case is the one an adversary probing altered ciphertexts
            // would be reading that answer from.
            throw new IllegalStateException("the stored card verification value envelope did not"
                    + " decipher; either the platform refused " + TRANSFORMATION
                    + " or the ciphertext failed its authentication check", refused);
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * Returns the encryption context both directions bind to.
     *
     * <p>Assumptions: built fresh on each call rather than held as a constant map, because the map is
     * handed to a client that may retain it and an immutable map shared between calls is one more thing to
     * reason about for no saving worth measuring against a network call.</p>
     *
     * @return the context, never {@code null} and never naming a card
     */
    private static Map<String, String> encryptionContext() {
        return Map.of(CONTEXT_PURPOSE_KEY, CONTEXT_PURPOSE_VALUE);
    }

    /**
     * Refuses a value that is not a verification value before any key is fetched.
     *
     * <p>Assumptions: the refusal names the width and never the value, for the same reason every other
     * refusal on this path does. Ordering it before the key-management call also means a malformed value
     * costs no request against that service's quota.</p>
     *
     * @param candidate the value to check; must not be {@code null}
     * @throws IllegalArgumentException if the value is not exactly {@value #VERIFICATION_VALUE_DIGITS}
     *     digit characters
     */
    private static void requireVerificationValueShape(String candidate) {
        boolean conforming = candidate.length() == VERIFICATION_VALUE_DIGITS;
        for (int index = 0; conforming && index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            conforming = character >= '0' && character <= '9';
        }
        if (!conforming) {
            throw new IllegalArgumentException("a card verification value is exactly "
                    + VERIFICATION_VALUE_DIGITS + " digits, from CARD-CVV-CD PIC 9(03) at"
                    + " app/cpy/CVACT02Y.cpy line 7; the value supplied was " + candidate.length()
                    + " characters and is not one");
        }
    }
}
