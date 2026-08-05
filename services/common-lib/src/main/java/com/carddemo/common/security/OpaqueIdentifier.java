package com.carddemo.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns protected data into a stable, opaque token that can be used wherever an identity is needed but
 * the value itself must not appear.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Several places in the migrated system need an identity rather than a value. A queue needs a group
 * identifier so that every message about one card is delivered in order; a request and its reply need a
 * correlation identity so a consumer can pair them; a log line needs something an operator can group by.
 * The reference baseline satisfied all three with the primary account number itself -- the correlation
 * key of the authorization flow is the card number followed by the transaction identifier -- because on
 * z/OS every one of those places was inside the same protected boundary.</p>
 *
 * <p>Refactoring Rationale: in the migrated system they are not. A queue's group identifier is message
 * <em>metadata</em>, so it sits outside the encrypted message body, is visible in the queue's own
 * telemetry, and is recorded by anything that traces a send. A correlation identity reaches log storage
 * by design. Carrying the account number through any of them would put it in exactly the places the
 * mapping layer masks it out of, and would do so in a form that is durable and searchable. This class
 * supplies the identity without the value: a keyed authentication code over the protected data, rendered
 * as bounded URL-safe text.</p>
 *
 * <h2>The three properties that make it usable as an identity</h2>
 *
 * <p><strong>It is stable.</strong> The same input under the same key always yields the same token, so
 * two messages about one card land in one group and a reply pairs with its request by equality. This is
 * the property a random identifier cannot provide and is the reason a random one is not used.</p>
 *
 * <p><strong>It is not reversible by the holder.</strong> The token is a keyed code and not an encoding,
 * so a party holding the token and not the key cannot recover the input. This is what distinguishes it
 * from the base64 rendering used for a cursor payload, which is reversible by anyone.</p>
 *
 * <p><strong>It resists confirmation.</strong> Assumptions: the inputs this class protects are
 * low-entropy -- a sixteen-digit card number, an eleven-digit account identifier -- so an unkeyed digest
 * of one can be confirmed by an adversary who guesses the value and hashes it, and the space is small
 * enough to enumerate. A keyed code cannot be computed without the key, so a guess cannot be confirmed.
 * That is the whole reason this class requires key material rather than offering a digest.</p>
 *
 * <h2>Assumptions and boundaries</h2>
 *
 * <p>Assumptions: the key comes from the deployment's secret store and this class holds no default. A
 * committed default would be a committed secret and would make every token in every environment
 * computable by anyone with the source. A per-process random key would break stability across the tasks
 * of one horizontally scaled service, which would put two messages about one card into two groups.</p>
 *
 * <p>Assumptions: a token is scoped by a caller-supplied purpose string, which is authenticated along
 * with the value. Two purposes therefore produce two unrelated tokens for the same input, so a queue
 * group identifier cannot be correlated with a log identity for the same card by an observer who sees
 * both. Reusing one token across purposes would let separate systems be joined on it, which is the
 * linkage a tokenised identifier is supposed to prevent.</p>
 *
 * <p>Assumptions: this class does not decide WHICH values are protected, and deliberately holds no list
 * of field names. That knowledge belongs to the mapping layer and to the copybook layout descriptors,
 * and duplicating it here would create a second place it could go stale.</p>
 */
public final class OpaqueIdentifier {

    /**
     * The number of characters a token carries.
     *
     * <p>Assumptions: 22 base64url characters carry 132 bits of the underlying code, which is far beyond
     * what a birthday bound on any realistic number of cards requires, so two different cards colliding
     * into one queue group is not a practical concern. The bound also matters at the other end: a queue
     * group identifier and a correlation attribute both have length limits measured in the low hundreds
     * of characters, and a token has to be comfortably inside them.</p>
     *
     * <p>Alternatives Considered: carrying the whole code, which is 43 characters encoded. Rejected
     * because the extra length buys no security that matters here while making every log line and every
     * message attribute longer, and because a truncated keyed code remains unforgeable without the key,
     * which is the property being relied on.</p>
     */
    public static final int TOKEN_LENGTH = 22;

    /**
     * The fewest bytes of key material this class accepts.
     *
     * <p>Assumptions: 32 is the output width of the digest the code is built on, so a key at least this
     * long does not weaken it. A shorter key still produces well-formed, stable tokens, so nothing would
     * reveal the weakness at run time -- which is why it is refused at construction instead.</p>
     */
    public static final int MIN_KEY_LENGTH = 32;

    /**
     * The name of the message authentication algorithm this class uses.
     *
     * <p>Alternatives Considered: a plain digest of the key material concatenated with the value, and a
     * plain digest of a salt concatenated with the value. The first is vulnerable to length extension;
     * the second requires the salt to travel with the token for the token to be reproducible, at which
     * point the salt is public and the construction is an unkeyed digest of a low-entropy value again --
     * confirmable by guessing. A keyed code has neither problem.</p>
     */
    private static final String MAC_ALGORITHM = "HmacSHA256";

    /** The key material the code is computed with, held as an immutable copy. */
    private final byte[] key;

    /**
     * Creates a tokeniser over one piece of key material.
     *
     * @param key the key material from the deployment's secret store, at least {@code MIN_KEY_LENGTH}
     *     bytes; the array is copied, so a caller may clear its own copy afterwards
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalArgumentException if {@code key} is shorter than {@code MIN_KEY_LENGTH} bytes
     */
    public OpaqueIdentifier(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.length < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "opaque identifier key material is " + key.length + " bytes; at least "
                            + MIN_KEY_LENGTH + " are required to key " + MAC_ALGORITHM);
        }

        // WHY : Trade-offs: the array is copied in and never handed back out, so a caller that clears
        //       its own copy -- which a caller reading key material from a secret store should -- cannot
        //       invalidate this instance, and no caller can reach in and change the key another
        //       component is tokenising under. One array copy per application context is the cost.
        this.key = key.clone();
    }

    /**
     * Produces the stable opaque token for one protected value under one purpose.
     *
     * @param purpose what the token will be used as, for example a queue group identity or a log
     *     correlation identity; authenticated along with the value so that two purposes yield unrelated
     *     tokens for the same value, and must not be blank
     * @param protectedValue the value that must not itself appear -- a card number, an account
     *     identifier, or a composite of both -- which is read and never retained
     * @return the token, exactly {@code TOKEN_LENGTH} URL-safe characters, stable for this input under
     *     this key and purpose
     * @throws NullPointerException if {@code purpose} or {@code protectedValue} is {@code null}
     * @throws IllegalArgumentException if {@code purpose} is blank, or if {@code protectedValue} is
     *     blank, because a token over nothing would be one shared constant standing in for every value
     *     that had not been supplied
     * @throws IllegalStateException if the platform cannot compute {@code MAC_ALGORITHM} or rejects the
     *     key, neither of which is recoverable: the algorithm is one every Java platform must provide
     *     and the key was validated at construction
     */
    public String token(String purpose, String protectedValue) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(protectedValue, "protectedValue must not be null");
        if (purpose.isBlank()) {
            throw new IllegalArgumentException(
                    "purpose must not be blank; an unscoped token can be correlated across the"
                            + " separate systems that use it");
        }
        if (protectedValue.isBlank()) {
            throw new IllegalArgumentException(
                    "protectedValue must not be blank; a token over nothing would be one constant"
                            + " standing in for every value a caller failed to supply");
        }

        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));

            // WHY : Assumptions: the purpose's length is fed in before the purpose itself, so that no
            //       two purpose-and-value pairs can produce the same input by moving the boundary
            //       between them. Without it the purpose "ab" with value "c" and the purpose "a" with
            //       value "bc" would tokenise identically, which would defeat the scoping this argument
            //       exists to provide.
            mac.update(Integer.toString(purpose.length()).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) ':');
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) ':');
            mac.update(protectedValue.getBytes(StandardCharsets.UTF_8));

            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
            return encoded.substring(0, TOKEN_LENGTH);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException fatal) {
            throw new IllegalStateException(
                    "the platform cannot compute " + MAC_ALGORITHM
                            + ", so no opaque identifier can be produced", fatal);
        }
    }
}
