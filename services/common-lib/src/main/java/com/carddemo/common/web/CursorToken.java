package com.carddemo.common.web;

import com.carddemo.common.error.ClientInputException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
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
 * <p><strong>It is opaque.</strong> The key is carried inside the token's payload rather than as the
 * token, and the payload is base64url text, so no client reads a card number out of a page response.
 * This is confidentiality by encoding rather than by encryption, and the distinction is stated plainly
 * because it matters: base64url is reversible by anyone. What makes the arrangement sound is that the
 * token never leaves the boundary it was minted for and never reaches durable logging -- the API
 * access-log format carries no request body and no query string, and the route contract forbids a card
 * number in a path. Where a deployment cannot hold that assumption, the payload must be encrypted with
 * the same key material rather than encoded; the seam for that is one method.</p>
 *
 * <p><strong>It is authenticated.</strong> Every token carries a message authentication code over its
 * own payload and over a caller-supplied binding, keyed with material the client does not hold. A token
 * whose payload is edited fails to open. This is what stops a client from turning a page cursor into an
 * arbitrary key predicate: without the key it cannot mint a token naming a row it was never shown.</p>
 *
 * <p><strong>It is bound and bounded.</strong> The binding is a caller-composed string naming the
 * query and the subject the token was issued to, and it is covered by the authentication code without
 * being carried in the token, so a token minted for one query and one user cannot be presented on
 * another. Both the key inside a token and the token itself are length-bounded, so a token arriving
 * from a client cannot provoke work disproportionate to its size.</p>
 *
 * <h2>Assumptions this class makes</h2>
 *
 * <p>Assumptions: the key material is supplied by the caller and comes from the deployment's secret
 * store rather than from this class, which holds no default and generates nothing. A default would be a
 * committed secret, and a generated per-process key would invalidate every token a sibling task issued,
 * which for a horizontally scaled service is every second token. The key is required to be at least
 * {@code MIN_KEY_LENGTH} bytes, which is the output width of the digest the authentication code is
 * built on: a shorter key reduces the strength of the code without any diagnostic, so it is refused.</p>
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
     */
    public static final String VERSION = "v1";

    /**
     * The greatest number of characters a sealed token may carry.
     *
     * <p>Assumptions: the widest cursor in the migration is the 27-character composite of card number
     * and account identifier at lines 230 to 232 of {@code app/cbl/COCRDLIC.cbl}. Sealed, that becomes
     * a version marker, an issue instant, the key itself and a 43-character encoded authentication
     * code, which is comfortably inside 256 characters. The bound exists so that a token arriving from
     * a client is rejected on its length before any decoding or verification is attempted.</p>
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
     * The shape a sealed token has to match before any part of it is decoded.
     *
     * <p>Assumptions: three dot-separated segments, of which the first is the version marker and the
     * other two are base64url without padding. Matching the shape first is what makes a raw key --
     * a bare card number, or a card number concatenated with an account identifier -- fail as a token
     * rather than being accepted as one, which is the enforcement {@link PageResponse} was missing.</p>
     */
    private static final Pattern SEALED_SHAPE =
            Pattern.compile(VERSION + "\\.[A-Za-z0-9_-]{1,200}\\.[A-Za-z0-9_-]{43}");

    /**
     * The name of the message authentication algorithm this class uses.
     *
     * <p>Alternatives Considered: a plain digest over the key material concatenated with the payload.
     * Rejected because a bare digest construction is vulnerable to length extension, so a client
     * holding one valid token could append to its payload and compute a code that verifies. A keyed
     * message authentication code is specified precisely to close that, which is why it is used rather
     * than assembled by hand.</p>
     */
    private static final String MAC_ALGORITHM = "HmacSHA256";

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

    /** The key material the authentication code is computed with, held as an immutable copy. */
    private final byte[] key;

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

        // WHY : Trade-offs: the array is copied on the way in and never handed back out, so a caller
        //       that clears its own copy -- which a caller reading key material from a secret store
        //       should -- cannot invalidate this instance, and a caller cannot reach in and change the
        //       key of a sealer another component is using. The cost is one array copy per instance,
        //       and an instance is built once per application context.
        this.key = key.clone();
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
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        String code = encode(authenticate(binding, encodedPayload));
        return VERSION + SEGMENT_SEPARATOR + encodedPayload + SEGMENT_SEPARATOR + code;
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
                            + " an encoded payload and an encoded authentication code, separated by"
                            + " dots");
        }

        int payloadStart = VERSION.length() + 1;
        int codeStart = token.lastIndexOf(SEGMENT_SEPARATOR) + 1;
        String encodedPayload = token.substring(payloadStart, codeStart - 1);
        byte[] presentedCode = decode(token.substring(codeStart));

        // WHY : Assumptions: the comparison is the constant-time one rather than array equality. An
        //       ordinary comparison returns as soon as two bytes differ, so the time it takes reveals
        //       how many leading bytes of a guess were right, and a client able to measure that can
        //       recover a valid code one byte at a time without ever holding the key.
        if (!MessageDigest.isEqual(authenticate(binding, encodedPayload), presentedCode)) {
            throw new InvalidCursorException(
                    "cursor token failed authentication; it was either altered or issued for a"
                            + " different query or subject");
        }

        String payload = new String(decode(encodedPayload), StandardCharsets.UTF_8);
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
     * Computes the authentication code over a binding and an encoded payload.
     *
     * <p>Assumptions: the binding is authenticated by being fed to the code rather than by being
     * carried in the token, and its length is fed in first so that two different bindings cannot
     * produce the same input by moving the boundary between them -- the binding {@code "ab"} with
     * payload {@code "c"} and the binding {@code "a"} with payload {@code "bc"} would otherwise
     * authenticate identically.</p>
     *
     * @param binding the caller-composed query and subject binding
     * @param encodedPayload the token's encoded payload segment
     * @return the raw authentication code
     * @throws IllegalStateException if the platform does not provide {@code MAC_ALGORITHM} or rejects
     *     the key, neither of which is recoverable at run time: the algorithm is one every Java
     *     platform is required to provide, and the key was validated at construction
     */
    private byte[] authenticate(String binding, String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            mac.update(Integer.toString(binding.length()).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) PAYLOAD_SEPARATOR);
            mac.update(binding.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) SEGMENT_SEPARATOR);
            mac.update(VERSION.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) SEGMENT_SEPARATOR);
            mac.update(encodedPayload.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException fatal) {
            throw new IllegalStateException(
                    "the platform cannot compute " + MAC_ALGORITHM
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
