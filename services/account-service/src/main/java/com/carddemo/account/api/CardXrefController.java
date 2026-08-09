package com.carddemo.account.api;

import com.carddemo.account.dto.AccountLookupRequest;
import com.carddemo.account.dto.CardXrefLookupRequest;
import com.carddemo.account.dto.CardXrefResponse;
import com.carddemo.account.dto.CardXrefView;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// WHAT: the REST entry point for the card cross-reference, carrying both of the record's access paths --
//       the one keyed on the card number and the one keyed on the account.
// WHY : Assumptions: the two paths are not a design choice made here, they are the two access paths the
//       reference system itself provides over one physical file. app/cbl/CBACT03C.cbl L32 declares
//       RECORD KEY IS FD-XREF-CARD-NUM, so the base cluster answers by card and by card alone;
//       app/csd/CARDDEMO.CSD L63 defines a second CICS file resource over the same data whose own
//       description at L64 reads ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY. A migration that published
//       only the keyed read would drop an access path the reference has, and no query against the
//       card-number key can reconstruct it.

/**
 * Publishes the card cross-reference lookups this context owns, keyed on the card and keyed on the account.
 *
 * <h2>What this controller is</h2>
 *
 * <p>Purpose. This is the REST surface over the 50-byte cross-reference record whose layout
 * {@code app/cpy/CVACT03Y.cpy} declares at L4 through L8, and it binds three operations onto it: the read
 * keyed on the card number, the read keyed on the account, and the paged walk of one account's rows. It
 * carries request binding, declarative validation and nothing else. Every rule about what a row means, what
 * an absent row means and how a row is represented on the wire belongs one layer down, and the paragraphs
 * below record why each of those was placed there rather than here.</p>
 *
 * <p>Assumptions: the reference program this surface descends from is BATCH rather than online.
 * {@code app/cbl/CBACT03C.cbl} declares {@code PROGRAM-ID.    CBACT03C.} at L23 and reads its file
 * sequentially end to end -- {@code 0000-XREFFILE-OPEN.} at L118 opening at L120,
 * {@code 1000-XREFFILE-GET-NEXT.} at L92 advancing at L93, {@code 9000-XREFFILE-CLOSE.} at L136 releasing
 * at L138 -- so it binds no CICS transaction and appears in no {@code DEFINE TRANSACTION} stanza of
 * {@code app/csd/CARDDEMO.CSD}. There is therefore no screen whose field layout fixes this surface's shape,
 * which is why the operations below are derived from the record contract and from the two declared access
 * paths instead of from a map.</p>
 *
 * <h2>Why an account-keyed operation exists at all</h2>
 *
 * <p>Assumptions: the account-keyed read replaces a real alternate index rather than adding a convenience,
 * and four independent places in the reference establish that. First, the alternate index is a declared CICS
 * file resource in its own right: {@code app/csd/CARDDEMO.CSD} L63 reads
 * {@code DEFINE FILE(CXACAIX) GROUP(CARDDEMO)}, its own description at L64 states its purpose as
 * {@code DESCRIPTION(ALTERNATE INDEX TO CCXREF VIA ACCOUNT KEY)}, and it is configured for use like any
 * other file at L72 with {@code JNLSYNCWRITE(YES) RECOVERY(NONE) FWDRECOVLOG(NO)}. Second, the dataset names
 * show two access paths over one physical stem: the alternate index is
 * {@code DSNAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH)} at L65 while the base cluster, whose CICS resource
 * name is {@code CCXREF} at L37 and whose description at L38 is
 * {@code DESCRIPTION(CARD TO ACCOUNT XREF)}, is {@code DSNAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)} at L39 --
 * the same base name ending {@code .AIX.PATH} against {@code .KSDS}. Third, an online program declares the
 * path by name and reads through it: {@code app/cbl/COACTVWC.cbl} L192 and L193 declare
 * {@code LIT-CARDXREFNAME-ACCT-PATH PIC X(8) VALUE 'CXACAIX '}, the literal carrying a trailing space that
 * pads it to its declared width of eight, and paragraph {@code 9200-GETCARDXREF-BYACCT.} at L723 consumes it
 * at L727 through L732 as {@code EXEC CICS READ} with {@code DATASET (LIT-CARDXREFNAME-ACCT-PATH)},
 * {@code RIDFLD (WS-CARD-RID-ACCT-ID-X)}, {@code KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)} and
 * {@code INTO (CARD-XREF-RECORD)}. Fourth, the base cluster cannot serve that read: its key is the card
 * number, per {@code RECORD KEY IS FD-XREF-CARD-NUM} at L32 of {@code app/cbl/CBACT03C.cbl}, so
 * account-keyed access is unavailable without the alternate index. The target resolves the same access path
 * through the non-unique secondary index {@code idx_card_xref_account_id}, created by this module's
 * {@code db/migration/V1__account.sql}.</p>
 *
 * <p>Assumptions: only {@code CCXREF} and {@code CXACAIX} are this controller's concern, and the neighbouring
 * resources in the same file belong elsewhere. {@code app/csd/CARDDEMO.CSD} also defines {@code CARDAIX} at
 * L13 and {@code CARDDAT} at L25, which the card context owns, and {@code TRANSACT} at L76 and
 * {@code USRSEC} at L88, which belong to other contexts again. The distinction is worth stating because
 * {@code app/cbl/COACTVWC.cbl} declares a second, similarly named literal at L190 and L191 --
 * {@code LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} -- which reads cards by account rather than
 * the cross-reference by account, and reading one for the other would attribute this controller's access
 * path to the wrong context.</p>
 *
 * <h2>The layer this class sits in</h2>
 *
 * <p>Trade-offs: this class reaches the store through the service layer only, and it holds neither a
 * repository nor a mapper. What that costs is one indirection on operations whose bodies are a single
 * delegating call, and it is accepted for two reasons that a shorter route would give up. The unit of work
 * is declared on the service method, so a controller holding a repository would put the transaction boundary
 * on the request binding, where nothing can enforce it. And the representation decisions the mapper carries
 * -- dropping the record's padding, and reducing a primary account number to its last four digits -- would
 * then be reachable from two layers, so the direction is fixed as service to mapper and never controller to
 * mapper. The boundary is asserted mechanically rather than by convention, by the rules in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}, which
 * forbid web types inside a domain package.</p>
 *
 * <p>Assumptions: no reference-baseline entity crosses this surface in either direction. The stored row type
 * appears in no signature, no local and no import here; the request bodies and response bodies are the
 * published transfer records of {@code com.carddemo.account.dto}. This is worth stating rather than leaving
 * implicit, because the stored cross-reference row is the one entity in this context that declares no version
 * column -- the record has no update path, which is also why every operation on this controller reads.</p>
 *
 * <p>Trade-offs: refusals are rendered by the shared advice in
 * {@code com.carddemo.common.error.GlobalExceptionHandler} rather than by an advice declared here, and by
 * the problem shape of {@code com.carddemo.common.error.ApiError}, whose nested per-field entry carries the
 * validation failures. Declaring either locally would give this service a second definition of a shape every
 * published contract in the migration already fixes, and the two would then drift apart one release at a
 * time. What is given up is the ability to phrase a refusal differently here, which is not something this
 * surface has any reason to want.</p>
 *
 * <h2>No state survives a request</h2>
 *
 * <p>Refactoring Rationale: the reference carried its continuity in the 160-byte structure
 * {@code app/cpy/COCOM01Y.cpy} declares from L19 to L44, and none of it is reproduced on this surface. The
 * identity half is the substantive change: {@code CDEMO-USER-ID} at L25 and {@code CDEMO-USER-TYPE} at L26,
 * whose conditions at L27 and L28 spell the two user kinds as {@code 'A'} and {@code 'U'}, travelled in
 * storage the terminal echoed back, and {@code app/csd/CARDDEMO.CSD} shows at L314 and L324 that both
 * account transactions ran with {@code RESSEC(NO) CMDSEC(NO)} -- no resource or command security at the
 * region level -- so that echoed field WAS the authorization. Here the caller cannot assert it: the identity
 * arrives as claims on a validated token, converted to authorities by
 * {@code com.carddemo.common.security.JwtRoleConverter}, and which route each authority reaches is decided by
 * {@code com.carddemo.account.config.SecurityConfig}. No operation below accepts a user identifier or a user
 * kind as a parameter, a header or a body member.</p>
 *
 * <p>Assumptions: the selection half of that structure becomes request parameters, and the two selectors it
 * declares are exactly this controller's two keys -- {@code CDEMO-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/COCOM01Y.cpy} L38 and {@code CDEMO-CARD-NUM PIC 9(16)} at L41. Carrying them in the request
 * is what makes each request describe itself, and therefore what makes each one authorizable on its own
 * rather than against something remembered from a previous turn.</p>
 *
 * <p>Refactoring Rationale: the re-entry discriminator has no counterpart here and its absence is
 * deliberate. {@code app/cpy/COCOM01Y.cpy} L29 declares {@code CDEMO-PGM-CONTEXT} with conditions at L30 and
 * L31 distinguishing a first entry from a re-entry, because a task that ended at every screen turn had to be
 * told which turn it was on. A handler that answers each request completely has no such distinction to make,
 * and the coupling this severs is real: the reference gates its field highlighting on that flag at L18
 * through L27 of {@code app/cpy/CSSETATY.cpy}, whereas here the error presentation is carried entirely by the
 * response body. The reference had nowhere else to put it, which {@code app/csd/CARDDEMO.CSD} confirms at
 * L308 and L318 with {@code TWASIZE(0)} on both account transactions: with no transaction work area, all
 * continuity lived in the echoed structure.</p>
 *
 * <p>Assumptions: the correlation identifier is attached and ordered by
 * {@code com.carddemo.account.config.SecurityConfig}, and the document-level metadata of the published
 * contract is owned by {@code com.carddemo.account.config.OpenApiConfig}. Neither is registered or restated
 * here. What this class does own is the per-operation half of that contract, which is kept in step with
 * {@code src/main/resources/openapi/account-api.yaml} by the guard in
 * {@code com.carddemo.account.api.AccountContextContractTest}, comparing the mounted operations against the
 * published ones in both directions.</p>
 *
 * <p>Assumptions: no golden-master oracle covers this surface, so its parity rests on the transcribed access
 * paths and the record contract cited above rather than on a comparison run. {@code tests/README.md} states
 * at L83 through L85 that the online programs cannot run end to end without a CICS runtime, which the runner
 * does not have, and its section 13 beginning at L553 asserts business rules for the posting, interest and
 * category-balance programs only -- {@code CBACT03C} is not among them.</p>
 */
@RestController
@RequestMapping(CardXrefController.BASE_PATH)
public class CardXrefController {

    /**
     * The path prefix every operation in this controller sits beneath.
     *
     * <p>Assumptions: this is a constant rather than a literal in the annotation, so that the load-balancer
     * rule and the gateway route that forward this prefix can be checked against it by a test rather than by
     * a reader comparing two files.</p>
     */
    public static final String BASE_PATH = "/api/v1/card-xrefs";

    /**
     * The path segment the card-keyed lookup sits at, relative to {@link #BASE_PATH}.
     */
    public static final String LOOKUP_PATH = "/lookup";

    /**
     * The path segment the account-keyed lookup sits at, relative to {@link #BASE_PATH}.
     */
    public static final String LOOKUP_BY_ACCOUNT_PATH = "/lookup-by-account";

    /**
     * The path segment the paged account-keyed walk sits at, relative to {@link #BASE_PATH}.
     */
    public static final String SEARCH_BY_ACCOUNT_PATH = "/search-by-account";

    /**
     * The two directions a paged walk may be asked to step in.
     *
     * <p>Assumptions: the domain is closed to these two words, so a third value is refused as a validation
     * failure at the boundary rather than reaching the service and being read as one of them by default. The
     * reference distinguishes exactly these two steps and no others, at L242 through L244 of
     * {@code app/cbl/COCRDLIC.cbl} for the forward step and by its separate backward read.</p>
     */
    private static final String DIRECTION_DOMAIN = "next|previous";

    /**
     * The read path this controller binds requests onto.
     */
    private final AccountViewService reads;

    /**
     * Creates the controller.
     *
     * <p>Assumptions: the single collaborator is the service layer, not a repository and not the mapper. The
     * charter for this package forbids both of those here, and the migration plan assigns this layer binding
     * and validation while assigning the rules one layer down; a controller holding a repository would place
     * the transaction boundary at the HTTP binding and would make the boundary unenforceable.</p>
     *
     * @param reads the account read path; must not be {@code null}
     * @throws NullPointerException if {@code reads} is {@code null}
     */
    public CardXrefController(AccountViewService reads) {
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
    }

    /**
     * Resolves a primary account number to the account and customer it belongs to.
     *
     * <p>Assumptions: this is the keyed read the base cluster serves, and the key is the record's own.
     * {@code app/cbl/CBACT03C.cbl} declares {@code RECORD KEY IS FD-XREF-CARD-NUM} at L32 for the file whose
     * layout it copies in at L45, and {@code XREF-CARD-NUM} is the leading {@code PIC X(16)} field at L5 of
     * {@code app/cpy/CVACT03Y.cpy}.</p>
     *
     * <p>Assumptions: the operation is a {@code POST} although it is a read with no side effect, and the
     * reason is disclosure rather than semantics. Its key is a primary account number, and a request path or
     * query string is recorded by the load balancer's access log, by the gateway's execution log and by every
     * intermediary between them -- none of which the migration's security mapping permits to hold that value.
     * A request body is written to none of them. The trade-off this accepts, and why it costs nothing for the
     * one consumer, is recorded on {@link CardXrefLookupRequest}.</p>
     *
     * <p>Assumptions: the transaction boundary sits on the service method this handler calls rather than on
     * this handler, so the read-only declaration governs the unit of work rather than the request
     * binding.</p>
     *
     * <p>Assumptions: an absent row is answered with 404 and never with a 200 carrying an empty document. The
     * consumer distinguishes the two and depends on the distinction: it treats 404 as a decision input -- the
     * reference declines such a request with its card-not-found reason -- and treats a 200 whose body it
     * cannot build a cross-reference from as a dependency failure that rolls its transaction back. A 200 with
     * nothing in it would therefore be read as a broken contract rather than as an absent card, which is the
     * opposite of what it would mean. The absence is signalled by throwing rather than by returning an empty
     * body, and the exception type is the one the shared advice already renders as 404 with this system's
     * problem document.</p>
     *
     * @param request the lookup request carrying the card number; must satisfy its declared constraints
     * @return the account and customer the card resolves to, a {@link CardXrefView}, never {@code null}
     * @throws NoSuchElementException if the card is not cross-referenced, which the shared advice renders as
     *     404 -- and which the consumer reads as its card-not-found decision input
     */
    @PostMapping(path = LOOKUP_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CardXrefView lookup(@Valid @RequestBody CardXrefLookupRequest request) {
        return this.reads.resolveCardCrossReference(request.cardNumber());
    }

    /**
     * Resolves an account to the cross-reference row that its lowest card number holds.
     *
     * <p>Purpose. This is the migrated form of paragraph {@code 9200-GETCARDXREF-BYACCT.} at L723 of
     * {@code app/cbl/COACTVWC.cbl}, whose body at L727 through L732 issues one {@code EXEC CICS READ} against
     * the alternate index literal declared at L192 and L193, passing the account identifier as
     * {@code RIDFLD (WS-CARD-RID-ACCT-ID-X)} and receiving one record
     * {@code INTO (CARD-XREF-RECORD)}.</p>
     *
     * <p>Assumptions: the reference operation is a single deterministic read rather than a browse, and the
     * distinction is what fixes this operation's shape. L727 issues {@code READ} with a record identification
     * field and a key length; it issues no start-browse and no read-next, so exactly one record answers and
     * the program continues at L737 by evaluating a single response code. This handler answers with one row
     * for that reason, and the paged walk beside it exists for the case that shape cannot express.</p>
     *
     * <p>Trade-offs: because {@code idx_card_xref_account_id} is not unique, an account may hold more than one
     * cross-reference row, and this operation answers with one of them -- the row whose card number orders
     * lowest. That tie-break is a decision, not an accident: it makes the answer reproducible where the
     * reference's own answer is whichever record the alternate index reaches first, and ascending card-number
     * order is the base cluster's own order, which {@code app/cbl/CBACT03C.cbl} states by declaring
     * {@code ACCESS MODE  IS SEQUENTIAL} at L31 beside {@code RECORD KEY   IS FD-XREF-CARD-NUM} at L32. What
     * is accepted is that a caller needing every row of a multi-card account cannot get it from here; that
     * caller is served by {@link #searchByAccount(AccountLookupRequest, String, String, Principal)}.</p>
     *
     * <p>Assumptions: the account identifier travels in a request body rather than in the target, for the
     * same reason the card-keyed lookup above does, and the consuming context records the same finding: an
     * account identifier placed in a request line is composed into the load balancer's access record before
     * any application code runs, and the migration's logging contract does not permit a durable diagnostic to
     * hold one. The bound request record is {@link AccountLookupRequest}, which constrains the value to the
     * eleven-digit range {@code XREF-ACCT-ID PIC 9(11)} declares at L7 of {@code app/cpy/CVACT03Y.cpy}.</p>
     *
     * <p>Assumptions: an account with no cross-referenced card is an absence, answered 404, and the reference
     * has a sentence for exactly this outcome -- {@code app/cbl/COACTVWC.cbl} L129 and L130 declare
     * {@code DID-NOT-FIND-ACCT-IN-CARDXREF} with the text
     * {@code Did not find this account in account card xref file}, which travels on the raised type rather
     * than being restated here. One observation is recorded without being reconciled:
     * {@code app/cbl/COACTUPC.cbl} declares that same condition name twice, at L497 with the sentence above
     * and again at L513 with {@code Did not find this account in cards database}. Both are left exactly as
     * they stand, neither is chosen over the other and the two are not merged.</p>
     *
     * @param request the lookup request carrying the account identifier; must satisfy its declared
     *     constraints
     * @return the account and customer the lowest-ordering cross-referenced card resolves to, a
     *     {@link CardXrefView}, never {@code null}
     * @throws NoSuchElementException if the account has no cross-referenced card, which the shared advice
     *     renders as 404 and which a consuming context reads as its account-not-cross-referenced decision
     *     input
     */
    @PostMapping(path = LOOKUP_BY_ACCOUNT_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CardXrefView lookupByAccount(@Valid @RequestBody AccountLookupRequest request) {
        return this.reads.resolveCardCrossReferenceByAccount(request.accountId());
    }

    /**
     * Walks one account's cross-reference rows a page at a time, in ascending card-number order.
     *
     * <p>Refactoring Rationale: this operation is a documented SUPERSET of the reference's account-keyed
     * access, not a restatement of it. The reference performs a single keyed read --
     * {@code app/cbl/COACTVWC.cbl} paragraph {@code 9200-GETCARDXREF-BYACCT.} at L723 issuing one
     * {@code EXEC CICS READ} at L727 through L732 with a record identification field and a key length -- and
     * so answers with one record. The target index the same access path resolves through,
     * {@code idx_card_xref_account_id}, is NOT unique, so an account may hold several rows and a single-record
     * answer cannot describe them. This operation therefore returns a bounded, ordered set where the reference
     * returns one row, and that divergence is recorded here and in the published contract rather than being
     * introduced quietly. The single-record shape is not withdrawn; it remains available at
     * {@link #lookupByAccount(AccountLookupRequest)}.</p>
     *
     * <p>Alternatives Considered: addressing a page by its ordinal position in the result -- counting rows
     * from the start of the set on each request -- was evaluated and rejected in favour of paging by key. The
     * two are not equivalent under concurrent insertion: a row inserted ahead of the reader's position shifts
     * every later row by one, so the following request re-serves a row the caller already received and passes
     * over one it has not. Paging by key has no such failure mode, because a key already read keeps its place
     * in the ordering whatever is inserted around it. The reference is already a keyset cursor and supplies
     * the shape directly: {@code app/cbl/COCRDLIC.cbl} declares a last-key pair at L230 through L232 and a
     * first-key pair at L233 through L235, a screen number at L237, a last-page-displayed flag at L239 whose
     * conditions read 0 for shown at L240 and 9 for not shown at L241, and a next-page indicator at L242
     * through L244 which it sets by discovering one more record than a screen holds, counted at L145. There is
     * no offset, no row number and no page-size member anywhere in that structure. Two of its members are
     * deliberately dropped rather than carried across -- the screen number and the last-page flag -- so no
     * page number crosses the wire in either direction.</p>
     *
     * <p>Assumptions: the cursor is opaque to the caller and is verified rather than trusted. It is a token
     * sealed by {@code com.carddemo.common.web.CursorToken}, bound to this query and to the subject it was
     * issued for, so a caller cannot compose one, edit one or replay another subject's. That matters
     * concretely here: the physical key sealed inside it is the card number, a
     * {@code PIC X(16)} primary account number per L5 of {@code app/cpy/CVACT03Y.cpy}, so a raw cursor would
     * publish in every page the value this context masks everywhere else. The width bound below is the
     * token's own declared maximum rather than a number chosen here.</p>
     *
     * <p>Assumptions: the subject the cursor is bound to is read from the validated principal and is never a
     * parameter of the request. A caller able to name its own subject could present a cursor sealed for
     * another one, which would make the binding decorative. This is the same substitution recorded on the type
     * charter above: the reference's user fields at L25 and L26 of {@code app/cpy/COCOM01Y.cpy} were echoed
     * storage, and here the identity is signed.</p>
     *
     * <p>Trade-offs: the page size is fixed by the service rather than accepted from the caller. What is given
     * up is a caller's ability to ask for a larger page; what is bought is that the surplus-row probe the
     * further-page indicator depends on stays entirely inside the layer that issues the query, and that no
     * request can widen a page beyond the bound the store is asked for. The reference likewise fixes its page
     * at the rows a screen holds and offers no way to change it.</p>
     *
     * <p>Assumptions: an account with no cross-referenced card yields an EMPTY page rather than a 404, which
     * is the opposite of the single-row operation beside this one and is deliberate. An account legitimately
     * holds no card, and a browse of an alternate index over an absent key likewise ends immediately rather
     * than failing; a caller walking a set asks how many rows there are, whereas a caller resolving one row
     * asks whether it exists.</p>
     *
     * @param request the request carrying the account whose rows are walked; must satisfy its declared
     *     constraints
     * @param cursor the sealed token naming the boundary the previous page ended on, or {@code null} to read
     *     the first page of the set
     * @param direction the step to take from that boundary, either {@code next} or {@code previous}, or
     *     {@code null} which reads forward
     * @param principal the validated caller, whose name is the subject the cursor is bound to; supplied by
     *     the framework from the token rather than by the request
     * @return one page of the account's rows in ascending card-number order, carrying both boundary tokens
     *     and the further-page indicator, a {@link PageResponse} of {@link CardXrefResponse}, never
     *     {@code null}
     * @throws ClientInputException if the supplied cursor cannot be opened, or was sealed for another query,
     *     another subject or the other direction, which the shared advice renders as 400 keyed to the
     *     cursor; the exact type reaching this boundary is
     *     {@link CursorToken.InvalidCursorException}, and the parent is declared here because the shared
     *     advice keys its rendering on the parent rather than on the seal's own subtype
     */
    @PostMapping(path = SEARCH_BY_ACCOUNT_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<CardXrefResponse> searchByAccount(
            @Valid @RequestBody AccountLookupRequest request,
            @RequestParam(name = "cursor", required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String cursor,
            @RequestParam(name = "direction", required = false)
            @Pattern(regexp = DIRECTION_DOMAIN)
            String direction,
            Principal principal) {

        return this.reads.listCardCrossReferences(
                request.accountId(), cursor, direction, principal.getName());
    }
}
