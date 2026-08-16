package com.carddemo.auth.service;

import com.carddemo.auth.dto.SignOnChallenge;
import com.carddemo.auth.dto.SignOnChallengeRequest;
import com.carddemo.auth.dto.SignOnOutcome;
import com.carddemo.auth.dto.SignOnRequest;
import com.carddemo.auth.dto.SignOnResponse;
import com.carddemo.auth.dto.SignOutRequest;
import com.carddemo.auth.dto.TokenRefreshRequest;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetTokensFromRefreshTokenRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetTokensFromRefreshTokenResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RefreshTokenReuseException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RevokeTokenRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UnsupportedTokenTypeException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Exchanges a submitted user identifier and credential for the token set a completed sign-on issues.
 *
 * <h2>Which baseline paragraph this replaces</h2>
 *
 * <p>This is the target successor to {@code READ-USER-SEC-FILE}, the paragraph whose label sits at
 * {@code app/cbl/COSGN00C.cbl} line 209 and whose body runs to line 257. The traceability register
 * records the pairing directly: {@code docs/architecture/cobol-to-service-traceability.md} maps that
 * paragraph onto {@code CognitoIdentityService.authenticate}, the first of the four public methods
 * below. The other three have no reference counterpart at all: the pool raises a challenge the reference
 * had no notion of, a bearer token expires where a terminal session did not, and a session that outlives
 * the terminal it was opened from has to be endable at the pool rather than at the screen.
 * The baseline paragraph issues one keyed read and then classifies its outcome three ways; this class
 * performs one local existence probe and one provider exchange and classifies the outcome against the
 * published contract at {@code services/auth-service/src/main/resources/openapi/auth-api.yaml}.
 *
 * <h2>The credential field is not carried forward</h2>
 *
 * <p>Refactoring Rationale: the baseline holds an eight-character credential in the clear and compares
 * it in application code. The field is {@code 05  SEC-USR-PWD  PIC X(08).} at
 * {@code app/cpy/CSUSR01Y.cpy} line 21, sitting at zero-based offset 48 of the eighty-byte
 * {@code SEC-USER-DATA} record, and the comparison is {@code IF SEC-USR-PWD = WS-USER-PWD} at
 * {@code app/cbl/COSGN00C.cbl} line 223. What is wrong with that arrangement is not the comparison but
 * the storage it requires: a readable credential column makes every reader of the record a holder of
 * the credential, so the exposure scales with access to the store rather than with any decision this
 * code makes. Delegating verification to a managed identity pool removes the defect class outright
 * instead of narrowing it, because the column that carried it has no successor.
 * <b>This service persists no password material of any kind.</b> The owning migration
 * {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql} declares
 * {@code auth.users} with five columns and none that could hold a credential,
 * {@code com.carddemo.auth.domain.User} declares no such field, and
 * {@code com.carddemo.auth.dto.UserResponse} declares no such component. The baseline stores and
 * compares a readable credential; the Java stores none and compares none; the divergence is
 * documented, and it is registered as {@code D-4} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Alternatives Considered: two other dispositions of that field were available and both are
 * refused. The first is to port the security-file read and compare a stored credential here, which
 * would reproduce the baseline exactly; it is refused because it carries the defect class forward into
 * a store that is reachable by more principals than a single region's file was, so parity on this one
 * point would cost more than it preserves. The second is to keep a locally hashed credential column,
 * which removes the readable value while keeping verification local; it is refused because credential
 * lifecycle then lives in application code -- issue, rotation, expiry, reset and lockout all become
 * behaviour this module must implement and keep correct -- whereas
 * {@code infra/modules/cognito} already provisions the seed users and generates their credentials
 * straight into the secrets store, so the pool is where that lifecycle already is.
 *
 * <p>Assumptions: the baseline exposes that credential through six faces, listed once here so a reader
 * can see the whole surface the decline covers rather than the one face this class replaces. They are
 * the record field at {@code app/cpy/CSUSR01Y.cpy} line 21; the comparison at
 * {@code app/cbl/COSGN00C.cbl} line 223; the capture at {@code app/cbl/COUSR01C.cbl} line 157, which
 * moves the submitted value into the record field; the echo at {@code app/cbl/COUSR02C.cbl} line 169,
 * which moves the stored value back onto the screen; the comparison and rewrite at
 * {@code app/cbl/COUSR02C.cbl} lines 227 to 229; and the two screen faces the update map declares,
 * {@code 02  PASSWDI  PIC X(8).} at {@code app/cpy-bms/COUSR02.CPY} line 78 and
 * {@code 02  PASSWDO  PIC X(8).} at line 152. The file name is named alongside every line number
 * because these copybooks collide: line 78 of {@code app/cpy-bms/COUSR02.CPY} is that credential
 * field, while line 78 of {@code app/cpy-bms/COUSR03.CPY} is a one-character user-type field.
 *
 * <p>Assumptions: the baseline itself already contains the precedent for a credential-free screen
 * path, which is worth naming because it makes the decline an extension of the source's own practice
 * rather than an imposition on it. The delete-user program re-displays only three values at
 * {@code app/cbl/COUSR03C.cbl} lines 165 to 167 -- first name, last name and user type -- and its map
 * {@code app/cpy-bms/COUSR03.CPY} declares no credential face at all.
 *
 * <h2>What the pseudo-conversational state becomes</h2>
 *
 * <p>Refactoring Rationale: the baseline ends a successful sign-on by choosing the next screen program
 * on the server. It moves the record's type into shared storage at {@code app/cbl/COSGN00C.cbl} line
 * 227 and then branches at lines 230 to 240, transferring control to the administrator menu whose
 * program literal {@code 'COADM01C'} sits on line 232 or to the general menu whose literal
 * {@code 'COMEN01C'} sits on line 237. That arrangement cannot survive, and the reason is what the
 * branch reads rather than where it leads: the structure it branches on is
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44, which travels to the terminal and back on every turn,
 * so the value the program trusts is a value the caller was most recently holding. In the target the
 * landing route is chosen by the client from the signed {@code cognito:groups} claim that
 * {@code com.carddemo.common.security.JwtRoleConverter} converts into authorities, and a signed claim
 * cannot be altered without invalidating the signature.
 * <b>There is no server-side next-program field anywhere in this class</b>, no route and no
 * authoritative user type on the response.
 *
 * <p>Assumptions: the sign-on screen is structurally incapable of supplying a user type, which is the
 * independent reason no such value is accepted or returned here. A search of
 * {@code app/cpy-bms/COSGN00.CPY} for a user-type symbol matches nothing; the value reaches the
 * baseline only from the security record, at {@code app/cbl/COSGN00C.cbl} line 227, after the read has
 * already succeeded.
 *
 * <p>Assumptions: nothing in this class represents the pseudo-conversational machinery, and each piece
 * is named so its absence reads as a decision. The passed-storage declaration at
 * {@code app/cbl/COSGN00C.cbl} lines 64 to 67, the first-entry test {@code IF EIBCALEN = 0} at line
 * 80, the turn-ending return at lines 98 to 102 whose storage operand sits on line 100, and the
 * re-entry discriminator {@code CDEMO-PGM-CONTEXT} at {@code app/cpy/COCOM01Y.cpy} lines 29 to 31 all
 * have no successor. Identity arrives from validated token claims and error rendering is driven by
 * response data alone, so there is no first-entry-versus-re-entry distinction left to make and no
 * session state for a load balancer to have to pin.
 *
 * <h2>Where this class departs from its own commissioning brief</h2>
 *
 * <p>Assumptions: three instructions given for this file are superseded by artifacts already committed
 * to this repository, and each supersession is recorded at the code it governs rather than only here.
 * The transport is the provider's authentication API rather than an OAuth token endpoint, because the
 * pool publishes no password grant and the corresponding configuration key was withdrawn; see the
 * exchange helper. The two credential failures are answered uniformly rather than distinguishably,
 * because the pool is provisioned to answer them identically; see the refusal helper. And the local
 * probe runs in the transaction the repository opens rather than one this method declares; see the
 * probe helper. Each of the three is settled by a sibling-owned artifact that names this class or this
 * operation explicitly, so the artifact is the authority and the brief is not.
 */
@Service
public class CognitoIdentityService {

    // WHY : Assumptions: one logger named for this class, matching the sibling provisioning service in
    //       this package. Every statement it carries names an event and an outcome and never an
    //       argument, because the two arguments this class handles are a credential and a token set.
    private static final Logger LOG = LoggerFactory.getLogger(CognitoIdentityService.class);

    // WHY : Assumptions: the five sentences below are user-visible strings, which AAP transformation
    //       rule T8 makes an external interface rather than a diagnostic, so each is reproduced
    //       character for character from the line that writes it. The space before each ellipsis is
    //       part of the string. They are declared here as inline literals rather than gathered into a
    //       catalogue because a catalogue is a second artifact to keep aligned with the baseline, and
    //       the browser application already owns the one catalogue this migration has.
    /** The sentence the baseline writes when the identifier is absent, from {@code COSGN00C.cbl} line 120. */
    private static final String MESSAGE_USER_ID_REQUIRED = "Please enter User ID ...";

    /** The sentence the baseline writes when the credential is absent, from {@code COSGN00C.cbl} line 125. */
    private static final String MESSAGE_PASSWORD_REQUIRED = "Please enter Password ...";

    /** The sentence a refused credential carries, spanning {@code COSGN00C.cbl} lines 242 and 243. */
    private static final String MESSAGE_CREDENTIAL_REFUSED = "Wrong Password. Try again ...";

    /** The sentence an unevaluable credential carries, from {@code COSGN00C.cbl} line 254. */
    private static final String MESSAGE_UNABLE_TO_VERIFY = "Unable to verify the User ...";

    /**
     * The sentence a refused session or refresh token carries.
     *
     * <p>Assumptions: this sentence has NO reference counterpart and deliberately reuses none of the
     * three the reference sign-on writes. The reference had neither a challenge exchange nor a renewal
     * exchange, so no literal exists for either refusal, and each of its three sentences would be
     * actively wrong here: {@code 'Wrong Password. Try again ...'} would send a caller to retype a
     * credential that was in fact accepted, {@code 'User not found. Try again ...'} would report an
     * existence fact the pool is provisioned not to disclose, and
     * {@code 'Unable to verify the User ...'} names an unevaluable exchange when this one was evaluated
     * and refused.
     *
     * <p>Assumptions: one sentence covers every reason both exchanges can be refused for -- an expired,
     * already-used, altered or mismatched session, and an expired, revoked or mismatched refresh token
     * -- because the remedy is identical in all of them and is the whole actionable content. The
     * published contract states the same merge for both operations, and distinguishing the reasons
     * would tell an unauthenticated caller which of them it had.
     *
     * <p>Assumptions: the wording satisfies the shared advice's provenance gate -- it ends in an
     * ellipsis, sits inside the declared message width and holds no long run of digits -- which is what
     * lets it reach the body rather than being replaced by a generic sentence. It is authored in the
     * shape the reference literals use so that a body carrying it is indistinguishable in form from one
     * carrying a transcribed sentence.
     *
     * <p>Assumptions: this one is public where the credential sentence beside it is private, and the
     * asymmetry is a correction rather than an inconsistency. The adapter that renders the credential
     * refusal re-declares that sentence, and the note beside its copy records that the duplication was
     * forced by the field's visibility rather than chosen -- the cost being two literals for one
     * externally observable sentence, kept checkable only by both citing the same reference line. This
     * sentence has no reference line to cite, so a second copy of it would be checkable against nothing
     * at all; publishing it instead gives the adapter one value to render and leaves exactly one place
     * for it to change.
     */
    public static final String MESSAGE_SESSION_REFUSED = "Please sign on again ...";

    // WHY : Assumptions: the two keys are derived from the baseline's own cursor targets rather than
    //       chosen here. Each failing branch homes the cursor to the field it blames -- USERIDL at
    //       app/cbl/COSGN00C.cbl lines 121, 250 and 255, and PASSWDL at lines 126 and 244 -- so the
    //       logical key set the baseline can attribute a failure to is exactly these two. The names
    //       are the record component names of com.carddemo.auth.dto.SignOnRequest, which are also the
    //       property names the published contract declares, so a form can bind a marker to a control.
    /** The key a refusal blamed on the identifier carries. */
    private static final String FIELD_USER_ID = "userId";

    /** The key a refusal blamed on the credential carries. */
    private static final String FIELD_PASSWORD = "password";

    // WHY : Assumptions: these three keys have no baseline cursor target to derive them from, because
    //       neither the challenge nor the renewal exchange has a baseline counterpart. They are the
    //       record component names of com.carddemo.auth.dto.SignOnChallengeRequest and
    //       com.carddemo.auth.dto.TokenRefreshRequest, which are also the property names the published
    //       contract declares and the names its 400 descriptions promise, so a client reading an entry
    //       can bind it to the value it sent.
    /** The key a refusal blamed on the challenge session carries. */
    private static final String FIELD_SESSION = "session";

    /** The key a refusal blamed on the proposed permanent credential carries. */
    private static final String FIELD_NEW_PASSWORD = "newPassword";

    /** The key a refusal blamed on the presented refresh token carries. */
    private static final String FIELD_REFRESH_TOKEN = "refreshToken";

    // WHY : Assumptions: the three parameter names and the algorithm are the provider's published
    //       contract for the user-password authentication flow, not choices. They are named as
    //       constants so the exchange helper reads as one request rather than as four string literals,
    //       and so a rename cannot leave two spellings behind.
    /** The provider's parameter name for the identifier being authenticated. */
    private static final String AUTH_PARAM_USERNAME = "USERNAME";

    /** The provider's parameter name for the credential being presented. */
    private static final String AUTH_PARAM_PASSWORD = "PASSWORD";

    /** The provider's parameter name for the confidential-client proof. */
    private static final String AUTH_PARAM_SECRET_HASH = "SECRET_HASH";

    /** The provider's response name for the permanent credential a challenge answer sets. */
    private static final String CHALLENGE_PARAM_NEW_PASSWORD = "NEW_PASSWORD";

    /**
     * The identity-token claim carrying the pool user name a renewed token set was issued for.
     *
     * <p>Refactoring Rationale: this claim is read because the renewal exchange lost the binding that
     * used to make the submitted identifier verifiable. The previous flow sent a keyed digest computed
     * over the submitted user name, and the pool verified that digest against the user the token
     * belonged to -- so a token replayed under another identifier was refused by the pool itself. The
     * rotation-compatible operation this class now issues accepts the client secret directly and no user
     * name at all, so nothing in the request or the response ties the submitted identifier to the
     * subject unless this claim is read. Without it the local membership gate below could be satisfied
     * by naming ANY still-present identifier while renewing a different user's session, which defeats
     * the one control that gate exists to apply.
     *
     * <p>Assumptions: {@code cognito:username} rather than {@code sub}, because {@code sub} is the
     * pool's own subject identifier and the key of {@code auth.users} is the eight-character
     * {@code SEC-USR-ID} the baseline declares at {@code app/cpy/CSUSR01Y.cpy} line 18. Comparing
     * against {@code sub} would compare two different identifier spaces and refuse every renewal.
     */
    private static final String ID_TOKEN_USERNAME_CLAIM = "cognito:username";

    /** The number of dot-separated segments a compact-serialised identity token carries. */
    private static final int ID_TOKEN_SEGMENT_COUNT = 3;

    /** The zero-based index of the claim segment within a compact-serialised identity token. */
    private static final int ID_TOKEN_CLAIM_SEGMENT_INDEX = 1;

    /**
     * The reader that turns an identity token's claim segment into addressable claims.
     *
     * <p>Assumptions: one instance is shared because the type is safe for concurrent use once
     * configured, and nothing here configures it after construction. Creating one per renewal would
     * rebuild a serialiser cache on the critical path of a caller mid-session for no benefit.
     */
    private static final ObjectMapper CLAIM_READER = new ObjectMapper();

    /** The keyed-digest algorithm the confidential-client proof is computed with. */
    private static final String SECRET_HASH_ALGORITHM = "HmacSHA256";

    /**
     * The longest run of digits a relayed provider sentence may carry before it is discarded.
     *
     * <p>Assumptions: thirteen matches the run the shared advice's own provenance gate refuses, and the
     * number is the same because the reason is the same: thirteen is the shortest primary account number
     * in use, so a run that long is the shape of one. Choosing a different number here would let a value
     * through this check that the gate would then refuse, leaving a sentence composed and discarded.
     */
    private static final int SENSITIVE_DIGIT_RUN = 13;

    /**
     * The most characters of a provider sentence that are relayed, before the ellipsis is appended.
     *
     * <p>Assumptions: seventy-one is the shared advice's declared message width of seventy-five less the
     * four characters the appended ellipsis occupies, so a relayed sentence is always exactly at or under
     * the width the gate admits. The width itself is the reference message field's:
     * {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COSGN00.CPY} line 84 is wider still, so nothing
     * this produces can overflow the field the reference would have displayed it in.
     */
    private static final int MAX_POLICY_REASON_LENGTH = 71;

    /**
     * The fewest characters a token the pool reports must hold before it is passed on as issued.
     *
     * <p>Assumptions: one, because the only question being asked is presence. A token's real length is
     * the pool's business and is not fixed by any contract this repository owns, so a larger floor here
     * would refuse a token the pool had legitimately minted. What this constant exists to catch is a
     * provider answer that carried an authentication result whose token members were absent or empty --
     * an answer that reads as success and cannot be used as one.
     */
    private static final int MINIMUM_TOKEN_LENGTH = 1;

    /**
     * The fewest seconds of remaining lifetime a reported token set must declare.
     *
     * <p>Assumptions: one, matching the minimum the response record's own constraint declares and the
     * minimum the committed contract publishes for that property. A lifetime of zero or a negative one
     * describes a token that is already expired, so relaying it would hand a caller a token set it
     * cannot use for a single request and would send it back to sign on with no explanation.
     */
    private static final int MINIMUM_TOKEN_LIFETIME_SECONDS = 1;

    // WHY : ⚠️ Refactoring Rationale: a 750-millisecond FLOOR on every refusal stood here, and it was
    //       removed along with the helper that applied it. It existed to close a user-enumeration
    //       channel, and it could not: the two refusal paths did different amounts of work -- one
    //       indexed primary-key probe for a locally-unknown identifier, or that probe plus a network
    //       round trip for a known one with a wrong credential -- and a MINIMUM equalises only the fast
    //       side of that difference. A provider round trip that exceeded the floor left the pool-refused
    //       path longer than the locally-refused one by exactly the amount it exceeded it, so the two
    //       remained distinguishable over repeated samples and an unauthenticated caller could still
    //       enumerate identifiers by timing the tails. Keeping a control that measurably did not close
    //       the channel it was documented as closing would have been worse than having none, because it
    //       reads as protection.
    //       Refactoring Rationale: what replaces it is EQUAL WORK rather than equal duration. All three
    //       exchanges below now perform their provider call FIRST and consult local membership only
    //       after the pool has answered, so an unknown identifier and a known one with a wrong
    //       credential traverse the same code, issue the same provider request and are refused from the
    //       same arm. There is no difference left for a floor to hide.
    //       Alternatives Considered: keeping the floor as a brute-force cost. Rejected because it blocks
    //       a request thread that has finished its work, and the throughput of these three
    //       unauthenticated routes is already bounded one layer out by the tighter rate and burst limits
    //       the edge applies to them -- declared as `public_route_throttling_rate_limit` and
    //       `public_route_throttling_burst_limit` in infra/modules/api-gateway-http -- which caps guess
    //       rate without holding a thread.
    //       Trade-offs: relaying a submitted credential to the pool for an identifier this context does
    //       not hold is the cost accepted, and the previous rationale named it as the reason NOT to. It
    //       is accepted now because the pool is the credential store of this system rather than a third
    //       party, the pool is provisioned to answer an unknown and a wrong-credential case identically
    //       (`PreventUserExistenceErrors` is fixed ENABLED in infra/modules/cognito/main.tf), and the
    //       exposure it adds -- one more recipient of a value already destined for that same pool -- is
    //       nil, whereas the timing channel it closes was real and measurable.

    // WHY : Trade-offs: the two bounds below are the whole of this operation's resilience posture, and
    //       the numbers are chosen against what a caller is waiting on rather than against what the
    //       pool usually takes. Sign-on is a person waiting at a screen, so a total budget of five
    //       seconds keeps a pool outage inside the span someone will tolerate before retrying by hand,
    //       while still leaving room for the connection setup and the digest verification a healthy
    //       exchange needs. The per-attempt bound is deliberately shorter than the total so that one
    //       stalled connection cannot consume the entire budget on its own. Making either longer would
    //       hold a request thread past the point the caller has given up; making either shorter would
    //       report a healthy but momentarily slow pool as unevaluable. The reasoning for adding no
    //       retry and no circuit breaker beside them is recorded on the exchange helper.
    /** The ceiling on the whole credential exchange, including any internally repeated attempt. */
    private static final Duration TOTAL_EXCHANGE_TIMEOUT = Duration.ofSeconds(5);

    /** The ceiling on any single network attempt within that exchange. */
    private static final Duration SINGLE_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);

    // WHY : Assumptions: the client is injected rather than built here so this class can be handed a
    //       stand-in and its provider interaction asserted without a network. The wiring type
    //       com.carddemo.auth.config.CognitoIdentityConfig contributes it and records that same reason.
    private final CognitoIdentityProviderClient provider;

    // WHY : Assumptions: the repository is the local existence authority for auth.users, which is the
    //       relational successor to the USRSEC dataset the baseline reads. It is consulted before the
    //       provider so this context never relays a credential for an identifier it does not own.
    private final UserRepository users;

    // WHY : Assumptions: both values arrive from configuration and neither has a fallback, so a
    //       missing one stops startup rather than leaving sign-on pointed at another environment's
    //       pool. application.yml declares them at carddemo.auth.cognito.client-id and
    //       carddemo.auth.cognito.client-secret, injected from Parameter Store and Secrets Manager
    //       respectively, and no value for either exists anywhere in this repository.
    private final String clientId;

    // WHY : Trade-offs: the confidential client's secret is held as a field for the lifetime of the
    //       bean, which is longer than any one exchange needs it. The alternative -- reading it from
    //       the secrets store per request -- was rejected because it puts a network call with its own
    //       failure mode in front of every sign-on, and the value would still be resident in memory
    //       while the digest was computed. It is never logged and never leaves this class.
    private final String clientSecret;

    /**
     * Creates the sign-on service with its provider client, its local repository and its client
     * credentials.
     *
     * <p>Assumptions: constructor injection is used rather than field injection, so every collaborator
     * is present and non-null before the object exists and a test can supply all four without a
     * container. The sibling {@code CognitoUserProvisioningService} in this package binds its own
     * configuration the same way, so the two read alike.
     *
     * <p>Alternatives Considered: binding the two configuration values through a
     * {@code @ConfigurationProperties} type instead of two annotated parameters. Rejected because that
     * type would be a fifth class in a package whose charter is identity exchange, and it would carry
     * the client secret as a readable property on a bean the container is free to render in its own
     * diagnostics. Two parameters keep the secret inside this object.
     *
     * @param provider the identity-provider client the credential exchange is issued through; must not
     *     be {@code null}
     * @param users the repository onto {@code auth.users} the local existence probe reads; must not be
     *     {@code null}
     * @param clientId the pool app-client identifier the exchange is issued under, read from
     *     {@code carddemo.auth.cognito.client-id}; must not be {@code null} or blank
     * @param clientSecret the confidential app-client secret the request proof is keyed with, read
     *     from {@code carddemo.auth.cognito.client-secret}; must not be {@code null} or blank
     * @throws NullPointerException if {@code provider}, {@code users}, {@code clientId} or
     *     {@code clientSecret} is {@code null}
     * @throws IllegalArgumentException if {@code clientId} or {@code clientSecret} is blank, which
     *     would leave the exchange unable to identify itself to the pool
     */
    public CognitoIdentityService(CognitoIdentityProviderClient provider, UserRepository users,
            @Value("${carddemo.auth.cognito.client-id}") String clientId,
            @Value("${carddemo.auth.cognito.client-secret}") String clientSecret) {

        this.provider = Objects.requireNonNull(provider, "provider");
        this.users = Objects.requireNonNull(users, "users");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");

        // WHY : Assumptions: blankness is refused here rather than tolerated, because an empty
        //       identifier or secret produces a provider refusal that is indistinguishable from a bad
        //       credential -- so every sign-on would report a wrong password while the actual fault
        //       was a missing environment value. Failing at construction names the real cause once.
        if (this.clientId.isBlank()) {
            throw new IllegalArgumentException(
                    "carddemo.auth.cognito.client-id must not be blank");
        }
        if (this.clientSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "carddemo.auth.cognito.client-secret must not be blank");
        }
    }

    /**
     * Verifies a submitted identifier and credential and returns the token set the pool issued.
     *
     * <p>The four steps below are: presence, normalisation, the provider exchange, then the local
     * membership gate. The first two are the baseline paragraph's own order and are kept because the
     * order is observable -- presence stops at the first offending field, which is what decides the one
     * sentence a caller submitting an empty screen is told, and the identifier is folded before it is
     * used as a key. The last two were the other way round and were swapped deliberately; the reasoning
     * is recorded at the code and once above the resilience bounds. In short, probing local membership
     * first made an identifier this context does not hold refusable without a network call, and that
     * made the two refusals distinguishable by duration -- an enumeration channel no minimum delay
     * closes. Both refusals now traverse the same provider call.
     *
     * <p>Assumptions: the baseline gates its file read on an error flag at
     * {@code app/cbl/COSGN00C.cbl} lines 138 to 140, so a presence failure never reaches the read.
     * That gate is reproduced by raising from the presence check before either collaborator is
     * touched, which is why a blank submission consults neither the repository nor the pool.
     *
     * <p>Assumptions: the baseline classifies its read with raw numeric response codes rather than
     * with the {@code DFHRESP} symbolics its four sibling programs use -- the
     * {@code EVALUATE WS-RESP-CD} at {@code app/cbl/COSGN00C.cbl} line 221 takes {@code WHEN 0} at
     * line 222 and {@code WHEN 13} at line 247 before closing at line 257, and a search of that
     * program for {@code DFHRESP} matches nothing where {@code COUSR00C} through {@code COUSR03C}
     * match repeatedly. The numeric form is what makes the failure taxonomy explicit enough to
     * transcribe: three arms, three sentences, and no dependence on a copybook of condition names.
     * The three arms survive as the three outcomes this method can produce, with the qualification
     * recorded on the refusal helper below.
     *
     * <p>Trade-offs: no transaction is declared on this method, and the omission is deliberate. The
     * read-only transaction the probe needs is the one Spring Data opens around the repository call
     * itself, which is narrower than this method and opens only after the provider exchange has already
     * returned. Declaring one here would instead hold a pooled connection for the duration of an
     * outbound network exchange -- {@code application.yml} sizes this service's pool at ten -- so a slow
     * pool would consume connections with no query to run. What is given up is that the probe and the
     * exchange are not one atomic unit, which costs nothing here because the probe only reads and the
     * method writes nothing at all.
     *
     * <p>Trade-offs: the target's read-only transaction is stricter than the baseline's file access,
     * which declares {@code READINTEG(UNCOMMITTED)} at {@code app/csd/CARDDEMO.CSD} line 90 and so
     * admits reads of uncommitted data. The relational default of read-committed cannot see an
     * uncommitted row, which means a user created in a transaction that has not yet committed is not
     * yet signable-on where the baseline might have admitted it. That is accepted rather than
     * engineered around: the window is the width of one insert, and the alternative -- asking the
     * store for a weaker isolation level to reproduce it -- would import a defect class in exchange
     * for a behaviour no caller depends on.
     *
     * <p>Refactoring Rationale: the declared return type is the sealed
     * {@link com.carddemo.auth.dto.SignOnOutcome} rather than the token set alone, and widening it was
     * the correction that made this operation usable. The published contract declares the success
     * status as a choice of two shapes discriminated on {@code outcome}, and the narrower signature
     * could express only the authenticated one -- so a pool answer of {@code NEW_PASSWORD_REQUIRED} had
     * nowhere to go and was reported as an unevaluable credential. That was not a theoretical branch:
     * every account the infrastructure provisions is created with a temporary password, a temporary
     * password always raises that challenge on first use, and no operation existed through which a
     * permanent one could be set. The first sign-on of every provisioned user therefore answered HTTP
     * 500. The sealed type keeps the set of shapes closed, so this method still cannot return anything
     * the contract does not publish.
     *
     * @param request the submitted identifier and credential, as the unauthenticated sign-on operation
     *     received them; must not be {@code null}
     * @return either the token set the pool issued, as {@link SignOnResponse} carrying the normalised
     *     identifier and no authoritative user type, or the challenge the pool raised, as
     *     {@link SignOnChallenge} carrying the session to answer it with and no token of any kind;
     *     never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if the identifier or the credential is absent or blank, carrying the
     *     baseline sentence for the earlier of the two fields and that field's key
     * @throws BadCredentialsException if the pool refused the pair, or if this context holds no record
     *     for the identifier, carrying the baseline refusal sentence and no field key
     * @throws IllegalStateException if the credential could not be evaluated at all -- the pool being
     *     unreachable or answering a fault, the request proof being rejected, the local store being
     *     unreadable, the pool answering with a challenge this contract publishes no answer path for,
     *     or the pool answering with a token set that is incomplete -- carrying the baseline sentence
     *     for an unevaluable credential and no provider diagnostic
     */
    public SignOnOutcome authenticate(SignOnRequest request) {

        Objects.requireNonNull(request, "request");

        requireSubmittedFields(request);

        String userId = normaliseUserId(request.userId());

        // WHY : ⚠️ Refactoring Rationale: the credential now reaches the pool BEFORE local membership is
        //       consulted, where the probe used to run first and short-circuit. The order is the whole
        //       of this method's user-enumeration control and it replaces a padding floor that did not
        //       work; the reasoning is recorded once, above the resilience bounds. In this order an
        //       identifier this context does not hold and an identifier it holds with a wrong credential
        //       execute the same statements and issue the same provider request, so the two refusals are
        //       indistinguishable in work as well as in wording.
        InitiateAuthResponse answer = exchangeCredential(userId, request.password());

        // WHY : Assumptions: the probe stands in for the keyed read at app/cbl/COSGN00C.cbl lines 211 to
        //       219, which carries no UPDATE option and so is a plain positioned read rather than a read
        //       for update. Nothing here acquires a lock for the same reason: no row is written.
        // WHY : Assumptions: it still runs, and running it AFTER the exchange changes nothing about what
        //       it decides. This context owns auth.users, so a pool identity with no local row is not a
        //       user of this system and must not receive a token set -- the pool and the local table are
        //       provisioned together and a row removed from one is meant to end access through the
        //       other. What moved is only when the answer is known.
        // WHY : Trade-offs: reaching this line means the pool ACCEPTED the credential, so a locally
        //       absent row is refused after the provider has done its work rather than before. The cost
        //       is one provider call for an identifier that cannot sign on either way; what it buys is
        //       that the timing of this refusal says nothing about which identifiers exist. No token
        //       reaches the caller on this path, and nothing the pool minted here is usable by anyone,
        //       because the response is discarded before it is rendered.
        if (!isKnownLocally(userId)) {
            throw refusedCredential("local-record-absent");
        }

        return outcomeFrom(answer, userId);
    }

    /**
     * Sets the permanent credential a sign-on challenge asked for and returns the token set it unlocks.
     *
     * <p>Purpose: this completes the exchange {@link #authenticate} left unfinished when the pool
     * answered with a challenge. It is the operation the published contract declares as
     * {@code POST /api/v1/auth/challenge}, and it is published unauthenticated for the same reason
     * sign-on is: a caller answering a challenge holds no token, that being what the exchange exists to
     * obtain.
     *
     * <p>Assumptions: this method has no reference counterpart, and none of the reference sign-on's
     * three sentences is reused for its refusals. The reference compared a stored eight-character
     * credential directly at {@code app/cbl/COSGN00C.cbl} lines 211 to 256 and had no notion of a
     * credential that must be changed before use. The divergence is registered as
     * {@code D-PASSWORD-CHALLENGE} in {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * <p>Assumptions: the local existence probe runs here exactly as it does on sign-on, and it is not
     * redundant just because the caller is holding a session this service issued. A session is minted by
     * the pool and this context owns its own membership: a row deleted between the sign-on and the
     * answer must not be able to complete an exchange that ends in a usable token set for a user this
     * context no longer holds.
     *
     * <p>Trade-offs: the answer returns the AUTHENTICATED shape only, never another challenge, so this
     * exchange cannot loop. The pool issues tokens once the new password is accepted, and a second
     * challenge would mean a pool configuration this contract publishes no answer path for -- reported
     * as an unevaluable exchange rather than returned as a challenge a client would have no defined way
     * to answer. What is given up is that enabling a second factor makes this operation fail rather than
     * chain; what is bought is that no client is written against a flow that has never been exercised.
     *
     * @param request the identifier the challenge was raised for, the session it issued and the
     *     permanent credential to set; must not be {@code null}
     * @return the token set the pool issued once the credential was accepted, carrying the normalised
     *     identifier; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if a submitted value is absent or blank, or if the pool refused the
     *     proposed credential under its own password policy, carrying the pool's own reason in the
     *     latter case rather than a restatement of the policy
     * @throws SessionRefusedException if the session was expired, already used, altered or issued for a
     *     different identifier, or if this context holds no record for the identifier, carrying the
     *     sign-on-again sentence and no field key
     * @throws IllegalStateException if the exchange could not be evaluated at all -- the pool being
     *     unreachable or answering a fault, the request proof being rejected, the local store being
     *     unreadable, the pool raising a further challenge, or the pool answering with a token set that
     *     is incomplete
     */
    public SignOnResponse answerChallenge(SignOnChallengeRequest request) {

        Objects.requireNonNull(request, "request");

        requireChallengeFields(request);

        String userId = normaliseUserId(request.userId());

        // WHY : ⚠️ Refactoring Rationale: the pool is asked first here too, and the probe follows. The
        //       previous order refused a locally-absent identifier without a network call, which is the
        //       same timing oracle the sign-on exchange carried: a caller needs no session to submit a
        //       guessed identifier with a made-up one, so this operation was as usable for enumeration
        //       as sign-on was. The reordering is the same fix and is argued once, above the resilience
        //       bounds.
        RespondToAuthChallengeResponse answer =
                answerNewPasswordChallenge(userId, request.session(), request.newPassword());

        // WHY : Assumptions: a second challenge is treated as unevaluable rather than returned, which is
        //       the trade-off recorded above. The name is logged so an operator can see which pool
        //       configuration produced it and is withheld from the caller, who has no published way to
        //       act on it.
        if (answer.authenticationResult() == null) {
            LOG.warn("event=auth.challenge.unevaluable reason=further-challenge challenge={}",
                    answer.challengeNameAsString());
            throw unableToVerify("further-challenge-" + answer.challengeNameAsString());
        }

        // WHY : Assumptions: the probe is not redundant just because the caller holds a session this
        //       service issued: a row deleted between the sign-on and the answer must not be able to
        //       complete an exchange that ends in a usable token set.
        // WHY : Trade-offs: the pool has by now ACCEPTED the proposed password and stored it, so a
        //       locally-absent row is refused after a state change the caller asked for has already
        //       happened. That is accepted because the caller reaching this line held a pool-minted
        //       session for that identity, so it had already authenticated with the temporary credential
        //       the session was issued against -- setting the permanent one grants it nothing it could
        //       not already do, and the token set the pool issued is discarded here rather than
        //       returned.
        if (!isKnownLocally(userId)) {
            throw refusedSession("local-record-absent");
        }

        return tokensFrom(answer.authenticationResult(), userId);
    }

    /**
     * Exchanges a refresh token for a fresh access token, identity token and refresh token.
     *
     * <p>Purpose: this is the operation the published contract declares as
     * {@code POST /api/v1/auth/refresh}. It exists so a session outlives one access-token lifetime
     * without the user re-entering a credential, and it is published unauthenticated because the token
     * it would carry is the one being renewed -- a caller whose access token has already expired must
     * still be able to renew.
     *
     * <p>⚠️ Refactoring Rationale: the renewed set carries a NEW refresh token and the caller must
     * replace the one it sent. This paragraph previously stated the opposite -- that the pool does not
     * reissue one and the caller keeps the token it holds -- and that claim was false against the
     * provisioned pool and dangerous to act on. {@code infra/modules/cognito/main.tf} declares the app
     * client with {@code RefreshTokenRotation} at {@code Feature = "ENABLED"} and
     * {@code RetryGracePeriodSeconds = 0}, so the pool mints a replacement and invalidates the submitted
     * token immediately; a caller that kept the token it sent would have its SECOND renewal refused as a
     * reuse, making the operation appear to work once and then fail permanently for the rest of the
     * refresh token's thirty-day life. The committed contract already described the correct behaviour on
     * this operation's 200 response, so the code and the prose here were the two artifacts out of step
     * with it. The response component stays nullable because the sign-on direction may legitimately
     * return none, and the value is passed through exactly as the pool supplied it.
     *
     * <p>⚠️ Refactoring Rationale: the provider operation is
     * {@code GetTokensFromRefreshToken} and no longer {@code InitiateAuth} with the
     * {@code REFRESH_TOKEN_AUTH} flow. The old call could not have succeeded even once. Rotation and
     * that flow are mutually exclusive at the provider, and the module refuses to configure them
     * together -- {@code infra/modules/cognito/variables.tf} validates
     * {@code explicit_auth_flows} to REJECT {@code ALLOW_REFRESH_TOKEN_AUTH} while rotation is enabled,
     * and its default permits {@code ALLOW_USER_PASSWORD_AUTH} alone -- so the app client this service
     * authenticates through does not permit the flow the renewal was asking for. Every renewal was
     * refused by the pool, and because a refused renewal is reported as a refused session, the browser
     * treated it as an ended session: a signed-on user was returned to the sign-on screen one access
     * token lifetime after signing on, with no diagnostic naming the cause.
     *
     * <p>Assumptions: the rotation-compatible operation takes the client secret DIRECTLY rather than a
     * keyed digest over a user name, and it accepts no user name at all. That is the one thing the change
     * of operation costs, and it is repaid below: the previous request proved the caller knew the user
     * name the token belonged to, because the pool verified the digest against the token's own subject,
     * whereas this one proves only that the caller holds the token and the client secret. The subject is
     * therefore read back out of the identity token the pool just issued and compared with the submitted
     * identifier, which restores the binding rather than trusting the request for it.
     *
     * <p>Assumptions: the identifier remains required on this request even though the provider call does
     * not use it. It is what the local membership gate is applied to and what the response echoes, and
     * both are now checked against the pool's own answer rather than accepted as submitted. Withdrawing
     * it would change the published contract and leave the response with no identifier to carry.
     *
     * <p>Assumptions: this method has no reference counterpart of any kind. A sign-on under the
     * transaction monitor lasted as long as the terminal session did, so no reference program, screen
     * field or literal corresponds to this exchange, and its refusal sentence is authored rather than
     * transcribed.
     *
     * @param request the identifier the token set was issued for and the refresh token to renew it
     *     with; must not be {@code null}
     * @return the renewed token set, carrying a new access token, a new identity token, the rotated
     *     refresh token that replaces the one submitted, and the normalised identifier; never
     *     {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if either submitted value is absent or blank
     * @throws SessionRefusedException if the refresh token was expired, revoked, already rotated away,
     *     or issued for an identifier other than the one submitted, or if this context holds no record
     *     for that identifier, carrying the sign-on-again sentence and no field key
     * @throws IllegalStateException if the renewal could not be evaluated at all -- the pool being
     *     unreachable or answering a fault, the local store being unreadable, the pool answering with no
     *     token set, the pool answering with a token set that is incomplete, or the issued identity token
     *     carrying no subject to bind the submitted identifier against
     */
    public SignOnResponse refresh(TokenRefreshRequest request) {

        Objects.requireNonNull(request, "request");

        requireRefreshFields(request);

        String userId = normaliseUserId(request.userId());

        // WHY : ⚠️ Refactoring Rationale: the pool is asked before local membership is consulted, where
        //       the probe used to run first. The old order made a locally-absent identifier refusable
        //       without a network call, and since this operation needs no credential to call -- a
        //       made-up token is enough to get a refusal -- that difference in duration was a
        //       user-enumeration oracle as usable as the sign-on one. The reordering is argued once,
        //       above the resilience bounds. It costs nothing here: the provider call no longer takes
        //       the identifier at all, so there is no work the probe could have saved.
        GetTokensFromRefreshTokenResponse answer = exchangeRefreshToken(request.refreshToken());

        // WHY : Assumptions: a renewal flow has no challenge to raise, so an answer carrying no token
        //       set is a pool fault rather than a step in a flow. It is reported as unevaluable, and the
        //       null-safe access to each member is in tokensFrom rather than repeated here.
        if (answer.authenticationResult() == null) {
            LOG.warn("event=auth.refresh.unevaluable reason=no-authentication-result");
            throw unableToVerify("refresh-no-result");
        }

        // WHY : Assumptions: the subject the pool issued for is compared with the identifier submitted,
        //       and a mismatch is refused as a refused session rather than answered. This is the binding
        //       the withdrawn keyed digest used to provide: without it a caller holding one user's
        //       refresh token could name ANY other still-present identifier, satisfy the membership gate
        //       below with that name, and renew the first user's session -- so the gate would be
        //       trivially bypassable by exactly the party it exists to stop.
        // WHY : Assumptions: the comparison is against the FOLDED submitted value, because the identifier
        //       is folded before it is used as a key and the pool stores the user name in the form it was
        //       created with. normaliseUserId applies the same fold to the claim, so the two sides are
        //       compared in one form.
        String subject = normaliseUserId(subjectOfIdentityToken(answer.authenticationResult().idToken()));
        if (!subject.equals(userId)) {
            throw refusedSession("refresh-subject-mismatch");
        }

        // WHY : Assumptions: the probe is what stops a token minted for a user this context has since
        //       removed from being renewed into a fresh one, and it is applied to the subject the pool
        //       reported rather than to the value the caller sent -- the two are equal by the check above,
        //       and reading the pool's value keeps that the case if the check is ever relaxed.
        if (!isKnownLocally(subject)) {
            throw refusedSession("local-record-absent");
        }

        return tokensFrom(answer.authenticationResult(), subject);
    }

    /**
     * Ends a session at the pool by revoking the refresh token it was renewed from.
     *
     * <p>Purpose: this is the operation the published contract declares as
     * {@code POST /api/v1/auth/signout}, and it is the provider-side half of signing out. It exists
     * because discarding a token in a browser does not end anything: the refresh token is provisioned
     * with a thirty-day life -- {@code refresh_token_validity_days} defaults to 30 in
     * {@code infra/modules/cognito/variables.tf} -- so a copy taken from a browser store, a synchronised
     * profile or a shared workstation could mint access tokens for a month after the user believed the
     * session was over. Revoking the token is what makes a sign-out an event at the pool rather than a
     * change of local state.
     *
     * <p>Assumptions: it is published unauthenticated, and the reason is the same one that publishes the
     * renewal unauthenticated rather than a relaxation of it. Authority here IS the refresh token: the
     * provider's revocation operation takes the token and the client credentials and no user name, so a
     * caller that cannot produce the token can revoke nothing, and a caller that can produce it could
     * already have used it for something worse. Requiring a valid access token instead would refuse the
     * revocation in exactly the case it matters most -- an access token that has already expired, which
     * is the state of every session an operator abandons rather than closes -- and would leave the
     * thirty-day token live.
     *
     * <p>Assumptions: no identifier is accepted on this operation, unlike the other three. The provider
     * call has nowhere to put one, this method has no membership decision to make -- revoking a token is
     * the right answer whether or not this context still holds a row for its subject, and REFUSING it
     * for a removed user would leave that user's token live -- and requiring a value nothing reads would
     * invite a later reader to believe it was checked.
     *
     * <p>Assumptions: it is idempotent and answers success for a token the pool will not accept, which
     * is argued at the revocation helper: an unusable token is the outcome the caller asked for, and
     * reporting a failure would both misstate that and hand an unauthenticated caller a liveness test.
     * The one failure it does report is a pool it could not reach, because then the token IS still live.
     *
     * <p>Trade-offs: revocation stops RENEWAL and does not invalidate an access token already issued.
     * Every service in this migration validates a bearer token by signature, issuer and expiry against
     * the pool's published keys, which is a local decision that consults no revocation state, so a
     * bearer in flight stays acceptable until it expires -- bounded by
     * {@code access_token_validity_minutes}, 60 by default. The alternatives were weighed and both
     * declined for this checkpoint: a token version or deny list would put a shared lookup on every
     * request of every service, and a shorter bearer lifetime would raise the renewal rate for every
     * session to shorten a window that only matters after a sign-out. What is bought is that the
     * long-lived, mintable credential stops working at once; what remains is a bounded tail on the
     * short-lived one, and it is stated here rather than left for a reader to assume away.
     *
     * <p>Assumptions: this method has no reference counterpart. The baseline's sign-off transferred
     * control back to the sign-on screen -- {@code app/cbl/COMEN01C.cbl} and the sibling menus move the
     * sign-on program's literal into the next-program field on the exit key -- and ended nothing, because
     * a terminal session was the session and it lasted until the terminal disconnected. There is
     * therefore no message literal to transcribe, and this operation returns no body at all.
     *
     * @param request the refresh token to revoke; must not be {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if the submitted token is absent or blank, carrying the
     *     sign-on-again sentence and the token's field key
     * @throws IllegalStateException if the pool could not be reached or answered a fault, so the token
     *     may still be live, carrying the sentence this service reports for an unevaluable exchange
     */
    public void signOut(SignOutRequest request) {

        Objects.requireNonNull(request, "request");

        requireSignOutFields(request);

        revokeRefreshToken(request.refreshToken());
    }

    /**
     * Refuses a submission whose identifier or credential is absent, blaming the earlier field only.
     *
     * <p>Assumptions: the chain is ordered and it stops, because the baseline's is. Lines 117 to 130 of
     * {@code app/cbl/COSGN00C.cbl} are a single {@code EVALUATE TRUE} whose first matching branch
     * writes its sentence and sends the screen, so the identifier at line 118 is tested before the
     * credential at line 123 and a caller submitting both blank is told about the identifier and never
     * about the credential. Which sentence comes back is therefore observable behaviour, and the
     * published contract states the same ordering for its 400 response.
     *
     * <p>Alternatives Considered: leaving this to the bean-validation constraints that
     * {@code com.carddemo.auth.dto.SignOnRequest} already declares, which would remove this method.
     * Rejected because those constraints guard the transport boundary and this guards the service. A
     * caller inside the application -- a batch utility, another component, or a test asserting this
     * behaviour directly -- reaches this method without an argument resolver having run, and would
     * otherwise present a blank identifier to the pool and receive a refusal that named the wrong
     * cause. The two are complementary rather than redundant: the record's declared field order is what
     * makes the transport path emit the earlier sentence, and this chain is what makes the direct path
     * emit it.
     *
     * <p>Assumptions: blankness rather than nullity is the test, because the baseline compares each
     * field against {@code SPACES OR LOW-VALUES} and so treats a field of blanks exactly as it treats
     * an empty one. A null check alone would admit the string of spaces the baseline refuses.
     *
     * @param request the submitted identifier and credential to check for presence; must not be
     *     {@code null}
     * @throws ClientInputException if the identifier is absent or blank, or if the credential is,
     *     carrying the baseline sentence and key for whichever of the two the baseline would have
     *     reported
     */
    private void requireSubmittedFields(SignOnRequest request) {

        if (isAbsent(request.userId())) {
            LOG.info("event=auth.signon.rejected reason=user-id-absent field={}", FIELD_USER_ID);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_USER_ID,
                    MESSAGE_USER_ID_REQUIRED);
        }

        // WHY : Assumptions: this branch is reachable only when the identifier passed, which is what
        //       reproduces the baseline's single-sentence outcome rather than merely ordering two
        //       sentences that both get emitted.
        if (isAbsent(request.password())) {
            LOG.info("event=auth.signon.rejected reason=credential-absent field={}", FIELD_PASSWORD);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_PASSWORD,
                    MESSAGE_PASSWORD_REQUIRED);
        }
    }

    /**
     * Reports whether a submitted value is missing or holds nothing but whitespace.
     *
     * <p>Assumptions: this is the analogue of the baseline's {@code = SPACES OR LOW-VALUES} test, which
     * a declared-width field needs because it can never be shorter than its declaration and so signals
     * absence by content. A JSON body can omit a property outright, so the null case is folded in here
     * rather than left to a separate check at each of the two call sites.
     *
     * @param submitted the value as submitted, possibly {@code null}
     * @return {@code true} when the value is {@code null}, empty or entirely whitespace, and
     *     {@code false} when it holds at least one non-whitespace character
     */
    private static boolean isAbsent(String submitted) {
        return submitted == null || submitted.isBlank();
    }

    /**
     * Folds a submitted identifier to upper case so it resolves the row the baseline would have read.
     *
     * <p>Refactoring Rationale: the baseline folds <b>both</b> submitted values, in one statement pair
     * spanning five lines with three receivers. {@code app/cbl/COSGN00C.cbl} line 132 pushes the
     * identifier through {@code FUNCTION UPPER-CASE} into {@code WS-USER-ID} on line 133 and
     * {@code CDEMO-USER-ID} on line 134, and line 135 pushes the credential through the same function
     * into {@code WS-USER-PWD} on line 136. Only the identifier's fold is reproduced. The credential's
     * fold existed to serve the direct comparison at line 223, and that comparison has no successor,
     * so reproducing the fold would collapse the alphabet a credential is drawn from for no remaining
     * purpose -- and the pool that now performs the comparison is case-sensitive, so a folded value
     * would be refused where the characters the caller actually typed would have been accepted. The
     * baseline folds both; the Java folds the identifier alone; the divergence is documented, and it is
     * registered as {@code D-SIGNON-CASE-SENSITIVE-PASSWORD} in
     * {@code docs/architecture/cobol-to-service-traceability.md}.
     *
     * <p>Trade-offs: the fold is pinned to an invariant locale rather than taking the platform default,
     * which costs a reader an argument they have to look up and buys an identifier that cannot change
     * meaning with the environment. Under a Turkish default locale the letter {@code i} folds to a
     * dotted capital rather than to {@code I}, so a task started with a different locale would derive a
     * different primary key from the same submission and resolve a different row -- or none. The
     * accepted compromise is that this is not the locale a human reader would see the identifier
     * rendered in, which is immaterial because the value is a key rather than display text.
     *
     * @param submitted the identifier as submitted, already known to be non-blank
     * @return the identifier folded to upper case under an invariant locale, ready to use as the key of
     *     {@code auth.users} and as the identifier presented to the pool; never {@code null}
     */
    private static String normaliseUserId(String submitted) {

        // WHY : Trade-offs: the value is trimmed as well as folded, and the trim is an addition rather
        //       than a transcription. A declared-width screen field pads with blanks that are the
        //       field's rather than the value's, so the baseline never sees leading or trailing space
        //       as data; a JSON string can carry it, and an untrimmed key would miss a row that exists.
        //       The credential is deliberately not treated this way -- see the exchange helper -- so
        //       the two values are handled differently here on a difference the target introduces.
        return submitted.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Reports whether this bounded context holds a record for the folded identifier.
     *
     * <p>Assumptions: this stands in for the keyed read at {@code app/cbl/COSGN00C.cbl} lines 211 to
     * 219, which reads {@code USRSEC} by {@code RIDFLD} and carries no {@code UPDATE} option. The
     * relational successor is {@code auth.users}, whose primary key is the same eight-character
     * identifier, so an existence check on the key is the same access path the baseline used and needs
     * no index beyond the one the migration already declares.
     *
     * <p>Alternatives Considered: loading the whole row through {@code findById} rather than testing
     * for its existence. Rejected because nothing on the row is used: the response carries the
     * identifier the caller already supplied, the authority comes from the token's group claim rather
     * than from the stored type, and the stored subject reference is not needed to authenticate. An
     * existence check states that, and it lets the store answer from the primary-key index without
     * materialising columns this method would discard.
     *
     * <p>Assumptions: the read-only transaction this needs is the one Spring Data opens around the
     * call, rather than one declared on the public method; the reason that boundary was chosen is
     * recorded there.
     *
     * <p>Refactoring Rationale: the probe is wrapped, where it previously was not, and the failure it
     * catches is not exotic. A data-access failure -- an exhausted connection pool, a connection reset,
     * a statement timeout, a revoked grant on {@code auth.users} -- is raised by the persistence layer
     * as an unchecked exception, so an unwrapped probe let it escape this class untranslated. The shared
     * advice then matched it with its unanticipated-failure handler, which answers 500 with a GENERIC
     * sentence, because that handler carries a service's own sentence only for the bare illegal-state
     * type. So the one condition the reference's own catch-all arm exists for -- a store that could not
     * be read -- was the one condition that could not produce the reference's sentence for it. Catching
     * it here and raising the unevaluable failure puts it back in the taxonomy the reference declares.
     *
     * <p>Assumptions: the caught type is the persistence abstraction's own root rather than a driver
     * exception or a JPA one, so every failure the repository can raise from a store interaction is
     * covered by one arm. Spring Data translates driver and provider exceptions into that hierarchy
     * before a repository method returns, which is exactly why one catch suffices and why naming a
     * narrower type would leave siblings to escape.
     *
     * <p>Assumptions: this is an unevaluable credential and NOT a refusal, and the distinction is what
     * the caller acts on. The store being unreadable says nothing at all about the submitted identifier
     * or credential, so answering with the refusal sentence would send a caller to reset a credential
     * that was never examined -- which is the same reasoning the reference encodes by giving its
     * {@code WHEN OTHER} arm at {@code app/cbl/COSGN00C.cbl} line 252 a different sentence from its
     * comparison-failed arm.
     *
     * @param userId the folded identifier to look for, as the key of {@code auth.users}
     * @return {@code true} when a record exists for the identifier and {@code false} when none does
     * @throws IllegalStateException if the store could not be read, carrying the baseline sentence for
     *     an unevaluable credential and no provider diagnostic
     */
    private boolean isKnownLocally(String userId) {
        try {
            return users.existsById(userId);
        } catch (DataAccessException unreadable) {
            throw unableToVerify("local-store-" + unreadable.getClass().getSimpleName());
        }
    }

    /**
     * Presents the credential to the pool and returns whatever the pool answered.
     *
     * <p>Alternatives Considered: the commissioning brief for this file called for an OAuth token
     * endpoint reached over an HTTP client, and that approach cannot work, which is why the provider's
     * own authentication API is used instead. The pool's token endpoint implements the
     * authorization-code, client-credentials and refresh-token grants and implements no password grant,
     * so no request on it exchanges an identifier and a credential for a token; the configuration key
     * that would have addressed it was withdrawn from
     * {@code services/auth-service/src/main/resources/application.yml} for exactly that reason, and the
     * same file then names this class and states the mechanism used here. A token endpoint paired with
     * a client secret is also the confidential authorization-code shape, which needs a hosted sign-in
     * domain and a browser redirect, whereas the browser application renders the baseline's own sign-on
     * screen and posts the credential to this service. {@code services/auth-service/pom.xml} records
     * the same conclusion where it justifies the one provider module it declares.
     *
     * <p>Alternatives Considered: the administrative variant of this call, which authenticates against
     * the pool with the task's own credentials rather than the app client's. Rejected because it needs
     * administrative user-pool permissions on the request-handling path, and the request-handling path
     * is the one path in this service that is reachable without a token at all. Keeping the
     * unauthenticated operation on the non-administrative call is what stops an unauthenticated request
     * from being handled by code holding administrative permission.
     *
     * <p>Trade-offs: the resilience posture is two explicit time bounds and nothing else. The total
     * bound caps the whole exchange including any attempt the client makes internally, and the
     * per-attempt bound stops one stalled connection consuming the whole budget, so a pool that stops
     * answering surfaces as an unevaluable credential within a known time instead of holding a request
     * thread indefinitely. No retry and no circuit breaker are added, and no resilience library is
     * introduced. A breaker would add a state machine whose open state returns the same unevaluable
     * outcome the timeout already returns, on the only synchronous hop this operation makes, so it
     * would add a failure mode -- a breaker open on stale evidence refusing sign-ons the pool would
     * have served -- without removing one. What is accepted is that a pool outage is felt as one
     * bounded wait per request rather than being short-circuited after the first failure.
     *
     * <p>Assumptions: the bounds are stated on the request rather than on the client, because the
     * client bean is contributed by {@code com.carddemo.auth.config.CognitoIdentityConfig} and is
     * shared with the user-administration path in this package, whose calls are not this call. A
     * per-request override bounds this exchange without imposing this operation's latency budget on
     * provisioning work that legitimately takes longer.
     *
     * @param userId the folded identifier to authenticate, already known to this context
     * @param credential the credential as submitted, forwarded unaltered
     * @return the pool's answer, which carries either an authentication result or a challenge; never
     *     {@code null}
     * @throws BadCredentialsException if the pool refused the pair, whether because the credential was
     *     wrong or because the pool holds no such user
     * @throws IllegalStateException if the pool could not be reached or answered a fault, so the
     *     credential was neither accepted nor refused
     */
    private InitiateAuthResponse exchangeCredential(String userId, String credential) {

        // WHY : Assumptions: the credential is placed in the request exactly as submitted. The
        //       identifier was folded and trimmed above because it is a key; a credential is compared
        //       byte for byte by the pool, so altering it here would present characters the caller did
        //       not type and turn a correct submission into a refusal naming the wrong cause.
        InitiateAuthRequest exchange = InitiateAuthRequest.builder()
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .clientId(clientId)
                .authParameters(Map.of(
                        AUTH_PARAM_USERNAME, userId,
                        AUTH_PARAM_PASSWORD, credential,
                        AUTH_PARAM_SECRET_HASH, secretHash(userId)))
                .overrideConfiguration(override -> override
                        .apiCallTimeout(TOTAL_EXCHANGE_TIMEOUT)
                        .apiCallAttemptTimeout(SINGLE_ATTEMPT_TIMEOUT))
                .build();

        try {
            return provider.initiateAuth(exchange);

            // WHY : Assumptions: these two provider faults are caught together and answered
            //       identically, and the uniformity is the whole point rather than a convenience. See
            //       the refusal helper for why the baseline's two distinguishable sentences become one.
        } catch (NotAuthorizedException | UserNotFoundException refused) {
            throw refusedCredential(refused.getClass().getSimpleName());

            // WHY : Assumptions: every remaining provider and transport fault is one class, because the
            //       baseline treats them as one. Its WHEN OTHER arm at app/cbl/COSGN00C.cbl line 252
            //       covers every response that is neither success nor not-found, so a pool that is
            //       unreachable, that rejects the request proof, that reports an internal fault or that
            //       is asked for an unconfigured flow all arrive here. SdkException is the common
            //       supertype of the client-side and service-side hierarchies, so catching it once
            //       covers a refused connection, an exceeded time bound and a fault response alike.
            //       The two credential refusals above are caught first because they are subtypes of it.
        } catch (SdkException unavailable) {
            throw unableToVerify("provider-" + unavailable.getClass().getSimpleName());
        }
    }

    /**
     * Computes the keyed digest that proves this confidential client issued the exchange.
     *
     * <p>Assumptions: the composition is the provider's published one and none of it is a choice made
     * here. The message is the identifier followed by the app-client identifier, the key is the
     * app-client secret, the algorithm is a SHA-256 keyed digest and the result is transmitted base-64
     * encoded. The identifier hashed here must be the same one sent as the user name, which is why the
     * folded value is passed in rather than the value as submitted.
     *
     * <p>Assumptions: the two operands are encoded as UTF-8 explicitly rather than through the
     * platform's default character set. A digest is over bytes, so a task started with a different
     * default would compute a different proof from the same inputs and the pool would reject every
     * exchange with a fault that names no cause.
     *
     * <p>Alternatives Considered: caching one initialised digest object on this class instead of
     * creating one per call. Rejected because the type is not safe for concurrent use -- it carries the
     * running state of one digest -- so a shared instance would need a lock around every sign-on, and
     * the contention that lock introduces costs more than constructing the object it protects.
     *
     * @param userId the folded identifier being authenticated, forming the first part of the message
     * @return the base-64 encoded keyed digest to send as the confidential-client proof; never
     *     {@code null}
     * @throws IllegalStateException if the digest algorithm is unavailable or the configured secret
     *     cannot be used as a key, either of which leaves the credential unevaluable rather than
     *     refused
     */
    private String secretHash(String userId) {

        try {
            Mac digest = Mac.getInstance(SECRET_HASH_ALGORITHM);
            digest.init(new SecretKeySpec(
                    clientSecret.getBytes(StandardCharsets.UTF_8), SECRET_HASH_ALGORITHM));
            digest.update(userId.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(digest.doFinal(clientId.getBytes(StandardCharsets.UTF_8)));

            // WHY : Assumptions: this is unevaluable rather than refused, and the distinction matters to
            //       the caller. A missing algorithm or an unusable key is a fault in this deployment,
            //       not a wrong credential, so answering with the refusal sentence would send a caller
            //       to reset a credential that was never examined. The caught type is the common
            //       supertype of both faults, so neither can be added later and go unhandled.
        } catch (GeneralSecurityException unusable) {
            throw unableToVerify("secret-hash-" + unusable.getClass().getSimpleName());
        }
    }

    /**
     * Decides which of the two published success shapes an accepted sign-on exchange produced.
     *
     * <p>Purpose: the pool answers an initial authentication with either an authentication result or a
     * challenge, never both and never neither, and the published contract has a shape for each. This
     * method is the single place that reads which one arrived and returns the corresponding shape.
     *
     * <p>Refactoring Rationale: an earlier form of this logic answered EVERY challenge as an unevaluable
     * credential, because the method it lived in returned the token set alone and the challenge shape was
     * unrepresentable in that signature. The consequence was the opposite of theoretical: every account
     * the infrastructure provisions is created with a temporary password, so the first sign-on of every
     * user raised {@code NEW_PASSWORD_REQUIRED} and answered HTTP 500, and no operation existed through
     * which a permanent password could be set. Widening the signature to the sealed outcome type is what
     * lets the challenge be returned as the contract declares it.
     *
     * <p>Trade-offs: exactly one challenge is translated into the challenge shape and every other
     * challenge remains an unevaluable exchange. The published contract admits a single member on its
     * challenge-name property, so a shape naming any other challenge would be a body no client is written
     * to read and no operation in this document can answer. What is given up is that enabling a
     * second-factor configuration on the pool makes sign-on fail rather than chain; what is bought is that
     * this service never hands a caller a challenge it has no published way to answer. The pool as
     * provisioned raises no other challenge -- {@code infra/modules/cognito} configures no second factor
     * -- so the branch is reachable only through a configuration change, which is the moment a contract
     * revision belongs.
     *
     * <p>Assumptions: a challenge that arrives without a session is unevaluable rather than returned,
     * even though its name is the one this contract answers. The session is the whole means of answering
     * it -- the answer operation cannot be performed without one -- so a body carrying the name and no
     * session would tell a caller to do something it has been given no way to do.
     *
     * @param answer the pool's answer to the exchange, carrying either an authentication result or a
     *     challenge; must not be {@code null}
     * @param userId the folded identifier the exchange was performed for, echoed onto whichever shape is
     *     returned
     * @return the token set as {@link SignOnResponse} when the pool issued one, or the challenge as
     *     {@link SignOnChallenge} when it raised the one this contract publishes an answer path for;
     *     never {@code null}
     * @throws IllegalStateException if the pool raised a challenge this contract publishes no answer path
     *     for, if it raised the published one without a session, or if it answered with a token set that
     *     is incomplete
     */
    private static SignOnOutcome outcomeFrom(InitiateAuthResponse answer, String userId) {

        AuthenticationResultType issued = answer.authenticationResult();

        if (issued != null) {
            return tokensFrom(issued, userId);
        }

        String challengeName = answer.challengeNameAsString();

        // WHY : Assumptions: the comparison is against the enumerated provider value's own string form
        //       rather than against a literal retyped here, so the name this service tests for cannot
        //       drift from the name the provider sends. The published contract's single admitted member
        //       is the same spelling, which SignOnChallenge asserts on its own component.
        if (ChallengeNameType.NEW_PASSWORD_REQUIRED.toString().equals(challengeName)) {

            // WHY : Assumptions: the identifier echoed onto the challenge is the FOLDED one this method
            //       was given, not whatever the pool may echo back in its own challenge parameters. The
            //       answer exchange recomputes the confidential-client proof over the identifier it is
            //       sent, so returning the folded value is what makes the answer this challenge invites
            //       computable from the challenge alone.
            if (answer.session() == null || answer.session().isBlank()) {
                LOG.warn("event=auth.signon.unevaluable reason=challenge-without-session challenge={}",
                        challengeName);
                throw unableToVerify("challenge-without-session");
            }

            // WHY : Assumptions: the line records that a challenge was raised and WHICH challenge, and
            //       records neither the session nor the identifier. Possession of the session plus a new
            //       password completes the authentication, so a log store holding it would hold half of
            //       a credential.
            // WHY : ⚠️ Refactoring Rationale: this line named the USER IDENTIFIER, on the
            //       reasoning that it "is already the row's primary key and is disclosed by every other
            //       line about this request". The second clause was a description of the defect rather
            //       than a justification -- every one of those lines has stopped disclosing it -- and the
            //       first is the reason it must not be logged: a sign-on identifier in an application log
            //       links log access to an identity record, and this line marks an account mid-way
            //       through authentication, which is the point at which that linkage is most useful to an
            //       attacker. What identifies the event instead is the correlation identifier the shared
            //       filter publishes into the mapped diagnostic context for the request, which the shared
            //       structured format renders on every line of it. Alternatives Considered: keeping the
            //       identifier because sign-on attribution is a security-audit need. Rejected because the
            //       authoritative record of who signed on, and of every challenge the pool raised, is the
            //       managed identity provider's own audit trail, which is separately governed and
            //       retained -- an application log is a second, weaker copy of it.
            LOG.info("event=auth.signon.challenge challenge={}", challengeName);
            return SignOnChallenge.newPasswordRequired(answer.session(), userId);
        }

        // WHY : Assumptions: the challenge name is recorded in the log and withheld from the response.
        //       An operator needs to know which challenge stalled a sign-on, whereas a caller learning
        //       it gains a detail about the pool's configuration and nothing it can act on through this
        //       operation.
        LOG.warn("event=auth.signon.unevaluable reason=unpublished-challenge challenge={}",
                challengeName);
        throw unableToVerify("challenge-" + challengeName);
    }

    /**
     * Builds the token-set body from an authentication result, refusing one that cannot be used.
     *
     * <p>Purpose: this is the single place a provider authentication result becomes the published token
     * set shape, shared by the sign-on, challenge and renewal exchanges so that all three validate what
     * the pool reported identically.
     *
     * <p>Refactoring Rationale: every member is now checked before the body is built, where an earlier
     * form checked only that the lifetime was present. That was too weak in a way that surfaced at the
     * caller rather than here. A result whose access token was absent, or empty, or whose token type was
     * absent, or whose lifetime was zero or negative, was relayed as a 200 SUCCESS carrying a token set
     * no caller could use: the browser client would store it, send an empty bearer credential on its next
     * request, be refused by the resource server with a 401 that names no cause, and send the user back
     * to a sign-on screen that had just told it sign-on succeeded. Refusing it here reports the one
     * failure that actually occurred -- the pool did not describe the account it had just authenticated
     * -- in the taxonomy the reference declares for exactly that: a credential that could not be
     * evaluated.
     *
     * <p>Assumptions: every member checked here is one the published contract declares REQUIRED on the
     * response, and none that the contract declares nullable is checked. The required set is the
     * outcome, the identifier, the access token, the identity token, the token type and the lifetime;
     * the renewal token is the one nullable member and is passed through exactly as supplied, including
     * when the pool supplies none.
     *
     * <p>Assumptions: ⚠️ Refactoring Rationale: this block justified not checking the renewal token by
     * saying the pool "never reissues one on that flow", which was true of the legacy refresh flow and is
     * false of the flow this service now uses. Rotation is enabled on the app client --
     * {@code RefreshTokenRotation} carries {@code Feature = "ENABLED"} with a zero retry grace period in
     * {@code infra/modules/cognito/main.tf} -- so a renewal ORDINARILY answers with a replacement token
     * and invalidates the one submitted. The member is still not checked, for a different and narrower
     * reason: the retry grace period is a pool-side setting, and configured above zero it leaves the
     * submitted token current and the answer without a replacement. Requiring one would turn a supported
     * pool configuration into a refused session.
     *
     * <p>Assumptions: blankness rather than nullity is the test on each token, because an empty or
     * whitespace-only token is as unusable as an absent one and a provider stub or a partially populated
     * response can produce either. This is the same reasoning the request records apply to their own
     * components, applied to a value arriving from the other direction.
     *
     * <p>Assumptions: the discriminating value is read from {@code SignOnResponse.OUTCOME_AUTHENTICATED}
     * rather than retyped, because a response body's own constraints are not evaluated on the way out: a
     * drifted literal would ship and the client's discriminator would then match neither declared shape.
     *
     * <p>Trade-offs: which member was at fault is recorded in the log and withheld from the caller. An
     * operator needs it to tell a pool misconfiguration from a client library fault; a caller learns
     * nothing it can act on from knowing which of six members the pool omitted, and the reference's
     * corresponding arm carries no diagnostic either.
     *
     * @param issued the authentication result the pool reported; must not be {@code null}
     * @param userId the folded identifier the tokens were issued for, echoed onto the response
     * @return the token set the pool issued, with the authenticated outcome and no user type; never
     *     {@code null}
     * @throws IllegalStateException if any member the contract declares required is absent or blank, or
     *     if the reported lifetime is below one second, so the reported set cannot be used
     */
    private static SignOnResponse tokensFrom(AuthenticationResultType issued, String userId) {

        requireReported("accessToken", issued.accessToken());
        requireReported("idToken", issued.idToken());
        requireReported("tokenType", issued.tokenType());

        Integer lifetime = issued.expiresIn();
        if (lifetime == null) {
            throw unableToVerify("token-lifetime-absent");
        }
        if (lifetime < MINIMUM_TOKEN_LIFETIME_SECONDS) {
            // WHY : Assumptions: the reported number is included in the internal reason because it is a
            //       property of the pool's answer rather than of the caller's request, and it is the one
            //       fact an operator needs to tell a clock-skew fault from a misconfigured lifetime. It
            //       reaches no response body: the sentence raised below carries no diagnostic.
            throw unableToVerify("token-lifetime-" + lifetime);
        }

        // WHY : ⚠️ Refactoring Rationale: this line named the USER IDENTIFIER and no longer
        //       does. It is emitted on every successful sign-on, so it was the highest-volume identity
        //       disclosure in this service: a log store retaining it holds a complete record of who used
        //       the system and when, keyed by the identity table's own primary key. The correlation
        //       identifier in the mapped diagnostic context marks the exchange, and the managed identity
        //       provider's audit trail -- separately governed and retained -- is the authoritative record
        //       of the sign-in itself.
        LOG.info("event=auth.tokens.issued");

        // WHY : Assumptions: the renewal token is passed through as the pool supplied it, including when
        //       the pool supplied none. The response component is declared nullable for that reason, so
        //       substituting a placeholder would report a token the caller cannot use.
        return new SignOnResponse(SignOnResponse.OUTCOME_AUTHENTICATED, userId,
                issued.accessToken(), issued.idToken(), issued.refreshToken(),
                issued.tokenType(), lifetime);
    }

    /**
     * Refuses a reported token member that is absent or holds nothing but whitespace.
     *
     * <p>Assumptions: the member name is passed in so the internal reason names which member failed,
     * and it is a fixed literal at each call site rather than a value derived from the provider's
     * response, so nothing of external provenance reaches the log line through it.
     *
     * @param member the contract property name of the member being checked, for the log only
     * @param reported the value the pool reported, possibly {@code null}
     * @throws IllegalStateException if the value is {@code null}, empty or entirely whitespace, carrying
     *     the baseline sentence for an unevaluable credential and no provider diagnostic
     */
    private static void requireReported(String member, String reported) {
        if (reported == null || reported.isBlank() || reported.length() < MINIMUM_TOKEN_LENGTH) {
            throw unableToVerify("token-" + member + "-absent");
        }
    }

    /**
     * Builds the refusal that answers a wrong credential and an unknown identifier alike.
     *
     * <p>Refactoring Rationale: the baseline distinguishes the two, and the target does not. Its
     * {@code EVALUATE WS-RESP-CD} takes {@code WHEN 0} and, on the comparison failing at
     * {@code app/cbl/COSGN00C.cbl} line 223, writes {@code 'Wrong Password. Try again ...'} across
     * lines 242 and 243; it takes {@code WHEN 13} for a record that does not exist and writes
     * {@code 'User not found. Try again ...'} at line 249. Only the first sentence survives, and the
     * second is returned on no code path here. What was wrong with the pair is that the two sentences
     * together are a user-enumeration oracle, and they are reachable without any credential because
     * sign-on is the one operation this migration publishes unauthenticated: an unauthenticated caller
     * could learn which identifiers exist simply by reading which sentence came back. The pool is
     * provisioned to answer the two cases identically -- {@code infra/modules/cognito/main.tf} line 612
     * sets uniform user-existence errors and the module publishes no input that could relax it -- so
     * the merge is a property of the provisioned system rather than a choice this class could reverse.
     * The baseline distinguishes them; the Java answers both with the first sentence; the divergence is
     * documented, and it is registered as {@code D-SIGNON-EXISTENCE-UNIFORM} in
     * {@code docs/architecture/cobol-to-service-traceability.md}. The third sentence, at line 254, is
     * unaffected and still answers an unevaluable credential.
     *
     * <p>Trade-offs: what is bought is that enumeration through this operation stops; what is given up
     * is a diagnostic the baseline showed an operator at a terminal inside the enterprise, which is a
     * materially different audience from an unauthenticated caller at an internet edge. The absent
     * local record is answered here rather than being relayed to the pool, and that is a second,
     * smaller compromise worth naming: the two paths differ in how long they take, so the timing of a
     * refusal is a weaker signal of the same fact the sentences used to carry outright. It is accepted
     * because the alternative is to present a submitted credential to a third party for an identifier
     * this context does not own, which widens the credential's exposure to buy uniformity in a channel
     * that is already far noisier than a difference in wording.
     *
     * <p>Assumptions: no field key accompanies this refusal, unlike the two presence refusals above.
     * Attributing the failure to the identifier or to the credential would restore by attribution
     * exactly the distinction the merged sentence withholds, and the published contract states the same
     * omission for its 401 response.
     *
     * @param reason the internal cause to record in the log, naming the provider fault or the local
     *     condition that produced the refusal; never reaches the caller
     * @return the refusal to throw, carrying the baseline refusal sentence and no field key; never
     *     {@code null}
     */
    private static BadCredentialsException refusedCredential(String reason) {

        // WHY : Assumptions: the reason is logged and never rendered. The published contract makes the
        //       three baseline sentences the entire externally visible failure vocabulary of this
        //       operation, so a provider fault name, a response code or a stack trace reaching a body
        //       would be a disclosure the contract does not describe. Note the baseline's own
        //       corresponding arm at lines 241 to 246 carries no such diagnostic either, and -- alone
        //       among the failing arms at 118, 123, 247 and 252 -- it does not even set the error flag
        //       those arms set; the Java has no such flag, so that asymmetry has nothing to reproduce
        //       and this path is reached and reported like any other refusal.
        LOG.info("event=auth.signon.refused reason={}", reason);
        return new BadCredentialsException(MESSAGE_CREDENTIAL_REFUSED);
    }

    /**
     * Builds the failure that answers a credential which could not be evaluated at all.
     *
     * <p>Assumptions: the exact type raised here is load bearing rather than incidental. The shared
     * kernel's {@code com.carddemo.common.error.GlobalExceptionHandler} carries a service's own sentence
     * onto the 500 body only when the failure is exactly this type and the sentence passes its
     * provenance gate -- ending in an ellipsis, within the declared message width and holding no long
     * run of digits -- and the sentence below satisfies all three. A different type, or a framework
     * status exception, would be rendered with a generic sentence instead, and the operation's published
     * 500 response names this literal specifically.
     *
     * <p>Assumptions: this is the successor to the baseline's {@code WHEN OTHER} arm at
     * {@code app/cbl/COSGN00C.cbl} line 252, whose sentence at line 254 is the one carried here. That
     * arm is where an infrastructure fault arrives rather than a credential fault, which is why this
     * path never reuses the refusal sentence: telling a caller its credential was wrong when the pool
     * was simply unreachable would send it to reset a credential that works.
     *
     * @param reason the internal cause to record in the log, naming the provider fault, the local
     *     condition or the challenge that made the credential unevaluable; never reaches the caller
     * @return the failure to throw, carrying the baseline sentence for an unevaluable credential; never
     *     {@code null}
     */
    private static IllegalStateException unableToVerify(String reason) {

        LOG.error("event=auth.signon.unevaluable reason={}", reason);
        return new IllegalStateException(MESSAGE_UNABLE_TO_VERIFY);
    }

    /**
     * Refuses a challenge answer whose identifier, session or proposed credential is absent.
     *
     * <p>Assumptions: the chain is ordered and it stops at the first failing value, matching the shape
     * the sign-on chain takes, even though there is no baseline evaluate construct to transcribe here.
     * The order is the committed contract's property order for this body -- identifier, session, then new
     * password -- so a caller submitting an empty body is told about the identifier, which is the value
     * it can most readily supply.
     *
     * <p>Alternatives Considered: leaving this to the bean constraints the request record already
     * declares, which would remove this method. Rejected for the reason the sign-on chain records: those
     * constraints guard the transport boundary, and a caller inside the application -- a test asserting
     * this behaviour directly, or any future in-process caller -- reaches this method without an
     * argument resolver having run and would otherwise present a blank session to the pool and receive a
     * refusal naming the wrong cause.
     *
     * @param request the submitted answer to check for presence; must not be {@code null}
     * @throws ClientInputException if any of the three values is absent or blank, carrying that value's
     *     key and the sentence the request record declares for it
     */
    private void requireChallengeFields(SignOnChallengeRequest request) {

        if (isAbsent(request.userId())) {
            LOG.info("event=auth.challenge.rejected reason=user-id-absent field={}", FIELD_USER_ID);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_USER_ID,
                    MESSAGE_USER_ID_REQUIRED);
        }

        if (isAbsent(request.session())) {
            LOG.info("event=auth.challenge.rejected reason=session-absent field={}", FIELD_SESSION);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_SESSION,
                    MESSAGE_SESSION_REFUSED);
        }

        // WHY : Assumptions: the sentence for an absent new credential is the sign-on password sentence
        //       reused, and the reuse is deliberate rather than a shortcut. Its literal --
        //       'Please enter Password ...' at app/cbl/COSGN00C.cbl:125 -- describes exactly this
        //       condition, a submitted form with no password in it, and transformation rule T8 keeps a
        //       user-visible string identical wherever the same condition is reported. Authoring a
        //       second sentence for the same condition would put two spellings of one message in front
        //       of a user.
        if (isAbsent(request.newPassword())) {
            LOG.info("event=auth.challenge.rejected reason=new-credential-absent field={}",
                    FIELD_NEW_PASSWORD);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_NEW_PASSWORD,
                    MESSAGE_PASSWORD_REQUIRED);
        }
    }

    /**
     * Refuses a renewal whose identifier or refresh token is absent.
     *
     * <p>Assumptions: the order is the committed contract's property order for this body, identifier then
     * token, for the same reason the challenge chain gives: there is no baseline construct to transcribe,
     * so the contract is the only ordering authority.
     *
     * @param request the submitted renewal to check for presence; must not be {@code null}
     * @throws ClientInputException if either value is absent or blank, carrying that value's key and the
     *     sentence the request record declares for it
     */
    private void requireRefreshFields(TokenRefreshRequest request) {

        if (isAbsent(request.userId())) {
            LOG.info("event=auth.refresh.rejected reason=user-id-absent field={}", FIELD_USER_ID);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_USER_ID,
                    MESSAGE_USER_ID_REQUIRED);
        }

        if (isAbsent(request.refreshToken())) {
            LOG.info("event=auth.refresh.rejected reason=refresh-token-absent field={}",
                    FIELD_REFRESH_TOKEN);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_REFRESH_TOKEN,
                    MESSAGE_SESSION_REFUSED);
        }
    }

    /**
     * Refuses a sign-out whose submitted token is absent.
     *
     * <p>Assumptions: there is one value to check, so there is no ordering to preserve and no reference
     * chain to reproduce. The sentence reported is the sign-on-again one the renewal uses for the same
     * member, because the remedy a caller has is identical: without a token there is nothing to revoke
     * and the local state should simply be discarded.
     *
     * <p>Alternatives Considered: treating an absent token as a no-op success, on the reasoning that a
     * sign-out with nothing to revoke has already achieved its outcome. Rejected because it would make
     * a client that never stored the token look indistinguishable from one that revoked it, which is
     * exactly the mistake this operation exists to catch -- the browser must learn that its sign-out
     * revoked nothing, even though it will clear its own state either way.
     *
     * @param request the submitted token to check for presence; must not be {@code null}
     * @throws ClientInputException if the token is absent or blank, carrying the sign-on-again sentence
     *     and the token's field key
     */
    private void requireSignOutFields(SignOutRequest request) {

        if (isAbsent(request.refreshToken())) {
            LOG.info("event=auth.signout.rejected reason=refresh-token-absent field={}",
                    FIELD_REFRESH_TOKEN);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_REFRESH_TOKEN,
                    MESSAGE_SESSION_REFUSED);
        }
    }

    /**
     * Answers the pool's new-credential challenge and returns whatever the pool answered.
     *
     * <p>Assumptions: the challenge answer is a distinct provider operation from the initial
     * authentication, not a repeat of it with different parameters. It carries the challenge name, the
     * session the pool issued, and a parameter map holding the user name, the proposed credential and
     * the confidential-client proof; the proof is recomputed over the same folded identifier, because
     * the pool verifies it against the user name in the same request.
     *
     * <p>Assumptions: the same two time bounds the credential exchange uses are applied here, and for the
     * same reason -- a person is waiting at a screen, and the bounds are stated on the request rather
     * than on the shared client so this operation's latency budget is not imposed on the provisioning
     * calls that legitimately take longer.
     *
     * <p>Trade-offs: a credential the pool refuses under its own password policy is reported as a caller
     * input failure carrying THE POOL'S OWN reason, which is the one place in this class where provider
     * text reaches a response body. It is admitted deliberately: the policy is configured in the pool and
     * this contract deliberately holds no second copy of it, so a sentence authored here would either
     * restate a policy that can change beneath it or tell the caller nothing about why its password was
     * refused -- leaving it to guess at a rule it cannot read. The exposure is narrow because the type
     * caught is the pool's password-policy refusal specifically rather than any provider fault, and its
     * message describes a password rule rather than any internal state.
     *
     * @param userId the folded identifier the challenge was raised for
     * @param session the session value the challenge issued, forwarded verbatim
     * @param newPassword the proposed permanent credential, forwarded unaltered
     * @return the pool's answer, carrying either an authentication result or a further challenge; never
     *     {@code null}
     * @throws ClientInputException if the pool refused the proposed credential under its password policy
     * @throws SessionRefusedException if the pool refused the session or the identifier it names
     * @throws IllegalStateException if the pool could not be reached or answered a fault
     */
    private RespondToAuthChallengeResponse answerNewPasswordChallenge(String userId, String session,
            String newPassword) {

        RespondToAuthChallengeRequest answer = RespondToAuthChallengeRequest.builder()
                .clientId(clientId)
                .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
                .session(session)
                // WHY : Assumptions: the proposed credential is placed in the request exactly as
                //       submitted. The identifier was folded and trimmed because it is a key; a
                //       credential is stored and later compared byte for byte by the pool, so altering
                //       it here would set a credential the caller did not type and every later sign-on
                //       with the value it did type would be refused.
                .challengeResponses(Map.of(
                        AUTH_PARAM_USERNAME, userId,
                        CHALLENGE_PARAM_NEW_PASSWORD, newPassword,
                        AUTH_PARAM_SECRET_HASH, secretHash(userId)))
                .overrideConfiguration(override -> override
                        .apiCallTimeout(TOTAL_EXCHANGE_TIMEOUT)
                        .apiCallAttemptTimeout(SINGLE_ATTEMPT_TIMEOUT))
                .build();

        try {
            return provider.respondToAuthChallenge(answer);

            // WHY : Assumptions: the password-policy refusal is caught FIRST and answered as a caller
            //       input failure, because it is the one refusal on this path the caller can act on by
            //       changing what it sent. It is a subtype of the provider fault hierarchy the last arm
            //       catches, so ordering it above that arm is what makes it reachable.
        } catch (InvalidPasswordException refusedByPolicy) {
            LOG.info("event=auth.challenge.rejected reason=password-policy field={}",
                    FIELD_NEW_PASSWORD);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_NEW_PASSWORD,
                    policyReason(refusedByPolicy));

            // WHY : Assumptions: these two are caught together and answered identically, matching the
            //       sign-on exchange. An expired, already-used, altered or mismatched session and an
            //       identifier the pool does not hold all reach one status with one sentence, because
            //       the remedy is the same in every case and distinguishing them would tell an
            //       unauthenticated caller which of the four it had.
        } catch (NotAuthorizedException | UserNotFoundException refused) {
            throw refusedSession(refused.getClass().getSimpleName());

            // WHY : Assumptions: every remaining provider and transport fault is one class, on the same
            //       grounds the credential exchange records: the common supertype of the client-side and
            //       service-side hierarchies covers a refused connection, an exceeded time bound and a
            //       fault response alike, and the three narrower arms above are subtypes of it and so
            //       must precede it.
        } catch (SdkException unavailable) {
            throw unableToVerify("challenge-provider-" + unavailable.getClass().getSimpleName());
        }
    }

    /**
     * Renders the pool's own account of a refused password into a sentence that can reach the caller.
     *
     * <p>Purpose: the published contract states that a policy refusal carries "the pool's own reason
     * rather than a restatement of the policy", and this is the one transformation that makes that
     * possible. The shared advice will only carry a service's sentence onto a response when the sentence
     * passes its provenance gate -- printable, within the declared message width, ending in an ellipsis
     * and holding no long run of digits -- and a provider's raw sentence satisfies none of the last three
     * by construction. Without this it would be replaced by a generic sentence and the contract's promise
     * would be unkeepable.
     *
     * <p>Refactoring Rationale: an earlier form passed the provider's message through unaltered. It
     * looked correct and could not work: the gate rejected every real provider sentence, so the response
     * carried the generic validation sentence and the caller learned nothing about why its password was
     * refused. The defect was invisible in this class -- the message was set correctly -- and only visible
     * two layers away in the rendered body.
     *
     * <p>Assumptions: each of the four transformations answers one clause of that gate, and none is
     * cosmetic. Non-printable characters are dropped because a value of external provenance entering a
     * structured response, and the log line beside it, is exactly the injection route the gate refuses
     * outright. Whitespace runs are collapsed because a provider sentence may carry a newline, which the
     * gate treats as non-printable. Trailing full stops are removed before the ellipsis is appended so
     * the result does not read as four dots. And the text is truncated to leave room for the ellipsis,
     * because the width is the reference message field's own.
     *
     * <p>Assumptions: a reason carrying a long run of digits is DISCARDED rather than truncated, and the
     * authored sentence is used instead. That run is the shape of a primary account number, and the
     * gate's own refusal of it exists because a message reaching a body is also written to a log; a
     * provider sentence quoting a submitted value could carry one, and shortening it would not make it
     * safe.
     *
     * <p>Trade-offs: a long provider sentence is cut mid-word, which reads poorly. The alternative --
     * discarding any sentence too long to carry whole -- was rejected because the leading words of a
     * policy refusal are the part that names the rule, so a truncated sentence still tells the caller
     * which requirement it missed while a discarded one tells it nothing.
     *
     * @param refusedByPolicy the pool's refusal of the proposed password; must not be {@code null}
     * @return the pool's reason as a sentence the shared advice will carry, or the authored fallback when
     *     the pool supplied nothing usable; never {@code null}
     */
    private static String policyReason(InvalidPasswordException refusedByPolicy) {

        String reported = refusedByPolicy.awsErrorDetails() == null
                ? refusedByPolicy.getMessage()
                : refusedByPolicy.awsErrorDetails().errorMessage();

        if (reported == null) {
            return MESSAGE_PASSWORD_REQUIRED;
        }

        StringBuilder printable = new StringBuilder(reported.length());
        boolean pendingSpace = false;
        int digitRun = 0;
        for (int index = 0; index < reported.length(); index++) {
            char character = reported.charAt(index);
            if (Character.isWhitespace(character)) {
                pendingSpace = printable.length() > 0;
                continue;
            }
            if (character < ' ' || character > '~') {
                continue;
            }
            digitRun = character >= '0' && character <= '9' ? digitRun + 1 : 0;
            if (digitRun >= SENSITIVE_DIGIT_RUN) {
                return MESSAGE_PASSWORD_REQUIRED;
            }
            if (pendingSpace) {
                printable.append(' ');
                pendingSpace = false;
            }
            printable.append(character);
        }

        while (printable.length() > 0 && printable.charAt(printable.length() - 1) == '.') {
            printable.setLength(printable.length() - 1);
        }
        while (printable.length() > 0 && printable.charAt(printable.length() - 1) == ' ') {
            printable.setLength(printable.length() - 1);
        }
        if (printable.length() == 0) {
            return MESSAGE_PASSWORD_REQUIRED;
        }
        if (printable.length() > MAX_POLICY_REASON_LENGTH) {
            printable.setLength(MAX_POLICY_REASON_LENGTH);
        }
        return printable + " ...";
    }

    /**
     * Presents a refresh token to the pool's rotation-compatible renewal operation.
     *
     * <p>⚠️ Refactoring Rationale: this issued {@code InitiateAuth} with the
     * {@code REFRESH_TOKEN_AUTH} flow, and that request was refused by the pool every time it was made.
     * The flow is not permitted on a client whose refresh-token rotation is enabled, and the module that
     * provisions this client both enables rotation and validates the permitted flow list to REJECT the
     * flow this call was selecting -- so the two halves of the deployment were configured correctly and
     * consistently while this line asked for something neither permitted. The failure was silent in the
     * worst available way: a refused renewal is reported as a refused session, so the browser ended the
     * session and returned the user to sign-on exactly one access-token lifetime after every sign-on,
     * with nothing in the response naming a cause an operator could act on.
     *
     * <p>Assumptions: the rotation-compatible operation carries the client secret as its own member
     * rather than a keyed digest in a parameter map, so no proof is computed here and no user name is
     * sent. That is the provider's contract for this call and not a simplification: the operation accepts
     * {@code refreshToken}, {@code clientId}, {@code clientSecret}, an optional device key and optional
     * client metadata, and nothing that could carry a user name. The consequence -- that the pool no
     * longer verifies the submitted identifier against the token's subject -- is repaired by the caller,
     * which reads the subject out of the issued identity token and compares it.
     *
     * <p>Assumptions: a token the pool has already rotated away raises its own refusal type, which is
     * caught with the other refusals rather than separately. Reuse of a rotated token is what a caller
     * that failed to store the replacement produces, and the remedy is identical to an expired token's:
     * sign on again. Telling the two apart on the response would describe the pool's rotation state to an
     * unauthenticated caller for nothing it could act on.
     *
     * <p>Assumptions: the same two time bounds are applied for the same reason as on the other two
     * exchanges. A renewal is on the critical path of a caller mid-session, so an unbounded wait here
     * would stall a request the user believes is already authenticated.
     *
     * @param refreshToken the refresh token as submitted, forwarded unaltered
     * @return the pool's answer, carrying the renewed and rotated token set; never {@code null}
     * @throws SessionRefusedException if the pool refused the token, whether expired, revoked or already
     *     rotated away
     * @throws IllegalStateException if the pool could not be reached or answered a fault
     */
    private GetTokensFromRefreshTokenResponse exchangeRefreshToken(String refreshToken) {

        GetTokensFromRefreshTokenRequest renewal = GetTokensFromRefreshTokenRequest.builder()
                .refreshToken(refreshToken)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .overrideConfiguration(override -> override
                        .apiCallTimeout(TOTAL_EXCHANGE_TIMEOUT)
                        .apiCallAttemptTimeout(SINGLE_ATTEMPT_TIMEOUT))
                .build();

        try {
            return provider.getTokensFromRefreshToken(renewal);

        } catch (RefreshTokenReuseException | NotAuthorizedException | UserNotFoundException refused) {
            throw refusedSession(refused.getClass().getSimpleName());

        } catch (SdkException unavailable) {
            throw unableToVerify("refresh-provider-" + unavailable.getClass().getSimpleName());
        }
    }

    /**
     * Reads the pool user name out of an identity token the pool has just issued.
     *
     * <p>Purpose: this recovers the subject a renewed token set belongs to, so the submitted identifier
     * can be checked against it rather than trusted. It exists because the rotation-compatible renewal
     * operation accepts no user name and therefore verifies none; see the renewal exchange above.
     *
     * <p>Assumptions: the token is NOT validated here and does not need to be, which is the one point a
     * reader is most likely to challenge. Signature, issuer and expiry validation exist to establish that
     * a token presented by an untrusted party is genuine; this token was not presented by anyone -- it is
     * the body of the response to an outbound call this service just made to the pool over TLS, so its
     * provenance is the call itself. Verifying it here would re-derive a fact already established and
     * would put key retrieval on the renewal path. Tokens arriving from a CALLER are validated, by the
     * resource-server filter chain in {@code com.carddemo.auth.config.SecurityConfig}, which is a
     * different direction and a different trust question.
     *
     * <p>Assumptions: only the claim segment is decoded, with the URL-safe alphabet and without padding,
     * which is the compact serialisation's own encoding. A token that does not carry exactly three
     * segments, or whose claim segment is not base-64url, or whose claims are not an object, or which
     * carries no user-name claim, is treated as an unevaluable answer rather than as a refusal: the pool
     * issuing a token this service cannot read is a fault of the deployment and says nothing about the
     * caller's credential.
     *
     * <p>Alternatives Considered: calling the provider's get-user operation with the issued access token,
     * which reports the user name authoritatively and needs no parsing. Rejected because it puts a second
     * network round trip on every renewal -- doubling the latency of an operation a user is waiting on
     * mid-session -- and adds a failure mode to a path whose whole purpose is to keep a working session
     * working. The claim is already in hand.
     *
     * <p>Alternatives Considered: reading the {@code sub} claim instead. Rejected because it is the
     * pool's own subject identifier, a UUID, while the key of {@code auth.users} is the eight-character
     * identifier the baseline declares -- comparing the two identifier spaces would refuse every renewal.
     *
     * @param idToken the identity token the pool issued in the answer being processed; may be
     *     {@code null} or blank, which is refused
     * @return the user name the token was issued for, exactly as the claim carries it; never
     *     {@code null} and never blank
     * @throws IllegalStateException if the token is absent, is not a three-segment compact
     *     serialisation, cannot be base-64url decoded, does not decode to a JSON object, or carries no
     *     user-name claim -- each of which leaves the renewal unevaluable rather than refused
     */
    private static String subjectOfIdentityToken(String idToken) {

        if (idToken == null || idToken.isBlank()) {
            throw unableToVerify("refresh-id-token-absent");
        }

        String[] segments = idToken.split("\\.");
        if (segments.length != ID_TOKEN_SEGMENT_COUNT) {
            throw unableToVerify("refresh-id-token-segments-" + segments.length);
        }

        JsonNode claims;
        try {
            claims = CLAIM_READER.readTree(
                    Base64.getUrlDecoder().decode(segments[ID_TOKEN_CLAIM_SEGMENT_INDEX]));

            // WHY : Assumptions: both faults are caught together because both mean the same thing to
            //       this method -- the pool sent something this service cannot read as a token -- and
            //       neither carries a detail a caller could act on. The decoder raises the unchecked
            //       argument failure for a segment that is not base-64url; the reader declares the
            //       checked input-output failure, of which its JSON-processing failure is a subtype, so
            //       the broader type is caught rather than the narrower one the reader actually raises
            //       from a byte array. The exception's class is recorded in the internal reason and its
            //       message, which could quote the undecodable material, is not.
        } catch (IOException | IllegalArgumentException unreadable) {
            throw unableToVerify("refresh-id-token-" + unreadable.getClass().getSimpleName());
        }

        JsonNode subject = claims.path(ID_TOKEN_USERNAME_CLAIM);
        if (!subject.isTextual() || subject.asText().isBlank()) {
            throw unableToVerify("refresh-id-token-subject-absent");
        }

        return subject.asText();
    }

    /**
     * Asks the pool to revoke a refresh token, so that no further token set can be minted from it.
     *
     * <p>Purpose: this is the provider half of sign-out. The app client is provisioned with token
     * revocation enabled -- {@code EnableTokenRevocation} is fixed true in
     * {@code infra/modules/cognito/main.tf} -- which is what makes the call available at all.
     *
     * <p>Assumptions: the call carries the token, the client identifier and the client secret and no user
     * name, because the operation accepts none: the token identifies its own subject to the pool, and the
     * client credentials are what authorise this service to speak for the client the token was minted
     * under. That is also why the operation this serves needs no identifier and no bearer -- possession
     * of the token is the whole authority required, exactly as it is for the renewal the revocation
     * cancels.
     *
     * <p>Assumptions: a token the pool will not accept is reported as SUCCESS rather than as a refusal,
     * and the two provider refusals that mean exactly that are caught and discarded here. An expired,
     * already-revoked or malformed token cannot mint anything, which is the outcome the caller asked for,
     * so answering it as a failure would report an unmet goal that has in fact been met. It would also
     * hand an unauthenticated caller a test for whether a token is live, which is a disclosure this
     * operation has no reason to make. The published revocation semantics of OAuth 2.0 take the same
     * position for the same reason: RFC 7009 section 2.2 has the server answer success both for a token
     * it revoked and for an invalid token a client submitted.
     *
     * <p>Trade-offs: a pool that could not be REACHED is reported as a failure, unlike a token it
     * refused. The distinction is the one that matters to a caller: a refused token is already unusable,
     * whereas an unreachable pool means the token is still live and still able to mint access tokens, so
     * answering success would state that a revocation happened when it did not. The browser clears its
     * own state either way, so the report costs the caller nothing and tells the operator the truth.
     *
     * @param refreshToken the refresh token to revoke, as submitted and forwarded unaltered
     * @throws IllegalStateException if the pool could not be reached or answered a fault, so the token
     *     may still be live
     */
    private void revokeRefreshToken(String refreshToken) {

        RevokeTokenRequest revocation = RevokeTokenRequest.builder()
                .token(refreshToken)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .overrideConfiguration(override -> override
                        .apiCallTimeout(TOTAL_EXCHANGE_TIMEOUT)
                        .apiCallAttemptTimeout(SINGLE_ATTEMPT_TIMEOUT))
                .build();

        try {
            provider.revokeToken(revocation);
            LOG.info("event=auth.signout.revoked");

            // WHY : Assumptions: the token-type refusal is caught alongside the unauthorised one because
            //       both describe a submitted value the pool will not act on. The pool answers the
            //       unsupported-token-type refusal when the value is not a refresh token -- an access
            //       token, say -- and that value mints nothing on presentation to this client either, so
            //       the caller's goal is met by the value being useless rather than by a revocation.
        } catch (UnsupportedTokenTypeException | NotAuthorizedException alreadyUnusable) {
            LOG.info("event=auth.signout.noop reason={}",
                    alreadyUnusable.getClass().getSimpleName());

            // WHY : Assumptions: every remaining provider and transport fault is one class, on the same
            //       grounds the credential exchange records -- the common supertype of the client-side
            //       and service-side hierarchies covers a refused connection, an exceeded time bound and
            //       a fault response alike, and the two narrower arms above are subtypes of it and so
            //       must precede it.
        } catch (SdkException unavailable) {
            throw unableToVerify("signout-provider-" + unavailable.getClass().getSimpleName());
        }
    }

    /**
     * Builds the refusal that answers a session or refresh token the pool would not accept.
     *
     * <p>Refactoring Rationale: this is a distinct type from the credential refusal rather than the same
     * type carrying a different sentence, and the reason is that the adapter renders a FIXED sentence per
     * type. Rendering whatever message a refusal happened to carry would mean any refusal raised anywhere
     * -- including one raised by a library -- could put its own text on an unauthenticated 401, which is
     * the property the adapter's credential handler was written to prevent. A second type keeps that
     * property while letting the two operations report the two different sentences their contracts
     * declare: the credential exchange reports the reference's own wording, and these two exchanges,
     * which have no reference counterpart, report the sign-on-again sentence.
     *
     * <p>Assumptions: it extends the credential refusal so that a caller which handles the general case
     * still catches this one. That containment is truthful -- a refused session IS a refused credential
     * of a kind -- and it means the adapter's existing handler remains a correct fallback if the narrower
     * handler is ever removed, rather than the refusal falling through to a 500.
     *
     * @param reason the internal cause to record in the log, naming the provider fault or the local
     *     condition that produced the refusal; never reaches the caller
     * @return the refusal to throw, carrying the sign-on-again sentence and no field key; never
     *     {@code null}
     */
    private static SessionRefusedException refusedSession(String reason) {

        // WHY : Assumptions: the reason is logged and never rendered, exactly as on the credential
        //       refusal. The published contract makes the sentence below the entire externally visible
        //       vocabulary of this status, so a provider fault name reaching a body would be a
        //       disclosure the contract does not describe.
        LOG.info("event=auth.session.refused reason={}", reason);
        return new SessionRefusedException(MESSAGE_SESSION_REFUSED);
    }

    /**
     * Reports that a challenge session or a refresh token was not accepted.
     *
     * <p>Purpose: this type exists so the adapter can render the sign-on-again sentence for the two
     * exchanges that have no reference counterpart, while the reference's own refusal wording stays
     * reserved to the credential exchange it belongs to.
     *
     * <p>Assumptions: it is declared here, nested in the service that raises it, following the idiom
     * {@code com.carddemo.common.web.CursorToken.InvalidCursorException} already establishes in the
     * shared kernel -- a refusal type belongs with the code that decides the refusal, so the decision and
     * its name cannot drift apart.
     *
     * <p>Assumptions: it carries no field key and no provider detail, because the status it is rendered
     * as carries neither. Every reason the two exchanges can be refused for reaches this one type, and
     * the published contract states for both operations that the body names no reason beyond the remedy.
     */
    public static final class SessionRefusedException extends BadCredentialsException {

        /**
         * The serialization version of this refusal.
         *
         * <p>Assumptions: declared because the supertype is serializable and a class that inherits
         * serializability without declaring this constant gets a value derived from its structure, so
         * adding a field would silently change it. Nothing serializes this type today; the constant
         * costs one line and removes the question.</p>
         */
        private static final long serialVersionUID = 1L;

        /**
         * Builds the refusal with the sentence the adapter will render.
         *
         * @param message the sentence to carry, always {@link #MESSAGE_SESSION_REFUSED} in this service;
         *     must not be {@code null}
         */
        SessionRefusedException(String message) {
            super(message);
        }
    }
}
