package com.carddemo.reporting.dto;

import com.carddemo.common.validation.FieldValidationFlag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Narrows a statement generation run to one card, or to one account, or to neither.
 *
 * <h2>What a statement request selects</h2>
 *
 * <p>This is the request body of the statement retrieval surface that
 * {@code com.carddemo.reporting.api} exposes in place of the batch statement flow. That flow takes no
 * selector whatsoever: {@code app/cbl/CBSTM03A.CBL} produces its output from a control break on the
 * card number it last saw, held in {@code WS-SAVE-CARD PIC X(16)} at L69, and
 * {@code app/jcl/CREASTMT.JCL} runs that program over the entire prepared file at L79, so one
 * statement is emitted per distinct card present. This record is the selector the migrated surface
 * adds in front of it, and it carries two components and nothing else.
 *
 * <p>Assumptions: each of the three states the two components can be in means something specific, and
 * the third is what makes both optional rather than merely lenient. A card number present narrows the
 * run to that one card, the unit the control break at {@code app/cbl/CBSTM03A.CBL} L69 already works
 * in. An account identifier present names the alternative scope, reached by resolving the account to
 * its cards through the cross-reference {@code app/cbl/CBSTM03B.CBL} opens as its {@code XREF-FILE} at
 * L65. Neither present means every card the prepared input holds, which is exactly the whole-file
 * behaviour of {@code app/jcl/CREASTMT.JCL} L79 and therefore a state this record has to express
 * rather than an incomplete request.
 *
 * <p>Assumptions: whether the two components may be supplied together is deliberately not settled
 * here. A rule relating one component to another decides how a run is composed, which is business
 * logic, and the charter at {@code com.carddemo.reporting.dto} closes this package to business logic
 * entirely. What this record guarantees is narrower and unconditional: each component, taken on its
 * own, is either absent or a value of the shape its copybook field declares.
 *
 * <h2>Two things this record deliberately does not carry</h2>
 *
 * <p>Alternatives Considered: a start-date and end-date pair, mirroring the {@code ReportRequest} type
 * in this same package, was evaluated and rejected. The contrast between the two jobs is the whole of
 * the reason. The report path plainly injects a range -- {@code app/jcl/TRANREPT.jcl} declares
 * {@code PARM-START-DATE} and {@code PARM-END-DATE} as sort symbols at L43 and L44 and
 * {@code app/cbl/CBTRN03C.cbl} receives them over the {@code DATEPARM} channel -- while the statement
 * path injects nothing of the kind: across all 98 lines of {@code app/jcl/CREASTMT.JCL} there is no
 * parameter field, no date symbol and no such channel. A range here would let a caller ask for a
 * statement bounded in a way the baseline cannot produce, and a bound the baseline never applied
 * changes observable output rather than adding a convenience. Functional parity is what forbids it.
 *
 * <p>Alternatives Considered: a format component choosing between the plain-text artifact and the
 * hypertext one was evaluated and rejected, because a run produces both and offers no choice between
 * them. {@code app/jcl/CREASTMT.JCL} creates the plain-text dataset at L87 to L91 with
 * {@code DCB=(LRECL=80,...)} and the hypertext dataset at L92 to L96 with
 * {@code DCB=(LRECL=100,...)}, and the program agrees from the inside, declaring
 * {@code FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL} L45 and
 * {@code FD-HTMLFILE-REC PIC X(100)} at L46. A selector would let a caller request less than a run
 * produces, which narrows observable output rather than parameterising it. One reading that looks
 * like a conflict is followed through here so it is not mistaken for one: the earlier stanza that
 * clears the previous run's output declares the hypertext dataset with {@code LRECL=80} at L69, but
 * that stanza belongs to a deletion step running {@code IEFBR14} at L66 which writes no data at all,
 * so the authoritative hypertext width is the {@code LRECL=100} at L94.
 *
 * <h2>Shape, and where the contracts behind it come from</h2>
 *
 * <p>Assumptions: both components are strings of digits rather than numbers, on the strength of the
 * baseline declaring each identifier twice over the same bytes -- once as characters and once as a
 * number -- at {@code app/cpy/CVCRD01Y.cpy}, where {@code CC-ACCT-ID PIC X(11)} at L34 is redefined
 * as {@code CC-ACCT-ID-N PIC 9(11)} at L36 and {@code CC-CARD-NUM PIC X(16)} at L37 is redefined
 * numerically at L39. The character declaration is what the wire carries and the numeric one exists
 * so that arithmetic can reach the same bytes, so a string is the representation that survives the
 * move: a numeric component would discard a leading zero the declared width preserves, and a 16-digit
 * card number does not fit a 32-bit integer at all.
 *
 * <p>Assumptions: this record declares no page envelope, no problem shape and no per-field error
 * carrier of its own, because {@code com.carddemo.common.web.PageResponse},
 * {@code com.carddemo.common.error.ApiError} and
 * {@code com.carddemo.common.validation.FieldValidationFlag} are consumed from the shared kernel under
 * transformation rule T2, which turns one former copybook inclusion into one import from the single
 * package owning the contract. It carries no monetary and no timestamp component either, so it reaches
 * nothing in the kernel's money or time packages; a statement request selects a scope and reports no
 * amounts.
 *
 * @param cardNumber the card number a run is narrowed to, as a string of at most 16 digits, or
 *     {@code null} to leave the run unnarrowed by card; 16 is the width
 *     {@code TRNX-CARD-NUM PIC X(16)} declares at {@code app/cpy/COSTM01.CPY} L22, and a value
 *     arriving with trailing padding is stored without it
 * @param accountId the account identifier whose cards a run is narrowed to, as a string of at most
 *     11 digits, or {@code null} to leave the run unnarrowed by account; 11 is the width
 *     {@code ACCT-ID PIC 9(11)} declares at {@code app/cpy/CVACT01Y.cpy} L5, and a value arriving
 *     with trailing padding is stored without it
 */
public record StatementRequest(
        @Size(max = CARD_NUMBER_WIDTH) @Pattern(regexp = DIGITS_ONLY) String cardNumber,
        @Size(max = ACCOUNT_ID_WIDTH) @Pattern(regexp = DIGITS_ONLY) String accountId) {

    /**
     * The number of positions the baseline declares for a card number.
     *
     * <p>Assumptions: 16 is read from {@code TRNX-CARD-NUM PIC X(16)} at
     * {@code app/cpy/COSTM01.CPY} L22, and is corroborated by {@code CC-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVCRD01Y.cpy} L37 and by the control-break field
     * {@code WS-SAVE-CARD PIC X(16)} at {@code app/cbl/CBSTM03A.CBL} L69. Three independent
     * declarations agree, so the width is a contract rather than a reading.
     *
     * <p>Alternatives Considered: writing the literal straight into the constraint annotation was
     * the obvious alternative and is rejected. A named constant leaves exactly one line to compare
     * against {@code app/cpy/COSTM01.CPY} L22, whereas a literal inside an annotation is a number
     * with no stated provenance sitting where nobody looks for one. The width is quoted in the
     * prose of this file as well, in the component descriptions, and that is documentation rather
     * than a second executable source: nothing reads those sentences to decide anything.
     *
     * <p>Refactoring Rationale: this width now has exactly ONE executable position, the
     * {@code @Size} constraint in the header above. It formerly had two, because the constructor
     * also held a throwing guard against the same number, and two executable positions for one
     * declared width is what lets a caller be published one contract and held to another -- the
     * guard fired first and answered with a body the constraint would have described per field. The
     * guard is gone and the constraint is the single authority.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * The number of positions the baseline declares for an account identifier.
     *
     * <p>Assumptions: 11 is read from {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy}
     * L5 and corroborated by {@code FD-ACCT-ID PIC 9(11)} at {@code app/cbl/CBSTM03B.CBL} L77, the
     * key of the account file the statement flow reads. The numeric picture at L5 and the character
     * picture {@code CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy} L34 describe the same 11
     * positions, which is the overlay discipline recorded on this type.
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The expression a present component value has to match in full.
     *
     * <p>Assumptions: the character class is written out as an explicit range rather than as the
     * shorthand digit class. The shorthand is interpretation-dependent -- it broadens to every
     * decimal digit in Unicode when Unicode character-class mode is turned on by an embedded flag
     * expression -- so a value carrying a non-Latin digit would then satisfy a constraint the
     * copybook field cannot hold.
     *
     * <p>Assumptions: the quantifier requires at least one digit rather than allowing none, because
     * the constructor below already reports a value carrying no content as absent, so a present
     * value always has content by the time this expression is applied. Constraint evaluation treats
     * an absent value as satisfied, which is what keeps an unsupplied component from being reported
     * as malformed.
     */
    private static final String DIGITS_ONLY = "[0-9]+";

    /**
     * Normalises both components onto this record's absence contract.
     *
     * <p>Assumptions: normalisation belongs at construction rather than at each accessor, so the
     * stored state and the returned state are the same state and no caller has to know which of
     * the two it holds. An instance cannot come into being without passing through here, which is
     * what makes the contract hold by construction rather than by every caller remembering it.
     *
     * <p>Refactoring Rationale: this constructor previously also refused an over-width component by
     * throwing, and that guard has been removed. It defeated the one outcome the charter at
     * {@code com.carddemo.reporting.dto} requires: a rejection has to name the component it
     * concerns. An exception raised while a request body is being bound does not arrive as a
     * per-field entry -- it surfaces as a generic malformed-body failure -- so a caller that sent a
     * 17-character card number was told the body was unreadable rather than which of its two fields
     * was too long. Width is now asserted by the {@code @Size} constraint on each component in the
     * header above, exactly as the digits-only shape already was, so both kinds of malformed input
     * take the same route and both name their field.
     *
     * <p>Alternatives Considered: keeping the guard alongside the constraint, on the reasoning that
     * the annotations are evaluated only when something asks a validator to evaluate them -- which
     * the transport boundary does and a caller constructing this record directly does not.
     * Rejected, because the two mechanisms answer to different consumers and cannot be made to
     * agree on an outcome: for a request arriving over HTTP the guard fires first and replaces the
     * structured per-field response with an unstructured one, so keeping it does not add a check to
     * that path, it degrades the check already there. A caller constructing the record in process is
     * a caller inside this service, and the charter's obligation to name the failing field is owed
     * to the client across the boundary.
     *
     * <p>Assumptions: width and shape are therefore now enforced identically and in one place. A
     * value of legal length carrying something other than a digit fits its field perfectly -- an
     * {@code X(16)} picture holds any 16 characters -- and what rejects it is the numeric overlay
     * discipline that {@code app/cpy/CVCRD01Y.cpy} L37 and L39 declare. A value longer than its
     * field is refused by the width the same copybook lineage declares, 16 positions at
     * {@code app/cpy/COSTM01.CPY} L22 and 11 at {@code app/cpy/CVACT01Y.cpy} L5. Both surface as a
     * per-field entry through {@code com.carddemo.common.error.ApiError}, naming the component and
     * never reproducing the value -- which also preserves the masking rule this context applies to
     * a primary account number, since a constraint violation reports the field and not its
     * content.
     *
     * @param cardNumber the card number as it arrived, which may be {@code null}, may carry
     *     trailing padding and may be entirely padding; it is stored with the padding removed, or
     *     as {@code null} when it carries no content
     * @param accountId the account identifier as it arrived, on the same three terms as the card
     *     number above; it is stored with the padding removed, or as {@code null} when it carries
     *     no content
     */
    public StatementRequest {
        // Assumptions: the two components are normalised independently and in the order the header
        //   declares them, because neither one's treatment depends on the other. A rule relating
        //   them would be a scope rule, and this record holds none, so nothing here inspects one
        //   component while handling the other.
        cardNumber = normalise(cardNumber);
        accountId = normalise(accountId);
    }

    /**
     * Renders this request for a log or a diagnostic with the primary account number masked.
     *
     * <p>Refactoring Rationale: the rendering a record generates for itself names every component
     * verbatim, and one of this record's two components is a primary account number. Every refusal
     * message in this file was already written to withhold that value, but the generated rendering
     * defeated all of that care through one path nobody has to write on purpose: a request object
     * interpolated into a log statement, an assertion message or a framework's own request trace. This
     * override closes that path, so the masking is a property of the type rather than of every place
     * the type is mentioned.</p>
     *
     * <p>Assumptions: the mask reveals the trailing four digits and no more, which is the same
     * concession the migration's mapping layer makes on every response except the administrative
     * card-detail endpoint. Four digits is what lets an operator match a log line to a support call
     * without the line carrying a usable card number.</p>
     *
     * <p>Alternatives Considered: withholding the card number entirely, rendering only its length.
     * Rejected because the length is fixed at the 16 positions {@code app/cpy/COSTM01.CPY} L22
     * declares, so it distinguishes nothing, and a diagnostic that cannot tell two statement runs apart
     * is of no use in the one situation it exists for. Alternatives Considered: masking the account
     * identifier too. Rejected because that identifier is a system key rather than protected data: it
     * addresses the resource in a request path and in the correlation of a run, so masking it would
     * make a log unusable while withholding nothing the path had not already carried.</p>
     *
     * @return the request with its card number reduced to a mask and its last four digits, and its
     *     account identifier unchanged; an absent component renders as {@code null}
     */
    @Override
    public String toString() {
        return "StatementRequest[cardNumber=" + maskCardNumber(cardNumber)
                + ", accountId=" + accountId + ']';
    }

    /**
     * Reduces a card number to a fixed mask and its trailing four digits.
     *
     * @param cardNumber the normalised card number, or {@code null} for an unnarrowed run
     * @return {@code null} when {@code cardNumber} is {@code null}; otherwise the string
     *     {@code "****"} followed by at most the last four characters, so a value shorter than four
     *     characters is rendered whole rather than padded into something it is not
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null) {
            return null;
        }

        // WHY : Assumptions: the tail is taken with a bounded start index rather than by subtracting
        //       four, because a value shorter than four characters is reachable here -- the constraint
        //       on this component bounds its maximum and not its minimum -- and subtracting would raise
        //       out of a rendering method, replacing a log line with an unrelated failure at exactly
        //       the moment the log line was wanted.
        int tailStart = Math.max(0, cardNumber.length() - 4);
        return "****" + cardNumber.substring(tailStart);
    }

    /**
     * Removes the trailing padding a declared-width field carries, and reports a value with no
     * content as absent.
     *
     * <p>Assumptions: padding is removed from the end only, never from the start, because a
     * character field of declared width is left-justified and padded on the right -- which is why
     * {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY} L22 can be compared against a
     * shorter literal at all. A blank at the start of such a field is therefore content and not
     * padding, and silently removing it would accept a value the declared-width contract cannot
     * produce. It is left in place so that the constraint annotation reports it.
     *
     * <p>Alternatives Considered: the platform's general trimming and blank-testing methods were
     * evaluated and rejected, and each fails differently. Trimming also removes the low-value byte
     * that the shared kernel's absence test needs to still be able to see, and blank-testing
     * treats a run of low-value bytes as present while treating any whitespace as absent -- so one
     * of them destroys the evidence and the other misreads it. Delegating the decision to
     * {@code FieldValidationFlag} keeps a single reading of absence for every validated field in
     * the migration, and that reading is the two-arm test the baseline performs on a field's value
     * rather than on its marker.
     *
     * <p>Assumptions: a value that is blank once trimmed is stored as absent so that a component
     * supplied empty and a component not supplied at all cannot diverge downstream. That is not a
     * flattening of a distinction the baseline draws: the baseline attaches its low-value sentinel
     * to one field only, {@code CCARD-RETURN-MSG} at {@code app/cpy/CVCRD01Y.cpy} L30, and the
     * charter at {@code com.carddemo.reporting.dto} records where that distinction is preserved.
     * A scope selector is not one of those places, because an empty selector and a missing
     * selector both narrow a run by nothing.
     *
     * @param raw the component value as it arrived, which may be {@code null} when the caller
     *     supplied nothing for it at all
     * @return the value with its trailing padding removed, or {@code null} when the value carries
     *     no content by the shared kernel's absence test
     */
    private static String normalise(String raw) {
        if (raw == null) {
            return null;
        }

        // Assumptions: the pad character is taken from the shared kernel rather than written as a
        //   literal here, because that constant is declared as the byte FOUND IN an input field and
        //   is deliberately kept apart there from the same byte's meaning when it is STORED IN a
        //   validation marker, which is a different layer. Reaching for the constant keeps this
        //   loop attached to the layer it actually operates on.
        int end = raw.length();
        while (end > 0 && raw.charAt(end - 1) == FieldValidationFlag.ABSENT_INPUT_SPACE) {
            end--;
        }

        String stripped = raw.substring(0, end);

        // Assumptions: the absence test is applied AFTER trimming rather than before it, and the
        //   order is observable on one specific input. The test reads a value as absent when it is
        //   wholly one pad byte or wholly the other, never a mixture, so a value of low-value bytes
        //   followed by trailing blanks matches neither arm as it arrives and would be reported
        //   present. Trimming first reduces it to low-value bytes alone, which the test does read
        //   as absent. Testing first would let that one value through as content.
        return FieldValidationFlag.isNeverSupplied(stripped) ? null : stripped;
    }
}
