package com.carddemo.common.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a protected identifier into an opaque, authenticated selector that a URL may carry, and opens
 * one again on the service side.
 *
 * <h2>The problem this exists for, and why the two existing primitives do not solve it</h2>
 *
 * <p>An HTTP resource has to be addressable. The migrated card context stores its rows under a primary
 * account number, so the obvious address for one card is that number -- and that puts the number into
 * the request line, from where it reaches the browser's own history, the referrer a browser sends to a
 * third party, and the access log of every intermediary between the browser and the service. Those are
 * durable, searchable stores outside the service's control, so masking applied inside the service does
 * not reach them: an application can redact what IT writes and cannot redact what a load balancer or a
 * content distribution already wrote.</p>
 *
 * <p>Refactoring Rationale: neither existing primitive in this package answers that. {@link
 * OpaqueIdentifier} produces a keyed code, which is exactly right for a group identifier or a
 * correlation identity but cannot be reversed BY ANYONE, including this system -- so it cannot address a
 * row without a stored lookup column and the migration to populate it. {@code
 * com.carddemo.common.web.CursorToken} is reversible, but it states in its own documentation that its
 * payload is base64-encoded rather than encrypted, so a protected value sealed by it would be recovered
 * by anyone who decodes the token: an encoded account number in an access log is still an account number
 * in an access log. This class is the third case -- reversible by the holder of the key and by nobody
 * else -- and it exists because addressing needs precisely that and neither of the others provides
 * it.</p>
 *
 * <h2>The four properties a sealed selector has</h2>
 *
 * <p><strong>It is confidential.</strong> The value is encrypted under AES-256 in Galois/Counter mode,
 * so a party holding the token and not the key learns nothing about the value beyond its length. That is
 * the property that distinguishes this from an encoding and the reason this class exists.</p>
 *
 * <p><strong>It is deterministic.</strong> The same value under the same key and purpose always seals to
 * the same token. Assumptions: this is a requirement rather than a convenience, because a selector is a
 * URL: a non-deterministic one would give one card a different address on every page load, so no route
 * would be bookmarkable, no response would be cacheable, and a client could not tell that two rows it
 * holds are the same row. It is obtained with a synthetic initialisation vector -- a keyed code over the
 * purpose and the value -- rather than with a fixed vector, which is the construction that keeps
 * determinism without ever reusing a vector across two different values under one key.</p>
 *
 * <p><strong>It is authenticated, and canonical.</strong> Galois/Counter mode fails to open a token
 * whose ciphertext, vector or purpose has been altered, so a client cannot manufacture a selector for a
 * row it was never shown. {@link #open(String, String)} additionally re-derives the synthetic vector
 * from the value it recovered and refuses a token whose carried vector differs, so exactly one token
 * exists for one value under one key and purpose. Trade-offs: that check costs one extra keyed code per
 * open and is worth it, because without it a holder of the key could mint a second, differently-shaped
 * selector for the same row, and two addresses for one row is the ambiguity a deterministic scheme is
 * chosen to avoid.</p>
 *
 * <p><strong>It is purpose-separated.</strong> The purpose string keys the cipher, seeds the vector and
 * is authenticated as associated data, so a selector minted for one purpose does not open under another
 * and two purposes give one value two unrelated selectors. That is what stops a card selector and, say,
 * an account selector from being joined by an observer who sees both.</p>
 *
 * <h2>Assumptions and boundaries</h2>
 *
 * <p>Assumptions: the key comes from the deployment's secret store and this class holds no default, for
 * the same reason {@link OpaqueIdentifier} holds none -- a committed default is a committed secret, and a
 * per-process random key would give one card a different address on each task of a horizontally scaled
 * service. The same secret that keys {@link OpaqueIdentifier} may key this class, because the two derive
 * separate working keys from it under separate labels and never use the raw material directly.</p>
 *
 * <p>Assumptions: rotating the key invalidates every outstanding selector, which means outstanding
 * bookmarks and open pages stop resolving and callers must re-address from a list or a lookup. That is
 * accepted and is stated because it is invisible from the API: the alternative -- a stored selector
 * column that survives rotation -- costs a column, a unique index, a migration and a populate step in the
 * extract-and-load path, and it makes the selector a second permanent identity for a row that already has
 * one.</p>
 *
 * <p>Assumptions: a selector is confidential rather than secret. It is designed to be safe in a URL, so
 * it is safe in the places a URL reaches; it is not an authorisation and never substitutes for one. A
 * caller presenting a valid selector for a row it may not read is refused by the authority check, not by
 * the selector.</p>
 *
 * <p>Assumptions: this class seals short identifiers and bounds the value it accepts at {@value
 * #MAX_VALUE_LENGTH} characters. It is not a general encryption facility: encrypting stored columns
 * belongs to the datastore's own encryption and to the mapping layer, and a bound stated here is what
 * keeps a selector short enough to be a path segment.</p>
 *
 * @see OpaqueIdentifier for the one-way case, where an identity is needed and reversal is not
 */
public final class SealedSelector {

    /**
     * The fewest bytes of key material this class accepts.
     *
     * <p>Assumptions: 32 is the length of the working keys derived here, so key material shorter than
     * the keys it produces would be stretched rather than used, which would overstate the strength of
     * every selector. The same figure bounds {@link OpaqueIdentifier#MIN_KEY_LENGTH}, and one deployment
     * secret satisfying both is the intended arrangement.
     */
    public static final int MIN_KEY_LENGTH = 32;

    /**
     * The greatest number of characters a value offered for sealing may carry.
     *
     * <p>Assumptions: the identifiers this class addresses rows by are a sixteen-character card number
     * and an eleven-character account number, so 64 is generous by a factor of four while still bounding
     * a token to a length a path segment carries comfortably. The bound exists so that a caller cannot
     * turn an addressing primitive into a bulk encryption one by handing it a document.
     */
    public static final int MAX_VALUE_LENGTH = 64;

    /**
     * The greatest number of characters a sealed selector may carry.
     *
     * <p>Assumptions: this follows from {@link #MAX_VALUE_LENGTH} by the arithmetic {@link
     * #sealedLengthFor(int)} performs, and it is published so that a caller can bound an input before
     * decoding it. Refusing an over-long token before any decoding is what keeps a hostile caller from
     * choosing how much work an open costs.
     */
    public static final int MAX_TOKEN_LENGTH = 128;

    /**
     * The shape a sealed selector has to match before any part of it is decoded.
     *
     * <p>Assumptions: the alphabet is that of URL-safe base64 without padding, which is the encoding
     * {@link #seal(String, String)} emits, and the bound is one character upwards so a shape check can
     * refuse an empty value. Checking the shape first means a malformed token is refused by a regular
     * expression rather than by an exception from a decoder, which is the difference between a field
     * error naming the parameter and a stack trace naming a codec.
     */
    public static final String SEALED_SHAPE = "[A-Za-z0-9_-]+";

    /**
     * The algorithm the working keys and the synthetic vector are derived with.
     */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /**
     * The transformation the value is sealed under.
     *
     * <p>Assumptions: Galois/Counter mode is chosen over a mode plus a separate authentication step
     * because it authenticates the ciphertext, the vector and the associated data in one operation, and
     * an unauthenticated mode would let a client alter a selector into one that decrypts to a different
     * row's identifier without detection.
     */
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * The name the derived working key is presented to the cipher under.
     */
    private static final String KEY_ALGORITHM = "AES";

    /**
     * The number of bytes of synthetic vector each token carries.
     *
     * <p>Assumptions: 12 is the vector length Galois/Counter mode is specified for, so a vector of that
     * length is used directly rather than being folded, which is what a different length would require.
     */
    private static final int VECTOR_LENGTH = 12;

    /**
     * The number of bits of authentication tag each token carries.
     *
     * <p>Assumptions: the full 128 bits are carried rather than a truncated tag. A shorter tag would
     * save four characters of URL and weaken the forgery bound, and a selector is not long enough for
     * four characters to matter.
     */
    private static final int TAG_LENGTH_BITS = 128;

    /**
     * The label the working cipher key is derived under.
     *
     * <p>Assumptions: the label is distinct from {@link #VECTOR_LABEL} so that the key and the vector
     * are independent functions of the same secret. Deriving both under one label would make the vector
     * a function of the key material in a way an analysis has to reason about; two labels make them two
     * unrelated outputs by construction.
     */
    private static final String KEY_LABEL = "carddemo/sealed-selector/key";

    /**
     * The label the synthetic vector is derived under.
     */
    private static final String VECTOR_LABEL = "carddemo/sealed-selector/vector";

    /**
     * The key material every derivation in this instance starts from.
     */
    private final byte[] key;

    /**
     * Creates a sealer and opener over one key.
     *
     * @param key the key material from the deployment's secret store, at least {@value #MIN_KEY_LENGTH}
     *     bytes
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalArgumentException if {@code key} is shorter than {@value #MIN_KEY_LENGTH} bytes
     */
    public SealedSelector(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "key material is " + key.length + " bytes; at least " + MIN_KEY_LENGTH
                            + " are required to derive a " + KEY_ALGORITHM + " working key");
        }
        // WHY : Assumptions: the array is copied on the way in and on no other occasion. A caller that
        //       zeroes its own buffer after construction -- which is what a caller reading a secret
        //       ought to do -- would otherwise blank the key this instance is still using, and a
        //       shared array would let one caller change the key another is sealing under.
        this.key = key.clone();
    }

    /**
     * Reports how many characters a sealed selector occupies for a value of a given length.
     *
     * <p>Assumptions: a token carries the synthetic vector, the ciphertext and the authentication tag,
     * and Galois/Counter mode is a stream mode so the ciphertext is exactly as long as the plaintext.
     * The byte count is therefore the vector length plus the value length plus the tag length, and the
     * character count is that many bytes in URL-safe base64 WITHOUT padding, which is four characters per
     * three bytes with the final partial group rounded up to whole characters -- that is, the ceiling of
     * four thirds of the byte count.
     *
     * <p>Assumptions: the unpadded arithmetic is written as {@code (4 * bytes + 2) / 3} rather than as
     * the padded {@code (bytes + 2) / 3 * 4}, and the distinction is load-bearing rather than
     * stylistic. The two differ by one for a byte count that is two more than a multiple of three, which
     * is exactly the case a sixteen-character value produces: forty-four bytes encode to fifty-nine
     * characters unpadded and would occupy sixty padded, so the padded form would overstate the length a
     * caller's constraint is written to.
     *
     * <p>Alternatives Considered: publishing one constant for the sixteen-character case that every
     * caller needing a length constraint would read. Rejected because a constraint annotation needs a
     * compile-time constant and would therefore restate the number anyway; a method the caller's own
     * test asserts its constant against keeps the arithmetic in one place and still lets the caller
     * write a literal where the language requires one.
     *
     * @param valueLength the number of characters in the value to be sealed, which must be positive and
     *     no greater than {@value #MAX_VALUE_LENGTH}
     * @return the exact number of characters {@link #seal(String, String)} returns for a value of that
     *     many single-byte characters
     * @throws IllegalArgumentException if {@code valueLength} is not positive or exceeds {@value
     *     #MAX_VALUE_LENGTH}
     */
    public static int sealedLengthFor(int valueLength) {
        if (valueLength <= 0 || valueLength > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "valueLength is " + valueLength + "; a sealed value carries between 1 and "
                            + MAX_VALUE_LENGTH + " characters");
        }
        int sealedBytes = VECTOR_LENGTH + valueLength + TAG_LENGTH_BITS / Byte.SIZE;
        return (4 * sealedBytes + 2) / 3;
    }

    /**
     * Reports whether a value has the shape of a sealed selector, without opening it.
     *
     * <p>Assumptions: this answers a routing question rather than an authenticity one. It exists so a
     * caller can distinguish a selector from a raw identifier before deciding which to treat it as, and
     * it deliberately verifies nothing: authenticity belongs to {@link #open(String, String)}, which
     * needs the key and the purpose that this method does not take.
     *
     * @param token the candidate value, which may be {@code null}
     * @return {@code true} when the value is non-null, no longer than {@value #MAX_TOKEN_LENGTH} and
     *     made only of URL-safe base64 characters; {@code false} otherwise, including for every string
     *     of digits, so a raw card number never has the shape of a selector
     */
    public static boolean hasSealedShape(String token) {
        // WHY : Assumptions: a run of digits IS valid URL-safe base64, so the alphabet alone would
        //       report a sixteen-digit card number as a selector -- which is the one confusion this
        //       method exists to prevent. The length test is what separates them: no sealed selector is
        //       as short as the shortest value this class will seal, because every token carries a
        //       twelve-byte vector and a sixteen-byte tag on top of the value.
        return token != null
                && token.length() <= MAX_TOKEN_LENGTH
                && token.length() >= sealedLengthFor(1)
                && token.matches(SEALED_SHAPE);
    }

    /**
     * Seals a protected value into a selector safe to carry in a URL.
     *
     * @param purpose the scope this selector belongs to, non-blank; the identical string is required to
     *     open the result, and a different one produces an unrelated selector for the same value
     * @param value the value to seal, non-blank and no longer than {@value #MAX_VALUE_LENGTH} characters
     * @return the sealed selector, exactly {@code sealedLengthFor(value.length())} URL-safe characters
     *     for a value of single-byte characters, stable for this value under this key and purpose
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code purpose} is blank, or {@code value} is blank or longer
     *     than {@value #MAX_VALUE_LENGTH} characters
     * @throws IllegalStateException if the platform cannot perform {@value #CIPHER_TRANSFORMATION} or
     *     {@value #MAC_ALGORITHM}, which is a deployment fault rather than a caller fault
     */
    public String seal(String purpose, String value) {
        requirePurpose(purpose);
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "value must not be blank; a selector over nothing would be one address standing in"
                            + " for every row a caller failed to name");
        }
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "value occupies " + plaintext.length + " bytes; at most " + MAX_VALUE_LENGTH
                            + " may be sealed into a selector");
        }

        byte[] vector = syntheticVector(purpose, plaintext);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, workingKey(purpose),
                    new GCMParameterSpec(TAG_LENGTH_BITS, vector));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext);

            byte[] token = new byte[vector.length + sealed.length];
            System.arraycopy(vector, 0, token, 0, vector.length);
            System.arraycopy(sealed, 0, token, vector.length, sealed.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (GeneralSecurityException fatal) {
            throw new IllegalStateException(
                    "the platform cannot perform " + CIPHER_TRANSFORMATION
                            + ", so no selector can be sealed", fatal);
        }
    }

    /**
     * Opens a sealed selector and returns the value it carries.
     *
     * @param purpose the identical purpose the selector was sealed under; a different one is refused
     * @param token the selector as received from the client
     * @return the value that was sealed, exactly as it was supplied to {@link #seal(String, String)}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code purpose} is blank, or {@code token} does not have the
     *     sealed shape, is too short to carry a vector and a tag, fails authentication under this key
     *     and purpose, or carries a vector other than the one its own value derives -- every case
     *     reported without echoing the offered token, because a rejected selector is attacker-supplied
     *     text
     * @throws IllegalStateException if the platform cannot perform {@value #CIPHER_TRANSFORMATION} or
     *     {@value #MAC_ALGORITHM}
     */
    public String open(String purpose, String token) {
        requirePurpose(purpose);
        Objects.requireNonNull(token, "token must not be null");
        if (!hasSealedShape(token)) {
            throw new IllegalArgumentException(
                    "the value offered is not a sealed selector; the accepted form is between "
                            + sealedLengthFor(1) + " and " + MAX_TOKEN_LENGTH
                            + " URL-safe base64 characters");
        }

        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException malformed) {
            // WHY : Assumptions: the shape test admits the alphabet but not every length, and a
            //       base64 length of one more than a multiple of four decodes to no whole byte. The
            //       decoder's own message quotes the offending input, so it is replaced here rather
            //       than propagated: a refusal must not write attacker-supplied text into a log.
            throw new IllegalArgumentException(
                    "the value offered is not a sealed selector; its length is not that of any"
                            + " sealed value", malformed);
        }
        int tagBytes = TAG_LENGTH_BITS / Byte.SIZE;
        if (decoded.length <= VECTOR_LENGTH + tagBytes) {
            throw new IllegalArgumentException(
                    "the value offered is too short to be a sealed selector; every one carries a "
                            + VECTOR_LENGTH + "-byte vector and a " + tagBytes + "-byte tag over at"
                            + " least one byte of value");
        }

        byte[] vector = Arrays.copyOf(decoded, VECTOR_LENGTH);
        byte[] sealed = Arrays.copyOfRange(decoded, VECTOR_LENGTH, decoded.length);
        byte[] plaintext;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, workingKey(purpose),
                    new GCMParameterSpec(TAG_LENGTH_BITS, vector));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            plaintext = cipher.doFinal(sealed);
        } catch (javax.crypto.IllegalBlockSizeException | javax.crypto.BadPaddingException rejected) {
            // WHY : Assumptions: the authentication failure this mode raises is a BadPaddingException
            //       subclass, so catching that supertype covers it without naming both -- the language
            //       refuses a multi-catch whose alternatives are related by subclassing. The block-size
            //       failure is caught alongside it because both mean the same thing to a caller: the
            //       bytes offered are not a selector this key and purpose produced.
            throw new IllegalArgumentException(
                    "the selector offered does not authenticate under this key and purpose", rejected);
        } catch (GeneralSecurityException fatal) {
            throw new IllegalStateException(
                    "the platform cannot perform " + CIPHER_TRANSFORMATION
                            + ", so no selector can be opened", fatal);
        }

        // WHY : Assumptions: the carried vector is compared against the one the recovered value
        //       derives, and a mismatch is refused even though the tag already verified. The tag
        //       proves the token was made with this key; this check proves it was made by the
        //       DETERMINISTIC construction, so exactly one token addresses one row. Without it a
        //       holder of the key could mint a second selector for the same value under a chosen
        //       vector, and two addresses for one row defeats the caching and equality the
        //       determinism was chosen for.
        if (!java.security.MessageDigest.isEqual(vector, syntheticVector(purpose, plaintext))) {
            throw new IllegalArgumentException(
                    "the selector offered is not the canonical selector for the value it carries");
        }
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /**
     * Rejects a purpose that cannot scope a selector.
     *
     * @param purpose the caller-supplied scope
     * @throws NullPointerException if {@code purpose} is {@code null}
     * @throws IllegalArgumentException if {@code purpose} is blank
     */
    private static void requirePurpose(String purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (purpose.isBlank()) {
            throw new IllegalArgumentException(
                    "purpose must not be blank; an unscoped selector can be opened by every caller"
                            + " that holds a selector for any other kind of row");
        }
    }

    /**
     * Derives the cipher key one purpose seals and opens under.
     *
     * @param purpose the scope the key is derived for
     * @return a 32-byte {@value #KEY_ALGORITHM} key, stable for this purpose under this instance's key
     *     material
     * @throws IllegalStateException if the platform cannot compute {@value #MAC_ALGORITHM}
     */
    private SecretKeySpec workingKey(String purpose) {
        return new SecretKeySpec(derive(KEY_LABEL, purpose, null), KEY_ALGORITHM);
    }

    /**
     * Derives the deterministic initialisation vector for one purpose and value.
     *
     * @param purpose the scope the selector belongs to
     * @param plaintext the bytes to be sealed
     * @return the first {@value #VECTOR_LENGTH} bytes of a keyed code over the purpose and the value
     * @throws IllegalStateException if the platform cannot compute {@value #MAC_ALGORITHM}
     */
    private byte[] syntheticVector(String purpose, byte[] plaintext) {
        return Arrays.copyOf(derive(VECTOR_LABEL, purpose, plaintext), VECTOR_LENGTH);
    }

    /**
     * Computes one keyed derivation over a label, a purpose and optional value bytes.
     *
     * @param label the derivation's own label, which separates one derived output from another
     * @param purpose the caller-supplied scope
     * @param plaintext the value bytes to include, or {@code null} to derive over the label and purpose
     *     alone
     * @return the full 32-byte code
     * @throws IllegalStateException if the platform cannot compute {@value #MAC_ALGORITHM}
     */
    private byte[] derive(String label, String purpose, byte[] plaintext) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            // WHY : Assumptions: each variable-length part is preceded by its own length, so no two
            //       different (label, purpose, value) triples can present the same byte sequence by
            //       moving a boundary. Concatenating them unprefixed would make the purpose "ab" with
            //       value "c" derive identically to the purpose "a" with value "bc", which would
            //       collapse the purpose separation this argument exists to provide.
            updateLengthPrefixed(mac, label.getBytes(StandardCharsets.UTF_8));
            updateLengthPrefixed(mac, purpose.getBytes(StandardCharsets.UTF_8));
            if (plaintext != null) {
                updateLengthPrefixed(mac, plaintext);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException fatal) {
            throw new IllegalStateException(
                    "the platform cannot compute " + MAC_ALGORITHM
                            + ", so no selector can be sealed or opened", fatal);
        }
    }

    /**
     * Feeds one length-prefixed part into a keyed code.
     *
     * @param mac the code under construction
     * @param part the bytes to feed in
     */
    private static void updateLengthPrefixed(Mac mac, byte[] part) {
        mac.update(Integer.toString(part.length).getBytes(StandardCharsets.UTF_8));
        mac.update((byte) ':');
        mac.update(part);
    }
}
