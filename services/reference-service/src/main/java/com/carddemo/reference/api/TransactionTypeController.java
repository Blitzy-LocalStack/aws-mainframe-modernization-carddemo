package com.carddemo.reference.api;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeListRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import com.carddemo.reference.service.TransactionTypeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The five transaction-type operations the published contract declares.
 *
 * <p>Purpose: this class is the HTTP surface of the parent half of the transaction-reference feature.
 * It binds and constrains a request, hands it to {@link TransactionTypeService}, and returns what that
 * service answers. It reads no table, decides no business rule and formats no failure body. The rules
 * transcribed from the baseline live in {@code com.carddemo.reference.service}; the charter for this
 * package, and the decisions that bind every controller in it, are recorded in its
 * {@code package-info}.</p>
 *
 * <h2>Which baseline this surface answers for</h2>
 *
 * <p>Two programs in {@code app/app-transaction-type-db2/cbl} supply the behaviour. The browse and
 * inline-maintenance screen {@code COTRTLIC.cbl} supplies the page boundaries and the delete refusal.
 * The single-record maintenance screen {@code COTRTUPC.cbl} supplies the create and replace paths and
 * their outcome ordering. Both are reference-only: nothing under {@code app} is edited by this
 * migration, and every citation below is a physical line in the file named.</p>
 *
 * <p>Assumptions: a citation such as line 1580 is a PHYSICAL line number in the file, not the printed
 * sequence number in columns 73 to 80. In this pair the printed sequence runs roughly four higher than
 * the physical line, so a reader who navigates by the printed number lands beside, rather than on, the
 * statement cited. The sibling service class records the same trap for the same region.</p>
 *
 * <h2>Why the category surface is not nested beneath this one</h2>
 *
 * <p>Alternatives Considered: publishing transaction categories as a sub-resource of a type, on paths
 * of the shape {@code transaction-types/&#123;typeCd&#125;/categories}, was evaluated. The foreign key at
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 and 7 makes a category existentially
 * dependent on its parent, so a nested path is a defensible reading of the data. It was rejected on
 * three specific grounds. The contract publishes the category collection at its own top-level path
 * {@code /api/v1/reference/transaction-categories} with the item at
 * {@code /&#123;typeCd&#125;/&#123;catCd&#125;}, and the contract is the authority for this module. A
 * sibling controller already answers for exactly those two paths, so a nested duplicate would declare a
 * second mapping for one resource. And {@code ReferenceApiRoutingContractTest} compares the declared
 * operations against the delivered handlers in BOTH directions, so a handler no operation declares
 * fails the build rather than merely widening the surface.</p>
 *
 * <p>Trade-offs: the accepted cost is that the parent-child relationship is expressed by the shared
 * leading {@code &#123;typeCd&#125;} segment of the sibling item path
 * {@code /api/v1/reference/transaction-categories/&#123;typeCd&#125;/&#123;catCd&#125;} rather than by
 * path containment, so a reader of the paths alone does not see the dependency and has to reach the
 * constraint at {@code TRNTYCAT.ddl} lines 6 and 7 to find it. That cost is paid once, in this paragraph
 * and in the package charter, in exchange for one mapping per resource.</p>
 *
 * <h2>Why no failure body is built here</h2>
 *
 * <p>Refactoring Rationale: this class declares no controller advice and no exception handler. The
 * baseline decided its own presentation: {@code COTRTLIC.cbl} line 1914 tests for Db2 SQLCODE -532 and
 * line 1919 moves the sentence a user sees into its own message field, so the screen program was both
 * the caller of the statement and the author of the refusal text. In the target that pairing is split:
 * {@code com.carddemo.common.error.GlobalExceptionHandler} is the single advice for every service and
 * already owns the whole outcome-to-status table, including the referential refusal that arrives as
 * SQLSTATE 23503. Declaring a second advice in this module would leave two advices competing to answer
 * one exception, and which of them wins is not something this file can state. Every typed condition
 * therefore propagates from the service untouched.</p>
 *
 * <h2>Message text and its width contract</h2>
 *
 * <p>Assumptions: every sentence a caller can see is single-sourced as a constant on
 * {@link TransactionTypeService} and is carried across character for character, including two baseline
 * spellings that a reader is likely to read as accidents. The refusal a stale key earns keeps the
 * trailing space it carries at {@code COTRTLIC.cbl} line 1864, and the sentence reporting a competing
 * write keeps "some one" as two words as declared at {@code COTRTUPC.cbl} line 184. Neither is
 * normalised, and no copy of either is kept here -- a second copy of a sentence is a second thing to
 * keep in step.</p>
 *
 * <p>Assumptions: the width contract for those sentences is the working-storage width and not the
 * screen width. {@code COTRTUPC.cbl} line 142 declares the informational field as {@code PIC X(40)} and
 * line 167 declares the error field as {@code PIC X(75)}, while the map fields those values are written
 * into are wider -- {@code INFOMSGI PIC X(45)} at {@code COTRTUP.cpy} line 72 and
 * {@code ERRMSGI PIC X(78)} at line 78. The narrower pair is the contract because it bounds what the
 * program can produce, whereas the wider pair only bounds what the terminal can display;
 * {@code app/cpy/CVCRD01Y.cpy} lines 28 and 29 carry the shared error and return fields at
 * {@code PIC X(75)} and corroborate the 75.</p>
 *
 * <h2>Every function key the two screens offered has an operation here</h2>
 *
 * <p>Assumptions: the keyboard belongs to the browser, but the completeness obligation belongs to this
 * surface -- an action reachable by a key on the terminal and by nothing over HTTP would be a lost
 * capability. The maintenance screen declares its keys as named map fields in
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTUP.cpy}: {@code FKEY04I PIC X(9)} confirms a delete,
 * {@code FKEY05I PIC X(8)} adds or saves, {@code FKEY06I PIC X(6)} and
 * {@code FKEY12I PIC X(10)} cancels, under a legend {@code FKEYSI PIC X(21)}. That screen declares
 * neither a seventh nor an eighth key, which is the assertion by absence that it does not page; the
 * list screen declares no such field at all, so its legend is unnamed map literal text. The five
 * operations below cover create, save, delete, cancel and both paging directions, so no key is left
 * without a way to reach its effect.</p>
 *
 * <p>Assumptions: the attention-identifier normaliser {@code app/cpy/CSSTRPFY.cpy} is cited rather than
 * reproduced, and its two conventions differ in a way worth stating once. Its paragraph opens at line
 * 17, its selection at line 21 and closes at line 78, with its exit label at line 80. The CICS
 * constants it compares against are single-digit through nine -- {@code DFHPF1} at line 30, with the
 * two-digit spellings beginning only at ten -- whereas the flags it sets in
 * {@code app/cpy/CVCRD01Y.cpy} are zero-padded, {@code PFK01} through {@code PFK12}, and lines 54 to 77
 * alias the upper twelve keys onto that same padded set. It carries 28 selection arms and no catch-all
 * arm, so an unrecognised identifier leaves the flag as it was and the calling program, not the
 * normaliser, produces the sentence about an unrecognised key. That is why the baseline holds three
 * separately spelled variants of that sentence in its callers, and why none of them is merged.</p>
 *
 * <h2>Nothing here remembers a previous turn</h2>
 *
 * <p>Assumptions: the two screens were pseudo-conversational, so continuity between turns travelled in
 * the 160-byte communication area declared at {@code app/cpy/COCOM01Y.cpy} line 19. Its parts are
 * replaced rather than carried: navigation becomes a client route change, identity becomes claims on a
 * validated token, and selection becomes the path segment below. The discriminator at lines 29 to 31 of
 * that copybook -- a {@code PIC 9(01)} field whose two condition names, valued with the bare digits 0
 * and 1, told a program whether it was seeing a screen for the first time -- has no counterpart at all,
 * because a stateless handler has no earlier turn to distinguish. Note that the two condition names four
 * lines above it, on {@code CDEMO-USER-TYPE PIC X(01)}, are valued with the QUOTED characters
 * {@code 'A'} and {@code 'U'}; the quoting differs between the two fields and is easy to transcribe
 * wrongly.</p>
 *
 * <h2>How a refused field is reported, and who decides authority</h2>
 *
 * <p>Assumptions: the per-parameter constraints below produce the structured field errors on
 * {@code com.carddemo.common.error.ApiError} rather than any shape local to this class. That array is
 * the migrated form of the one-byte edit flags the baseline kept per field, whose valid state is
 * {@code LOW-VALUES} at {@code COTRTUPC.cbl} line 95 rather than a populated value, and whose two error
 * states at lines 96 and 97 are peer values of the same byte rather than a third condition. The
 * baseline also grouped those flags structurally, keeping the key flag bare at line 94 and nesting the
 * non-key flags beneath a group item at lines 99 and 100; naming each field error after its own
 * parameter preserves that separation. The templated highlight at {@code app/cpy/CSSETATY.cpy} moves a
 * colour into one subfield at lines 21 and 22 and an asterisk marker into a different subfield at lines
 * 24 and 25, both of which are presentation and neither of which is a stored value; its gate at line 20
 * on the turn discriminator is gone with the discriminator itself, so a refusal here is driven only by
 * the response body.</p>
 *
 * <p>Assumptions: no method here carries an authority annotation, and that is deliberate rather than an
 * omission. {@code com.carddemo.reference.config.SecurityConfig} decides authority once for the module
 * by HTTP method, so
 * every write below is admitted only to the administrator group and every read to either group -- which
 * is what each operation declares for itself in the contract as {@code x-required-authority}, naming
 * {@code carddemo-admin} on the three writes and {@code carddemo-user} on the two reads.
 * {@code com.carddemo.common.security.JwtRoleConverter} publishes those two group names verbatim with no
 * prefix added, so a route must test the authority itself rather than the role predicate, which would
 * look for a prefix that converter never emits. Annotating each handler as well was considered for locality and rejected, because the
 * same rule stated twice can disagree with itself and the filter chain is the copy that actually
 * refuses the request. The correlation header the contract declares on every operation is applied by
 * the shared filter in {@code common-lib} and is not bound as a parameter here.</p>
 *
 * <p>Assumptions: where this class and
 * {@code src/main/resources/openapi/reference-api.yaml} disagree, that document wins; every path,
 * method, status and parameter name below was cross-checked against it. Behavioural divergences from the
 * baseline are registered in {@code docs/architecture/cobol-to-service-traceability.md}, which is owned
 * elsewhere and referenced rather than restated.</p>
 */
@RestController
@RequestMapping(TransactionTypeController.BASE_PATH)
public class TransactionTypeController {

    /** The collection path the contract declares. */
    public static final String BASE_PATH = "/api/v1/reference/transaction-types";

    /** The item path, relative to the collection. */
    public static final String ITEM_PATH = "/{typeCd}";

    /** The path variable naming the type code. */
    public static final String PARAM_TYPE_CD = "typeCd";

    /** The query parameter carrying the paging position. */
    public static final String PARAM_CURSOR = "cursor";

    /** The query parameter carrying the paging direction. */
    public static final String PARAM_DIRECTION = "direction";

    /** The query parameter carrying the type-code filter. */
    public static final String PARAM_TYPE_CODE = "typeCode";

    /** The query parameter carrying the description filter. */
    public static final String PARAM_DESCRIPTION = "description";

    /** The rules this controller delegates to. */
    private final TransactionTypeService service;

    /** The sealer that mints and opens an opaque paging position. */
    private final CursorToken cursorToken;

    /**
     * Builds the controller over its two collaborators.
     *
     * <p>Assumptions: constructor injection and exactly two collaborators, matching the pair the
     * module's own parameter-constraint test constructs this class with. Neither a store nor a
     * translator is taken, because {@link TransactionTypeService} sits in front of both and reaching
     * past it would put one rule in two places. The sealer is held HERE rather than inside that service
     * because a paging position is a property of the HTTP conversation: the package charter's section on
     * why every list is a keyset walk records the alternative of letting the service hold it, and its
     * rejection.</p>
     *
     * @param service the transaction-type rules this class delegates every decision to; must not be
     *     {@code null}
     * @param cursorToken the sealer that mints an opaque paging position for a reply and opens one
     *     supplied on a request; must not be {@code null}
     */
    public TransactionTypeController(TransactionTypeService service, CursorToken cursorToken) {
        this.service = service;
        this.cursorToken = cursorToken;
    }

    /**
     * Answers one page of transaction types in key order.
     *
     * <p>Purpose: the migrated form of the browse the list screen drives with a declared pair of
     * cursors -- a forward cursor at {@code COTRTLIC.cbl} line 339 ordered ascending at line 351, and a
     * backward cursor at line 355 ordered descending at line 367. Sending no position asks for the
     * opening page; thereafter a client returns the position matching the direction it wants.</p>
     *
     * <p>Assumptions: the page holds seven rows, and that number is read from the baseline's own named
     * constant rather than counted off a screen. {@code COTRTLIC.cbl} line 60 declares
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.}, and
     * {@code TransactionTypeService.PAGE_SIZE} carries it. The row count of the list map agrees with
     * that constant, but the constant is the contract and the map is its consequence -- counting map
     * rows would tie the page size to a terminal geometry this surface no longer has.</p>
     *
     * <p>Assumptions: paging is by key throughout, never by ordinal position. The reply envelope carries
     * two sealed positions and the availability flags either direction needs, and carries no page number
     * and no page-size field, so a client that wants to show a page number keeps its own count. The
     * forward step reads strictly beyond the trailing position ascending and the backward step strictly
     * before the leading position descending, which is what the two declared cursors already did. The
     * forward availability flag is a transcription rather than an invention: after its page is filled at
     * {@code COTRTLIC.cbl} line 1627 the program performs one further read at line 1662 purely to learn
     * whether another row exists, which is the same read-one-extra-and-discard the envelope reports.</p>
     *
     * <p>Trade-offs: a client gives up the ability to jump to an arbitrary page number, which paging by
     * ordinal position would have allowed. That was accepted because the alternative changes observable
     * behaviour under concurrent inserts -- a positional walk repeats and skips rows across page
     * boundaries where a keyed walk does not -- and the baseline was already walking by key while merely
     * DISPLAYING a page number in {@code PAGENOI PIC X(3)} at {@code COTRTLI.cpy} line 60. Displaying a
     * number is not the same as addressing by one.</p>
     *
     * <p>Assumptions: both filters are narrowed here to the shapes the contract publishes, and that
     * narrowing is load-bearing for more than shape. The baseline accepts them as
     * {@code TRTYPEI PIC X(2)} at {@code COTRTLI.cpy} line 66 and {@code TRDESCI PIC X(50)} at line 72,
     * and feeds each straight into a containment predicate -- {@code COTRTLIC.cbl} lines 348, 364 and
     * 1812 -- with no escape clause, so
     * a metacharacter arriving in caller input would be read as a wildcard rather than as a character to
     * match. Constraining the type-code filter to exactly two digits removes that possibility for that
     * filter outright, since no metacharacter is a digit. The description filter admits free text, so
     * escaping its metacharacters is done where the predicate is built and is not repeated here; this
     * method's obligation is to reject a filter of the wrong length or shape before it reaches that
     * predicate at all.</p>
     *
     * @param cursor the sealed paging position a previous reply minted, absent on a first request
     * @param direction the paging direction as the caller spelled it, one of the two lower-case values
     *     the contract publishes, absent meaning forward
     * @param typeCode an exact two-digit type-code filter, absent meaning unfiltered
     * @param description a description containment filter, absent meaning unfiltered
     * @param principal the authenticated caller, supplied by the filter chain; its name is sealed into
     *     every position this page mints, so a position cannot be replayed by a different caller
     * @return one page of transaction types carrying its sealed positions and its availability flags
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the supplied position is not
     *     a position this service minted for this caller and this browse, answered as 400
     * @throws com.carddemo.common.error.ClientInputException if a direction is supplied without a
     *     position, or if the supplied filters match no row anywhere in the table; this is the supertype
     *     of the cursor refusal above and is answered as 400
     */
    @GetMapping
    public PageResponse<TransactionTypeResponse> listTransactionTypes(
            @RequestParam(name = PARAM_CURSOR, required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            @Pattern(regexp = CursorToken.SEALED_SHAPE_PATTERN) String cursor,
            // WHY : ⚠️ Refactoring Rationale: bound as a STRING and converted below, because a
            //       parameter declared as the enumeration is bound by Enum.valueOf against the
            //       CONSTANT NAME -- so the two lower-case values this contract publishes, and the
            //       only two the browser client sends, were refused while NEXT and PREVIOUS were
            //       accepted. The whole argument, and the converter alternative that was rejected,
            //       is recorded on PageDirection.fromRequestParameter.
            @RequestParam(name = PARAM_DIRECTION, required = false) String direction,
            @RequestParam(name = PARAM_TYPE_CODE, required = false)
            @Size(min = TransactionTypeListRequest.TYPE_CODE_LENGTH,
                    max = TransactionTypeListRequest.TYPE_CODE_LENGTH)
            @Pattern(regexp = TransactionTypeListRequest.TYPE_CODE_PATTERN) String typeCode,
            @RequestParam(name = PARAM_DESCRIPTION, required = false)
            @Size(min = TransactionTypeListRequest.DESCRIPTION_MIN_LENGTH,
                    max = TransactionTypeListRequest.DESCRIPTION_MAX_LENGTH) String description,
            Principal principal) {

        return this.service.list(
                new TransactionTypeListRequest(cursor, PageDirection.fromRequestParameter(direction),
                        typeCode, description),
                this.cursorToken, principal.getName());
    }

    /**
     * Adds one transaction type and reports where it now lives.
     *
     * <p>Purpose: the migrated form of the add path of {@code COTRTUPC.cbl}, whose screen prompts for
     * the new details and asks for a confirming key press before writing. The code travels in the body
     * here, unlike every other operation on this class, because on a create there is no existing item to
     * address -- the caller is proposing the key, not naming one.</p>
     *
     * <p>Alternatives Considered: answering a duplicate key and a still-referenced row with ONE generic
     * conflict was evaluated and rejected, and the evidence for rejecting it is specific. The baseline's
     * insert arm at {@code COTRTUPC.cbl} lines 1596 to 1621 has exactly two branches, success at line
     * 1605 and a catch-all at line 1607; there is no arm for a duplicate key anywhere in either program,
     * which a search for the Db2 code that reports one confirms by returning nothing in both files. The
     * catch-all at line 1608 raises the very same condition flag as the update arm at line 1568, so the
     * classifier that reads that flag at line 1583 cannot tell an insert refusal from an update refusal,
     * and both reach a caller as one sentence. The target keeps the two apart instead: a duplicate key
     * arrives as SQLSTATE 23505 and a still-referenced row as 23503, and the service raises a separately
     * kinded conflict for each so that the shared advice can answer each with its own sentence. Both
     * arrive at a caller as 409. This is a documented divergence from the baseline, registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: the description's admitted character class is declared once, on the request record
     * this method accepts, and is not restated here. One sentence in the baseline invites the opposite
     * conclusion and is worth naming so it is not wired up by a later reader: the condition name at
     * {@code COTRTUPC.cbl} line 173 declares a sentence about a name being restricted to letters and
     * spaces, and that name occurs exactly once in the file -- at its own declaration -- so no path in
     * either program ever selects it. It is therefore not carried into any validation message here, and
     * its wording is not used to widen or narrow the admitted class. The class actually declared for
     * that comparison is a 52-character table at line 249, which is 26 upper-case letters plus 26
     * lower-case ones.</p>
     *
     * @param request the code and description of the type to create, both required
     * @return the stored type as the contract publishes it, at 201, with a Location header naming the
     *     path of the created item
     * @throws com.carddemo.common.error.RecordConflictException when a type already carries the
     *     submitted code, answered as 409
     * @throws org.springframework.dao.DataIntegrityViolationException when the constraint that refused
     *     the insert reports a state the service does not classify, left to the shared advice
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TransactionTypeResponse> createTransactionType(
            @Valid @RequestBody TransactionTypeCreateRequest request) {

        TransactionTypeResponse created = this.service.create(request);

        // WHY : Assumptions: the 201 reply in reference-api.yaml declares this header with
        //       required true, so it is built from the STORED representation rather than from the
        //       request body. Echoing the submitted code would assume the row was stored exactly as
        //       proposed, which is the assumption returning the stored body exists to avoid making.
        return ResponseEntity.created(itemLocation(created.typeCd())).body(created);
    }

    /**
     * Answers one transaction type by its code.
     *
     * <p>Assumptions: the segment is narrowed to the {@code TransactionTypeCode} schema its published
     * path parameter references, whose pattern admits only 01 through 99 and therefore excludes 00, so a
     * value outside that domain is refused as a bad request rather than read for and reported absent. Unconstrained, a
     * two-character value the domain excludes, or a value of the wrong width entirely, reached the keyed
     * read, matched nothing, and was answered as though the row were missing -- which tells a caller
     * something about the table when what is actually wrong is the request.</p>
     *
     * @param typeCd the two-character code to read
     * @return the type as the contract publishes it
     * @throws java.util.NoSuchElementException carrying the service's verbatim refusal when no row holds
     *     that code, answered as 404
     */
    @GetMapping(path = ITEM_PATH)
    public TransactionTypeResponse getTransactionType(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd) {
        return this.service.read(typeCd);
    }

    /**
     * Replaces the description of an existing transaction type, and never creates one.
     *
     * <p>Purpose: the migrated form of the save path both baseline screens offer. The chosen semantic is
     * stated rather than left to be inferred: this operation REFUSES an absent code with 404 and does not
     * insert. A type is brought into existence only by the create operation above.</p>
     *
     * <p>Alternatives Considered: the baseline does three DIFFERENT things when a write finds no row, so
     * one of the three had to be chosen for this operation and the other two named. The list screen's
     * update arm at {@code COTRTLIC.cbl} lines 1837 to 1892 treats an absent row as a refusal: its
     * no-row branch at line 1861 sets its outcome flag at line 1862 and moves a sentence about the record
     * having been removed by another at line 1864, and inserts nothing. The maintenance screen's write arm
     * at {@code COTRTUPC.cbl} lines 1531 to 1593 does the opposite: its no-row branch at line 1558
     * performs the insert paragraph at lines 1559 and 1560, making that path a genuine create-or-replace.
     * The batch reference updater does a third thing again -- {@code COBTUPDT.cbl} reports no rows found
     * from its update paragraph at line 166 with the sentences at lines 181 and 211, then performs the
     * paragraph at lines 184 and 214 whose body is a display, a move of 4 into the program return code
     * and an exit with no stop, so that record is soft-rejected and the loop carries on to the next one.
     * The list screen's refusal is the semantic adopted here, because an item address in a path is a
     * statement that the item exists and a request that contradicts it is a caller error. The batch
     * behaviour is preserved where it belongs, on the reference-maintenance surface, and the
     * create-or-replace behaviour is preserved by the create operation being a separate operation. This is
     * a documented divergence from the maintenance screen's write arm, registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: only the description is replaceable, which is why the body carries no code at all.
     * Both baseline write paths issue the same shape of statement -- {@code COTRTLIC.cbl} line 1848 and
     * {@code COTRTUPC.cbl} line 1546 each set the description column alone -- so the key is never
     * updated. The code this method acts on is therefore taken from the path and from nowhere else: the
     * body shape declares no member able to carry one, so a value a caller adds under that name is not a
     * second opinion about the key that this method could accidentally prefer. Whether an unrecognised
     * member is refused outright or discarded is a module-wide property of the JSON binding rather than a
     * decision available at one handler, and stating it per handler would put one rule in six places.</p>
     *
     * <p>Assumptions: the precondition a caller sends is the record's own concurrency token, published by
     * the contract as an integer alongside every representation. That column is owned by this context's
     * migration and its entity, not by this class -- a controller cannot introduce a column, and this one
     * neither declares nor names a persistence concurrency annotation. Its purpose is exactly the one the
     * baseline solved by hand: the list screen kept a snapshot of what it had read so it could tell
     * whether the row had moved underneath the user across the gap between two screen turns, reporting the
     * competing write with the sentence declared at {@code COTRTUPC.cbl} line 184 and selected by the arm
     * at line 1585.</p>
     *
     * <p>Trade-offs: a single monotonic token is coarser than comparing every stored field, so a caller
     * whose submission would not actually have changed anything can still be refused after an unrelated
     * write to the same row. That was accepted because the service also keeps the baseline's no-change
     * arm, whose sentence is declared at {@code COTRTUPC.cbl} line 180 -- a submission that describes the
     * stored values is answered as a success without a write -- so the coarseness costs a retry rather
     * than a lost edit, and the alternative of comparing whole records makes every read a candidate
     * snapshot to carry.</p>
     *
     * <p>Refactoring Rationale: the outcomes this operation can report are typed, and they are typed
     * because the baseline's own flags could not tell them apart. In the list screen's update arm all
     * three error branches raise the SAME outcome flag -- line 1862 for no row, line 1871 for a lock
     * refusal and line 1881 for any other negative code -- and only the lock branch additionally raises
     * the input-error flag at line 1872. So the flag pair separates the lock refusal from the PAIR of
     * absent-row and failed-write, and cannot separate those two from each other; the only thing that
     * distinguished them was the sentence each branch moved into the message field. Three outcomes that a
     * caller must be able to act on differently cannot ride on a discriminator that carries two, which is
     * why each arrives here as its own exception type instead.</p>
     *
     * <p>Refactoring Rationale: the order in which those outcomes are considered is preserved exactly,
     * and the shape of the target pipeline is transcribed rather than invented. The maintenance screen
     * classifies in TWO sequential stages: lines 1555 to 1578 map a driver code to a typed condition --
     * success at 1556, no row at 1558, a lock refusal at 1561 setting its condition at 1564, any other
     * negative code at 1567 setting a failed-write condition at 1568 -- and then lines 1580 to 1589 map
     * those conditions to one caller-visible outcome in a strict order: lock refusal first at 1581 and
     * 1582, failed write second at 1583 and 1584, altered-underneath third at 1585 and 1586, and success
     * only as the remaining arm at 1587 and 1588. That two-stage shape IS the target's route from a
     * SQLSTATE, through a typed exception, to a status, and the service's outcome enumeration declares its
     * constants in that same order. Re-ordering the second stage would change which status a request
     * satisfying two conditions receives, so the order is part of the behaviour and not an implementation
     * detail.</p>
     *
     * @param typeCd the two-character code of the type to replace
     * @param request the new description together with the concurrency token the caller read
     * @return the stored type as the contract publishes it
     * @throws java.util.NoSuchElementException carrying the service's verbatim refusal when no row holds
     *     that code, answered as 404 and never as an insert
     * @throws com.carddemo.common.error.RecordConflictException when the token supplied is not the one the
     *     row now carries, answered as 409 together with the state the row now holds
     * @throws org.springframework.dao.DataIntegrityViolationException when the constraint that refused the
     *     write reports a state the service does not classify, left to the shared advice
     */
    @PutMapping(path = ITEM_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public TransactionTypeResponse replaceTransactionType(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd,
            @Valid @RequestBody TransactionTypeUpdateRequest request) {
        return this.service.replace(typeCd, request);
    }

    /**
     * Deletes one transaction type, unless categories still reference it.
     *
     * <p>Purpose: the migrated form of the delete arm of {@code COTRTLIC.cbl} at lines 1896 to 1935. No
     * body is returned, which is what the contract declares by answering 204.</p>
     *
     * <p>Refactoring Rationale: the refusal a still-referenced type earns is INHERITED here rather than
     * decided here, and the whole chain matters because a break anywhere in it turns a caller error into
     * a server error. The constraint at {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 and
     * 7 declares the child's foreign key with a restricted delete. Db2 reports that restriction as
     * SQLCODE -532, which the baseline tests for at line 1914 and answers with the sentence about
     * associated child records at line 1919 -- the same sentence the maintenance screen carries at
     * {@code COTRTUPC.cbl} line 1641. The equivalent constraint in the target reports SQLSTATE 23503,
     * which surfaces as a data-integrity violation and reaches
     * {@code com.carddemo.common.error.GlobalExceptionHandler}, which already owns that mapping to 409
     * expressly to preserve the semantic the baseline's own child index asserts. That handler recognises
     * the condition by class name while walking the cause chain, so it needs no dependency on a driver.
     * Because it already answers correctly, adding a handler here would only create a second advice
     * competing for the same exception. The behaviour a caller must never see is a 500 carrying a driver
     * class, statement text, a constraint name or a stack frame, and the browser screen that lists these
     * types renders the 409 as a blocked delete.</p>
     *
     * <p>Alternatives Considered: mirroring the baseline's routing for a delete of a code that is not
     * there was evaluated and rejected. Its selection at line 1907 carries only three arms -- success at
     * 1908, the restricted refusal at 1914, and a catch-all at 1926 -- and, unlike the update arm at
     * 1861, it has no arm for the no-row condition at all, so an absent code falls into the catch-all
     * alongside genuine failures. The contract publishes 404 as a first-class reply for this operation, so
     * an absent code is answered as 404 here. Reproducing the catch-all routing would report a caller's
     * key error as a server fault, which is exactly the confusion the separate status exists to remove.
     * This is a documented divergence, registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * @param typeCd the two-character code of the type to delete
     * @throws java.util.NoSuchElementException carrying the service's verbatim refusal when no row holds
     *     that code, answered as 404 rather than through a generic failure path
     * @throws com.carddemo.common.error.RecordConflictException when categories still reference the type,
     *     answered as 409 with the referential sentence and never as a 500
     * @throws org.springframework.dao.DataIntegrityViolationException when the constraint that refused the
     *     delete reports a state the service does not classify, left to the shared advice
     */
    @DeleteMapping(path = ITEM_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransactionType(
            @PathVariable(name = PARAM_TYPE_CD)
            @Size(min = TransactionType.TYPE_CD_WIDTH, max = TransactionType.TYPE_CD_WIDTH)
            @Pattern(regexp = TransactionType.TYPE_CD_PATTERN) String typeCd) {
        this.service.delete(typeCd);
    }

    /**
     * Builds the path of one stored type for the create reply's Location header.
     *
     * <p>Assumptions: the path is composed from {@link #BASE_PATH} and {@link #ITEM_PATH} rather than
     * written as a literal, so the header a create returns and the route a client then calls cannot
     * drift apart. {@link #ITEM_PATH} carries the {@code &#123;typeCd&#125;} template placeholder, so the
     * code is substituted into it rather than concatenated onto it -- appending would emit that
     * placeholder text itself, and the resulting header would name a path no route matches.</p>
     *
     * @param typeCd the two-character code of the stored type, taken from the stored representation
     * @return the absolute path of that type, suitable as a Location header value
     */
    private static URI itemLocation(String typeCd) {
        return URI.create(BASE_PATH + ITEM_PATH.replace("{" + PARAM_TYPE_CD + "}", typeCd));
    }
}
