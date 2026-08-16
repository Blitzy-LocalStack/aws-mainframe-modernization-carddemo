package com.carddemo.reference.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Locale;

/**
 * The verdict on one candidate date, satisfying the contract schema {@code DateEvaluationResult}.
 *
 * <p>Purpose: the outbound shape of the date evaluation, carried by the synchronous read at
 * {@code GET /api/v1/reference/date-evaluations}. Its members are the variable fields of the
 * eighty-character result the baseline date utility hands back, which
 * {@code app/cbl/CSUTLDTC.cbl} declares at its lines 42 to 57 and returns through
 * {@code 01 LS-RESULT PIC X(80)} at its line 86. Nothing on this shape reads a datastore, decides a
 * rule or holds state between requests: the rules live in
 * {@code com.carddemo.common.validation.DateEditValidator}, the read reaches them through
 * {@code com.carddemo.reference.service.DateConversionService}, and this type states only what a
 * caller receives.</p>
 *
 * <p>Refactoring Rationale: this paragraph said the shape was carried "by the queue route beside it"
 * as well, and named a listener type as the path both routes took to the rules. The queue route
 * answers with a fixed-width forty-six-character body rendered by
 * {@code com.carddemo.common.codec.DateInquiryReplyCodec} and has never carried this shape. The claim
 * is corrected rather than dropped so that a reader does not go looking for a queue consumer that
 * serialises this record. Assumptions: that route is not implemented in this module at all -- the ONE
 * shared inquiry request queue is owned by {@code com.carddemo.account.service.InquiryMessageListener},
 * which dispatches on the request's function code -- so this record is the ONLY wire form of a date
 * answer this module publishes.</p>
 *
 * <h2>Why the outcome travels as two independent members</h2>
 *
 * <p>Assumptions: the severity and the message number are two separate members and are never
 * collapsed into one, because two baseline callers accept a REJECTED evaluation when the message
 * number is the unsupported-range one. Each of them compares the severity against the
 * four-character {@code '0000'} first and consults the message number only when that comparison
 * fails, at {@code app/cbl/COTRN02C.cbl} lines 397 to 406 and again at its lines 417 to 426, and at
 * {@code app/cbl/CORPT00C.cbl} lines 396 to 405 and again at its lines 416 to 425. The number those
 * four sites forgive is {@code '2513'}, the decimal reading of the message halfword of the feedback
 * condition declared at {@code app/cbl/CSUTLDTC.cbl} line 66, whose verdict wording that program
 * selects at its line 138. This is the single strongest constraint on the member set. One acceptance
 * flag here would answer one question where those callers ask two, and a caller reproducing them
 * could no longer tell a rejection it must honour from the one rejection it forgives. The shared
 * validator publishes the same distinction as its own {@code unsupportedRange()} predicate, so the
 * tolerance remains a caller's decision on both sides of the wire.</p>
 *
 * <p>Assumptions: that tolerance is NOT universal across the baseline, which is a second and
 * independent reason the two members stay apart. The shared date-edit driver reads the same result
 * record through its numeric view and tests {@code IF WS-SEVERITY-N = 0} at
 * {@code app/cpy/CSUTLDPY.cpy} line 298, granting no exception for any message number whatever, so
 * the shared driver is STRICTER than the two screen programs. Both behaviours are live in the
 * reference at once. Exposing the severity and the message number separately is what lets each
 * caller apply the rule its own path applies, and the two rules are deliberately not unified
 * here.</p>
 *
 * <p>Alternatives Considered: an acceptance flag, and separately a single composite status member
 * spelling the severity and the number into one token, were both evaluated and rejected. The flag
 * discards the message number outright, which makes the tolerance above unreproducible. The
 * composite token keeps the information but obliges every caller to parse a status string by offset
 * to recover the halves the reference compares independently, which is the positional addressing
 * this migration is moving away from. Two members cost one extra property and leave both rules
 * expressible without parsing.</p>
 *
 * <h2>Two names that read backwards, and both are load-bearing</h2>
 *
 * <p>Assumptions: the feedback condition named for an invalid date is the ACCEPTING outcome, and the
 * name is carried across as it stands. {@code app/cbl/CSUTLDTC.cbl} declares
 * {@code 88 FC-INVALID-DATE VALUE X'0000000000000000'} at its line 62 -- the all-zero feedback the
 * platform date service reports for a usable date -- and selects the wording
 * {@code 'Date is valid'} for it at its lines 129 and 130. A reader who trusts that condition name
 * inverts the verdict. Branch on the severity, which is zero for this outcome, or on the verdict
 * wording; never read acceptance off the name. The contract schema {@code DateFeedbackCode} records
 * the same hazard against the same value, and the shared validator records it against its own
 * outcome type, so one vocabulary serves the reference, the validator and the wire.</p>
 *
 * <p>Assumptions: the reference's per-field markers read backwards in the same way, and consumers
 * must ask a predicate rather than compare a value. {@code app/cpy/CSUTLDWY.cpy} declares the year,
 * month and day markers at its lines 46 to 57, and in each the acceptable state is the LOW-VALUE
 * byte while the unacceptable state is the digit zero; the group aggregate above them at its lines
 * 44 and 45 is spelled the same way round. Never-supplied, the letter B, is a REASON WITHIN error
 * rather than a third state beside it: the presentation template tests the unacceptable and
 * never-supplied conditions as one disjunction at {@code app/cpy/CSSETATY.cpy} lines 18 and 19.
 * Those markers map onto {@code com.carddemo.common.validation.FieldValidationFlag}, whose
 * {@code isError()} predicate answers that one question for both error states. No member of this
 * shape, and no line of it, compares against a marker constant.</p>
 *
 * <h2>The codes are four characters wide in the reference, and nothing here discards that</h2>
 *
 * <p>Assumptions: the severity and the message number are one X-over-9 pair each, so the reference
 * holds BOTH a numeric form and a four-character display form of every value.
 * {@code app/cbl/CSUTLDTC.cbl} receives them as the halfwords {@code 04 SEVERITY PIC S9(4) BINARY}
 * and {@code 04 MSG-NO PIC S9(4) BINARY} at its lines 72 and 73, moves each into a numeric
 * redefinition at its lines 123 and 124, and thereby renders it into the character fields
 * {@code WS-SEVERITY PIC X(04)} and {@code WS-MSG-NO PIC X(04)} that
 * {@code app/cpy/CSUTLDWY.cpy} declares at its lines 61 and 66 over the numeric views at its lines
 * 62 and 67. The pattern recurs across the reference wherever a value is compared as characters and
 * computed as a number, most visibly over the three identifiers at {@code app/cpy/CVCRD01Y.cpy}
 * lines 34 to 42. Both forms are therefore faithful, and the display form is the one the screen
 * programs compare, so it is reachable here through {@link #severityCode()} and
 * {@link #messageNumberCode()} and is never zero-stripped by either.</p>
 *
 * <p>Alternatives Considered: declaring the two members themselves as four-character digit-validated
 * strings was evaluated and rejected, and the reasoning is recorded because the reference supports
 * either form. The published contract
 * {@code services/reference-service/src/main/resources/openapi/reference-api.yaml} declares both as
 * integers, declares the schema with {@code additionalProperties: false}, and is authoritative for
 * every member of every shape in this package by the charter beside this file; the browser client
 * {@code ui/src/api/reference.ts} and this module's own contract test are written against that
 * declaration. Publishing the numeric form and deriving the padded form keeps one wire shape while
 * losing neither reading. Trade-offs: a caller that needs the reference's own comparand calls a
 * derived accessor rather than reading a member, which is the accepted cost of not having two
 * spellings of one value on the wire at once.</p>
 *
 * <h2>What the eighty-character record contributes, and what it does not</h2>
 *
 * <p>Refactoring Rationale: the label constants interleaved through that record are dropped, per
 * migration rule T1, and the drop is recorded here rather than left to be noticed.
 * {@code app/cpy/CSUTLDWY.cpy} declares {@code FILLER PIC X(11) VALUE 'Mesg Code:'} at its lines 64
 * and 65, {@code FILLER PIC X(09) VALUE 'TstDate:'} at its lines 74 and 75 and
 * {@code FILLER PIC X(10) VALUE 'Mask used:'} at its lines 79 and 80, along with the single-character
 * spacing fillers at its lines 69, 72, 77 and 82 and the trailing one at its line 84. Every one of
 * them is presentation for a record read by eye on a console, where a value has no name unless a
 * label sits beside it. In JSON the member names carry that role, so a label member would put the
 * same text on the wire twice.</p>
 *
 * <p>Alternatives Considered: the reference offers a third, flattened view of those same eighty
 * bytes, and this shape mirrors the structured one instead. The two screen programs declare the
 * result as a severity, an eleven-character filler, a message number and then
 * {@code 10 CSUTLDTC-RESULT-MSG PIC X(61)} -- the whole tail in one field -- at
 * {@code app/cbl/COTRN02C.cbl} lines 65 to 69 and at {@code app/cbl/CORPT00C.cbl} lines 132 to 136.
 * Mirroring that view would hand the client a sixty-one-character string and require it to recover
 * the verdict, the echoed date and the echoed mask by offset, reintroducing exactly the positional
 * parsing the structured declaration at {@code app/cpy/CSUTLDWY.cpy} lines 60 to 85 already
 * resolves. The structured view is the same eighty bytes read the way the reference itself reads
 * them when it has a choice.</p>
 *
 * <p>Refactoring Rationale: no member carries a converted date, and no member is twenty characters
 * wide. The conversion record {@code app/cpy/CODATECN.cpy} declares
 * {@code 10 CODATECN-0UT-DATE PIC X(20)} at its line 39 -- spelled there with a digit in place of
 * the letter, which is not propagated -- and slices it two ways through the redefinitions at its
 * lines 40 and 47, whose trailing {@code 15 CODATECN-1OFIl PIC X(10)} and
 * {@code 15 CODATECN-2OFIl PIC X(12)} at its lines 46 and 51 exist so either layout occupies one
 * record of a set length; that padding is dropped per rule T1. The operation this shape answers
 * performs no conversion at all: {@code DateConversionRequest} carries the candidate and the mask
 * and declares no output selection, the published contract declares none either, and its
 * {@code additionalProperties: false} would make an added member invalid. That record is cited here
 * as the authority for a layout, not as a path being carried forward -- the one program copying it,
 * {@code app/cbl/CBACT01C.cbl}, hands the whole record to an assembler routine, and the assembler
 * modules retire with no cloud analogue.</p>
 *
 * <h2>Wording is carried across exactly as it stands</h2>
 *
 * <p>Refactoring Rationale: the verdict wordings are reproduced character for character per
 * migration rule T8, including their internal inconsistencies, and none is normalised. Those the
 * reference selects at {@code app/cbl/CSUTLDTC.cbl} lines 130 to 148 are {@code 'Date is valid'},
 * {@code 'Insufficient'}, {@code 'Datevalue error'}, {@code 'Invalid Era    '},
 * {@code 'Unsupp. Range  '}, {@code 'Invalid month  '}, {@code 'Bad Pic String '},
 * {@code 'Nonnumeric data'}, {@code 'YearInEra is 0 '} and {@code 'Date is invalid'}. They disagree
 * among themselves about spacing and case -- one runs two words together, one abbreviates and pads,
 * one is in camel form -- and harmonising them would change text a caller may already be matching
 * on. The wording arrives here already selected by
 * {@code com.carddemo.common.validation.DateEditValidator}, which holds each one, so this shape
 * neither re-derives nor rewrites any of them.</p>
 *
 * <p>Refactoring Rationale: the same holds for the per-field message fragments the date-edit driver
 * composes, which are equally uneven and equally untouched. Among them
 * {@code app/cpy/CSUTLDPY.cpy} carries {@code ' : Year must be supplied.'} at its line 37 with a
 * leading space and spaces around the colon, {@code ' must be 4 digit number.'} at its line 54 with
 * no colon at all, {@code ': Month must be a number between 1 and 12.'} at its line 119 with a
 * capital in the field word, {@code ':day must be a number between 1 and 31.'} at its line 180 with
 * a bare colon and a lower-case one, {@code ':Not a leap year.Cannot have 29 days in this month.'}
 * at its line 266 whose two sentences run together with no space between them, and
 * {@code ':cannot be in the future '} at its line 363 with a trailing space. Each is published
 * verbatim by the shared validator. The placeholder wording {@code 'Looks Good.... so far'} that
 * several screen programs carry is a development leftover rather than a verdict, and it is not
 * carried across.</p>
 *
 * <h2>Per-field verdicts, and the one divergence in how they are reported</h2>
 *
 * <p>Refactoring Rationale: the reference composes each fragment onto a field name and keeps only
 * the FIRST such message. Every composition in {@code app/cpy/CSUTLDPY.cpy} is gated on the
 * return-message-off sentinel -- at its lines 34, 51, 76, 98, 116, 133, 158, 177, 192, 218, 233,
 * 263, 305 and 360 -- writing into one message field of the width declared at
 * {@code app/cpy/CVCRD01Y.cpy} lines 28 and 29, whose off-state that copybook declares at its line
 * 30, so a later field in error composes nothing once an earlier one has spoken. The migrated
 * services report EVERY field verdict, as a per-field array on
 * {@code com.carddemo.common.error.ApiError} whose entries carry a
 * {@code com.carddemo.common.validation.FieldValidationFlag} state, per migration rule T7. The
 * baseline retains the first composed message and drops the rest; the Java reports each field that
 * is in error; the divergence is documented in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Refactoring Rationale: that array separates the field name from the message wording, where the
 * reference joins them. Each composition applies {@code FUNCTION TRIM} to the field-name variable
 * and concatenates a fragment onto it, so the identity of the offending field survives only inside
 * a sentence. Separating the two is what makes the field identity machine-readable, which is what
 * lets a client attach a message to the control that produced it instead of displaying one line
 * above the form.</p>
 *
 * <p>Assumptions: one check can put several fields in error at once, so that array is keyed by field
 * identity rather than by condition. {@code app/cpy/CSUTLDPY.cpy} sets the day, the month AND the
 * year markers from a single condition in three places -- the leap-year branch at its lines 259 to
 * 262, the platform-validation branch at its lines 301 to 304 and the future-date branch at its
 * lines 356 to 359. A consumer must therefore expect three entries originating in one check, and
 * must not infer that three entries mean three independent failures.</p>
 *
 * <p>Refactoring Rationale: no member of this shape carries that array, and the omission is
 * deliberate rather than pending. The published contract declares this schema with six required
 * members and {@code additionalProperties: false}, and the charter beside this file settles that a
 * validation failure travels on {@code com.carddemo.common.error.ApiError} instead. A per-field
 * member here would duplicate that channel and give a caller two places to look for one answer.</p>
 *
 * <h2>Two boundary facts that add no member but are easy to invert</h2>
 *
 * <p>Assumptions: the reference's future-date boundary is INCLUSIVE, so a date equal to the current
 * date is rejected. {@code app/cpy/CSUTLDPY.cpy} reads
 * {@code IF WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY} at its line 350 and takes the error path
 * otherwise, so the error fires when the current date is less than OR EQUAL TO the date under
 * examination. A reader assuming an exclusive boundary reverses the verdict for exactly one day.
 * That program reads the current date from the wall clock at its line 343; the migrated services take
 * the business date from an injected clock instead, so a verdict is reproducible on a rerun, which
 * is the discipline the reference already applies wherever it passes a date in as a parameter.</p>
 *
 * <p>Assumptions: the leap-year arithmetic at {@code app/cpy/CSUTLDPY.cpy} lines 243 to 271 agrees
 * with the Gregorian rule -- it divides a century year by four hundred and any other year by four,
 * choosing the divisor at its line 245 -- so there is nothing to record against it. That is stated
 * rather than passed over in silence, because an unexplained absence beside the notes above invites
 * a later reader to re-derive the arithmetic to find out whether it was examined.</p>
 *
 * <h2>An unusable date is a successful answer, not a rejected request</h2>
 *
 * <p>Alternatives Considered: answering a client-error status when the candidate date turns out to
 * be unusable was evaluated and rejected. A caller asking whether a value is a usable date under a
 * given picture has asked a well-formed question, and this shape IS the answer to it; the verdict
 * members carry the outcome. A client-error status would conflate a malformed request with a sound
 * request about an unusable date, and -- because the body of an error response is the problem shape
 * rather than this one -- it would put the message number out of the caller's reach, which destroys
 * the tolerance above a second way, by a different mechanism than an acceptance flag would.</p>
 *
 * <p>Assumptions: genuine request-shape faults do still answer a client-error status, and the
 * boundary between the two channels is exactly this. A candidate that is absent, or outside the
 * width bounds {@code DateConversionRequest} declares, or a picture the deserialiser cannot bind, is
 * refused before any evaluation runs and travels as
 * {@code com.carddemo.common.error.ApiError} with its per-field entries, raised by
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, which
 * {@code com.carddemo.reference.ReferenceApplication} already imports as this module's single
 * advice; this shape neither declares nor references another. Anything the evaluation itself reaches
 * a verdict on -- including an unusable picture, which the reference reports with its own wording
 * rather than as a fault -- travels here. That problem shape bounds its sentence at the
 * seventy-five characters of the message fields at {@code app/cpy/CVCRD01Y.cpy} lines 28 and 29,
 * published as its own rendering-width constant, and not at the width of the eighty-character
 * result record these members come from.</p>
 *
 * <p>Refactoring Rationale: the conversion record's own error text is not carried as a member
 * either. {@code app/cpy/CODATECN.cpy} declares {@code 05 CODATECN-ERROR-MSG PIC X(38)} at its line
 * 52, sized to fit the remainder of that record rather than to bound a message, and error text in
 * the migrated services travels on the problem shape named above at the width recorded there. A
 * member of that width here would publish a third message bound, narrower than the one the migrated
 * services honour, for a channel this shape does not carry.</p>
 *
 * @param feedbackCode the outcome, named as the reference names it and enumerated by the contract
 *     schema {@code DateFeedbackCode}; read the severity or the verdict wording rather than this
 *     name, because the value denoting acceptance is the one whose name reads as its opposite
 * @param severity the severity as reported: zero when the candidate was accepted, and non-zero when
 *     it was not. The reference compares this value in its four-character display form against
 *     {@link #ACCEPTED_SEVERITY_CODE}, which {@link #severityCode()} reproduces. A non-zero severity
 *     is NOT necessarily a rejection the caller must honour: two screen programs accept the
 *     evaluation anyway when {@code messageNumber} is the tolerated one, while the shared date-edit
 *     driver forgives nothing, so the two members are read together and by whichever rule the
 *     caller's own path applies
 * @param messageNumber the message number as reported, zero when the candidate was accepted. This is
 *     the member the tolerance turns on: the value {@link #TOLERATED_MESSAGE_NUMBER}, whose
 *     four-character form {@link #TOLERATED_MESSAGE_NUMBER_CODE} is what those programs compare
 *     against, identifies the one rejection they accept in spite of a non-zero severity. It pairs
 *     with {@code feedbackCode}, so the two never disagree
 * @param verdict the verdict wording for this outcome, reproduced character for character from the
 *     reference and bounded by {@link #VERDICT_WIDTH}; deliberately not a closed set of values,
 *     because the reference selects a wording for feedback matching none of its named conditions
 * @param date the candidate exactly as submitted, echoed back so that a verdict stays interpretable
 *     once it has been logged away from the request that produced it
 * @param mask the picture the evaluation actually used, which is the submitted one or the default
 *     applied in its absence, echoed so that a caller which omitted it learns which one applied
 */
public record DateConversionResponse(
        String feedbackCode,
        int severity,
        int messageNumber,
        String verdict,
        String date,
        String mask) {

    /**
     * The declared width of both code fields in the reference, four characters.
     *
     * <p>Assumptions: this is the width of {@code WS-SEVERITY PIC X(04)} and
     * {@code WS-MSG-NO PIC X(04)} at {@code app/cpy/CSUTLDWY.cpy} lines 61 and 66, and of the
     * flattened views the two screen programs declare at {@code app/cbl/COTRN02C.cbl} lines 66 and 68
     * and at {@code app/cbl/CORPT00C.cbl} lines 133 and 135. It is published because the two
     * comparands below are only meaningful at this width, and a reader who finds them as bare
     * literals cannot tell which of the reference's several code widths they belong to.</p>
     */
    public static final int BASELINE_CODE_WIDTH = 4;

    /**
     * The severity the reference compares against to conclude that a candidate was accepted.
     *
     * <p>Assumptions: the two screen programs compare the CHARACTER form of the severity against this
     * value, at {@code app/cbl/COTRN02C.cbl} lines 397 and 417 and at {@code app/cbl/CORPT00C.cbl}
     * lines 396 and 416, so acceptance is a four-character comparison there rather than a numeric one.
     * Publishing the padded comparand keeps that reading available to a caller reproducing those
     * programs, which a numeric zero on its own would not: rendered without its padding the value is
     * one character wide and no longer equal to what those four sites test.</p>
     */
    public static final String ACCEPTED_SEVERITY_CODE = "0000";

    /**
     * The message number identifying the one rejection two screen programs accept regardless.
     *
     * <p>Assumptions: this is the decimal reading of the message halfword of the feedback condition
     * declared at {@code app/cbl/CSUTLDTC.cbl} line 66, the condition whose verdict wording that
     * program selects at its line 138. The tolerance itself is set out on this type; the value is
     * published so that a caller applying it names the number rather than embedding it, and so that a
     * reader of such a comparison can find the reasoning from the constant.</p>
     */
    public static final int TOLERATED_MESSAGE_NUMBER = 2513;

    /**
     * That same tolerated message number in the four-character form the reference compares.
     *
     * <p>Assumptions: {@code app/cbl/COTRN02C.cbl} at its lines 400 and 420 and
     * {@code app/cbl/CORPT00C.cbl} at its lines 399 and 419 compare the character form, not the
     * number, so both spellings are published and neither is derived at a call site. Trade-offs: two
     * constants for one value is accepted because a caller working numerically and a caller
     * reproducing the reference's own comparison would otherwise each convert it, and a conversion
     * written twice is a conversion that can disagree with itself.</p>
     */
    public static final String TOLERATED_MESSAGE_NUMBER_CODE = "2513";

    /**
     * The declared width of the verdict field in the reference, fifteen characters.
     *
     * <p>Assumptions: this is the width of {@code WS-RESULT PIC X(15)} at
     * {@code app/cpy/CSUTLDWY.cpy} line 71, which {@code app/cbl/CSUTLDTC.cbl} notes for itself in
     * the comment above its selection at its lines 126 and 127, and it is the bound the published
     * contract declares for the member. The longest wordings occupy it exactly, so it is the width a
     * caller allots when rendering the verdict in a column beside other verdicts.</p>
     */
    public static final int VERDICT_WIDTH = 15;

    /**
     * The rendering pattern that restores a code to the reference's declared width.
     *
     * <p>Assumptions: the pattern pads on the left with zeros to {@link #BASELINE_CODE_WIDTH} and is
     * held here rather than composed at each use, so the two derived accessors below cannot drift
     * apart in how they pad.</p>
     */
    private static final String CODE_PATTERN = "%0" + BASELINE_CODE_WIDTH + "d";

    /**
     * Renders the severity in the four-character form the reference compares against.
     *
     * <p>Purpose: reproduces the display side of the severity's X-over-9 pair, so that a caller
     * reproducing the two screen programs can compare against {@link #ACCEPTED_SEVERITY_CODE}
     * exactly as they do. The reasoning behind carrying both readings, rather than choosing one, is
     * set out on this type.</p>
     *
     * <p>Assumptions: this is a derived reading and never a second wire member. It is withheld from
     * serialisation because the published contract declares this schema with
     * {@code additionalProperties: false}, so a property here beyond the six declared members would
     * make every response body invalid against the document the client validates against.</p>
     *
     * @return the severity padded on the left with zeros to {@link #BASELINE_CODE_WIDTH}, so an
     *     accepted candidate yields {@link #ACCEPTED_SEVERITY_CODE}; never {@code null}, and wider
     *     than that width only for a value that does not fit it
     */
    @JsonIgnore
    public String severityCode() {
        return padToBaselineWidth(this.severity);
    }

    /**
     * Renders the message number in the four-character form the reference compares against.
     *
     * <p>Purpose: reproduces the display side of the message number's X-over-9 pair, so that the
     * comparison the two screen programs make against {@link #TOLERATED_MESSAGE_NUMBER_CODE} can be
     * made here in the same terms. A caller working numerically compares {@link #messageNumber()}
     * against {@link #TOLERATED_MESSAGE_NUMBER} instead, and the two readings agree by
     * construction.</p>
     *
     * <p>Assumptions: withheld from serialisation for the same reason as its companion above, the
     * closed member set the published contract declares for this schema.</p>
     *
     * @return the message number padded on the left with zeros to {@link #BASELINE_CODE_WIDTH}, so
     *     the tolerated rejection yields {@link #TOLERATED_MESSAGE_NUMBER_CODE} and an accepted
     *     candidate yields {@link #ACCEPTED_SEVERITY_CODE}; never {@code null}
     */
    @JsonIgnore
    public String messageNumberCode() {
        return padToBaselineWidth(this.messageNumber);
    }

    /**
     * Pads one reported code to the width the reference declares for it.
     *
     * <p>Assumptions: the rendering is bound to {@link Locale#ROOT} rather than to the ambient
     * locale. A decimal conversion follows the locale's digit set, so under a locale whose digits are
     * not the Latin ones the ambient rendering would produce characters that no longer equal the
     * comparands published above -- a verdict that changed with the deployment's locale rather than
     * with the date under examination.</p>
     *
     * <p>Trade-offs: a value too wide for the declared width is rendered in full rather than
     * truncated to it, so the answer can exceed {@link #BASELINE_CODE_WIDTH}. Truncating would report
     * a code the evaluation never produced, which is worse than returning one a reader can see is
     * unexpected; the reported values are the severity ladder and the message numbers the shared
     * validator publishes, all of which fit. A negative value is likewise rendered as reported,
     * because the reference declares both halfwords as signed and inventing a floor here would
     * conceal a value that ought to be investigated.</p>
     *
     * @param code the value as reported by the evaluation
     * @return the value padded on the left with zeros to {@link #BASELINE_CODE_WIDTH}, or rendered in
     *     full when it does not fit that width; never {@code null}
     */
    private static String padToBaselineWidth(int code) {
        // WHY : Assumptions: the root locale is named explicitly rather than left to the ambient one,
        //       because a decimal conversion follows the locale's digit set. Under a locale requesting
        //       a non-Latin digit set the ambient rendering would no longer equal
        //       ACCEPTED_SEVERITY_CODE or TOLERATED_MESSAGE_NUMBER_CODE, so a comparison reproducing
        //       the reference would answer differently depending on where the service happened to run
        //       rather than on the date being examined.
        return String.format(Locale.ROOT, CODE_PATTERN, code);
    }
}
