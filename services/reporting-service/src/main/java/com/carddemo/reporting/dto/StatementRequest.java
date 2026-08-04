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
 * {@code com.carddemo.reporting.api} exposes in place of the batch statement flow. That flow is a
 * job driving two programs, and it takes no selector whatsoever:
 * {@code app/cbl/CBSTM03A.CBL} produces its output from a control break on the card number it last
 * saw, held in {@code WS-SAVE-CARD PIC X(16)} at L69, and {@code app/jcl/CREASTMT.JCL} runs that
 * program over the entire prepared file at L79, so one statement is emitted per distinct card
 * present. This record is the selector the migrated surface adds in front of it, and it carries
 * two components and nothing else.
 *
 * <p>Assumptions: each of the three states the two components can be in means something specific,
 * and the third of them is what makes both of them optional rather than merely lenient. A card
 * number present narrows the run to that one card, which is the unit the control break at
 * {@code app/cbl/CBSTM03A.CBL} L69 already works in. An account identifier present names the
 * alternative scope, reached by resolving the account to its cards through the cross-reference
 * that {@code app/cbl/CBSTM03B.CBL} opens as its {@code XREF-FILE} at L65. Neither present means
 * every card the prepared input holds, which is exactly the whole-file behaviour of
 * {@code app/jcl/CREASTMT.JCL} L79 and therefore a state this record has to be able to express
 * rather than an incomplete request.
 *
 * <p>Assumptions: whether the two components may be supplied together is deliberately not settled
 * here. A rule relating one component to another decides how a run is composed, which is business
 * logic, and the charter at {@code com.carddemo.reporting.dto} closes this package to business
 * logic entirely; {@code com.carddemo.reporting.service} is where such a rule belongs and the only
 * place it may be enforced. What this record guarantees is narrower and unconditional: each
 * component, taken on its own, is either absent or a value of the shape its copybook field
 * declares.
 *
 * <h2>Two things this record deliberately does not carry</h2>
 *
 * <p>Alternatives Considered: a start-date and end-date pair, mirroring the {@code ReportRequest}
 * type in this same package, was evaluated and rejected. The contrast between the two jobs is the
 * whole of the reason, so it is named rather than summarised. The report path plainly injects a
 * range: {@code app/jcl/TRANREPT.jcl} declares {@code PARM-START-DATE,C'2022-01-01'} at L43 and
 * {@code PARM-END-DATE,C'2022-07-06'} at L44 as sort symbols under the symbol declaration at L40,
 * and {@code app/cbl/CBTRN03C.cbl} receives them over the {@code DATEPARM} data-definition
 * channel. The statement path injects nothing of the kind: across all 98 lines of
 * {@code app/jcl/CREASTMT.JCL} there is no parameter field, no date symbol and no such channel at
 * all. A range here would therefore let a caller ask for a statement bounded in a way the baseline
 * cannot produce, and a bound the baseline never applied changes observable output rather than
 * adding a convenience. Functional parity is what forbids it.
 *
 * <p>Alternatives Considered: a format component choosing between the plain-text artifact and the
 * hypertext one was evaluated and rejected, because a run produces both and offers no choice
 * between them. {@code app/jcl/CREASTMT.JCL} creates the plain-text dataset at L87 to L91 with
 * {@code DCB=(LRECL=80,...)} and the hypertext dataset at L92 to L96 with
 * {@code DCB=(LRECL=100,...)}, and the program agrees from the inside, declaring
 * {@code FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL} L45 and
 * {@code FD-HTMLFILE-REC PIC X(100)} at L46. A selector would let a caller request less than a run
 * produces, which narrows observable output rather than parameterising it.
 *
 * <p>Assumptions: one observation supports that second decision and is set down because it reads
 * as a conflict until it is followed through. The earlier stanza that clears the previous run's
 * output declares the hypertext dataset with {@code LRECL=80} at {@code app/jcl/CREASTMT.JCL} L69
 * rather than with the 100 the creating stanza uses. That stanza belongs to a deletion step
 * running {@code IEFBR14} at L66, which writes no data at all, so the record length attached to it
 * never describes any bytes; the authoritative hypertext width is the {@code LRECL=100} at L94,
 * corroborated by the program's own file description at {@code app/cbl/CBSTM03A.CBL} L46. This is
 * an observation about reference material that stays byte-identical, and nothing here proposes an
 * alteration to it.
 *
 * <h2>Shape, and where the contracts behind it come from</h2>
 *
 * <p>Assumptions: both components are strings of digits rather than numbers, on the strength of
 * the baseline declaring each identifier twice over the same bytes -- once as characters and once
 * as a number -- at {@code app/cpy/CVCRD01Y.cpy}: {@code CC-ACCT-ID PIC X(11)} at L34 with
 * {@code CC-ACCT-ID-N PIC 9(11)} redefining it at L36, {@code CC-CARD-NUM PIC X(16)} at L37 with
 * its numeric redefinition at L39, and {@code CC-CUST-ID PIC X(09)} at L40 with its numeric
 * redefinition at L42. The character declaration is what the wire carries and the numeric one
 * exists so that arithmetic can reach the same bytes, so a string is the representation that
 * survives the move: a numeric component would discard a leading zero the declared width
 * preserves, and a 16-digit card number does not fit a 32-bit integer at all. The charter at
 * {@code com.carddemo.reporting.dto} records the same reading for every type in this package, and
 * this record follows it rather than restating it.
 *
 * <p>Assumptions: this record declares no page envelope, no problem shape and no per-field error
 * carrier of its own, because {@code com.carddemo.common.web.PageResponse},
 * {@code com.carddemo.common.error.ApiError} and
 * {@code com.carddemo.common.validation.FieldValidationFlag} are consumed from the shared kernel
 * under transformation rule T2, which turns one former copybook inclusion into one import from the
 * single package owning the contract. It carries no monetary and no timestamp component either, so
 * it reaches nothing in the kernel's money or time packages; a statement request selects a scope
 * and reports no amounts. The written convention every block here follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, and any difference between this migration and the
 * baseline belongs to {@code docs/architecture/cobol-to-service-traceability.md}, which this
 * package cites and never extends.
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
     * {@code app/cpy/COSTM01.CPY} L22, the leading field of the 32-byte {@code TRNX-KEY} group
     * that L21 to L23 declare, and it is corroborated by {@code CC-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVCRD01Y.cpy} L37 and by the control-break field
     * {@code WS-SAVE-CARD PIC X(16)} at {@code app/cbl/CBSTM03A.CBL} L69. Three independent
     * declarations agree, so the width is a contract rather than a reading.
     *
     * <p>Alternatives Considered: writing the literal straight into the constraint annotation and
     * again into the constructor guard below was the obvious alternative and is rejected. It puts
     * one declared width in two executable positions -- the constraint a caller is published and
     * the guard a caller is held to -- which is what lets those two drift apart silently. Naming
     * it once means they cannot disagree, and it leaves exactly one line to compare against
     * {@code app/cpy/COSTM01.CPY} L22. The width is quoted in the prose of this file as well, in
     * the component descriptions and in the refusal contract, and that is documentation rather
     * than a second executable source: nothing reads those sentences to decide anything.
     */
    private static final int CARD_NUMBER_WIDTH = 16;

    /**
     * The number of positions the baseline declares for an account identifier.
     *
     * <p>Assumptions: 11 is read from {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy}
     * L5, the leading field of the 300-byte account record, and it is corroborated by
     * {@code CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy} L34 and by
     * {@code FD-ACCT-ID PIC 9(11)} at {@code app/cbl/CBSTM03B.CBL} L77, the key of the account
     * file the statement flow reads. The numeric picture at L5 and the character picture at L34
     * describe the same 11 positions, which is the overlay discipline recorded on this type.
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The expression a present component value has to match in full.
     *
     * <p>Assumptions: the character class is written out as an explicit range rather than as the
     * shorthand digit class, because a zoned-decimal field holds exactly the ten characters this
     * range names and nothing else, so the range states the contract instead of describing it
     * indirectly. That the values are digits at all is the overlay discipline at
     * {@code app/cpy/CVCRD01Y.cpy}, where {@code CC-CARD-NUM PIC X(16)} at L37 is redefined
     * numerically at L39. The shorthand would additionally be interpretation-dependent: it
     * broadens to every decimal digit in Unicode when Unicode character-class mode is turned on by
     * an embedded flag expression, so a value carrying a non-Latin digit would then satisfy a
     * constraint the copybook field cannot hold.
     *
     * <p>Assumptions: the quantifier requires at least one digit rather than allowing none,
     * because the constructor below already reports a value carrying no content as absent, so a
     * present value always has content by the time this expression is applied. Constraint
     * evaluation treats an absent value as satisfied, which is what keeps an unsupplied component
     * from being reported as malformed.
     */
    private static final String DIGITS_ONLY = "[0-9]+";

    /**
     * Normalises both components onto this record's absence contract, then refuses a value no
     * declared field could carry.
     *
     * <p>Assumptions: normalisation belongs at construction rather than at each accessor, so the
     * stored state and the returned state are the same state and no caller has to know which of
     * the two it holds. An instance cannot come into being without passing through here, which is
     * what makes the contract hold by construction rather than by every caller remembering it.
     *
     * <p>Alternatives Considered: leaving the width to the constraint annotations alone was
     * evaluated and rejected. Those annotations are evaluated only when something asks a validator
     * to evaluate them, which the transport boundary does and a caller constructing this record
     * directly does not, so the width would hold for one caller and not for another. The guard is
     * kept because a value longer than its field is a question of representability rather than of
     * content: a 17-character card number is not a card number this system can carry at all, since
     * {@code app/cpy/COSTM01.CPY} L22 gives it 16 positions, so the record refuses to exist in
     * that state.
     *
     * <p>Alternatives Considered: extending that guard to cover the digits-only shape as well was
     * evaluated and deliberately rejected, and the asymmetry is the point. A value of legal length
     * carrying something other than a digit fits its field perfectly -- an {@code X(16)} picture
     * holds any 16 characters -- and what rejects it is the numeric overlay discipline that
     * {@code app/cpy/CVCRD01Y.cpy} L37 and L39 declare, which is a rule about content. A caller
     * has to be told which component was wrong and why, so that shape is asserted by the
     * constraint annotation above and surfaces as a per-field entry through
     * {@code com.carddemo.common.error.ApiError}. Raising here instead would collapse it into a
     * body that could not be read, and a rejection that cannot name its field is the one outcome
     * the charter at {@code com.carddemo.reporting.dto} rules out.
     *
     * @param cardNumber the card number as it arrived, which may be {@code null}, may carry
     *     trailing padding and may be entirely padding; it is stored with the padding removed, or
     *     as {@code null} when it carries no content
     * @param accountId the account identifier as it arrived, on the same three terms as the card
     *     number above; it is stored with the padding removed, or as {@code null} when it carries
     *     no content
     * @throws IllegalArgumentException if either component, once its trailing padding has been
     *     removed, is longer than the number of positions its copybook field declares, namely 16
     *     for the card number and 11 for the account identifier; the length and the width are
     *     reported and the value itself never is
     */
    public StatementRequest {
        // Assumptions: the two components are normalised and guarded independently and in the
        //   order the header declares them, because neither one's treatment depends on the other.
        //   A rule relating them would be a scope rule, and this record holds none, so nothing
        //   here inspects one component while handling the other.
        cardNumber = requireDeclaredWidth(normalise(cardNumber), CARD_NUMBER_WIDTH, "cardNumber");
        accountId = requireDeclaredWidth(normalise(accountId), ACCOUNT_ID_WIDTH, "accountId");
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

    /**
     * Returns a normalised value that its declared field can carry, and refuses one that it
     * cannot.
     *
     * <p>Assumptions: the refusal reports the component name, the length that arrived and the
     * width that was available, and never the value. One of the two components is a primary account
     * number of the 16 positions {@code app/cpy/COSTM01.CPY} L22 declares, and this context masks
     * that value to its last four digits everywhere it is rendered, so reproducing a whole one
     * inside an exception message would put it into a log by the shortest available route. The
     * three facts reported are what identifies the offending component, which is what a caller
     * needs in order to act.
     *
     * @param value the normalised component value, or {@code null} for a component that carries
     *     no content and therefore has no width to check
     * @param declaredWidth the number of positions the copybook field behind this component
     *     declares
     * @param component the component name as the request body spells it, reproduced in the refusal
     *     so that the rejection names the field it concerns
     * @return {@code value} unchanged, so that a guarded assignment reads as one expression rather
     *     than as a check followed by a store
     * @throws IllegalArgumentException if {@code value} is present and longer than
     *     {@code declaredWidth}, because a value that overruns its field cannot be carried at any
     *     point further along this system either
     */
    private static String requireDeclaredWidth(String value, int declaredWidth, String component) {
        if (value != null && value.length() > declaredWidth) {
            throw new IllegalArgumentException(
                    component + " arrived " + value.length() + " characters long once padding was "
                            + "removed, and the field carrying it declares " + declaredWidth
                            + " positions");
        }

        return value;
    }
}
