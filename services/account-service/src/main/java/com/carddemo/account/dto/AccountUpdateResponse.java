package com.carddemo.account.dto;

import com.carddemo.common.error.ApiError;
import java.util.List;

/**
 * Response body of the account update endpoint, carrying its success, its field-validation
 * rejection and its optimistic-lock conflict in one shape.
 *
 * <p>The endpoint is {@code com.carddemo.account.api.AccountController} and the producer is
 * {@code com.carddemo.account.service.AccountUpdateService}. On success this shape echoes the
 * committed state, which is what {@code app/cbl/COACTUPC.cbl} did when it redisplayed the update
 * map after its one commit: the group identifier is written back at lines 2749, 2824 and 2921 and
 * the first telephone's three parts at lines 2939 to 2941, and the sole success-path
 * {@code SYNCPOINT} preceding all of it is at line 953. On a field-validation rejection this shape
 * carries the accumulated per-field entries beside one latched aggregate message. On a conflict it
 * carries the current server state, because the instruction published as
 * {@link ApiError#COACTUPC_RECORD_CHANGED} and declared at lines 521 and 522 of that program asks
 * the operator to review a record, and there is nothing to review without one.
 *
 * <p>The two message channels are independent, and one baseline paragraph proves it.
 * {@code 3250-SETUP-INFOMSG.} at line 2955 of {@code app/cbl/COACTUPC.cbl} moves the
 * forty-character informational variable to the screen's informational slot at line 2979 and, one
 * statement later at line 2981, moves the seventy-five-character aggregate to the screen's error
 * slot. Components two and three are those two channels, and they are never merged.
 *
 * <p>Assumptions: AR-01 fixes the arity at five and lets one shape serve all three outcomes rather
 * than three shapes serve one outcome each. The success echo is evidenced by the redisplay moves at
 * lines 2749, 2824, 2921 and 2939 to 2941 of {@code app/cbl/COACTUPC.cbl}; the conflict payload by
 * the message at lines 521 and 522 of the same program; and the rejection payload by the graded
 * soft-reject discipline recorded at lines 558 to 565 of {@code tests/README.md}, where a
 * business-rule reject is a data-quality outcome rather than a failure of the program, which places
 * a field-validation rejection in the client-error range and never in the server-error range. Three
 * separate shapes were rejected because a client would then have to decide which shape it was
 * holding before it could read either message channel, and both channels are identical in all three
 * outcomes.
 *
 * <p>Assumptions: AR-02 declares no amount-bearing component, so this file needs neither the shared
 * exact-decimal money type nor its serialiser. The echo in component five is
 * {@link AccountUpdateRequest}, whose forty-three components are every one a {@code String} carrying
 * digits, and neither the aggregate message nor a per-field entry carries an amount. The five
 * amounts of the account master, declared {@code PIC S9(10)V99} at lines 7, 8, 9, 13 and 14 of
 * {@code app/cpy/CVACT01Y.cpy}, are money in the entity and in {@link AccountViewResponse}, which
 * declares five money-typed components and therefore does import the shared exact-decimal type; they
 * are not money on this response. Nothing here is ever routed through IEEE-754 binary floating
 * point, which the architecture rules in {@code services/common-lib/src/test/java} assert for the
 * whole tree rather than for the money package alone.
 *
 * <p>Assumptions: AR-03 takes component two's provenance from the program rather than from the map.
 * A distinct informational variable exists, {@code 05 WS-INFO-MSG PIC X(40).} at line 463 of
 * {@code app/cbl/COACTUPC.cbl}, with its absent state covering both blanks and low values at lines
 * 464 and 465, its six values at lines 466 to 477, and its move to the screen at line 2979. The
 * screen slot it fills is wider, {@code 02 INFOMSGI PIC X(45).} at line 318 of
 * {@code app/cpy-bms/COACTUP.CPY}, and sits beside {@code 02 ERRMSGI PIC X(78).} at line 324. The
 * view program declares the same forty-character variable at line 110 of
 * {@code app/cbl/COACTVWC.cbl}, so the two programs agree on this channel and no width is inferred
 * for it. That the update program also carries informational text on the aggregate channel, as
 * {@code Looks Good.... so far} at lines 527 and 528 shows, does not merge the channels: line 2979
 * and line 2981 write to different slots.
 *
 * <p>Assumptions: AR-04 keeps one latched aggregate beside many accumulated entries. The latch is
 * the guard {@code IF WS-RETURN-MSG-OFF} wrapping every {@code STRING ... INTO WS-RETURN-MSG} in
 * {@code app/cpy/CSUTLDPY.cpy}, fourteen guards paired one to one with fourteen assignments, among
 * them the guards at lines 218, 233, 263 and 305. The first error therefore wins the aggregate and
 * no later error overwrites it, whereas the per-field flags immediately above each guard, at lines
 * 213 to 217, 228 to 232, 259 to 262 and 300 to 304, are set outside the guard and accumulate. Two
 * consequences bind every consumer of this record: the aggregate is never derived by joining
 * component four, and component four is never assumed to hold exactly one entry because component
 * three is populated. The fan-out is not uniform either, two field flags on the first two of those
 * spans and three on the last two, so no consumer may infer a count.
 *
 * <p>Assumptions: AR-05 honours the program-side width of seventy-five characters, declared
 * {@code 05 WS-RETURN-MSG PIC X(75).} at line 479 of {@code app/cbl/COACTUPC.cbl} and identically at
 * line 117 of {@code app/cbl/COACTVWC.cbl}; the screen slot at line 324 of
 * {@code app/cpy-bms/COACTUP.CPY} is wider and is not the contract. That width is already published
 * as {@link ApiError#MESSAGE_RENDERING_WIDTH} and is not restated here. Three further widths coexist
 * and are never collapsed into it: fifty at lines 18 to 21 of {@code app/cpy/CSMSG01Y.cpy},
 * seventy-two at line 28 of {@code app/cpy/CSMSG02Y.cpy} and eighty at line 86 of
 * {@code app/cbl/CSUTLDTC.cbl}, published in turn as {@link ApiError#COMPACT_MESSAGE_WIDTH},
 * {@link ApiError#ABEND_MESSAGE_WIDTH} and {@link ApiError#DATE_DIAGNOSTIC_WIDTH}. This record
 * documents the widths and truncates nothing to them, because truncation would alter text that
 * transformation rule T8 carries across character for character.
 *
 * <p>Assumptions: the two empty states stay distinguishable, so neither message component is trimmed
 * or blanked here. The baseline does not agree with itself about which byte means absent: the
 * informational channel treats blanks and low values alike at lines 464 and 465 of
 * {@code app/cbl/COACTUPC.cbl}, that program's own aggregate sentinel is blanks alone at line 480,
 * the seventy-five-character screen line carries a low-values sentinel on its return message at line
 * 30 of {@code app/cpy/CVCRD01Y.cpy} but none at all on the error message beside it at line 28, and
 * the abend block initialises to blanks at lines 23, 25, 27 and 29 of {@code app/cpy/CSMSG02Y.cpy}.
 * This record therefore stores what its producer supplied and adopts the shared kernel's convention,
 * in which an absent channel is {@code null} and a populated but empty channel is the empty string.
 * Collapsing both to one string would erase a distinction four baseline declarations preserve.
 *
 * <p>Assumptions: AR-06 replaces three comparison regimes with one monotonic version compare, a
 * structural divergence that leaves the operator-visible behaviour alone. The baseline's
 * before-image check is {@code 9700-CHECK-CHANGE-IN-REC.} at line 4109 of
 * {@code app/cbl/COACTUPC.cbl}, ending at its exit label on line 4193, and it compares in three
 * different ways. Upper-case folded on both sides for nine customer fields, at lines 4152 to 4167
 * and 4172 to 4173. Lower-case folded for the account group identifier alone, at lines 4139 and
 * 4140, which hold the only two {@code FUNCTION LOWER-CASE} calls in that program's 4236 lines
 * against sixty-four uses of the trimming function. Raw for everything else: the status on line
 * 4115, the five amounts against their numeric {@code REDEFINES} views on lines 4117, 4119, 4121,
 * 4123 and 4125, the three account dates by reference modification on lines 4127 to 4129, 4131 to
 * 4133 and 4135 to 4137, the postal code on line 4168, the national identifier on line 4171, and the
 * electronic-funds-transfer account, primary-holder indicator and credit score on lines 4181 to
 * 4186. A difference sets the flag on line 4143 and branches on line 4144; equality continues on
 * line 4141, and the customer half mirrors that on lines 4187, 4189 and 4190. Two details show the
 * predicate is not even internally uniform: the telephones are compared whole on lines 4169 and 4170
 * yet part by part on lines 1748 to 1750, and the date bridge on lines 4174 to 4179 reads the record
 * side at positions 1, 6 and 9 while reading the before-image side at positions 1, 5 and 7, because
 * one side carries separators and the other does not. A version column expresses none of that: it
 * reports a conflict on any committed write, including a pure letter-case change to a name that
 * lines 4152 to 4167 would have judged unchanged. The divergence is in what raises the conflict and
 * never in what the operator reads, so the message stays
 * {@link ApiError#COACTUPC_RECORD_CHANGED}. Three failure conditions stay distinct behind it: lines
 * 517 to 520 are lock-acquisition failures of forty and forty-one characters, lines 521 and 522 are
 * the before-image mismatch, and lines 523 and 524 are a failed rewrite, whose rollback path runs
 * from line 4095 to line 4103 with its rollback on lines 4099 to 4101.
 *
 * <p>Refactoring Rationale: AR-07 keeps the blank marker but makes it output-only, because the
 * mechanism that used to carry it in the other direction no longer exists. The highlight macro
 * {@code app/cpy/CSSETATY.cpy} writes to two different subfields: the red attribute goes to the
 * field's colour subfield at lines 21 and 22, while the literal {@code '*'} goes to the field's
 * output subfield at lines 24 and 25, under the inner blank test on line 23. On a terminal the
 * output subfield was echoed back as input, which is why lines 1051 and 1052 of
 * {@code app/cbl/COACTUPC.cbl} accept an incoming {@code '*'} as a blank field; a stateless request
 * has no echo, so nothing can arrive bearing it. The marker is consequently derived for rendering
 * from an entry's state, by {@link ApiError.FieldError#screenMarker()}, rather than transported as
 * data. Marker and state also stay apart: the value the baseline stores in the flag byte for a blank
 * field is {@code 'B'}, per lines 43 to 57 of {@code app/cpy/CSUTLDWY.cpy}, and {@code '*'} is only
 * ever presentation.
 *
 * <p>Assumptions: AR-08 requires component four to key an entry to the individual parts of a
 * composite field and not only to the composite. Under {@code 05 WS-NON-KEY-FLAGS.} at line 191 of
 * {@code app/cbl/COACTUPC.cbl}, which runs to line 352, each date group fans out into three
 * component flags: date of birth at lines 219, 223 and 227 under its group on line 216, open date at
 * lines 237, 241 and 245 under line 235, expiry at lines 251, 255 and 259 under line 249, and
 * reissue at lines 265, 269 and 273 under line 263. The telephones fan out the same way, at lines
 * 318, 322 and 326 and at lines 333, 337 and 341, and the national identifier at lines 135, 139 and
 * 143. The highlight macro is nevertheless applied per screen field, thirty-nine times between lines
 * 3208 and 3432, so a client must be able to attach help text to a year, a month, a day, an area
 * code, a prefix, a line number or one identifier part on its own. The groups are not uniform and
 * this record normalises none of it: the date-of-birth group carries a group-level valid condition
 * on line 218 where the open-date group has only an invalid one on line 236, and the expiry group's
 * name on line 249 lacks the prefix its three siblings carry.
 *
 * <p>Assumptions: AR-09 records that one editable field has no error key at all. A search of
 * {@code app/cbl/COACTUPC.cbl} for a validation flag belonging to the account group identifier
 * returns nothing, yet the field round-trips completely: read into the before-image at line 3847,
 * taken from the map at line 1217, written to the update record at line 4002, and compared for
 * change at lines 1698 to 1700 and again at lines 4139 and 4140. Component four therefore has no
 * entry to carry for that field, and inventing one would assert a validation the baseline does not
 * perform.
 *
 * <p>Alternatives Considered: AR-10 types the echo as {@link AccountUpdateRequest} after rejecting
 * three alternatives. Restating its forty-three components inside this record was rejected because
 * one shape would then have two homes and this type Javadoc would owe forty-three further parameter
 * tags. Omitting the echo was rejected because the conflict instruction at lines 521 and 522 of
 * {@code app/cbl/COACTUPC.cbl} asks for a review the client cannot perform against state it does not
 * hold. Returning {@link AccountViewResponse} was rejected because the two shapes genuinely differ:
 * the view keeps whole ten-character dates, declared at lines 72, 84 and 96 of
 * {@code app/cpy-bms/COACTVW.CPY} and moved whole at lines 487 to 489 and 507 of
 * {@code app/cbl/COACTVWC.cbl}, and one composed national identifier assembled with hyphens at lines
 * 496 to 504, whereas the update form splits each date into year, month and day and the identifier
 * into three parts. Handing a view shape back to an update form would make the client re-split both.
 * The baseline left a note of exactly this kind at this exact site, at lines 4148 to 4150, recording
 * that its customer check was split in two for readability and might later update only the file whose
 * data had changed.
 *
 * <p>Refactoring Rationale: AR-11 severs the gate that used to decide whether a field was
 * highlighted at all, so field-error rendering is driven purely by this response body. Line 20 of
 * {@code app/cpy/CSSETATY.cpy} adds a third conjunct to the highlight test on lines 18 and 19,
 * requiring the pseudo-conversational context indicator declared at line 29 of
 * {@code app/cpy/COCOM01Y.cpy}, whose two condition names sit on lines 30 and 31, to hold its second
 * value. A stateless handler has no such indicator, so no component of this record restates it and
 * none may be added: whether a field is marked follows from whether component four holds an entry
 * for it, and from nothing else. The same severance is why the marker in AR-07 travels outward only.
 *
 * <p>Alternatives Considered: AR-12 consumes {@link ApiError.FieldError} rather than declaring an
 * entry type in this package. A local record would give one contract two incompatible shapes, and
 * the entry carries domains this package does not own: the four severities and six subsystems of
 * {@code 01 ERROR-LOG-RECORD.} at line 19 of
 * {@code app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy}, at lines 26 to 29 and 31 to 36, with
 * that record's own correlation identifier at line 40. {@link ApiError} owns those, and the shared
 * validation enumeration owns the three field states, so neither is redeclared here. No key-paged
 * envelope is declared or imitated either: component four is the complete set of fields that failed
 * one submission, not a window onto a longer sequence, so it needs no cursor of any kind.
 *
 * <p>Alternatives Considered: AR-13 rejects three further components. A nested abend detail was
 * rejected because the baseline's abend block, {@code 01 ABEND-DATA.} at line 21 of
 * {@code app/cpy/CSMSG02Y.cpy} whose four fields on lines 22, 24, 26 and 28 total 134 bytes, already
 * has exactly one Java home inside {@link ApiError}, and two homes for one contract are worse than
 * one. A version or entity-tag component was rejected because the optimistic-lock version is
 * transport metadata and belongs in a header rather than in a body a client may hand back. An
 * outcome enumeration was rejected because the status line already carries the outcome, published as
 * {@link ApiError#CONFLICT_STATUS} with {@link ApiError#CODE_CONFLICT} beside
 * {@link ApiError#CODE_VALIDATION}, and a second copy inside the body could disagree with the first.
 *
 * <p>Trade-offs: AR-14 returns the sensitive identifiers masked, which the baseline did not do.
 * {@code 05 CUST-SSN PIC 9(09).} and {@code 05 CUST-GOVT-ISSUED-ID PIC X(20).} at lines 17 and 18 of
 * {@code app/cpy/CVCUS01Y.cpy} are held encrypted in the target and rendered masked, whereas line 519
 * of {@code app/cbl/COACTVWC.cbl} moves the government-issued identifier to the screen unmasked, so
 * masking is an addition rather than a carried-forward behaviour. The compromise accepted is that a
 * masked echo cannot be resubmitted as it stands, so a client changing one of those fields supplies
 * it again; the alternative was a response that reproduces a whole identifier on every update, which
 * is a worse exposure than an extra keystroke is an inconvenience. Masking is applied once, by
 * {@code com.carddemo.account.mapper}, before any value reaches this record, which is the same rule
 * that makes {@link AccountViewResponse} name its equivalents for the masking they already carry.
 * This record neither applies nor reverses it, and it carries no card number and no verification
 * value at all.
 *
 * <p>Trade-offs: AR-15 makes component four non-null, copied and unmodifiable, and refuses an absent
 * list rather than coercing one. The copy costs a single allocation per response and buys a published
 * contract that a retained caller reference cannot mutate afterwards, while an always-present array
 * removes an absence branch from every consumer. Refusal is where this record parts company with the
 * shared kernel, whose own constructor turns an absent list into an empty one at line 463 of
 * {@code services/common-lib/src/main/java/com/carddemo/common/error/ApiError.java}: that envelope is
 * assembled by shared advice which cannot know whether any field failed, whereas this body is
 * assembled by one service method on all three outcomes, where an empty array is a known state and
 * never an unknown one. Coercing here would turn a producer defect into a response that silently
 * reports no field errors, so the constructor fails at the construction site instead.
 *
 * <p>Assumptions: AR-16 records the baseline's inconsistencies without resolving any of them. The
 * condition name for a missing card cross-reference is declared twice in
 * {@code app/cbl/COACTUPC.cbl}, at lines 497 and 513, with different text each time. The flag-suffix
 * convention has four coexisting variants: a singular suffix on the first component of each composite
 * group at lines 219, 237, 251, 265, 318 and 333, no suffix on the second and third components, and a
 * plural suffix on all three identifier components at lines 135, 139 and 143 as well as on several
 * scalars. The key-filter flags at lines 183 to 186 and 187 to 190 use a different sentinel alphabet
 * from the non-key flags below them, and lines 193 and 350 use neither, holding the domain value
 * itself when the field is valid; the shared validation enumeration reduces all three alphabets to
 * three named states with an error predicate spanning two of them, which is a structural divergence
 * of the same family as AR-06 and is the reason a blank field is a kind of error here rather than a
 * third outcome, exactly as the single disjunction at lines 18 and 19 of
 * {@code app/cpy/CSSETATY.cpy} treats it. Finally, back in {@code app/cbl/COACTUPC.cbl}, line 168
 * declares a one-character change flag with its conditions on lines 169 and 170 and is not the
 * message-valued condition on line 521 of the same program; the two are unrelated constructs that
 * happen to share a subject.
 *
 * @param accountId the eleven-character account identifier this response is about, carried as digits
 *     in a {@code String} because the update map declares it alphanumeric,
 *     {@code 02 ACCTSIDI PIC X(11).} at line 60 of {@code app/cpy-bms/COACTUP.CPY}, and because the
 *     baseline itself holds such values as characters and views them as numbers only for arithmetic:
 *     line 671 of {@code app/cbl/COACTUPC.cbl} declares the before-image identifier
 *     {@code PIC X(11)} and lines 672 and 673 redefine it {@code PIC 9(11)}, precisely as lines 675
 *     to 677 do for an amount and lines 754 to 756 for a credit score. The view map declares the same
 *     field with an eleven-digit numeric picture at line 60 of {@code app/cpy-bms/COACTVW.CPY}, and
 *     the stricter alphanumeric form of the update map is the one taken. May be {@code null} only
 *     when the request carried no identifier to report back
 * @param informationMessage the {@code String} holding the informational channel of the screen,
 *     whose slot is {@code 02 INFOMSGI PIC X(45).} at line 318 of {@code app/cpy-bms/COACTUP.CPY} and
 *     whose program-side source is {@code 05 WS-INFO-MSG PIC X(40).} at line 463 of
 *     {@code app/cbl/COACTUPC.cbl}, moved to that slot at line 2979; {@code null} when the channel is
 *     unset and the empty string when it is set and empty, and never trimmed
 * @param returnMessage the {@code String} holding the single latched aggregate message,
 *     {@code 05 WS-RETURN-MSG PIC X(75).} at line 479 of {@code app/cbl/COACTUPC.cbl}, carrying at
 *     most one of that field's condition values verbatim, such as
 *     {@link ApiError#COACTUPC_RECORD_CHANGED} from lines 521 and 522 on a conflict or
 *     {@code Looks Good.... so far} from lines 527 and 528, whose four dots are part of the text;
 *     {@code null} when unset, never trimmed, and never assembled by joining {@code fieldErrors}
 * @param fieldErrors the {@code List} of {@link ApiError.FieldError} entries accumulated for this
 *     submission, keyed to the thirty-six per-screen-field validation groups at lines 191 to 352 of
 *     {@code app/cbl/COACTUPC.cbl} and to their individual date, identifier and telephone parts; must
 *     not be {@code null}, is empty when no field failed, and is held as an unmodifiable copy
 * @param account the committed state on success and the current server state on a conflict, typed
 *     {@link AccountUpdateRequest} so that the shape a client submitted is the shape it reads back,
 *     with each date split into year, month and day and the national identifier into three parts; may
 *     be {@code null} when a request was rejected before any record was read
 */
public record AccountUpdateResponse(
        String accountId,
        String informationMessage,
        String returnMessage,
        List<ApiError.FieldError> fieldErrors,
        AccountUpdateRequest account) {

    /**
     * Seals the per-field error array and refuses an absent one.
     *
     * <p>Trade-offs: one call performs all three jobs the array contract needs. It refuses
     * {@code null}, it copies so that a caller holding the original list cannot mutate a published
     * response, and it yields an unmodifiable view. The alternative was to write the three as
     * separate checks, using an explicit null test of the kind the shared kernel writes at lines 458
     * and 459 of {@code services/common-lib/src/main/java/com/carddemo/common/error/ApiError.java};
     * that requires the {@code java.util.Objects} import which that file declares at its line 10,
     * and the two-import surface of this file is deliberate, since every other type named here is
     * either in this package or reached through {@link ApiError}. The compromise accepted is that
     * the refusal arrives as an unnamed argument failure rather than a message naming the component,
     * which is the price of the smaller surface.
     *
     * <p>Assumptions: the three remaining components are deliberately left unvalidated because each
     * has a legitimate absent state. An unset informational or aggregate channel is real, which is
     * why lines 464, 465 and 480 of {@code app/cbl/COACTUPC.cbl} distinguish an unset channel from an
     * empty one, and an absent echo is real on a rejection that never reached a record. Rejecting any
     * of them would impose a constraint the baseline does not, and would make the rejection outcome
     * unrepresentable.
     *
     * @param accountId the account identifier {@code String} to retain exactly as supplied
     * @param informationMessage the informational-channel {@code String} to retain exactly as
     *     supplied, including its unset and empty states
     * @param returnMessage the latched aggregate-message {@code String} to retain exactly as supplied,
     *     including its unset and empty states
     * @param fieldErrors the {@code List} of {@link ApiError.FieldError} entries to copy into an
     *     unmodifiable list; must not be {@code null}
     * @param account the committed or current state, an {@link AccountUpdateRequest}, to retain
     *     exactly as supplied
     * @throws NullPointerException if {@code fieldErrors} is {@code null} or contains a {@code null}
     *     entry
     */
    public AccountUpdateResponse {
        // WHAT: replace the incoming reference with an unmodifiable copy of its contents.
        // WHY : Trade-offs: List.copyOf refuses a null argument and a null element and returns an
        //       unmodifiable copy in one call, so the array contract holds for every instance
        //       without a second import. See AR-15 on the type above for why an absent list is
        //       refused here rather than turned into an empty one as ApiError does at its line 463.
        fieldErrors = List.copyOf(fieldErrors);
    }
}
