package com.carddemo.common.error;

import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.FieldValidationFlag;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The one problem shape every migrated CardDemo service returns for a failed request.
 *
 * <p>This is the migrated form of the two message channels the reference baseline maintains side by
 * side. The aggregate sentence a user reads is {@code 10 CCARD-ERROR-MSG PIC X(75).} at line 28 of
 * {@code app/cpy/CVCRD01Y.cpy}; the per-field marking a form renders is the {@code FLG-*-NOT-OK}
 * and {@code FLG-*-BLANK} condition pair read by the templated highlight copybook at lines 17 to 27
 * of {@code app/cpy/CSSETATY.cpy}. Transformation rule T7 turns the second into the structured array
 * carried by {@link #fieldErrors()}, whose element type is the nested {@link FieldError}.
 *
 * <h2>The two channels accumulate differently, and that is the whole design</h2>
 *
 * <p>Refactoring Rationale: AE-02 keeps the aggregate message and the per-field array as separate
 * members
 * because the baseline fills them under different rules, and a single collection would lose one of
 * the two. In {@code app/cpy/CSUTLDPY.cpy} the aggregate text is written inside an
 * {@code IF WS-RETURN-MSG-OFF} test at four separate sites -- lines 218, 233, 263 and 305 -- and
 * every one of the four wraps a {@code STRING ... INTO WS-RETURN-MSG}, so the sentence latches to
 * the FIRST failure and later failures leave it alone. That test reads the condition
 * {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES} declared at line 30 of
 * {@code app/cpy/CVCRD01Y.cpy}. The per-field markers beside it are set UNCONDITIONALLY at every one
 * of those sites, so they accumulate. One latched sentence, many accumulated entries: deriving the
 * sentence by joining the array would report the last failure or all of them where the baseline
 * reports the first, and assuming the array holds one entry would discard every failure after the
 * first. {@link #latchMessage(String)} and {@link #withFieldError(FieldError)} are the two
 * accumulation rules expressed as code rather than left as prose.
 *
 * <h2>The machine code and the sentence are different members</h2>
 *
 * <p>Refactoring Rationale: AE-09 keeps {@link #code()} and {@link #secondaryCode()} out of
 * {@link #message()} because the baseline's own structured error record keeps them out. At
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy} the two nine-character code fields
 * {@code ERR-CODE-1} and {@code ERR-CODE-2} are declared at lines 37 and 38, separately from
 * {@code ERR-MESSAGE PIC X(50)} at line 39. Two other baseline sites do the opposite and are the
 * pattern deliberately not carried across: lines 544 to 549 of the authorization extension's
 * {@code COPAUS1C.cbl} assemble a return code into the sentence a user reads, and lines 306 to 313
 * of {@code app/cpy/CSUTLDPY.cpy} concatenate {@code ' validation error Sev code: '} and
 * {@code ' Message code: '} around two machine values. One string cannot serve an operator who needs
 * a stable token to search for and a user who needs a sentence that can be reworded without
 * anything breaking. The baseline does both; this record separates them; the divergence is
 * documented.
 *
 * <h2>Nothing here is derived from a caught exception</h2>
 *
 * <p>Assumptions: every member is supplied by the code that decided to fail, never scraped from the
 * text or the class of a caught throwable, so there is no path by which a credential, a connection
 * string, a rendered stack trace or a database vendor error code reaches a response body.
 * Diagnostic detail belongs to the operational record instead, keyed by the same
 * {@link #correlationId()} this shape carries, which is what makes it possible to answer a client
 * with one short sentence and still investigate the failure afterwards.
 *
 * <h2>No monetary component, and why that is a decision rather than an omission</h2>
 *
 * <p>Assumptions: AE-10 means this record carries no money-bearing component at all. None of the
 * three baseline
 * error surfaces has one -- the eleven fields of {@code ERROR-LOG-RECORD} at
 * {@code CCPAUERY.cpy} lines 19 to 40, the four components of {@code 01 ABEND-DATA} at
 * {@code app/cpy/CSMSG02Y.cpy} lines 21 to 29, and the two seventy-five-character message lines at
 * {@code app/cpy/CVCRD01Y.cpy} lines 28 and 29 -- so an amount on a problem shape would be an
 * invention rather than a migration. A rejected amount is reported as the text of the field entry
 * that rejected it, where it is already a string.
 *
 * <p>Trade-offs: AE-10 accepts that a client wanting the offending amount as a number
 * must read it from the request it sent rather than from the failure it received. That is accepted
 * because the alternative carries a silent hazard. Money in this system is exact fixed point at
 * every hop, which is why the baseline declares it as zoned decimal with a sign overpunch --
 * {@code 05 ACCT-CURR-BAL PIC S9(10)V99.} at line 7 of {@code app/cpy/CVACT01Y.cpy} -- and why the
 * migrated wire form is a JSON string: a JSON number is parsed into an IEEE-754 binary64 value by
 * most clients, which destroys exactness at the boundary the user actually sees. Reaching that
 * string form depends on a component being typed {@code com.carddemo.common.money.Money}, because
 * {@code com.carddemo.common.money.MoneyModule} registers its serialiser against that class and
 * nothing else, so a component typed as a plain arbitrary-precision decimal would emit a bare JSON
 * number and still compile and still run. Carrying no monetary component removes that failure mode
 * from this record entirely rather than relying on every future editor to remember it. The
 * type-level half of the same prohibition is enforced for the whole module by the architecture test
 * at {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}.
 *
 * <h2>Why a record</h2>
 *
 * <p>Alternatives Considered: AE-01 evaluated and rejected an ordinary class with
 * library-generated accessors, specifically Lombok. A generated accessor has no source of its own on
 * which documentation can be written at all, so a Lombok-built form of this type could not carry a
 * per-member contract for the eleven components that cross a wire, and no exemption from the
 * repository's documentation audit exists to hide that behind. A Java record reaches the same
 * brevity by a route that keeps every member documentable,
 * and the eleven {@code @param} tags below are enforced by
 * {@code JavadocType allowMissingParamTags="false"} rather than merely conventional.
 *
 * <p>Alternatives Considered: the framework's own problem-detail type, and a service-local error
 * shape per bounded context. The first was rejected because it models neither the per-field array
 * nor the abend aggregate, so both would travel as loosely-typed extension properties that no
 * compiler checks; the second was rejected for the reason the baseline settles the same question --
 * every record layout resolves through one copybook include path, which is what
 * {@code cobc -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy} expresses, and transformation rule
 * T2 carries that discipline into Java -- so eight service-local problem shapes would drift until
 * the day a client parses seven services' failures correctly and mishandles the eighth.
 *
 * @param code the stable machine-readable identifier for the failure, matched on by clients and by
 *     operational tooling and never reworded; the migrated counterpart of {@code ERR-CODE-1} at
 *     line 37 of {@code CCPAUERY.cpy}, and never {@code null} or blank once constructed
 * @param secondaryCode the subordinate machine-readable identifier qualifying {@code code}, the
 *     counterpart of {@code ERR-CODE-2} at line 38 of {@code CCPAUERY.cpy}; the empty string when
 *     the failure has no second code, and never {@code null} once constructed
 * @param message the human-readable aggregate sentence, carried verbatim from the originating
 *     copybook where the failure has one, and deliberately NULLABLE: {@code null} is the migrated
 *     form of message-off and the empty string is the migrated form of message-empty, two states the
 *     baseline can still tell apart and this record therefore does not merge
 * @param severity the operational level of the failure, drawn from the baseline's own four-value
 *     domain rather than a newly invented ladder; never {@code null} once constructed
 * @param subsystem the part of the platform the failure arose in, drawn from the baseline's own
 *     six-value domain; never {@code null} once constructed
 * @param status the HTTP status the response carries, restated inside the body so that a logged or
 *     archived payload is self-describing without its response envelope
 * @param correlationId the identity of the unit of work that failed, inherited from the inbound
 *     request rather than invented here; the empty string when the request carried none
 * @param path the request path that failed, so a client reporting a problem does not have to
 *     reconstruct which call produced it; the empty string when no path is available. This is the one
 *     member whose content a caller chooses, so a value narrowed for disclosure elsewhere has to be
 *     narrowed here too: {@link GlobalExceptionHandler} applies that narrowing before the path reaches
 *     this record, and this record does not re-apply it -- one owner of a rule, applied once, is what
 *     keeps a caller from receiving two different renderings of the same value
 * @param timestamp the instant of the failure in the baseline-compatible twenty-six-character form
 *     produced by {@link TimestampFormatter}; the empty string when no reading was supplied, and
 *     never read from the wall clock by this record itself
 * @param fieldErrors the per-field array of transformation rule T7, one entry per offending field in
 *     the order the validating code reported them; never {@code null} once constructed, empty when
 *     the failure is not field-attributable, and always unmodifiable
 * @param abend the structured abend aggregate when the failure was an abend and {@code null}
 *     otherwise; nullable because an abend is genuinely absent from most failures rather than empty
 *     in them
 */
public record ApiError(
        String code,
        String secondaryCode,
        String message,
        Severity severity,
        Subsystem subsystem,
        int status,
        String correlationId,
        String path,
        String timestamp,
        List<FieldError> fieldErrors,
        AbendDetail abend) {

    /**
     * The width a renderer may hold the aggregate message to, seventy-five characters.
     *
     * <p>Assumptions: read from {@code 10 CCARD-ERROR-MSG PIC X(75).} at line 28 of
     * {@code app/cpy/CVCRD01Y.cpy} and from the identically-declared
     * {@code 10 CCARD-RETURN-MSG PIC X(75).} on line 29 beside it. Of the four message widths this
     * migration inherits, this is the only one carried forward as a rendering constraint, and it is
     * published as a constant rather than restated at each renderer because the number comes from a
     * file this migration does not alter: one constant can only be wrong everywhere at once, and is
     * therefore checkable by one test against the copybook line it came from.
     *
     * <p>Trade-offs: the width is documented rather than ENFORCED, and this record rejects nothing
     * for exceeding it. A client rendering into a reflowing browser layout has no 24-row terminal to
     * overflow, so refusing a longer sentence would refuse text no consumer would have struggled
     * with; what is given up is the ability to prove by construction that a migrated message would
     * still have fitted the original screen. Publishing the number is what leaves that proof
     * available to a renderer that does care, without imposing it on one that does not.
     */
    public static final int MESSAGE_RENDERING_WIDTH = 75;

    /**
     * The declared width of the baseline's short message fields, fifty characters.
     *
     * <p>Assumptions: this is documentation of provenance and is not enforced on the wire. It is a
     * house convention rather than a coincidence of one copybook, recurring across three unrelated
     * files: {@code CCDA-MSG-THANK-YOU} and {@code CCDA-MSG-INVALID-KEY} at
     * {@code app/cpy/CSMSG01Y.cpy} lines 18 and 20, {@code ABEND-REASON} at
     * {@code app/cpy/CSMSG02Y.cpy} line 26, and {@code ERR-MESSAGE} at {@code CCPAUERY.cpy} line 39.
     */
    public static final int COMPACT_MESSAGE_WIDTH = 50;

    /**
     * The declared width of the baseline's abend message field, seventy-two characters.
     *
     * <p>Assumptions: documentation of provenance only, read from
     * {@code ABEND-MSG PIC X(72)} at lines 28 and 29 of {@code app/cpy/CSMSG02Y.cpy}. It is named
     * here so that a reader meeting four different widths in this package can see that four were
     * inherited and none was chosen.
     */
    public static final int ABEND_MESSAGE_WIDTH = 72;

    /**
     * The declared width of the date utility's diagnostic out-parameter, eighty characters.
     *
     * <p>Assumptions: documentation of provenance only, read from {@code 01 LS-RESULT PIC X(80).} in
     * the linkage section of {@code app/cbl/CSUTLDTC.cbl} at line 86. It is a call contract rather
     * than a response contract, which is precisely why it is recorded and not applied: the four
     * inherited widths of 50, 72, 75 and 80 are modelled as four, and none is treated as a canonical
     * width from which the others are padded or truncated.
     */
    public static final int DATE_DIAGNOSTIC_WIDTH = 80;

    /**
     * The declared width of each of the baseline's two machine code fields, nine characters.
     *
     * <p>Assumptions: read from {@code ERR-CODE-1 PIC X(09)} at line 37 and
     * {@code ERR-CODE-2 PIC X(09)} at line 38 of {@code CCPAUERY.cpy}. It is recorded rather than
     * enforced because the migrated codes are longer than nine characters by design: a code such as
     * {@link #CODE_VALIDATION} spends its width on being legible to the operator reading it, which a
     * nine-character field could not afford.
     */
    public static final int CODE_WIDTH = 9;

    /**
     * The declared width of the baseline's own correlation identifier, twenty characters.
     *
     * <p>Assumptions: AE-14 reads {@code ERR-EVENT-KEY PIC X(20)} at line 40 of
     * {@code CCPAUERY.cpy}. It is recorded because it establishes that correlating the events of one
     * unit of work is an idea this migration INHERITED rather than introduced, which is why
     * {@code com.carddemo.common.web.CorrelationIdFilter} formalises an existing concept instead of
     * inventing one. The width itself is not enforced, since the migrated identifier is generated by
     * the transport rather than by this record.
     *
     * <p>Refactoring Rationale: package-private rather than public, unlike every other width recorded
     * here. The others are widths this record's own members are held to and a consumer can legitimately
     * assert against; this one is provenance for a field the migration deliberately does NOT carry
     * forward at its declared width, because the identity the transport mints occupies twenty-four
     * characters -- {@code CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH}. Left public it was an
     * unused shared-library constant a consumer could mistake for the live contract and enforce, which
     * would truncate a conforming twenty-four-character identity and break the log join it exists to
     * support. Narrowing the visibility keeps the record and removes the trap; this module's own tests
     * are the reader that still needs it.
     */
    static final int CORRELATION_ID_WIDTH = 20;

    /**
     * The shared machine code for a request whose fields fail validation.
     *
     * <p>Assumptions: the prefix distinguishes a CardDemo code from prose and the 0400 suffix keeps
     * the code aligned with the HTTP 400 response assembled by the shared exception advice. The
     * constant is separate from every message because {@code ERR-CODE-1 PIC X(09)} at line 37 and
     * {@code ERR-MESSAGE PIC X(50)} at line 39 of {@code CCPAUERY.cpy} are separate fields.
     *
     * <p>Assumptions: the four code constants declared here are the CLOSED set this shared kernel
     * defines, and a bounded context is free to publish its own alongside them rather than stretching
     * one of these to fit. They are spelled as a fixed prefix and a four-digit ordinal so that a code
     * is recognisable as one at a glance, sorts stably in an operational search, and cannot be
     * mistaken for a sentence a client might display.
     */
    public static final String CODE_VALIDATION = "CARDDEMO-0400";

    /**
     * The shared machine code for a request that conflicts with persisted state.
     *
     * <p>Assumptions: the 0409 suffix identifies the HTTP 409 surface without embedding either
     * conflict sentence in the code. The optimistic-lock sentence remains the 46-character literal
     * declared at lines 521 and 522 of {@code app/cbl/COACTUPC.cbl}.
     *
     * <p>Assumptions: this is the migrated form of the baseline's OWN before-image comparison rather
     * than a mechanism this migration introduces. The account update program snapshots the whole
     * pre-edit record from line 669 of {@code app/cbl/COACTUPC.cbl}, carries the one-character change
     * marker declared at its line 168, and rolls back at lines 4097 to 4103 when the rewrite finds the
     * record altered underneath it. One code covers that case and the reference-data
     * restrict-on-delete case, because both answer HTTP 409 and both are told apart by
     * {@link #message()} rather than by status -- two codes for one status would make a client branch
     * on a distinction the status does not carry.
     */
    public static final String CODE_CONFLICT = "CARDDEMO-0409";

    /**
     * The shared machine code for a request naming an absent record.
     *
     * <p>Assumptions: the 0404 suffix identifies the HTTP 404 surface while leaving the user-visible
     * wording to the caller, preserving the code-versus-message separation established by
     * {@code CCPAUERY.cpy} lines 37 to 39.
     *
     * <p>Assumptions: the baseline expresses a missing record as a FILE STATUS rather than as an
     * exception, and the migrated code is deliberately generic for that reason -- the posting
     * program's reject reason 100 is an unmatched cross-reference read and reason 101 an unmatched
     * account read, two different records behind one condition. Which record was missing therefore
     * travels in {@link #message()}, carrying the reject reason's own wording, instead of splitting
     * into one code per record type that a client would then have to enumerate.
     */
    public static final String CODE_NOT_FOUND = "CARDDEMO-0404";

    /**
     * The shared machine code for a failure the client cannot correct.
     *
     * <p>Assumptions: the 0500 suffix identifies the HTTP 500 surface and carries no cause text. The
     * response therefore has no member sourced from a caught exception, while the correlation
     * identifier still lets an operator find the diagnostic record.
     */
    public static final String CODE_INTERNAL = "CARDDEMO-0500";

    /**
     * The HTTP status used by the abend factory.
     *
     * <p>Assumptions: the integer is declared here rather than imported from a web framework because
     * {@code services/common-lib/pom.xml} marks the web dependency optional, while this record must
     * remain constructible by a non-HTTP consumer of the shared library.
     */
    public static final int INTERNAL_SERVER_ERROR_STATUS = 500;

    /**
     * The HTTP status a contention refusal carries, 409.
     *
     * <p>Assumptions: declared here for the same reason {@link #INTERNAL_SERVER_ERROR_STATUS} is --
     * {@code services/common-lib/pom.xml} marks the web dependency optional, so this record cannot read
     * the framework's status enumeration and the number has to be stated. It is a constant rather than a
     * literal inside {@link #ofConflict} so that a reader can see that the code and the status of a
     * contention refusal are both fixed, and a test can assert the pair.</p>
     *
     * <p>Assumptions: 409 rather than 412. Both are defensible for a lost version comparison, and 409 is
     * chosen because it covers all three contention conditions this migration distinguishes, whereas 412
     * would describe only the one that arrives with a precondition header -- and this migration carries
     * the version in the body, following the baseline's own before-image comparison rather than an HTTP
     * conditional-request idiom the baseline has no counterpart for.</p>
     */
    public static final int CONFLICT_STATUS = 409;

    /**
     * The shared machine code for a request refused because the environment is not currently accepting
     * mutating work.
     *
     * <p>Assumptions: the 0503 suffix identifies the HTTP 503 surface, following the same convention as
     * the four codes above. It is a distinct code rather than a reuse of {@link #CODE_INTERNAL} because
     * the two conditions call for opposite client behaviour: an internal failure should not be retried
     * blindly, whereas this one succeeds on retry once the window reopens, and a client cannot tell the
     * difference from the status alone once its own error handling has collapsed every 5xx into one
     * case.</p>
     */
    public static final String CODE_WRITES_QUIESCED = "CARDDEMO-0503";

    /**
     * The HTTP status a refusal during the online-write window carries, 503.
     *
     * <p>Assumptions: declared here for the same reason {@link #CONFLICT_STATUS} is -- the web
     * dependency is optional for this module, so the number has to be stated rather than read from a
     * framework enumeration -- and as a constant so that a test can assert the code and the status as a
     * pair.</p>
     */
    public static final int SERVICE_UNAVAILABLE_STATUS = 503;

    /**
     * The shared machine code for a request whose body exceeds the accepted size.
     *
     * <p>Assumptions: the 0413 suffix identifies the HTTP 413 surface, following the same convention as
     * the five codes above. It is a distinct code rather than a reuse of {@link #CODE_VALIDATION}
     * because the two refusals differ in what the caller must do about them: a validation refusal names
     * the offending members in {@link #fieldErrors()} and is corrected member by member, whereas this
     * one names no member at all -- it is refused before any member has been parsed -- and is corrected
     * only by submitting less. Collapsing the two would give a client a 400 with an empty field array
     * and no way to tell that case from a body it had merely mis-spelled.</p>
     *
     * <p>Assumptions: it has no reference counterpart, and the absence is worth stating rather than
     * leaving as a silent gap. A reference screen field cannot overflow its own declared width -- the
     * terminal refuses the keystroke -- so no reference program, literal or file status corresponds to
     * this condition. It exists because a target request body is assembled by a caller this system does
     * not control, which is a exposure the fixed 24-by-80 presentation layer did not have.</p>
     */
    public static final String CODE_PAYLOAD_TOO_LARGE = "CARDDEMO-0413";

    /**
     * The HTTP status an over-large request body carries, 413.
     *
     * <p>Assumptions: declared here for the same reason {@link #CONFLICT_STATUS} is --
     * {@code services/common-lib/pom.xml} marks the web dependency optional, so this record cannot read
     * the framework's status enumeration and the number has to be stated -- and as a constant so that a
     * test can assert the code and the status as a pair.</p>
     *
     * <p>Assumptions: 413 rather than 400. Both are answerable, and 413 is chosen because it is the one
     * status whose definition names the size of the body as the reason, which is what lets a caller
     * distinguish a request it can shrink from one it must correct. A 400 would leave the caller
     * inspecting the message text to learn which of the two it had.</p>
     */
    public static final int PAYLOAD_TOO_LARGE_STATUS = 413;

    /**
     * The CardDemo thank-you source literal from {@code CSMSG01Y}.
     *
     * <p>Assumptions: AE-12 preserves the 49-character literal at line 19 of
     * {@code app/cpy/CSMSG01Y.cpy}, including its six trailing blanks, separately from the declared
     * {@code PIC X(50)} width on line 18. COBOL supplies the one additional right-pad blank at load;
     * adding that seventh blank here would no longer be the source literal transformation rule T8
     * requires.
     */
    public static final String CSMSG01Y_THANK_YOU =
            "Thank you for using CardDemo application..." + "      ";

    /**
     * The invalid-key source literal from {@code CSMSG01Y}.
     *
     * <p>Assumptions: AE-12 preserves the 49-character literal at line 21 of
     * {@code app/cpy/CSMSG01Y.cpy}, including its nine trailing blanks, separately from the declared
     * {@code PIC X(50)} width on line 20. The source and loaded field therefore remain two
     * independently testable contracts rather than one silently padded constant.
     */
    public static final String CSMSG01Y_INVALID_KEY =
            "Invalid key pressed. Please see below..." + "         ";

    /**
     * The date-edit fragment for a thirty-first day in a shorter month.
     *
     * <p>Assumptions: AE-13 preserves five casing and punctuation regimes rather than harmonising
     * them: mixed-case and mostly unpunctuated at {@code COACTUPC.cbl} lines 508 to 528; mixed-case,
     * ellipsis-terminated and blank-padded at {@code CSMSG01Y.cpy} lines 18 to 21; colon-prefixed
     * and period-terminated here at {@code CSUTLDPY.cpy} line 221; leading-blank and
     * {@code ||}-terminated at {@code COPAUS1C.cbl} lines 544 to 549; and all-caps unpunctuated
     * text at {@code tests/README.md} lines 563 to 565. Transformation rule T8 makes every one of
     * those differences part of the contract.
     */
    public static final String CSUTLDPY_NO_31_DAYS =
            ":Cannot have 31 days in this month.";

    /**
     * The date-edit fragment for a thirtieth day in February.
     *
     * <p>Assumptions: AE-13 carries the exact literal from line 236 of
     * {@code app/cpy/CSUTLDPY.cpy}; its leading colon and terminating period are retained rather
     * than normalised to either of the other message regimes.
     */
    public static final String CSUTLDPY_NO_30_DAYS =
            ":Cannot have 30 days in this month.";

    /**
     * The date-edit fragment for a twenty-ninth day in a non-leap year.
     *
     * <p>Assumptions: AE-13 carries line 266 of {@code app/cpy/CSUTLDPY.cpy} character for
     * character. In particular there is no blank after the leading colon and none after the first
     * period; introducing either would change the help text transformation rule T8 preserves.
     */
    public static final String CSUTLDPY_NOT_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    /**
     * The optimistic-lock message-valued condition from {@code COACTUPC}.
     *
     * <p>Assumptions: AE-13 preserves the mixed-case, unpunctuated regime represented by the
     * 46-character literal at line 522 of {@code app/cbl/COACTUPC.cbl}, including the two-word
     * spelling {@code some one} and no terminating period. Line 168 is a different construct, the
     * one-character {@code WS-DATACHANGED-FLAG} whose conditions are at lines 169 and 170; only the
     * separate message-valued condition at lines 521 and 522 becomes this constant.
     */
    public static final String COACTUPC_RECORD_CHANGED =
            "Record changed by some one else. Please review";

    /**
     * Normalises required metadata, preserves the two aggregate-message empty states and seals the
     * per-field array.
     *
     * <p>Assumptions: AE-03 keeps the array variable length. The two-field cases at lines 213 to 217
     * and 228 to 232 of {@code app/cpy/CSUTLDPY.cpy} coexist with the three-field cases at lines 259
     * to 262 and 300 to 304, so neither a fixed size nor a uniform three-way fan-out is valid.
     *
     * <p>Assumptions: AE-15 represents message-off as {@code null} and message-empty as
     * {@code ""}. The sentinel belongs to {@code CCARD-RETURN-MSG} at lines 29 and 30 of
     * {@code app/cpy/CVCRD01Y.cpy}; {@code CCARD-ERROR-MSG} on line 28 has no sentinel. Normalising
     * both states to one string would erase the asymmetry the two adjacent declarations preserve.
     *
     * <p>Trade-offs: a {@code null} field-error list becomes the empty list and a supplied list is
     * copied. That allocates on the error path, but it makes every constructed instance expose the
     * always-present array transformation rule T7 requires and prevents a retained caller reference
     * from changing the response after construction.
     *
     * <p>Assumptions: a supplied {@link AbendDetail} is stored only in its external form. The
     * baseline block at {@code app/cpy/CSMSG02Y.cpy} lines 22 to 29 carries a four-character code
     * beside an internal culprit, reason and message; retaining the code while blanking the other
     * internal components prevents a response-shaped value from becoming a transport for diagnostic
     * detail that belongs in the operational record.
     *
     * <p>Refactoring Rationale: the supplied path is masked here as well, on exactly the reasoning that
     * already applies to the abend detail one line below. The shared advice masks the request target
     * before it ever reaches this constructor, so on every failure this system currently produces the
     * masking here finds nothing and returns the same string; what it buys is that the guarantee stops
     * depending on the advice being the only producer. A service that adds its own advice, or a
     * component that assembles this shape for a failure of its own, inherits the masking without
     * knowing about it -- which is the difference between a rule and a convention. Applying it in the
     * canonical constructor means no instance can exist that has not passed through it.</p>
     *
     * <p>Trade-offs: the masking runs twice on the ordinary path, once in the advice's reader and once
     * here. The duplicate pass is accepted because the scan returns its argument unchanged when it finds
     * no card-number-shaped run, which is every routed request, so the second pass costs one traversal
     * of a short string on a path that has already failed. Removing the masking from the advice's reader
     * to avoid the duplication was rejected: that reader also feeds nine log statements, and those never
     * reach this constructor.</p>
     *
     * @param code the primary machine code, normalised to {@link #CODE_INTERNAL} when absent or blank
     * @param secondaryCode the subordinate machine code, normalised to the empty string when absent
     * @param message the aggregate message, retained exactly including {@code null} and the empty
     *     string because those states are distinct
     * @param severity the baseline-compatible severity; must not be {@code null}
     * @param subsystem the baseline-compatible subsystem; must not be {@code null}
     * @param status the HTTP status, retained without alteration
     * @param correlationId the inherited correlation identity, normalised to the empty string when
     *     absent
     * @param path the failing request path, normalised to the empty string when absent and with every
     *     run of sixteen or more digits masked to its last four
     * @param timestamp the caller-supplied timestamp string, normalised to the empty string when
     *     absent
     * @param fieldErrors the per-field entries, copied into an unmodifiable list; {@code null}
     *     becomes an empty list
     * @param abend the structured abend detail, reduced to its client-safe external form when
     *     present, or {@code null} when the failure was not an abend
     * @throws NullPointerException if {@code severity} or {@code subsystem} is {@code null}, or if a
     *     supplied {@code fieldErrors} list contains a {@code null} entry
     */
    public ApiError {
        code = (code == null || code.isBlank()) ? CODE_INTERNAL : code;
        secondaryCode = secondaryCode == null ? "" : secondaryCode;
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(subsystem, "subsystem must not be null");
        correlationId = correlationId == null ? "" : correlationId;
        path = path == null ? "" : CardNumberMasker.maskEmbeddedCardNumbers(path);
        timestamp = timestamp == null ? "" : timestamp;
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
        abend = abend == null ? null : abend.external();
    }

    /**
     * One structured entry in the per-field error array.
     *
     * <p>Assumptions: AE-04 treats blank as a subset of error. Lines 18 and 19 of
     * {@code app/cpy/CSSETATY.cpy} contain one disjunctive test over the not-acceptable and blank
     * conditions, so {@link #isError()} delegates to the shared derived predicate and answers
     * {@code true} for both states rather than modelling blank as a peer of error.
     *
     * <p>Assumptions: AE-05 derives the asterisk through {@link #screenMarker()} and never stores it
     * as a state. Lines 21 and 22 of {@code app/cpy/CSSETATY.cpy} write the error colour to the
     * {@code C} subfield, while lines 24 and 25 write {@code '*'} to the distinct {@code O}
     * subfield. The two destinations make the marker a rendering hint rather than a flag value.
     *
     * <p>Refactoring Rationale: AE-06 removes the conversational presentation gate at line 20 of
     * {@code app/cpy/CSSETATY.cpy}. The one-digit context at lines 29 to 31 of
     * {@code app/cpy/COCOM01Y.cpy} distinguishes an initial turn from a later one, but a stateless
     * response has no turn to retain. Presence in this array therefore drives presentation by itself,
     * including on an initial failed submission; the baseline does one thing, the Java implements
     * another, and the divergence is documented.
     *
     * <p>Trade-offs: AE-16 consumes {@link FieldValidationFlag} rather than declaring another
     * three-state vocabulary. Transformation rule T2 and the single include-path discipline at line
     * 268 and lines 540 to 542 of {@code tests/README.md} require shared layouts to stay
     * single-sourced. The dependency consequently points from {@code error} to {@code validation}
     * and never back, which costs an adapter at this boundary but leaves validation independent of a
     * response model.
     *
     * <p>Trade-offs: AE-17 nests this record here instead of creating a fourth top-level production
     * class. The package inventory is three production classes plus one charter, and transformation
     * rule T7 requires the array element to sit beside the array that owns it. The audit includes
     * {@code RECORD_DEF} and disallows missing component tags, so this nested record carries its own
     * Javadoc and all three {@code @param} entries rather than relying on the outer record's block.
     *
     * @param field the response-field identity the client uses to attach help text; never
     *     {@code null}, empty or entirely whitespace
     * @param state the shared validation state, either {@link FieldValidationFlag#NOT_OK} or
     *     {@link FieldValidationFlag#BLANK}; never {@code null} or
     *     {@link FieldValidationFlag#VALID}
     * @param message the human-readable help text for the field; never {@code null}, empty or
     *     entirely whitespace
     */
    public record FieldError(String field, FieldValidationFlag state, String message) {

        /**
         * Rejects an entry that cannot identify an offending field and explain its error.
         *
         * <p>Assumptions: the presence of an entry is itself evidence of an error, matching the
         * single disjunction at lines 18 and 19 of {@code app/cpy/CSSETATY.cpy}. Allowing
         * {@link FieldValidationFlag#VALID} here would make an array length no longer equal the
         * number of offending fields and would give clients two contradictory signals.
         *
         * @param field the response-field identity to validate and retain
         * @param state the shared validation state to validate and retain
         * @param message the field help text to validate and retain
         * @throws NullPointerException if any component is {@code null}
         * @throws IllegalArgumentException if {@code field} or {@code message} is blank, or if
         *     {@code state} is not an error state
         */
        public FieldError {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(message, "message must not be null");

            if (field.isBlank()) {
                throw new IllegalArgumentException("field must not be blank");
            }
            if (!state.isError()) {
                throw new IllegalArgumentException(
                        "state must be an error state, but was " + state + " for field " + field);
            }
            if (message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }

        /**
         * Reports whether this entry's shared validation state is an error.
         *
         * <p>Refactoring Rationale: the annotation is what keeps this predicate off the wire, and it is
         * required rather than decorative. Jackson treats an {@code isX()} method returning
         * {@code boolean} as a readable property named {@code x}, so without it every response carried a
         * fourth {@code error} member alongside the three record components -- and every one of the seven
         * published contracts declares {@code FieldError} with {@code additionalProperties: false}, so
         * that fourth member was a response no contract admitted. The predicate itself is retained
         * because callers inside the JVM use it; it is its PUBLICATION that was wrong, not its existence.
         *
         * <p>Alternatives Considered: declaring the member in all seven contracts instead. Rejected
         * because the value is a pure function of {@code state} and is unconditionally {@code true} --
         * the compact constructor above refuses {@link FieldValidationFlag#VALID} -- so it would oblige
         * every generated client to carry an accessor that can only ever answer one way, and would put a
         * second, redundant statement of the same fact beside {@code state} for a caller to reconcile.
         *
         * <p>Assumptions: the sibling {@link #screenMarker()} needs no annotation, because it is neither
         * a record component nor named with a {@code get} or {@code is} prefix and so is invisible to
         * Jackson's default property detection. That is a naming coincidence rather than a stated
         * intent, which is why the emitted property set is pinned by a test rather than left to it.
         *
         * @return {@code true} for both {@link FieldValidationFlag#NOT_OK} and
         *     {@link FieldValidationFlag#BLANK}; a valid state cannot be constructed
         */
        @JsonIgnore
        public boolean isError() {
            return state.isError();
        }

        /**
         * Returns the rendering marker derived from the shared validation state.
         *
         * <p>Assumptions: this member is excluded from the serialised form for the same reason the
         * predicate above is, and the exclusion is declared rather than relied upon. The name is not
         * a bean-style getter, so the serialiser does not detect it under the configuration this
         * kernel ships today; annotating it means a later configuration that enabled fluent
         * accessor detection could not silently add a fifth member to a contract two consumers have
         * closed.</p>
         *
         * @return {@link FieldValidationFlag#BLANK_SCREEN_MARKER} for a blank field, otherwise
         *     {@link FieldValidationFlag#NO_SCREEN_MARKER}
         */
        @JsonIgnore
        public String screenMarker() {
            return state.screenMarker();
        }

        /**
         * Converts the validation package's field entry into the response package's nested entry.
         *
         * <p>Trade-offs: the two records intentionally have the same three components but remain
         * distinct types. The validation entry can be produced without depending on this response
         * package, preserving the one-way package arrow required by transformation rule T2; this
         * allocation is the boundary cost of keeping that direction.
         *
         * @param source the validation entry to convert; must not be {@code null}
         * @return a response entry carrying the same field, state and message, never {@code null}
         * @throws NullPointerException if {@code source} is {@code null}
         */
        public static FieldError from(FieldValidationFlag.FieldError source) {
            Objects.requireNonNull(source, "source must not be null");
            return new FieldError(source.field(), source.state(), source.message());
        }

        /**
         * Converts validation entries to an unmodifiable response array while preserving order.
         *
         * <p>Assumptions: encounter order is retained because the two-field and three-field cases at
         * lines 213 to 217 and 259 to 262 of {@code app/cpy/CSUTLDPY.cpy} establish variable
         * fan-out but contain no instruction to alphabetise the resulting fields. Sorting here
         * would replace the validating caller's order with one the baseline never specifies.
         *
         * @param sources the validation entries to convert in encounter order; must not be
         *     {@code null} and must contain no {@code null} entry
         * @return an unmodifiable response-entry list in the same order, empty when
         *     {@code sources} is empty
         * @throws NullPointerException if {@code sources} or one of its entries is {@code null}
         */
        public static List<FieldError> fromAll(
                List<FieldValidationFlag.FieldError> sources) {
            Objects.requireNonNull(sources, "sources must not be null");
            List<FieldError> converted = new ArrayList<>(sources.size());
            for (FieldValidationFlag.FieldError source : sources) {
                converted.add(from(source));
            }
            return List.copyOf(converted);
        }
    }

    /**
     * The four operational levels declared by the baseline error-log record.
     *
     * <p>Alternatives Considered: AE-07 rejects a fresh logging-framework ladder and adopts the
     * exact domain at lines 26 to 29 of {@code CCPAUERY.cpy}: {@code 'L'} log, {@code 'I'} info,
     * {@code 'W'} warning and {@code 'C'} critical. Adding a debug rung or splitting critical into
     * error and fatal would create values the baseline's operational material cannot name, so all
     * four existing values and no invented fifth value are retained.
     */
    public enum Severity {

        /** The baseline log level encoded by {@code 'L'} at line 26 of {@code CCPAUERY.cpy}. */
        LOG('L'),

        /** The baseline informational level encoded by {@code 'I'} at line 27 of {@code CCPAUERY.cpy}. */
        INFO('I'),

        /** The baseline warning level encoded by {@code 'W'} at line 28 of {@code CCPAUERY.cpy}. */
        WARNING('W'),

        /** The baseline critical level encoded by {@code 'C'} at line 29 of {@code CCPAUERY.cpy}. */
        CRITICAL('C');

        private final char code;

        /**
         * Associates one enum value with its one-character baseline code.
         *
         * @param code the character declared for this level at lines 26 to 29 of
         *     {@code CCPAUERY.cpy}
         */
        Severity(char code) {
            this.code = code;
        }

        /**
         * Returns the one-character baseline representation of this level.
         *
         * @return {@code 'L'}, {@code 'I'}, {@code 'W'} or {@code 'C'} for the corresponding value
         */
        public char code() {
            return this.code;
        }

        /**
         * Resolves a baseline severity character to its named value.
         *
         * @param code the character to resolve, expected to be one of the four values declared at
         *     lines 26 to 29 of {@code CCPAUERY.cpy}
         * @return the matching severity, never {@code null}
         * @throws IllegalArgumentException if {@code code} is outside the four-value baseline domain
         */
        public static Severity fromCode(char code) {
            return switch (code) {
                case 'L' -> LOG;
                case 'I' -> INFO;
                case 'W' -> WARNING;
                case 'C' -> CRITICAL;
                // WHY : Assumptions: an unknown value is rendered as a code point because a
                //       one-character field can contain an invisible control character. Naming its
                //       numeric identity keeps the rejection diagnosable while the accepted set
                //       remains exactly the four visible characters at CCPAUERY.cpy lines 26 to 29.
                default -> throw new IllegalArgumentException(
                        "unknown severity code: U+" + String.format("%04X", (int) code));
            };
        }
    }

    /**
     * The six subsystem identities declared by the baseline error-log record.
     *
     * <p>Alternatives Considered: AE-08 rejects a target-only vocabulary and adopts lines 31 to 36
     * of {@code CCPAUERY.cpy}: application {@code 'A'}, CICS {@code 'C'}, IMS {@code 'I'}, Db2
     * {@code 'D'}, MQ {@code 'M'} and file {@code 'F'}. Application maps to application, Db2 to the
     * relational store, MQ to the queue transport and file to the object store; CICS and IMS have no
     * target analogue but remain decodable so a reader of baseline and migrated records does not
     * lose two members of the original six-value domain.
     */
    public enum Subsystem {

        /** The application subsystem encoded by {@code 'A'} at line 31 of {@code CCPAUERY.cpy}. */
        APPLICATION('A'),

        /** The CICS subsystem encoded by {@code 'C'} at line 32 of {@code CCPAUERY.cpy}. */
        CICS('C'),

        /** The IMS subsystem encoded by {@code 'I'} at line 33 of {@code CCPAUERY.cpy}. */
        IMS('I'),

        /** The relational-store mapping of Db2, encoded by {@code 'D'} at line 34. */
        RELATIONAL('D'),

        /** The queue-transport mapping of MQ, encoded by {@code 'M'} at line 35. */
        QUEUE('M'),

        /** The object-store mapping of file, encoded by {@code 'F'} at line 36. */
        OBJECT_STORE('F');

        private final char code;

        /**
         * Associates one enum value with its one-character baseline code.
         *
         * @param code the character declared for this subsystem at lines 31 to 36 of
         *     {@code CCPAUERY.cpy}
         */
        Subsystem(char code) {
            this.code = code;
        }

        /**
         * Returns the one-character baseline representation of this subsystem.
         *
         * @return one of {@code 'A'}, {@code 'C'}, {@code 'I'}, {@code 'D'}, {@code 'M'} or
         *     {@code 'F'}
         */
        public char code() {
            return this.code;
        }

        /**
         * Reports whether the migrated platform has a direct analogue for this subsystem.
         *
         * @return {@code false} for {@link #CICS} and {@link #IMS}; {@code true} for the four
         *     subsystem values that map to a target component
         */
        public boolean hasTargetAnalogue() {
            return this != CICS && this != IMS;
        }

        /**
         * Resolves a baseline subsystem character to its named value.
         *
         * @param code the character to resolve, expected to be one of the six values declared at
         *     lines 31 to 36 of {@code CCPAUERY.cpy}
         * @return the matching subsystem, never {@code null}
         * @throws IllegalArgumentException if {@code code} is outside the six-value baseline domain
         */
        public static Subsystem fromCode(char code) {
            return switch (code) {
                case 'A' -> APPLICATION;
                case 'C' -> CICS;
                case 'I' -> IMS;
                case 'D' -> RELATIONAL;
                case 'M' -> QUEUE;
                case 'F' -> OBJECT_STORE;
                // WHY : Assumptions: the code-point form remains legible for an invisible input and
                //       does not widen the accepted domain beyond the six visible characters at
                //       CCPAUERY.cpy lines 31 to 36.
                default -> throw new IllegalArgumentException(
                        "unknown subsystem code: U+" + String.format("%04X", (int) code));
            };
        }
    }

    /**
     * Writes the aggregate message only while its message-off state is still present.
     *
     * <p>Refactoring Rationale: the first candidate that arrives while this shape is still in its
     * message-off state wins, because the four {@code IF WS-RETURN-MSG-OFF} sites at lines 218, 233,
     * 263 and 305 of {@code app/cpy/CSUTLDPY.cpy} guard every aggregate write in the reference
     * validator. The field those sites guard is {@code WS-RETURN-MSG PIC X(75)}, and its off-state is
     * declared {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} -- at line 174 of
     * {@code app/cbl/COCRDUPC.cbl} and at line 250 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. A BLANK aggregate is therefore
     * message-off in the reference, and this method previously treated only {@code null} as such, so a
     * shape carrying an empty or all-blank message silently refused its first real message -- the exact
     * inverse of the first-message-wins behaviour it exists to reproduce.
     *
     * <p>Refactoring Rationale: the predicate is message-specific and is no longer
     * {@link FieldValidationFlag#isNeverSupplied(String)}. That predicate folds THREE states into
     * message-off -- null, empty, all-space and all-{@code LOW-VALUES} -- because a screen INPUT field
     * genuinely arrives as either pad byte. This field is not a screen input. Its off-state is declared
     * exactly once, as {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} at line 174 of
     * {@code app/cbl/COCRDUPC.cbl} and line 250 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, and SPACES is the whole of it; the
     * {@code LOW-VALUES} off-state at line 30 of {@code app/cpy/CVCRD01Y.cpy} belongs to a different
     * field, {@code CCARD-RETURN-MSG}. Reusing the wider predicate therefore admitted a fourth state
     * the cited baseline does not treat as off, and treating an all-{@code LOW-VALUES} aggregate as
     * absent means silently OVERWRITING it -- the one thing first-message-wins exists to prevent.
     *
     * <p>Assumptions: narrowing the predicate changes nothing on any reachable path, which is what
     * makes it safe rather than merely more faithful. Over HTTP an aggregate the caller never set
     * arrives as {@code null}, one a mapper defaulted arrives empty, and one padded out to its declared
     * width arrives blank -- and all three remain message-off here. The removed arm is the run of NUL
     * characters, which no JSON body and no mapper in this migration produces, so it can only arise from
     * a corrupted value; refusing to overwrite one is strictly better than replacing it and reporting
     * nothing.
     *
     * <p>Trade-offs: one predicate becomes two, and the duplication is deliberate. The field entries
     * keep {@link FieldValidationFlag#isNeverSupplied(String)} because they ARE screen inputs and both
     * pad bytes reach them; the aggregate gets its own. A single shared predicate would have to be the
     * wider of the two to serve the inputs, so sharing it is what caused this defect.
     *
     * @param candidate the aggregate message to latch; may be empty but must not be {@code null}
     * @return this instance when a message is already present, otherwise a new instance carrying
     *     {@code candidate}
     * @throws NullPointerException if {@code candidate} is {@code null}
     */
    public ApiError latchMessage(String candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (!isMessageOff(this.message)) {
            return this;
        }

        return new ApiError(
                this.code,
                this.secondaryCode,
                candidate,
                this.severity,
                this.subsystem,
                this.status,
                this.correlationId,
                this.path,
                this.timestamp,
                this.fieldErrors,
                this.abend);
    }

    /**
     * Reports whether an aggregate message is in the state the baseline declares as message-off.
     *
     * <p>Assumptions: the state is null, empty, or every character a space, and nothing else. Those are
     * the three shapes an unset aggregate arrives in over HTTP -- absent, defaulted, or padded to a
     * declared width -- and SPACES is the only off-state the field's own condition name declares, at
     * line 174 of {@code app/cbl/COCRDUPC.cbl}. A run of NUL characters is deliberately NOT off-state
     * here; the reasoning is on {@link #latchMessage(String)}.
     *
     * <p>Trade-offs: the space test is written out rather than delegated to the platform's blank test.
     * {@code String.isBlank} answers true for every Unicode whitespace character -- a tab, a form feed,
     * a no-break space -- and a value made of those is not a message the baseline would have written,
     * so admitting it as off-state would let a non-space value be overwritten. Testing for the one
     * character the condition name declares keeps the predicate exactly as wide as its source.
     *
     * @param message the aggregate message to classify, which may be {@code null}
     * @return {@code true} when the message is {@code null}, empty, or made only of space characters
     */
    private static boolean isMessageOff(String message) {
        if (message == null || message.isEmpty()) {
            return true;
        }

        for (int index = 0; index < message.length(); index++) {
            if (message.charAt(index) != ' ') {
                return false;
            }
        }

        return true;
    }

    /**
     * Appends one field entry without changing the aggregate message.
     *
     * <p>Refactoring Rationale: field entries accumulate independently of
     * {@link #latchMessage(String)} because the baseline sets field flags unconditionally beside the
     * four guarded aggregate writes at lines 213 to 305 of {@code app/cpy/CSUTLDPY.cpy}. Returning a
     * new record keeps the original response immutable while preserving every earlier entry.
     *
     * @param fieldError the field entry to append; must not be {@code null}
     * @return a new problem shape with {@code fieldError} after all existing entries, never
     *     {@code null}
     * @throws NullPointerException if {@code fieldError} is {@code null}
     */
    public ApiError withFieldError(FieldError fieldError) {
        Objects.requireNonNull(fieldError, "fieldError must not be null");
        List<FieldError> accumulated = new ArrayList<>(this.fieldErrors.size() + 1);
        accumulated.addAll(this.fieldErrors);
        accumulated.add(fieldError);

        return new ApiError(
                this.code,
                this.secondaryCode,
                this.message,
                this.severity,
                this.subsystem,
                this.status,
                this.correlationId,
                this.path,
                this.timestamp,
                accumulated,
                this.abend);
    }

    /**
     * Reports whether this problem shape attributes the failure to any named field.
     *
     * @return {@code true} when at least one field entry is present, otherwise {@code false}
     */
    public boolean hasFieldErrors() {
        return !this.fieldErrors.isEmpty();
    }

    /**
     * Builds a problem shape that is not attributable to a particular field.
     *
     * <p>Assumptions: AE-11 obtains the 26-character, zone-less timestamp through
     * {@link TimestampFormatter#formatNow(Clock)} using the caller's explicit clock. The resulting
     * {@code yyyy-MM-dd HH:mm:ss.SSSSSS} form contains a space between date and time, and this file
     * never resolves an ambient clock of its own.
     *
     * <p>Trade-offs: two published contracts of this type are asserted at BUILD time rather than
     * enforced here by refusing an argument, and the choice is deliberate in both cases.
     * {@link #MESSAGE_RENDERING_WIDTH} is the 75-character band the reference message line occupies,
     * and {@code status} is expected to be a real HTTP status. Refusing either here would put a throw
     * inside the one code path whose whole purpose is to RENDER a failure: a message eight characters
     * too long would stop producing a body a client could read and start producing no body at all, and
     * an odd status would do the same. That trades a cosmetic defect for an outage of the error
     * channel. Every message this system can emit is a named constant, and every status is supplied by
     * a handler from an {@code HttpStatus} member, so both properties are decidable without running
     * the application: {@code ApiErrorTest} asserts the band over every emittable message constant and
     * over the composed validator messages, and the handler tests assert the status of every response
     * shape. That places the failure at build time, which is what the width being a contract requires,
     * without inventing a new way for an error response to fail.
     *
     * @param code the stable machine code for the failure
     * @param message the aggregate message, including {@code null} for message-off
     * @param status the HTTP status the response carries
     * @param correlationId the inherited correlation identity, or {@code null} when absent
     * @param path the failed request path, or {@code null} when absent
     * @param clock the explicit clock from which the timestamp is read; must not be {@code null}
     * @return a problem shape with an empty field array and no abend detail, never {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     * @throws IllegalArgumentException if the clock yields a year outside the formatter's supported
     *     four-digit range
     */
    public static ApiError of(String code, String message, int status, String correlationId,
            String path, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new ApiError(
                code,
                "",
                message,
                severityForStatus(status),
                Subsystem.APPLICATION,
                status,
                correlationId,
                path,
                TimestampFormatter.formatNow(clock),
                List.of(),
                null);
    }

    /**
     * Builds a validation problem shape from nested response field entries.
     *
     * <p>Assumptions: encounter order is retained because the two-field and three-field fan-outs at
     * lines 213 to 262 of {@code app/cpy/CSUTLDPY.cpy} contain no instruction to reorder their
     * fields after validation. The constructor seals the caller's supplied order without sorting
     * or grouping it.
     *
     * @param message the first aggregate message, including {@code null} for message-off
     * @param status the HTTP status the response carries
     * @param correlationId the inherited correlation identity, or {@code null} when absent
     * @param path the failed request path, or {@code null} when absent
     * @param fieldErrors the field entries in reporting order; must not be {@code null}
     * @param clock the explicit clock from which the timestamp is read; must not be {@code null}
     * @return a validation problem shape carrying all supplied entries, never {@code null}
     * @throws NullPointerException if {@code fieldErrors}, one of its entries or {@code clock} is
     *     {@code null}
     * @throws IllegalArgumentException if the clock yields a year outside the formatter's supported
     *     four-digit range
     */
    public static ApiError ofFieldErrors(String message, int status, String correlationId,
            String path, List<FieldError> fieldErrors, Clock clock) {
        Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ApiError(
                CODE_VALIDATION,
                "",
                message,
                Severity.WARNING,
                Subsystem.APPLICATION,
                status,
                correlationId,
                path,
                TimestampFormatter.formatNow(clock),
                fieldErrors,
                null);
    }

    /**
     * Builds a validation problem shape at a caller-supplied local timestamp.
     *
     * <p>Assumptions: AE-11 accepts a {@link LocalDateTime} because
     * {@link TimestampFormatter#format(LocalDateTime)} is the dependency's direct caller-supplied
     * route. It emits exactly 26 characters with a space separator and no zone, so no clock read
     * occurs anywhere in this method.
     *
     * @param message the first aggregate message, including {@code null} for message-off
     * @param status the HTTP status the response carries
     * @param correlationId the inherited correlation identity, or {@code null} when absent
     * @param path the failed request path, or {@code null} when absent
     * @param fieldErrors the field entries in reporting order; must not be {@code null}
     * @param timestamp the zone-less local timestamp to format; must not be {@code null}
     * @return a validation problem shape stamped with {@code timestamp}, never {@code null}
     * @throws NullPointerException if {@code fieldErrors}, one of its entries or {@code timestamp}
     *     is {@code null}
     * @throws IllegalArgumentException if {@code timestamp} has a year outside the formatter's
     *     supported four-digit range
     */
    public static ApiError ofFieldErrorsAt(String message, int status, String correlationId,
            String path, List<FieldError> fieldErrors, LocalDateTime timestamp) {
        Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        return new ApiError(
                CODE_VALIDATION,
                "",
                message,
                Severity.WARNING,
                Subsystem.APPLICATION,
                status,
                correlationId,
                path,
                TimestampFormatter.format(timestamp),
                fieldErrors,
                null);
    }

    /**
     * Builds a contention problem shape whose subsystem is stated rather than assumed.
     *
     * <p>Refactoring Rationale: every conflict this stack emitted went through {@link #of} and therefore
     * carried {@link Subsystem#APPLICATION}, while the published contracts declare a contention refusal
     * as arising in the relational store and their examples show {@code RELATIONAL}. The body and the
     * document describing it disagreed on a component a client can read, and no amount of care at the
     * call site could fix it, because the factory hardcoded the value. This factory takes the subsystem
     * as an argument so a caller that knows where the contention arose says so.
     *
     * <p>Assumptions: the code and the status are NOT arguments. Both are fixed properties of a
     * contention refusal -- {@link #CODE_CONFLICT} and 409 -- so accepting them would let one caller
     * emit a conflict under a different code and defeat the reason the code is a constant. The subsystem
     * is an argument precisely because it genuinely varies: a version comparison this service performed
     * is application-level, whereas one the persistence provider performed at commit, and an integrity
     * constraint the database enforced, are relational.
     *
     * <p>Trade-offs: the field array is accepted even though a conflict is not a validation failure, and
     * the reason is that it is the only satisfiable place in this shape to report the version a caller
     * lost to. Adding a dedicated component for it was the alternative and was rejected: the published
     * contracts seal this shape against unknown properties, so a component added for one refusal would
     * have to be admitted -- and then documented as absent -- on every other. One entry keyed by the
     * version field says the same thing inside the shape every client already parses.
     *
     * @param message the user-visible contention sentence, carried verbatim from its baseline source
     * @param subsystem the part of the platform the contention arose in; must not be {@code null}
     * @param correlationId the inherited correlation identity, or {@code null} when absent
     * @param path the failed request path, or {@code null} when absent
     * @param fieldErrors the field entries reporting the contended values, empty when there are none;
     *     must not be {@code null}
     * @param clock the explicit clock from which the timestamp is read; must not be {@code null}
     * @return a 409 problem shape carrying {@link #CODE_CONFLICT} and the supplied subsystem, never
     *     {@code null}
     * @throws NullPointerException if {@code subsystem}, {@code fieldErrors}, one of its entries or
     *     {@code clock} is {@code null}
     * @throws IllegalArgumentException if the clock yields a year outside the formatter's supported
     *     four-digit range
     */
    public static ApiError ofConflict(String message, Subsystem subsystem, String correlationId,
            String path, List<FieldError> fieldErrors, Clock clock) {
        Objects.requireNonNull(subsystem, "subsystem must not be null");
        Objects.requireNonNull(fieldErrors, "fieldErrors must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ApiError(
                CODE_CONFLICT,
                "",
                message,
                Severity.WARNING,
                subsystem,
                CONFLICT_STATUS,
                correlationId,
                path,
                TimestampFormatter.formatNow(clock),
                fieldErrors,
                null);
    }

    /**
     * Builds the 503 shape reporting that the environment is not currently accepting mutating work.
     *
     * <p>Purpose. The refusal a service returns while the nightly batch chain owns the data -- the
     * target equivalent of a write attempted against the VSAM files {@code app/jcl/CLOSEFIL.jcl} had
     * closed. The window it reports is a planned bracket around the batch chain, not an outage.</p>
     *
     * <p>Assumptions: the severity is stated as {@link Severity#WARNING} rather than derived from the
     * status, and that is the whole reason this factory exists instead of a call to {@link #of}. The
     * derivation in this type maps every status at or above 500 to {@link Severity#CRITICAL}, which is
     * right for a failure and wrong for this: a write refused inside a scheduled window is expected
     * behaviour, and reporting each one as critical would fill an operator's record with critical
     * entries every night for a control working exactly as designed. That is how a severity field stops
     * being read.</p>
     *
     * <p>Assumptions: the code and the status are NOT arguments, for the same reason
     * {@link #ofConflict} fixes its own -- both are properties of this single condition, so accepting
     * them would let one caller emit the same refusal under a different code.</p>
     *
     * <p>Trade-offs: no field-error array is accepted, unlike the contention factory. There is no field
     * to report: nothing about the request is wrong, so an empty array is the only honest value and
     * taking it as an argument would invite a caller to populate it with something else.</p>
     *
     * @param message the user-visible sentence naming the closed window; must name the condition rather
     *     than the parameter or the environment, because neither is something a client can act on
     * @param subsystem the part of the platform the refusal arose in; must not be {@code null}
     * @param correlationId the inherited correlation identity, or {@code null} when absent
     * @param path the refused request path, or {@code null} when absent
     * @param clock the explicit clock from which the timestamp is read; must not be {@code null}
     * @return a 503 problem shape carrying {@link #CODE_WRITES_QUIESCED} and no field entries, never
     *     {@code null}
     * @throws NullPointerException if {@code subsystem} or {@code clock} is {@code null}
     * @throws IllegalArgumentException if the clock yields a year outside the formatter's supported
     *     four-digit range
     */
    public static ApiError ofWritesQuiesced(String message, Subsystem subsystem,
            String correlationId, String path, Clock clock) {
        Objects.requireNonNull(subsystem, "subsystem must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ApiError(
                CODE_WRITES_QUIESCED,
                "",
                message,
                Severity.WARNING,
                subsystem,
                SERVICE_UNAVAILABLE_STATUS,
                correlationId,
                path,
                TimestampFormatter.formatNow(clock),
                List.of(),
                null);
    }

    /**
     * Builds a problem shape carrying structured abend detail.
     *
     * <p>Assumptions: the client-facing message remains independent of the four operator-facing
     * {@link AbendDetail} components. This factory never accepts a throwable or an exception
     * message, so a credential, connection string, rendered stack trace or database vendor code
     * cannot enter the response through an automatic cause-to-payload conversion.
     *
     * @param message the client-safe aggregate message, including {@code null} for message-off
     * @param correlationId the inherited correlation identity, or {@code null} when absent
     * @param path the failed request path, or {@code null} when absent
     * @param abend the structured operator detail to reduce to its client-safe external form; must
     *     not be {@code null}
     * @param clock the explicit clock from which the timestamp is read; must not be {@code null}
     * @return an internal-error problem shape carrying the client-safe external form of
     *     {@code abend}, never {@code null}
     * @throws NullPointerException if {@code abend} or {@code clock} is {@code null}
     * @throws IllegalArgumentException if the clock yields a year outside the formatter's supported
     *     four-digit range
     */
    public static ApiError ofAbend(String message, String correlationId, String path,
            AbendDetail abend, Clock clock) {
        Objects.requireNonNull(abend, "abend must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new ApiError(
                CODE_INTERNAL,
                "",
                message,
                Severity.CRITICAL,
                Subsystem.APPLICATION,
                INTERNAL_SERVER_ERROR_STATUS,
                correlationId,
                path,
                TimestampFormatter.formatNow(clock),
                List.of(),
                abend);
    }

    /**
     * Selects the baseline-compatible operational level for a factory-built HTTP failure.
     *
     * <p>Assumptions: client-correctable 4xx outcomes use warning while server-side 5xx outcomes use
     * critical, matching the distinction between warning and critical already present at lines 28
     * and 29 of {@code CCPAUERY.cpy}. Lower statuses use information because this helper is also
     * reachable through the general factory, while no factory invents a fifth level.
     *
     * @param status the HTTP status whose operational level is required
     * @return {@link Severity#CRITICAL} for 5xx, {@link Severity#WARNING} for 4xx, otherwise
     *     {@link Severity#INFO}
     */
    private static Severity severityForStatus(int status) {
        if (status >= INTERNAL_SERVER_ERROR_STATUS) {
            return Severity.CRITICAL;
        }
        if (status >= 400) {
            return Severity.WARNING;
        }
        return Severity.INFO;
    }
}
