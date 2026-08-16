package com.carddemo.transaction.dto;

import java.util.Objects;

/**
 * The data members a copy-last turn lifted out of the stored row, as the screen must now hold them.
 *
 * <p><b>Purpose.</b> {@code COPY-LAST-TRAN-DATA} at {@code app/cbl/COTRN02C.cbl} lines 471 to 495 does
 * not write anything and does not answer with a figure: it moves ELEVEN values out of the row it read
 * INTO the unprotected map fields the operator is looking at -- the type code, category code and source
 * at lines 482 to 484, the amount rendered through the edited picture at lines 481 and 485, the
 * description at 486, the two dates at 487 and 488 and the four merchant members at 489 to 492 -- and
 * then performs {@code PROCESS-ENTER-KEY} at line 495. Everything the confirming turn writes is
 * therefore read off the SCREEN, and the row that was copied is never consulted again. This shape is
 * that screen state, published so a browser can adopt it.
 *
 * <p>⚠️ Refactoring Rationale: this shape exists because the copy-last operation used to answer with the
 * normalised amount alone, so a browser could restore exactly one of the eleven values and had no way to
 * carry the other ten. The consequence was not a display gap but a WRITE hazard, and it was reported as
 * one: a screen holding only the amount had to reach the copy operation a SECOND time to write, which
 * re-resolves "the most recently stored transaction" -- so a row appended between the operator's preview
 * and their confirmation silently replaced what they had been shown. Publishing the copied members is
 * what lets the confirming turn go through the ordinary capture operation carrying the previewed values,
 * which resolves "last" exactly once.
 *
 * <p>Assumptions: this shape carries TEN members and the eleventh copied value is the {@code amount}
 * that {@link TransactionAddPreview} already publishes beside it. The split is the reference's own: ten
 * of the eleven moves are plain field-to-field moves, while the amount is the single value TRANSFORMED
 * on its way to the screen -- line 481 renders {@code TRAN-AMT} through
 * {@code WS-TRAN-AMT-E PIC +99999999.99} and line 485 moves that edited rendering, not the stored
 * figure. The preview's amount member is that normalised value, so repeating it here would publish one
 * figure twice in one body and invite the two copies to be read as different things.
 *
 * <p>Assumptions: the two dates are DATES and not timestamps, at the declared width of the map fields
 * rather than of the columns. Lines 487 and 488 move a {@code PIC X(26)} record field into a
 * {@code PIC X(10)} screen field, which truncates to the leftmost ten characters; the service performs
 * the same narrowing when it builds the copied submission, so what is published here is what the
 * reference would have painted.
 *
 * <p>Assumptions: no member here is a key. The account identifier, the card number and the confirmation
 * are the caller's own and the copy block moves nothing into them, so a browser adopting this shape
 * leaves its two key controls and its confirmation control exactly as the operator left them -- which is
 * what the reference does, and what makes the copied capture land against the account the operator was
 * already working on rather than against the account the copied row belonged to.
 *
 * <p>Alternatives Considered: publishing this as a third response shape of its own, alongside the
 * preview and the capture, with the copy-last operation's {@code 200} pointing at it. Rejected because
 * the two bodies would then differ in exactly one member while duplicating the other three, the sealed
 * outcome interface would have to admit a third permitted implementation, and both handlers' published
 * responses would have to be split -- all to avoid one nullable member. Carrying it on the preview
 * keeps one shape, one status mapping and one client path, and the inclusion policy makes the member's
 * absence explicit as a null rather than leaving a client to guess from a missing property.
 *
 * <p>Trade-offs: the consequence of that choice is that the ordinary capture operation's unconfirmed
 * answer publishes this member holding null on every turn, because it copied nothing. That is stated on
 * the member itself rather than left to be discovered, and it is the same shape every other nullable
 * member in this package has: {@code carddemo-common-defaults.yml} pins
 * {@code default-property-inclusion} to {@code always}, so a member that may hold no value is required
 * and nullable and is never absent.
 *
 * @param sourceTransactionId the identifier of the row these members were copied out of, from
 *     {@code TRAN-ID PIC X(16)} at line 5 of {@code app/cpy/CVTRA05Y.cpy}; sixteen characters, never
 *     {@code null}. It is what makes the preview a STABLE reference to one stored row: a caller holding
 *     it can tell that two copy turns copied the same row, and an audit of the appended capture can name
 *     the row it was taken from. Assumptions: it is published as itself and not sealed, because every
 *     other transaction operation publishes it as itself -- the detail operation is keyed by it -- so
 *     sealing it here would introduce a second spelling of one identity while masking nothing: a ledger
 *     identifier is neither a primary account number nor sensitive authentication data
 * @param typeCode the copied transaction type, from line 482; {@code TRAN-TYPE-CD PIC X(02)} at line 6
 *     of {@code app/cpy/CVTRA05Y.cpy}, never {@code null}
 * @param categoryCode the copied transaction category, from line 483; {@code TRAN-CAT-CD PIC 9(04)} at
 *     line 7, carried as characters because the map field it is painted into is a character field, never
 *     {@code null}
 * @param source the copied source, from line 484; {@code TRAN-SOURCE PIC X(10)} at line 8, never
 *     {@code null}
 * @param description the copied description, from line 486; {@code TRAN-DESC PIC X(100)} at line 9,
 *     never {@code null}
 * @param merchantId the copied merchant identifier, from line 489; {@code TRAN-MERCHANT-ID PIC 9(09)} at
 *     line 11, published zero-padded to its nine declared positions because the map field is nine
 *     characters wide and the reference moves a zoned display field into it, never {@code null}
 * @param merchantName the copied merchant name, from line 490; {@code TRAN-MERCHANT-NAME PIC X(50)} at
 *     line 12, never {@code null}
 * @param merchantCity the copied merchant city, from line 491; {@code TRAN-MERCHANT-CITY PIC X(50)} at
 *     line 13, never {@code null}
 * @param merchantZip the copied merchant postal code, from line 492; {@code TRAN-MERCHANT-ZIP PIC X(10)}
 *     at line 14, never {@code null}
 * @param originDate the copied origination date, from line 487, being the leftmost ten characters of
 *     {@code TRAN-ORIG-TS PIC X(26)} at line 15 as an ISO date, never {@code null}
 * @param processDate the copied processing date, from line 488, being the leftmost ten characters of
 *     {@code TRAN-PROC-TS PIC X(26)} at line 16 as an ISO date, never {@code null}
 *
 * <p>⚠️ Refactoring Rationale: the two RESOLVED key values are NOT members of this block. They were
 * declared here, on the reading that the reference re-sends one screen and this block is that screen; two
 * shapes were then authored for the same answer and both put them somewhere other than where every turn
 * can reach them. They are components of {@link TransactionAddPreview} instead, because the reference
 * resolves and repaints both key fields on EVERY turn -- {@code app/cbl/COTRN02C.cbl} L166 performs
 * {@code VALIDATE-INPUT-KEY-FIELDS} for the Enter arm and L473 for the copy arm, and that paragraph's
 * account arm moves the cross-reference's card number into {@code CARDNINI} at L209 while its card arm
 * moves the account identifier into {@code ACTIDINI} at L221 -- whereas this block is attached to the copy
 * turn alone. Holding them here published the repaint on one of the two turns that perform it, so an
 * ordinary capture left a client rendering the key the operator typed rather than the key the row will
 * carry.</p>
 *
 * <p>Alternatives Considered: publishing them in a separate identity block beside this one. Rejected for
 * the reason promotion is preferred: two blocks would both describe one screen state, and a client would
 * have to choose which document it was confirming. Promoting them to the preview keeps one answer per
 * call and makes the repaint available on every turn that performs it.</p>
 */
public record CopiedTransactionData(
    // WHY : Assumptions: the component order below is the property order of the CopiedTransactionData
    //       schema in openapi/transaction-api.yaml, and the source identifier leads because it is the
    //       one member that names the row rather than describing it. TransactionApiContractTest asserts
    //       the names and the set rather than the order, so this is for a reader comparing the two
    //       documents side by side.
    String sourceTransactionId,
    String typeCode,
    String categoryCode,
    String source,
    String description,
    String merchantId,
    String merchantName,
    String merchantCity,
    String merchantZip,
    String originDate,
    String processDate) {

    /**
     * Reads the ten copied members off the submission the copy built, pairing them with the source row.
     *
     * <p>Purpose: the service already assembles a complete submission from the stored row before it
     * validates and answers, so the copied values exist in one place and this factory publishes THAT
     * rather than reading the row a second time. Reading the row again would let the published members
     * drift from the members that were validated and, on a confirmed turn, written.
     *
     * <p>Assumptions: the submission passed in is the COPIED one and not the caller's own. Its ten data
     * members are the stored row's; its two key members and its confirmation are the caller's, and none
     * of those three is read here.
     *
     * @param copied the submission the copy block produced, whose data members are the stored row's;
     *     must not be {@code null}
     * @param sourceTransactionId the identifier of the row that was copied; must not be {@code null}
     * @return the copied screen state, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static CopiedTransactionData of(TransactionAddRequest copied,
            String sourceTransactionId) {
        Objects.requireNonNull(copied, "copied must not be null");
        Objects.requireNonNull(sourceTransactionId, "sourceTransactionId must not be null");

        return new CopiedTransactionData(sourceTransactionId, copied.typeCode(),
                copied.categoryCode(), copied.source(), copied.description(), copied.merchantId(),
                copied.merchantName(), copied.merchantCity(), copied.merchantZip(),
                copied.originDate(), copied.processDate());
    }

    /**
     * Renders this shape WITHOUT the copied business values.
     *
     * <p>Purpose. {@code docs/architecture/observability.md} prohibits a diagnostic rendering of the
     * values a record carries, and nine of the eleven members here are exactly that: a merchant's name,
     * city and postal code, a free-text description and the codes that classify a ledger movement. The
     * compiler-generated rendering would have written all of them into any log line, stack trace or
     * assertion message that touched this shape.</p>
     *
     * <p>Assumptions: the source identifier is KEPT, because it is the one member that is neither a
     * business value nor derived from one -- it names the row rather than describing it, every other
     * transaction operation publishes it, and it is what makes a log line about a copy turn answerable
     * at all: without it the entry records that something was copied and not what from.</p>
     *
     * @return a rendering carrying the source identifier alone; never {@code null}
     */
    @Override
    public String toString() {
        return "CopiedTransactionData[sourceTransactionId=" + this.sourceTransactionId + ']';
    }
}
