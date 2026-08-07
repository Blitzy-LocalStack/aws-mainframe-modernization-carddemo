package com.carddemo.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the enciphered verification value cannot be a plaintext one.
 *
 * <p>The subject is the defect this type was introduced to close. The member was a bare {@code byte[]}
 * named {@code cvvEncrypted}, so the three ASCII bytes {@code 123} -- the baseline's own
 * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy} line 7, in the clear -- were valid state
 * for a column called {@code cvv_encrypted}. The first case below is that exact value, asserted
 * refused.</p>
 *
 * <p>Assumptions: the envelope framing is asserted through the public factories rather than by
 * inspecting bytes at fixed offsets, because the offsets are an implementation detail of the framing
 * while the properties -- that a plaintext value is inexpressible, that the parts round-trip, that a
 * truncated or re-versioned envelope is refused -- are the contract. A test written against the offsets
 * would have to be rewritten by any format change that kept the contract.</p>
 *
 * <p>Assumptions: no case here enciphers anything, and none needs a key. The whole point of a
 * self-describing envelope is that it can be validated without one, which is what lets the persistence
 * boundary refuse a malformed value while holding no key material.</p>
 */
class EncryptedCvvTest {

    /**
     * A stand-in for the enciphered data key, at a length the framing admits.
     *
     * <p>Assumptions: 184 bytes, which is the order of magnitude a real enciphered data key occupies, and
     * more than 127 -- so the two-byte length field's high byte is exercised and a signed read of it would
     * fail this test rather than passing and failing in production.</p>
     */
    private static final byte[] ENCIPHERED_DATA_KEY = filled(184, (byte) 0xA7);

    /**
     * A stand-in for the initialisation vector, at exactly the declared length.
     */
    private static final byte[] INITIALISATION_VECTOR =
            filled(EncryptedCvv.INITIALISATION_VECTOR_LENGTH, (byte) 0x5C);

    /**
     * A stand-in for the ciphertext with its authentication tag: three bytes plus a 16-byte tag.
     */
    private static final byte[] CIPHERTEXT = filled(19, (byte) 0x3E);

    /**
     * The plaintext verification value the old member accepted.
     */
    private static final byte[] PLAINTEXT_CVV = "123".getBytes(StandardCharsets.US_ASCII);

    /**
     * A plaintext verification value cannot be expressed as this value type.
     *
     * <p>Assumptions: both the predicate and the factory are asserted, because the two are what the
     * persistence boundary uses in each direction. Asserting only the factory would leave the read path's
     * check unproven.</p>
     *
     * <p>Assumptions: the refusal message is asserted NOT to contain the digits. A value reaching the
     * refusal may be payment data -- that is the case being caught -- so quoting it would write the data
     * into the log line reporting its rejection.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the three plaintext digits are refused and are not quoted in the refusal")
    void aPlaintextVerificationValueIsRefused() {
        assertThat(EncryptedCvv.hasEnvelopeShape(PLAINTEXT_CVV)).isFalse();
        assertThatThrownBy(() -> EncryptedCvv.ofEnvelope(PLAINTEXT_CVV))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cvv_encrypted")
                .hasMessageContaining("plaintext")
                .hasMessageNotContaining("123");
    }

    /**
     * The three parts an enciphering boundary produces round-trip through the framing.
     *
     * <p>Assumptions: this is the property the format exists for, and it is asserted on the parts rather
     * than on the framed length, because the boundary reads the parts back out and a framing that
     * assembled correctly but split wrongly would produce a ciphertext that deciphers to nothing while
     * every length assertion passed.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the data key, the vector and the ciphertext round-trip through the envelope")
    void theThreePartsRoundTrip() {
        EncryptedCvv wrapped =
                EncryptedCvv.wrap(ENCIPHERED_DATA_KEY, INITIALISATION_VECTOR, CIPHERTEXT);

        assertThat(wrapped.encipheredDataKey()).isEqualTo(ENCIPHERED_DATA_KEY);
        assertThat(wrapped.initialisationVector()).isEqualTo(INITIALISATION_VECTOR);
        assertThat(wrapped.ciphertext()).isEqualTo(CIPHERTEXT);
        assertThat(EncryptedCvv.ofEnvelope(wrapped.envelope())).isEqualTo(wrapped);
    }

    /**
     * The value owns its bytes: nothing a caller holds afterwards can change what it carries.
     *
     * <p>Assumptions: both directions are asserted. Mutating a caller's array after wrapping must not
     * reach the value, and mutating the array a reader receives must not reach it either -- the second
     * matters because the persistence provider writes whatever the attribute holds at flush time, so a
     * shared array would change the stored bytes with no assignment appearing in the source.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the envelope is immutable in both directions")
    void theEnvelopeIsImmutable() {
        byte[] mutableKey = ENCIPHERED_DATA_KEY.clone();
        EncryptedCvv wrapped = EncryptedCvv.wrap(mutableKey, INITIALISATION_VECTOR, CIPHERTEXT);

        mutableKey[0] = 0x00;
        assertThat(wrapped.encipheredDataKey()).isEqualTo(ENCIPHERED_DATA_KEY);

        byte[] exported = wrapped.envelope();
        exported[0] = 0x00;
        assertThat(wrapped.envelope()).isNotEqualTo(exported);
    }

    /**
     * Every malformed envelope shape is refused.
     *
     * <p>Assumptions: the four shapes are the ways an envelope can be nearly right. A wrong marker is a
     * value that was never one; a wrong version was written by something else and is refused rather than
     * interpreted; a truncated envelope has lost bytes the framing declares; and a declared key length
     * that overruns the value is the one shape a length-prefixed format is specifically exposed to, since
     * trusting it would read past the end.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a wrong marker, wrong version, truncation or overrunning key length is refused")
    void everyMalformedEnvelopeIsRefused() {
        byte[] valid = EncryptedCvv.wrap(ENCIPHERED_DATA_KEY, INITIALISATION_VECTOR, CIPHERTEXT)
                .envelope();

        byte[] wrongMarker = valid.clone();
        wrongMarker[0] = 'X';
        assertThat(EncryptedCvv.hasEnvelopeShape(wrongMarker)).isFalse();

        byte[] wrongVersion = valid.clone();
        wrongVersion[EncryptedCvv.MAGIC_LENGTH] = EncryptedCvv.FORMAT_VERSION + 1;
        assertThat(EncryptedCvv.hasEnvelopeShape(wrongVersion)).isFalse();

        byte[] truncated = new byte[EncryptedCvv.MIN_ENVELOPE_LENGTH - 1];
        System.arraycopy(valid, 0, truncated, 0, truncated.length);
        assertThat(EncryptedCvv.hasEnvelopeShape(truncated)).isFalse();

        byte[] overrunningKeyLength = valid.clone();
        overrunningKeyLength[EncryptedCvv.MAGIC_LENGTH + 1] = (byte) 0xFF;
        overrunningKeyLength[EncryptedCvv.MAGIC_LENGTH + 2] = (byte) 0xFF;
        assertThat(EncryptedCvv.hasEnvelopeShape(overrunningKeyLength)).isFalse();
    }

    /**
     * Each assembled part is held to the length the framing admits.
     *
     * <p>Assumptions: the three refusals are asserted at the boundary they matter at. An empty data key
     * would produce an envelope no reader can split; a vector of the wrong length would be silently
     * padded or truncated by the cipher on the other side; and a ciphertext shorter than a tag cannot
     * carry one, so it could never authenticate.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an empty data key, a wrong-length vector and a tag-less ciphertext are refused")
    void eachPartIsHeldToItsLength() {
        assertThatThrownBy(() -> EncryptedCvv.wrap(new byte[0], INITIALISATION_VECTOR, CIPHERTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encipheredDataKey");
        assertThatThrownBy(() -> EncryptedCvv.wrap(ENCIPHERED_DATA_KEY, new byte[8], CIPHERTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialisationVector");
        assertThatThrownBy(
                () -> EncryptedCvv.wrap(ENCIPHERED_DATA_KEY, INITIALISATION_VECTOR, new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ciphertext");
    }

    /**
     * The rendering names the length and discloses no byte of the envelope.
     *
     * <p>Assumptions: a rendering is what a log line, an assertion message or a framework diagnostic
     * picks up automatically, so it is asserted rather than assumed. The length is asserted PRESENT
     * because a rendering that said only that a value existed would not distinguish a stored envelope
     * from an empty one.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the rendering carries the length and no byte of the envelope")
    void theRenderingDisclosesNothing() {
        EncryptedCvv wrapped =
                EncryptedCvv.wrap(ENCIPHERED_DATA_KEY, INITIALISATION_VECTOR, CIPHERTEXT);

        assertThat(wrapped).hasToString(
                "EncryptedCvv[envelopeBytes=" + wrapped.envelope().length + "]");
    }

    /**
     * Builds an array of the requested length filled with one byte value.
     *
     * <p>Assumptions: a fixed fill rather than random bytes, so a failure reports the same value twice
     * rather than a different one each run. Nothing under test depends on the bytes being unpredictable --
     * these stand in for a data key and a ciphertext, and the framing does not inspect their contents.</p>
     *
     * @param length how many bytes to produce
     * @param value the byte to fill with
     * @return the filled array, never {@code null}
     */
    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
