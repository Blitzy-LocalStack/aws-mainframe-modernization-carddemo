package com.carddemo.authorization.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Carries the fraud state a fraud-marking request asks the service to set.
 *
 * <p><strong>Purpose.</strong> The authorization row is named by the operation's path selector and by
 * nothing else; this body carries only the state to end in. A state rather than an operation is what
 * makes a retried request a no-op instead of a second change.
 *
 * <p>Alternatives Considered: repeating the row's identity in the body -- the account identifier and
 * the two key components -- so that it could be compared against the path selector. Rejected: the
 * selector already names the row, so a second naming adds a disagreement to detect rather than a
 * capability, and a client would be supplying identity the service must not take from it.
 *
 * <p>Alternatives Considered: accepting the persisted detail segment, as the baseline does. The
 * baseline passes the whole 200-byte {@code COPY CIPAUDTY} area through {@code DFHCOMMAREA} at
 * {@code cbl/COPAUS2C.cbl} L77-L78, but its caller is a sibling program reached by
 * {@code EXEC CICS LINK} inside one CICS task, whereas this caller is a browser over HTTP. Accepting
 * the segment would let that browser choose persisted content, so the service re-reads the detail
 * instead.
 *
 * <p>Assumptions: the customer identifier the fraud row records is resolved server-side from the
 * account's summary row, where {@code db/migration/V1__authorization.sql} holds it as a not-null
 * column. The baseline takes it from the caller -- {@code cbl/COPAUS2C.cbl} L139 moves
 * {@code WS-CUST-ID} into the fraud row's {@code CUST_ID} column -- and that is precisely the input
 * this contract declines to accept from a browser.
 *
 * <p>Trade-offs: this record must never be logged or echoed as a whole. It names no identity of its
 * own, so a rendering of it is safe only for as long as that stays true, and the prohibition is
 * recorded here rather than left to be inferred.
 *
 * <p><strong>Return value.</strong> This declaration is an input value type and declares no operation
 * with a return value.
 *
 * @param action the one-character {@code WS-FRD-ACTION PIC X(01)} command at
 *     {@code cbl/COPAUS2C.cbl} L80: {@code F} means {@code WS-REPORT-FRAUD} at L81 and {@code R}
 *     means {@code WS-REMOVE-FRAUD} at L82. It is distinct from the adjacent
 *     {@code WS-FRD-UPDATE-STATUS} at L83, where {@code F} means update failed at L85, so this
 *     component and {@code FraudMarkResponse}'s {@code updateStatus} never share a type or a
 *     validation constant
 */
public record FraudMarkRequest(
        @NotNull @Pattern(regexp = ACTION_DOMAIN) String action) {

    /**
     * The two commands the baseline's fraud action admits, one character each.
     *
     * <p>Assumptions: this domain is closed by the two condition names at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L81 and L82 and by nothing else, so a
     * third character is not an unhandled case but a value the reference program cannot represent. It
     * is case-sensitive because a COBOL condition name compares the bytes of its field against the
     * literal it was declared with, so a lower-case form would satisfy neither condition and would
     * still reach the fraud column.</p>
     */
    private static final String ACTION_DOMAIN = "[FR]";
}
