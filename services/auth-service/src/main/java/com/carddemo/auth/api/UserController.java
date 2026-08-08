package com.carddemo.auth.api;

import com.carddemo.auth.dto.CreateUserRequest;
import com.carddemo.auth.dto.UpdateUserRequest;
import com.carddemo.auth.dto.UserResponse;
import com.carddemo.auth.dto.UserSummary;
import com.carddemo.auth.service.UserService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the five user-administration operations migrated from {@code COUSR00C} through
 * {@code COUSR03C}.
 *
 * <p>Purpose: this adapter turns each HTTP request into one call on {@link UserService} and turns its
 * outcome into a status code. It holds no business rule: which sentence answers which failure, which
 * values may change, and the order the reference validated them in all belong to the layer beneath,
 * and the package charter beside this file closes this layer's concerns at three -- validating an
 * inbound request as transport, mapping an outcome onto a status, and delegating.
 *
 * <p>Assumptions: every operation here requires the administrative authority, and that requirement is
 * enforced once in the filter chain rather than repeated on these handlers. The chain matches the
 * collection path and the single-user subtree and requires the authority on both before any handler
 * runs, so a caller holding an ordinary token never reaches this class. Annotating each method as well
 * would put the rule in two places, and the copy that went stale would be the one no test exercised.
 *
 * <p>Refactoring Rationale: the five operations sit on one adapter rather than on four, one per
 * reference program. This context owns a single aggregate -- the {@code auth.users} row -- and the five
 * operations are five views of it: the create, the update and the delete all write the row the two
 * reads return. Four adapters would spread one five-operation contract over four files while adding no
 * seam the domain has, and would leave no single file whose shape can be read against the contract of
 * record as a whole. The trade accepted is five handlers in one file, which this layer can hold
 * precisely because it holds no rules.
 *
 * <p>Refactoring Rationale: this class did not exist while the committed contract published all five of
 * its operations, which was not a tidy boundary: the request and response records, the mapper and the
 * repository query methods all existed and had no production caller, and the gateway routed five
 * operations the service answered with 404. Everything the reference reached from its administrative
 * menu was therefore unreachable in the target.
 *
 * <p>Assumptions: the selected user travels in the request PATH on the three single-user operations,
 * which is what replaces the reference's input field on the screen -- {@code USRIDINI PIC X(8)} at
 * {@code app/cpy-bms/COUSR02.CPY} line 60 and {@code app/cpy-bms/COUSR03.CPY} line 60 -- and the
 * selection column on the list screen. Putting it in the path makes every request self-describing and
 * independently authorizable, where the reference carried it in a structure the client held between
 * turns and handed back.
 *
 * <p>Trade-offs: this class carries no springdoc annotation -- no tag, no operation, no response
 * annotation -- because the hand-authored OpenAPI 3.1 document beside this module is the contract of
 * record and settles the paths, the property names, the response shapes and the error vocabulary.
 * Annotating the handlers would put a second, editable copy of the contract in the code and make drift
 * expressible at all. What is given up is that the document springdoc assembles is thinner than the
 * committed one; that is accepted because clients are generated from the committed document. Every
 * sibling adapter in this migration is annotated the same way.
 */
@RestController
@RequestMapping(path = UserController.COLLECTION_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class UserController {

    /** The collection path the list and create operations are served at. */
    public static final String COLLECTION_PATH = "/api/v1/auth/users";

    /** The single-user path template, relative to {@link #COLLECTION_PATH}. */
    public static final String SINGLE_USER_SUBPATH = "/{userId}";

    /**
     * The number of characters a user identifier may carry in a path segment.
     *
     * <p>Assumptions: eight is {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line 18 and
     * the maximum the committed contract declares for the path parameter. It is bounded HERE as well as
     * in the request records because a path segment is not covered by any of them: a nine-character
     * segment would otherwise reach the store as a key that cannot exist, and be answered 404 rather
     * than 400.
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /**
     * The two values the direction parameter admits.
     *
     * <p>Assumptions: the expression is anchored and lists the contract's enumeration exactly. Bean
     * validation matches a pattern against the whole value, so no anchoring metacharacter is needed and
     * none is written; the alternation is the whole domain.
     */
    private static final String DIRECTION_DOMAIN = "next|previous";

    /**
     * The sentence reported when the delete confirmation is absent or not affirmative.
     *
     * <p>Assumptions: this sentence has no reference counterpart, because the safeguard it enforces was
     * a KEYSTROKE in the reference rather than a field. {@code app/cbl/COUSR03C.cbl} lines 283 and 284
     * invite a second, different key -- "Press PF5 key to delete this user ..." -- so displaying a user
     * and destroying one were never the same request. A keystroke cannot survive as a keystroke over
     * HTTP, so the sentence is authored for the target and worded to name the parameter the caller must
     * send.
     */
    private static final String MESSAGE_CONFIRMATION_REQUIRED =
            "Confirm the deletion to delete this user ...";

    /** The key the confirmation refusal is attributed to, being the parameter's own name. */
    private static final String FIELD_CONFIRMED = "confirmed";

    private static final Logger LOG = LoggerFactory.getLogger(UserController.class);

    /** The user-administration operations this adapter delegates to. */
    private final UserService users;

    // WHY : Assumptions: ApiError does not read the wall clock -- its factory takes a Clock and derives
    //       the timestamp from it -- so this package must hold one. The shared kernel contributes a
    //       Clock bean guarded by a missing-bean condition, so injecting it keeps the instant consistent
    //       with every other problem body this service emits and lets a test fix the instant instead of
    //       asserting around it.
    private final Clock clock;

    /**
     * Builds the adapter over the user-administration service and the clock its problem bodies use.
     *
     * @param users the user-administration operations this adapter delegates to; must not be
     *     {@code null}
     * @param clock the time source the conflict body below is stamped from; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public UserController(UserService users, Clock clock) {
        this.users = Objects.requireNonNull(users, "users must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Reads one bounded page of the user list, positioned by key.
     *
     * <p>Purpose: this is the migrated form of {@code app/cbl/COUSR00C.cbl}, whose browse filled a
     * ten-row screen and reported whether another row followed.
     *
     * <p>Assumptions: the page size is not a parameter. Ten is the reference screen's own row count and
     * the committed contract fixes it, so a caller cannot ask for more; the alternative would let one
     * request read the whole table, which is the enumeration keyset paging exists to avoid.
     *
     * <p>Assumptions: reaching an end of the list is a SUCCESS. A request that lands on the last page
     * answers 200 with no further page available, and a request that cannot move at all answers 200 with
     * the boundary page unchanged. The reference distinguished those two by message; the contract keeps
     * them distinguishable by response, and the browser client composes the five reference sentences from
     * the envelope.
     *
     * <p>Assumptions: the cursor's SHAPE is constrained here and nothing else about it is. Whether it
     * authenticates, whether it has expired and which query, subject and direction it was issued for are
     * the sealer's to decide inside the service, which is where a refusal is turned into the field entry
     * the contract promises.
     *
     * @param cursor the sealed position to continue from, or {@code null} for the first page
     * @param direction {@code next} or {@code previous}, or {@code null} to default to next; meaningful
     *     only alongside a cursor
     * @param principal the authenticated caller, supplied by the framework; the cursors this operation
     *     issues are sealed against its name, so a page issued to one administrator cannot be replayed by
     *     another
     * @return HTTP 200 carrying one page of at most ten summaries in ascending identifier order, with
     *     both boundary cursors and the forward availability indicator; never {@code null}
     */
    @GetMapping
    public PageResponse<UserSummary> listUsers(
            @RequestParam(name = "cursor", required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String cursor,
            @RequestParam(name = "direction", required = false)
            @Pattern(regexp = DIRECTION_DOMAIN)
            String direction,
            Principal principal) {

        return this.users.list(cursor, direction, principal.getName());
    }

    /**
     * Creates one user row and answers with the row as stored.
     *
     * <p>Purpose: this is the migrated form of {@code app/cbl/COUSR01C.cbl}.
     *
     * <p>Assumptions: the body carries no password, because this service creates no credential -- the
     * pool generates one and this service never sees it -- and no subject reference, because the service
     * creates the pool account itself and reads the subject back from the provider. Accepting a subject
     * would let a caller choose which pool identity the new row authenticates as, which is the whole of
     * the authorization decision.
     *
     * <p>Assumptions: the success sentence the reference composed -- "User " then the identifier then
     * " has been added ..." across {@code app/cbl/COUSR01C.cbl} lines 255 to 258, the literal on line 257
     * -- is NOT returned. The browser client holds every user-visible string in its own catalogue and
     * composes that sentence from the identifier it receives; a server-rendered sentence would be a
     * second source for one string and the two could disagree. Failure sentences ARE returned, because
     * which branch failed is knowledge only this service has.
     *
     * <p>Assumptions: the location header is built from the collection path and the stored identifier
     * rather than echoed from the request, so it names the row as it was actually keyed. The contract
     * declares the header required and its form absolute-path, which is what this composes.
     *
     * @param request the validated new row's values; must not be {@code null}
     * @return HTTP 201 carrying the stored row and a location header addressing it; never {@code null}
     * @throws UserService.DuplicateUserException if a row already carries the identifier, which
     *     {@link #onDuplicateUser} below renders as 409
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {

        UserResponse created = this.users.create(request);

        return ResponseEntity.created(URI.create(COLLECTION_PATH + "/" + created.userId()))
                .body(created);
    }

    /**
     * Reads the whole of one user row.
     *
     * <p>Purpose: this is the load arm the reference update and delete screens share, at
     * {@code app/cbl/COUSR02C.cbl} line 143 and its counterpart in {@code app/cbl/COUSR03C.cbl}.
     *
     * @param userId the identifier of the row to read; must not be blank and at most eight characters
     * @return HTTP 200 carrying the stored row, including the subject reference the list projection
     *     omits; never {@code null}
     */
    @GetMapping(path = SINGLE_USER_SUBPATH)
    public UserResponse getUser(
            @PathVariable(name = "userId")
            @NotBlank
            @Size(max = USER_ID_MAX_LENGTH)
            String userId) {

        return this.users.read(userId);
    }

    /**
     * Replaces the mutable values of one user row.
     *
     * <p>Purpose: this is the save arm of {@code app/cbl/COUSR02C.cbl}, its {@code UPDATE-USER-INFO}
     * paragraph at line 177.
     *
     * <p>Assumptions: the identifier appears once per request, in the path, and the body carries only
     * what may change. That is why the request record declares no identifier component: a body carrying
     * one could contradict the path, and the contract would then have to arbitrate between two
     * statements of the same fact.
     *
     * <p>Assumptions: a body matching the stored row in every field is refused with 400. That is the
     * reference's own behaviour -- its else branch at {@code app/cbl/COUSR02C.cbl} line 237 writes
     * {@code 'Please modify to update ...'} in RED at lines 239 to 241, and the colour is what marks it a
     * rejection rather than advice, since the same field is written neutral at line 338 and green at line
     * 371 in the same program. The comparison itself is the service's, because it needs the stored row.
     *
     * @param userId the identifier of the row to change; must not be blank and at most eight characters
     * @param request the validated values the row is to hold; must not be {@code null}
     * @return HTTP 200 carrying the row as stored after the change; never {@code null}
     */
    @PutMapping(path = SINGLE_USER_SUBPATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse updateUser(
            @PathVariable(name = "userId")
            @NotBlank
            @Size(max = USER_ID_MAX_LENGTH)
            String userId,
            @Valid @RequestBody UpdateUserRequest request) {

        return this.users.update(userId, request);
    }

    /**
     * Deletes one user row, with the deletion explicitly confirmed.
     *
     * <p>Purpose: this is the destructive arm of {@code app/cbl/COUSR03C.cbl}.
     *
     * <p>Refactoring Rationale: the confirmation parameter reconstructs a safeguard the reference had and
     * HTTP would otherwise lose. The reference made deleting a two-step act: its load arm read the record
     * and then invited a second, different keystroke -- "Press PF5 key to delete this user ..." at
     * {@code app/cbl/COUSR03C.cbl} lines 283 and 284 -- so displaying a user and destroying one were
     * never the same operation. That safeguard cannot survive as a keystroke, and dropping it would mean
     * a prefetch, a retried request or a crawler following a link could destroy a row. Requiring an
     * explicit affirmative parameter rebuilds the two-step act from what one request carries: the caller
     * must have decided to delete in order to have sent it.
     *
     * <p>Alternatives Considered: carrying the confirmation in a request body. Rejected because a body on
     * a delete has no defined semantics and intermediaries are permitted to drop it, so the safeguard
     * could vanish in transit and the deletion would proceed unconfirmed. A required query parameter
     * cannot be silently discarded: if it is missing the request is refused.
     *
     * <p>Assumptions: the value must be affirmative, not merely present, which is what the assert-true
     * constraint states. Accepting any value would let a caller that explicitly sent a negative
     * confirmation delete the row, which is the exact opposite of what it wrote.
     *
     * <p>Alternatives Considered: binding the confirmation as a required primitive, so that an absent
     * parameter is a binding failure rather than a validation failure. Rejected because it answers the
     * wrong status. An unbound required parameter raises a binding failure, and the shared advice claims
     * every unclassified failure for its own catch-all and renders it as 500 -- so a caller that simply
     * omitted the confirmation would be told the service had failed, when in truth its request was
     * refused and nothing was deleted. Binding the parameter optionally and requiring its presence with
     * a constraint instead routes absence and negation down the one path the advice renders as the 400
     * the contract declares, keyed to this parameter in both cases. The cost is two constraints where a
     * primitive needed one, and a boxed value this method must null-check; the contract's declared
     * status is worth both.
     *
     * <p>Assumptions: the parameter is still published as required. The presence constraint is what
     * carries that into the generated document -- a presence constraint on a parameter is read as a
     * requirement -- so declaring the binding optional relaxes when the framework refuses the request,
     * never what the contract states about it. The committed contract remains the published authority
     * and declares it required with a constant affirmative value.
     *
     * <p>Assumptions: the success sentence the reference composed -- "User " then the identifier then
     * " has been deleted ..." across lines 318 to 321, the literal on line 320 -- is not returned, both
     * for the reason the create operation records and because this status carries no body at all.
     *
     * @param userId the identifier of the row to delete; must not be blank and at most eight characters
     * @param confirmed explicit confirmation that the row named in the path is to be destroyed; must be
     *     present and {@code true}
     * @return HTTP 204 with no body, by design
     * @throws ClientInputException if the confirmation is absent or not affirmative on a direct
     *     in-process call, which the shared advice renders as 400 carrying a single entry keyed to the
     *     confirmation, and nothing is deleted
     */
    @DeleteMapping(path = SINGLE_USER_SUBPATH)
    public ResponseEntity<Void> deleteUser(
            @PathVariable(name = "userId")
            @NotBlank
            @Size(max = USER_ID_MAX_LENGTH)
            String userId,
            @RequestParam(name = FIELD_CONFIRMED, required = false)
            @NotNull
            @AssertTrue
            Boolean confirmed) {

        // WHY : Assumptions: the affirmative value is re-checked here as well as by the constraint, and
        //       the redundancy is deliberate rather than belt-and-braces for its own sake. The constraint
        //       is what publishes the rule into the generated document and what refuses the request when
        //       the framework's argument resolution runs; this check is what refuses it when this method
        //       is called directly -- from a test asserting the safeguard, or from any in-process caller
        //       -- where no resolver has run. A destructive operation is the one place a safeguard that
        //       depends on how the call arrived is not good enough.
        // WHY : Assumptions: the refusal names the parameter rather than the row, because the row is not
        //       at fault and the contract states the array carries a single entry keyed to the
        //       confirmation.
        if (confirmed == null || !confirmed) {
            LOG.info("event=api.user.delete-refused reason=unconfirmed field={}", FIELD_CONFIRMED);
            throw new ClientInputException(ApiError.CODE_VALIDATION, FIELD_CONFIRMED,
                    MESSAGE_CONFIRMATION_REQUIRED);
        }

        this.users.delete(userId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Renders a duplicate user identifier as the 409 the committed contract declares for the create.
     *
     * <p>Alternatives Considered: raising the shared kernel's own conflict type and letting the shared
     * advice render it, which is where every other conflict in this migration is rendered. It cannot
     * answer this one. That type carries a fixed sentence chosen from three contention kinds -- a stale
     * version, an unavailable lock, a referenced row -- none of which describes a duplicate primary key
     * and none of whose sentences is the literal this contract publishes. Raising it would answer the
     * declared status with the wrong sentence, which under transformation rule T8 is a defect in observable
     * output rather than a wording preference.
     *
     * <p>Alternatives Considered: a package-local advice carrying this mapping. Rejected for the reason
     * the sign-on adapter records for its own handler: the shared kernel guards its advice with a
     * missing-bean condition keyed on its own type, so a local advice of another type would stand beside
     * it rather than displace it, and two advices with no declared precedence leave it unpredictable which
     * renders any given failure. A handler declared on this class carries no such ambiguity, because a
     * controller-local handler is preferred over any advice for exceptions raised in that controller, and
     * it narrows the mapping to the one operation that can raise this conflict.
     *
     * <p>Assumptions: the sentence is read from the service that decides the conflict rather than
     * re-declared here, so there is exactly one copy of a string the contract publishes verbatim. The
     * refusal type is closed and raised in one place, so nothing else can put its own text on this status.
     *
     * <p>Assumptions: no field entry accompanies the body. The contract declares this status with a
     * message and no field array, and the identifier is not malformed -- it is unavailable, which is a
     * statement about the collection rather than about the value the caller sent.
     *
     * @param failure the conflict the service raised; its sentence is the contract's literal and its
     *     class is logged
     * @param request the request being answered, read only for the path recorded in the body
     * @return the 409 response carrying the shared problem shape and the reference sentence; never
     *     {@code null}
     */
    @ExceptionHandler(UserService.DuplicateUserException.class)
    public ResponseEntity<ApiError> onDuplicateUser(UserService.DuplicateUserException failure,
            HttpServletRequest request) {

        LOG.warn("event=api.user.conflict code={} status=409 path={} exception={}",
                ApiError.CODE_CONFLICT, request.getRequestURI(), failure.getClass().getName());

        // WHY : Assumptions: the body is built through the conflict factory rather than the general one,
        //       because that factory settles the subsystem member as the relational store -- which is what
        //       decided this conflict, the primary key on auth.users -- and the published contract's own
        //       conflict examples show that value. The general factory would emit the application
        //       subsystem, so the body a client parsed would contradict the document describing it.
        // WHY : Assumptions: the field array is EMPTY rather than carrying an entry, which is what the
        //       shared advice's own conflict renderer passes when it has no version to report. The
        //       contract declares this status with a message and no field array, and an entry naming the
        //       identifier would attribute the failure to a value that is well formed.
        ApiError body = ApiError.ofConflict(UserService.MESSAGE_USER_ID_EXISTS,
                ApiError.Subsystem.RELATIONAL, correlationId(), request.getRequestURI(), List.of(),
                this.clock);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Reads the correlation identifier the shared filter recorded for the request being answered.
     *
     * <p>Assumptions: the identifier is read from the logging context rather than from a request header,
     * because the filter accepts a conforming inbound header but generates a value when the caller sends
     * none, and only the context holds whichever of the two is in force. An absent value yields the empty
     * string rather than {@code null}, matching what the shared advice puts on its own bodies, so a client
     * parsing the field never has to distinguish a missing member from an empty one.</p>
     *
     * @return the correlation identifier in force, or the empty string when none was recorded; never
     *     {@code null}
     */
    private static String correlationId() {
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        return correlationId == null ? "" : correlationId;
    }
}
