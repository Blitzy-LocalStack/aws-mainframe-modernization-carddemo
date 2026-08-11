package com.carddemo.authorization.api;

import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.authorization.service.FraudMarkingService;
import com.carddemo.common.web.CursorToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one write surface of the pending credit-card authorization context: an authorization's fraud state.
 *
 * <p><strong>Purpose.</strong> Bind, validate and delegate
 * {@code PUT /api/v1/authorizations/&#123;key&#125;/fraud}, published as {@code setAuthorizationFraudState}
 * by {@code src/main/resources/openapi/authorization-api.yaml}. It is the only operation that context
 * publishes which changes state, and the only one carrying a route rule of its own. This class holds
 * validation and HTTP mapping and nothing else: every rule transcribed from the reference COBOL lives in
 * {@link FraudMarkingService}.
 *
 * <p>It migrates the caller half of {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl}, the
 * reference fraud-state writer, reached from the detail screen
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}. The reference pair is read as specification
 * and is never modified.
 *
 * <h2>Why the fraud route is its own controller</h2>
 *
 * <p>Refactoring Rationale: the fraud route is its own controller rather than a method on the read
 * controller beside it, even though the reference system reaches the fraud program from INSIDE transaction
 * {@code CPVD} and so treats detail and fraud as one screen's work -- {@code cbl/COPAUS1C.cbl} reaches it
 * by {@code EXEC CICS LINK} at L248 to L252. The deciding factor is the authority rather than the
 * grouping: this is the only route that changes state, so giving it its own type lets the route matrix in
 * {@code config/SecurityConfig.java} name one class-level path prefix instead of singling out a single
 * method inside a mixed controller, where a later edit could add a second method under the same prefix and
 * inherit an authority nobody intended for it. A deployment narrowing the write can then do so without
 * narrowing the read, which a mixed controller would not allow. The cost accepted is one more small type
 * than the reference screen count suggests.
 *
 * <h2>The authority rule this route carries, and the baseline structure behind it</h2>
 *
 * <p>Refactoring Rationale: this route is gated, and the reference structure it replaces gates nothing at
 * the resource or command level. All three transaction definitions in
 * {@code app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd} carry {@code RESSEC(NO) CMDSEC(NO)} -- at L46
 * for {@code CPVD}, L56 for {@code CPVS} and L66 for {@code CP00} -- so no per-resource and no
 * per-command check ran in the baseline, and reaching the terminal transaction was the whole of the
 * access decision. The target consequently ADDS two controls this path did not previously have: a
 * validated bearer token, and an authority requirement declared for this exact path. Two further
 * properties of the same definitions describe the baseline's data-handling structure rather than this
 * route's behaviour: {@code CONFDATA(NO)} at L45, L55 and L65 sits alongside {@code DUMP(YES)
 * TRACE(YES)} at L44, L54 and L64, so primary account numbers could reach CICS trace output and dumps;
 * and {@code cpy-bms/COPAU01.cpy} L60 declares {@code 02  CARDNUMI  PIC X(16).}, so the detail screen
 * rendered a full sixteen-digit account number. The baseline does that; the target masks to the last four
 * digits and suppresses card verification values, and each such divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The migration adds a path, it does not
 * remove one.
 *
 * <p>Assumptions: WHICH authority this route demands is decided by {@code SecurityConfig.fraudAccess()}
 * and not here, that method being the owner of the route authorization matrix. It grants either business
 * group, which is the authority the baseline grants -- the reference reaches this write from the ordinary
 * user menu, and the administrative program table names {@code COPAUS2C} nowhere -- and the published
 * contract states the same as {@code x-required-authority}. No {@code @PreAuthorize} is declared on this
 * class: a second rule here would either restate the first, and drift from it, or silently contradict it.
 * A narrower rule is one edit to that method, and is a behavioural divergence to be registered and
 * published before it ships rather than asserted in this file.
 *
 * <p>Assumptions: the confirmation step the migrated screen shows before this call is a usability
 * affordance and is NOT authorization. The reference convention it replaces -- re-keying to confirm --
 * was likewise a terminal affordance with no server-side rule behind it. Neither stops anything except an
 * accidental click, so neither may be read as a control, and the gate above is the only thing that is one.
 *
 * <p>Assumptions: masking the account number and suppressing the card verification value are the work of
 * {@code com.carddemo.authorization.mapper}, which is the single place copybook representation concerns
 * are permitted to appear. This class re-implements neither. That placement is what makes the guarantee
 * hold: a field cannot escape masking by being serialised from somewhere else, because there is nowhere
 * else for it to be serialised from.
 *
 * <h2>Three fields share the characters F and R, and none of them share a type</h2>
 *
 * <p>Assumptions: {@code 'F'} carries three unrelated meanings across the two reference programs, and the
 * two that meet in this class are deliberately kept in separate types. {@code WS-FRD-ACTION} at
 * {@code cbl/COPAUS2C.cbl} L80 is a REQUESTED ACTION, where L81 declares {@code 'F'} as report fraud and
 * L82 declares {@code 'R'} as remove it; the immediately adjacent {@code WS-FRD-UPDATE-STATUS} at L83 is
 * an OUTCOME, where L84 declares {@code 'S'} as success and L85 declares the same character {@code 'F'}
 * as failed; and {@code PA-AUTH-FRAUD} at {@code cpy/CIPAUDTY.cpy} L50 is the STORED STATE, where L51
 * declares {@code 'F'} as confirmed and L52 declares {@code 'R'} as removed. Nothing in the reference is
 * ambiguous about this, because a COBOL condition name tests the field it was declared under; the hazard
 * belongs entirely to the migration, and it arises only if two of the three are collapsed into one target
 * type. {@link FraudMarkRequest}'s action and {@link FraudMarkResponse}'s update status are therefore
 * separate components of separate types, and this class never assigns one to the other -- a single type
 * spanning both domains would let a request to report fraud be read as a failed update, and the reverse.
 *
 * <h2>Four message widths circulate in this module, and this route uses one</h2>
 *
 * <p>Assumptions: the message this route returns is bounded at 50 characters, because
 * {@code WS-FRD-ACT-MSG PIC X(50)} declares 50 positions at {@code cbl/COPAUS2C.cbl} L86. That is the
 * width of the communication area the reference fraud writer reports through, and it is the width
 * {@link FraudMarkResponse} carries. It is not the width of the screen: {@code cpy-bms/COPAU01.cpy}
 * declares its message field as {@code PIC X(78)} at L180 and L344, fed from {@code WS-MESSAGE PIC X(80)}
 * at {@code cbl/COPAUS1C.cbl} L37 and so truncated by two characters on the way to the terminal. And it
 * is not the house contract either: the 75-character {@code CCARD-ERROR-MSG} and {@code CCARD-RETURN-MSG}
 * of {@code app/cpy/CVCRD01Y.cpy} L28 to L29 DOES NOT APPLY to this module at all, that copybook being
 * included by none of its programs. Sizing this route's message from the 78-character or 75-character
 * regime would accept a value 28 or 25 characters longer than the field it migrates can hold.
 *
 * <h2>Statelessness: what replaced the passed structure</h2>
 *
 * <p>Assumptions: this class holds no session. The reference is pseudo-conversational, so continuity
 * between screen turns travels in {@code app/cpy/COCOM01Y.cpy}, whose {@code 01 CARDDEMO-COMMAREA} opens
 * at L19 and whose data ends at L44, and that one structure decomposes into four mechanisms of which none
 * is server-side session state. Its navigation fields at L21 to L24 and L43 to L44 become the browser's
 * own history, so no field here names a next program. Its identity fields at L25 to L28 become claims on
 * a validated token, which is a change of trust and not merely of carrier: the communication area is
 * storage the terminal echoes back, so a caller could in principle assert its own value of
 * {@code CDEMO-USER-TYPE} at L26 with its admin and user conditions at L27 and L28, whereas a group claim
 * is signed and a caller cannot assert anything. Its selection context -- {@code CDEMO-CUST-ID} at L33,
 * {@code CDEMO-ACCT-ID} at L38 and {@code CDEMO-CARD-NUM} at L41 -- becomes the request path, which is
 * what makes each request self-describing and independently authorizable. And its re-entry discriminator
 * {@code CDEMO-PGM-CONTEXT} at L29 to L31 disappears outright.
 *
 * <p>Assumptions: that last removal severs a coupling worth naming, because it is why nothing in this
 * class tracks whether a caller has been here before. The reference field-highlight template
 * {@code app/cpy/CSSETATY.cpy} gates its whole effect on re-entry at L20, inside the block at L18 to L27
 * that moves the error colour at L21 to L22 and the blank marker at L23 to L25. With no re-entry state to
 * gate on, error presentation is driven purely by the response body. No {@code HttpSession} and no
 * {@code @SessionAttributes} appear here, and the extended area {@code cbl/COPAUS1C.cbl} declares for
 * {@code CPVD} at L109 to L120 has no target counterpart at all.
 *
 * <p>Assumptions: that statelessness is what lets any task behind the load balancer serve this route,
 * with no sticky session and no shared session store. Caching anything request-scoped in an instance field
 * would break the property silently, because one task would still answer every request correctly.
 *
 * <h2>What this class refuses to hold</h2>
 *
 * <p>Assumptions: this class touches no entity, injects no repository or {@code EntityManager}, and
 * declares no transaction boundary. That matters more here than on any read route in the package: the
 * write spans two tables and the reference commits them together, taking its syncpoint at
 * {@code cbl/COPAUS1C.cbl} L557 to L558 and rolling back at L565 to L567, so a transaction opened from
 * this layer would fragment a commit that has to stay whole. The single boundary lives on
 * {@code FraudMarkingService.mark(...)}, and a rollback reaches this layer as a propagated exception
 * rather than as a return value to inspect.
 *
 * <p>Refactoring Rationale: this class is deliberately NOT annotated {@code @Validated}, for the reason
 * recorded on {@link PendingAuthController}: the annotation switches parameter validation onto an AOP
 * proxy raising an exception the shared advice declares no handler for, so every refusal would be
 * answered as HTTP 500. The consequence is sharper on this route than on the read routes, because the
 * framework routes a {@code @Valid} request BODY through the same mechanism once any parameter of the
 * method carries a constraint -- which the sealed path selector does -- so the annotation would have
 * turned a fraud action outside its two-character domain into a server fault as well.
 *
 * <p>Assumptions: this route carries no exemption from the online-write gate, and the omission is the
 * point. {@code OnlineWriteGateInterceptor} treats every non-safe method as a write, so a {@code PUT} is
 * gated with no annotation needed, and the gate is the migrated form of the operator quiesce the
 * reference performs around its batch window. A read expressed as {@code POST} needs the exemption the
 * sibling read controller carries; a genuine write must not have it.
 */
@RestController
@RequestMapping(path = FraudController.FRAUD_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class FraudController {

    /**
     * The subresource path this controller is mounted at.
     *
     * <p>Assumptions: it corresponds exactly to the {@code FRAUD_PATH_PATTERN} literal that
     * {@code config/SecurityConfig.java} gates with its own rule, differing only in that the gate uses a
     * single-segment wildcard where this declares the template variable. The two are written literally on
     * both sides for the stated reason that a path gate matching nothing fails open, so a computed path
     * shared between them would remove the very discrepancy a reader is meant to be able to see.
     */
    public static final String FRAUD_PATH = "/api/v1/authorizations/{key}/fraud";

    /**
     * The write behaviour, which owns the transaction boundary and both writes.
     */
    private final FraudMarkingService marking;

    /**
     * Builds the controller over the fraud-marking service.
     *
     * <p>Assumptions: this is the SOLE collaborator. The reference screen program reaches exactly one
     * other program to perform this work, naming it in {@code WS-PGM-AUTH-FRAUD PIC X(08) VALUE
     * 'COPAUS2C'} at {@code cbl/COPAUS1C.cbl} L35, so one delegate here matches one linked program there.
     * Nothing else in the module is wired in: the message consumer, the outbox publisher, the purge job
     * and the load and unload utilities are all reached by their own entry points and none of them
     * participates in this route.
     *
     * <p>Alternatives Considered: issuing an HTTP call to a separately deployed fraud service, which is
     * how the reference boundary would translate if {@code EXEC CICS LINK} were read as a remote call.
     * Rejected, and the consequence is concrete rather than stylistic: it would split one atomic unit of
     * work across two deployables, reintroducing the distributed commit that collapsing the reference IMS
     * segment and Db2 table into one PostgreSQL schema exists to eliminate. The reference itself argues
     * against the remote reading. {@code csd/CRDDEMO2.csd} L32 defines {@code PROGRAM(COPAUS2C)} and gives
     * it {@code TRANSID(CPVD)} at L36, yet that file's only three {@code DEFINE TRANSACTION} stanzas are
     * {@code CPVD} at L39 to L40 over {@code COPAUS1C}, {@code CPVS} at L49 to L50 over {@code COPAUS0C}
     * and {@code CP00} at L59 to L60 over {@code COPAUA0C} -- so no transaction names the fraud writer,
     * and a terminal could never start it. It runs inside its caller's task by {@code EXEC CICS LINK} at
     * L248 to L252, which is an in-process call, and transformation rule T5 maps it as one. What the
     * remote shape would buy is independent deployability of a program the baseline never exposed
     * independently; what it would cost is the atomicity of a commit the baseline holds whole.
     *
     * @param marking the fraud-marking service; must not be {@code null}
     * @throws NullPointerException if {@code marking} is {@code null}
     */
    public FraudController(FraudMarkingService marking) {
        this.marking = Objects.requireNonNull(marking, "marking must not be null");
    }

    /**
     * Sets the fraud state of one pending authorization to the state the body names.
     *
     * <p><strong>Purpose.</strong> This is the HTTP face of the reference function-key branch that marks
     * an authorization. In {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}, paragraph
     * {@code MAIN-PARA} at L157 to L206 dispatches on the attention identifier and takes
     * {@code WHEN DFHPF5} at L187 to {@code PERFORM MARK-AUTH-FRAUD} at L188, followed unconditionally by
     * {@code PERFORM SEND-AUTHVIEW-SCREEN} at L189. {@code MARK-AUTH-FRAUD} at L230 to L266 prepares the
     * area and links the writer, whose own {@code MAIN-PARA} begins at {@code cbl/COPAUS2C.cbl} L89, and
     * on success {@code UPDATE-AUTH-DETAILS} at L520 to L552 replaces the detail segment. The remaining
     * paragraphs of that flow have no counterpart on this route and are named so their absence is
     * legible: {@code PROCESS-ENTER-KEY} at L208 to L228 and {@code SEND-AUTHVIEW-SCREEN} at L373 to L396
     * are screen composition, which a JSON body replaces, while {@code TAKE-SYNCPOINT} at L557 and
     * {@code ROLL-BACK} at L565 are the transaction boundary, which belongs to the service.
     *
     * <h2>The operation sets a target state; it is not a one-way mark</h2>
     *
     * <p>Alternatives Considered: a one-way mark -- {@code POST /api/v1/authorizations/&#123;key&#125;/fraud}
     * with no body, meaning "flag this as fraudulent". Rejected because it cannot express the removal at
     * all. {@code WS-FRD-ACTION PIC X(01)} at {@code cbl/COPAUS2C.cbl} L80 admits {@code 'F'} for
     * {@code WS-REPORT-FRAUD} at L81 AND {@code 'R'} for {@code WS-REMOVE-FRAUD} at L82, and L137 moves
     * whichever arrived straight into the fraud column, so a verb carrying no state has no way to say
     * {@code 'R'}. The reference exercises both directions: {@code MARK-AUTH-FRAUD} inverts the stored
     * state at L236 to L242, setting the removed condition when the row was confirmed and the confirmed
     * condition otherwise, and {@code UPDATE-AUTH-DETAILS} then words its confirmation on the resulting
     * state, testing {@code IF PA-FRAUD-REMOVED} at L534. Under a one-way verb the removal path at L534 to
     * L535 would be unreachable, so an operator who mis-flagged an authorization would have no way back.
     * Carrying the target state in the body also makes the request idempotent, which matters over a
     * network the reference did not have: a retry that arrives after the first attempt succeeded leaves
     * the same state behind, whereas a verb meaning "invert it" would toggle twice.
     *
     * <p>Assumptions: the body carries the target state and nothing else, the sealed selector in the path
     * being the whole address of the row. The reference passes a 272-byte area -- 11 for
     * {@code WS-ACCT-ID} at L75, 9 for {@code WS-CUST-ID} at L76, 200 for the {@code COPY CIPAUDTY}
     * segment at L78, and 1, 1 and 50 for the group opened at L79 -- but its caller is a sibling program
     * inside one CICS task, whereas this caller is a browser. The identifiers are therefore resolved
     * server-side from the row being marked rather than accepted from the request, so no naming a client
     * supplies can disagree with the selector.
     *
     * <h2>Success and failure are distinct responses, not one shape with a flag</h2>
     *
     * <p>Assumptions: the reference is asymmetric between its two outcomes, and the asymmetry is
     * reproduced as two different responses rather than as one body carrying an outcome flag. In
     * {@code UPDATE-AUTH-DETAILS} the success arm at L532 takes the syncpoint at L533 and selects a
     * message at L534 to L538 and DOES NOT re-send the screen; the failure arm at L539 rolls back at L540,
     * raises the error flag at L542, composes a diagnostic at L544 to L549 and DOES send the screen at
     * L550. Because the function-key caller already sends the screen unconditionally at L189, the failure
     * path emits it TWICE -- L550 and then L189 -- while success emits it once. That double send is a CICS
     * screen-flow artifact with no HTTP analogue whatsoever, and it is deliberately dropped rather than
     * modelled: a response is emitted once per request, and nothing about repeating it would carry
     * information. What survives is the branch itself. Success returns this body with a 2xx status;
     * failure returns no body of this type at all, but a non-2xx
     * {@code com.carddemo.common.error.ApiError} raised from the service and rendered centrally by
     * {@code com.carddemo.common.error.GlobalExceptionHandler}. A 2xx envelope carrying a body that says
     * the write failed would be a contradiction a client cannot resolve, and the reference has no such
     * ambiguity to preserve because it has no status code -- L253 to L258 tests the outcome condition and,
     * when it does not hold, moves the failure sentence into the message line and rolls back, so a failed
     * write reaches the operator as an error screen and never as a confirmation.
     *
     * <p>Assumptions: {@code MOVE 'Y' TO WS-ERR-FLG} at L542 is the reference analogue of the house
     * not-ok validation flag, and it is raised ONCE for the whole fraud write rather than per field.
     * Its target form is therefore exactly one entry in the per-field array that
     * {@code com.carddemo.common.error.ApiError} carries, described by
     * {@code com.carddemo.common.validation.FieldValidationFlag} -- whose blank state additionally
     * contributes the literal {@code '*'} marker the reference template moves into an empty field, exposed
     * there as {@code BLANK_SCREEN_MARKER}. Neither type is re-declared here, per transformation rule T2,
     * and neither is constructed here: this method raises nothing itself, and both the validation refusal
     * and the write failure are rendered by the shared advice.
     *
     * <p>Assumptions: mapping an exception to a status is the shared advice's job and is not duplicated on
     * this route. A selector naming no row surfaces as 404, a body outside the action domain as 400 with
     * the per-field array, a conflicting concurrent change as 409 rather than as a raw database error, and
     * a quiesced write window as 503. An unrecoverable failure surfaces as 500 through the structured abend
     * detail whose four components the reference declares in {@code app/cpy/CSMSG02Y.cpy} -- the code, the
     * culprit, the reason and the message, spanning L21 to L29 of that 35-line copybook, which
     * {@code cbl/COPAUS1C.cbl} includes at L135. Nothing on this route composes any of those bodies.
     *
     * <h2>The status code carries the insert-versus-update distinction</h2>
     *
     * <p>Assumptions: the reference writer is an insert that falls back to an update, so it reports FOUR
     * distinct sentences and the two successes are worded differently. Its insert arm sets the success
     * condition and {@code 'ADD SUCCESS'} at L200 to L201; a duplicate key, tested as
     * {@code SQLCODE = -803} at L203, diverts at L204 to the update arm, which sets {@code 'UPDT SUCCESS'}
     * at L232; and each arm has its own failure sentence, {@code ' SYSTEM ERROR DB2: CODE:'} at L211 with
     * {@code ', STATE: '} at L212, and {@code ' UPDT ERROR DB2: CODE:'} at L239 with {@code ', STATE: '}
     * at L240. A caller must be able to tell a first-time mark from a re-mark, so that distinction is
     * carried on the status: 201 when the fraud row was created, 200 when an existing one was replaced.
     * The two failure sentences are diagnostic detail about this service's own store and are not published
     * to a caller; they are retained for the operator in the failure log.
     *
     * <p>Assumptions: the distinction is read from the service's own carrier flag and NEVER inferred from
     * the sentence in the body. Inferring it would make a user-visible string load-bearing, and
     * transformation rule T8 carries those strings across for display and for nothing else, so a future
     * edit to one would silently change a status code.
     *
     * <p>Trade-offs: the two successes are distinguished by status while the body stays one shape, which
     * accepts that a client reading the body alone cannot tell them apart. That was preferred to adding a
     * third member, because the published response schema carries exactly the two members the reference
     * area's response direction declares and a third would put the document and the returned record out of
     * agreement. A client that needs the distinction reads the status, which is where HTTP already
     * expresses created-versus-updated.
     *
     * <h2>The verbatim sentences, and how they are keyed</h2>
     *
     * <p>Assumptions: the sentences this flow can produce are carried character for character as
     * transformation rule T8 requires, and their leading spaces are part of them. The writer's two
     * successes, {@code 'ADD SUCCESS'} at {@code cbl/COPAUS2C.cbl} L201 and {@code 'UPDT SUCCESS'} at
     * L232, have NO leading space, whereas its two failure sentences, {@code ' SYSTEM ERROR DB2: CODE:'}
     * at L211 and {@code ' UPDT ERROR DB2: CODE:'} at L239, each carry exactly one. Their common suffix has
     * its own shape: {@code CODE:} is followed immediately by the code with no separating space, and only
     * then comes {@code ', STATE: '} with a trailing space. That is the Db2 form specifically; the
     * repository-wide regime is trimodal, with DL/I errors ending {@code Code:} and file errors ending
     * {@code Resp:}, so the three are not interchangeable. The screen program contributes two more, both
     * without a leading space, selected on the resulting state at {@code cbl/COPAUS1C.cbl} L534 to L537:
     * {@code 'AUTH FRAUD REMOVED...'} at L535 when the report was withdrawn and
     * {@code 'AUTH MARKED FRAUD...'} at L537 when it was made, each ending in three literal periods. Its
     * rollback sentence is {@code ' System error while FRAUD Tagging, ROLLBACK||'} at L545, with one
     * leading space and a trailing double-pipe that separates it from the status code appended after it.
     *
     * <p>Assumptions: the catalog these sentences belong to is keyed by ORIGINATING PROGRAM and not by
     * meaning, because the two programs do not agree on case. {@code cbl/COPAUS2C.cbl} writes in upper
     * case throughout while {@code cbl/COPAUS1C.cbl} writes in mixed case, so the same idea appears as
     * {@code ' SYSTEM ERROR DB2: CODE:'} in one and as a mixed-case sentence in the other. Normalising
     * either toward the other, or folding two near-identical sentences into one entry, would corrupt a
     * user-visible string -- the same hazard rule T8 guards against for the two thank-you strings that
     * differ only in declared width.
     *
     * <p>Assumptions: one reference statement is deliberately NOT carried across.
     * {@code cbl/COPAUS1C.cbl} L523 issues {@code DISPLAY 'RPT DT: ' PA-FRAUD-RPT-DATE} immediately before
     * the segment replace, and that is a leftover developer trace rather than user-visible output, so it
     * has no place in a response. The report date it prints remains part of the record --
     * {@code PA-FRAUD-RPT-DATE PIC X(08)} at {@code cpy/CIPAUDTY.cpy} L53, written under the mask
     * {@code 'YY-MM-DD HH24.MI.SSNNNNNN'} the writer applies at {@code cbl/COPAUS2C.cbl} L228 -- and is
     * returned by the detail route rather than by this one.
     *
     * @param key the sealed selector taken from the {@code key} property of a list row; must not be blank
     *     and must be no longer than a sealed token may be
     * @param request the fraud state to set, and nothing else -- the sealed selector is the whole address
     *     of the row; must not be {@code null} and is validated against its published domain before this
     *     method runs
     * @param principal the authenticated caller, supplied by the framework; the selector is redeemed
     *     against its name inside the service, so a selector issued to another operator is refused rather
     *     than honoured
     * @return HTTP 201 with the insert sentence when the fraud row was created, or HTTP 200 with the
     *     update sentence when an existing row was replaced; never {@code null}
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FraudMarkResponse> setFraudState(
            @PathVariable(name = "key")
            @NotBlank
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String key,
            @Valid @RequestBody FraudMarkRequest request,
            Principal principal) {

        // WHY : Assumptions: the caller's name is passed as an argument rather than read from a security
        //       context inside the service, because the selector is bound to the subject it was issued to
        //       and the service redeems it against that name. Taking the identity from the request's own
        //       principal keeps this class the single place identity crosses the HTTP boundary, which is
        //       what replaced the reference's echoed-back identity fields.
        FraudMarkingService.FraudMarkOutcome outcome =
                this.marking.mark(key, request, principal.getName());

        // WHY : Assumptions: the status is selected from the outcome's own carrier flag, which reports
        //       whether the fraud row was inserted or replaced -- the target form of the -803 duplicate-key
        //       branch the reference writer takes at cbl/COPAUS2C.cbl L203. The body is returned exactly as
        //       the service composed it, so the two verbatim reference sentences reach the caller
        //       unaltered; this method neither reads nor rewrites them.
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.body());
    }
}
