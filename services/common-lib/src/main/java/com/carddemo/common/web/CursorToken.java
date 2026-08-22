package com.carddemo.common.web;

import com.carddemo.common.error.ClientInputException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals a keyset cursor into an opaque, authenticated token and opens one again, so that no page
 * boundary is ever expressed to a client as the key it was built from.
 *
 * <h2>What this replaces</h2>
 *
 * <p>The baseline carried its browse cursor in the communication area it echoed to the terminal
 * between screen turns. For the card list that cursor is a composite of a card number and an account
 * identifier, declared at lines 230 to 232 of {@code app/cbl/COCRDLIC.cbl}; for the transaction list
 * it is a transaction identifier, at line 595 of {@code app/cbl/COTRN00C.cbl}. A 3270 terminal
 * attached to a CICS region is a closed circuit: that echo never left the session. The migrated
 * equivalent answers a browser over a public edge, and a cursor in a JSON body is a value the client
 * holds, stores, replays and may put in a URL -- so echoing the composite would publish a primary
 * account number to the client and, through the edge access log, to durable log storage.</p>
 *
 * <p>Refactoring Rationale: {@link PageResponse} described its two boundary components as opaque
 * cursor tokens from the outset, and opacity was the correct intent, but nothing enforced it. A caller
 * could satisfy that type by assigning the raw composite key, and the first callers would have, because
 * the raw key is exactly what the query produces. This class supplies the missing half: the token a
 * caller can build is a sealed one, and {@link PageResponse} now refuses anything that is not.</p>
 *
 * <h2>The three properties a sealed token has</h2>
 *
 * <p><strong>It is confidential.</strong> The key is ENCIPHERED inside the token rather than encoded
 * into it, under a cipher key derived from the deployment's secret, so a client that base64url-decodes
 * a token recovers ciphertext and nothing else.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of this class signed a base64url-encoded PLAINTEXT
 * payload and described that as "confidentiality by encoding", noting honestly that base64url is
 * reversible by anyone and resting the property on an assumption -- that a token never reaches a client
 * that would decode it, nor durable logging. That assumption does not hold: the card list's cursor is
 * the composite of a card number and an account identifier, the token is returned in a JSON body the
 * client stores and replays, and one base64url decode recovers the primary account number in full. A
 * signature answers "was this altered", never "can this be read", so signing plaintext could not close
 * it. The payload is therefore enciphered with an authenticated cipher, which answers both questions
 * with one primitive and removes the separate authentication segment entirely.</p>
 *
 * <p><strong>It is authenticated.</strong> The cipher is AES in Galois/Counter Mode, whose
 * authentication tag covers the enciphered payload and, as additional authenticated data, the
 * caller-supplied binding. A token whose ciphertext, nonce or binding is edited fails to decipher
 * rather than deciphering to something else. This is what stops a client from turning a page cursor into
 * an arbitrary key predicate: without the key it cannot mint a token naming a row it was never
 * shown.</p>
 *
 * <p><strong>It is bound and bounded.</strong> The binding is a caller-composed string naming the
 * query, the subject the token was issued to, the narrowing predicate it was issued under and the
 * direction it was issued for, and it is authenticated without being carried in the token, so a token
 * minted for one query, one user, one filter or one direction cannot be presented on another. Both the
 * key inside a token and the token itself are length-bounded, so a token arriving from a client cannot
 * provoke work disproportionate to its size.</p>
 *
 * <h2>Assumptions this class makes</h2>
 *
 * <p>Assumptions: the key material is supplied by the caller and comes from the deployment's secret
 * store rather than from this class, which holds no default and generates nothing. A default would be a
 * committed secret, and a generated per-process key would invalidate every token a sibling task issued,
 * which for a horizontally scaled service is every second token. The key is required to be at least
 * {@code MIN_KEY_LENGTH} bytes, which is the output width of the digest the cipher key is derived
 * through: a shorter secret reduces the strength of the derivation without any diagnostic, so it is
 * refused.</p>
 *
 * <p>Assumptions: the cipher key is DERIVED from that secret rather than used as it stands, by one
 * keyed-digest expansion over a fixed label. Two properties follow and both are wanted. The secret may
 * be any admissible length while the cipher requires exactly 32 bytes, and the same secret can key an
 * unrelated construction elsewhere in the shared kernel without the two sharing a cipher key -- the
 * label is what separates them. The same device is used by
 * {@code com.carddemo.common.security.SealedSelector}, deliberately, so that the platform has one
 * derivation idiom rather than two.</p>
 *
 * <p>Assumptions: a token carries the instant it was issued and is refused once it is older than the
 * lifetime the caller configured. A cursor names a position in an ordered set, and a position held for
 * a day says nothing useful about a set that has been written to since; expiring it also bounds how
 * long a leaked token remains usable.</p>
 */
public final class CursorToken {

    /**
     * The version marker every sealed token begins with.
     *
     * <p>Assumptions: the marker is inside the authenticated payload as well as being the token's
     * leading segment, so a token cannot be re-labelled as a different version without failing to
     * open. Carrying it at all is what allows the payload format to change later without a running
     * deployment having to reject every token minted before the change: a future version can be
     * recognised and handled rather than being read as a corrupt one.</p>
     *
     * <p>Refactoring Rationale: the marker moved from {@code v1} to {@code v2} when the payload stopped
     * being signed plaintext and became authenticated ciphertext. Bumping it rather than reusing
     * {@code v1} is what makes the change safe to deploy on a running fleet: a token minted by an
     * older task is rejected on its shape instead of being deciphered as though its plaintext were
     * ciphertext, which would fail with a cipher error rather than the cursor refusal a client can act
     * on. Nothing durable holds a token -- the lifetime is minutes and a rejected cursor costs one
     * re-read of the opening page -- so no migration path is needed beyond the marker itself.</p>
     */
    public static final String VERSION = "v2";

    /**
     * The greatest number of characters a sealed token may carry.
     *
     * <p>Assumptions: the widest cursor in the migration is the 27-character composite of card number
     * and account identifier at lines 230 to 232 of {@code app/cbl/COCRDLIC.cbl}. Sealed, that becomes
     * a version marker, a 16-character encoded nonce and an encoded ciphertext covering an issue
     * instant, the key and a 16-byte authentication tag, which is comfortably inside 256 characters.
     * The bound exists so that a token arriving from a client is rejected on its length before any
     * decoding or deciphering is attempted.</p>
     */
    public static final int MAX_TOKEN_LENGTH = 256;

    /**
     * The greatest number of characters a cursor key inside a token may carry.
     *
     * <p>Assumptions: 64 is set well above the 27 characters the widest reference cursor needs, so a
     * composite gaining a further key column does not require this constant to move, while remaining
     * far below anything that could be used to smuggle a payload through a page response.</p>
     */
    public static final int MAX_KEY_LENGTH = 64;

    /**
     * The fewest bytes of key material this class accepts.
     *
     * <p>Assumptions: 32 is the output width of the digest underlying the authentication code, so a key
     * at least this long does not weaken the code. Refusing a shorter one is the only way a caller
     * learns that its key was inadequate, since a short key produces a perfectly well-formed and
     * verifiable -- but weaker -- code.</p>
     */
    public static final int MIN_KEY_LENGTH = 32;

    /**
     * The published shape a sealed cursor must match, for a request contract to validate against.
     *
     * <p>Assumptions: the expression is published as a constant rather than restated in each request
     * DTO, because a copy would keep validating the previous encoding after this one changed and would
     * refuse every live token while reading as though it were still correct.</p>
     */
    public static final String SEALED_SHAPE_PATTERN =
            VERSION + "\\.[A-Za-z0-9_-]{16}\\.[A-Za-z0-9_-]{1,200}";

    /** The compiled form of {@link #SEALED_SHAPE_PATTERN}, so the expression exists in one place. */
    private static final Pattern SEALED_SHAPE = Pattern.compile(SEALED_SHAPE_PATTERN);

    /**
     * The name of the keyed-digest algorithm the cipher key is derived through.
     *
     * <p>Alternatives Considered: a plain digest over the secret concatenated with the label. Rejected
     * because a bare digest construction is vulnerable to length extension, so a party holding one
     * derived value could extend the input and compute a related one. A keyed message authentication
     * code is specified precisely to close that, which is why it is used rather than assembled by
     * hand.</p>
     */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /**
     * The authenticated cipher transformation the payload is enciphered under.
     *
     * <p>Alternatives Considered: encipher-then-authenticate assembled from a block cipher in counter
     * mode plus a separate keyed digest. Rejected because it is the composition that is easy to get
     * wrong -- two keys, an ordering that must be authenticate-after-encipher, and a comparison that
     * must be constant-time -- and Galois/Counter Mode is the standardised composition that supplies
     * all three, with the additional-data channel this class needs for the binding.</p>
     */
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    /** The key algorithm the derived cipher key is presented under. */
    private static final String KEY_ALGORITHM = "AES";

    /**
     * The nonce width in bytes.
     *
     * <p>Assumptions: twelve bytes is the width Galois/Counter Mode is specified for, and using it
     * avoids the internal re-derivation any other width forces. Twelve bytes encode to exactly sixteen
     * base64url characters without padding, which is why the shape pattern above can pin that segment
     * to a fixed width and reject a malformed token before any decoding.</p>
     */
    private static final int VECTOR_LENGTH = 12;

    /** The authentication tag width in bits, the maximum the mode defines. */
    private static final int TAG_LENGTH_BITS = 128;

    /**
     * The label separating this class's derived cipher key from any other use of the same secret.
     *
     * <p>Assumptions: the label is a constant and is not secret. Its whole function is domain
     * separation: two constructions deriving from one secret under two different labels obtain
     * unrelated keys, so a value sealed by one can never be opened by the other even though both were
     * configured from the same secret-store entry.</p>
     */
    private static final String KEY_LABEL = "carddemo/cursor-token/key";

    /**
     * The source every nonce is drawn from.
     *
     * <p>Assumptions: one instance is shared by every sealer in the process, which is safe because the
     * type is documented thread-safe and because the alternative -- one instance per
     * {@code CursorToken} -- would seed a fresh generator for each application context and gain
     * nothing. {@code SecureRandom} is used rather than {@code Random}: a predictable nonce under
     * Galois/Counter Mode is a reused nonce waiting to happen, and a predictable one lets an observer
     * confirm which key a token was minted for.</p>
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The separator between a sealed token's segments.
     *
     * <p>Assumptions: the dot is outside the base64url alphabet, so a segment can never contain one and
     * splitting is unambiguous. A character inside the alphabet would make the boundary between payload
     * and code decidable only by counting, which a malformed token could then shift.</p>
     */
    private static final char SEGMENT_SEPARATOR = '.';

    /**
     * The separator between the issue instant and the key inside a payload.
     *
     * <p>Assumptions: the colon cannot appear in an epoch-second rendering, so the first colon in a
     * payload always ends the instant. A reference cursor can contain any character its key columns
     * hold, so the split is taken at the FIRST separator and the remainder is the key, rather than
     * splitting on every occurrence.</p>
     */
    private static final char PAYLOAD_SEPARATOR = ':';

    /**
     * The character {@link #binding(String, String, String)} writes between a part's length and the
     * part itself.
     *
     * <p>Assumptions: this is a punctuation mark inside a length-prefixed encoding and not a
     * delimiter between parts. The distinction matters: a length prefix makes the composition
     * injective with no escaping at all, whereas a delimiter -- however it is escaped by doubling --
     * is not, because the doubled form and the delimiter are the same character. The concrete case
     * that proved it: joining with a bar and doubling an embedded bar renders the subject
     * {@code "s|"} with scope {@code "x"} and the subject {@code "s"} with scope {@code "|x"} as one
     * identical string, so a token issued under either would open under the other.</p>
     */
    private static final char BINDING_LENGTH_MARK = ':';

    /**
     * The scope a query that narrows nothing declares.
     *
     * <p>Assumptions: a listing over a whole table has no narrowing predicate to bind, and this
     * constant is how that is stated rather than passed as an empty string. An empty string would be
     * indistinguishable from a scope a caller forgot to render, whereas this value reads as a decision
     * at the call site and can be searched for when a query later acquires a predicate.</p>
     */
    public static final String SCOPE_NONE = "scope:none";

    /**
     * The cipher key derived from the deployment secret, held as an immutable copy.
     *
     * <p>Assumptions: the SECRET is not retained, only the derived key. Holding one and not the other
     * bounds what a heap inspection of a running task can recover to the key of this one construction,
     * rather than the secret every construction derives from.</p>
     */
    private final byte[] cipherKey;

    /** How long after issue a token remains acceptable. */
    private final Duration lifetime;

    /**
     * Creates a sealer and opener over one key and one token lifetime.
     *
     * @param key the key material from the deployment's secret store, at least
     *     {@code MIN_KEY_LENGTH} bytes; the array is copied, so a caller may clear its own copy
     * @param lifetime how long after issue a token remains acceptable; must be positive, because a
     *     zero or negative lifetime would refuse every token this instance minted, including the one
     *     it minted a moment earlier
     * @throws NullPointerException if {@code key} or {@code lifetime} is {@code null}
     * @throws IllegalArgumentException if {@code key} is shorter than {@code MIN_KEY_LENGTH} bytes or
     *     {@code lifetime} is not positive
     */
    public CursorToken(byte[] key, Duration lifetime) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(lifetime, "lifetime must not be null");
        if (key.length < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "cursor token key material is " + key.length + " bytes; at least "
                            + MIN_KEY_LENGTH + " are required to key " + MAC_ALGORITHM);
        }
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException(
                    "cursor token lifetime must be positive, because a non-positive lifetime refuses"
                            + " every token this instance issues");
        }

        // WHY : Trade-offs: the secret is DERIVED FROM on the way in and never retained, so a caller
        //       that clears its own copy -- which a caller reading key material from a secret store
        //       should -- cannot invalidate this instance, and a caller cannot reach in and change the
        //       key of a sealer another component is using. The cost is one keyed digest per instance,
        //       and an instance is built once per application context.
        this.cipherKey = deriveCipherKey(key);
        this.lifetime = lifetime;
    }

    /**
     * Seals one cursor key into an opaque authenticated token.
     *
     * @param binding the query and subject this token is issued for, composed by the caller, for
     *     example the query name followed by the authenticated subject identifier; it is authenticated
     *     but not carried, so an opener must supply the identical string
     * @param cursorKey the raw keyset cursor -- the key columns of the boundary row, in the physical
     *     key order -- of at most {@code MAX_KEY_LENGTH} characters
     * @return the sealed token, at most {@code MAX_TOKEN_LENGTH} characters, safe to place in a JSON
     *     body and to accept back from a client
     * @throws NullPointerException if {@code binding} or {@code cursorKey} is {@code null}
     * @throws IllegalArgumentException if {@code binding} is blank, or if {@code cursorKey} is blank or
     *     longer than {@code MAX_KEY_LENGTH}; a blank binding would bind the token to nothing, and an
     *     over-long key is refused here rather than producing a token no opener would accept
     */
    public String seal(String binding, String cursorKey) {
        requireBinding(binding);
        Objects.requireNonNull(cursorKey, "cursorKey must not be null");
        if (cursorKey.isBlank()) {
            throw new IllegalArgumentException(
                    "cursorKey must not be blank; an absent cursor is expressed by a null token rather"
                            + " than by a token carrying nothing");
        }
        if (cursorKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "cursorKey is " + cursorKey.length() + " characters, exceeding the "
                            + MAX_KEY_LENGTH + " a sealed cursor may carry");
        }

        String payload = Instant.now().getEpochSecond() + String.valueOf(PAYLOAD_SEPARATOR)
                + cursorKey;

        // WHY : Assumptions: the nonce is drawn fresh for every token from the platform's strong
        //       source. Galois/Counter Mode loses confidentiality catastrophically if one nonce is
        //       reused under one key -- two ciphertexts under the same pair reveal the difference of
        //       their plaintexts -- so a counter would have to be durable and shared across every task
        //       in the fleet to be safe, which a stateless service cannot hold. A random 96-bit nonce
        //       needs no coordination at all; the collision probability across the lifetime of a
        //       deployment's key is negligible against the number of page tokens a fleet mints.
        byte[] vector = new byte[VECTOR_LENGTH];
        RANDOM.nextBytes(vector);
        byte[] sealed = transform(
                Cipher.ENCRYPT_MODE,
                vector,
                binding,
                payload.getBytes(StandardCharsets.UTF_8));
        return VERSION + SEGMENT_SEPARATOR + encode(vector) + SEGMENT_SEPARATOR + encode(sealed);
    }

    /**
     * Verifies a sealed token and returns the cursor key it carries.
     *
     * @param binding the identical binding the token was sealed with; a different one fails
     *     verification, which is what stops a token issued for one query or one subject being
     *     presented on another
     * @param token the sealed token as received from the client
     * @return the raw keyset cursor the token carries, for use in the repository predicate
     * @throws NullPointerException if {@code binding} or {@code token} is {@code null}
     * @throws IllegalArgumentException if {@code binding} is blank
     * @throws InvalidCursorException if the token is longer than {@code MAX_TOKEN_LENGTH}, is not the
     *     sealed shape, fails authentication, carries no readable issue instant, carries an over-long
     *     key, or was issued longer ago than this instance's lifetime
     */
    public String open(String binding, String token) {
        requireBinding(binding);
        Objects.requireNonNull(token, "token must not be null");

        // WHY : Assumptions: the length and the shape are checked before anything is decoded or
        //       authenticated, so the work an unauthenticated caller can provoke is two comparisons on
        //       characters. A token is client-supplied, and every step after this one -- base64
        //       decoding, key derivation, code computation -- costs more than the step before it.
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidCursorException(
                    "cursor token is " + token.length() + " characters, exceeding the "
                            + MAX_TOKEN_LENGTH + " a sealed token may carry");
        }
        if (!SEALED_SHAPE.matcher(token).matches()) {
            // WHY : Trade-offs: the message states the expected shape and never the value received.
            //       A rejected token is frequently a raw key a caller assigned by mistake, so quoting
            //       it would copy the very card number this class exists to keep out of a response
            //       into an error body and a log line instead.
            throw new InvalidCursorException(
                    "cursor token is not a sealed token; the accepted form is the version marker,"
                            + " an encoded nonce and an encoded enciphered payload, separated by"
                            + " dots");
        }

        int vectorStart = VERSION.length() + 1;
        int sealedStart = token.lastIndexOf(SEGMENT_SEPARATOR) + 1;
        byte[] vector = decode(token.substring(vectorStart, sealedStart - 1));
        byte[] sealed = decode(token.substring(sealedStart));

        // WHY : Trade-offs: authenticity is decided by the cipher's own tag check rather than by a
        //       comparison written here, and that is strictly better than the constant-time comparison
        //       the previous revision performed. The mode's verification is specified to be
        //       non-revealing, it happens before any plaintext is released, and it covers the binding
        //       through the additional-data channel -- so a token altered, or presented against a
        //       different query, subject, filter or direction, fails here with nothing recovered.
        byte[] plaintext = transform(
                Cipher.DECRYPT_MODE, vector, binding, sealed);

        String payload = new String(plaintext, StandardCharsets.UTF_8);
        int separator = payload.indexOf(PAYLOAD_SEPARATOR);
        if (separator <= 0 || separator == payload.length() - 1) {
            throw new InvalidCursorException(
                    "cursor token payload does not carry an issue instant followed by a cursor key");
        }

        Instant issuedAt = readIssueInstant(payload.substring(0, separator));
        if (issuedAt.plus(lifetime).isBefore(Instant.now())) {
            throw new InvalidCursorException(
                    "cursor token was issued more than " + lifetime.toSeconds() + " seconds ago and a"
                            + " position in an ordered set is not held open that long");
        }

        String cursorKey = payload.substring(separator + 1);
        if (cursorKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidCursorException(
                    "cursor token carries a key of " + cursorKey.length() + " characters, exceeding"
                            + " the " + MAX_KEY_LENGTH + " a sealed cursor may carry");
        }

        return cursorKey;
    }

    /**
     * Reports whether a value has the shape of a sealed token, without verifying it.
     *
     * <p>Assumptions: this decides shape alone and deliberately requires no key, which is what lets
     * {@link PageResponse} refuse a raw key in a cursor component without every service having to hand
     * that type its key material. Verification stays with {@link #open(String, String)}, where the
     * binding is known.</p>
     *
     * @param token the candidate value, which may be {@code null}
     * @return {@code true} when {@code token} is non-null, within {@code MAX_TOKEN_LENGTH} and matches
     *     the sealed shape; {@code false} otherwise, including for every raw key
     */
    public static boolean hasSealedShape(String token) {
        return token != null && token.length() <= MAX_TOKEN_LENGTH
                && SEALED_SHAPE.matcher(token).matches();
    }

    /**
     * Derives this instance's cipher key from the deployment secret.
     *
     * <p>Assumptions: one keyed-digest expansion over a fixed label is sufficient here, and the reason
     * is the input rather than the construction: the secret is already uniformly random key material
     * from a secret store, not a password, so there is nothing for an extract step to condense and
     * nothing for a work factor to slow down. A password-based derivation would be the wrong tool and
     * would cost iterations for no gain in strength.</p>
     *
     * @param secret the deployment secret, already validated to be at least {@code MIN_KEY_LENGTH}
     *     bytes; it is read and not retained
     * @return a 32-byte cipher key, the width {@code AES-256} requires
     * @throws IllegalStateException if the platform does not provide {@code MAC_ALGORITHM} or rejects
     *     the secret as a key, neither of which is recoverable at run time: the algorithm is one every
     *     Java platform is required to provide, and the secret's length was validated by the caller
     */
    private static byte[] deriveCipherKey(byte[] secret) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, MAC_ALGORITHM));
            return mac.doFinal(KEY_LABEL.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException fatal) {
            throw new IllegalStateException(
                    "the platform cannot compute " + MAC_ALGORITHM
                            + ", so no cursor token key can be derived", fatal);
        }
    }

    /**
     * Enciphers or deciphers a payload under this instance's key, with the binding as additional
     * authenticated data.
     *
     * <p>Assumptions: the binding's LENGTH is authenticated ahead of the binding itself, so that two
     * different bindings cannot present the same additional data by moving the boundary between their
     * parts -- the binding {@code "ab"} and the binding {@code "a"} followed by a {@code "b"} that came
     * from somewhere else would otherwise authenticate identically. The version marker is authenticated
     * too, so a token cannot be re-labelled as another version and replayed.</p>
     *
     * <p>Trade-offs: one method serves both directions rather than two symmetrical ones. The additional
     * data and the nonce must be assembled identically on both sides or every token fails to open, and a
     * single method makes that identity structural instead of something two bodies have to agree on.</p>
     *
     * @param mode {@link Cipher#ENCRYPT_MODE} or {@link Cipher#DECRYPT_MODE}
     * @param vector the nonce, exactly {@code VECTOR_LENGTH} bytes
     * @param binding the caller-composed query, subject, filter and direction binding
     * @param input the payload to encipher, or the ciphertext with its tag to decipher
     * @return the ciphertext with its authentication tag, or the recovered payload
     * @throws InvalidCursorException if a decipher fails its tag check or its block-size check, both
     *     of which mean the token was altered or was issued for a different binding; the message names
     *     neither the token nor the binding, for the same reason every other diagnostic in this class
     *     does
     * @throws IllegalStateException if the platform does not provide {@code CIPHER_TRANSFORMATION} or
     *     rejects the derived key or nonce, none of which is recoverable at run time
     */
    private byte[] transform(int mode, byte[] vector, String binding, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(
                    mode,
                    new SecretKeySpec(cipherKey, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_LENGTH_BITS, vector));
            cipher.updateAAD(VERSION.getBytes(StandardCharsets.UTF_8));
            cipher.updateAAD(
                    Integer.toString(binding.length()).getBytes(StandardCharsets.UTF_8));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (BadPaddingException | IllegalBlockSizeException rejected) {
            throw new InvalidCursorException(
                    "cursor token failed authentication; it was either altered or issued for a"
                            + " different query, subject, filter or direction");
        } catch (GeneralSecurityException fatal) {
            throw new IllegalStateException(
                    "the platform cannot apply " + CIPHER_TRANSFORMATION
                            + ", so no cursor token can be sealed or opened", fatal);
        }
    }

    /**
     * Reads the issue instant out of a payload.
     *
     * @param epochSeconds the payload's leading segment, expected to be epoch seconds
     * @return the instant the token was issued
     * @throws InvalidCursorException if the segment is not a readable epoch-second value; the message
     *     reports the segment's length rather than its content, for the same reason every other
     *     diagnostic in this class does
     */
    private static Instant readIssueInstant(String epochSeconds) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds));
        } catch (NumberFormatException malformed) {
            throw new InvalidCursorException(
                    "cursor token carries a " + epochSeconds.length()
                            + "-character issue instant that is not a whole number of epoch seconds");
        }
    }

    /**
     * Composes the binding a cursor of one query, one subject and one query scope is sealed under.
     *
     * <p><b>Purpose.</b> The binding is what stops a token issued for one query or one user being
     * redeemed on another, and it only does that if the caller actually puts the query, the subject
     * and the narrowing scope into it. This is the one place that composition happens, so a sealing
     * site and its opening site cannot compose it differently.</p>
     *
     * <p>Refactoring Rationale: every paging service composed its own binding, and the first one to do
     * so used a bare query-name constant with no subject in it at all -- which made the token
     * transferable between authorized users, contradicting the promise this class's own contract and
     * the published {@code CursorToken} schema both make. A constant is the natural thing to write
     * when the method being called takes a single string, so the remedy is a method whose parameters
     * are the three parts rather than a convention that each caller must remember.</p>
     *
     * <p>Assumptions: the parts are LENGTH-PREFIXED rather than delimited, so the composition is
     * injective -- two different part triples cannot compose one binding -- with no escaping needed.
     * Refactoring Rationale: an earlier revision joined the parts with a bar and doubled an embedded
     * bar. That is not injective, because the escape and the delimiter are the same character: the
     * triple {@code ("q", "s|", "x")} and the triple {@code ("q", "s", "|x")} both render as
     * {@code q|s|||x}, so a token issued under either would open under the other and the isolation
     * this method exists to provide would silently not hold for a subject containing a bar.
     * Alternatives Considered: escaping with a second escape character, which is injective and is what
     * a delimited encoding needs. Rejected as more moving parts than a length prefix for the same
     * guarantee. Alternatives Considered: hashing each part and concatenating the digests. Rejected
     * because it makes a binding unreadable in a debugger and in a failure message.</p>
     *
     * <p>Trade-offs: {@code queryScope} is required rather than optional, and a query with no
     * narrowing predicate passes {@link #SCOPE_NONE} explicitly. An optional parameter would let a
     * caller omit a scope it should have supplied and get a working token, which is the failure this
     * method exists to prevent; naming the absence makes it a decision that appears at the call
     * site.</p>
     *
     * @param queryName the stable name of the query being paged, such as the resource being listed;
     *     must not be {@code null} or blank
     * @param subject the authenticated principal the token is issued to, which for a bearer-token
     *     caller is the token's subject claim; must not be {@code null} or blank, because a token
     *     bound to no subject is transferable between callers
     * @param queryScope the narrowing predicate this page was produced under, rendered so that two
     *     different predicates render differently, or {@link #SCOPE_NONE} when the query narrows
     *     nothing; must not be {@code null}
     * @return the composed binding, never {@code null} and never blank
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code queryName} or {@code subject} is blank
     */
    public static String binding(String queryName, String subject, String queryScope) {
        Objects.requireNonNull(queryName, "queryName must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(queryScope, "queryScope must not be null");
        if (queryName.isBlank()) {
            throw new IllegalArgumentException("queryName must not be blank; a binding that does not"
                    + " name its query lets a token issued for one listing open on another");
        }
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank; a binding that does not"
                    + " name its subject lets a token be redeemed by a different authorized caller");
        }

        return lengthPrefixed(queryName) + lengthPrefixed(subject) + lengthPrefixed(queryScope);
    }

    /**
     * Composes several narrowing predicates into one query scope for {@link #binding}.
     *
     * <p>Purpose: a scope frequently has to name more than one thing -- the direction of the step AND the
     * parent whose children are being walked, or a normalised search filter -- and every such caller
     * otherwise concatenates them by hand. Refactoring Rationale: three paging services were reported for
     * scopes that named the direction and omitted the parent, so a cursor issued while walking one
     * account's cards repositioned another account's. Hand-concatenation is what made the omission easy:
     * the parameter takes one string, so naming one thing looks complete. A composer whose parameters ARE
     * the predicates makes the omission visible at the call site.</p>
     *
     * <p>Assumptions: the parts are length-prefixed by the same private renderer {@link #binding} uses, so
     * the composition is injective for the same reason and needs no escaping and no reserved delimiter. A
     * scope composed here is therefore safe to pass as the third part of a binding, because neither
     * composition can be made to look like the other's parts.</p>
     *
     * <p>Alternatives Considered: joining with a separator such as a bar, which is what the reported call
     * sites would naturally have written. Rejected for exactly the reason recorded on {@link #binding}: a
     * delimited join is not injective unless it also escapes, and a scope carrying a caller-supplied filter
     * is precisely where an unescaped delimiter would let two different filters compose one scope.</p>
     *
     * <p>Trade-offs: at least one part is required rather than an empty call yielding {@link #SCOPE_NONE}.
     * A query that narrows nothing should say so by passing that constant to {@link #binding} directly, so
     * that "narrows nothing" reads differently from "someone called the composer with nothing".</p>
     *
     * @param parts the narrowing predicates, each already rendered so that two different predicates render
     *     differently -- for example {@code "direction:next"} and {@code "account:00000000011"}; must not
     *     be {@code null}, must carry at least one element, and no element may be {@code null}
     * @return the composed scope, never {@code null} and never blank
     * @throws NullPointerException if {@code parts} or any element is {@code null}
     * @throws IllegalArgumentException if {@code parts} is empty
     */
    public static String scope(String... parts) {
        Objects.requireNonNull(parts, "parts must not be null");
        if (parts.length == 0) {
            throw new IllegalArgumentException("parts must carry at least one predicate; a query that"
                    + " narrows nothing passes SCOPE_NONE to binding rather than composing an empty"
                    + " scope, so that the two cases read differently");
        }

        StringBuilder composed = new StringBuilder();
        for (String part : parts) {
            composed.append(lengthPrefixed(
                    Objects.requireNonNull(part, "no scope part may be null")));
        }
        return composed.toString();
    }

    /**
     * Renders one part as its character count, a length mark and the part itself.
     *
     * @param part the part to render; must not be {@code null}
     * @return the length-prefixed rendering, which no other part value can produce
     */
    private static String lengthPrefixed(String part) {
        return part.length() + String.valueOf(BINDING_LENGTH_MARK) + part;
    }

    /**
     * Confirms a caller supplied a binding to authenticate against.
     *
     * @param binding the caller-composed binding
     * @throws NullPointerException if {@code binding} is {@code null}
     * @throws IllegalArgumentException if {@code binding} is blank, because a blank binding would bind
     *     a token to nothing and let it be replayed against any query and any subject
     */
    private static void requireBinding(String binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        if (binding.isBlank()) {
            throw new IllegalArgumentException(
                    "binding must not be blank; a token has to be bound to the query and the subject"
                            + " it was issued for");
        }
    }

    /**
     * Encodes bytes as base64url without padding.
     *
     * @param raw the bytes to encode
     * @return the encoded text, which contains only characters the sealed shape admits
     */
    private static String encode(byte[] raw) {
        // WHY : Assumptions: the URL-and-filename-safe alphabet without padding is used rather than the
        //       standard one, because a token is carried in a JSON body that a client may put in a
        //       query string, and the standard alphabet's plus and slash characters change meaning
        //       there. Omitting the padding character removes an equals sign for the same reason.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Decodes base64url text that has already been matched against the sealed shape.
     *
     * @param encoded the encoded segment
     * @return the decoded bytes
     * @throws InvalidCursorException if the segment is not decodable, which the shape check makes
     *     unreachable for alphabet reasons but which remains reachable for a truncated segment
     */
    private static byte[] decode(String encoded) {
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidCursorException(
                    "cursor token carries a " + encoded.length()
                            + "-character segment that is not decodable base64url");
        }
    }

    /**
     * The stable token an alert rule or a log query matches a cursor refusal on.
     *
     * <p>Assumptions: one code for all three refusal reasons, matching the single exception type below,
     * because the three are deliberately indistinguishable to a caller.</p>
     */
    private static final String REFUSAL_CODE = "CURSOR_REFUSED";

    /**
     * The request parameter a cursor refusal is attributed to.
     *
     * <p>Assumptions: the name is the query parameter the published contracts declare the cursor under,
     * so a form or a client keys the refusal by the same name it sent.</p>
     */
    private static final String CURSOR_FIELD = "cursor";

    /**
     * Reports that a presented cursor token cannot be accepted.
     *
     * <p>Assumptions: one exception type covers every refusal -- malformed, unauthenticated, expired --
     * and that is deliberate rather than coarse. A caller's response to all three is identical: refuse
     * the request and start the browse again from its opening page. Distinguishing them in the type
     * would also let a handler report which of them occurred, and telling a client whether its token
     * was expired or merely unauthentic is information it can use to probe.</p>
     *
     * <p>Refactoring Rationale: the supertype is {@link ClientInputException} rather than
     * {@link IllegalArgumentException}. A cursor arrives from a caller as a query parameter, so a
     * refusal is the caller's to fix and belongs in the 400 shape -- but the shared advice no longer
     * claims the whole {@code IllegalArgumentException} family, precisely because that family also
     * carries every internal invariant in the migration. Naming the narrower supertype is what keeps
     * this refusal a 400 while a genuine invariant failure remains a 500. The message contract is
     * unchanged and is what satisfies the supertype's redaction obligation: it names the shape, the
     * length or the age of the token and never the characters it carried.</p>
     */
    public static final class InvalidCursorException extends ClientInputException {

        /** The serialisation version, fixed because this type's shape is its inherited message alone. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates a refusal carrying a message that names no token content.
         *
         * @param message what was wrong with the presented token, in terms of its shape, its length or
         *     its age, and never in terms of the characters it carried
         */
        InvalidCursorException(String message) {
            // WHY : Assumptions: the stable code and the field key are fixed rather than taken from the
            //       caller, because every refusal of this type concerns the same one parameter and a
            //       caller's response to all of them is identical -- start the browse again from its
            //       opening page. Accepting them per raise site would let one refusal be keyed
            //       differently from another for one parameter.
            super(REFUSAL_CODE, CURSOR_FIELD, message);
        }
    }
}
