package com.carddemo.account.api;

import com.carddemo.account.dto.CustomerLookupRequest;
import com.carddemo.account.dto.CustomerResponse;
import com.carddemo.account.service.AccountViewService;
import com.carddemo.common.control.OnlineWriteGateExempt;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the customer reads this context owns.
 *
 * <p><b>Purpose.</b> The reference for this adapter is {@code app/cbl/CBCUS01C.cbl}, 178 lines. It is a
 * BATCH reader: it carries no {@code EXEC CICS} verb and appears in no resource definition, so unlike the
 * account view and the account update there is no transaction behind it and no screen of its own. Its
 * whole access surface is therefore settled by its file declaration and its driver loop rather than by a
 * map, and both are read directly. The file is declared {@code ORGANIZATION IS INDEXED} at L30 with
 * {@code ACCESS MODE IS SEQUENTIAL} at L31 over {@code RECORD KEY IS FD-CUST-ID} at L32, and the loop at
 * L74 through L81 walks it end to end, writing each record out at L78 between an open at L72 and a close
 * at L83. Two operations follow from that and are what this adapter publishes: a read positioned by the
 * key of L32, and an ascending scan standing in for the sweep of L74 through L81. A presence probe sits
 * beside them, serving the neighbouring pending-authorization context.</p>
 *
 * <p>Assumptions: the record layout rather than the file description fixes the published fields. The file
 * description carves the record into a nine-digit key at L39 and four hundred and ninety-one opaque bytes
 * at L40, which sum to the declared five hundred but name nothing; the meaningful layout arrives at L45
 * through {@code COPY CVCUS01Y.} and is what L78 writes. {@code app/cpy/CVCUS01Y.cpy} is therefore the
 * contract, and {@code app/cpy/CUSTREC.cpy} -- which exists -- is not, because nothing in this reference
 * names it.</p>
 *
 * <p>Trade-offs: no projection is injected or invoked here. The migration plan fixes this package as
 * the REST layer, carrying request validation and wiring and no business logic, so the projection is
 * invoked from the service layer and reached from here only through the type it returns. The boundary
 * is mechanised rather than trusted:
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * refuses a web type inside a domain package, and this adapter accepts and returns transfer records only,
 * so no stored row appears in a signature, a local or an import here.</p>
 *
 * <p>Refactoring Rationale: no operation below accepts a caller identity in any form. The reference
 * carried one in the communication area the terminal echoed back -- {@code CDEMO-USER-ID PIC X(08)} at
 * L25 of {@code app/cpy/COCOM01Y.cpy} and {@code CDEMO-USER-TYPE PIC X(01)} at L26, whose two admitted
 * values are declared at L27 and L28 -- which means a client was in a position to assert its own user
 * type. Here the same distinction arrives as a signed group claim converted by
 * {@code com.carddemo.common.security.JwtRoleConverter}, so it cannot be asserted by the caller at all.
 * Which authority governs which route is settled by {@code config/SecurityConfig.java} and
 * {@code config/InternalApiSecurityConfig.java}, and no authority is named in this file.</p>
 *
 * <p>Assumptions: the selection context travels in the request rather than in server-held state. The
 * reference kept it at L33 of {@code app/cpy/COCOM01Y.cpy} as {@code CDEMO-CUST-ID PIC 9(09)}; here it is
 * a path variable, which is what makes each request self-describing and independently authorizable. The
 * full account of how that structure decomposes is in this package's charter and is not restated here.
 * Nothing below holds a session, a re-entry discriminator or a next-program field, and the correlation
 * filter is registered and ordered by {@code config/SecurityConfig.java} rather than here.</p>
 *
 * <p>Assumptions: parity for these operations rests on the transcribed access path and the copybook
 * contract, and on no golden master, because none exists for this reference. Its own suite records at
 * L83 through L85 of {@code tests/README.md} that the online programs cannot be driven end to end without
 * a CICS runtime, and the business rules it asserts verbatim from L553 onward name three other programs
 * and not this one. Claiming an oracle here would misdescribe what the tests in this module prove.</p>
 *
 * <p>Every reference cited above is read as evidence only. None is modified: the baseline reads its
 * customer master one record at a time and writes each to a print stream, this adapter publishes the same
 * two access paths over HTTP, and each divergence between them is documented where it arises.</p>
 */
@RestController
@RequestMapping(CustomerController.BASE_PATH)
@OnlineWriteGateExempt(reason =
        "Both operations this controller publishes are READS. Each is a POST only so that the"
        + " nine-digit customer identifier travels in a request body instead of a path segment,"
        + " where the load balancer composes it into an access record no application code can"
        + " withdraw it from. Refusing either during the batch window would stop an internal caller"
        + " resolving a customer context while the chain runs, and this migration's quiesce closes"
        + " writes rather than reads.")
public class CustomerController {

    /**
     * The path prefix every operation in this controller sits beneath.
     *
     * <p>Assumptions: exposed as a constant so the load-balancer rule and the gateway route that forward
     * this prefix can be asserted against it rather than compared by eye across two files.</p>
     */
    public static final String BASE_PATH = "/api/v1/customers";

    /**
     * The sub-path of the customer existence check, beneath {@link #BASE_PATH}.
     *
     * <p>Assumptions: exposed as a constant because {@code InternalApiSecurityConfig} builds its request
     * matcher from this value, so the authority the operation requires and the operation itself cannot come
     * to disagree by an edit to one of them.</p>
     */
    public static final String LOOKUP_PATH = "/lookup";

    /**
     * The sub-path of the keyed customer record read, beneath {@link #BASE_PATH}.
     *
     * <p>Assumptions: the keyed read is NOT published at the collection address itself even though a
     * representation of one customer would ordinarily occupy a keyed address. Both this read and the
     * existence check beside it carry their key in a body rather than in the target, so neither has a keyed
     * address to occupy, and the two must still be distinguishable -- their responses differ in shape, one
     * being a status with no body and the other a whole record. A segment of its own is what keeps the
     * response shape a property of the address rather than of the request.</p>
     *
     * <p>Alternatives Considered: naming the segment {@code /view}, which is the segment the sibling
     * account adapter uses for its human read. It is not used, because in this module's vocabulary that
     * segment names a screen projection and this reference has no screen: {@code app/cbl/CBCUS01C.cbl}
     * carries no {@code EXEC CICS} verb. The segment is named for what the reference actually writes --
     * {@code DISPLAY CUSTOMER-RECORD} at L78, over the group item {@code 01 CUSTOMER-RECORD.} declared at
     * L4 of {@code app/cpy/CVCUS01Y.cpy}.</p>
     *
     * <p>Refactoring Rationale: the segment was {@code /{customerId}/record} and the path variable is gone.
     * The reason is recorded on {@link CustomerLookupRequest} and applies to the whole of this controller's
     * keyed surface rather than to one operation of it.</p>
     */
    public static final String RECORD_PATH = "/record";

    /**
     * The read path this controller binds requests onto.
     */
    private final AccountViewService reads;

    /**
     * Creates the controller.
     *
     * <p>Assumptions: the single collaborator is the service layer, for the reason recorded on the
     * sibling cross-reference controller: this package's charter forbids a repository or the projection
     * here. There is no customer-specific service to inject beside it; the read coordination for this
     * context lives on the account read path, whose sibling set is closed.</p>
     *
     * @param reads the account read path this controller delegates every operation to, an
     *     {@link AccountViewService}; must not be {@code null}
     * @throws NullPointerException if {@code reads} is {@code null}
     */
    public CustomerController(AccountViewService reads) {
        this.reads = Objects.requireNonNull(reads, "reads must not be null");
    }

    /**
     * Reports whether the customer master holds one customer.
     *
     * <p>Purpose: this is the migrated form of the read {@code app/app-authorization-ims-db2-mq/cbl/
     * COPAUA0C.cbl} performs at its paragraph {@code 5300-READ-CUST-RECORD}, declared at L568 and entered
     * at L452. That paragraph reads the whole customer record and uses none of its fields: the read
     * exists so the program can tell whether the row is there before recording an authorization against
     * the account. This operation answers that question and nothing more.</p>
     *
     * <p>Assumptions: the response carries NO BODY on either outcome, and the whole answer is the status.
     * The migrated form crosses the context boundary with none of the record, which is a deliberate
     * narrowing rather than a claim of parity -- the observable outcome is identical, because the
     * reference's own use of the record is limited to whether the read succeeded, and the eighteen fields
     * the copybook declares at L5 through L22 include two identifiers there is no reason to move.</p>
     *
     * <p>Refactoring Rationale: this check was reachable as {@code GET} and {@code HEAD} on
     * {@code /api/v1/customers/{customerId}} and is now a single {@code POST} on {@value #LOOKUP_PATH}
     * carrying the identifier in a body. Two facts made the move necessary rather than tidy. The
     * consuming context had ALREADY moved -- {@code authorization-service}'s
     * {@code RestAccountContextClient} addresses {@code /api/v1/customers/lookup} with a JSON body -- so
     * while this controller published only the keyed form, every existence check reached this service as a
     * dispatcher 404, which the caller cannot distinguish from "no such customer" and would read as a
     * legitimate decision input. And the reason the caller moved is the one {@link CustomerLookupRequest}
     * records: the load balancer composes its access record from the request line before any application
     * code runs, so a customer identifier in a path segment lands in a durable log that nothing inside a
     * service can withdraw it from.</p>
     *
     * <p>Assumptions: the two methods collapse into ONE operation and nothing is lost. Both were already
     * served by this single handler -- the framework answers a {@code HEAD} from a {@code GET} mapping by
     * discarding the body -- and both declared the same two status codes, so the contract described one
     * behaviour twice. The answer was never in a body, so a caller that wanted only presence still
     * transfers no body back.</p>
     *
     * <p>Assumptions: absence is 404 and presence is 204 rather than 200. A 200 announces a body that
     * this operation never has, and a client library reading a 200 with a zero-length body may report a
     * decoding failure rather than a successful call. 204 states the shape exactly.</p>
     *
     * @param request the lookup request carrying the customer identifier, the key {@code CUST-ID} declares
     *     at L5 of {@code app/cpy/CVCUS01Y.cpy}; must satisfy its declared constraints
     * @return 204 with no body when the row exists, 404 with no body when it does not; never
     *     {@code null}
     * @throws org.springframework.web.bind.MethodArgumentNotValidException if the bound request violates
     *     a declared constraint, which the shared advice renders as a 400 naming the field
     * @throws NullPointerException if {@code request} is {@code null}, which the binding layer does not
     *     produce for a required body and which therefore signals a direct call rather than a request
     */
    @PostMapping(path = LOOKUP_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> lookup(@Valid @RequestBody CustomerLookupRequest request) {
        // WHY : Assumptions: this handler answers with a ResponseEntity rather than throwing the
        //   not-found exception the sibling operations throw, and the difference is required rather
        //   than stylistic. The shared advice renders a not-found as a problem DOCUMENT, and this
        //   operation is bodyless by contract -- a consumer that reads the status alone would receive a
        //   Content-Length announcing a body it never asked for, and the absence answer would then carry
        //   a customer identifier back out in a problem document that is itself logged.
        Objects.requireNonNull(request, "request must not be null");
        return this.reads.customerExists(request.customerId())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * Reads one customer of the customer master by its key.
     *
     * <p>Purpose: this is the keyed arm of the access surface {@code app/cbl/CBCUS01C.cbl} declares,
     * positioned by {@code RECORD KEY IS FD-CUST-ID} at L32. The reference itself never takes that path
     * -- its loop reads sequentially and reaches no record by key -- so what is migrated here is the key
     * the file declares rather than a statement the program executes, which is why the fields published
     * are the copybook's and the ordering guarantee is the file's.</p>
     *
     * <p>Assumptions: the two stored identifiers are returned MASKED and never whole, and the masking is
     * an invariant established before the value reaches this method rather than something applied here.
     * {@code CUST-SSN} at L17 of {@code app/cpy/CVCUS01Y.cpy} and {@code CUST-GOVT-ISSUED-ID} at L18 are
     * held encrypted and projected in masked form by the service layer's projection, so this handler is
     * not in a position to publish either one whole even by mistake. The two are NOT alike in the
     * reference: L17 is declared {@code PIC 9(09)}, numeric, while L18 is declared {@code PIC X(20)},
     * alphanumeric, and only their disclosure treatment coincides.</p>
     *
     * <p>Trade-offs: no card verification value and no primary account number appear in this response at
     * all, so neither needs masking here; the customer record declares neither. Where a card number is
     * published by this system it is reduced to its last four digits, and the one operation that answers
     * with a whole card number belongs to the card context rather than to this one.</p>
     *
     * <p>Assumptions: every numeric field of this record travels as digits-only TEXT rather than as a
     * numeric member, including the identifier in the path echoed back in the body, the national
     * identifier and the credit score. The reference holds exactly that separation itself: at L671 of
     * {@code app/cbl/COACTUPC.cbl} it declares {@code ACUP-OLD-ACCT-ID-X PIC X(11)} and at L672 with L673
     * redefines the same storage as {@code PIC 9(11)}, so the screen value is characters and the numeric
     * reading is an overlay on it. The two symbolic maps disagree on the same field and the stricter form
     * is taken: {@code ACCTSIDI} is {@code PIC X(11)} at L60 of {@code app/cpy-bms/COACTUP.CPY} and
     * {@code PIC 99999999999} at L60 of {@code app/cpy-bms/COACTVW.CPY}.</p>
     *
     * <p>Assumptions: the record declares no city field, so none is published under that name. The
     * account view screen carries {@code ACSCITYI PIC X(50)} at L192 of {@code app/cpy-bms/COACTVW.CPY}
     * and it is fed from {@code CUST-ADDR-LINE-3 PIC X(50)} at L11 of {@code app/cpy/CVCUS01Y.cpy} -- the
     * widths match exactly, so this is a difference of NAME and not a narrowing. The reference agrees
     * from the other side: {@code app/cbl/COACTUPC.cbl} declares the validation condition
     * {@code 88 FLG-CITY-NOT-OK} at L301 for a field its record layout never declares.</p>
     *
     * <p>Refactoring Rationale: this read moved from {@code GET /api/v1/customers/{customerId}/record} to
     * {@code POST} on {@value #RECORD_PATH} with the identifier in a body, for the reason
     * {@link CustomerLookupRequest} records for the existence check beside it -- an identifier in a path
     * segment is composed into the load balancer's access record before any application code runs. The
     * request record is shared with that check rather than duplicated, because the two constrain the same
     * nine-digit key to the same range and a second record would be the same three constraints in a second
     * place.</p>
     *
     * <p>Refactoring Rationale: this operation and the master scan beside it now require
     * {@link com.carddemo.common.security.InternalServiceToken#SCOPE_CUSTOMER_MASTER_READ}, where both
     * previously answered to the same
     * {@link com.carddemo.common.security.InternalServiceToken#SCOPE_CUSTOMER_READ} as the
     * single-key decision reads. That was an escalation rather than an imprecision: the two contexts that
     * hold the decision scope mint it in order to resolve one card number, and while this read shared it,
     * a token issued for that lookup could return all eighteen fields of any customer record. The scope
     * boundary is drawn by what a token can read, and the reasoning is recorded once on the scope constant
     * rather than restated at each operation it governs.</p>
     *
     * @param request the lookup request carrying the customer identifier, the key {@code CUST-ID} declares
     *     at L5 of {@code app/cpy/CVCUS01Y.cpy}; must satisfy its declared constraints
     * @return the customer with both stored identifiers masked, a {@link CustomerResponse}, never
     *     {@code null}
     * @throws NoSuchElementException if the customer master holds no such row, which the shared advice
     *     renders as HTTP 404; the body carries the advice's own fixed absence sentence rather than the
     *     reference one, because the advice passes a carried sentence through only when it ends in an
     *     ellipsis -- its proof that the text came from this repository's catalogue -- and the customer
     *     absence condition at L133 with L134 of {@code app/cbl/COACTVWC.cbl} has none. The reference
     *     wording survives in the raised exception and in the advice's log line, and a client needing it
     *     takes it from its own message catalogue keyed by the originating copybook
     */
    @PostMapping(path = RECORD_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public CustomerResponse readRecord(@Valid @RequestBody CustomerLookupRequest request) {
        // WHY : Assumptions: the transaction boundary and the not-found decision both sit on the service
        //   method this handler calls, so neither is restated here. The reference's own terminal failure
        //   path is a language-environment abend -- Z-ABEND-PROGRAM. at L154 of app/cbl/CBCUS01C.cbl
        //   calling CEE3ABD at L158 -- which in the target is an ordinary exception propagating to the
        //   single shared advice, so this handler catches nothing and converts nothing.
        return this.reads.readCustomer(request.customerId());
    }

    /**
     * Reads one ascending page of the customer master, resuming from a position a previous page issued.
     *
     * <p>Purpose: this is the migrated form of the sweep {@code app/cbl/CBCUS01C.cbl} drives at L74
     * through L81 -- a {@code PERFORM UNTIL END-OF-FILE = 'Y'} whose nested pair of guards at L75 and L77
     * admits one record at a time, entered after the open at L72 and left at the close at L83. The
     * reference filters nothing and pages nothing, so the ORDER and the ACCESS PATH are the whole of what
     * has to be carried across, and both are: ascending {@code CUST-ID}, following from
     * {@code ACCESS MODE IS SEQUENTIAL} at L31 over {@code RECORD KEY IS FD-CUST-ID} at L32.</p>
     *
     * <p>Trade-offs: the reference sweep is UNBOUNDED and this operation is bounded, which is the one
     * behavioural difference between them and is accepted deliberately. The reference is a batch reader
     * writing to a print stream, so reading the whole master in one step costs it nothing; an operation
     * answering a request cannot stream an entire master into one response. What is given up is that a
     * caller wanting every row now issues a sequence of requests where the reference ran one step. What
     * is preserved is everything a caller can observe about the order those rows arrive in and the path
     * they are reached by, so the sequence of pages concatenates to the sequence L78 would have
     * written.</p>
     *
     * <p>Alternatives Considered: positioning the page by offset or by page number, rejected on
     * correctness rather than on cost. The reference's own browse state is already a keyset cursor: at
     * L230 through L244 of {@code app/cbl/COCRDLIC.cbl} it carries a trailing key pair at L230 through
     * L232, a leading key pair at L233 through L235, a screen ordinal at L237, a last-screen-displayed
     * flag at L239 through L241 whose two conditions read shown as zero and not-shown as nine, and a
     * further-rows indicator at L242 through L244, with a row counter at L145 -- and not one offset, row
     * number or page-size field anywhere in that structure. Two of those members are deliberately dropped
     * rather than migrated, the screen ordinal and the last-screen flag, so that no page number crosses
     * the wire in either direction. What specifically goes wrong with an offset is that it is evaluated
     * against the table as it stands when each page is fetched, so a row inserted or removed between two
     * fetches shifts the window and the caller silently skips a row or receives the same row twice; a
     * position naming the last key seen is not moved by a concurrent write, which is the behaviour a
     * browse by key already had.</p>
     *
     * <p>Assumptions: the position is OPAQUE to the caller and is opened by the service before it reaches
     * a predicate, so a position this scan did not issue is refused rather than used. Only its length is
     * constrained here, and that constraint exists so an oversized value is refused at the edge instead
     * of being carried into the sealer. Which query, which direction and which lifetime a position was
     * issued for are the sealer's to decide, one layer in.</p>
     *
     * <p>Assumptions: an absent position and a blank one are folded into the SAME outcome, the opening
     * page, and the classification is made by asking the shared validation type rather than by comparing
     * against a literal. The reference is why a literal comparison would be wrong: inside one program,
     * {@code app/cbl/COACTUPC.cbl} encodes an acceptable field three different ways -- the digit one at
     * L184 against a space blank at L186, a figurative low-value at L197 against the letter B blank at
     * L199, and a two-member value set at L193 and again at L350 -- so no single literal identifies the
     * acceptable state even within that one reference. Blank is treated as a SUBSET of error and not as a
     * third peer state, which is what makes folding it into the opening page correct: the shared type's
     * error predicate answers true for both of its error states, so a blank position is never handed to
     * the sealer, where it would be refused as malformed and reported as a fault in a request that merely
     * asked to start at the beginning.</p>
     *
     * <p>Assumptions: a rejected parameter is answered as HTTP 400 carrying the per-field array rather
     * than a bare status, and the array is assembled by the single shared advice from the problem
     * record's own nested per-field entry. That entry carries the validation state as well as the field
     * and the sentence, and membership of the array is decided there by the same error predicate this
     * method uses, so the two agree by construction instead of by convention.</p>
     *
     * @param cursor the opaque position a previous page issued, or absent to read the opening page of the
     *     scan; a blank value reads the opening page for the reason recorded above
     * @param size the number of rows the page may carry, or absent to take the scan's own default; bounded
     *     at both ends, since a page of no rows would answer nothing and the ceiling is what makes the
     *     bounded scan bounded
     * @return one ascending page with its two boundary positions and its further-rows indicator, a
     *     {@link PageResponse} of {@link CustomerResponse}, carrying no rows when the position is at the
     *     end of the master; never {@code null}
     * @throws CursorToken.InvalidCursorException if the position is not one this scan issued, which the
     *     shared advice renders as HTTP 400 keyed to the cursor
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<CustomerResponse> listCustomers(
            @RequestParam(name = "cursor", required = false)
            @Size(max = CursorToken.MAX_TOKEN_LENGTH)
            String cursor,
            @RequestParam(name = "size", required = false)
            @Min(1)
            @Max(AccountViewService.CUSTOMER_SCAN_MAX_PAGE_SIZE)
            Integer size) {

        // WHY : Assumptions: the default page size is READ from the service rather than restated here as
        //   a request-parameter default, because the ceiling and the default are one decision and a
        //   second copy at this edge could be changed without the bound moving with it. Passing the
        //   service's own constant is what keeps the two in step.
        return this.reads.listCustomers(scanPosition(cursor),
                size == null ? AccountViewService.CUSTOMER_SCAN_DEFAULT_PAGE_SIZE : size);
    }

    /**
     * Classifies a supplied position and reduces an unusable one to the opening page.
     *
     * <p>Assumptions: the shared never-supplied predicate is what decides the case, and it answers for
     * exactly the set the reference cannot distinguish -- an absent value, an empty one, a run of
     * figurative low-values and a run of spaces all describe a field nobody filled in, because a screen
     * field reaches a COBOL program as characters of declared width rather than as an absence. The
     * outcome is then taken from the derived error predicate rather than from a comparison against a
     * flag value, so blank remains a subset of error and a state added to that type would be reported as
     * an error rather than silently accepted.</p>
     *
     * @param cursor the position exactly as supplied, which may be {@code null} when none arrived
     * @return the position to resume strictly after, or {@code null} to read the opening page
     */
    private static String scanPosition(String cursor) {
        FieldValidationFlag state = FieldValidationFlag.isNeverSupplied(cursor)
                ? FieldValidationFlag.BLANK
                : FieldValidationFlag.VALID;

        // WHY : Assumptions: the accepted value is TRIMMED before it travels on, because a position is
        //   carried in a query string and a caller that reflects a whitespace-padded copy of what it
        //   received would otherwise present a token the sealer cannot verify. Trimming is safe only on
        //   this arm: the blank arm has already been folded into the opening page above, so no value
        //   reaching the trim is wholly whitespace.
        return state.isError() ? null : cursor.trim();
    }
}
