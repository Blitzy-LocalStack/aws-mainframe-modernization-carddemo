package com.carddemo.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mints the short-lived bearer token one bounded context presents to another.
 *
 * <h2>Purpose</h2>
 *
 * <p>This is the machine identity for service-to-service calls inside the private network. It exists because
 * the reference system had no such concept and needed none: every program in a CICS region reaches every file
 * in it, so a program reading another program's data presented nothing. Once those files are owned by separate
 * deployables reached over HTTP, the caller has to be authenticated as something -- and it is not a person, so
 * none of the human sign-on machinery applies to it.</p>
 *
 * <p>Assumptions: the token is signed with a SYMMETRIC key using HMAC with SHA-256, not with an
 * asymmetric key. The two ends are deployed together from one repository into one environment by one apply, so
 * there is no third party who must verify without being able to mint -- which is the property asymmetric
 * signing buys and the only reason to pay for it. A symmetric key additionally needs no key-distribution
 * endpoint: the verifier holds the same secret the minter does, injected from the same secret store, so a
 * verification involves no network call and cannot fail because a key document was unreachable.</p>
 *
 * <p>Refactoring Rationale: there is one key PER CALLING SERVICE, where there used to be one key shared by
 * every caller, and one scope PER OPERATION FAMILY, where there used to be one scope covering every internal
 * address of the callee. The shared arrangement had two defects that compounded. Because both callers signed
 * with the same bytes, either could mint a token carrying the OTHER's subject, and because the callee never
 * examined the subject at all, that impersonation was undetectable; and because one scope covered the whole
 * internal surface, a caller needing one address was authorised for all eight, so the impersonation was also
 * worth performing. Splitting the key binds the subject cryptographically -- a token is verified against the
 * key belonging to the subject it names, so a token minted with one caller's key and another caller's name
 * fails its signature check -- and splitting the scope means a caller carries only what its own operation
 * needs. Alternatives Considered: keeping one key and merely validating the subject. Rejected because with a
 * shared key the subject is a claim any holder can write, so validating it would refuse an unknown name while
 * still admitting a known one written by the wrong party -- which is precisely the impersonation.
 * Alternatives Considered: holding both keys as unlabelled verification candidates and accepting whichever
 * matched. Rejected for the same reason: a token signed by either key would verify regardless of the subject it
 * claimed, so the binding would not exist. The key is therefore identified in the JWS header by the subject it
 * belongs to, and the verifier selects by that identifier and then requires the two to agree.</p>
 *
 * <p>Assumptions: the JWS header carries a key identifier and its value IS the subject. Two values that must
 * always agree are better expressed as one, and the alternative -- an opaque key name plus a mapping from name
 * to subject -- would add a table that could disagree with itself. The verifier still asserts the equality
 * rather than assuming it, because the header and the payload are separate parts of the token and only the
 * payload's subject is what an authorization decision reads.</p>
 *
 * <p>Alternatives Considered: obtaining a token from the identity provider with a client-credentials grant.
 * That is the conventional answer and it is unavailable here for a concrete reason rather than a preference:
 * the provisioned user pool has no hosted domain -- {@code infra/modules/cognito} leaves its domain prefix
 * unset -- so the pool exposes no token endpoint at all, and adding one would introduce a public hostname and
 * a second public surface for the sole benefit of an entirely private call. Alternatives Considered: mutual
 * transport-layer authentication between the tasks, which is stronger and was rejected as disproportionate --
 * it would require issuing and rotating a client certificate per service and would still leave the callee
 * unable to distinguish WHICH operation the caller was entitled to, which is what the scope claim below
 * carries. Alternatives Considered: an unsigned shared header value. Rejected outright: a static shared secret
 * in a header is replayable forever by anything that observes one request, and this token expires.</p>
 *
 * <p>Assumptions: the lifetime is MINUTES and deliberately short. The token is minted per call by a process
 * that already holds the key, so a short life costs nothing -- there is no refresh to arrange and no cache to
 * invalidate -- while a long one turns a single captured request into a durable credential. The bound is
 * enforced by the verifier's own expiry check rather than by convention.</p>
 *
 * <p>Assumptions: the audience and the scope are separate claims and both are verified. The audience says
 * which service the token was minted FOR, so a token minted for one callee cannot be replayed against
 * another; the scope says which family of operations it authorises, so a token minted for the account-context
 * reads cannot be presented to an unrelated internal endpoint that happens to share the callee. Verifying only
 * one of the two would leave the other kind of replay open.</p>
 *
 * <p>Assumptions: the SUBJECT is verified too, against {@link #ADMITTED_SUBJECTS}, and it is a third
 * independent check rather than a restatement of the audience. The audience names the callee and the subject
 * names the caller, so a token carrying a subject this deployment mints for nothing is refused instead of
 * being accepted and attributed to a workload that does not exist. It is not an authentication on its own --
 * the key is shared, so any holder can mint any subject -- which is exactly why it is stated here as a closed
 * set the verifier applies rather than as a property the token asserts about itself.</p>
 *
 * <p>Trade-offs: this class mints and does not verify. Verification is left to the resource-server machinery
 * on the receiving side, which already knows how to decode a signed token, check its signature against a
 * secret key and apply validators -- so a hand-written verifier here would be a second implementation of
 * something the framework does, and the two could disagree about an edge case such as a missing expiry. What
 * this class does provide is the CONSTANTS both sides use, so the minter and the verifier cannot disagree
 * about a claim name or an algorithm.</p>
 */
public final class InternalServiceToken {

    /**
     * The signing algorithm both ends use.
     *
     * <p>Assumptions: this is fixed in code rather than configurable, and the fixing is the security control.
     * An algorithm read from configuration -- or worse, from the token's own header -- is how a verifier ends
     * up accepting an unsigned token: the classic failure is a header naming no algorithm at all, which a
     * verifier that trusts the header treats as valid. Naming one algorithm in one shared constant means both
     * ends admit exactly one.</p>
     */
    public static final JWSAlgorithm SIGNING_ALGORITHM = JWSAlgorithm.HS256;

    /**
     * The signing algorithm's name, for the verifier's own decoder configuration.
     *
     * <p>Assumptions: exposed as a string as well as an object because the framework's decoder builder takes
     * the name, while the signer takes the object. Deriving one from the other at each site would let the two
     * drift; deriving it here once means they cannot.</p>
     */
    public static final String SIGNING_ALGORITHM_NAME = SIGNING_ALGORITHM.getName();

    /**
     * The minimum admissible key length in bytes.
     *
     * <p>Assumptions: thirty-two, which is the output width of the digest the signature is built on. A shorter
     * key is admitted by some implementations and reduces the work an attacker needs to forge a signature to
     * below the digest's own strength, so it is refused here rather than accepted with a warning.</p>
     */
    public static final int MIN_KEY_LENGTH = 32;

    /**
     * The issuer every internal token carries.
     *
     * <p>Assumptions: a fixed opaque identifier rather than a URL. An issuer URL invites a verifier to fetch
     * something from it, and there is nothing to fetch: this token is verified against a shared secret, so the
     * issuer's only job is to distinguish an internal token from a token minted by the identity provider. A
     * value that is not a URL makes that distinction unmistakable.</p>
     */
    public static final String ISSUER = "carddemo-internal";

    /**
     * The claim carrying the family of operations a token authorises.
     *
     * <p>Assumptions: the name matches the one the framework's own authority converter reads by default, so a
     * verifier needs no custom claim mapping to turn this into an authority.</p>
     */
    public static final String SCOPE_CLAIM = "scope";

    /**
     * The claim carrying the identifier of the key a token was signed with.
     *
     * <p>Assumptions: the name is the registered JOSE header parameter rather than a private one, so the
     * verifier's key selection is done by the library's own mechanism instead of by code of ours reading a
     * custom header.</p>
     */
    public static final String KEY_ID_HEADER = "kid";

    /**
     * The scope authorising the card cross-reference reads of the account context.
     *
     * <p>Assumptions: the three cross-reference addresses share ONE scope, where the account and customer
     * families have their own. They are one operation family in the sense that matters: all three resolve a
     * cross-reference row, differing only in which column they are keyed by, so a caller entitled to one is
     * entitled to the others on the same grounds. Splitting them further would produce three values always
     * issued together, which is a distinction with no decision behind it.</p>
     */
    public static final String SCOPE_CARD_XREF_READ = "internal:account-context.card-xref.read";

    /** The scope authorising the account reads of the account context. */
    public static final String SCOPE_ACCOUNT_READ = "internal:account-context.account.read";

    /**
     * The scope authorising the customer reads of the account context.
     *
     * <p>Assumptions: this scope is the reason the split is worth making. The customer addresses expose a
     * national identifier and a government-issued identifier, and only ONE of the two callers reads them; the
     * shared arrangement authorised both. Naming the family separately is what lets the callee refuse the
     * caller that has no business there, rather than relying on that caller not to ask.</p>
     */
    public static final String SCOPE_CUSTOMER_READ = "internal:account-context.customer.read";

    /**
     * The scope a token authorising the customer-master maintenance reads carries.
     *
     * <p>Purpose. Separates the two operations that answer with, or page over, WHOLE customer records
     * from the single-key decision reads above. Its members are the keyed customer record read and the
     * ascending customer-master scan -- the two access paths the batch reader
     * {@code app/cbl/CBCUS01C.cbl} declares, positioned by {@code RECORD KEY IS FD-CUST-ID} at L32 and
     * swept by the loop at L74 through L81.</p>
     *
     * <p>Assumptions: the split is drawn by what a token can READ rather than by which service calls it,
     * because that is the boundary that matters when a credential leaks. A decision read answers a
     * question the caller already knows the key to and returns a handful of fields; the record read
     * returns all eighteen fields the reference layout declares at L5 through L22 of
     * {@code app/cpy/CVCUS01Y.cpy}, and the scan returns them for every customer in the master. The
     * difference between those is the difference between confirming one row and exfiltrating a file, so
     * they do not share a credential.</p>
     *
     * <p>Assumptions: no service mints this scope today, and that is the correct state rather than an
     * unfinished one. Neither operation has a service consumer -- the authorization and transaction
     * contexts make the decision reads only, and no browser client consumes this contract at all, which
     * {@code ui/src/api/contracts.test.ts} asserts by excluding the account document from its client
     * inventory. What the scope does is make reaching them require a credential nothing currently issues,
     * so the operations the plan requires this context to publish stay published and stay addressable by
     * a holder of the internal signing key, without any existing token reaching them.</p>
     *
     * <p>Alternatives Considered: three other placements, each rejected for a specific reason. Deleting
     * the two operations -- the plan requires this context to migrate {@code app/cbl/CBCUS01C.cbl}, and
     * these two ARE its access surface, so deleting them would drop required capability. Granting them to
     * a business group on the end-user chain -- {@code SecurityConfig} records, correctly, that no
     * baseline screen reads a customer record directly, so granting a group would ADD a capability rather
     * than preserve one. Leaving them on the decision scope and relying on the callers not to misuse it
     * -- that is the state being corrected, and it makes the boundary a property of caller behaviour
     * rather than of the credential.</p>
     *
     * @see #SCOPE_CUSTOMER_READ
     */
    public static final String SCOPE_CUSTOMER_MASTER_READ = "internal:customer-master.read";

    /**
     * The audience a token minted for the account context carries.
     */
    public static final String AUDIENCE_ACCOUNT_CONTEXT = "carddemo-account-service";

    /**
     * The subject a token minted by the authorization context carries.
     *
     * <p>Assumptions: declared in the shared kernel rather than only in that service's own configuration, so
     * the minter and the verifier read ONE spelling. A verifier holding its own copy of a caller's name is a
     * verifier that can be made to admit nothing -- or everything -- by a rename in a file it does not
     * import.</p>
     */
    public static final String SUBJECT_AUTHORIZATION_SERVICE = "carddemo-authorization-service";

    /**
     * The subject a token minted by the transaction context carries.
     */
    public static final String SUBJECT_TRANSACTION_SERVICE = "carddemo-transaction-service";

    /**
     * The closed set of workload identities a verifier admits.
     *
     * <p>Purpose: lets a callee refuse a token whose signature and claims are otherwise correct but whose
     * subject is not one of the two services this deployment mints for.</p>
     *
     * <p>Assumptions: the set is CLOSED and lives beside the key length and the algorithm for the same
     * reason they do -- it is a property of the credential boundary rather than of one callee. The shared
     * signing key means any holder can mint any subject, so the subject alone is not an authentication; what
     * it provides is that a token minted with an unrecognised or absent subject is refused rather than
     * accepted and attributed to nothing, which is what a callee's audit record depends on.</p>
     *
     * <p>Trade-offs: adding a third caller is now an edit here as well as in that caller's own
     * configuration. That is the intended friction: a new workload reaching another context's internal
     * surface is a decision, and the alternative -- admitting any subject -- is what let the reach of the
     * single scope above go unnoticed.</p>
     */
    public static final Set<String> ADMITTED_SUBJECTS =
            Set.of(SUBJECT_AUTHORIZATION_SERVICE, SUBJECT_TRANSACTION_SERVICE);

    /**
     * Which scopes each subject may carry, as a closed table both ends read.
     *
     * <p>Assumptions: the table is stated HERE rather than on either side of the call, because the minter
     * must refuse to issue what the verifier would refuse to accept. Two copies of this table -- one
     * deciding what to mint and one deciding what to admit -- would disagree eventually, and the failure
     * would appear as an intermittent refusal on one operation rather than as a mismatch anybody could
     * see.</p>
     *
     * <p>Assumptions: the transaction context is granted the cross-reference and account families and NOT
     * the customer family, and that asymmetry is the point of the table. Its client reaches the
     * cross-reference and the account balance; nothing in it reads a customer record. The authorization
     * context is granted all three because its decision path resolves a cross-reference, an account and
     * the customer identifier the fraud row carries.</p>
     *
     * <p>Assumptions: {@link #SCOPE_CUSTOMER_MASTER_READ} appears in NO row, deliberately. The two
     * operations that disclose a whole customer record are gated on it and nothing in this deployment
     * mints it, so publishing the operations while issuing no credential for them is what keeps that
     * capability unreachable rather than merely unused.</p>
     */
    private static final Map<String, Set<String>> PERMITTED_SCOPES = Map.of(
            SUBJECT_AUTHORIZATION_SERVICE,
            Set.of(SCOPE_CARD_XREF_READ, SCOPE_ACCOUNT_READ, SCOPE_CUSTOMER_READ),
            SUBJECT_TRANSACTION_SERVICE,
            Set.of(SCOPE_CARD_XREF_READ, SCOPE_ACCOUNT_READ));

    /**
     * The longest lifetime this class will mint.
     *
     * <p>Assumptions: five minutes, and the bound exists so that a configured lifetime cannot quietly become a
     * long-lived credential. The token is minted per call by a holder of the key, so nothing legitimate needs
     * longer -- a call that took five minutes has already exceeded every timeout on the path.</p>
     */
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(5);

    /**
     * The key material, held as a copy so a caller's array cannot reach it.
     */
    private final byte[] key;

    /**
     * The clock the issue and expiry instants are read from.
     */
    private final Clock clock;

    /**
     * How long each minted token is valid for.
     */
    private final Duration lifetime;

    /**
     * The subject every minted token carries: the calling service's own name.
     */
    private final String subject;

    /**
     * Creates a minter over the deployment's shared internal signing key.
     *
     * <p>Assumptions: the key is copied on the way in, so a caller that clears its own array -- which a caller
     * handling key material should -- does not empty this one. The copy is the reason the array parameter is
     * safe to accept at all.</p>
     *
     * @param key the calling service's own signing key from the deployment's secret store, at least
     *     {@link #MIN_KEY_LENGTH} bytes; must not be {@code null}
     * @param subject the calling service's own name, carried as the token subject and as the key identifier,
     *     so a callee's log records which service called it and its verifier knows which key to check
     *     against; must be one of the {@code SUBJECT_} constants
     * @param clock the clock the issue and expiry instants are read from; must not be {@code null}
     * @param lifetime how long each minted token is valid for; must be positive and at most
     *     {@link #MAX_LIFETIME}
     * @throws NullPointerException if {@code key}, {@code subject} or {@code clock} is {@code null}
     * @throws IllegalArgumentException if the key is shorter than {@link #MIN_KEY_LENGTH} bytes, if the
     *     subject is not one of the {@code SUBJECT_} constants, or if the lifetime is not positive or
     *     exceeds {@link #MAX_LIFETIME}
     */
    public InternalServiceToken(byte[] key, String subject, Clock clock, Duration lifetime) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(lifetime, "lifetime must not be null");
        if (key.length < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("the internal signing key is " + key.length
                    + " bytes; at least " + MIN_KEY_LENGTH + " are required to key "
                    + SIGNING_ALGORITHM_NAME);
        }
        // WHY : Refactoring Rationale: the subject is checked against the CLOSED SET here, where it used
        //   only to be checked for blankness. A subject outside the set names a caller the verifier holds
        //   no key for, so every token minted under it would be refused at the callee -- a whole
        //   deployment's worth of 401s traced to a misconfigured string. Refusing at construction turns
        //   that into a container that does not start, which names the property and the value.
        if (!PERMITTED_SCOPES.containsKey(subject)) {
            throw new IllegalArgumentException("subject must be one of " + knownSubjects()
                    + " but was '" + subject + "'; the verifying context holds one key per known"
                    + " subject and could not check a token minted under any other name");
        }
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("lifetime must be positive and at most " + MAX_LIFETIME
                    + " but was " + lifetime
                    + "; a longer-lived internal token turns one captured request into a durable"
                    + " credential");
        }
        this.key = key.clone();
        this.subject = subject;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    /**
     * Mints a token for one audience and one scope.
     *
     * <p>Assumptions: a fresh token is minted per call rather than cached for its lifetime. Caching would save
     * a signature -- which costs microseconds against a network call measured in milliseconds -- and would
     * introduce a window in which a token near its expiry is handed out and rejected on arrival, producing an
     * intermittent authorization failure that looks like a permissions problem. Minting each time removes that
     * class of failure entirely.</p>
     *
     * <p>Assumptions: the token carries an issue instant as well as an expiry. The expiry is what the verifier
     * enforces; the issue instant is what makes a captured token attributable to a moment in an investigation,
     * and it costs one claim.</p>
     *
     * @param audience the service the token is minted for, one of the {@code AUDIENCE_} constants; must not be
     *     {@code null}
     * @param scope the family of operations the token authorises, one of the {@code SCOPE_} constants and one
     *     this instance's subject is permitted to carry; must not be {@code null}
     * @return the serialised signed token, ready to be presented as a bearer credential; never {@code null}
     * @throws NullPointerException if {@code audience} or {@code scope} is {@code null}
     * @throws IllegalArgumentException if this instance's subject is not permitted to carry {@code scope}
     * @throws IllegalStateException if the token cannot be signed, which indicates the configured key is
     *     unusable rather than a transient condition, so it is not retryable
     */
    public String mint(String audience, String scope) {
        Objects.requireNonNull(audience, "audience must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        // WHY : Assumptions: the minter refuses a scope its own subject may not carry, rather than issuing
        //   it and letting the callee refuse. The two refusals are not equivalent to whoever has to
        //   diagnose them: refused here, the failure names the caller, the scope and the permitted set at
        //   the moment of the mistake; refused there, it arrives as a 403 on one address, which reads as a
        //   policy problem at the callee. Checking both ends is deliberate duplication of a decision, not
        //   of a table -- the table is PERMITTED_SCOPES and there is one of it.
        if (!permits(this.subject, scope)) {
            throw new IllegalArgumentException("'" + this.subject + "' may carry "
                    + PERMITTED_SCOPES.get(this.subject) + " but not '" + scope
                    + "'; the verifying context refuses a scope outside the caller's own set");
        }

        Instant issuedAt = this.clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(this.subject)
                .audience(List.of(audience))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(this.lifetime)))
                .claim(SCOPE_CLAIM, scope)
                .build();

        // WHY : Assumptions: the header names the type explicitly as well as the algorithm. A verifier that
        //   accepts more than one token shape -- and this system's account context accepts both an
        //   identity-provider token on its business paths and an internal token on its internal paths --
        //   can then reject a token presented on the wrong path by its type alone, before any claim is read.
        // WHY : Assumptions: the key identifier is the subject, so the verifier selects the key belonging
        //   to the caller the token NAMES and the signature check then proves the name. Omitting it would
        //   leave the verifier trying every key it holds, which admits a token signed by one caller under
        //   another caller's name -- the impersonation the per-caller keys exist to close.
        JWSHeader header = new JWSHeader.Builder(SIGNING_ALGORITHM)
                .type(JOSEObjectType.JWT)
                .keyID(this.subject)
                .build();
        SignedJWT token = new SignedJWT(header, claims);
        try {
            token.sign(new MACSigner(this.key));
        } catch (JOSEException unusableKey) {
            // WHY : Assumptions: this is reported as an illegal STATE rather than wrapped as a transport
            //   failure, and it is deliberately not retryable. The only way signing fails once the key
            //   length has been checked in the constructor is that the key is unusable, which no number of
            //   retries changes -- so a caller must fail rather than redeliver a message forever.
            throw new IllegalStateException(
                    "the internal service token could not be signed; the configured key is unusable",
                    unusableKey);
        }
        return token.serialize();
    }

    /**
     * Reports the closed set of subjects a token may be minted under.
     *
     * <p>Assumptions: the verifier reads this rather than declaring its own list, so a caller added on the
     * minting side cannot be one the callee has never heard of.</p>
     *
     * @return the known subjects, sorted so a message built from it reads the same every time; never
     *     {@code null}
     */
    public static Set<String> knownSubjects() {
        return new TreeSet<>(PERMITTED_SCOPES.keySet());
    }

    /**
     * Reports whether one subject is a known caller.
     *
     * @param subject the subject claim of a presented token, which may be {@code null} on a token that
     *     carries none
     * @return {@code true} when the subject is one of the {@code SUBJECT_} constants
     */
    public static boolean isKnownSubject(String subject) {
        return subject != null && PERMITTED_SCOPES.containsKey(subject);
    }

    /**
     * Reports whether one subject is permitted to carry one scope.
     *
     * <p>Assumptions: an unknown subject permits nothing, so this single predicate answers both questions a
     * verifier asks and cannot answer one of them affirmatively while the other is unresolved.</p>
     *
     * @param subject the subject claim of a presented token, which may be {@code null}
     * @param scope the scope claim of a presented token, which may be {@code null}
     * @return {@code true} only when the subject is known and the scope is in that subject's own set
     */
    public static boolean permits(String subject, String scope) {
        return scope != null && PERMITTED_SCOPES.getOrDefault(subject, Set.of()).contains(scope);
    }

    /**
     * Reports the scopes one subject is permitted to carry.
     *
     * @param subject the subject to report for, which may be {@code null}
     * @return that subject's permitted scopes, sorted; empty for an unknown subject; never {@code null}
     */
    public static Set<String> permittedScopes(String subject) {
        return new TreeSet<>(PERMITTED_SCOPES.getOrDefault(subject, Set.of()));
    }

    /**
     * Returns a copy of the signing key, for a verifier that must hold the same material.
     *
     * <p>Assumptions: a COPY is returned, so a caller cannot reach the array this instance signs with. The
     * accessor exists because a verifier configured in the same application context needs the identical key,
     * and having it read the key from configuration a second time would create a second parse of the same
     * secret and a second place the decoding could differ.</p>
     *
     * @return an independent copy of the key material, never {@code null}
     */
    public byte[] keyMaterial() {
        return this.key.clone();
    }

    /**
     * Renders this minter for a log line, disclosing no key material.
     *
     * <p>Refactoring Rationale: a default rendering would print the key array. It is replaced rather than
     * omitted so that a diagnostic can still say which subject and lifetime were configured, which is what a
     * reader of a startup log actually needs, while the key is described only by its length.</p>
     *
     * @return a rendering carrying the subject, the lifetime and the key LENGTH, never {@code null}
     */
    @Override
    public String toString() {
        return "InternalServiceToken[subject=" + this.subject
                + ", lifetime=" + this.lifetime
                + ", keyLengthBytes=" + this.key.length + ']';
    }
}
