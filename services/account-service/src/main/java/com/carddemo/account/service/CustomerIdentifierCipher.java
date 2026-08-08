package com.carddemo.account.service;

import com.carddemo.account.mapper.CustomerMapper;
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
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * The one boundary that enciphers a protected customer identifier into the bytes its column stores.
 *
 * <h2>What this is for</h2>
 *
 * <p><b>Purpose.</b> The reference holds both identifiers in the clear on a file whose resource
 * definition declares {@code RECOVERY(NONE)} and {@code READINTEG(UNCOMMITTED)} -- the {@code CUSTDAT}
 * stanza opening at {@code app/csd/CARDDEMO.CSD} L50, with L53 and L59 carrying those two options --
 * namely {@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy} L17 and
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)} at L18. The migrated schema declares {@code ssn_encrypted BYTEA}
 * and {@code govt_issued_id_encrypted BYTEA} instead. This class supplies the encipherment that makes
 * those column names true: it is the sole implementation of the port
 * {@link CustomerMapper.CustomerIdentifierProtection} that the mapping layer declares, and it is the
 * only place either identifier exists as plaintext in this service.</p>
 *
 * <p>Refactoring Rationale: the port was declared and required through
 * {@code CustomerMapper}'s constructor while no implementation and no bean existed. Because that mapper
 * is component-scanned, the effect was that this service could not create its application context at
 * all, and the encryption boundary the schema promises had no production implementation -- a column typed
 * {@code BYTEA} and named for ciphertext does not encrypt anything by itself. Authoring the
 * implementation rather than relaxing the constructor is the direction that closes both problems at
 * once: a mapper that tolerated an absent boundary would start, and would then write plaintext into a
 * column named for ciphertext with nothing detecting it.</p>
 *
 * <p>Assumptions: ENVELOPE encryption, not direct encryption under the customer-managed key. The
 * key-management service returns a fresh data key per value, this class enciphers with that data key
 * locally, and the enciphered data key travels inside the stored envelope. The alternative -- calling the
 * service to encipher each identifier directly -- would put the plaintext on the wire to a second
 * service and into that service's request path, which is the one path a national identifier must not
 * travel, and it would bind throughput to a service call per value with a request quota attached. The
 * sibling boundary in the card context reached the same conclusion for the same reason, and this class
 * follows it deliberately so the migration has one encipherment shape rather than two.</p>
 *
 * <p>Alternatives Considered: caching data keys so that many identifiers share one. Rejected because the
 * cached key becomes plaintext key material held in the process for as long as the cache lives, and
 * because the saving is against a call made once per customer write -- a path this service performs on
 * an account update, not on a read. The cost accepted is one key-management call per encipherment.</p>
 *
 * <h2>Why the encryption context names the column and not the customer</h2>
 *
 * <p>Alternatives Considered: binding each ciphertext to its customer by putting the customer identifier
 * in the encryption context, which is the idiomatic way to stop an envelope being moved between rows.
 * Rejected outright, and the ground is specific rather than stylistic: an encryption context is NOT
 * secret. It is recorded in the key-management service's own request log and appears verbatim in the
 * audit trail of every encipher call, so a customer identifier placed there would write a directly
 * identifying value into an audit stream retained longer than the data and read by more people. The
 * context therefore names the PURPOSE and the COLUMN only.</p>
 *
 * <p>Trade-offs: naming the column still buys something the purpose alone does not. The two identifiers
 * live in one row under one key, so a context carrying only a purpose would let an envelope written for
 * the government-issued identifier be deciphered as though it were the national identifier -- a
 * twenty-character value read as a nine-digit one. Including the column makes that substitution fail its
 * authentication check instead of succeeding quietly. What remains accepted is that an envelope moved
 * between two rows' SAME column still deciphers; the row itself is the binding, and neither identifier
 * has any read path in this service at all -- both output shapes publish a fixed marker -- so a moved
 * envelope is a database-integrity fault reachable only by direct table access.</p>
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <p>Assumptions: there is no decipher method, and the asymmetry is inherited from the port rather than
 * introduced here. {@code com.carddemo.account.domain.Customer} publishes no accessor for either
 * ciphertext field, and both published shapes mask unconditionally, so no caller in this service could
 * reach a decipher operation. A declared operation no caller can reach would advertise a capability this
 * boundary does not offer, and the reasoning is recorded on the port itself. A re-key or a future
 * disclosure path would add the operation together with the caller that needs it, and would then also
 * have to add the {@code Decrypt} action to this service's task role, which today it does not hold.</p>
 *
 * <p>Assumptions: no value is logged, not even at debug, and no failure message quotes an identifier.
 * Every refusal below names the column and the observed length and never the content, which is the same
 * discipline the diagnostic renderings of this context follow under the sensitive-data logging contract
 * in {@code docs/architecture/observability.md}.</p>
 *
 * <p><strong>Return value.</strong> Each member documents its own return value.</p>
 */
@Component
public class CustomerIdentifierCipher implements CustomerMapper.CustomerIdentifierProtection {

    /**
     * The transformation the data key is used under.
     *
     * <p>Assumptions: an AUTHENTICATED mode, so that a modified ciphertext fails to decipher rather than
     * producing a plausible identifier. That property is why the mode is named here rather than left to a
     * default: an unauthenticated mode would let a single flipped byte in the column yield a different
     * national identifier with nothing detecting the change.</p>
     */
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** The algorithm the data key material belongs to. */
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
     * The initialisation-vector length, in bytes.
     *
     * <p>Assumptions: twelve, which is the length the mode's own specification names as the one requiring
     * no further derivation step. A different length is legal and is then hashed down internally, so
     * choosing twelve keeps the vector this class generates the vector the mode actually uses.</p>
     */
    static final int INITIALISATION_VECTOR_LENGTH = 12;

    /**
     * The four bytes every envelope this class produces begins with.
     *
     * <p>Assumptions: a magic distinct from the card context's own envelope magic, so that a byte array
     * recovered from one column cannot be read as an envelope of the other kind. The letters spell the
     * bounded context and the value it protects.</p>
     */
    static final byte[] ENVELOPE_MAGIC = {'C', 'D', 'C', 'I'};

    /**
     * The envelope format this class writes.
     *
     * <p>Assumptions: the version is stored rather than assumed so that a re-key introducing a second
     * framing can be told apart from this one by a reader holding only the bytes. Nothing reads it today,
     * which is exactly why it has to be written today: a format that did not record its own version could
     * never gain a second one without ambiguity.</p>
     */
    static final byte FORMAT_VERSION = 1;

    /** The number of bytes the enciphered-data-key length occupies in the envelope header. */
    static final int KEY_LENGTH_FIELD_BYTES = 2;

    /**
     * The key of the encryption-context entry naming what a ciphertext is for.
     */
    private static final String CONTEXT_PURPOSE_KEY = "carddemo:purpose";

    /**
     * The value of the encryption-context entry naming what a ciphertext is for.
     *
     * <p>Assumptions: a fixed literal, so an envelope produced here cannot be deciphered by a caller
     * asking under any other purpose. It names no customer, for the audit-trail reason on the class.</p>
     */
    private static final String CONTEXT_PURPOSE_VALUE = "customer-identifier";

    /** The key of the encryption-context entry naming which column a ciphertext belongs to. */
    private static final String CONTEXT_COLUMN_KEY = "carddemo:column";

    /**
     * The largest clear identifier this boundary accepts, in characters.
     *
     * <p>Assumptions: twenty, from {@code CUST-GOVT-ISSUED-ID PIC X(20)} at
     * {@code app/cpy/CVCUS01Y.cpy} L18, which is the wider of the two protected fields -- the national
     * identifier is {@code PIC 9(09)} at L17. The bound is asserted so that a value which is not one of
     * these two identifiers is refused before a key is fetched, rather than being enciphered and stored
     * as though it were one. It is deliberately a MAXIMUM rather than an exact width, because one port
     * serves two fields of different declared widths and because the mapper composes the national
     * identifier from three screen parts before it arrives here.</p>
     */
    static final int MAX_CLEAR_LENGTH = 20;

    /** The key-management client data keys are obtained from and enciphered data keys returned by. */
    private final KmsClient kms;

    /**
     * The identifier of the customer-managed key data keys are generated under.
     *
     * <p>Assumptions: an identifier supplied by the deployment and carrying no default. A default would
     * be a key this code chose on a deployment's behalf, and the wrong key is not a recoverable mistake
     * once identifiers have been written under it.</p>
     */
    private final String keyId;

    /**
     * The source of initialisation vectors.
     *
     * <p>Assumptions: one instance held for the life of the bean rather than one per call. This generator
     * is thread-safe and seeds itself from the platform, and constructing one per call is a documented
     * way to make it slow without making it stronger.</p>
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
    public CustomerIdentifierCipher(KmsClient kms,
            @Value("${carddemo.security.customer-identifier.key-id}") String keyId) {
        this.kms = Objects.requireNonNull(kms, "kms must not be null");
        Objects.requireNonNull(keyId, "carddemo.security.customer-identifier.key-id must not be null");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException(
                    "carddemo.security.customer-identifier.key-id must not be blank; a blank key"
                            + " identifier would be resolved by the key-management service as an absent"
                            + " argument and the failure would surface on the first account update"
                            + " rather than at start-up");
        }
        this.keyId = keyId;
    }

    /**
     * Enciphers one clear identifier into the framed envelope its column stores.
     *
     * <p>Assumptions: the plaintext bytes and the data key are both CLEARED before this method returns,
     * on every path including the failing one. Neither can be cleared from the immutable string the
     * mapper passed in, which is why the argument is documented as the caller's own responsibility, but
     * the two copies this method makes are its own and are not left in the heap for a dump to pick
     * up.</p>
     *
     * <p>Assumptions: the characters are encoded as US-ASCII rather than through the platform default.
     * Both protected fields are declared as display data over the reference's own character repertoire,
     * so a task started with a different default would otherwise store a different byte sequence for the
     * same identifier and nothing downstream could detect it.</p>
     *
     * @param clearText the identifier exactly as it is to be stored, at its declared character width;
     *     must not be {@code null}, must not be empty and must not exceed {@value #MAX_CLEAR_LENGTH}
     *     characters. The caller owns the string and this method cannot clear it, a string being
     *     immutable
     * @param field the column the value belongs to, which becomes part of the encryption context so that
     *     an envelope written for one column cannot be deciphered as the other; must not be {@code null}
     *     or blank
     * @return the framed envelope to store, never {@code null} and never empty
     * @throws NullPointerException if {@code clearText} or {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code clearText} is empty or wider than
     *     {@value #MAX_CLEAR_LENGTH} characters, or if {@code field} is blank
     * @throws IllegalStateException if the platform refuses the transformation or the key material, or
     *     the key-management service does not return a usable data key, which is a deployment fault
     *     rather than a bad argument and which the mapper propagates rather than catching, because a row
     *     written with an unprotected identifier is worse than a request that fails
     */
    @Override
    public byte[] encrypt(String clearText, String field) {
        Objects.requireNonNull(clearText, "clearText is required");
        Objects.requireNonNull(field, "field is required");
        requireColumn(field);
        requireIdentifierShape(clearText, field);

        GenerateDataKeyResponse dataKey = this.kms.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(this.keyId)
                .keySpec(DataKeySpec.AES_256)
                .encryptionContext(encryptionContext(field))
                .build());

        byte[] keyMaterial = dataKey.plaintext().asByteArray();
        byte[] plaintext = clearText.getBytes(StandardCharsets.US_ASCII);
        try {
            byte[] initialisationVector = new byte[INITIALISATION_VECTOR_LENGTH];
            this.random.nextBytes(initialisationVector);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyMaterial, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_LENGTH_BITS, initialisationVector));
            return frame(dataKey.ciphertextBlob().asByteArray(), initialisationVector,
                    cipher.doFinal(plaintext));
        } catch (GeneralSecurityException refused) {
            // Assumptions: the cause is attached and the message names no value. A security-provider
            // failure message can quote the argument it rejected, and the argument here is a national
            // identifier, so the wrapper states what failed and which column, and lets the cause carry
            // the rest to a stack trace rather than into a message a handler might render.
            throw new IllegalStateException("the platform refused the customer-identifier"
                    + " transformation " + TRANSFORMATION + " for column " + field, refused);
        } finally {
            Arrays.fill(keyMaterial, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Frames an enciphered data key, its initialisation vector and the ciphertext into one byte array.
     *
     * <p>Assumptions: the envelope is SELF-DESCRIBING rather than three columns. One {@code BYTEA}
     * column holding a framed value cannot be half written, whereas three columns can, and the schema
     * declares one column per identifier. The header is fixed width and the enciphered data key's length
     * is stored, so the reader needs no length agreed out of band.</p>
     *
     * <p>Alternatives Considered: storing the three parts concatenated with no header, on the grounds
     * that the data key's length is constant for a given key specification. Rejected because it is
     * constant only for as long as nothing changes: a re-key to a different specification would produce a
     * value the reader still parsed, at the wrong split point, and the failure would look like corrupt
     * ciphertext rather than a format change.</p>
     *
     * @param encipheredDataKey the data key as the key-management service returned it enciphered; must
     *     not be {@code null} and must be short enough to record its length in
     *     {@value #KEY_LENGTH_FIELD_BYTES} bytes
     * @param initialisationVector exactly {@value #INITIALISATION_VECTOR_LENGTH} bytes
     * @param ciphertext the enciphered identifier including its authentication tag; must not be
     *     {@code null}
     * @return the framed envelope, never {@code null}
     * @throws IllegalStateException if the enciphered data key is longer than the length field can record
     */
    private static byte[] frame(byte[] encipheredDataKey, byte[] initialisationVector,
            byte[] ciphertext) {
        int maxRecordableKeyLength = (1 << (KEY_LENGTH_FIELD_BYTES * Byte.SIZE)) - 1;
        if (encipheredDataKey.length > maxRecordableKeyLength) {
            throw new IllegalStateException("the enciphered data key is " + encipheredDataKey.length
                    + " bytes, which the " + KEY_LENGTH_FIELD_BYTES
                    + "-byte length field of this envelope cannot record");
        }
        int headerLength = ENVELOPE_MAGIC.length + 1 + KEY_LENGTH_FIELD_BYTES;
        byte[] framed = new byte[headerLength + encipheredDataKey.length
                + INITIALISATION_VECTOR_LENGTH + ciphertext.length];
        System.arraycopy(ENVELOPE_MAGIC, 0, framed, 0, ENVELOPE_MAGIC.length);
        framed[ENVELOPE_MAGIC.length] = FORMAT_VERSION;
        framed[ENVELOPE_MAGIC.length + 1] = (byte) (encipheredDataKey.length >>> Byte.SIZE);
        framed[ENVELOPE_MAGIC.length + 2] = (byte) encipheredDataKey.length;
        int cursor = headerLength;
        System.arraycopy(encipheredDataKey, 0, framed, cursor, encipheredDataKey.length);
        cursor += encipheredDataKey.length;
        System.arraycopy(initialisationVector, 0, framed, cursor, INITIALISATION_VECTOR_LENGTH);
        cursor += INITIALISATION_VECTOR_LENGTH;
        System.arraycopy(ciphertext, 0, framed, cursor, ciphertext.length);
        return framed;
    }

    /**
     * Returns the encryption context this encipherment binds to.
     *
     * <p>Assumptions: built fresh on each call rather than held as a constant map, because the map is
     * handed to a client that may retain it and because the column entry varies by call.</p>
     *
     * @param field the column the value belongs to; must not be {@code null}
     * @return the context, never {@code null} and never naming a customer
     */
    private static Map<String, String> encryptionContext(String field) {
        return Map.of(CONTEXT_PURPOSE_KEY, CONTEXT_PURPOSE_VALUE, CONTEXT_COLUMN_KEY, field);
    }

    /**
     * Refuses a blank column name before any key is fetched.
     *
     * <p>Assumptions: a blank column would silently widen the encryption context's meaning -- every
     * envelope would then share one context and the substitution this class exists to prevent would
     * become possible again -- so it is refused rather than defaulted.</p>
     *
     * @param field the column name to check; must not be {@code null}
     * @throws IllegalArgumentException if the column name is blank
     */
    private static void requireColumn(String field) {
        if (field.isBlank()) {
            throw new IllegalArgumentException("the column a protected identifier belongs to must be"
                    + " named, because it forms part of the encryption context that keeps one column's"
                    + " envelope from deciphering as another's");
        }
    }

    /**
     * Refuses a value that cannot be one of the two protected identifiers, before any key is fetched.
     *
     * <p>Assumptions: the refusal names the column and the observed length and never the value, for the
     * same reason every other refusal on this path does. Ordering it before the key-management call also
     * means a malformed value costs no request against that service's quota.</p>
     *
     * @param candidate the value to check; must not be {@code null}
     * @param field the column the value belongs to, named in the refusal
     * @throws IllegalArgumentException if the value is empty or wider than {@value #MAX_CLEAR_LENGTH}
     *     characters
     */
    private static void requireIdentifierShape(String candidate, String field) {
        if (candidate.isEmpty() || candidate.length() > MAX_CLEAR_LENGTH) {
            throw new IllegalArgumentException("a protected customer identifier is between 1 and "
                    + MAX_CLEAR_LENGTH + " characters, the upper bound being CUST-GOVT-ISSUED-ID"
                    + " PIC X(20) at app/cpy/CVCUS01Y.cpy L18; the value offered for column " + field
                    + " was " + candidate.length() + " characters and is not one");
        }
    }
}
