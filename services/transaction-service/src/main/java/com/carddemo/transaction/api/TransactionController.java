package com.carddemo.transaction.api;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddOutcome;
import com.carddemo.transaction.dto.TransactionAddPreview;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import com.carddemo.transaction.service.TransactionAddService;
import com.carddemo.transaction.service.TransactionListService;
import com.carddemo.transaction.service.TransactionViewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the three transaction-resource operations migrated from three online COBOL programs.
 *
 * <p>Purpose: this adapter is the only way an HTTP request reaches the browse, the keyed read and the
 * capture. It binds each request to a request shape, delegates the whole of the decision to one
 * {@code @Service} beneath it, and binds the outcome to the status, headers and body the published
 * contract assigns to it. Each operation is named below by the CICS transaction identifier and the
 * official screen name the online-components table of the root {@code README.md} gives it, and by the
 * baseline program that is its specification.</p>
 *
 * <ul>
 *   <li>{@code listTransactions}, transaction {@code CT00}, official name <b>Transaction List</b> at
 *       {@code README.md:298}, migrated from {@code app/cbl/COTRN00C.cbl} (699 lines)</li>
 *   <li>{@code viewTransaction}, transaction {@code CT01}, official name <b>Transaction View</b> at
 *       {@code README.md:299}, migrated from {@code app/cbl/COTRN01C.cbl} (330 lines)</li>
 *   <li>{@code addTransaction}, transaction {@code CT02}, official name <b>Transaction Add</b> at
 *       {@code README.md:300}, migrated from {@code app/cbl/COTRN02C.cbl} (783 lines)</li>
 * </ul>
 *
 * <p>Assumptions: the official name of {@code CT01} is <b>Transaction View</b> and that wording is used
 * throughout rather than the intuitive "transaction detail". The response type it returns is named for
 * the detail it carries, so one name belongs to the screen and another to the payload; paraphrasing the
 * screen name to match the payload would break the correspondence with the table that assigns it. Two
 * further rows of that table are deliberately absent from this adapter: {@code CR00}, official name
 * <b>Transaction Reports</b> at {@code README.md:301}, belongs to reporting-service, and {@code CB00},
 * official name <b>Bill Payment</b> at {@code README.md:302}, is served by the sibling
 * {@code BillPaymentController}. Neither may be added here, because either would place one screen's
 * surface in two places at once.</p>
 *
 * <p>Refactoring Rationale: identity arrives as claims on a validated bearer token rather than in the
 * structure the baseline passes between screen turns, and that closes an exposure instead of relocating
 * a field. The baseline declares {@code CDEMO-USER-ID PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:25} and
 * {@code CDEMO-USER-TYPE PIC X(01)} at {@code :26}, with condition names for {@code 'A'} at {@code :27}
 * and {@code 'U'} at {@code :28}, inside a communication area the client holds between turns and hands
 * back; the one-character user type is therefore client-asserted, and a caller could in principle
 * present its own. A {@code cognito:groups} claim on a token this service verifies is not something a
 * caller can assert. The conversion is
 * {@code com.carddemo.common.security.JwtRoleConverter}, wired by the sibling
 * {@code com.carddemo.transaction.config.SecurityConfig}, which also holds every authorization decision
 * for these three routes at the filter chain. The authority names are the group names verbatim,
 * {@code carddemo-admin} and {@code carddemo-user}, so any predicate over them is an authority
 * predicate such as {@code hasAuthority} or {@code hasAnyAuthority} and never the prefixed form, whose
 * naming convention those verbatim names do not follow. The baseline behaves as described; the target
 * reads the signed claim; the divergence is documented rather than introduced silently.</p>
 *
 * <p>Assumptions: no part of that communication area travels here and none of it is echoed back. Its
 * selection fields, {@code CDEMO-CUST-ID PIC 9(09)} at {@code app/cpy/COCOM01Y.cpy:33},
 * {@code CDEMO-ACCT-ID PIC 9(11)} at {@code :38} and {@code CDEMO-CARD-NUM PIC 9(16)} at {@code :41},
 * arrive as path and query values, which is what makes each request self-describing and therefore
 * independently authorizable. Its navigation fields at {@code :21} through {@code :24} and at
 * {@code :43} and {@code :44} are entirely client-side, so no response leaving this adapter names a next
 * program or a next screen. Corroboration for the stateless boundary comes from the baseline's own
 * resource definitions rather than from the target design alone: all four of these transactions are
 * declared {@code TWASIZE(0)}, at {@code app/csd/CARDDEMO.CSD:420}, {@code :430}, {@code :440} and
 * {@code :338}, so the per-task work area each could have reserved is zero bytes wide in every one.</p>
 *
 * <p>Assumptions: money crosses this boundary as a <b>JSON string</b> and never as a JSON number. Every
 * monetary member of every shape named below is declared as
 * {@code com.carddemo.common.money.Money}, and
 * {@code com.carddemo.common.money.MoneyModule} binds the encoding to that one declared type, so no
 * method here formats an amount. The reason for the string is specific rather than stylistic: most
 * clients parse a JSON number into an IEEE-754 binary floating point value, which destroys exactness at
 * the boundary a user actually reads, and the baseline's amounts are exact decimals with a scale of two
 * throughout. Declaring a plain decimal in place of {@code Money} would compile, run and silently emit a
 * JSON number instead, so the declared type is what selects the wire form.</p>
 *
 * <p>Assumptions: not one user-visible sentence is declared in this file. Each of the three services
 * owns the sentences its baseline program emits, and the sentences are quoted here only to identify
 * which baseline text a status corresponds to. Declaring any of them here would give one contract two
 * homes that can drift apart while both still claim to reproduce the same baseline text. The house
 * precedent for that discipline predates the Java: {@code tests/README.md:540-542} directs the COBOL
 * unit tests to resolve record layouts through the compiler copybook path rather than restating a
 * layout, and to keep them single-sourced from {@code app/cpy/}. The Java analogue is exact, one former
 * {@code COPY} statement becoming one type import from the single package that owns that contract.</p>
 *
 * <p>Assumptions: the same sentence appears in two capitalisations across these programs and the two are
 * kept as two. {@code app/cbl/COTRN00C.cbl} spells it lower case at {@code :615}, {@code :649} and
 * {@code :683}, while {@code app/cbl/COTRN01C.cbl} capitalises it at {@code :292} and
 * {@code app/cbl/COTRN02C.cbl} capitalises it at {@code :664} and {@code :693}. They differ by the case
 * of one letter, which no reader comparing them by eye will see, so the distinction is recorded here as
 * well as being asserted by a test. Normalising the case would alter text a terminal displayed, which
 * transformation rule T8 of the technical specification does not permit.</p>
 *
 * <p>Assumptions: every failure these three operations report is translated by
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, the single advice in this project, which
 * reaches this module through the shared kernel's auto-configuration rather than through anything this
 * service declares. No type in this file is an advice and no method in it is annotated as a handler of
 * exceptions, and no handler below carries a {@code try} block, so a failure raised beneath this adapter
 * reaches that advice unaltered and is rendered as
 * {@code com.carddemo.common.error.ApiError}. That body carries the
 * 75-character aggregate message the baseline declares as {@code CCARD-ERROR-MSG PIC X(75)} at
 * {@code app/cpy/CVCRD01Y.cpy:28} and {@code CCARD-RETURN-MSG PIC X(75)} at {@code :29}, together with
 * the per-field array whose entries carry a {@code com.carddemo.common.validation.FieldValidationFlag}
 * state.</p>
 *
 * <p>Alternatives Considered: annotating each handler below with the documentation library's operation,
 * parameter and response annotations so that the generated description carried them. Rejected, because
 * the contract of record is not the generated description: it is the committed document at
 * {@code src/main/resources/openapi/transaction-api.yaml}, which the browser client
 * {@code ui/src/api/transactions.ts} and the edge route table are both written against. The sibling
 * {@code com.carddemo.transaction.config.OpenApiConfig} already mirrors that document's metadata and
 * records why it keeps the mirrored values as one short list rather than a scatter of literals, and
 * annotating three handlers here would create exactly that scatter: a summary sentence would then exist
 * in two files with nothing comparing them, whereas today
 * {@code TransactionApiRoutingContractTest} compares the committed document against the mapping
 * annotations on these methods and fails when the two disagree. Every controller in this repository is
 * annotation-free in this respect, so the alternative would also make this one the single exception.</p>
 */
@RestController
@RequestMapping(path = TransactionController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class TransactionController {

    /** The collection path the transaction resource is served at, as the published contract declares it. */
    public static final String BASE_PATH = "/api/v1/transactions";

    /** The member path one transaction is addressed by, relative to {@link #BASE_PATH}. */
    public static final String ITEM_PATH = "/{transactionId}";

    /**
     * The path the copy-last action is served at, relative to {@link #BASE_PATH}.
     *
     * <p>Assumptions: a literal segment rather than a variable, and it cannot collide with
     * {@link #ITEM_PATH} even though both sit one level below the collection. That path admits exactly
     * sixteen decimal digits through {@link #TRANSACTION_ID_PATTERN}, which no letter satisfies, and the
     * two mappings answer different methods besides -- a GET for the member and a POST here.</p>
     *
     * <p>Assumptions: the segment is hyphenated rather than camel-cased, matching every other multi-word
     * path segment this deployment publishes -- {@code /card-xrefs/lookup-by-account} and
     * {@code /card-xrefs/search-by-account} in the account context among them -- so one convention
     * governs the whole surface.</p>
     */
    public static final String COPY_LAST_PATH = "/copy-last";

    /** The path variable the addressed transaction identifier arrives in. */
    public static final String PATH_TRANSACTION_ID = "transactionId";

    /** The query parameter the browse's positioning identifier arrives in. */
    public static final String PARAM_TRANSACTION_ID_FILTER = "transactionIdFilter";

    /** The query parameter the browse's sealed cursor arrives in. */
    public static final String PARAM_CURSOR = "cursor";

    /** The query parameter the browse's read direction arrives in. */
    public static final String PARAM_DIRECTION = "direction";

    /** The declared width of a transaction identifier, from {@code TRAN-ID PIC X(16)}. */
    public static final int TRANSACTION_ID_WIDTH = 16;

    /** The character class a transaction identifier is admitted over: exactly sixteen decimal digits. */
    public static final String TRANSACTION_ID_PATTERN = "\\d{16}";

    /**
     * The two wire tokens a read direction is admitted over, mirroring the contract's direction
     * enumeration.
     *
     * <p>Assumptions: this expression is written out rather than assembled from the enumeration's own
     * wire values, because an annotation argument has to be a compile-time constant and a method call
     * is not one. It is the one value in this file that mirrors the published contract, and it is
     * asserted against that document by the routing contract test beside it. Admitting the token here
     * rather than leaving the resolver to refuse it is what makes an unpublished direction the 400 the
     * contract publishes: the resolver refuses by raising an unchecked argument failure, which the
     * shared advice cannot distinguish from an internal fault and therefore renders as a 500.</p>
     */
    public static final String DIRECTION_PATTERN = "next|previous";

    /** The browse this adapter delegates its list operation to. */
    private final TransactionListService listService;

    /** The keyed read this adapter delegates its member operation to. */
    private final TransactionViewService viewService;

    /** The capture this adapter delegates its create operation to. */
    private final TransactionAddService addService;

    /** The seam that seals and opens a browse cursor, held here rather than beneath. */
    private final CursorToken cursorToken;

    /**
     * Builds this adapter over the three services and the cursor seam it needs.
     *
     * <p>Assumptions: the three collaborators are the concrete {@code @Service} types rather than
     * interfaces over them, because there is one implementation of each and no second one is
     * anticipated; an interface with a single implementer would add a file to open without adding a
     * decision to make, and these types are mockable as classes. They are taken through the constructor
     * rather than assigned into fields, so no instance of this adapter can exist without them and a
     * test can supply stubs without a container.</p>
     *
     * <p>Assumptions: the cursor seam is held by this adapter and not by the service beneath it, because
     * the token carries key material and sealing it is binding work rather than a business rule. The
     * package charter beside this file assigns it here for that reason, and the browse service accepts
     * it as a parameter so that the layer holding the key stays the layer facing the wire.</p>
     *
     * @param listService the browse serving {@code CT00} Transaction List, of type
     *     {@link TransactionListService}; must not be {@code null}
     * @param viewService the keyed read serving {@code CT01} Transaction View, of type
     *     {@link TransactionViewService}; must not be {@code null}
     * @param addService the capture serving {@code CT02} Transaction Add, of type
     *     {@link TransactionAddService}; must not be {@code null}
     * @param cursorToken the seam that seals an outgoing cursor and opens an incoming one, of type
     *     {@link CursorToken}; must not be {@code null}
     * @throws NullPointerException if {@code listService}, {@code viewService}, {@code addService} or
     *     {@code cursorToken} is {@code null}
     */
    public TransactionController(TransactionListService listService, TransactionViewService viewService,
            TransactionAddService addService, CursorToken cursorToken) {
        this.listService = Objects.requireNonNull(listService, "listService must not be null");
        this.viewService = Objects.requireNonNull(viewService, "viewService must not be null");
        this.addService = Objects.requireNonNull(addService, "addService must not be null");
        this.cursorToken = Objects.requireNonNull(cursorToken, "cursorToken must not be null");
    }

    /**
     * Reads one page of the transaction ledger, serving {@code CT00} <b>Transaction List</b>.
     *
     * <p>Purpose: this handler answers a {@code GET} on the collection path with one bounded page of
     * list rows and the two boundary keys that address the pages either side of it. It re-expresses the
     * browse of {@code app/cbl/COTRN00C.cbl}, whose forward fill runs {@code PERFORM UNTIL WS-IDX >= 11}
     * at {@code :297} and whose backward fill seeds {@code MOVE 10 TO WS-IDX} at {@code :349} and runs
     * {@code PERFORM UNTIL WS-IDX <= 0} at {@code :351}, so the page holds ten rows in either
     * direction.</p>
     *
     * <p>Alternatives Considered: positioning the page by an ordinal and a row count, the offset
     * pagination a relational rewrite is usually given, was evaluated and rejected. Its concrete
     * consequence is that under concurrent inserts an offset query skips rows and returns other rows
     * twice, because the ordinal is resolved afresh against a result set that has shifted beneath it.
     * That is not a hypothetical workload here: the baseline mints its own identifiers by an unlocked
     * read-then-increment, seen as {@code MOVE HIGH-VALUES}, {@code STARTBR}, {@code READPREV},
     * {@code ENDBR}, {@code MOVE} and {@code ADD 1} at {@code app/cbl/COTRN02C.cbl:444-449} and again at
     * {@code app/cbl/COBIL00C.cbl:212-217}, so new rows arrive precisely at the high end of the key range
     * a forward page is walking towards. Positioning by key cannot skip or repeat a row for that reason,
     * because the position is a value in the ordering rather than a distance along it.</p>
     *
     * <p>Assumptions: no ordinal reaches this signature at all, and the baseline is the authority for
     * that rather than the target design. Its browse state is already a keyset cursor, declared as
     * {@code CDEMO-CT00-TRNID-FIRST PIC X(16)} at {@code app/cbl/COTRN00C.cbl:63} and
     * {@code CDEMO-CT00-TRNID-LAST PIC X(16)} at {@code :64} beside a next-page indicator at {@code :66}
     * whose two condition names sit at {@code :67} and {@code :68}. The counter it keeps at {@code :65}
     * is written to the screen at {@code :324} and is never used to locate a row, so it is display state
     * and not position. The indicator itself is discovered by reading one row beyond the page at
     * {@code :305-313}, one further read at {@code :308} setting the affirmative at {@code :310} and the
     * negative at {@code :312}, which is exactly what the envelope's forward indicator reports.</p>
     *
     * <p>Trade-offs: the envelope's two boundary keys are always the keys of the first and last rows
     * actually returned, and on a partial page the baseline's are not. Its fill writes the leading anchor
     * only under the first slot, at {@code app/cbl/COTRN00C.cbl:392-393}, and the trailing anchor only
     * under the tenth, at {@code :438-439}, so a forward page holding fewer than ten rows leaves the
     * trailing anchor holding a value from an earlier page; the backward fill descends from ten to one
     * and leaves the leading anchor stale in the same way. The baseline behaves as described, this
     * handler answers with the boundary keys of the rows it returned, and the divergence is documented
     * here and in the traceability matrix. What the divergence buys is that a client stepping on from a
     * partial page addresses the row it can see rather than one it was shown earlier; what it costs is
     * that this one observable value is not a transcription, which is why it is recorded rather than
     * assumed to be equivalent.</p>
     *
     * <p>Assumptions: a cursor is an opaque token and this handler treats it as one. It is not parsed,
     * decoded, length-checked or compared here, and no meaning is read from it; it is carried through to
     * the browse together with the seam that sealed it and the caller's own name, and only that seam can
     * open it. Anything more here would put key material and its interpretation in two places.</p>
     *
     * <p>Assumptions: the caller's name is passed down as an argument rather than read by the service
     * from a thread-bound context, because the browse folds it into the cursor binding so that a token
     * issued to one caller cannot be redeemed by another. Taking it as a parameter is what keeps an
     * unauthenticated request a refusal rather than a silently anonymous binding, and it is the
     * arrangement the authorization context's selector-bearing handlers already use.</p>
     *
     * <p>Assumptions: the five page-boundary sentences and the browse's own filter sentences belong to
     * {@code TransactionListService} and are not restated here. Two of the five are guards that reach no
     * data, {@code app/cbl/COTRN00C.cbl:248} reported when the test at {@code :245} fails and
     * {@code :270} reported when the indicator test at {@code :267} fails, and three report an ordered
     * read reaching an end, {@code :608} after the browse position finds no row, {@code :642} after a
     * forward read reaches the end of the set and {@code :676} after a backward read reaches its start.
     * Three of the five speak of the top of the page and no two of the five are byte-identical, so
     * collapsing any pair would silently change the sentence a user reads.</p>
     *
     * <p>Refactoring Rationale: the browse's positioning identifier is bound from the query parameter
     * {@code transactionIdFilter}, and an earlier revision of this adapter bound it from a parameter
     * named {@code transactionId} instead. That was not a cosmetic difference. The published contract
     * declares the parameter as {@code transactionIdFilter} and the browser client sends that spelling,
     * so the earlier name silently discarded every filter a client supplied: a browse that was asked to
     * begin at an identifier answered from the beginning of the ordering instead, with nothing in the
     * response to distinguish that from a filter that genuinely matched the first row. The member path
     * below keeps {@code transactionId} because that is what the contract names there, so the two
     * spellings are deliberate and each matches its own declaration.</p>
     *
     * @param transactionIdFilter the identifier the browse is to begin at, of type {@code String}
     *     carrying exactly sixteen decimal digits when supplied, or {@code null} to begin at the start
     *     of the ordering; a blank value is the baseline's own condition at
     *     {@code app/cbl/COTRN00C.cbl:206-207}, where an unsupplied screen field is replaced by the
     *     lowest key rather than by spaces
     * @param cursor the sealed cursor token copied verbatim from a previous response, of type
     *     {@code String}, or {@code null} on an opening request; replayed exactly as received because
     *     any alteration invalidates the seal
     * @param direction the side of that cursor to read, of type {@code String} carrying one of the two
     *     wire tokens the contract's direction enumeration publishes, or {@code null} to take the
     *     request shape's own default; meaningful only alongside a cursor
     * @param principal the authenticated caller, of type {@link Principal} supplied by the framework,
     *     whose name the browse binds the outgoing cursor to; must not be {@code null}
     * @return a 200 response carrying the {@link PageResponse} envelope of
     *     {@link TransactionListItemResponse} rows with its two boundary keys and its two availability
     *     indicators; never {@code null}
     * @throws org.springframework.web.method.annotation.HandlerMethodValidationException if
     *     {@code transactionIdFilter} is neither absent nor exactly sixteen decimal digits, or if
     *     {@code direction} is neither of the two published wire tokens, both of which the shared advice
     *     renders as the 400 this operation publishes
     * @throws NullPointerException if {@code principal} is {@code null}, which is an unauthenticated
     *     request reaching a route the filter chain admits only to an authenticated one
     * @throws IllegalArgumentException if the caller's name is blank, because a cursor bound to no
     *     caller would be redeemable by any authorized one, and independently if the direction token
     *     reaches the request shape's resolver without matching a published value
     * @throws CursorToken.InvalidCursorException if {@code cursor} is not a token this seam issued for
     *     this query and this caller, or was issued longer ago than its lifetime; the shared advice
     *     renders it as 400 because the type derives from
     *     {@code com.carddemo.common.error.ClientInputException}
     * @throws IllegalStateException if an ordered read answers abnormally, carrying the lower-case
     *     sentence {@code app/cbl/COTRN00C.cbl} emits at {@code :615}, {@code :649} and {@code :683}
     */
    @GetMapping
    public ResponseEntity<PageResponse<TransactionListItemResponse>> listTransactions(
            @RequestParam(name = PARAM_TRANSACTION_ID_FILTER, required = false)
            @Size(min = TRANSACTION_ID_WIDTH, max = TRANSACTION_ID_WIDTH)
            @Pattern(regexp = TRANSACTION_ID_PATTERN) String transactionIdFilter,
            @RequestParam(name = PARAM_CURSOR, required = false) String cursor,
            @RequestParam(name = PARAM_DIRECTION, required = false)
            @Pattern(regexp = DIRECTION_PATTERN) String direction,
            Principal principal) {

        // WHY : Refactoring Rationale: the direction arrives as the wire token and is crossed into the
        //       constant by the request shape's own resolver, and an earlier revision of this adapter
        //       declared the parameter as the enumeration itself and let the framework convert it.
        //       That did not work and could not have been caught by either build: the framework's
        //       enum converter matches the Java identifier, so it accepts only the upper-case forms,
        //       while the published contract's enumeration and the browser client both send the
        //       lower-case tokens. Every backward page a client asked for was therefore refused as an
        //       unconvertible value. The resolver named below is where the case difference is crossed,
        //       and the shape that owns the enumeration documents it as the entry point a controller
        //       binds this parameter through rather than relying on a converter's case policy.
        TransactionListRequest request = new TransactionListRequest(transactionIdFilter, cursor,
                TransactionListRequest.Direction.fromWireValue(direction));

        // WHY : Assumptions: the name is required before it is used rather than dereferenced and
        //       allowed to fail, because the two failures are not equally diagnosable. The filter
        //       chain admits these routes only to an authenticated caller, so a null here means the
        //       chain was bypassed or misconfigured, and a named requirement says that in the message
        //       while a bare dereference reports only that something was null.
        Objects.requireNonNull(principal, "principal must not be null");

        // WHY : Assumptions: the envelope is returned exactly as the browse assembled it. This adapter
        //       does not construct one, does not set the forward indicator and does not compute a
        //       boundary key, because the row that decides each of those is the row the browse read;
        //       recomputing any of them here would need the rows again and would be a second opinion
        //       about the same read.
        return ResponseEntity.ok(this.listService.listTransactions(request, this.cursorToken,
                principal.getName()));
    }

    /**
     * Reads one transaction by its identifier, serving {@code CT01} <b>Transaction View</b>.
     *
     * <p>Purpose: this handler answers a {@code GET} on the member path with the thirteen record fields
     * the baseline writes to its screen at {@code app/cbl/COTRN01C.cbl:176-192}, from the identifier at
     * {@code :178} through the merchant postal code at {@code :190}, together with the nullable
     * 75-character aggregate message the shared contract carries.</p>
     *
     * <p>Refactoring Rationale: the drill-down from the list screen becomes the identifier in the request
     * path, and the mechanism it replaces is worth stating because what disappears matters more than what
     * remains. The baseline enters through {@code IF NOT CDEMO-PGM-REENTER} at
     * {@code app/cbl/COTRN01C.cbl:99}, sets that flag true at {@code :100}, clears its output map at
     * {@code :101}, and then, at {@code :103-104}, tests whether an identifier handed over from the list
     * screen is neither spaces nor low values; if so it moves that identifier into the keyed input field
     * at {@code :105-106} and performs its own enter-key paragraph at {@code :107} before sending the
     * screen at {@code :109}. So the record is looked up without the user pressing anything, but only on
     * the first of the two turns the screen takes. The discriminator that decides which turn it is,
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)} at {@code app/cpy/COCOM01Y.cpy:29} with an enter value at
     * {@code :30} and a re-enter value at {@code :31}, has no counterpart here and its absence is
     * deliberate. What was wrong with the approach being replaced is precise: continuity lived in a
     * structure the client held between turns and handed back, so a handler had to know which turn it was
     * in before it could decide what to render, and the highlight logic downstream was gated on that same
     * flag. A stateless handler addressed by an identifier has no first-entry-against-re-entry
     * distinction left to draw, so the lookup is simply what the endpoint does and error rendering is
     * driven by response data alone. No {@code isResubmit}, {@code firstEntry} or {@code turnCount}
     * concept may be introduced here under any name.</p>
     *
     * <p>Assumptions: the fifth function key on this screen navigates and does not save, and it is
     * described specifically rather than by the module-wide key table for that reason. At
     * {@code app/cbl/COTRN01C.cbl:125-127} it moves {@code 'COTRN00C'} into the outgoing program field
     * and performs the return-to-previous paragraph, so it goes back to Transaction List. The same key
     * means something different one screen away: at {@code app/cbl/COTRN02C.cbl:146-147} it performs the
     * copy-last-transaction paragraph, and that paragraph ends by performing the enter-key paragraph at
     * {@code :495}, so there it copies the previous submission into the form and re-runs the whole
     * validation and confirmation chain. Neither is the save the module-wide table assigns to that key.
     * All three readings are client-side navigation or client-side form population in the target, so no
     * response from this handler names a key, a program or a next screen; the deviation is recorded so
     * that a reader consulting the table alone does not attribute a save to either screen.</p>
     *
     * <p>Refactoring Rationale: this handler performs an unlocked read, and the baseline's equivalent
     * does not. Its {@code READ-TRANSACT-FILE} paragraph at {@code app/cbl/COTRN01C.cbl:269-278} carries
     * the {@code UPDATE} option at {@code :275}, so a screen that never issues a rewrite anywhere in its
     * 330 lines nevertheless takes an exclusive record lock, and that lock is held until the task ends at
     * the {@code EXEC CICS RETURN} of {@code :136}. The concrete consequence is that the baseline
     * serialises two users who merely look at the same transaction, each waiting on a lock neither will
     * use, and the target does not serialise them because the read beneath this handler is declared
     * read-only. The baseline behaves as described, the target reads without locking, and the divergence
     * is documented rather than introduced silently.</p>
     *
     * <p>Assumptions: the card number in the response is masked to its last four digits, and the baseline
     * shows all sixteen. At {@code app/cbl/COTRN01C.cbl:179} it moves {@code TRAN-CARD-NUM} straight into
     * the screen field, so the whole primary account number is displayed. Masking is performed in this
     * context's mapper package and never here, this handler offers no parameter that could defeat it, and
     * no verification value is carried by any shape it returns. The baseline behaves as described, the
     * target masks, and the divergence is documented as a deliberate narrowing of exposure; an unmasked
     * administrative view of a card exists in card-service and not on this route.</p>
     *
     * <p>Trade-offs: the identifier's shape is asserted at this boundary as well as inside the service,
     * and the two do not report the same sentence. A value that is not sixteen digits is refused here and
     * rendered by the shared advice as the 400 the contract publishes for exactly that case, while a
     * value that was never supplied at all is refused by the service carrying the baseline's own sentence
     * from {@code app/cbl/COTRN01C.cbl:149}, emitted there from a one-branch selection at
     * {@code :144-156}. The alternative of asserting nothing here and letting every malformed identifier
     * reach the service was weighed: its concrete cost is that a value the published contract already
     * declares inadmissible would still open the read-only transaction the service declares, so the
     * database would be consulted for a key the contract says cannot exist. The cost accepted instead is
     * that one narrow case, a segment of sixteen characters that are not all digits, reports the shape
     * refusal rather than the absence sentence, which is the division the contract itself draws when it
     * describes that status as covering an identifier that is absent or is not sixteen digits.</p>
     *
     * <p>Assumptions: the identifier travels as a digits-validated string and never as an integral
     * numeric type, and the baseline is the reason. {@code app/cpy/CVCRD01Y.cpy} declares each identifier
     * twice over the same bytes, as characters at {@code :34}, {@code :37} and {@code :40} and as numbers
     * redefining those characters at {@code :36}, {@code :39} and {@code :42}, so the baseline itself
     * treats them as characters on the wire and as numbers only inside arithmetic. A numeric member here
     * would drop a leading zero on the way out while still comparing equal on the way in, and the stored
     * key is a declared-width character column of exactly the width recorded in {@link
     * #TRANSACTION_ID_WIDTH}, matching {@code TRNIDINI PIC X(16)} at {@code app/cpy-bms/COTRN01.CPY:60}.
     * </p>
     *
     * @param transactionId the identifier of the transaction to read, of type {@code String} carrying
     *     exactly sixteen decimal digits, taken from the request path exactly as the caller addressed it
     *     and neither padded nor reformatted here
     * @return a 200 response carrying the {@link TransactionDetailResponse} for that transaction, its
     *     card number masked to the last four digits and its amount exact to two decimal places; never
     *     {@code null}
     * @throws org.springframework.web.method.annotation.HandlerMethodValidationException if
     *     {@code transactionId} is not exactly sixteen decimal digits, which the shared advice renders as
     *     the 400 this operation publishes
     * @throws com.carddemo.common.error.ClientInputException if the identifier was never supplied in any
     *     of the forms the baseline treats as empty, carrying the sentence at
     *     {@code app/cbl/COTRN01C.cbl:149} and naming one field, rendered as 400 with one entry in the
     *     per-field array
     * @throws IllegalArgumentException as the supertype of the refusal above, and independently if a
     *     stored merchant identifier or timestamp falls outside the domain its baseline field declares;
     *     both types are named because this check holds no model of the exception hierarchy
     * @throws java.util.NoSuchElementException if no stored transaction carries that identifier, carrying
     *     the sentence at {@code app/cbl/COTRN01C.cbl:285} and rendered as 404
     * @throws IllegalStateException if the keyed read fails for any reason other than absence, carrying
     *     the capitalised sentence at {@code app/cbl/COTRN01C.cbl:292} and rendered as 500
     * @throws ArithmeticException if a stored amount cannot be reduced to the two decimal places the
     *     money contract carries, raised by the conversion beneath rather than here
     */
    @GetMapping(path = ITEM_PATH)
    public ResponseEntity<TransactionDetailResponse> viewTransaction(
            @PathVariable(name = PATH_TRANSACTION_ID)
            @Size(min = TRANSACTION_ID_WIDTH, max = TRANSACTION_ID_WIDTH)
            @Pattern(regexp = TRANSACTION_ID_PATTERN) String transactionId) {

        // WHY : Assumptions: the identifier is handed on untouched. The baseline moves its keyed-in
        //       field into the record key unchanged, and the stored key is a declared-width character
        //       column, so a padding or trimming rule invented here would be a rule the baseline does
        //       not apply and would make this adapter answer for a key the caller never named.
        return ResponseEntity.ok(this.viewService.viewTransaction(transactionId));
    }

    /**
     * Captures one transaction, or answers with the prompt the baseline answers an unconfirmed turn with,
     * serving {@code CT02} <b>Transaction Add</b>.
     *
     * <p>Purpose: this handler answers a {@code POST} on the collection path. It re-expresses the
     * enter-key paragraph of {@code app/cbl/COTRN02C.cbl} at {@code :164-188}, which validates the key
     * fields, validates the data fields and only then examines the confirmation, appending a record from
     * the affirmative arm at {@code :172} alone. The submitted shape carries no identifier because the
     * server assigns one.</p>
     *
     * <p>Assumptions: the identifier is minted beneath this adapter and this handler neither generates,
     * predicts nor validates it. The baseline mints it by walking to the highest key and adding one,
     * {@code MOVE HIGH-VALUES}, {@code STARTBR}, {@code READPREV}, {@code ENDBR}, {@code MOVE} and
     * {@code ADD 1} at {@code app/cbl/COTRN02C.cbl:444-449}, and its end-of-file arm at {@code :688-689}
     * moves zeros into the key so that the first identifier over an empty table is one. All of that is
     * {@code TransactionAddService}'s business, and asserting anything about the result here would be a
     * second implementation of the same rule.</p>
     *
     * <p>Refactoring Rationale: the status is selected from which of the two published outcomes the
     * service produced, and an earlier revision of this adapter answered 200 for both. That revision did
     * not satisfy the published contract, which declares 201 for a written transaction with a
     * <b>required</b> {@code Location} header addressing it, and 200 for a withheld confirmation that
     * wrote nothing. The concrete consequence of collapsing them was that a client could not tell a
     * capture from a prompt by status, and the header the contract obliges this operation to send was
     * never sent at all, so a caller following it to the new resource had nothing to follow. Selecting
     * between two published statuses from an outcome the service already decided is binding work of the
     * same kind as sealing a cursor, not a business rule: the decision whether to write was taken
     * beneath, and this handler only renders it.</p>
     *
     * <p>Assumptions: the two outcomes are told apart by whether the answer carries an identifier, and
     * the service's own behaviour is the authority for that rather than an inference. On the unconfirmed
     * path it answers with no identifier and the prompt sentence from
     * {@code app/cbl/COTRN02C.cbl:178}, which its affirmative-arm test at {@code :169-176} reaches for
     * {@code 'N'}, {@code 'n'}, spaces and low values alike; on the written path it answers with the
     * identifier it assigned. Answering an error for a withheld confirmation would report a failure where
     * the baseline reports a prompt, and answering 201 there would claim a write that did not happen.</p>
     *
     * <p>Assumptions: the {@code Location} header carries an origin-relative path and not an absolute
     * URL. An absolute URL would have to name a host, and the host this task observes is the internal
     * load balancer rather than the edge the caller reached, so the URL would address something the
     * caller cannot resolve. The path is composed from {@link #BASE_PATH} and the assigned identifier so
     * that it is the member path this adapter itself serves, and the identifier is safe to place in a path
     * because the service assigns it as decimal digits alone, of the width {@link #TRANSACTION_ID_WIDTH}
     * records.</p>
     *
     * <p>Alternatives Considered: distinguishing the baseline's two duplicate conditions as two statuses,
     * or as two sentences, was evaluated and rejected. Its write paragraph at
     * {@code app/cbl/COTRN02C.cbl:711-749} selects on the file response at {@code :723}: the normal arm at
     * {@code :724} succeeds, then a duplicate-key arm at {@code :735} and a duplicate-record arm at
     * {@code :736} fall through to one handler that emits a single sentence at {@code :738}, and the
     * remaining arm at {@code :742} emits its own sentence at {@code :745} after a diagnostic at
     * {@code :743}. Because the two duplicate arms share one handler, the baseline shows the user one
     * outcome, so both map to 409 Conflict here. Separating them would surface a distinction the baseline
     * never shows, which a client would then be entitled to branch on. That sentence also reads
     * {@code 'Tran ID already exist...'} in the singular, and it is carried across exactly as written
     * rather than being regularised, because transformation rule T8 of the technical specification takes
     * user-visible text verbatim. An optimistic-lock conflict reaches the same status through the same
     * shared advice, which owns that mapping; nothing here implements it.</p>
     *
     * <p>Alternatives Considered: returning every validation failure at once, which is the usual reading
     * of a per-field error array, was evaluated and rejected. The baseline reports exactly one sentence
     * per submission, and the mechanism is worth stating precisely because a looser summary would mislead.
     * Its key-field paragraph at {@code app/cbl/COTRN02C.cbl:193-230} selects one arm, giving the account
     * branch precedence at {@code :196} over the card branch at {@code :210} and falling through to
     * {@code :224} with the sentence at {@code :226} when neither was supplied. Its data-field paragraph
     * at {@code :235-437} is eight sequential blocks that do not guard one another: the first, at
     * {@code :237-249}, clears the eleven data fields when the error switch is already set rather than
     * skipping them; the second, at {@code :251-320}, is one selection over eleven emptiness tests that
     * short-circuits to a single sentence, the first of them at {@code :254}; the rest check the two
     * numeric codes from {@code :322}, the amount's twelve-character signed form from {@code :339} with
     * the sentence at {@code :345}, the two dates' shape from {@code :353} and {@code :368} with the
     * sentences at {@code :360} and {@code :375}, the two dates' validity from {@code :389} and
     * {@code :409} with the sentences at {@code :401} and {@code :421}, and the merchant identifier at
     * {@code :430} with the sentence at {@code :432}. The confirmation selection at {@code :169} then runs
     * with no guard on that switch at all. Since each arm ends by re-sending the screen, and the send at
     * {@code :520} overwrites the message line, the user reads the sentence of whichever construct fired
     * last: first-arm-wins within the emptiness selection, last-block-wins across the key fields, the
     * eight data blocks and the confirmation. So on a first submission with the confirmation left blank,
     * the sentence displayed is the prompt at {@code :178} even when a field was also empty. Reporting
     * every failure would therefore show a client errors the terminal never showed, and a client
     * rendering the array would highlight fields the screen never highlighted. The array this operation
     * answers with legitimately carries one entry, in the baseline's own precedence order, and its entry
     * carries a {@code com.carddemo.common.validation.FieldValidationFlag} state so that a field left
     * blank is distinguishable from a field filled in wrongly: the baseline's templated highlight moves a
     * red attribute into a field whose validation flag is not-OK and additionally moves a literal
     * {@code '*'} into it when the field is blank, and that marker is carried on the flag rather than
     * being re-derived by a client.</p>
     *
     * <p>Assumptions: the acknowledgement sentence is assembled from three fragments and contains two
     * consecutive spaces, and that is deliberate rather than accidental. The baseline builds it at
     * {@code app/cbl/COTRN02C.cbl:728-733} by concatenating a first fragment that already ends in a space,
     * a second that both begins and ends with one, the identifier trimmed at its first space, and a full
     * stop; the emitted text is 66 characters long and the two spaces fall together immediately after the
     * first fragment's full stop. It is also a success-severity message rather than an error one, the
     * colour attribute being moved separately at {@code :727} into a subfield distinct from the text, and
     * the form is cleared first at {@code :725}. {@code TransactionAddService} owns that assembly and this
     * handler neither composes nor rewrites it. The consequence of not knowing this is concrete: a single
     * format string written in the obvious way would emit one space and be byte-wrong against the
     * baseline, and a future reader tidying the two spaces away would break the same guarantee.</p>
     *
     * @param request the submitted capture, of type {@link TransactionAddRequest}, whose fourteen
     *     members open with the account identifier and close with the confirmation and which carries no
     *     transaction identifier; validated against its declared constraints before this method is
     *     entered, and never {@code null}
     * @return a 201 response carrying the {@link TransactionAddResponse} for a written transaction, with
     *     the assigned identifier, the normalised amount and the baseline's acknowledgement sentence, and
     *     a {@code Location} header addressing it; or a 200 response carrying the
     *     {@link TransactionAddPreview} shape -- the normalised amount, the discriminator fixed false and
     *     the prompt -- when the confirmation was withheld and nothing was written; never {@code null}
     * @throws NullPointerException if the deserialised body is {@code null}
     * @throws org.springframework.web.bind.MethodArgumentNotValidException if a submitted member breaks a
     *     constraint the request shape declares, which the shared advice renders as 400 with one entry per
     *     violated member
     * @throws com.carddemo.common.error.ClientInputException if a key field, a data field or the
     *     confirmation carries a value the baseline refuses, carrying that program's own sentence and
     *     naming one field, rendered as 400
     * @throws IllegalArgumentException as the supertype of the refusal above, named separately because
     *     this check holds no model of the exception hierarchy
     * @throws java.util.NoSuchElementException if the supplied account or card resolves to no
     *     cross-reference entry, carrying the absence sentence for whichever key was supplied, rendered as
     *     404
     * @throws com.carddemo.common.error.RecordConflictException if the assigned identifier is already
     *     stored, carrying the sentence at {@code app/cbl/COTRN02C.cbl:738} that the baseline emits for
     *     both of its duplicate conditions, rendered as 409
     * @throws IllegalStateException if a read or the append fails for a reason the caller cannot correct,
     *     carrying the sentence the baseline emits for that operation, rendered as 500
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransactionAddOutcome> addTransaction(
            @Valid @RequestBody TransactionAddRequest request) {
        return answer(this.addService.addTransaction(request));
    }

    /**
     * Re-captures the most recently stored transaction as a new one.
     *
     * <p>Purpose: this publishes {@code COPY-LAST-TRAN-DATA} at line 471 of
     * {@code app/cbl/COTRN02C.cbl}, which the baseline reaches from PF5 at lines 146 and 147. The action
     * validates the key fields at line 473, reads the most recent record backwards across lines 475 to
     * 478, copies eleven of its data columns across lines 480 to 493 and then re-enters
     * {@code PROCESS-ENTER-KEY} at line 495 -- so a copied capture travels the full validation chain and
     * is written or previewed by exactly the rule the capture operation follows.</p>
     *
     * <p>⚠️ Refactoring Rationale: this handler exists because the service method it calls was reachable
     * from nothing. {@code TransactionAddService.copyLastTransactionData} was authored and unit-tested and
     * no controller, contract or client addressed it, which reads from outside as a missing baseline
     * capability and from inside as dead code. Publishing it is the cheaper of the two available
     * corrections, the other being to delete a transcription of an action the baseline demonstrably has;
     * AAP section 0.5.1.5 maps {@code COTRN02C} wholesale onto this context, so deleting it would have
     * been a silent reduction of scope.</p>
     *
     * <p>Alternatives Considered: a member on the capture request -- a {@code copySource} discriminator
     * on {@link TransactionAddRequest} -- rather than a route of its own. Rejected because the baseline
     * reaches this action from a DIFFERENT attention identifier than Enter, so it is a separate action
     * and not a variant of the capture, and because a flag would make eleven of that request's members
     * conditionally meaningless with no schema able to express when.</p>
     *
     * <p>Assumptions: the authority required is the one the catch-all rule of this module's security
     * chain applies, which is the same authority the capture operation requires. It is deliberately not
     * narrowed to an administrative authority: the baseline binds this action to a function key on the
     * ordinary capture screen, available to whoever may capture a transaction at all, so requiring more
     * here would refuse an operator the baseline admits.</p>
     *
     * <p>Assumptions: the eleven data members of the submission are OVERWRITTEN from the copied record
     * rather than merged with it, because lines 482 to 492 move the record's own columns over them
     * unconditionally. They stay required by the request shape because line 473 validates the key fields
     * against the same screen the operator was already on, so the submission is a full one either way.
     * </p>
     *
     * @param request the submission whose key members select the account or card and whose confirmation
     *     decides whether the copied capture is written; validated against its declared constraints
     *     before this method is entered, and never {@code null}
     * @return a 201 response carrying the {@link TransactionAddResponse} for a written transaction with a
     *     {@code Location} header addressing it, or a 200 response carrying the
     *     {@link TransactionAddPreview} shape when the confirmation was withheld; never {@code null}
     * @throws NullPointerException if the deserialised body is {@code null}
     * @throws org.springframework.web.bind.MethodArgumentNotValidException if a submitted member breaks a
     *     constraint the request shape declares, rendered as 400
     * @throws com.carddemo.common.error.ClientInputException if a key field, a copied data field or the
     *     confirmation carries a value the baseline refuses, rendered as 400
     * @throws java.util.NoSuchElementException if the supplied key resolves to no cross-reference entry,
     *     or if the table holds no transaction to copy, rendered as 404
     * @throws com.carddemo.common.error.RecordConflictException if the assigned identifier is already
     *     stored, rendered as 409
     * @throws IllegalStateException if a read or the append fails for a reason the caller cannot correct,
     *     rendered as 500
     */
    @PostMapping(path = COPY_LAST_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransactionAddOutcome> copyLastTransaction(
            @Valid @RequestBody TransactionAddRequest request) {
        return answer(this.addService.copyLastTransactionData(request));
    }

    /**
     * Chooses the status, body and {@code Location} header for one capture outcome.
     *
     * <p>⚠️ Refactoring Rationale: the outcome is matched on its TYPE, where an earlier revision tested
     * whether one record's identifier was null. That test worked only because the service returned the
     * capture shape on both turns with the identifier absent on one of them -- a body the published
     * {@code TransactionAddPreview} schema rejects outright, since it declares no {@code transactionId}
     * and closes its object. Now that the two turns return the two published shapes, the identifier is
     * present exactly where it exists and the discriminator is the type itself.</p>
     *
     * <p>Alternatives Considered: an {@code instanceof} test with a cast. Rejected because a switch over
     * the sealed hierarchy is checked for exhaustiveness by the compiler, so a third outcome added to
     * that hierarchy fails this method to compile rather than silently taking a default arm.</p>
     *
     * <p>Assumptions: this is a private helper rather than inline code in the handler, because two
     * handlers answer with the same pair of shapes -- the capture operation and the copy-last operation
     * -- and one of them assembling the {@code Location} header differently from the other is the kind of
     * divergence nothing would catch.</p>
     *
     * @param outcome the service's answer, being either shape of the sealed hierarchy; must not be
     *     {@code null}
     * @return 201 with the {@code Location} header when a transaction was written, and 200 with the
     *     preview body when none was; never {@code null}
     */
    private ResponseEntity<TransactionAddOutcome> answer(TransactionAddOutcome outcome) {
        return switch (outcome) {
            case TransactionAddPreview preview -> ResponseEntity.ok(preview);
            // WHY : Trade-offs: the path is composed by concatenation rather than by a URI builder
            //       reading the current request. A builder would resolve against the host and scheme
            //       this task observed, which behind an internal load balancer and an edge is not what
            //       the caller used; concatenation yields the origin-relative form the contract
            //       publishes. The cost is that this one path is written here as well as being declared
            //       in the mapping above, and it is composed from the same constant the mapping uses so
            //       the two cannot diverge.
            case TransactionAddResponse written ->
                    ResponseEntity.created(URI.create(BASE_PATH + "/" + written.transactionId()))
                            .body(written);
        };
    }
}
