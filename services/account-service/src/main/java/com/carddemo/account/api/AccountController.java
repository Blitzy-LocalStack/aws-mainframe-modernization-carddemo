package com.carddemo.account.api;

import com.carddemo.account.dto.AccountContextView;
import com.carddemo.account.dto.AccountLookupRequest;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.dto.AccountUpdateValidationResponse;
import com.carddemo.account.dto.AccountViewResponse;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.service.AccountUpdateService;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.control.OnlineWriteGateExempt;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// WHY : Assumptions: the two transactions this adapter answers for are named by
//       app/csd/CARDDEMO.CSD -- CAVW at L317 naming program COACTVWC at L318, and CAUP at L306 naming
//       COACTUPC at L308 -- so the provenance of every rule below is app/cbl/COACTVWC.cbl and
//       app/cbl/COACTUPC.cbl, and neither is restated here.
// WHY : this adapter is where the reference system's pseudo-conversational state is dismantled. Both
//       transactions are defined TWASIZE(0) at app/csd/CARDDEMO.CSD L308 and L318, so no Transaction
//       Work Area existed and every scrap of continuity between screen turns travelled in the
//       160-byte communication area of app/cpy/COCOM01Y.cpy L19 to L44. That single structure is the
//       thing this class replaces, and it does not replace it with one mechanism but with four, each
//       recorded on the class below.

/**
 * Publishes the account operations this context owns, across both of its surfaces.
 *
 * <p><b>Purpose.</b> Two of the operations below are end-user surfaces migrated from named reference
 * programs: the account view of {@code app/cbl/COACTVWC.cbl}, whose three-hop read is driven from
 * {@code 0000-MAIN.} at L262 through the paragraphs at L687, L723, L774 and L825, and the account
 * update of {@code app/cbl/COACTUPC.cbl}. One is the machine read a neighbouring context resolves an
 * authorization against. One is the by-account cross-reference browse the reference reaches through
 * its {@code CXACAIX} alternate index. Each read or write becomes an operation on the published
 * contract of the context that owns the account master.</p>
 *
 * <p>Refactoring Rationale: IDENTITY is taken from validated token claims and never from the request.
 * The reference carries it in the communication area at {@code app/cpy/COCOM01Y.cpy} L25 and L26, whose
 * two conditions at L27 and L28 admit an administrator and an ordinary user, and both programs WRITE
 * that field before transferring control -- {@code app/cbl/COACTVWC.cbl} L344 and
 * {@code app/cbl/COACTUPC.cbl} L947. Because the communication area is storage the terminal echoes
 * back, a caller could present whichever user type it preferred, and {@code app/csd/CARDDEMO.CSD} L314
 * and L324 define both transactions {@code RESSEC(NO) CMDSEC(NO)}, so no resource or command check
 * stood behind it: that echoed field WAS the authorization. Here the claim is signed, so no handler
 * accepts a user identifier or user type as a parameter, header or body member at all.</p>
 *
 * <p>Refactoring Rationale: SELECTION CONTEXT becomes a REQUEST BODY member, and not a path parameter.
 * The reference holds it in the same structure at {@code app/cpy/COCOM01Y.cpy} L33, L38, L39 and L41,
 * where it survives between turns only because the terminal returns it. Carrying it with each request
 * instead makes every request self-describing, which is what lets the filter chain authorize one request
 * without consulting a previous one -- and the body is the carrier rather than the path because the
 * selection context here IS an account identifier. A path segment and a query string are both written
 * verbatim into the load balancer's access record, composed by the load balancer before any application
 * code runs, and the migration's sensitive-data contract in
 * {@code docs/architecture/observability.md} names account identifiers among the values a durable
 * diagnostic may not hold. Every operation on this controller therefore addresses a fixed segment, and
 * the module's contract test fails the build if any published path template or declared parameter
 * regains a place to put one. Assumptions: this departs from §0.7.1 of the technical specification,
 * which words the same decomposition as "REST path and query parameters", and the departure is
 * registered centrally as {@code D-ACCOUNT-SELECTION-IN-BODY} in
 * {@code docs/architecture/cobol-to-service-traceability.md} §7.4 rather than argued here — that entry
 * holds the cost, the three rejected alternatives and the §0.7.8 reasoning that decides it.</p>
 *
 * <p>Refactoring Rationale: NAVIGATION leaves the server entirely. The reference transfers control with
 * {@code EXEC CICS XCTL} at {@code app/cbl/COACTVWC.cbl} L349 and {@code app/cbl/COACTUPC.cbl} L956 to
 * L959, and in both places the program named is a communication-area field rather than a literal, fed
 * from {@code app/cpy/COCOM01Y.cpy} L21 to L24 with the last map and mapset at L43 and L44. A route
 * change in the browser replaces it. No response member names a next program, a next transaction or a
 * last map, because a server-chosen next screen is precisely the coupling that made the reference
 * conversational.</p>
 *
 * <p>Refactoring Rationale: the RE-ENTRY DISCRIMINATOR disappears rather than being ported. The
 * reference distinguishes a first entry from a resubmission with the field at
 * {@code app/cpy/COCOM01Y.cpy} L29 and its two conditions at L30 and L31, and that distinction reaches
 * into presentation: the field-highlight fragment {@code app/cpy/CSSETATY.cpy} conjoins the resubmission
 * condition at L20 into the very test that decides whether a rejected field is coloured at all, so on a
 * first entry a rejected field would not be marked. A handler that answers a refusal with HTTP 400 and
 * a per-field array has no turn to remember, so field-error rendering is driven purely by the response
 * body. Nothing here reintroduces that flag under any name.</p>
 *
 * <p>Refactoring Rationale: one structured per-field array replaces the reference's hand-maintained
 * highlight expansions. {@code app/cpy/CSSETATY.cpy} L18 to L27 is a procedure-division fragment
 * substituted per screen field -- it moves a colour into the field's attribute when the field's flag is
 * unacceptable or absent, and additionally moves the literal at L24 into the field itself when it is
 * absent. That fragment is expanded once per validated field throughout {@code app/cbl/COACTUPC.cbl};
 * the whole of it collapses into one array on the response, and the absent-field marker survives as a
 * presentation concern of the browser rather than as a value in any domain object.</p>
 *
 * <p>Trade-offs: no JPA entity and no mapper crosses this boundary, and the direction is service to
 * mapper rather than adapter to mapper. This package therefore sees data transfer objects only, and
 * mapping -- masking, encryption and the reference's field renamings, of which the misspelled
 * expiration date at {@code app/cpy/CVACT01Y.cpy} L11 is the clearest example -- is invoked from inside
 * the transaction that reads the rows. The compromise accepted is a longer call path for a value this class
 * could have assembled itself; what it buys is that the layering rule which forbids web types inside
 * the domain package cannot be broken from here, and that a later reader who is tempted to inject a
 * mapper finds the reason not to recorded at the place the temptation arises.</p>
 *
 * <p>Alternatives Considered: no exception advice, error type or pagination envelope is declared in this
 * package. {@code com.carddemo.common.error} owns the refusal, conflict and abend shapes and
 * {@code com.carddemo.common.web} owns the page envelope, so a second declaration here would be a
 * second contract for one concern and the two could disagree about the body a client parses. The
 * alternative of a local advice was rejected for that reason alone: it would have been shorter to write
 * and would have made the response shape depend on which controller answered.</p>
 *
 * <p>Alternatives Considered: were any operation here to return a list longer than one account's cross
 * references, it would page by KEY and never by position. The reference browse state is already a keyset
 * cursor -- {@code app/cbl/COCRDLIC.cbl} holds a last-key pair at L230 to L232 and a first-key pair at
 * L233 to L235, and discovers whether a further page exists by reading one row more than the screen
 * holds, counted at L145. Nowhere in that structure is there an offset, a row number or a page size.
 * Offset paging was rejected because concurrent inserts make it skip and repeat rows, which a
 * browse-by-key does not, so it would change observable behaviour while appearing to preserve it.</p>
 *
 * <p>Assumptions: the machine read and the end-user edit share one address and differ only in method,
 * because they act on one record. They are separated by filter chain rather than by path prefix, and
 * the reasoning is recorded on {@code SecurityConfig.ACCOUNT_PATH_PATTERN}: two prefixes would mean two
 * published contracts and two handlers for one query. Every operation below is declared in
 * {@code src/main/resources/openapi/account-api.yaml}, and the contract test compares the two sets in
 * both directions so neither can move alone.</p>
 *
 * <p>Assumptions: the key is an eleven-digit internal account identifier, declared
 * {@code 05 ACCT-ID PIC 9(11).} at {@code app/cpy/CVACT01Y.cpy} L5, so it travels in the PATH -- unlike
 * the cross-reference lookup, whose key is a primary account number and therefore may not appear in a
 * path at all. The asymmetry between the two operations is not inconsistency; it follows from what
 * each key is.</p>
 *
 * <p>Assumptions: per-operation and per-response metadata lives in the committed contract at
 * {@code src/main/resources/openapi/account-api.yaml} rather than in annotations here, matching the two
 * sibling adapters of this package, while {@code OpenApiConfig} contributes document-level metadata
 * only. The obligation that creates is stated so it is not discovered later: every status named in a
 * handler comment below is declared there for the same operation, and the contract test reads the
 * PACKAGED document, so a status added in one place and not the other is visible as a build failure
 * rather than as a surprise to a client.</p>
 *
 * <p>Assumptions: money crosses this boundary as a JSON STRING, which the shared serialisation module
 * registered by the application class applies to every amount. The account layout at
 * {@code app/cpy/CVACT01Y.cpy} declares five signed amounts of ten integer digits and two decimals, at
 * L7, L8, L9, L13 and L14. A JSON number is parsed into IEEE-754 binary floating point by most clients,
 * and twelve significant digits leave that representation no margin, so the exactness would be lost at
 * the boundary the user actually reads. Emitting a number was therefore rejected for a measurable loss
 * of precision rather than as a matter of preference.</p>
 *
 * <p>Assumptions: every user-visible sentence crosses this boundary exactly as the reference holds it,
 * with whitespace never normalised and two similar wordings never harmonised into one. The evidence is
 * that the reference itself is inconsistent on purpose or by accident and both forms are live:
 * {@code app/cbl/COACTVWC.cbl} EMITS {@code Account Filter must  be a non-zero 11 digit number} at L672,
 * carrying two consecutive spaces between the second and third words, a hyphen inside "non-zero" and the
 * word "Filter", whereas the two conditions DECLARED at L125 with L126 and at L127 with L128 read
 * "Account number must be a non zero 11 digit number" with single spacing, no hyphen and the word
 * "number" -- and those two declarations are byte-identical to each other under two distinct condition
 * names. A reader who tidies the spacing at L672, or who folds the two declarations together because
 * their text matches, changes what at least one screen says. Related and equally load-bearing: the exit
 * sentence declared at L119 with L120 occupies thirty-four characters of which the last fourteen are
 * spaces, so a literal's length and the width of the field holding it are separate facts.</p>
 *
 * <p>Assumptions: the message channels are the PROGRAM-side widths and not the screen-side ones. The
 * return channel is declared seventy-five characters wide at {@code app/cbl/COACTVWC.cbl} L117 and
 * identically at {@code app/cbl/COACTUPC.cbl} L479, and the informational channel forty characters at
 * {@code app/cbl/COACTVWC.cbl} L110, while the symbolic maps give their containers more room -- forty-five
 * and seventy-eight characters at {@code app/cpy-bms/COACTVW.CPY} L234 and L240. The narrower program
 * widths are the contract because they are what the programs actually move, and the response record owns
 * and enforces that contract, so this adapter passes a sentence through without rewriting or truncating
 * it.</p>
 *
 * <p>Assumptions: the published projection of the machine read carries three amounts and not the account
 * record. The reason it is narrow, and specifically why the account's active status is withheld rather
 * than carried, is recorded on {@link AccountContextView} -- acting on that status would decline
 * requests the reference approves, and carrying it unread would be a field the contract gained without
 * anyone deciding it had.</p>
 *
 * <p>Assumptions: an absent row is answered with 404 and never with a 200 carrying nulls. The consumer
 * treats 404 as a decision input, matching the reference's own account-not-found outcome whose sentence
 * is declared at {@code app/cbl/COACTVWC.cbl} L131 with L132, and treats a 200 whose body is missing any
 * of the three amounts as a dependency failure that rolls its transaction back. The two therefore have opposite meanings on the other side, and a 200 with nulls would deliver
 * the wrong one.</p>
 *
 * <p>Trade-offs: identifiers are masked and encrypted at the mapping boundary rather than at the
 * database. The customer layout at {@code app/cpy/CVCUS01Y.cpy} declares a national identifier at L17
 * and a government-issued identifier at L18, and both are stored encrypted and returned masked; no card
 * verification value exists in either record this context owns, so none can be returned by any operation
 * here. What is accepted is that a reader of the response cannot reconstruct the stored value even for a
 * legitimate purpose, and the unmasked administrative card view deliberately lives in another service
 * rather than being reachable from this one.</p>
 */
@RestController
@RequestMapping(AccountController.BASE_PATH)
public class AccountController {

    /**
     * The path prefix every operation in this controller sits beneath.
     *
     * <p>Assumptions: exposed as a constant so the load-balancer rule and the gateway route that forward
     * this prefix can be asserted against it rather than compared by eye across two files.</p>
     */
    public static final String BASE_PATH = "/api/v1/accounts";

    /**
     * The sub-path of the internal account context lookup, beneath {@link #BASE_PATH}.
     *
     * <p>Assumptions: exposed as a constant for the reason the sibling cross-reference controller
     * exposes its own -- {@code InternalApiSecurityConfig} builds its request matcher from this value, so
     * the authority the operation requires and the operation itself cannot come to disagree by an edit to
     * one of them. A literal in the security configuration would silently stop matching if this path were
     * renamed, and a matcher that stops matching leaves the operation reachable by whichever chain claims
     * it next rather than failing.</p>
     */
    public static final String LOOKUP_PATH = "/lookup";

    /**
     * The sub-path of the human account view, beneath {@link #BASE_PATH}.
     *
     * <p>Refactoring Rationale: this operation was {@code GET /api/v1/accounts/{accountId}/view} and the
     * identifier travelled in the path. It is now a {@code POST} at this fixed address with the identifier
     * in an {@link AccountLookupRequest} body. The reason is the one recorded on that record and enforced
     * by the contract test beside this class: a load balancer composes its access record from the REQUEST
     * LINE, inside the process terminating the connection and before any application code runs, so an
     * identifier in a path segment reaches a durable store that no masker, filter or exception handler in
     * this service can reach -- and the migration's sensitive-data contract in
     * {@code docs/architecture/observability.md} names account identifiers among the values a durable
     * diagnostic may not hold. The keyed form is REMOVED rather than kept as an alias, because an alias
     * would leave the disclosure reachable by anyone who addressed the older shape.</p>
     *
     * <p>Assumptions: exposed as a constant for the reason {@link #LOOKUP_PATH} is -- the contract test
     * and the dispatcher test address the same value this mapping publishes, so a rename cannot leave one
     * of them asserting against an address no handler serves.</p>
     */
    public static final String VIEW_PATH = "/view";

    /**
     * The sub-path of the account edit, beneath {@link #BASE_PATH}.
     *
     * <p>Refactoring Rationale: this operation was {@code PUT /api/v1/accounts/{accountId}} and is now a
     * {@code POST} at this fixed address, for the reason recorded on {@link #VIEW_PATH}. Two properties
     * made the move cost nothing in expressiveness. The submitted record ALREADY carries the account
     * identifier as its first component -- it is screen field {@code ACCTSIDI} at
     * {@code app/cpy-bms/COACTUP.CPY} L60, a field the reference user types into the map -- so the path
     * variable was a second spelling of a value the body already held, and the body's is the one the
     * reference has. And {@code AccountUpdateService.editAccountKey} already edited the submitted value,
     * so the key the caller supplies is now the ONLY key: the disagreement between a path key and a body
     * key that the service's own documentation had to reason about is no longer expressible.</p>
     *
     * <p>Trade-offs: {@code POST} rather than {@code PUT} gives up idempotence by method semantics. That
     * costs this operation nothing, because it was never idempotent in effect: it is conditional on
     * {@code If-Match} and a repeated submission of the same body is refused with 409 by the precondition
     * rather than applied twice. A {@code PUT} to a fixed collection address carrying its own target in
     * the body would have claimed a semantic -- replace the resource at this address -- that is not what
     * the operation does.</p>
     */
    public static final String UPDATE_PATH = "/update";

    /**
     * The sub-path of the no-write validation turn, beneath {@link #BASE_PATH}.
     *
     * <p>Assumptions: it sits BENEATH the update path rather than beside it, spelled
     * {@code /update/validate}, because it judges exactly the submission {@link #UPDATE_PATH} applies
     * and shares its request body. A sibling spelling such as {@code /validate-update} would have
     * separated two operations that a reader has to read together.</p>
     */
    public static final String UPDATE_VALIDATE_PATH = "/update/validate";

    /**
     * The sub-path of the by-account cross-reference walk, beneath {@link #BASE_PATH}.
     *
     * <p>Refactoring Rationale: this operation was
     * {@code GET /api/v1/accounts/{accountId}/card-cross-references} and is now a {@code POST} at this
     * address with the account in an {@link AccountLookupRequest} body, for the reason recorded on
     * {@link #VIEW_PATH}. The {@code cursor} and {@code direction} query parameters STAY in the request
     * line and are unaffected: a sealed cursor is confidential by construction and a direction is one of
     * two published words, so neither is a value the sensitive-data contract prohibits.</p>
     *
     * <p>Assumptions: the address keeps the collection segment and gains {@code /search} rather than
     * becoming a {@code POST} on the collection itself. This migration spells a bounded {@code POST} read
     * that way everywhere it has one -- {@code /api/v1/cards/search},
     * {@code /api/v1/authorizations/search} and the internal twin of this very walk at
     * {@code /api/v1/card-xrefs/search-by-account} -- and a {@code POST} on a collection address would
     * read as a create, which this context publishes for no cross-reference row at all.</p>
     */
    public static final String CARD_XREF_SEARCH_PATH = "/card-cross-references/search";

    /**
     * The two words the cross-reference walk accepts as a direction.
     *
     * <p>Assumptions: the domain is enforced at the EDGE rather than only inside the service, so a
     * misspelled direction is refused as a bad request instead of silently reading forward. The service
     * still treats anything other than {@code previous} as forward, which is what makes an absent
     * parameter the opening page; this constraint narrows what can reach it to the two words the contract
     * publishes.</p>
     */
    static final String CARD_XREF_DIRECTION_DOMAIN = "next|previous";

    /**
     * The request property name a refused account identifier is reported under.
     *
     * <p>Assumptions: the name is the BODY MEMBER this class binds — {@code accountId} on
     * {@code AccountLookupRequest} and on {@code AccountUpdateRequest} — and the committed contract states
     * the same name in the refusal description of both operations that carry the key. Naming it once here
     * is what keeps the binding and the reported property from drifting apart, since a rename of the
     * member alone would otherwise leave the array pointing at a property no request has. Refactoring
     * Rationale: this said "the path variable this class binds", which no operation here binds — every one
     * of the four is a {@code POST} taking its key from a body, for the reason recorded on the class and
     * registered as {@code D-ACCOUNT-SELECTION-IN-BODY}. The property name is unchanged by the
     * correction, which is exactly why the stale wording survived: it described the wrong mechanism for
     * the right name.</p>
     */
    private static final String ACCOUNT_ID_FIELD = "accountId";

    /**
     * The pattern that renders a bound identifier in the width the reference edits examine.
     *
     * <p>Assumptions: the width is taken from the write path's own constant rather than written as a
     * literal, because both this rendering and the edit that consumes it have to mean the same eleven
     * characters. A literal here could be changed without the edit noticing.</p>
     */
    private static final String ACCOUNT_KEY_FORMAT =
            "%0" + AccountUpdateService.ACCOUNT_KEY_WIDTH + "d";

    /**
     * The read path this controller binds requests onto.
     */
    private final AccountViewService reads;

    /** Applies an edit under the caller's concurrency precondition. */
    private final AccountUpdateService writes;

    /**
     * Creates the controller.
     *
     * <p>Assumptions: the collaborators are the service layer and nothing else, for the reason recorded
     * on the sibling cross-reference controller: this package's charter forbids a repository or the
     * mapper here. They are supplied through the CONSTRUCTOR rather than assigned into fields by the
     * container, which is what lets both handlers be exercised without a servlet container or a
     * database, and what makes a missing collaborator a startup failure instead of a null dereference on
     * the first request.</p>
     *
     * @param reads the account read path; must not be {@code null}
     * @param writes the write path that applies an edit under the caller's precondition; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public AccountController(AccountViewService reads, AccountUpdateService writes) {
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
        this.writes = Objects.requireNonNull(writes, "writes must not be null");
    }

    /**
     * Reads the limits and balance of one account, taking the identifier from a request body.
     *
     * <p>Purpose: this is the machine read the pending-authorization and transaction contexts call before
     * they decide, and it is the operation their clients address as
     * {@code POST /api/v1/accounts/lookup}. It is a separate operation from the human account view below:
     * this one carries three amounts and serves a neighbouring bounded context, that one carries the
     * account's fields beside its customer's and serves a screen.</p>
     *
     * <p>Refactoring Rationale: the operation is a {@code POST} on a fixed segment where it was a
     * {@code GET} on {@code /{accountId}}, and the reason is disclosure rather than semantics -- the
     * identifier is withdrawn from the request line so that the load balancer's access record cannot hold
     * it. The two consuming clients were already written against this shape and address; publishing the
     * keyed form instead left every one of their calls without a handler, so the calls were read as
     * dependency failures, rolled back and redelivered until they dead-lettered. Republishing the
     * documented shape is what closes that, and it is verified in both directions: the published contract
     * is asserted against the mounted handlers by this module's contract test, and each client's own test
     * pins the method and the path it calls.</p>
     *
     * <p>Assumptions: the transaction boundary sits on the service method this handler calls, so the
     * read-only declaration governs the unit of work rather than the request binding.</p>
     *
     * <p>Assumptions: this operation deliberately does NOT apply the screen key edit the two end-user
     * operations apply. It is not a migrated screen -- it serves the pending-authorization context's
     * keyed read -- so the guards at {@code app/cbl/COACTVWC.cbl} L666 and L667, which belong to that
     * program's own screen filter, have no jurisdiction over it, and refusing a value here that the
     * consumer obtained from its own stored row would turn a data condition into a validation failure
     * the consumer cannot act on. What the bound record does constrain is the declared eleven-digit width,
     * because a wider value cannot match a stored row and is a malformed request rather than an absent
     * one.</p>
     *
     * <p>Refactoring Rationale: this operation was {@code GET /api/v1/accounts/{accountId}} and is now
     * {@code POST} on {@value #LOOKUP_PATH} with the identifier in the body. The move is not a style
     * preference and it closes a real gap rather than relocating one. Two consuming contexts --
     * {@code authorization-service} and {@code transaction-service}, each through its own
     * {@code RestAccountContextClient} -- were ALREADY addressing {@code /api/v1/accounts/lookup} with a
     * JSON body, having been migrated to the body form when {@link AccountLookupRequest} was introduced,
     * while this controller still published only the keyed {@code GET}. Every such call therefore reached
     * this service as a 404 from the dispatcher, so the pending-authorization decision path and the bill
     * payment path could not read an account at all. The reason the callers moved is recorded in full on
     * {@link AccountLookupRequest}: the load balancer composes its access record from the request line
     * before any application code runs, so an identifier in a path segment is written to a durable log
     * that no masker, filter or exception handler inside a service can reach, and the migration's
     * sensitive-data logging contract names account identifiers among the values such a log may not
     * carry.</p>
     *
     * <p>Trade-offs: the keyed {@code GET} is REMOVED rather than kept beside the {@code POST} as a
     * deprecated alias. Keeping it would have restored the callers with no further change, which is
     * exactly the argument against it -- the reason for the move is that the path form writes an
     * identifier to the access log, so a surviving alias would leave that disclosure reachable by anyone
     * who addressed the older shape, and an internal contract with two spellings has no way to tell a
     * caller which one it is meant to use. Nothing else addressed the {@code GET}: the browser client
     * does not consume this contract at all, and the two internal clients had already moved.</p>
     *
     * @param request the lookup request carrying the account identifier; must satisfy its declared
     *     constraints
     * @return the account's credit limit, cash credit limit and posted balance, never {@code null}
     * @throws java.util.NoSuchElementException if the account master holds no such row, which the shared
     *     advice renders as 404 -- and which the consumer reads as its account-not-found decision input
     */
    @OnlineWriteGateExempt(reason =
            "A READ of the account context, a POST only so that the eleven-digit account identifier"
            + " travels in a request body rather than in a request line the load balancer records."
            + " It is the decision read the authorization and transaction contexts make, so it has to"
            + " keep answering while the batch window is closed. The human update on this same"
            + " controller is also a POST, for the same disclosure reason, and is deliberately NOT"
            + " exempt: it is the one operation here that writes.")
    @PostMapping(path = LOOKUP_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AccountContextView lookup(@Valid @RequestBody AccountLookupRequest request) {
        return this.reads.readAccountContext(request.accountIdNumber());
    }

    /**
     * Serves the HUMAN account view, and publishes the revision an update must return.
     *
     * <p>Purpose: this is the migrated route for {@code app/cbl/COACTVWC.cbl}. It is a SEPARATE path from
     * the machine read above, and deliberately so: that one serves a neighbouring bounded context and
     * carries three amounts, this one serves a screen and carries the account's fields beside its
     * customer's. One path returning whichever shape a caller preferred would make the response type
     * depend on a header, and neither caller could then be given a stable contract.</p>
     *
     * <p>Refactoring Rationale: the key is edited BEFORE the read, because the reference edits it before
     * its own read -- {@code app/cbl/COACTVWC.cbl} guards at L666 and L667 and only then reaches the
     * three-hop read. Binding the path to a number satisfies the digit and width halves of that guard,
     * but it does NOT satisfy the all-zeroes half at L667, and the committed contract bounds the
     * parameter from zero inclusive, so a zeroed key would otherwise reach the read and be answered as a
     * missing row. The reference distinguishes the two outcomes and says so in different words, and this
     * call restores that distinction rather than assuming the binding made it unnecessary.</p>
     *
     * <p>Assumptions: the revision is published as an {@code ETag} and NOT as a body member. The update
     * response record's own recorded rationale rejects a body version component on the grounds that an
     * optimistic-lock version is transport metadata, and a value with two homes is a value whose two
     * homes can disagree.</p>
     *
     * <p>⚠️ Refactoring Rationale: the header is OMITTED when the composition carries no revision, and it
     * was previously written unconditionally. The read now answers HTTP 200 for an account located with no
     * customer row -- the reference's own partial screen -- and that arm has no revision, because a
     * precondition cannot be formed from rows that were not both read. Formatting a null through the weak
     * tag would have published the literal {@code W/"null"}, which a caller would echo on
     * {@code If-Match} and be told was stale by a comparison against a real token, when what it should be
     * told is that this representation carries no precondition at all.</p>
     *
     * <p>Trade-offs: the entity tag is WEAK, prefixed {@code W/}. A strong tag asserts octet equality of
     * the representation, which this value cannot promise -- two responses at the same revision are
     * semantically identical but need not be byte-identical, since the masked identifiers and the message
     * channels are assembled per response. A weak tag asserts semantic equivalence, which is exactly
     * what a revision means here, and is what {@code If-Match} on the update compares.</p>
     *
     * @param request the read request carrying the account identifier; must satisfy its declared
     *     constraints
     * @return the account view, carrying its revision in the {@code ETag} header when both master rows
     *     were located and carrying no such header when only the account was, never {@code null}
     * @throws ClientInputException if the identifier is not one the reference's own filter edit would
     *     accept, which the shared advice renders as HTTP 400 naming the offending property
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it and a caller catching the parent catches both
     * @throws java.util.NoSuchElementException if the account, its cross-reference or its customer is
     *     absent, which the shared advice renders as HTTP 404
     */
    @OnlineWriteGateExempt(reason =
            "A READ of one account and its customer, a POST only so that the eleven-digit account"
            + " identifier travels in a request body rather than in a request line the load balancer"
            + " records. The gate classifies by HTTP method because a method is known before a handler"
            + " runs, so a read expressed as a POST has to say so here or it would be refused while the"
            + " batch window is closed -- and refusing a read during the window removes a capability the"
            + " baseline keeps: the quiesce this gate migrates closed the files to WRITERS.")
    @PostMapping(path = VIEW_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountViewResponse> readView(
            @Valid @RequestBody AccountLookupRequest request) {
        long accountId = request.accountIdNumber();
        refuseUnacceptableViewFilter(accountId);

        AccountViewService.RevisionedAccountView composed = this.reads.readAccountView(accountId);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (composed.revision() != null) {
            response.header(HttpHeaders.ETAG, weakTag(composed.revision()));
        }
        return response.body(composed.view());
    }

    /**
     * Applies an edited account and customer, refusing a submission whose precondition is stale.
     *
     * <p>Purpose: this is the migrated route for {@code app/cbl/COACTUPC.cbl}. The precondition is
     * REQUIRED rather than optional, which is what makes the concurrency check unavoidable: an optional
     * header would let a caller opt out simply by omitting it, and opting out means the silent-overwrite
     * behaviour the check exists to remove. A request without the header is refused by the framework
     * before this method is entered.</p>
     *
     * <p>Refactoring Rationale: the key is edited here too, by the WRITE path's own edit rather than the
     * read path's. The two reference programs reach different words for a rejected key -- the account
     * view emits the sentence at {@code app/cbl/COACTVWC.cbl} L672 while the update composes a different
     * one at {@code app/cbl/COACTUPC.cbl} L1806 to L1810 -- so asking each side's own edit is what keeps
     * each screen saying what it said. Sharing one edit between them would have been less code and would
     * have changed the text of one of the two screens.</p>
     *
     * <p>Assumptions: the successful response carries the NEW revision in its own {@code ETag}, so a
     * caller performing consecutive edits does not have to re-read the account between them. Without it
     * every second edit in a sequence would fail its own precondition.</p>
     *
     * <p>⚠️ Refactoring Rationale: that new revision is read off the write operation's own answer and is
     * no longer obtained by a second call. This method used to follow the update with a read-only
     * {@code writes.currentRevision} in a transaction of its own, which could observe a THIRD party's
     * edit committed after this one -- so a caller was handed a tag naming a state it had never been
     * shown, and its next edit then succeeded against that unseen state instead of being refused. The
     * write service now derives the token from the managed rows after its flush, inside the same
     * transaction that wrote them.</p>
     *
     * <p>Assumptions: the tag comparison tolerates the {@code W/} prefix and quoting, because an
     * intermediary is permitted to reformat an entity tag and a caller that echoes what it received must
     * not be refused for the formatting. The comparison is on the value inside.</p>
     *
     * <p>Assumptions: the conflict status is produced by the shared advice and is NOT re-implemented
     * here, so no optimistic-lock failure is caught in order to convert it. The advice recognises the
     * condition without a persistence dependency and renders it as HTTP 409 carrying the reference's own
     * sentence, {@code Record changed by some one else. Please review} from
     * {@code app/cbl/COACTUPC.cbl} L521 to L522 -- reproduced with its two-word spelling of "some one"
     * and with no closing full stop, because that is what the field holds. The reference reached that
     * text and the conflict signal with a single assignment, since the conditions at L480 to L528 all sit
     * on the return-message field declared at L479 and not on the change flag at L168; here the signal is
     * the exception and the text is a member of the error body, and the two travel together.</p>
     *
     * @param ifMatch the revision the caller was given, from the {@code If-Match} header; required
     * @param request the submitted edit, whose own first member names the account to update; must not be
     *     {@code null}
     * @return the committed state with the new revision in the {@code ETag} header, never {@code null}
     * @throws ClientInputException if the submitted identifier is not one the reference's own key edit
     *     would accept -- including absent, which a body can express and a path segment could not -- or
     *     if any submitted value was refused, which the shared advice renders as HTTP 400 with one entry
     *     per offending property
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it and a caller catching the parent catches both
     * @throws java.util.NoSuchElementException if the account or its customer is absent, which the shared
     *     advice renders as HTTP 404
     * @throws com.carddemo.common.error.RecordConflictException if the precondition does not name the
     *     stored state, which the shared advice renders as HTTP 409 carrying the reference's own
     *     changed-record sentence
     * @throws org.springframework.dao.OptimisticLockingFailureException if either row moves between the
     *     write transaction's read and its flush, which the shared advice renders as the same HTTP 409
     *     because the caller's remedy is identical -- re-read and retry
     */
    @PostMapping(path = UPDATE_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountUpdateResponse> update(
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody AccountUpdateRequest request) {
        long accountId = refuseUnacceptableUpdateKey(request.accountId());

        AccountUpdateService.RevisionedAccountUpdate applied =
                this.writes.update(accountId, request, bareTag(ifMatch));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, weakTag(applied.revision()))
                .body(applied.response());
    }

    /**
     * Judges a submitted edit against the stored rows and reports the verdict, writing nothing.
     *
     * <p>Purpose: this is the baseline's FIRST turn. {@code 2000-DECIDE-ACTION}'s show-details arm
     * advances to the confirmation state only when the edits found no error and something changed --
     * {@code IF INPUT-ERROR OR NO-CHANGES-DETECTED ... CONTINUE ELSE SET ACUP-CHANGES-OK-NOT-CONFIRMED}
     * at {@code app/cbl/COACTUPC.cbl} L2584 to L2591 -- so the twenty-four edits have run before the
     * operator is ever shown {@code Changes validated.Press F5 to save}. Only the confirmation turn
     * performs {@code 9600-WRITE-PROCESSING}.</p>
     *
     * <p>Refactoring Rationale: this route did not exist, and its absence was a correctness problem in
     * the browser rather than a missing convenience. A client wanting the reference's two turns could
     * only advance without validating -- asserting the edits had passed on the strength of nothing --
     * or submit the write to find out, which is the one thing the first turn must not do. The screen
     * took the first option and told operators their changes were validated when no rule had run.</p>
     *
     * <p>Assumptions: no {@code If-Match} header is required, unlike {@link #update}. A verdict changes
     * nothing, so there is no state a precondition would protect, and requiring one would only prevent
     * a caller asking a question it is entitled to ask. The key edit is still applied to the body's own
     * identifier first, by the same helper the write uses, so an unusable identifier is refused here
     * exactly as it is there.</p>
     *
     * <p>Trade-offs: a refusal is reported as HTTP 200 carrying the verdict, not as a 400. Here a
     * refused value is the successful answer to the question asked; on the write it means the request
     * could not be carried out. The reasoning is recorded on
     * {@link AccountUpdateValidationResponse}.</p>
     *
     * @param request the submitted edit to judge, whose own first member names the account; must not be
     *     {@code null}
     * @return the verdict, never {@code null}
     * @throws ClientInputException if the submitted identifier is not one the reference's own key edit
     *     would accept, which the shared advice renders as HTTP 400
     * @throws IllegalArgumentException as the parent of the above, since {@link ClientInputException}
     *     extends it
     * @throws java.util.NoSuchElementException if the account or its customer is absent, which the
     *     shared advice renders as HTTP 404
     */
    @OnlineWriteGateExempt(reason =
            "A READ that judges a submitted edit and writes nothing, a POST only because it shares the"
            + " update's request body and so carries the eleven-digit account identifier where the"
            + " update carries it. The gate classifies by HTTP method, so a read expressed as a POST has"
            + " to declare itself one; and the quiesce this gate migrates closed the files to WRITERS"
            + " rather than to readers, so refusing a verdict during the batch window would withhold the"
            + " one turn that tells an operator whether their edit would be accepted once it reopens.")
    @PostMapping(path = UPDATE_VALIDATE_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountUpdateValidationResponse> validateUpdate(
            @Valid @RequestBody AccountUpdateRequest request) {
        long accountId = refuseUnacceptableUpdateKey(request.accountId());

        AccountUpdateService.EditVerdict verdict = this.writes.validateOnly(accountId, request);
        return ResponseEntity.ok(new AccountUpdateValidationResponse(verdict.fieldErrors(),
                verdict.message(), verdict.inputError(), verdict.noChangesFound()));
    }

    /**
     * Lists an account's card cross-reference rows a page at a time through the migrated by-account index.
     *
     * <p>Purpose: this is the {@code CXACAIX} access path, which the baseline surfaces to the online
     * region as an alternate index over the cross-reference file and reads by account. Until this route
     * existed the path had no production consumer: the secondary index, the ordered repository query and
     * the response projection all existed and nothing joined them.</p>
     *
     * <p>Refactoring Rationale: this operation returned the WHOLE of an account's cross-reference rows in
     * one array, and it now returns a bounded keyset page. The unbounded shape had no ceiling of any kind:
     * the index is not unique, so the row count is whatever the data holds, and every row carries a
     * primary account number -- so a single request decided how much cardholder data this process read
     * into its heap and serialised. The bounded read is not new work either; the seven-row keyset walk,
     * its repository statements and its sealed cursors already existed on the service and this route was
     * simply not using them.</p>
     *
     * <p>Assumptions: seven rows to a page, which is the reference screen's own capacity rather than a
     * number chosen here -- {@code app/cbl/COCRDLIC.cbl} fills seven rows and discovers a further page by
     * reading one more. Trade-offs: a caller wanting every row now issues several requests, which is the
     * cost; what it buys is that no single request can be made arbitrarily large by the data.</p>
     *
     * <p>Assumptions: the route hangs off the ACCOUNT rather than living under a cross-reference subtree
     * of its own, because the by-account read is a property of an account and because the standalone
     * cross-reference subtree is denied to end users by the filter chain -- it carries the by-card lookup,
     * which takes a whole primary account number and is reachable only from inside the network.</p>
     *
     * <p>Assumptions: an account with no cards yields an EMPTY page rather than a not-found outcome,
     * matching an alternate-index browse that ends immediately.</p>
     *
     * @param request the read request carrying the account whose cross-reference rows are required; must
     *     satisfy its declared constraints
     * @param cursor the sealed boundary a previous page issued, or absent to read the first page
     * @param direction {@code previous} to step backward, absent or anything else to step forward;
     *     meaningful only alongside a cursor
     * @param principal the authenticated caller, supplied by the framework; the cursors this operation
     *     issues are sealed against its name, the account in the BODY and the direction, so a page issued
     *     to one caller walking one account cannot reposition another caller or another account
     * @return one page of at most seven rows in ascending card-number order, with both sealed boundaries
     *     and both availability indicators; never {@code null}
     * @throws CursorToken.InvalidCursorException if the cursor cannot be opened, or was sealed for another
     *     query, subject, account or direction, which the shared advice renders as HTTP 400
     */
    // WHY : Assumptions: the cursor and the direction stay in the QUERY STRING while the account moves
    //       into the body, and the split is deliberate rather than an oversight. A query string is part
    //       of the request line and is persisted by the load balancer exactly as a path segment is, so
    //       the test is not where a value sits but whether the sensitive-data contract prohibits it: a
    //       sealed cursor is confidential by construction -- CursorToken authenticates and the account is
    //       part of its seal -- and a direction is one of two published words. Neither is an account
    //       identifier, so neither has to move, and moving them would have made the two boundaries the
    //       response returns awkward to echo back.
    @OnlineWriteGateExempt(reason =
            "A READ of one account's cross-reference rows, a POST only so that the eleven-digit account"
            + " identifier travels in a request body rather than in a request line the load balancer"
            + " records. Exempt for the reason the account view beside it is: the gate classifies by HTTP"
            + " method, so a read expressed as a POST has to declare itself one, and the quiesce this"
            + " gate migrates closed the files to WRITERS rather than to readers.")
    @PostMapping(path = CARD_XREF_SEARCH_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<CardXrefResponse> listCardCrossReferences(
            @Valid @RequestBody AccountLookupRequest request,
            @RequestParam(name = "cursor", required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String cursor,
            @RequestParam(name = "direction", required = false)
            @Pattern(regexp = CARD_XREF_DIRECTION_DOMAIN)
            String direction,
            Principal principal) {

        return this.reads.listCardCrossReferences(
                request.accountIdNumber(), cursor, direction, principal.getName());
    }

    /**
     * Refuses a view request whose account filter the reference's own view edit would not accept.
     *
     * <p>Assumptions: the read path is asked for the ENTRY rather than for a verdict this class then
     * words itself, because the entry already carries the property name, the state and the sentence the
     * reference latched under the guard at {@code app/cbl/COACTVWC.cbl} L670 -- a latch that lets the
     * FIRST refusal win the aggregate sentence while every field error still accumulates. Composing a
     * sentence here would publish wording no reference line produces, and the screen and the field array
     * could then disagree about why the filter was refused.</p>
     *
     * <p>Assumptions: the array is documented as holding at most one entry for this single field, so the
     * loop refuses on the first entry it is given and is not a way of reporting several. It is written as
     * a loop rather than as an index so that an empty array is the ordinary case and needs no test of its
     * own.</p>
     *
     * @param accountId the bound identifier to submit to the reference's view edit
     * @throws ClientInputException if the edit reports the filter unacceptable or absent
     */
    private void refuseUnacceptableViewFilter(long accountId) {
        for (ApiError.FieldError refusal : this.reads.accountFilterFieldErrors(elevenDigitKey(accountId))) {
            refuseWhenUnacceptable(refusal.field(), refusal.state(), refusal.message());
        }
    }

    /**
     * Refuses an update whose submitted account key the reference's own update edit would not accept, and
     * returns the accepted key as the number the write path addresses the row by.
     *
     * <p>Assumptions: the write path's edit returns a state paired with the sentence it would have
     * latched and no property name, so the name is supplied from this class's own binding. That is the
     * one place the two edits differ in shape, and it is why the two refusal helpers are separate rather
     * than one helper taking a function.</p>
     *
     * <p>Refactoring Rationale: the value edited here is the one the CALLER SUBMITTED and no longer a
     * path variable rendered to width. The submitted key is screen field {@code ACCTSIDI} at
     * {@code app/cpy-bms/COACTUP.CPY} L60 and the reference edits exactly it, so this is the closer
     * reading as well as the one that keeps the identifier out of the request line. The refusals are a
     * SUPERSET of what the keyed form could reach and every added one carries the reference's own
     * wording: an absent or blank key -- which a screen field can be and a path segment cannot -- is
     * refused with the sentence {@code 1210-EDIT-ACCOUNT} latches at {@code app/cbl/COACTUPC.cbl} L1792,
     * and a non-numeric key with the sentence composed at L1806 to L1810, where the keyed form would
     * have failed at the framework's binding layer with wording no reference line produces.</p>
     *
     * <p>Assumptions: the accepted key parses without a further guard, and that is a property of the edit
     * rather than an assumption about callers. {@code editAccountKey} accepts only a value that is
     * present, trims to at most {@link AccountUpdateService#ACCOUNT_KEY_WIDTH} characters, is entirely
     * digits and is not entirely zeroes, so every accepted value is a non-negative integer inside the
     * eleven-digit range. Re-testing that here would be a second, weaker copy of the edit that owns the
     * wording.</p>
     *
     * @param submittedKey the account key as the caller submitted it, which may be padded, short,
     *     over-wide, blank or {@code null}
     * @return the submitted key as the number the write path loads the row by
     * @throws ClientInputException if the edit reports the key unacceptable or absent
     */
    private long refuseUnacceptableUpdateKey(String submittedKey) {
        AccountUpdateService.EditOutcome verdict = this.writes.editAccountKey(submittedKey);
        refuseWhenUnacceptable(ACCOUNT_ID_FIELD, verdict.state(), verdict.message());
        return Long.parseLong(submittedKey.trim());
    }

    /**
     * Raises the shared refusal when a validation state reports a rejected field.
     *
     * <p>Assumptions: the DERIVED error predicate is consulted and no flag byte is ever compared against
     * a literal. The reference spells acceptability three structurally different ways inside one program
     * -- a digit one for the key filters at {@code app/cbl/COACTUPC.cbl} L184 to L186, a low value for
     * most other fields at L197 to L199, and the domain letters themselves at L193 and L350 -- so a
     * literal comparison would be correct for one regime and wrong for the other two. The predicate also
     * treats an absent field as a KIND of error rather than as a third peer state, which is what lets one
     * test serve both and matches the reference joining the two conditions with OR at
     * {@code app/cpy/CSSETATY.cpy} L18 and L19.</p>
     *
     * @param field the request property the refusal is reported under; must not be {@code null}
     * @param state the validation state the owning edit returned; must not be {@code null}
     * @param message the sentence the reference would have latched for this refusal
     * @throws ClientInputException if {@code state} reports an error, carrying the field, the state and
     *     the sentence for the shared advice to render as HTTP 400
     */
    private static void refuseWhenUnacceptable(String field, FieldValidationFlag state, String message) {
        if (state.isError()) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, field, state, message);
        }
    }

    /**
     * Renders a bound identifier as the fixed-width character key the reference edits examine.
     *
     * <p>Assumptions: the reference itself holds this key in both representations over one storage --
     * {@code app/cbl/COACTVWC.cbl} declares it numeric at L78 and redefines the same bytes as eleven
     * characters at L79 and L80 -- so presenting the bound number left-zero-padded to that width is the
     * reference's own character view of it and not an invention of this class. The edits consume
     * characters because that is what a screen field gave them, so a rendering step is unavoidable once
     * the transport carries a number.</p>
     *
     * <p>Assumptions: a negative value renders with a leading sign and is therefore rejected by the
     * digits test in the edit rather than by a guard here. Letting the edit answer keeps every refusal
     * reason in the one place that owns the wording.</p>
     *
     * @param accountId the bound identifier
     * @return the identifier as a fixed-width character key, never {@code null}
     */
    private static String elevenDigitKey(long accountId) {
        return String.format(Locale.ROOT, ACCOUNT_KEY_FORMAT, accountId);
    }

    /**
     * Renders a revision as a weak entity tag.
     *
     * @param revision the revision token; must not be {@code null}
     * @return the quoted weak tag, never {@code null}
     */
    private static String weakTag(String revision) {
        return "W/\"" + revision + "\"";
    }

    /**
     * Extracts the revision value from an entity tag a caller supplied.
     *
     * <p>Assumptions: the weak prefix and the surrounding quotes are both stripped, and a value carrying
     * neither is accepted unchanged. An intermediary may reformat an entity tag, so refusing a caller
     * that echoed a reformatted value would fail the request for a difference the caller did not make.
     * </p>
     *
     * @param tag the header value as supplied; must not be {@code null}
     * @return the revision inside, never {@code null}
     */
    private static String bareTag(String tag) {
        String value = tag.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
