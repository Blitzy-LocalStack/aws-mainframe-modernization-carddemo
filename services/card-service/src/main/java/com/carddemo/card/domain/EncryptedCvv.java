package com.carddemo.card.domain;

import java.util.Arrays;
import java.util.Objects;

/**
 * The enciphered card verification value, as a self-describing envelope that cannot hold plaintext.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>The verification value was held on {@link Card} as a bare {@code byte[]} named for what it was
 * supposed to contain. Nothing checked the name against the contents, so the three ASCII bytes
 * {@code 123} -- the baseline's own {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy} line 7,
 * in the clear -- were valid entity state for a column called {@code cvv_encrypted}, and a mapper that
 * assigned the digits straight across would have written them to the database with nothing raising a
 * fault. Documentation stating that the value arriving is "already enciphered" is not a check: it holds
 * only while every writer remembers it, and there was no writer at all to remember it, so the first one
 * authored would have had nothing to be refused by.</p>
 *
 * <p>Refactoring Rationale: a TYPE replaces the convention. An instance of this class can only be
 * obtained by presenting bytes that carry the envelope framing below, and the framing is not something a
 * plaintext value can accidentally satisfy: the shortest well-formed envelope is
 * {@value #MIN_ENVELOPE_LENGTH} bytes and begins with a four-byte marker. A three-digit verification
 * value is therefore not merely discouraged from reaching the column -- it cannot be expressed as the
 * value the column's mapped attribute holds. That is the difference between a documented obligation and
 * an enforced one, and this member is the one where the difference is a payment-data disclosure.</p>
 *
 * <h2>The envelope format</h2>
 *
 * <p>Assumptions: the framing is self-describing so that a value can be VALIDATED without a key. That is
 * the property the whole design rests on -- the persistence boundary has to be able to refuse a
 * malformed value on the way in and on the way out, and it holds no key material and must not acquire
 * any. The layout is, in order:</p>
 *
 * <ul>
 *   <li>{@value #MAGIC_LENGTH} bytes of marker, the ASCII characters {@code CDCV}, which is what makes a
 *       value that is not an envelope recognisable as such rather than merely the wrong length.</li>
 *   <li>one byte of format version, currently {@value #FORMAT_VERSION}. A version byte is carried from
 *       the first release rather than added when it is first needed, because a stored ciphertext outlives
 *       the code that wrote it and a re-key or a cipher change has to be able to tell the generations
 *       apart. Adding the byte later would require reading every stored value to decide which generation
 *       it belonged to.</li>
 *   <li>two bytes of unsigned big-endian length for the enciphered data key that follows.</li>
 *   <li>the enciphered data key itself, as the key-management service returned it. It is opaque here: its
 *       length is a property of that service and of the key, so it is carried rather than assumed.</li>
 *   <li>{@value #INITIALISATION_VECTOR_LENGTH} bytes of initialisation vector. Twelve is the length the
 *       authenticated cipher mode this envelope is written for takes natively, so no derivation step is
 *       needed and none is performed.</li>
 *   <li>the remaining bytes, which are the ciphertext with its authentication tag appended.</li>
 * </ul>
 *
 * <p>Assumptions: the ciphertext and its tag are ONE run rather than two members. The authenticated
 * cipher this envelope is written for appends the tag to the ciphertext and consumes it the same way, so
 * separating them here would mean splitting a value only to rejoin it, with a second length field to get
 * wrong.</p>
 *
 * <p>Alternatives Considered: storing the raw ciphertext with no framing and keeping the key identifier,
 * the vector and the version in separate columns. Rejected because it makes the ciphertext meaningless
 * without four columns read together and, decisively, because it removes the property that a value can be
 * checked at all: a bare ciphertext column accepts any bytes, which is exactly the state this class was
 * introduced to leave.</p>
 *
 * <p>Alternatives Considered: enciphering inside this class, so a caller could hand over the three digits
 * and receive an instance. Rejected because it would put a key-management client inside a domain type,
 * which the tree's architecture rule refuses on the ground that a domain type must stay exercisable
 * without the platform it is deployed on -- and because it would make the plaintext reachable from an
 * entity any query can hydrate. The enciphering boundary is
 * {@code com.carddemo.card.service.CardVerificationValueCipher}, and this class is the storage form on
 * both sides of it.</p>
 *
 * <p><strong>Return value.</strong> This class is an immutable value; each member documents its own
 * return value.</p>
 */
public final class EncryptedCvv {

    /**
     * The number of leading bytes carrying the format marker.
     */
    public static final int MAGIC_LENGTH = 4;

    /**
     * The format version this class writes and the only one it reads.
     *
     * <p>Assumptions: a single accepted version rather than a range. There is exactly one format, so a
     * value carrying any other version byte was not written by this system and is refused rather than
     * interpreted; widening this to a range is the change a second format would come with.</p>
     */
    public static final byte FORMAT_VERSION = 1;

    /**
     * The number of bytes the initialisation vector occupies.
     *
     * <p>Assumptions: twelve, the native length of the authenticated cipher mode this envelope is written
     * for. A different length would work but would force the cipher to derive one, which is a step that
     * can differ between an encrypting and a deciphering caller.</p>
     */
    public static final int INITIALISATION_VECTOR_LENGTH = 12;

    /**
     * The shortest ciphertext-with-tag run this envelope admits.
     *
     * <p>Assumptions: seventeen, being a sixteen-byte authentication tag plus at least one byte of
     * ciphertext. A shorter run cannot carry a tag at all, so it is not a truncated envelope but a value
     * that was never one.</p>
     */
    public static final int MIN_CIPHERTEXT_LENGTH = 17;

    /**
     * The shortest well-formed envelope, in bytes.
     *
     * <p>Assumptions: derived from the fixed parts plus one byte of enciphered data key plus the shortest
     * ciphertext run, so that changing any component length cannot leave this figure behind. It is far
     * above the three bytes a plaintext verification value occupies, which is the property that makes the
     * plaintext inexpressible rather than merely discouraged.</p>
     */
    public static final int MIN_ENVELOPE_LENGTH =
            MAGIC_LENGTH + 1 + 2 + 1 + INITIALISATION_VECTOR_LENGTH + MIN_CIPHERTEXT_LENGTH;

    /**
     * The format marker, as the bytes it occupies.
     *
     * <p>Assumptions: held privately and copied on every use, because an array constant is mutable and a
     * shared reference to one is a constant only by convention.</p>
     */
    private static final byte[] MAGIC = {'C', 'D', 'C', 'V'};

    /**
     * The offset of the version byte.
     */
    private static final int VERSION_OFFSET = MAGIC_LENGTH;

    /**
     * The offset of the two-byte enciphered-data-key length.
     */
    private static final int KEY_LENGTH_OFFSET = VERSION_OFFSET + 1;

    /**
     * The offset the enciphered data key begins at.
     */
    private static final int KEY_OFFSET = KEY_LENGTH_OFFSET + 2;

    /**
     * The mask that reads one byte as an unsigned value.
     */
    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    /**
     * The number of bit positions the high byte of the length field is shifted by.
     */
    private static final int HIGH_BYTE_SHIFT = 8;

    /**
     * The largest enciphered data key this framing can express, from the two-byte length field.
     */
    private static final int MAX_KEY_LENGTH = 0xFFFF;

    /**
     * The framed bytes, owned entirely by this instance.
     */
    private final byte[] envelope;

    /**
     * Wraps bytes already known to be a well-formed envelope.
     *
     * <p>Assumptions: private, so that every route into this type passes one of the two factories below
     * and therefore passes the framing check. A public constructor taking bytes would be the raw setter
     * this class exists to remove, relocated.</p>
     *
     * @param envelope the framed bytes, already validated and already this instance's own copy
     */
    private EncryptedCvv(byte[] envelope) {
        this.envelope = envelope;
    }

    /**
     * Returns the envelope for bytes read back from storage.
     *
     * <p>Assumptions: this is the read path, and it VALIDATES rather than trusting the column. A row can
     * predate this type, can have been written by a tool that bypassed the application, or can have been
     * edited in place; refusing a malformed value on the way out is what turns "plaintext is not written
     * here" into "plaintext is not held here", which is the claim the disclosure contract in
     * {@code docs/architecture/security-and-identity.md} actually makes.</p>
     *
     * @param envelope the framed bytes as stored; must not be {@code null}
     * @return the value, never {@code null}
     * @throws NullPointerException if {@code envelope} is {@code null}
     * @throws IllegalArgumentException if the bytes do not carry the framing this class declares
     */
    public static EncryptedCvv ofEnvelope(byte[] envelope) {
        Objects.requireNonNull(envelope, "envelope is required");
        requireEnvelopeShape(envelope);
        return new EncryptedCvv(envelope.clone());
    }

    /**
     * Assembles an envelope from the three parts an enciphering boundary produces.
     *
     * <p>Assumptions: the parts are copied on the way in, so a caller that reuses its buffers -- which a
     * cipher implementation reasonably does -- cannot change what this value holds afterwards.</p>
     *
     * @param encipheredDataKey the data key as the key-management service enciphered it; must not be
     *     {@code null} or empty, and at most {@value #MAX_KEY_LENGTH} bytes
     * @param initialisationVector the vector the ciphertext was produced under; must not be {@code null}
     *     and must be exactly {@value #INITIALISATION_VECTOR_LENGTH} bytes
     * @param ciphertext the ciphertext with its authentication tag appended; must not be {@code null} and
     *     must be at least {@value #MIN_CIPHERTEXT_LENGTH} bytes
     * @return the value, never {@code null}
     * @throws NullPointerException if any part is {@code null}
     * @throws IllegalArgumentException if any part falls outside the length the framing admits
     */
    public static EncryptedCvv wrap(byte[] encipheredDataKey, byte[] initialisationVector,
            byte[] ciphertext) {
        Objects.requireNonNull(encipheredDataKey, "encipheredDataKey is required");
        Objects.requireNonNull(initialisationVector, "initialisationVector is required");
        Objects.requireNonNull(ciphertext, "ciphertext is required");
        if (encipheredDataKey.length < 1 || encipheredDataKey.length > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("encipheredDataKey must be between 1 and "
                    + MAX_KEY_LENGTH + " bytes but was " + encipheredDataKey.length);
        }
        if (initialisationVector.length != INITIALISATION_VECTOR_LENGTH) {
            throw new IllegalArgumentException("initialisationVector must be exactly "
                    + INITIALISATION_VECTOR_LENGTH + " bytes but was " + initialisationVector.length);
        }
        if (ciphertext.length < MIN_CIPHERTEXT_LENGTH) {
            throw new IllegalArgumentException("ciphertext must carry at least "
                    + MIN_CIPHERTEXT_LENGTH + " bytes, being an authentication tag and at least one"
                    + " byte of ciphertext, but was " + ciphertext.length);
        }

        byte[] framed = new byte[KEY_OFFSET + encipheredDataKey.length
                + INITIALISATION_VECTOR_LENGTH + ciphertext.length];
        System.arraycopy(MAGIC, 0, framed, 0, MAGIC_LENGTH);
        framed[VERSION_OFFSET] = FORMAT_VERSION;
        framed[KEY_LENGTH_OFFSET] = (byte) (encipheredDataKey.length >>> HIGH_BYTE_SHIFT);
        framed[KEY_LENGTH_OFFSET + 1] = (byte) encipheredDataKey.length;
        System.arraycopy(encipheredDataKey, 0, framed, KEY_OFFSET, encipheredDataKey.length);
        int vectorOffset = KEY_OFFSET + encipheredDataKey.length;
        System.arraycopy(initialisationVector, 0, framed, vectorOffset,
                INITIALISATION_VECTOR_LENGTH);
        System.arraycopy(ciphertext, 0, framed, vectorOffset + INITIALISATION_VECTOR_LENGTH,
                ciphertext.length);
        return new EncryptedCvv(framed);
    }

    /**
     * Reports whether bytes carry the framing this class declares.
     *
     * <p>Assumptions: {@code null} is reported as not an envelope rather than accepted, requiredness
     * being the separate concern of whichever member holds the value.</p>
     *
     * @param candidate the bytes to test, which may be {@code null}
     * @return {@code true} when the bytes are a well-formed envelope
     */
    public static boolean hasEnvelopeShape(byte[] candidate) {
        if (candidate == null || candidate.length < MIN_ENVELOPE_LENGTH) {
            return false;
        }
        for (int index = 0; index < MAGIC_LENGTH; index++) {
            if (candidate[index] != MAGIC[index]) {
                return false;
            }
        }
        if (candidate[VERSION_OFFSET] != FORMAT_VERSION) {
            return false;
        }
        int keyLength = declaredKeyLength(candidate);
        if (keyLength < 1) {
            return false;
        }
        int remaining = candidate.length - KEY_OFFSET - keyLength;
        return remaining >= INITIALISATION_VECTOR_LENGTH + MIN_CIPHERTEXT_LENGTH;
    }

    /**
     * Returns a copy of the framed bytes, which is the form the column stores.
     *
     * @return a copy of the envelope, never {@code null} and never shorter than
     *     {@value #MIN_ENVELOPE_LENGTH} bytes
     */
    public byte[] envelope() {
        return this.envelope.clone();
    }

    /**
     * Returns a copy of the enciphered data key the ciphertext was produced under.
     *
     * @return a copy of the enciphered data key, never {@code null} and never empty
     */
    public byte[] encipheredDataKey() {
        return Arrays.copyOfRange(this.envelope, KEY_OFFSET,
                KEY_OFFSET + declaredKeyLength(this.envelope));
    }

    /**
     * Returns a copy of the initialisation vector the ciphertext was produced under.
     *
     * @return a copy of the vector, never {@code null} and exactly
     *     {@value #INITIALISATION_VECTOR_LENGTH} bytes
     */
    public byte[] initialisationVector() {
        int from = KEY_OFFSET + declaredKeyLength(this.envelope);
        return Arrays.copyOfRange(this.envelope, from, from + INITIALISATION_VECTOR_LENGTH);
    }

    /**
     * Returns a copy of the ciphertext with its authentication tag.
     *
     * @return a copy of the ciphertext run, never {@code null} and never shorter than
     *     {@value #MIN_CIPHERTEXT_LENGTH} bytes
     */
    public byte[] ciphertext() {
        int from = KEY_OFFSET + declaredKeyLength(this.envelope) + INITIALISATION_VECTOR_LENGTH;
        return Arrays.copyOfRange(this.envelope, from, this.envelope.length);
    }

    /**
     * Compares two values by their framed bytes.
     *
     * @param other the value to compare with, which may be {@code null} or of another type
     * @return {@code true} when {@code other} is an envelope carrying the identical bytes
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EncryptedCvv candidate)) {
            return false;
        }
        return Arrays.equals(this.envelope, candidate.envelope);
    }

    /**
     * Returns a hash of the framed bytes.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(this.envelope);
    }

    /**
     * Renders this value as its length and nothing else.
     *
     * <p>Assumptions: the bytes are never rendered, not even as a digest. A rendering is what a log line,
     * an assertion message or a framework diagnostic picks up automatically, and this value is the
     * enciphered form of payment data -- so the safe rendering is the one that says an envelope is
     * present and how long it is. A digest would be stable across rows and would therefore let equal
     * verification values be recognised as equal, which is the one inference this member must not
     * enable.</p>
     *
     * @return a rendering naming the type and the envelope length, never {@code null}
     */
    @Override
    public String toString() {
        return "EncryptedCvv[envelopeBytes=" + this.envelope.length + "]";
    }

    /**
     * Refuses bytes that do not carry the framing this class declares.
     *
     * <p>Assumptions: the refusal reports the LENGTH and never the bytes. A value reaching this branch may
     * be a plaintext verification value -- that is the case the class exists to catch -- so rendering it
     * would write the payment data into the log line reporting its refusal.</p>
     *
     * @param candidate the bytes to check; must not be {@code null}
     * @throws IllegalArgumentException if the bytes are not a well-formed envelope
     */
    private static void requireEnvelopeShape(byte[] candidate) {
        if (!hasEnvelopeShape(candidate)) {
            throw new IllegalArgumentException("cvv_encrypted must hold an enciphered envelope of at"
                    + " least " + MIN_ENVELOPE_LENGTH + " bytes carrying the CDCV marker, format"
                    + " version " + FORMAT_VERSION + ", an enciphered data key, a "
                    + INITIALISATION_VECTOR_LENGTH + "-byte initialisation vector and a ciphertext;"
                    + " the value supplied was " + candidate.length + " bytes and is not one. A"
                    + " plaintext verification value cannot be stored in this column.");
        }
    }

    /**
     * Reads the two-byte enciphered-data-key length from a candidate envelope.
     *
     * <p>Assumptions: both bytes are masked to unsigned before being combined, because a data key longer
     * than 127 bytes -- which every real one is -- sets the high bit of at least one of them, and a signed
     * read would then produce a negative length and a plausible-looking rejection of a valid value.</p>
     *
     * @param candidate the bytes to read from, at least {@value #KEY_OFFSET} bytes long
     * @return the declared length of the enciphered data key
     */
    private static int declaredKeyLength(byte[] candidate) {
        return ((candidate[KEY_LENGTH_OFFSET] & UNSIGNED_BYTE_MASK) << HIGH_BYTE_SHIFT)
                | (candidate[KEY_LENGTH_OFFSET + 1] & UNSIGNED_BYTE_MASK);
    }
}
