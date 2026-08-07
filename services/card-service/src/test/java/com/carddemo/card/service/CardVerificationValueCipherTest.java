package com.carddemo.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.card.domain.EncryptedCvv;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Pins the encipherment boundary: what it produces, what it refuses, and what it does not disclose.
 *
 * <p>The subject is the one place a card verification value exists as plaintext in this service. The
 * baseline held it as three display digits in the clear at {@code app/cpy/CVACT02Y.cpy} line 7 on files
 * declared {@code JOURNAL(NO)} and {@code RECOVERY(NONE)}; the migrated column holds an envelope instead,
 * and this class is what produces one.</p>
 *
 * <p>Assumptions: the key-management client is MOCKED and the local cryptography is REAL. That split is
 * deliberate rather than convenient. Mocking the client keeps the cases offline and lets the same data key
 * be returned to the encipher and the decipher call, which is what makes a round-trip assertable at all;
 * performing the cipher for real is what makes the round-trip evidence rather than a restatement of the
 * mock. A mocked cipher would prove only that this test can spell its own expectations.</p>
 *
 * <p>Assumptions: the data key the mock returns is a real 32-byte key, generated once per case. A fixed
 * pattern would work for the cipher but would make an accidental use of the key material as the ciphertext
 * -- the kind of wiring mistake this test is for -- harder to see, since a repeated byte pattern looks the
 * same wherever it appears.</p>
 */
class CardVerificationValueCipherTest {

    /**
     * The key identifier the deployment supplies, in the alias form a deployment most often uses.
     */
    private static final String KEY_ID = "alias/carddemo-application-dev";

    /**
     * The verification value under test, three digits as the baseline declares.
     */
    private static final String VERIFICATION_VALUE = "123";

    /**
     * A stand-in for the enciphered form of the data key, which this test never has to interpret.
     *
     * <p>Assumptions: opaque bytes at a realistic length. The cipher carries this value into the envelope
     * and hands it back to the key-management service unchanged, so its contents are irrelevant to
     * everything under test except that they survive the round trip.</p>
     */
    private static final byte[] ENCIPHERED_DATA_KEY = new byte[184];

    /** The mocked key-management client. */
    private KmsClient kms;

    /** The plaintext data key both directions are given. */
    private byte[] dataKey;

    /** The cipher under test. */
    private CardVerificationValueCipher cipher;

    /**
     * Builds a fresh mock, a fresh data key and the cipher for each case.
     */
    @BeforeEach
    void setUp() {
        this.kms = mock(KmsClient.class);
        this.dataKey = new byte[32];
        new SecureRandom().nextBytes(this.dataKey);
        new SecureRandom().nextBytes(ENCIPHERED_DATA_KEY);
        this.cipher = new CardVerificationValueCipher(this.kms, KEY_ID);
        when(this.kms.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenReturn(GenerateDataKeyResponse.builder()
                        .keyId(KEY_ID)
                        .plaintext(SdkBytes.fromByteArray(this.dataKey))
                        .ciphertextBlob(SdkBytes.fromByteArray(ENCIPHERED_DATA_KEY))
                        .build());
        when(this.kms.decrypt(any(DecryptRequest.class)))
                .thenReturn(DecryptResponse.builder()
                        .keyId(KEY_ID)
                        .plaintext(SdkBytes.fromByteArray(this.dataKey))
                        .build());
    }

    /**
     * A verification value enciphers to an envelope and deciphers back to itself.
     *
     * <p>Assumptions: the round trip is the property the format exists for, and it is asserted with the
     * real cipher over a real data key. It is what would fail if the framing split the envelope
     * differently from the way it assembled it -- a fault every length assertion would pass.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a verification value enciphers to an envelope and deciphers back to itself")
    void aVerificationValueRoundTrips() {
        EncryptedCvv enciphered = this.cipher.encipher(VERIFICATION_VALUE);

        assertThat(EncryptedCvv.hasEnvelopeShape(enciphered.envelope())).isTrue();
        assertThat(enciphered.encipheredDataKey()).isEqualTo(ENCIPHERED_DATA_KEY);
        assertThat(this.cipher.decipher(enciphered)).isEqualTo(VERIFICATION_VALUE);
    }

    /**
     * The envelope carries neither the plaintext nor the data key that enciphered it.
     *
     * <p>Assumptions: both are asserted, and the second is the one a reader would not think to check. The
     * plaintext being absent is the obvious requirement; the plaintext DATA KEY being absent is what
     * distinguishes envelope encryption from a mistake that stores the key beside the ciphertext, which
     * would leave the value readable by anyone holding the row.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the envelope carries neither the plaintext nor the plaintext data key")
    void theEnvelopeCarriesNoPlaintext() {
        byte[] envelope = this.cipher.encipher(VERIFICATION_VALUE).envelope();

        assertThat(contains(envelope, VERIFICATION_VALUE.getBytes(StandardCharsets.US_ASCII)))
                .as("the three plaintext digits appear nowhere in the stored bytes")
                .isFalse();
        assertThat(contains(envelope, this.dataKey))
                .as("the plaintext data key appears nowhere in the stored bytes")
                .isFalse();
    }

    /**
     * Two encipherments of one value produce different envelopes.
     *
     * <p>Assumptions: this asserts the initialisation vector is fresh per call, which is the property that
     * stops equal verification values being recognisable as equal from the stored bytes. It is a real
     * hazard for a three-digit value: there are only a thousand of them, so a deterministic encipherment
     * would let every card sharing a value be grouped by comparing columns, without deciphering
     * anything.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("two encipherments of one value differ, so equal values are not recognisable")
    void encipheringTwiceProducesDifferentEnvelopes() {
        byte[] first = this.cipher.encipher(VERIFICATION_VALUE).envelope();
        byte[] second = this.cipher.encipher(VERIFICATION_VALUE).envelope();

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * The key-management call names the configured key, an authenticated key spec and the purpose context.
     *
     * <p>Assumptions: the encryption context is asserted to name the purpose and to name NO card. Putting
     * the card number there is the idiomatic way to bind a ciphertext to its row and is refused here for a
     * specific reason: an encryption context is recorded verbatim in the key-management service's audit
     * trail, so a primary account number placed there would be written into a stream retained longer than
     * the data and read by more people. Asserting the absence is what keeps a later change from adding
     * it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the data-key request names the configured key and a context carrying no card")
    void theDataKeyRequestNamesTheKeyAndNoCard() {
        this.cipher.encipher(VERIFICATION_VALUE);

        ArgumentCaptor<GenerateDataKeyRequest> request =
                ArgumentCaptor.forClass(GenerateDataKeyRequest.class);
        verify(this.kms).generateDataKey(request.capture());
        assertThat(request.getValue().keyId()).isEqualTo(KEY_ID);
        assertThat(request.getValue().keySpec()).isEqualTo(DataKeySpec.AES_256);
        assertThat(request.getValue().encryptionContext())
                .containsExactly(java.util.Map.entry("carddemo:purpose", "card-cvv"));
    }

    /**
     * A value that is not three digits is refused before any key is fetched.
     *
     * <p>Assumptions: the absence of the key-management call is asserted alongside the refusal. Ordering
     * the check first is what keeps a malformed value from consuming a request against that service's
     * quota, and a test that asserted only the exception would pass with the check in either position.</p>
     *
     * <p>Assumptions: the refusal message is asserted not to quote the value, for the same reason every
     * other refusal on this path is -- a value reaching it may be payment data.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a value that is not three digits is refused before any key is fetched")
    void aMalformedValueIsRefusedBeforeAnyKeyIsFetched() {
        assertThatThrownBy(() -> this.cipher.encipher("12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 3 digits");
        assertThatThrownBy(() -> this.cipher.encipher("12A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("12A");
        assertThatThrownBy(() -> this.cipher.encipher(""))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(this.kms);
    }

    /**
     * An altered ciphertext fails its authentication check rather than deciphering to something.
     *
     * <p>Assumptions: this is the reason the authenticated cipher mode was chosen and is asserted rather
     * than assumed. Under an unauthenticated mode a single flipped byte in the column would yield a
     * different three-digit value with nothing detecting the change, which for a verification value means a
     * silently wrong answer rather than a visible fault.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an altered ciphertext fails authentication instead of deciphering")
    void anAlteredCiphertextFailsAuthentication() {
        EncryptedCvv enciphered = this.cipher.encipher(VERIFICATION_VALUE);
        byte[] altered = enciphered.envelope();
        altered[altered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> this.cipher.decipher(EncryptedCvv.ofEnvelope(altered)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication check");
    }

    /**
     * A blank or absent key identifier is refused at construction.
     *
     * <p>Assumptions: refused at construction rather than on first use, so a deployment that failed to
     * supply the identifier does not start. A blank value would otherwise reach the key-management service
     * as an absent argument and the failure would surface on the first card write, from whichever request
     * happened to be first.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a blank or absent key identifier is refused at construction")
    void aBlankKeyIdentifierIsRefused() {
        assertThatThrownBy(() -> new CardVerificationValueCipher(this.kms, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.security.cvv.key-id");
        assertThatThrownBy(() -> new CardVerificationValueCipher(this.kms, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("carddemo.security.cvv.key-id");
        assertThatThrownBy(() -> new CardVerificationValueCipher(null, KEY_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("kms");
    }

    /**
     * Reports whether one byte run occurs anywhere inside another.
     *
     * <p>Assumptions: a naive scan rather than a library search, because the arrays here are a few hundred
     * bytes and a dependency added for this would be a dependency to justify. The property under test is
     * absence, so a false positive would fail the test loudly rather than passing it quietly.</p>
     *
     * @param haystack the bytes to search; must not be {@code null}
     * @param needle the bytes to search for; must not be {@code null} or empty
     * @return {@code true} when {@code needle} occurs in {@code haystack}
     */
    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
