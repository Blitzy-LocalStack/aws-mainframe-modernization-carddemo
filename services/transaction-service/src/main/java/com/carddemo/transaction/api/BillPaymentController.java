package com.carddemo.transaction.api;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.transaction.dto.BillPaymentOutcome;
import com.carddemo.transaction.dto.BillPaymentPreview;
import com.carddemo.transaction.dto.BillPaymentRequest;
import com.carddemo.transaction.dto.BillPaymentResponse;
import com.carddemo.transaction.service.BillPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Serves the bill-payment operation, migrated from {@code app/cbl/COBIL00C.cbl}.
 *
 * <p>Purpose: this adapter is the HTTP surface of CICS transaction {@code CB00}, whose official name in
 * the online-components table of the root {@code README.md} at line 302 is <b>Bill Payment</b> and whose
 * specification is the 572-line program {@code app/cbl/COBIL00C.cbl}. It accepts one submission,
 * delegates it to {@link BillPaymentService#payBalanceInFull(BillPaymentRequest)}, and maps what that
 * method returned onto a status code, a {@code Location} header and a body. It decides nothing about the
 * payment: no balance is computed here, no timestamp is rendered, no transaction identifier is derived,
 * no message is assembled and none of the ten values the baseline moves into the transaction record at
 * lines 220 to 229 is supplied here. The baseline program is reference material, read as the
 * specification and never modified, and every claim below cites it by line so that a reader can check
 * it against the source rather than trusting this text.</p>
 *
 * <p>Assumptions: every sentence this operation puts in front of an operator is owned by
 * {@link BillPaymentService} or by the type it hands to, and is cited here by line rather than
 * reproduced. The reason is the same one the repository already applies to its COBOL unit tests at
 * {@code tests/README.md} lines 540 to 542, where a record layout is resolved through the compiler
 * copybook path and never duplicated: one contract, one owner. A message declared in this class as well
 * would be a second copy of a value the migration reproduces character for character, and the two could
 * then drift while both still compiled.</p>
 *
 * <p>Alternatives Considered: a shared abstract adapter beneath this class and the sibling adapter that
 * carries this context's other three operations, holding whatever the two have in common. Rejected
 * because they have nothing to hold: their handlers differ in arity, in HTTP method and in what they
 * return, so the supertype would carry no behaviour at all. The concrete cost of adding it is that a
 * reader tracing which endpoint re-expresses which baseline program would meet an inherited mapping
 * before finding the program, and the correspondence this class exists to make legible is precisely that
 * one. Constructor injection of the one service each adapter needs is what they share instead, and that
 * is a pattern rather than a supertype.</p>
 *
 * <p>Refactoring Rationale: the mechanism replaced is the pseudo-conversational communication area
 * declared at {@code app/cpy/COCOM01Y.cpy} lines 19 to 44, and what was wrong with it is specific rather
 * than aesthetic. Identity travelled in {@code CDEMO-USER-ID PIC X(08)} at line 25 and
 * {@code CDEMO-USER-TYPE PIC X(01)} at line 26 with its two condition names, {@code 'A'} at line 27 and
 * {@code 'U'} at line 28, inside a structure the client held between turns and handed back, so the user
 * type was client-asserted and a caller could in principle present its own. Here identity arrives as
 * claims on a token the issuer signed and this service verifies, converted into authorities by the
 * shared kernel's claim converter and wired by the sibling {@code SecurityConfig}, whose authority names
 * are the group names verbatim, so every predicate is an authority predicate such as
 * {@code hasAuthority}. Selection context, carried by {@code CDEMO-ACCT-ID PIC 9(11)} at line 38,
 * arrives in the request body as the account identifier. Navigation is client-side and no response
 * leaving this class names a next program. The re-entry discriminator
 * {@code CDEMO-PGM-CONTEXT PIC 9(01)} at line 29, with its enter value at line 30 and its re-enter value
 * at line 31, has no counterpart here at all, and no resubmission, first-entry or turn-count concept may
 * be introduced under any name: a stateless handler that answers with a per-field error array has no
 * such distinction left to draw. Corroboration comes from the baseline's own resource definition rather
 * than from the target design, which is why it is worth citing: {@code app/csd/CARDDEMO.CSD:337} defines
 * this transaction and {@code :338} declares it {@code TWASIZE(0)}, so the per-task work area it could
 * have reserved is zero bytes wide.</p>
 *
 * <p>Alternatives Considered: transporting the balance as a JSON number. Rejected on a measurable loss.
 * Most clients parse a JSON number into an IEEE-754 binary64 value on receipt, and that binary
 * representation cannot hold every two-place decimal fraction exactly, while
 * {@code ACCT-CURR-BAL PIC S9(10)V99} is twelve significant decimal digits and leaves no margin for an
 * approximation. The consequence is sharper on this operation than on any other in this context: line
 * 224 of the baseline moves that same balance into the transaction amount, so the figure carried by
 * {@code BillPaymentResponse.currentBalance} is simultaneously the sum that was paid, and an inexact
 * round trip would misstate the payment itself rather than merely a display. The exact-decimal type in
 * {@code com.carddemo.common.money} is what the response declares, and the shared codec module the
 * kernel registers is what renders it as a quoted string; neither is configured here.</p>
 *
 * <p>Assumptions: no primary account number and no card verification value reaches any response of this
 * operation, under any status code. The baseline resolves a card number through the cross-reference read
 * at line 211 and writes it into the transaction record at line 225, and the published body for this
 * operation carries no card member at all, which the screen corroborates: {@code app/cpy-bms/COBIL00.CPY}
 * declares only three business fields, {@code ACTIDINI PIC X(11)} at line 60,
 * {@code CURBALI PIC X(14)} at line 66 and {@code CONFIRMI PIC X(1)} at line 72. Where a card number is
 * reported elsewhere in this migration it is masked to its last four digits, and that masking belongs to
 * the package this context reserves for copybook representation concerns rather than to an adapter. This
 * class therefore offers no parameter that could widen a response and adds no member that could carry a
 * verification value.</p>
 *
 * <p>Assumptions: an absent {@code returnMessage} is null and never a blank string of the declared width,
 * and the copybook rather than convention is what settles it. {@code app/cpy/CVCRD01Y.cpy} declares
 * {@code CCARD-ERROR-MSG PIC X(75)} at line 28 and {@code CCARD-RETURN-MSG PIC X(75)} at line 29, the
 * same width, and attaches a message-off condition valued at {@code LOW-VALUES} to the return message
 * alone at line 30. {@code LOW-VALUES} means the message is off; {@code SPACES} means a message that is
 * present and empty, which is the state {@code WS-MESSAGE} starts in at line 39 of the program.
 * Collapsing the two into one empty string would erase a distinction the baseline uses as control flow,
 * and a client would render an empty message band where the baseline rendered none.</p>
 *
 * <p>Assumptions: the two sentences this program uses for a failed transaction lookup are the uppercase
 * form at lines 463 and 492, and they stay distinct from the lowercase form a sibling program of the
 * baseline uses. Reproducing user-visible text character for character is the migration's rule for every
 * such string, so normalising the case to remove what looks like an inconsistency would alter text a
 * terminal displayed. The same holds for the payment-write failure at line 543, whose wording is its own
 * and is not merged with any other; both are documented on the operation below.</p>
 */
@RestController
@RequestMapping(path = BillPaymentController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = BillPaymentController.TAG_BILL_PAYMENT,
        description = "Full-balance payments against an account, migrated from CICS transaction CB00.")
public class BillPaymentController {

    /** The collection path the payment operation is served at. */
    public static final String BASE_PATH = "/api/v1/billpay";

    /**
     * The published tag this operation is grouped under, and the official screen name it carries.
     *
     * <p>Assumptions: the value is the name the online-components table of the root {@code README.md}
     * gives transaction {@code CB00} at line 302, reproduced exactly, and it is also the tag the published
     * contract groups this operation under, so one string serves both. Alternatives Considered: taking it
     * from the sibling configuration class that declares the same tag for the generated description.
     * Rejected because this package's reference list admits the sibling request, response and service
     * types and the shared kernel, and not the configuration package, so an adapter reaching into
     * configuration for a literal would cross a boundary this charter draws in order to keep an adapter
     * dependent on nothing that boots the application.</p>
     */
    public static final String TAG_BILL_PAYMENT = "Bill Payment";

    /** Template of the path a posted payment's {@code Location} header addresses. */
    public static final String PAID_TRANSACTION_PATH = "/api/v1/transactions/{transactionId}";

    /** The payment this controller delegates to. */
    private final BillPaymentService billPaymentService;

    /**
     * Builds the controller over the payment service.
     *
     * @param billPaymentService the payment this controller delegates every submission to; must not be
     *     {@code null}
     * @throws NullPointerException if {@code billPaymentService} is {@code null}
     */
    public BillPaymentController(BillPaymentService billPaymentService) {
        this.billPaymentService =
                Objects.requireNonNull(billPaymentService, "billPaymentService must not be null");
    }

    /**
     * Pays an account's whole current balance, or reports the balance a confirmed submission would pay.
     *
     * <p>Purpose: this is the single operation of transaction {@code CB00}, and it answers the same three
     * classes of turn the baseline paragraph at {@code app/cbl/COBIL00C.cbl:154} answers. A confirmed
     * submission writes one transaction and has the balance reduced, and is answered 201 with the path of
     * the transaction that was written. A submission whose confirmation was withheld or declined writes
     * nothing and is answered 200. A submission the baseline refuses is answered with the status its
     * refusal maps to, carrying that program's own sentence.</p>
     *
     * <p>Alternatives Considered: accepting a payment amount. Rejected because the baseline admits no
     * partial payment: line 224 moves the whole of {@code ACCT-CURR-BAL} into {@code TRAN-AMT} and line
     * 234 subtracts that same amount back out of the balance, so the sum paid is derived from stored
     * state and the balance left behind is invariably zero, and the screen carries no amount field to
     * migrate -- {@code app/cpy-bms/COBIL00.CPY} declares only the account identifier at line 60, the
     * balance at line 66 and the confirmation at line 72. Accepting one would add a partial-payment
     * capability the baseline cannot express, which is a change in behaviour rather than in structure.
     * That is also why the balance reported back is the figure as it stood before the payment: lines 193
     * and 194 fill the screen field before the confirmation branch at line 210 is reached, and nothing
     * refills it, so the one number is both the balance shown and the sum paid.</p>
     *
     * <p>Alternatives Considered: answering 400 when the confirmation is {@code 'N'} or {@code 'n'}.
     * Rejected because that branch, at lines 178 to 181, clears the screen and raises the program's error
     * flag while moving no message whatsoever -- the flag is a control-flow short circuit that suppresses
     * the rest of the paragraph, not a complaint about a field. Answering an error there would surface a
     * refusal the baseline never displays, so a client would render a field highlight the 3270 screen
     * never showed. The declined turn is therefore an acknowledged turn that wrote nothing: 200, with the
     * balance and no error body, and no field-error element at all.</p>
     *
     * <p>Alternatives Considered: reporting every validation failure of a submission at once. Rejected
     * because this program emits exactly one message per turn and its stages are guarded: the
     * blank-account evaluation at lines 158 to 167 is followed by a re-test of the error flag at line
     * 169 before the confirmation evaluation at lines 173 to 191, another at line 197 before the
     * balance test at lines 198 to 201, and another at line 208 before the payment block, so the FIRST
     * failure wins and each later stage is bypassed once the flag is raised. This precedence is a
     * property of this program and is not shared with the capture path of the baseline, whose blocks are
     * mutually unguarded. Returning an array of every failure would therefore surface complaints the
     * baseline never shows. The per-field array of the shared problem body accordingly carries ONE
     * element for this operation, and it distinguishes a never-supplied field from a field whose value
     * was refused: the blank state carries the marker the templated highlight copybook moves into an
     * empty field, and on this operation the blank case is the account-identifier test at line 159
     * answered with the sentence at line 161. The states are those of
     * {@code com.carddemo.common.validation.FieldValidationFlag}, which the service selects and this
     * class neither chooses nor rewrites.</p>
     *
     * <p>Assumptions: the balance test at line 198 is inclusive, so a balance of exactly zero is also
     * nothing to pay. The comparison is {@code <= ZEROS} combined at line 199 with the account identifier
     * being non-blank, and reading it as a strict comparison would let a zero-balance submission through
     * to the payment block, which the baseline never does.</p>
     *
     * <p>Alternatives Considered: distinguishing an account that does not exist from an account that
     * exists with no card cross-reference. Rejected because the baseline emits ONE sentence from THREE
     * places -- the account read at line 361, the balance rewrite at line 392 and the cross-reference
     * read at line 425 all move the same text -- so the two conditions are indistinguishable to an
     * operator. Publishing them apart would surface a distinction the baseline never shows, so both 404
     * bodies carry identical message text.</p>
     *
     * <p>Assumptions: a duplicate transaction identifier is a 409 on THIS operation and not only on the
     * capture, because the write paragraph at lines 510 to 547 handles the duplicate-key response at line
     * 533 and the duplicate-record response at line 534 by falling through to one handler, which answers
     * with the sentence at line 536. The singular verb in that sentence is the program's own and is
     * carried across as written. The derivation that makes the collision possible is the unlocked
     * read-then-increment at lines 212 to 217, and it stays in the service: this class does not derive,
     * predict or validate the identifier.</p>
     *
     * <p>Assumptions: the sentence a posted payment carries back is assembled by the baseline and is
     * reproduced with its spacing intact. Lines 527 to 531 concatenate a first literal that already ends
     * in a space with a second that opens with one, so the emitted text carries TWO consecutive spaces
     * before the identifier label, and that label reads "Transaction ID" in full.</p>
     *
     * <p>Alternatives Considered: one assembly shared with the capture path's success sentence. Rejected
     * because the two differ in both parts -- a different leading sentence and a different identifier
     * label -- so a single template would emit text that is wrong for at least one of them, and both are
     * strings an operator reads.</p>
     *
     * <p>Assumptions: the sentence for a payment that could not be written, at line 543, names bill
     * payment specifically and is a different string from the capture path's write failure. They are not
     * merged, because merging them would change the text an operator sees for one of the two.</p>
     *
     * <p>Assumptions: the correlation header is documented on the request and on the two outcomes that
     * carry a body of this operation's own, and is not restated on each refusal, although the shared
     * filter in {@code com.carddemo.common.web} echoes it on every response. The published contract makes
     * the same choice for the same stated reason, declaring the response header once among its components
     * and referring to it: a header re-typed at every response is one that will eventually be typed
     * differently.</p>
     *
     * @param request the submitted payment, bean-validated before this method is entered, carrying the
     *     eleven-digit account identifier and the one-character confirmation and no amount; must not be
     *     {@code null}
     * @return 201 carrying the posted {@link BillPaymentResponse} and the {@code Location} of the
     *     transaction that was written, or 200 carrying the {@link BillPaymentPreview} with the balance a
     *     confirmed submission would pay together with the baseline's prompt, its nothing-to-pay advisory
     *     or no sentence at all; never {@code null}
     * @throws ClientInputException an invalid-argument failure raised when the account identifier was
     *     never supplied, answered with line 161, or the confirmation carries a value outside the four
     *     the baseline accepts, answered with lines 187 and 188; rendered as 400 with the one-element
     *     per-field array
     * @throws NoSuchElementException if the identifier names no account or the account has no
     *     cross-reference entry, answered with lines 361 and 425; rendered as 404
     * @throws DataIntegrityViolationException if the identifier the payment derived is already taken,
     *     which is the fall-through at lines 533 and 534; rendered as 409
     * @throws IllegalStateException if a read failed, answered with line 368 or line 432, if the payment
     *     could not be written, answered with line 543, or if the balance change was refused, answered
     *     with line 399; rendered as 500
     * @throws NullPointerException if {@code request} is {@code null}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "payAccountBalanceInFull",
            summary = "Pay an account's entire current balance, or preview what would be paid.",
            description = "Pays the whole of an account's current balance, migrated from"
                    + " app/cbl/COBIL00C.cbl. The payment is full-balance only and the request carries no"
                    + " amount, because L224 moves the entire balance into the transaction amount and"
                    + " L234 subtracts it back out. The confirmation selects between previewing and"
                    + " paying, which is why one operation serves both: the baseline makes that choice"
                    + " inside a single EVALUATE at L173 to L191 within one input set.",
            parameters = @Parameter(name = CorrelationIdFilter.CORRELATION_ID_HEADER,
                    in = ParameterIn.HEADER, required = false,
                    description = "Identity of the unit of work this request belongs to, echoed back on"
                            + " the response. Supply it to tie this request to one a caller has already"
                            + " named, or omit it and the service mints one.",
                    schema = @Schema(type = "string")))
    // WHY : ⚠️ Refactoring Rationale: the 200 body is BillPaymentPreview, and the description no longer
    //       says the service emits something narrower than the published contract declares. It used to
    //       name BillPaymentResponse here and record the divergence as visible-by-comparison, which
    //       documented a defect instead of removing it: the body carried a null transactionId that the
    //       published preview schema forbids outright, and reported the balance under currentBalance
    //       where the contract and ui/src/api/transactions.ts both name it payableBalance. The service
    //       now returns the preview shape on this status, so the annotation, the published document and
    //       the browser client agree and there is no divergence left to point at.
    @ApiResponse(responseCode = "200",
            description = "The confirmation was withheld or declined, so no payment was made. On a"
                    + " withheld confirmation the body reports the balance a confirmed request would pay"
                    + " together with the prompt \"Confirm to make a bill payment...\" verbatim from"
                    + " app/cbl/COBIL00C.cbl L237, or \"You have nothing to pay...\" from L201 when the"
                    + " balance is not positive. On a declined confirmation it reports the account"
                    + " identifier alone, with neither a balance nor a sentence, because L180 clears the"
                    + " screen and L178 reaches no account read at all.",
            headers = @Header(name = CorrelationIdFilter.CORRELATION_ID_HEADER,
                    description = "Identity of the unit of work, echoed from the request or minted.",
                    schema = @Schema(type = "string")),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BillPaymentPreview.class)))
    @ApiResponse(responseCode = "201",
            description = "The payment was posted and the balance was reduced by the amount paid, which"
                    + " for this operation is the whole balance. The body carries the assigned"
                    + " transaction identifier and the balance as it stood before the payment.",
            headers = {
                @Header(name = HttpHeaders.LOCATION, required = true,
                        description = "Path of the transaction the payment wrote, of the form"
                                + " /api/v1/transactions/{transactionId}.",
                        schema = @Schema(type = "string")),
                @Header(name = CorrelationIdFilter.CORRELATION_ID_HEADER,
                        description = "Identity of the unit of work, echoed from the request or minted.",
                        schema = @Schema(type = "string"))
            },
            // WHY : Assumptions: this status carries BillPaymentResponse and the 200 above carries
            //       BillPaymentPreview, which is the whole point of the sealed pair -- a written
            //       payment reports the transaction identifier the preview shapes have no member for.
            //       An earlier revision named the preview on BOTH statuses, which disagreed with the
            //       published contract's 201 schema and with the arm below that returns the payment
            //       shape, so a generated client would have been typed to discard the one member the
            //       created resource exists to report.
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BillPaymentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The account identifier is absent or not numeric, or the confirmation is a"
                    + " value the baseline does not accept. The message is \"Acct ID can NOT be"
                    + " empty...\" verbatim from app/cbl/COBIL00C.cbl L161, or \"Invalid value. Valid"
                    + " values are (Y/N)...\" from L187, and the per-field array carries one element.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401",
            description = "The bearer token is absent, expired or malformed.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403",
            description = "The token carries neither authority this context admits.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404",
            description = "No account carries that identifier, or it has no cross-reference entry. The"
                    + " message is \"Account ID NOT found...\" verbatim from app/cbl/COBIL00C.cbl L361,"
                    + " which the baseline also emits from L392 and L425, so the two conditions are"
                    + " reported identically.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409",
            description = "The identifier the payment derived is already taken, which is the"
                    + " duplicate-key and duplicate-record fall-through at app/cbl/COBIL00C.cbl L533 and"
                    + " L534, answered with \"Tran ID already exist...\" from L536; or the account row"
                    + " changed under the submission.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500",
            description = "The payment failed after validation. The message is one of \"Unable to lookup"
                    + " Account...\" from app/cbl/COBIL00C.cbl L368, \"Unable to lookup XREF AIX"
                    + " file...\" from L432, \"Unable to lookup Transaction...\" from L463 and L492,"
                    + " \"Unable to Add Bill pay Transaction...\" from L543, or \"Unable to Update"
                    + " Account...\" from L399.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503",
            description = "Writes are quiesced for the batch window, so no payment can be posted.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BillPaymentOutcome> payAccountBalanceInFull(
            @Valid @RequestBody BillPaymentRequest request) {

        // WHY : Assumptions: no try-catch stands around this call, and the omission is deliberate. Every
        //       failure the service raises is translated by the one advice type the shared kernel owns
        //       and this module receives through its auto-configuration, which maps the invalid-input
        //       family to 400 with the per-field array, an absent record to 404, a contended write to 409
        //       and anything else to 500. Catching here would stand a second translation beside that one
        //       with no declared precedence between them, so the status a caller received would depend on
        //       which ran first.
        BillPaymentOutcome outcome = this.billPaymentService.payBalanceInFull(request);

        // WHY : Refactoring Rationale: the status is chosen from the outcome rather than left at 200 for
        //       every turn, which is what an earlier revision of this class did by returning the body
        //       type directly. What was wrong with that is measurable rather than stylistic: the browser
        //       client at ui/src/api/transactions.ts decides which of the two outcomes occurred from the
        //       status alone, reporting a payment only when it reads 201, so a posted payment answered
        //       200 was rendered to the operator as a preview that had paid nothing, and the Location
        //       header the published contract marks required on that outcome was never sent at all.
        //       Reading the discriminator is not a decision about the payment: the service has already
        //       decided and has already published its answer in that member of the body.
        // WHY : ⚠️ Refactoring Rationale: the outcome is now matched on its TYPE rather than read through
        //       one record's nullable identifier, because the service returns two distinct published
        //       shapes -- BillPaymentPreview for the three turns that pay nothing and BillPaymentResponse
        //       for the one that pays. A pattern switch over the sealed hierarchy is what makes the
        //       identifier available exactly where it exists: the previous form read
        //       outcome.transactionId() off a record whose identifier was null on every non-paying turn,
        //       so the Location header depended on a member the preview shape does not publish at all.
        //       Alternatives Considered: an instanceof test plus a cast. Rejected because a switch over a
        //       sealed interface is checked for exhaustiveness by the compiler, so a third outcome added
        //       to that hierarchy fails this method to compile rather than silently taking a default arm.
        return switch (outcome) {
            case BillPaymentPreview preview -> ResponseEntity.ok(preview);
            case BillPaymentResponse payment ->
                    ResponseEntity.created(paidTransactionLocation(payment.transactionId()))
                            .body(payment);
        };
    }

    /**
     * Renders the path that addresses the transaction a posted payment wrote.
     *
     * <p>Assumptions: the value is a path and not an absolute URL, because that is the form the published
     * contract declares for this header, and a path is what survives an edge that terminates TLS and
     * rewrites a host. The template is expanded rather than concatenated so that the one variable is
     * encoded by the same builder the framework uses elsewhere.</p>
     *
     * <p>Alternatives Considered: importing the path constant the sibling adapter of this package already
     * declares for the transaction resource, or building the value from a method reference to its read
     * handler. Both were rejected because either one makes this adapter depend on that one for no
     * behaviour, and the two are deliberately independent so that each reads against the single baseline
     * program it re-expresses.</p>
     *
     * <p>Trade-offs: the consequence of declaring the template here is accepted rather than dismissed.
     * The transaction member path is now written in this class as well as in that one, and no gate in this
     * build compares the two, so a change to that path is a change this template has to follow. It is
     * recorded because a duplication a reader believes to be checked is worse than one known to rest on
     * review.</p>
     *
     * @param transactionId the sixteen-digit identifier the service assigned to the transaction it wrote,
     *     which the posted outcome always carries; must not be {@code null}
     * @return the path of that transaction, for the {@code Location} header of a posted payment, never
     *     {@code null}
     */
    private static URI paidTransactionLocation(String transactionId) {
        return UriComponentsBuilder.fromPath(PAID_TRANSACTION_PATH)
                .buildAndExpand(transactionId)
                .toUri();
    }
}
