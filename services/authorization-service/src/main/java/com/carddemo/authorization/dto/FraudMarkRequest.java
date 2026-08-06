package com.carddemo.authorization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Carries the pending-authorization row key, the customer it is filed under and the fraud-marking
 * command accepted at the HTTP boundary.
 *
 * <p><strong>Purpose.</strong> This request names one pending authorization, names the customer
 * the fraud row records against it, and asks the service either to report or to remove that row's
 * fraud mark, without accepting the persisted detail payload from a browser.
 *
 * <h2>Boundary shape</h2>
 *
 * <p>Alternatives Considered: The baseline passes the complete 200-byte {@code COPY CIPAUDTY}
 * segment through {@code DFHCOMMAREA} at {@code cbl/COPAUS2C.cbl} L77-L78. Mirroring that segment
 * in the request body would save the server one read, but the callers have different trust
 * boundaries: the baseline caller is a sibling program reached by {@code EXEC CICS LINK} inside
 * the same CICS task, while the target caller is a browser over HTTP. Accepting the segment from
 * that browser would let it choose persisted content rather than only the row and the action. The
 * target therefore carries the key, the customer and the action, and re-reads the detail. The
 * fixed-width merchant name described below also means a client-trimmed segment would not
 * round-trip byte for byte.
 *
 * <p>Assumptions: The bidirectional {@code DFHCOMMAREA} at {@code cbl/COPAUS2C.cbl} L74 occupies
 * 272 bytes: 11 + 9 + 200 + 1 + 1 + 50. The copied detail contributes 200 bytes from 28 elementary
 * items; its four packed-decimal widths are 3, 5, 7 and 7 bytes. The target request derives from
 * the request-direction fields at L75, L76 and L80, while the response derives from L83 and L86.
 * One COBOL area serves both directions, whereas HTTP represents request and response separately,
 * so the migration expresses the two directions as distinct Java records.
 *
 * <p>Assumptions: {@code PA-MERCHANT-NAME} is {@code PIC X(22)} at
 * {@code cpy/CIPAUDTY.cpy} L40 and is not trimmed before persistence.
 * {@code cbl/COPAUS2C.cbl} L130 moves {@code LENGTH OF PA-MERCHANT-NAME}, which is always 22, into
 * the level-49 Db2 {@code VARCHAR} length field; L131 then moves the text. Comparisons and
 * round-trips against the persisted value must therefore account for all trailing spaces.
 *
 * <p>Trade-offs: Re-reading the detail costs one additional data-store read per request. In
 * exchange, the caller selects the row and the action but cannot supply persisted row content.
 *
 * <h2>Why the three key components are carried, and how they relate to the path selector</h2>
 *
 * <p>Refactoring Rationale: the components are the three columns of the
 * {@code authorization.pending_auth_detail} primary key -- {@code accountId},
 * {@code authDateKey} and {@code authTimeKey} -- plus the customer identifier and the action, and
 * they are carried here because a preceding revision of this record replaced all four identity
 * components with one opaque selector. That revision recorded three reasons, and each is answered
 * by the contract as it now stands rather than by preference. The first was that a caller could
 * address any authorization on any account by arithmetic on the numbers; the operation is gated on
 * the {@code carddemo-admin} authority, and the baseline performed no resource-level check at all
 * -- {@code csd/CRDDEMO2.csd} carries {@code RESSEC(NO) CMDSEC(NO)} on all three of its
 * transactions at L46, L56 and L66 -- so the guard is an addition and obscuring the identifier
 * adds nothing to it. The second was that a client had no legitimate way to obtain the numbers;
 * {@code openapi/authorization-api.yaml} publishes all three on the detail body, as
 * {@code accountId}, {@code authDate} and {@code authTime}, so a client that read the row holds
 * them. The third was that the two key parts were nines-complement values the schema does not
 * store; the values carried here are the DECODED ordinal date and time that
 * {@code db/migration/V1__authorization.sql} actually keys on, and the bounds below refuse a
 * complement.
 *
 * <p>Assumptions: the operation's path also names the row, as the opaque selector published for it,
 * and the two namings must AGREE. The service decodes the path selector, compares the recovered
 * triple against {@code accountId}, {@code authDateKey} and {@code authTimeKey}, and refuses a
 * disagreeing request with a per-field rejection rather than choosing one of the two silently.
 * Carrying the triple in the body is what makes this record a complete command that can be
 * validated and logged on its own terms, and what lets that comparison exist at all.
 *
 * <p>Alternatives Considered: naming the row only in the path and reducing this record to the
 * customer and the action. Rejected because the migration plan makes selection context travel as
 * request path and query values while the body carries the state to reach, and because a
 * two-component record could not be checked against the path at all -- the disagreement the
 * comparison above detects would instead be undetectable, since there would be nothing to compare.
 *
 * <p>Alternatives Considered: deriving the customer identifier server-side from the summary row for
 * the account, which {@code pending_auth_summary} holds as a not-null column. Rejected because the
 * baseline persists the value the caller supplied: {@code cbl/COPAUS2C.cbl} L139 moves
 * {@code WS-CUST-ID} into the fraud row's {@code CUST_ID} column, and
 * {@code db/migration/V1__authorization.sql} records that {@code acct_id} and {@code cust_id}
 * arrive from the caller because neither IMS segment carries them. A derived value would silently
 * differ from what the reference system stores whenever the two disagree.
 *
 * <p>Alternatives Considered: Lombok is unnecessary because a Java 21 record exposes the five
 * components directly and keeps their boundary documentation together. MapStruct is not used
 * because reconstructing the persistent detail is not a mechanical projection: fixed-width padding
 * and the key comparison above require explicit mapping decisions and adjacent rationale.
 *
 * <h2>The character collision between this record's action and the reply's outcome flag</h2>
 *
 * <p>Assumptions: {@code 'F'} means two different things across the two halves of the baseline area,
 * and the two fields are ADJACENT inside the one group item opened at {@code cbl/COPAUS2C.cbl} L79,
 * which is what makes the collision easy to read past. {@code WS-FRD-ACTION} at L80 reads {@code 'F'}
 * as {@code WS-REPORT-FRAUD} at L81; {@code WS-FRD-UPDATE-STATUS} at L83 reads the same character as
 * {@code WS-FRD-UPDT-FAILED} at L85. Nothing in the baseline is ambiguous about it, because a COBOL
 * condition name tests the field it was declared under. The hazard belongs entirely to the migration:
 * it arises only if the two fields are collapsed into one target type, so this record's {@code action}
 * and {@code FraudMarkResponse}'s {@code updateStatus} must never share a Java type, an enum or a
 * validation constant. The two domains do not overlap beyond the character itself -- {@code F} and
 * {@code R} here against {@code S} and {@code F} there -- and a test in this module asserts that
 * swapping the two values across the two records leaves each one invalid, so a mapper that crossed
 * them cannot pass validation.
 *
 * <h2>Validation and result contract</h2>
 *
 * <p>Assumptions: Bean Validation annotations are the sole executable authority for required
 * values, the two digit-string widths, the two integer key bounds and the two-value action domain;
 * a compact constructor does not duplicate those checks, as binding exceptions would lose
 * field-level validation context. The test channel must prove that {@code F} and {@code R} are
 * accepted and {@code S} is refused on the action, that the composite key round-trips against the
 * {@code authorization.pending_auth_detail} primary key, and that a response {@code updateStatus}
 * of {@code F} is not interchangeable with this record's action {@code F}.
 *
 * <p><strong>Return value.</strong> This declaration is an input value type and declares no
 * operation with a return value.
 *
 * @param accountId the eleven-digit account identifier from {@code WS-ACCT-ID PIC 9(11)} at
 *     {@code cbl/COPAUS2C.cbl} L75, the first column of the detail row's primary key, which L138
 *     also moves into the fraud row's {@code ACCT_ID} column
 * @param customerId the nine-digit customer identifier from {@code WS-CUST-ID PIC 9(9)} at
 *     {@code cbl/COPAUS2C.cbl} L76, which L139 moves into the fraud row's {@code CUST_ID} column;
 *     it is not part of any key and is recorded on the fraud row as supplied
 * @param authDateKey the decoded five-digit Julian day-of-year authorization date, the second
 *     column of the detail row's primary key, whose baseline field is
 *     {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} at {@code cpy/CIPAUDTY.cpy} L20; the value carried
 *     here is the decoded ordinal date and never the nines complement stored in that field
 * @param authTimeKey the decoded nine-digit millisecond-of-day authorization time, the third
 *     column of the detail row's primary key, whose baseline field is
 *     {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} at {@code cpy/CIPAUDTY.cpy} L21; the value carried
 *     here is the decoded time and never the nines complement stored in that field
 * @param action the one-character {@code WS-FRD-ACTION PIC X(01)} command at
 *     {@code cbl/COPAUS2C.cbl} L80: {@code F} means {@code WS-REPORT-FRAUD} at L81 and {@code R}
 *     means {@code WS-REMOVE-FRAUD} at L82; it is distinct from adjacent
 *     {@code WS-FRD-UPDATE-STATUS}, where {@code F} means update failed at L85
 */
public record FraudMarkRequest(
        @NotNull @Pattern(regexp = ACCOUNT_ID_DOMAIN) String accountId,
        @NotNull @Pattern(regexp = CUSTOMER_ID_DOMAIN) String customerId,
        @NotNull @Min(JULIAN_AUTH_DATE_MIN) @Max(JULIAN_AUTH_DATE_MAX) Integer authDateKey,
        @NotNull @Min(MILLISECOND_AUTH_TIME_MIN) @Max(MILLISECOND_AUTH_TIME_MAX)
                Integer authTimeKey,
        @NotNull @Pattern(regexp = ACTION_DOMAIN) String action) {

    /**
     * The eleven-digit form the account identifier is carried in.
     *
     * <p>Assumptions: the width is {@code WS-ACCT-ID PIC 9(11)} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L75, and the anchored digit class
     * is what keeps the value a digit string rather than a number. A leading zero is data in this
     * identifier, and a JSON number would lose it before any validation could see it.
     */
    private static final String ACCOUNT_ID_DOMAIN = "^[0-9]{11}$";

    /**
     * The nine-digit form the customer identifier is carried in.
     *
     * <p>Assumptions: the width is {@code WS-CUST-ID PIC 9(9)} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L76, and it is anchored for the
     * same leading-zero reason recorded on the account identifier above.
     */
    private static final String CUSTOMER_ID_DOMAIN = "^[0-9]{9}$";

    /**
     * The lowest Julian authorization date the contract admits.
     *
     * <p>Assumptions: a five-digit Julian value is a two-digit year followed by a day of year, and
     * day zero does not exist, so the floor is one rather than zero.
     */
    private static final long JULIAN_AUTH_DATE_MIN = 1L;

    /**
     * The highest Julian authorization date the contract admits.
     *
     * <p>Assumptions: the ceiling is the DOMAIN maximum of year 99 and day 366, and not the
     * five-nine constant a five-digit nines complement is subtracted from. Bounding at the domain
     * refuses a complement that reached this boundary by mistake; bounding at 99999 would admit
     * one, and the row it addressed would not be the row the caller meant.
     */
    private static final long JULIAN_AUTH_DATE_MAX = 99366L;

    /**
     * The lowest millisecond-of-day authorization time the contract admits.
     *
     * <p>Assumptions: midnight is a representable instant, so zero is a value rather than an
     * omission; requiredness is the separate concern of the annotation beside this bound.
     */
    private static final long MILLISECOND_AUTH_TIME_MIN = 0L;

    /**
     * The highest millisecond-of-day authorization time the contract admits.
     *
     * <p>Assumptions: the ceiling is the last representable instant of a day, 23:59:59 and 999
     * milliseconds, expressed as the hours, minutes and seconds multiplied by one thousand with the
     * milliseconds added. It is bounded at the domain rather than at 999999999 for the reason
     * recorded on the Julian ceiling above.
     */
    private static final long MILLISECOND_AUTH_TIME_MAX = 235959999L;

    /**
     * The two commands the baseline's fraud action admits, one character each.
     *
     * <p>Assumptions: this domain is closed by the two condition names at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L81 and L82 and by nothing else, so
     * a third character is not an unhandled case but a value the reference program cannot
     * represent. The domain is case-sensitive because a COBOL condition name compares the bytes of
     * its field against the literal it was declared with, so a lower-case form would satisfy
     * neither condition and would still reach the fraud column.
     */
    private static final String ACTION_DOMAIN = "[FR]";
}
