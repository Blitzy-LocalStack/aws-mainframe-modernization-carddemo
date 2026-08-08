package com.carddemo.auth.api;

import com.carddemo.auth.dto.SignOnChallengeRequest;
import com.carddemo.auth.dto.SignOnOutcome;
import com.carddemo.auth.dto.SignOnRequest;
import com.carddemo.auth.dto.SignOnResponse;
import com.carddemo.auth.dto.TokenRefreshRequest;
import com.carddemo.auth.service.CognitoIdentityService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the sign-on credential exchange migrated from {@code app/cbl/COSGN00C.cbl}.
 *
 * <p>Purpose: this adapter turns one HTTP request into one call on
 * {@link CognitoIdentityService#authenticate(SignOnRequest)} and turns its outcome into a status code.
 * The baseline program it replaces is 260 lines of pseudo-conversational CICS COBOL reached by
 * transaction {@code CC00}, whose resource definition opens at {@code app/csd/CARDDEMO.CSD:378} and
 * names the program at {@code :379}. That one program is the whole specification for this class, as
 * the package charter beside this file records.</p>
 *
 * <p>Assumptions: exactly three concerns live here, because the charter in
 * {@code package-info.java} closes the list at three -- validating an inbound request as transport,
 * mapping an outcome onto an HTTP status, and delegating to the layer beneath. Credential evaluation,
 * identifier folding and the choice of which sentence answers which failure all belong to
 * {@link CognitoIdentityService}; none of them is repeated here, and a reader looking for a validation
 * rule or a message literal in this file will find only the one sentence noted on
 * {@link #MESSAGE_CREDENTIAL_REFUSED}.</p>
 *
 * <p>Refactoring Rationale: nothing in the baseline's communication area survives into this class.
 * {@code app/cbl/COSGN00C.cbl} declares that structure's inbound face in its linkage section at
 * {@code :64-67}, as a {@code DFHCOMMAREA} whose single subordinate item is
 * {@code LK-COMMAREA PIC X(01) OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN}; it detects first entry
 * by testing {@code IF EIBCALEN = 0} at {@code :80}; and it hands the whole structure back to the
 * terminal at {@code :98-102}, with the {@code COMMAREA} option itself on {@code :100}. The
 * re-entry discriminator that structure carried, {@code CDEMO-PGM-CONTEXT} at
 * {@code app/cpy/COCOM01Y.cpy:29-31} with an enter value on {@code :30} and a re-enter value on
 * {@code :31}, has no counterpart here at all. That absence severs a real coupling rather than
 * relocating it: in the baseline the field-highlight logic is gated on the re-entry flag, so what a
 * screen displayed depended on a turn count the server remembered, whereas here the per-field error
 * array in the response body is the only input to error rendering. Consequently this class holds no
 * first-entry branch for anyone to maintain, keeps no state between requests, and needs neither a
 * sticky session nor load-balancer affinity -- which is what lets the tasks running it scale
 * horizontally. The baseline's own resource definitions corroborate that a stateless boundary is
 * faithful rather than a departure: transaction {@code CC00} declares {@code TWASIZE(0)} at
 * {@code app/csd/CARDDEMO.CSD:379}, so the per-task work area it could have reserved is zero bytes
 * wide.</p>
 *
 * <p>Refactoring Rationale: no user type is accepted from a caller and none is returned as an
 * authorization input, so the baseline's own next-program decision does not survive as a server-side
 * one. The baseline picks the next program itself at {@code app/cbl/COSGN00C.cbl:230-240}, where a
 * single test of the administrative condition name selects between a transfer to {@code COADM01C}
 * named on {@code :232} and one to {@code COMEN01C} named on {@code :237}, having moved the record's
 * one-character type into the communication area at {@code :227}. What was wrong with that is not the
 * branch but its input: the type byte is declared at {@code app/cpy/COCOM01Y.cpy:26} with its two
 * condition names at {@code :27-28}, it travelled in a structure the client held between turns and
 * handed back, and the transaction definition added no compensating gate -- the {@code CC00} stanza
 * carries {@code RESSEC(NO) CMDSEC(NO)} at {@code app/csd/CARDDEMO.CSD:385} -- so a client-asserted
 * byte was the only gate there was. Here the response carries a token, the browser routes on its
 * group claim, and a claim on a validated token is not something a caller can assert. The baseline
 * behaves as just described; the target encodes the signed claim; the divergence is documented rather
 * than introduced silently.</p>
 *
 * <p>Trade-offs: this class carries no springdoc annotation -- no {@code @Tag}, no
 * {@code @Operation}, no {@code @ApiResponse} -- because the hand-authored OpenAPI 3.1 document at
 * {@code services/auth-service/src/main/resources/openapi/auth-api.yaml} is the contract of record,
 * and it settles the paths, the property names, the response shapes and the error vocabulary. The
 * structural precedent for that precedence is {@code tests/README.md:3-6}, which resolves a
 * disagreement between a runner script and its own prose in favour of the script; here the committed
 * document plays the script's role, so generated output that drifts from it is a defect in whichever
 * one moved rather than a tolerated variation. Annotating the handler would put a second, editable
 * copy of the contract in the code and make drift expressible at all. What is given up is that the
 * document springdoc assembles is thinner than the committed one, carrying only the document-level
 * metadata {@code com.carddemo.auth.config.OpenApiConfig} supplies; that is accepted because the
 * committed document, not the generated one, is what clients are generated from. Every sibling
 * adapter in this migration is annotated the same way, so the convention is uniform rather than local
 * to this file.</p>
 *
 * <p>Refactoring Rationale: this class now serves ALL THREE operations the committed document tags
 * {@code Sign-On}, where it previously served one. The two that were missing were
 * {@code answerSignOnChallenge} on {@code POST /api/v1/auth/challenge} and {@code refreshTokens} on
 * {@code POST /api/v1/auth/refresh}, and their absence was not a tidy boundary: the filter chain
 * already opened both paths and the edge already forwarded them, so the gateway routed two operations
 * the service answered with 404. The challenge one mattered more than a missing route usually does.
 * Every account the infrastructure provisions is created with a temporary password, a temporary
 * password always raises {@code NEW_PASSWORD_REQUIRED} on first use, and with no operation to answer
 * that challenge no provisioned user could obtain a token at all -- the first sign-on of every user
 * answered 500. All three operations are published unauthenticated, which the committed document
 * states by declaring {@code security: []} on each: one issues a token, one completes the exchange
 * that issues one, and one renews a token that may already have expired, so none of the three can
 * require one.</p>
 *
 * <p>Trade-offs: no golden-master oracle exists for this path. The online programs of this context
 * cannot run end to end without a CICS runtime, which {@code tests/README.md:83-85} records among the
 * suite's known limitations, so parity here rests on the transcribed rules and on the tests under
 * {@code services/auth-service/src/test} rather than on byte comparison against recorded mainframe
 * output. That is a weaker guarantee than the batch contexts enjoy. What remains directly verifiable
 * is the message text, which is why the one sentence this class declares is reproduced character for
 * character from its cited line and is asserted that way.</p>
 */
@RestController
@RequestMapping(path = AuthController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    /** The context prefix every operation of this adapter sits beneath. */
    public static final String BASE_PATH = "/api/v1/auth";

    /** The segment beneath {@link #BASE_PATH} that the credential exchange is served at. */
    public static final String SIGNON_SUBPATH = "/signon";

    /** The segment beneath {@link #BASE_PATH} that the challenge answer is served at. */
    public static final String CHALLENGE_SUBPATH = "/challenge";

    /** The segment beneath {@link #BASE_PATH} that the token renewal is served at. */
    public static final String REFRESH_SUBPATH = "/refresh";

    // WHY : Alternatives Considered: publishing the whole path as one constant here, rather than
    //       importing com.carddemo.auth.config.SecurityConfig and reusing its SIGNON_PATH. Reusing it
    //       would guarantee the route and the permit rule could never drift, but the charter beside
    //       this file closes the set of packages this one may reference at the sibling dto and
    //       service packages plus three shared-kernel packages, and the config package is not among
    //       them; importing it would make an adapter depend on the chain that fronts it. Composing
    //       the full path from the two constants above instead leaves a single value a test can
    //       assert equal to SecurityConfig.SIGNON_PATH and to the path key of the committed
    //       contract, so the alignment is checked by machine rather than compared by eye across
    //       three files, and this class still names no path a second time.
    /** The full path of the credential exchange, as the contract and the filter chain both declare it. */
    public static final String SIGNON_PATH = BASE_PATH + SIGNON_SUBPATH;

    /** The full path of the challenge answer, as the contract and the filter chain both declare it. */
    public static final String CHALLENGE_PATH = BASE_PATH + CHALLENGE_SUBPATH;

    /** The full path of the token renewal, as the contract and the filter chain both declare it. */
    public static final String REFRESH_PATH = BASE_PATH + REFRESH_SUBPATH;

    // WHY : Assumptions: this sentence is the externally visible vocabulary of the refusal below, and
    //       it is reproduced character for character from app/cbl/COSGN00C.cbl:242, where the literal
    //       opens and continues onto :243. Transformation rule T8 of the migration plan, at its
    //       section 0.1.3.2, carries user-visible strings across verbatim because message text is an
    //       external interface rather than prose: a controller test asserts this value byte for byte,
    //       so rewording it -- including changing the single space before the ellipsis -- breaks that
    //       assertion rather than merely reading differently.
    // WHY : Trade-offs: the sentence is re-declared here even though CognitoIdentityService already
    //       holds it, because that field is private and cannot be referenced. Rendering
    //       BadCredentialsException.getMessage() instead would have kept one copy, and it was
    //       rejected: this status must carry this sentence and nothing else, and a refusal raised
    //       anywhere other than that one service method would then render whatever text it happened
    //       to carry. Two literals for one sentence is the cost; a closed vocabulary on an
    //       unauthenticated path is what it buys, and the citation above is what keeps the two
    //       copies checkable against the same line.
    // WHY : Assumptions: no width guard accompanies this value and none is needed. The baseline
    //       renders a message through ERRMSGI, declared PIC X(78) at app/cpy-bms/COSGN00.CPY:84, and
    //       the longest sentence any of the five auth programs emits is forty-four characters, so no
    //       sentence this context produces can reach that bound. A truncation path here would be code
    //       for a condition the source cannot produce.
    static final String MESSAGE_CREDENTIAL_REFUSED = "Wrong Password. Try again ...";

    // WHY : Trade-offs: one line is logged where this class maps a refusal onto a status, matching the
    //       idiom the shared advice uses at its own mapping sites, and it carries the exception class,
    //       the status and the path but neither the sentence above nor any part of the request. A 401
    //       absent from this service's own log cannot be correlated with the status a client saw,
    //       which is what the line buys; what it costs is a second line for one refusal, the service
    //       having already logged the cause it withheld from the caller.
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);

    /** The credential exchange this adapter delegates to. */
    private final CognitoIdentityService identityService;

    // WHY : Assumptions: ApiError does not read the wall clock. Its factory takes a Clock and derives
    //       the timestamp component from it, so the caller supplies time rather than the type
    //       capturing it, and this package must therefore hold one. The shared kernel contributes a
    //       Clock bean guarded by a missing-bean condition, so injecting it here both keeps the
    //       instant consistent with every other problem body this service emits and lets a test fix
    //       the instant instead of asserting around it.
    private final Clock clock;

    /**
     * Builds the adapter over the credential exchange and the clock its problem bodies are stamped from.
     *
     * @param identityService the credential exchange this adapter delegates to; must not be
     *     {@code null}
     * @param clock the time source the refusal body below is stamped from; must not be {@code null}
     * @throws NullPointerException if {@code identityService} or {@code clock} is {@code null}
     */
    public AuthController(CognitoIdentityService identityService, Clock clock) {
        this.identityService =
                Objects.requireNonNull(identityService, "identityService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Exchanges a submitted user identifier and password for the token set the pool issued.
     *
     * <p>Purpose: this is the migrated form of the whole of {@code app/cbl/COSGN00C.cbl}. What the
     * baseline spread across a receive, a presence check, an upper-case fold, a keyed file read and a
     * transfer of control is one call here, because every step after the presence check belongs to the
     * layer beneath. The operation is published unauthenticated, which the committed contract states
     * by declaring {@code security: []} on it: it is the operation that issues a token, so it cannot
     * require one.</p>
     *
     * <p>Assumptions: presence is enforced as a bean constraint on the submitted record rather than
     * by a check written here, and the two sentences it reports are declared on
     * {@code com.carddemo.auth.dto.SignOnRequest} beside the constraint that raises each. Their order
     * is part of the contract and not an accident of iteration: {@code app/cbl/COSGN00C.cbl} opens an
     * {@code EVALUATE TRUE} at {@code :117} whose blank-identifier arm at {@code :118-122} precedes
     * its blank-password arm at {@code :123-127}, and because the construct takes the first matching
     * arm only, a caller submitting an empty screen is told about the identifier and never about both.
     * The record implements the shared kernel's field-ordering contract so that the advice rendering
     * the response sorts the entries the same way, which is what preserves that single-sentence
     * behaviour through a body that can carry two entries. The over-length half of the same status has
     * no baseline analogue at all -- a 3270 field cannot overflow its own declared width -- so it
     * rests on the declared maxima alone.</p>
     *
     * <p>Assumptions: the per-field keys those entries carry are {@code userId} and {@code password},
     * and they are derived from the cursor the baseline positions rather than from any attribute
     * copybook. Each failing arm ends by moving {@code -1} into the length subfield of the field it
     * wants the operator to correct -- {@code USERIDL} at {@code app/cbl/COSGN00C.cbl:121} and
     * {@code PASSWDL} at {@code :126} -- and that is the only per-field attribution the program makes.
     * The templated attribute-highlight copybook one might expect to carry it -- the one that moves a
     * red attribute, and a literal asterisk, into a field whose validation flag is not satisfied -- is
     * not in play here at all: a search across all five programs of this context matches no file for
     * it, nor for the function-key normaliser, the abend-data book or the card-detail book. This
     * program includes eight books and no others, on lines {@code :48}, {@code :50}, {@code :52-55}
     * and {@code :57-58}, with a ninth include commented out on {@code :59}. Deriving the keys from an
     * attribute copybook would therefore have derived them from a file the program never includes.</p>
     *
     * <p>Trade-offs: this method returns the response record itself while the refusal below returns a
     * response entity, and the asymmetry is deliberate rather than an inconsistency. A record return
     * leaves the status to the framework, which is right where exactly one status is possible and is
     * how every sibling adapter in this migration declares its success path; the refusal has to name a
     * status the framework would not otherwise choose, so it declares one. Wrapping this return in an
     * entity as well would add a wrapper to every success for symmetry with a path that answers
     * something different.</p>
     *
     * <p>Refactoring Rationale: the declared return type is the sealed
     * {@link com.carddemo.auth.dto.SignOnOutcome} rather than the token set alone, because the
     * committed document declares this operation's 200 as a choice of two shapes discriminated on
     * {@code outcome}. The narrower type could express only one of them, so the challenge outcome had
     * no representation and was reported as a server fault -- which, since every provisioned account is
     * created with a temporary password, is what every user's first sign-on received. The type is sealed
     * rather than open so the set of shapes this handler can answer with stays closed to the two the
     * document publishes.</p>
     *
     * @param request the submitted identifier and password, bean-validated before this method is
     *     entered so that a blank or over-length field is answered without the pool being consulted;
     *     must not be {@code null}
     * @return either the token set the pool issued or the challenge it raised, whichever the exchange
     *     produced, answered with 200 and told apart by the {@code outcome} member; never {@code null}
     * @throws ClientInputException if a submitted field is absent or blank, which the shared advice
     *     renders as 400 carrying one entry per offending field, identifier first
     * @throws BadCredentialsException if the credential was refused, which
     *     {@link #onRefusedCredential} below renders as 401
     * @throws IllegalStateException if the credential could not be evaluated at all, which the shared
     *     advice renders as 500 carrying the baseline sentence for that outcome
     */
    @PostMapping(path = SIGNON_SUBPATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public SignOnOutcome signOn(@Valid @RequestBody SignOnRequest request) {

        // WHY : Assumptions: the submitted values are handed on exactly as received, because
        //       normalisation is the service's to perform and not this adapter's to anticipate. The
        //       baseline folded BOTH fields to upper case before comparing, at
        //       app/cbl/COSGN00C.cbl:132-136 -- the identifier into two targets on :132-134 and the
        //       password into WS-USER-PWD on :135-136 -- which made its comparison case-insensitive in
        //       the password as well as the identifier. Only the identifier's fold survives, and
        //       folding a password here to restore the rest would weaken the credential in the one
        //       place this migration publishes without a token; the divergence is documented on the
        //       service method that owns the exchange.
        return this.identityService.authenticate(request);
    }

    /**
     * Sets the permanent password a sign-on challenge asked for and returns the token set it unlocks.
     *
     * <p>Purpose: this completes a sign-on that answered {@code outcome CHALLENGE} with
     * {@code challengeName NEW_PASSWORD_REQUIRED}. It is the operation without which no account the
     * infrastructure provisions could ever obtain a token, because every such account is created with a
     * temporary password and a temporary password always raises that challenge on first use.</p>
     *
     * <p>Assumptions: it is published unauthenticated, which the committed document states by declaring
     * {@code security: []} on it and which the edge already assumes -- the path is one of the three
     * unauthenticated route keys the gateway module declares. A caller answering a challenge holds no
     * token, that being what the exchange exists to obtain.</p>
     *
     * <p>Assumptions: this operation has no baseline counterpart, so none of the three sign-on sentences
     * is reused for its failures and no message literal is transcribed for it. The reference compared a
     * stored eight-character credential directly at {@code app/cbl/COSGN00C.cbl:211-256} and had no
     * notion of a credential that must be changed before use. The divergence is registered as
     * {@code D-PASSWORD-CHALLENGE} in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Trade-offs: it is idempotent in neither direction and must not be retried blindly. The session
     * is single-use, so a repeat with the same value is refused and a fresh sign-on is required; the
     * committed document says so on the operation, and the refusal below is what a repeat receives.
     * Making it retryable would mean holding the session server-side and reissuing the token set for a
     * second presentation of it, which is the session storage that ending the pseudo-conversational
     * design removed.</p>
     *
     * @param request the identifier the challenge was raised for, the session it issued and the
     *     permanent password to set, bean-validated before this method is entered; must not be
     *     {@code null}
     * @return the token set the pool issued once the password was accepted, always the authenticated
     *     shape and never a further challenge, answered with 200; never {@code null}
     * @throws ClientInputException if a submitted field is absent or blank, or if the pool refused the
     *     proposed password under its own policy, which the shared advice renders as 400
     * @throws CognitoIdentityService.SessionRefusedException if the session was not accepted, which
     *     {@link #onRefusedSession} below renders as 401
     * @throws IllegalStateException if the exchange could not be evaluated at all, which the shared
     *     advice renders as 500 carrying the baseline sentence for that outcome
     */
    @PostMapping(path = CHALLENGE_SUBPATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public SignOnResponse answerSignOnChallenge(
            @Valid @RequestBody SignOnChallengeRequest request) {

        // WHY : Assumptions: the submitted values are handed on exactly as received, for the reason the
        //       sign-on handler records: normalisation is the service's to perform. It matters more here
        //       than there, because the value being set is stored and every later sign-on compares
        //       against it -- altering it here would set a password the caller did not type and would
        //       lock the account out from the caller's own point of view.
        return this.identityService.answerChallenge(request);
    }

    /**
     * Renews an expiring token set from the refresh token a previous sign-on returned.
     *
     * <p>Purpose: this exists so a session outlives one access-token lifetime without the user
     * re-entering a credential. The baseline had no counterpart, because a sign-on under the transaction
     * monitor lasted as long as the terminal session did.</p>
     *
     * <p>Assumptions: it is published unauthenticated, and the reason is specific rather than a
     * relaxation: the token it would carry is the one being renewed, and a caller whose access token has
     * already expired must still be able to renew. Authority comes from the refresh token, which the
     * pool verifies and which a caller cannot forge.</p>
     *
     * <p>Assumptions: the renewed body carries a null renewal token, because the pool does not reissue
     * one -- the caller keeps the token it already holds. The response shape declares that member
     * nullable for exactly this reason, which is what lets one shape serve all three operations of this
     * tag rather than a second nearly identical shape existing for this path alone.</p>
     *
     * @param request the identifier the token set was issued for and the refresh token to renew it
     *     with, bean-validated before this method is entered; must not be {@code null}
     * @return the renewed token set, carrying a new access token and identity token and a null renewal
     *     token, answered with 200; never {@code null}
     * @throws ClientInputException if a submitted field is absent or blank, which the shared advice
     *     renders as 400
     * @throws CognitoIdentityService.SessionRefusedException if the refresh token was not accepted,
     *     which {@link #onRefusedSession} below renders as 401
     * @throws IllegalStateException if the renewal could not be evaluated at all, which the shared
     *     advice renders as 500 carrying the baseline sentence for that outcome
     */
    @PostMapping(path = REFRESH_SUBPATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public SignOnResponse refreshTokens(@Valid @RequestBody TokenRefreshRequest request) {
        return this.identityService.refresh(request);
    }

    /**
     * Renders a refused session or refresh token as the 401 the two contracts declare for them.
     *
     * <p>Refactoring Rationale: this is a second handler rather than a widening of the credential one
     * below, because the two statuses carry different sentences and the credential handler renders
     * exactly one fixed sentence by design. The reference's {@code 'Wrong Password. Try again ...'}
     * would be actively misleading on either of these two paths: on the challenge answer the credential
     * was accepted -- the challenge is what proves it -- and on the renewal no credential was presented
     * at all. Rendering the message the refusal happened to carry would have kept one handler and was
     * rejected for the reason recorded below: it would let any refusal raised anywhere put its own text
     * on an unauthenticated 401. Two handlers, each mapping one closed type onto one fixed sentence,
     * keeps that property while telling the two situations apart.</p>
     *
     * <p>Assumptions: the narrower type is matched in preference to its supertype because the framework
     * selects the most specific declared handler for the thrown type, so no ordering between the two
     * methods is relied on and neither shadows the other. If this handler were ever removed the refusal
     * would fall to the credential handler rather than to a 500, which is why the refusal type extends
     * that one.</p>
     *
     * <p>Assumptions: no field entry accompanies the body, matching what both contracts state. Every
     * reason either exchange can be refused for -- an expired, already-used, altered or mismatched
     * session, and an expired, revoked or mismatched refresh token -- reaches this one status with this
     * one sentence, because the remedy is identical in all of them and naming which one applied would
     * tell an unauthenticated caller a fact about the pool's state.</p>
     *
     * @param failure the refusal the service raised; its class is logged and neither its message nor
     *     any other part of it reaches the body
     * @param request the request being answered, read only for the path recorded in the body
     * @return the 401 response carrying the shared problem shape, the sign-on-again sentence and no
     *     field entry; never {@code null}
     */
    @ExceptionHandler(CognitoIdentityService.SessionRefusedException.class)
    public ResponseEntity<ApiError> onRefusedSession(
            CognitoIdentityService.SessionRefusedException failure, HttpServletRequest request) {

        LOG.warn("event=api.session.refused code={} status=401 path={} exception={}",
                ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED, request.getRequestURI(),
                failure.getClass().getName());

        // WHY : Assumptions: the code is the same unauthenticated code the credential refusal and the
        //       filter chain's own 401 use, so a client parsing the code field needs no second branch to
        //       tell one 401 of this service from another. What distinguishes them for a HUMAN is the
        //       sentence; what a machine acts on is identical in both, because the required action is
        //       identical: obtain a token.
        // WHY : Assumptions: the path is recorded as received rather than narrowed, as on the credential
        //       refusal. Both of the paths that reach this handler are fixed values with no variable
        //       segment, so there is nothing in either to withhold.
        ApiError body = ApiError.of(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED,
                CognitoIdentityService.MESSAGE_SESSION_REFUSED, HttpStatus.UNAUTHORIZED.value(),
                correlationId(), request.getRequestURI(), this.clock);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Renders a refused credential as the 401 the committed contract declares for this operation.
     *
     * <p>Alternatives Considered: leaving this refusal to the shared advice, which is where every
     * other failure of this operation is rendered. It does not answer this one. The advice in
     * {@code com.carddemo.common.error.GlobalExceptionHandler} names no authentication failure and no
     * unauthorized status, so a refusal reaching it is matched by its runtime-failure handler and
     * rendered 500 with a generic sentence -- and its 500 path carries a service's own sentence only
     * for the unevaluable-credential type. The shared 401 renderer is not reached either: it is an
     * authentication entry point, and an entry point fires for a refusal the filter chain detects, not
     * for one a handler raised. Architecture rule A4 of
     * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
     * states that asymmetry from the other side, recording that a filter-chain refusal never reaches
     * the shared advice. Mapping it here is therefore the only placement that answers with the
     * declared status, and it is the placement the charter assigns, that charter listing the mapping of
     * an outcome onto an HTTP status among the three concerns this package holds.</p>
     *
     * <p>Alternatives Considered: a package-local {@code @RestControllerAdvice} carrying this mapping.
     * Rejected for the reason the charter records: the shared kernel guards its advice with a
     * missing-bean condition keyed on its own type, so a local advice of another type would stand
     * beside it rather than displace it, and two advices with no declared precedence leave it
     * unpredictable which renders any given failure. A handler declared on this class carries no such
     * ambiguity, because a controller-local handler is preferred over any advice for exceptions raised
     * in that controller, and it narrows the mapping to the one operation that can raise this refusal
     * instead of applying it service-wide.</p>
     *
     * <p>Assumptions: no field entry accompanies the body, and the omission is the point of the
     * status rather than an economy. The service answers a wrong password and an unknown identifier
     * with one sentence because the provisioned pool returns uniform user-existence errors, so
     * attributing the failure to the identifier or to the password would restore by attribution
     * exactly the distinction the merged sentence withholds. The baseline told the two apart, writing
     * {@code 'Wrong Password. Try again ...'} at {@code app/cbl/COSGN00C.cbl:242-243} and
     * {@code 'User not found. Try again ...'} at {@code :249}; only the first is reachable through this
     * operation, the second is returned on no code path, and the divergence is documented on the
     * service and in the committed contract's own description of this status.</p>
     *
     * <p>Trade-offs: the body carries no diagnostic from the refusal it renders -- not the exception's
     * own message, not a provider fault name, and above all not the response and reason codes the
     * baseline showed an operator. Those codes are real in the baseline: the sibling user programs
     * display them outright, at {@code app/cbl/COUSR00C.cbl:608}, {@code :642} and {@code :676}, at
     * {@code app/cbl/COUSR02C.cbl:347} and {@code :384}, and at {@code app/cbl/COUSR03C.cbl:294} and
     * {@code :330}. What is given up is that operator-console detail, which the baseline showed at a
     * terminal inside an enterprise; what is protected is that this operation is the one path this
     * migration publishes without a token, so its audience is an unauthenticated caller at an internet
     * edge and a code naming internal state would be a disclosure the contract does not describe. The
     * correlation identifier below is what replaces it, letting an operator find the cause in the log
     * the service already wrote.</p>
     *
     * @param failure the refusal the service raised; its class is logged and neither its message nor
     *     any other part of it reaches the body, for the reason recorded above
     * @param request the request being answered, read only for the path recorded in the body
     * @return the 401 response carrying the shared problem shape, the refusal sentence and no field
     *     entry; never {@code null}
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> onRefusedCredential(BadCredentialsException failure,
            HttpServletRequest request) {

        LOG.warn("event=api.signon.refused code={} status=401 path={} exception={}",
                ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED, request.getRequestURI(),
                failure.getClass().getName());

        // WHY : Assumptions: the code is the shared kernel's unauthenticated code, taken from the same
        //       constant the filter chain's own 401 uses, so a client parsing the code field cannot
        //       tell a refused credential from a rejected token by shape and needs no second branch to
        //       read one. The factory below also settles severity from the status, which at 401 yields
        //       the warning severity that renderer sets explicitly, so the two bodies agree member for
        //       member rather than only in their status line.
        // WHY : Assumptions: the path is recorded as received and is not narrowed first, unlike the
        //       shared advice's, which masks long digit runs in case a key reached a path. This
        //       operation's path is the fixed value on SIGNON_PATH and carries no variable segment at
        //       all, so there is nothing in it to withhold.
        ApiError body = ApiError.of(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED,
                MESSAGE_CREDENTIAL_REFUSED, HttpStatus.UNAUTHORIZED.value(), correlationId(),
                request.getRequestURI(), this.clock);

        // WHY : Assumptions: the correlation header the contract declares on this response is not set
        //       here, because the shared kernel's correlation filter has already put it on the
        //       response by the time a handler runs. Setting it again would either duplicate the
        //       header or overwrite a value the filter generated when the caller sent none.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Reads the correlation identifier the shared filter recorded for the request being answered.
     *
     * <p>Assumptions: the identifier is read from the logging context rather than from a request
     * header, because the filter accepts a conforming inbound header but generates a value when the
     * caller sends none, and only the context holds whichever of the two is in force. An absent value
     * yields the empty string rather than {@code null}, matching what the shared advice puts on its own
     * bodies, so a client parsing the field never has to distinguish a missing member from an empty
     * one.</p>
     *
     * @return the correlation identifier in force, or the empty string when none was recorded; never
     *     {@code null}
     */
    private static String correlationId() {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        return correlationId == null ? "" : correlationId;
    }
}
