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
import java.util.NoSuchElementException;
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
 * <p>Assumptions: a method-level authorization annotation would be INERT here, which is the reason none
 * is written rather than an omission. Method security is opt-in, and the annotation that installs its
 * interceptor appears nowhere in this repository's services, so a handler-level rule would compile,
 * activate nothing and refuse nobody. The failure mode is what makes this worth recording: a test
 * asserting that an ordinary token is forbidden would still pass, because the filter chain refuses the
 * request anyway, so the annotation would look proven while guarding nothing. The chain is therefore
 * both the only place this rule takes effect and the only place a test can establish it. The predicate
 * it uses must also stay in the AUTHORITY family: the converter in the shared kernel emits the identity
 * provider's group name verbatim with no framework role prefix, so a role-family predicate would search
 * for a prefixed authority nothing produces, match nothing, and answer every administrative request
 * with a refusal while the context started cleanly.
 *
 * <p>Refactoring Rationale: nothing is remembered between requests. The baseline ended its task at every
 * screen turn and carried continuity in one communication area that the terminal handed back --
 * {@code app/cbl/COSGN00C.cbl} declares it across lines 64 to 67 as a byte string whose length depends
 * on what was passed, detects a first entry from that length at line 80, and returns it at lines 98 to
 * 102, naming it on line 100 -- and that single structure decomposes here into three separate
 * mechanisms: the caller's identity arrives as validated token claims, the selected row arrives in the
 * request path, and navigation is the browser client's own route change. The entry-versus-re-entry
 * discriminator at {@code app/cpy/COCOM01Y.cpy} lines 29 to 31 has no counterpart at all, and the
 * coupling that severs is the point: the baseline gated its field highlighting on that flag, so a field
 * could be marked in error only on a re-entry, whereas what a caller sees here is decided solely by the
 * response body it has just received.
 *
 * <p>Assumptions: a field entry is keyed by the caller-visible member name, and which field the baseline
 * blamed is recoverable from where it homed the cursor -- each refusal ends in a {@code MOVE -1} to one
 * field's length, as {@code app/cbl/COUSR01C.cbl} does to {@code USERIDL} on line 265 and to
 * {@code FNAMEL} on line 272. It is NOT recoverable from a highlighting copybook, because no program in
 * this domain copies one: the templated attribute book, the abend-data book and the function-key
 * normaliser are absent from all five of them, each copying exactly eight books and testing the
 * attention identifier against the framework's own constants directly. The keys this contract publishes
 * are therefore the request members themselves -- {@code userId}, {@code firstName}, {@code lastName}
 * and {@code userType} -- plus the one key this adapter originates below, for a parameter the baseline
 * had no field for.
 *
 * <p>Assumptions: no sentence is shortened on its way out, and no truncation path exists here to be
 * tested. The baseline's message field is {@code ERRMSGI PIC X(78)} -- at
 * {@code app/cpy-bms/COUSR00.CPY} line 372, and the same declared width on the other three maps in this
 * domain -- while the longest sentence any of these operations can produce is the 44-character "You are
 * already at the bottom of the page..." of the list browse. Every sentence fits its baseline field with
 * room to spare, so the decision about what to drop from an over-long message never arises and nothing
 * here makes it.
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
     * <p>Alternatives Considered: positioning a page by an ordinal offset, which is the obvious shape and
     * is rejected on a specific defect rather than a preference. Under concurrent insertion the number of
     * rows preceding a position changes between one request and the next, so an offset-paged reader skips
     * and repeats rows -- a row inserted ahead of the cursor pushes an unread row past the window, and a
     * row deleted ahead of it pulls an already-read row back into view. A key that has been read keeps its
     * place in the ordering no matter what is inserted or removed around it. This is a substitution rather
     * than an approximation, because the baseline's browse was already a cursor over keys: it stored the
     * page's last and first key as real key values at {@code app/cbl/COUSR00C.cbl} lines 435 and 389.
     *
     * <p>Refactoring Rationale: no page number is accepted, although the baseline screen genuinely had
     * one. It exists as {@code PAGENUMI PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY} line 60 and as
     * {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COUSR00C.cbl} line 70 -- note that the two
     * disagree on type, the map field being text and the working-storage field numeric -- but it never
     * positioned a read. It is moved to the screen at lines 327 and 376 for display, and its only
     * control-flow use is the guard at line 248 deciding whether an already-at-the-top message is shown.
     * Accepting it would invent positioning the baseline never had, and an absolute ordinal cannot be
     * honoured by a key-positioned query without counting every row ahead of the cursor, which is the
     * enumeration this shape exists to avoid.
     *
     * <p>Assumptions: reaching an end of the list is a SUCCESS. A request that lands on the last page
     * answers 200 with no further page available, and a request that cannot move at all answers 200 with
     * the boundary page unchanged. The baseline distinguished those two situations by message and the
     * contract keeps them distinguishable by response, which is why neither is a 4xx. The two are
     * genuinely distinct and are not merged: the baseline refused a move it could see was impossible
     * before reading anything, at {@code app/cbl/COUSR00C.cbl} line 251 going back and line 273 going
     * forward, and separately reported an end the read itself ran into, at line 603 on a not-found
     * opening the browse, line 637 on end-of-file reading forward and line 671 on end-of-file reading
     * back. Those are five distinct sentences, not three with repeats -- lines 603 and 671 differ from
     * each other -- and the browser client composes whichever of the five applies from this envelope.
     *
     * <p>Assumptions: the cursor's SHAPE is constrained here and nothing else about it is. Whether it
     * authenticates, whether it has expired and which query, subject and direction it was issued for are
     * the sealer's to decide inside the service, which is where a refusal is turned into the field entry
     * the contract promises.
     *
     * <p>Trade-offs: the envelope publishes forward availability only -- there is no backward equivalent
     * beside it -- so a client wanting to know whether it may page back reads whether it is holding a
     * first key at all, an absent one meaning it is already at the beginning. Publishing a second flag
     * would have cost an extra read per page, because the only way to know a preceding row exists is to
     * look for one, and the client already has the cheaper answer in its hand. What is given up is that
     * the two directions are not symmetrical in the envelope, which a reader comparing the four members
     * would otherwise expect.
     *
     * @param cursor the sealed position to continue from, or {@code null} for the first page
     * @param direction {@code next} or {@code previous}, or {@code null} to default to next; meaningful
     *     only alongside a cursor
     * @param principal the authenticated caller, supplied by the framework; the cursors this operation
     *     issues are sealed against its name, so a page issued to one administrator cannot be replayed by
     *     another
     * @return HTTP 200 carrying one page of at most ten summaries in ascending identifier order, with
     *     both boundary cursors and the forward availability indicator; never {@code null}
     * @throws ClientInputException if the supplied cursor cannot be opened, or names a direction other
     *     than the one requested, which the shared advice renders as 400 keyed to the cursor
     * @throws IllegalStateException if the store could not be read, carrying the sentence the baseline
     *     wrote for a failed lookup at {@code app/cbl/COUSR00C.cbl} lines 610, 644 and 678
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
     * <p>Refactoring Rationale: the baseline validated a password field on this screen and this operation
     * has no equivalent refusal, which is a deliberate absence rather than a dropped branch. The baseline
     * tested it blank at {@code app/cbl/COUSR01C.cbl} line 136 and answered "Password can NOT be empty..."
     * at line 138, homing the cursor on {@code PASSWDL} at line 140, because the credential was a column
     * of the record it was about to write. Here the credential is not this service's to hold: the row
     * carries no password column at all and the pool owns the secret, so there is no field to submit, none
     * to validate and none to refuse. The branch has no target analogue and its absence is recorded here
     * so a reader comparing the two validation chains does not read it as an omission.
     *
     * <p>Alternatives Considered: reading first to see whether the identifier is free, then writing. That
     * is rejected on both fidelity and correctness. The baseline did not do it -- this program issues no
     * read at all and exactly one write, keyed on the identifier, detecting a collision purely from the
     * store's own duplicate response -- and a preflight read would open a window between the check and
     * the write in which a concurrent request could take the identifier, so the check would pass and the
     * write would still collide. Writing and letting the primary key decide has no such window, because
     * the constraint is evaluated in the same statement that inserts.
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
     * <p>Purpose: this is the load arm the baseline's update and delete screens each performed for
     * themselves, entered at {@code app/cbl/COUSR02C.cbl} line 143 and at {@code app/cbl/COUSR03C.cbl}
     * line 142.
     *
     * <p>Refactoring Rationale: ONE operation serves both the update view and the delete view, where the
     * baseline had two. Each program read the record independently -- {@code app/cbl/COUSR02C.cbl} issues
     * its read at line 322 from its own {@code READ-USER-SEC-FILE}, and {@code app/cbl/COUSR03C.cbl}
     * issues an identical one at line 269 from a paragraph of the same name at line 267 -- because each
     * was a separate transaction that had to fill its own screen. The two reads returned the same row
     * from the same file by the same key, and the only thing that differed was the screen painted
     * afterwards, which is now the browser client's concern. Publishing two endpoints would therefore
     * have split one read across two paths distinguished by nothing the server does, and a caller could
     * not have said which to use except by naming the screen it intended to draw next.
     *
     * @param userId the identifier of the row to read; must not be blank and at most eight characters
     * @return HTTP 200 carrying the stored row, including the subject reference the list projection
     *     omits; never {@code null}
     * @throws ClientInputException if the identifier is blank, carrying the sentence the baseline wrote
     *     at {@code app/cbl/COUSR02C.cbl} line 148, which the shared advice renders as 400
     * @throws NoSuchElementException if no row carries the identifier, carrying the baseline's
     *     "User ID NOT found..." from {@code app/cbl/COUSR03C.cbl} line 289, rendered as 404
     * @throws IllegalStateException if the store could not be read, carrying the baseline's
     *     "Unable to lookup User..." from {@code app/cbl/COUSR03C.cbl} line 296
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
     * baseline's own behaviour -- its modified test at {@code app/cbl/COUSR02C.cbl} line 236 takes the else
     * arm at line 238 and writes {@code 'Please modify to update ...'} in RED at lines 239 to 241, and the
     * colour is what marks it a rejection rather than advice, since the same field is written neutral at
     * line 338 and green at line 371 in the same program. The comparison itself is the service's, because
     * it needs the stored row. This is dirty detection and not concurrency control: the refusal is that
     * the request asks for no change, so nothing here compares a version, and a caller whose values simply
     * lost a race is not what this status reports.
     *
     * <p>Refactoring Rationale: navigating away does NOT save. The baseline's back key wrote the row on
     * its way out -- {@code app/cbl/COUSR02C.cbl} line 111 selects it and line 112 performs the update
     * before line 119 returns to the previous screen -- so leaving the screen committed whatever had been
     * typed, whether or not the operator meant to keep it. That behaviour is not reproduced. Navigation
     * here is the browser client's route change and reaches no handler at all, so the only way to change a
     * row is to call this operation deliberately. The divergence is documented rather than silently made:
     * a mutation that happens because the operator left a screen cannot be expressed over HTTP without
     * inventing a request the client never sent.
     *
     * @param userId the identifier of the row to change; must not be blank and at most eight characters
     * @param request the validated values the row is to hold; must not be {@code null}
     * @return HTTP 200 carrying the row as stored after the change; never {@code null}
     * @throws ClientInputException if the identifier is blank, if a submitted field is blank, if the user
     *     type is outside the two the column admits, or if the body asks for no change at all, each
     *     carrying the baseline's own sentence and rendered as 400
     * @throws NoSuchElementException if no row carries the identifier, carrying the baseline's
     *     "User ID NOT found..." from {@code app/cbl/COUSR02C.cbl} lines 342 and 343, rendered as 404
     * @throws IllegalStateException if the change could not be written, carrying the baseline's
     *     "Unable to Update User..." from {@code app/cbl/COUSR02C.cbl} lines 386 and 387
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
     * <p>Refactoring Rationale: when this operation fails, the sentence it reports is the baseline's
     * "Unable to Update User..." -- said of a DELETE -- and it is carried across unchanged. The baseline
     * writes exactly that at {@code app/cbl/COUSR03C.cbl} line 332 on a failed delete, and the wording is
     * not this program's own: the literal's home is the update program, at {@code app/cbl/COUSR02C.cbl}
     * lines 386 and 387, and this program was plainly cloned from that one -- the two carry their eight
     * {@code COPY} statements on byte-identical lines 49, 60, 62, 63, 64, 65, 67 and 68. The mismatched
     * verb travelled with the copy. It is documented here rather than smoothed over, because message text
     * is observable output under transformation rule T8 and the baseline is the specification for it.
     *
     * <p>Trade-offs: preserving that wording means a human reading a failed deletion is told an update
     * could not be performed, which is the wrong verb and is worse for that reader than a corrected
     * sentence would be. What preservation buys is that any client asserting on the exact string -- the
     * only kind of assertion available, since these sentences are the contract's error vocabulary --
     * continues to match, and that the target introduces no observable difference the baseline can be
     * diffed against. The reader's confusion is recoverable from this note; a silently reworded external
     * interface is not recoverable at all.
     *
     * <p>Assumptions: the baseline's unrecognised-key prompt has no counterpart here and none is invented.
     * Its else arm moves the shared invalid-key sentence at {@code app/cbl/COUSR03C.cbl} line 128 -- the
     * one place in this whole domain that consumes the shared message book -- and it answers a keystroke
     * the terminal could not interpret. Over HTTP there is no keystroke to misread: a request either
     * addresses a method this path publishes or is refused by the framework before any handler runs, so
     * the condition the sentence described cannot arise and nothing here can raise it.
     *
     * @param userId the identifier of the row to delete; must not be blank and at most eight characters
     * @param confirmed explicit confirmation that the row named in the path is to be destroyed; must be
     *     present and {@code true}
     * @return HTTP 204 with no body, by design
     * @throws ClientInputException if the confirmation is absent or not affirmative on a direct
     *     in-process call, which the shared advice renders as 400 carrying a single entry keyed to the
     *     confirmation, and nothing is deleted
     * @throws NoSuchElementException if no row carries the identifier, carrying the baseline's
     *     "User ID NOT found..." from {@code app/cbl/COUSR03C.cbl} line 325, rendered as 404
     * @throws IllegalStateException if the row could not be deleted, carrying the baseline's line 332
     *     sentence discussed above
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
     * <p>Assumptions: there is exactly ONE duplicate outcome to render, not two. The baseline tests two
     * distinct store responses for it -- a duplicate key at {@code app/cbl/COUSR01C.cbl} line 260 and a
     * duplicate record at line 261 -- but the two fall through to a single shared body at lines 262 to 266
     * and produce one sentence, one highlighted field and one screen. Modelling them as two refusals here
     * would invent a distinction the baseline never exposed and would oblige this contract to say which of
     * the two a caller received, which the baseline's own operator could not tell either.
     *
     * <p>Trade-offs: the store's own diagnostic codes are not carried into this body. The baseline printed
     * them to the operator console on every unexpected response -- {@code app/cbl/COUSR00C.cbl} lines 608,
     * 642 and 676, {@code app/cbl/COUSR02C.cbl} lines 347 and 384, {@code app/cbl/COUSR03C.cbl} lines 294
     * and 330 -- and that detail is genuinely useful when diagnosing a failure, so dropping it from the
     * response costs something real. It is dropped because a console is read by an operator inside the
     * system boundary whereas this body is read by whoever called, and a response code naming the store's
     * internal condition tells an unauthenticated-until-now caller how the store is built and which of its
     * constraints it just met. The diagnostic is kept where the console kept it, in this service's log,
     * where the correlation identifier on both sides joins the two records.
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
