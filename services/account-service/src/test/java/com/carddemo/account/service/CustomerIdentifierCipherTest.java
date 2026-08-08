package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Pins what the customer-identifier encipherment produces and, above all, what it never produces.
 *
 * <p>Purpose: {@link CustomerIdentifierCipher} is the only implementation of the encryption boundary the
 * mapping layer declares, and the two values it protects -- the national identifier and the
 * government-issued identifier -- have no read path anywhere in this service. That absence is what makes
 * these cases necessary rather than merely useful: nothing downstream would notice if the boundary stored
 * the clear characters, because nothing downstream ever reads the column back. The assertions therefore
 * work on the bytes the boundary returns and on the request it sent, which are the only two observable
 * surfaces it has.
 *
 * <p>Assumptions: the key-management client is a substitute and no key is reached. The data key it returns
 * is a fixed thirty-two byte pattern, so the ciphertext is deterministic in length while remaining
 * unpredictable in content, and every case asserting an absence can name the exact characters that must
 * not appear. A real key would make the same assertions dependent on an account.
 *
 * <p>Alternatives Considered: asserting round-trip equality by deciphering what was enciphered, which is
 * how the sibling card boundary is tested. Not available here: the port declares one direction only,
 * because {@code Customer} publishes no accessor for either ciphertext field and both output shapes mask
 * unconditionally, so there is no decipher operation to round-trip through. What replaces it is a direct
 * assertion that the clear characters are absent from the envelope, which is the property the round trip
 * would have been standing in for.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised methods below carry their own.
 */
class CustomerIdentifierCipherTest {

    /**
     * The key alias the cipher is constructed with.
     *
     * <p>Assumptions: fabricated and visibly non-production. It travels into a captured request rather
     * than to a service, so a real alias would couple this class to an account without strengthening a
     * single assertion.
     */
    private static final String KEY_ALIAS = "alias/carddemo-customer-identifier-test";

    /**
     * The column the national identifier is stored in, as {@code V1__account.sql} names it.
     */
    private static final String SSN_COLUMN = "ssn_encrypted";

    /**
     * The column the government-issued identifier is stored in, as {@code V1__account.sql} names it.
     */
    private static final String GOVERNMENT_ID_COLUMN = "govt_issued_id_encrypted";

    /**
     * A nine-digit national identifier at the declared width of {@code CUST-SSN PIC 9(09)}.
     *
     * <p>Assumptions: authored rather than taken from the seed extract, so it identifies no real person,
     * and chosen with no repeated run of digits so that an absence assertion over the envelope cannot pass
     * or fail by coincidence.
     */
    private static final String NATIONAL_IDENTIFIER = "418736925";

    /** The substitute key-management client every case is driven through. */
    private KmsClient kms;

    /** The boundary under test. */
    private CustomerIdentifierCipher cipher;

    /**
     * Builds a fresh substitute client and boundary before each case.
     *
     * <p>Assumptions: a new substitute per case rather than one shared, because several cases capture the
     * request that was sent and a shared substitute would accumulate invocations across them.
     */
    @BeforeEach
    void setUp() {
        this.kms = mock(KmsClient.class);
        when(this.kms.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenReturn(GenerateDataKeyResponse.builder()
                        .keyId(KEY_ALIAS)
                        .plaintext(SdkBytes.fromByteArray(fixedDataKey()))
                        .ciphertextBlob(SdkBytes.fromByteArray(fixedEncipheredDataKey()))
                        .build());
        this.cipher = new CustomerIdentifierCipher(this.kms, KEY_ALIAS);
    }

    /**
     * Confirms the envelope carries the declared framing rather than bare ciphertext.
     *
     * <p>Assumptions: the framing is asserted because it is what a future reader has to parse. A boundary
     * that returned the three parts concatenated with no header would satisfy every confidentiality
     * assertion in this class and would still be unreadable after a re-key changed the data key's length.
     */
    @Test
    void envelopeBeginsWithTheDeclaredMagicAndVersion() {
        byte[] envelope = this.cipher.encrypt(NATIONAL_IDENTIFIER, SSN_COLUMN);

        assertThat(Arrays.copyOf(envelope, CustomerIdentifierCipher.ENVELOPE_MAGIC.length))
                .isEqualTo(CustomerIdentifierCipher.ENVELOPE_MAGIC);
        assertThat(envelope[CustomerIdentifierCipher.ENVELOPE_MAGIC.length])
                .isEqualTo(CustomerIdentifierCipher.FORMAT_VERSION);
    }

    /**
     * Confirms the enciphered data key's recorded length matches the bytes that follow it.
     *
     * <p>Assumptions: this is the one header field a reader depends on arithmetically, so it is asserted
     * against the substitute's own key length rather than against a constant. A header that recorded the
     * wrong length would split the envelope at the wrong offset and the failure would look like corrupt
     * ciphertext.
     */
    @Test
    void envelopeRecordsTheEncipheredDataKeyLength() {
        byte[] envelope = this.cipher.encrypt(NATIONAL_IDENTIFIER, SSN_COLUMN);

        int high = envelope[CustomerIdentifierCipher.ENVELOPE_MAGIC.length + 1] & 0xFF;
        int low = envelope[CustomerIdentifierCipher.ENVELOPE_MAGIC.length + 2] & 0xFF;
        int recorded = (high << Byte.SIZE) | low;

        assertThat(recorded).isEqualTo(fixedEncipheredDataKey().length);

        int header = CustomerIdentifierCipher.ENVELOPE_MAGIC.length + 1
                + CustomerIdentifierCipher.KEY_LENGTH_FIELD_BYTES;
        assertThat(envelope).hasSizeGreaterThan(header + recorded
                + CustomerIdentifierCipher.INITIALISATION_VECTOR_LENGTH);
    }

    /**
     * Confirms the clear identifier appears nowhere in the envelope, in any encoding this path could use.
     *
     * <p>Assumptions: BOTH candidate encodings are asserted absent -- the ASCII bytes the method writes
     * and the characters as they would appear in a text rendering of the envelope -- because a boundary
     * that skipped the cipher entirely would leave the value in one of the two and an assertion over only
     * the other would pass. This is the single most important case in the class: it is the assertion that
     * the column named for ciphertext holds ciphertext.
     *
     * @param identifier a protected identifier at one of the two declared widths
     */
    @ParameterizedTest
    @ValueSource(strings = {NATIONAL_IDENTIFIER, "D1234567890123456789"})
    void envelopeNeverContainsTheClearIdentifier(String identifier) {
        String column = identifier.length() == NATIONAL_IDENTIFIER.length()
                ? SSN_COLUMN
                : GOVERNMENT_ID_COLUMN;

        byte[] envelope = this.cipher.encrypt(identifier, column);

        assertThat(indexOf(envelope, identifier.getBytes(StandardCharsets.US_ASCII))).isEqualTo(-1);
        assertThat(new String(envelope, StandardCharsets.ISO_8859_1)).doesNotContain(identifier);
    }

    /**
     * Confirms two encipherments of one identifier differ, so the column cannot be searched by equality.
     *
     * <p>Assumptions: this is what a fresh initialisation vector per call buys, and it is asserted rather
     * than assumed because a deterministic envelope would let an adversary holding the table confirm a
     * guessed identifier by enciphering the guess -- the same confirmable-token weakness the shared kernel
     * records against an unkeyed digest. The substitute returns the SAME data key on both calls, so any
     * difference observed here comes from the vector alone.
     */
    @Test
    void twoEnciphermentsOfOneIdentifierDiffer() {
        byte[] first = this.cipher.encrypt(NATIONAL_IDENTIFIER, SSN_COLUMN);
        byte[] second = this.cipher.encrypt(NATIONAL_IDENTIFIER, SSN_COLUMN);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).hasSameSizeAs(second);
    }

    /**
     * Confirms the request names the configured key, an authenticated key specification and the context.
     *
     * <p>Assumptions: the encryption context is asserted to name the purpose AND the column, and to name
     * neither the customer nor the identifier. An encryption context is recorded verbatim in the key
     * service's own audit trail, so a customer identifier placed there would be written into a stream
     * retained longer than the data -- which is exactly the disclosure the class's own rationale refuses.
     */
    @Test
    void requestNamesTheKeyTheSpecificationAndAPurposeBoundContext() {
        this.cipher.encrypt(NATIONAL_IDENTIFIER, SSN_COLUMN);

        ArgumentCaptor<GenerateDataKeyRequest> sent =
                ArgumentCaptor.forClass(GenerateDataKeyRequest.class);
        verify(this.kms).generateDataKey(sent.capture());
        GenerateDataKeyRequest request = sent.getValue();

        assertThat(request.keyId()).isEqualTo(KEY_ALIAS);
        assertThat(request.keySpec()).isEqualTo(DataKeySpec.AES_256);
        assertThat(request.encryptionContext())
                .containsEntry("carddemo:purpose", "customer-identifier")
                .containsEntry("carddemo:column", SSN_COLUMN)
                .hasSize(2);
        assertThat(request.encryptionContext().values()).doesNotContain(NATIONAL_IDENTIFIER);
    }

    /**
     * Confirms the two columns are enciphered under different contexts, so one cannot open the other.
     *
     * <p>Assumptions: this is the reason the port takes a column name at all. Both identifiers live in one
     * row under one key, so a context carrying only a purpose would let a twenty-character value be read
     * back as a nine-digit one; naming the column makes that substitution fail its authentication check.
     */
    @Test
    void eachColumnIsEncipheredUnderItsOwnContext() {
        this.cipher.encrypt(NATIONAL_IDENTIFIER, SSN_COLUMN);
        this.cipher.encrypt("D1234567890123456789", GOVERNMENT_ID_COLUMN);

        ArgumentCaptor<GenerateDataKeyRequest> sent =
                ArgumentCaptor.forClass(GenerateDataKeyRequest.class);
        verify(this.kms, org.mockito.Mockito.times(2)).generateDataKey(sent.capture());

        assertThat(sent.getAllValues().get(0).encryptionContext())
                .containsEntry("carddemo:column", SSN_COLUMN);
        assertThat(sent.getAllValues().get(1).encryptionContext())
                .containsEntry("carddemo:column", GOVERNMENT_ID_COLUMN);
    }

    /**
     * Confirms a value that cannot be either identifier is refused before any key is fetched.
     *
     * <p>Assumptions: the ordering is asserted as well as the refusal. A malformed value that reached the
     * key service would cost a request against that service's quota and would put the value into the
     * request path, and both are avoidable by checking first.
     *
     * @param malformed a value that is empty or wider than the twenty-character upper bound
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "D12345678901234567890"})
    void aValueThatIsNotAnIdentifierIsRefusedWithoutFetchingAKey(String malformed) {
        assertThatThrownBy(() -> this.cipher.encrypt(malformed, SSN_COLUMN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SSN_COLUMN)
                .hasMessageContaining(String.valueOf(malformed.length()))
                .hasMessageNotContaining(malformed.isEmpty() ? "\u0000" : malformed);

        verify(this.kms, org.mockito.Mockito.never()).generateDataKey(any(GenerateDataKeyRequest.class));
    }

    /**
     * Confirms a blank column name is refused, so no envelope is written under a widened context.
     *
     * <p>Assumptions: a blank column would collapse both identifiers onto one context and reopen the
     * substitution the column entry exists to prevent, so it is refused rather than defaulted.
     */
    @Test
    void aBlankColumnNameIsRefused() {
        assertThatThrownBy(() -> this.cipher.encrypt(NATIONAL_IDENTIFIER, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encryption context");

        verify(this.kms, org.mockito.Mockito.never()).generateDataKey(any(GenerateDataKeyRequest.class));
    }

    /**
     * Confirms a blank key identifier is refused at construction rather than on the first write.
     *
     * <p>Assumptions: construction is where this has to fail. A blank identifier is resolved by the key
     * service as an absent argument, so tolerating it would move the failure to the first account update
     * -- the least convenient moment to discover a deployment that published no key.
     */
    @Test
    void aBlankKeyIdentifierIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new CustomerIdentifierCipher(this.kms, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carddemo.security.customer-identifier.key-id");
    }

    /**
     * Returns the fixed data-key material the substitute hands back.
     *
     * <p>Assumptions: thirty-two bytes, matching the specification the cipher asks for, with a varying
     * pattern rather than zeroes so that a boundary which failed to initialise the cipher would not
     * accidentally produce plausible-looking output.
     *
     * @return exactly thirty-two bytes, never {@code null}
     */
    private static byte[] fixedDataKey() {
        byte[] material = new byte[32];
        for (int index = 0; index < material.length; index++) {
            material[index] = (byte) (index * 7 + 3);
        }
        return material;
    }

    /**
     * Returns the fixed enciphered form of the data key the substitute hands back.
     *
     * <p>Assumptions: a length unlike the plaintext key's, so a framing mistake that recorded one length
     * and copied the other is detectable rather than coincidentally correct.
     *
     * @return the stand-in enciphered data key, never {@code null}
     */
    private static byte[] fixedEncipheredDataKey() {
        byte[] blob = new byte[77];
        Arrays.fill(blob, (byte) 0xA5);
        return blob;
    }

    /**
     * Finds the first offset at which one byte sequence occurs inside another.
     *
     * <p>Assumptions: written out rather than taken from a library, because the assertion it serves is a
     * negative one over a byte array and the available matchers work on text. Reducing the envelope to
     * text first would introduce a decode step that could itself lose the very bytes being searched for.
     *
     * @param haystack the sequence to search; must not be {@code null}
     * @param needle the sequence to find; must not be {@code null} and must not be empty
     * @return the first offset at which {@code needle} occurs, or {@code -1} when it does not occur
     */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return start;
        }
        return -1;
    }
}
