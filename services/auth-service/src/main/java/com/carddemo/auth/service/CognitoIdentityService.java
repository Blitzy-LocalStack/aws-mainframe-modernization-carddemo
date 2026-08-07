package com.carddemo.auth.service;

import com.carddemo.auth.dto.SignOnRequest;
import com.carddemo.auth.dto.SignOnResponse;
import com.carddemo.auth.repository.UserRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Exchanges a submitted user identifier and credential for the token set a completed sign-on issues.
 *
 * <h2>Which baseline paragraph this replaces</h2>
 *
 * <p>This is the target successor to {@code READ-USER-SEC-FILE}, the paragraph whose label sits at
 * {@code app/cbl/COSGN00C.cbl} line 209 and whose body runs to line 257. The traceability register
 * records the pairing directly: {@code docs/architecture/cobol-to-service-traceability.md} maps that
 * paragraph onto {@code CognitoIdentityService.authenticate}, which is the single public method below.
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

    /** The keyed-digest algorithm the confidential-client proof is computed with. */
    private static final String SECRET_HASH_ALGORITHM = "HmacSHA256";

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
     * <p>The four steps below are the baseline paragraph's own order, kept because the order is
     * observable rather than incidental. Presence is checked first and stops at the first offending
     * field, which is what decides the one sentence a caller submitting an empty screen is told. The
     * identifier is then normalised. The local record is probed next, so no credential is relayed for
     * an identifier this context does not own. Only then is the credential presented to the pool.
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
     * itself, which is narrower than this method and closes before the provider is reached. Declaring
     * one here would instead hold a pooled connection for the duration of an outbound network
     * exchange -- {@code application.yml} sizes this service's pool at ten -- so a slow pool would
     * consume connections that no longer have a query to run. What is given up is that the probe and
     * the exchange are not one atomic unit, which costs nothing here because the probe only reads and
     * the method writes nothing at all.
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
     * @param request the submitted identifier and credential, as the unauthenticated sign-on operation
     *     received them; must not be {@code null}
     * @return the token set the pool issued, carrying the normalised identifier and no authoritative
     *     user type; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws ClientInputException if the identifier or the credential is absent or blank, carrying the
     *     baseline sentence for the earlier of the two fields and that field's key
     * @throws BadCredentialsException if the pool refused the pair, or if this context holds no record
     *     for the identifier, carrying the baseline refusal sentence and no field key
     * @throws IllegalStateException if the credential could not be evaluated at all -- the pool being
     *     unreachable or answering a fault, the request proof being rejected, or the pool answering
     *     with a challenge this operation cannot complete -- carrying the baseline sentence for an
     *     unevaluable credential and no provider diagnostic
     */
    public SignOnResponse authenticate(SignOnRequest request) {

        Objects.requireNonNull(request, "request");

        requireSubmittedFields(request);

        String userId = normaliseUserId(request.userId());

        // WHY : Assumptions: the probe stands in for the keyed read at app/cbl/COSGN00C.cbl lines 211
        //       to 219, which carries no UPDATE option and so is a plain positioned read rather than a
        //       read for update. Nothing here acquires a lock for the same reason: no row is written.
        if (!isKnownLocally(userId)) {
            throw refusedCredential("local-record-absent");
        }

        InitiateAuthResponse answer = exchangeCredential(userId, request.password());

        return tokensFrom(answer, userId);
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
     * @param userId the folded identifier to look for, as the key of {@code auth.users}
     * @return {@code true} when a record exists for the identifier and {@code false} when none does
     */
    private boolean isKnownLocally(String userId) {
        return users.existsById(userId);
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
     * Builds the success body from an accepted exchange, or rejects an answer that carries no tokens.
     *
     * <p>Assumptions: the pool answers an initial authentication with either an authentication result or
     * a challenge, never both and never neither, so the absence of a result means a challenge was
     * returned. The success branch reads the discriminating value from
     * {@code SignOnResponse.OUTCOME_AUTHENTICATED} rather than retyping it, because a response body's
     * own constraints are not evaluated on the way out: a drifted literal would ship and the client's
     * discriminator would then match neither declared shape.
     *
     * <p>Trade-offs: a challenge is answered here as an unevaluable credential, and this is the one
     * place where this method's declared return type is narrower than the operation's published
     * contract. That contract declares the success status as a choice of two shapes, and routes the
     * new-credential challenge to the challenge shape precisely so that a seeded user's first sign-on is
     * not reported as a fault. This method returns {@code SignOnResponse}, whose discriminating
     * component admits the authenticated value alone, so the challenge shape is unrepresentable in its
     * signature and cannot be produced from here. The boundary is therefore recorded rather than
     * papered over: completing a challenge is the separately published challenge operation's
     * responsibility, and the shape it answers with belongs to a sibling type in
     * {@code com.carddemo.auth.dto} that this method does not construct. What is accepted in the interim
     * is that a challenge reports as unevaluable, which is truthful about this method -- it did not
     * evaluate the credential to a token set -- and which deliberately does not reuse the refusal
     * sentence, because the credential was in fact accepted and a caller told otherwise would reset a
     * working credential.
     *
     * <p>Assumptions: the lifetime the pool reports arrives as a boxed integer and is read through a
     * null guard, because the response component it feeds is a primitive. An answer that carried tokens
     * but omitted the lifetime would otherwise fail on unboxing with an exception naming nothing a
     * reader could act on, where the sentence raised here names the operation that could not complete.
     *
     * @param answer the pool's answer to the exchange, carrying either an authentication result or a
     *     challenge
     * @param userId the folded identifier the tokens were issued for, echoed onto the response
     * @return the token set the pool issued, with the authenticated outcome and no user type; never
     *     {@code null}
     * @throws IllegalStateException if the answer carries no authentication result, or carries one whose
     *     lifetime is absent, so no token set can be reported
     */
    private static SignOnResponse tokensFrom(InitiateAuthResponse answer, String userId) {

        AuthenticationResultType issued = answer.authenticationResult();

        if (issued == null) {
            // WHY : Assumptions: the challenge name is recorded in the log and withheld from the
            //       response. An operator needs to know which challenge stalled a sign-on, whereas a
            //       caller learning it gains a detail about the pool's configuration and nothing it can
            //       act on through this operation.
            LOG.warn("event=auth.signon.unevaluable reason=challenge-returned challenge={}",
                    answer.challengeNameAsString());
            throw unableToVerify("challenge-" + answer.challengeNameAsString());
        }

        if (issued.expiresIn() == null) {
            throw unableToVerify("lifetime-absent");
        }

        LOG.info("event=auth.signon.authenticated userId={}", userId);

        // WHY : Assumptions: the renewal token is passed through as the pool supplied it, including when
        //       the pool supplied none. The response component is declared nullable for that reason, so
        //       substituting a placeholder would report a token the caller cannot use.
        return new SignOnResponse(SignOnResponse.OUTCOME_AUTHENTICATED, userId,
                issued.accessToken(), issued.idToken(), issued.refreshToken(),
                issued.tokenType(), issued.expiresIn());
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
}
