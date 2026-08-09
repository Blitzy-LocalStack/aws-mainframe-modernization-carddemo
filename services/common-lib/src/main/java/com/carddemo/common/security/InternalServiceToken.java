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
import java.util.Objects;

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
 * <p>Assumptions: the token is signed with a SHARED SYMMETRIC key using HMAC with SHA-256, not with an
 * asymmetric key. The two ends are deployed together from one repository into one environment by one apply, so
 * there is no third party who must verify without being able to mint -- which is the property asymmetric
 * signing buys and the only reason to pay for it. A symmetric key additionally needs no key-distribution
 * endpoint: the verifier holds the same secret the minter does, injected from the same secret store, so a
 * verification involves no network call and cannot fail because a key document was unreachable.</p>
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
     * The scope a token authorising the account-context reads carries.
     *
     * <p>Assumptions: one scope for the whole family of account-context reads rather than one per operation.
     * The three the authorization decision makes are read by a single caller as a single unit -- it needs all
     * three or none -- so separate scopes would be three values always issued and always checked together,
     * which is a distinction with no decision behind it.</p>
     *
     * <p>Refactoring Rationale: this described the scope as covering "the three account-context reads", and
     * the reach outgrew the number when the customer scan and the customer record read were matched on the
     * verifying chain. Those two are not made by the authorization decision, so the count named neither the
     * right size nor the right caller. The reach is now stated as a family and enumerated in exactly one
     * place, {@code InternalApiSecurityConfig.internalPaths()} in the account context -- which is also the
     * reason a per-operation scope is still refused: one scope means adding a route moves that enumeration
     * alone, where per-operation scopes would move the minter and the verifier as well.</p>
     */
    public static final String SCOPE_ACCOUNT_CONTEXT_READ = "internal:account-context.read";

    /**
     * The audience a token minted for the account context carries.
     */
    public static final String AUDIENCE_ACCOUNT_CONTEXT = "carddemo-account-service";

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
     * @param key the shared signing key from the deployment's secret store, at least {@link #MIN_KEY_LENGTH}
     *     bytes; must not be {@code null}
     * @param subject the calling service's own name, carried as the token subject so a callee's log records
     *     which service called it; must not be {@code null} and must not be blank
     * @param clock the clock the issue and expiry instants are read from; must not be {@code null}
     * @param lifetime how long each minted token is valid for; must be positive and at most
     *     {@link #MAX_LIFETIME}
     * @throws NullPointerException if {@code key}, {@code subject} or {@code clock} is {@code null}
     * @throws IllegalArgumentException if the key is shorter than {@link #MIN_KEY_LENGTH} bytes, if the
     *     subject is blank, or if the lifetime is not positive or exceeds {@link #MAX_LIFETIME}
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
        if (subject.isBlank()) {
            throw new IllegalArgumentException(
                    "subject must not be blank: a callee's audit record identifies the caller by it");
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
     * @param scope the family of operations the token authorises, one of the {@code SCOPE_} constants; must not
     *     be {@code null}
     * @return the serialised signed token, ready to be presented as a bearer credential; never {@code null}
     * @throws NullPointerException if {@code audience} or {@code scope} is {@code null}
     * @throws IllegalStateException if the token cannot be signed, which indicates the configured key is
     *     unusable rather than a transient condition, so it is not retryable
     */
    public String mint(String audience, String scope) {
        Objects.requireNonNull(audience, "audience must not be null");
        Objects.requireNonNull(scope, "scope must not be null");

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
        JWSHeader header = new JWSHeader.Builder(SIGNING_ALGORITHM)
                .type(JOSEObjectType.JWT)
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
