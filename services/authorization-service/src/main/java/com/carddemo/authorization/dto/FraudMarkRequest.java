package com.carddemo.authorization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Carries an authorization row key and the fraud-marking command accepted at the HTTP boundary.
 *
 * <p><strong>Purpose.</strong> This request identifies one pending authorization and asks the
 * service either to report or to remove its fraud mark without accepting the persisted detail
 * payload from a browser.
 *
 * <h2>Boundary shape</h2>
 *
 * <p>Alternatives Considered: The baseline passes the complete 200-byte {@code COPY CIPAUDTY}
 * segment through {@code DFHCOMMAREA} at {@code cbl/COPAUS2C.cbl} L77-L78. Mirroring that segment
 * in the request body would save the server one read, but the callers have different trust
 * boundaries: the baseline caller is a sibling program reached by {@code EXEC CICS LINK} inside
 * the same CICS task, while the target caller is a browser over HTTP. Accepting the segment from
 * that browser would let it choose persisted content rather than only the row and action. The
 * target therefore carries the key and action and re-reads the detail. The fixed-width merchant
 * name described below also means a client-trimmed segment would not round-trip byte for byte.
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
 * exchange, the caller selects the row and action but cannot supply persisted row content.
 *
 * <h2>Type and identity rulings</h2>
 *
 * <p>Assumptions: {@code accountId} and {@code customerId} are digit-only strings so leading
 * zeroes survive the character-over-numeric representation used by the baseline. The two
 * authorization key parts remain integers because {@code PA-AUTH-DATE-9C} and
 * {@code PA-AUTH-TIME-9C} are packed-decimal items at {@code cpy/CIPAUDTY.cpy} L20-L21 rather than
 * character data. They are stored nines-complement key values, not a calendar date and wall-clock
 * time: {@code cbl/COPAUA0C.cbl} L874-L875 subtracts the source values from 99,999 and 999,999,999
 * so ascending key order yields descending chronology. Their packed widths, 3 + 5, match the
 * eight-byte {@code CDEMO-CPVS-AUTH-KEYS PIC X(08)} slot at {@code cbl/COPAUS0C.cbl} L126.
 * Together, {@code accountId}, {@code authDateKey} and {@code authTimeKey} identify the
 * {@code authorization.pending_auth_detail} primary key.
 *
 * <p>Alternatives Considered: Lombok is unnecessary because a Java 21 record exposes the five
 * components directly and keeps their boundary documentation together. MapStruct is not used
 * because reconstructing the persistent detail is not a mechanical projection: nines-complement
 * keys and fixed-width padding require explicit mapping decisions and adjacent rationale.
 *
 * <h2>Validation and result contract</h2>
 *
 * <p>Assumptions: Bean Validation annotations are the sole executable authority for required
 * values, identifier widths, key ranges and the two-value action domain; a compact constructor
 * does not duplicate those checks as binding exceptions would lose field-level validation
 * context. The test channel must prove that {@code F} and {@code R} are accepted, {@code S} is
 * rejected, and the three composite-key components round-trip unchanged against the
 * {@code authorization.pending_auth_detail} primary key.
 *
 * <p><strong>Return value.</strong> This declaration is an input value type and declares no
 * operation with a return value.
 *
 * @param accountId exactly 11 ASCII digits from {@code WS-ACCT-ID PIC 9(11)} at
 *     {@code cbl/COPAUS2C.cbl} L75; the string representation preserves leading zeroes
 * @param customerId exactly 9 ASCII digits from {@code WS-CUST-ID PIC 9(9)} at
 *     {@code cbl/COPAUS2C.cbl} L76; the string representation preserves leading zeroes
 * @param authDateKey the stored nines-complement value from
 *     {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} at {@code cpy/CIPAUDTY.cpy} L20, in the inclusive
 *     range 0 through 99,999 rather than a caller-formatted date
 * @param authTimeKey the stored nines-complement value from
 *     {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} at {@code cpy/CIPAUDTY.cpy} L21, in the inclusive
 *     range 0 through 999,999,999 rather than a caller-formatted time
 * @param action the one-character {@code WS-FRD-ACTION PIC X(01)} command at
 *     {@code cbl/COPAUS2C.cbl} L80: {@code F} means {@code WS-REPORT-FRAUD} at L81 and {@code R}
 *     means {@code WS-REMOVE-FRAUD} at L82; it is distinct from adjacent
 *     {@code WS-FRD-UPDATE-STATUS}, where {@code F} means update failed at L85
 */
public record FraudMarkRequest(
        @NotNull @Pattern(regexp = "[0-9]{11}") String accountId,
        @NotNull @Pattern(regexp = "[0-9]{9}") String customerId,
        @NotNull @Min(0) @Max(99_999) Integer authDateKey,
        @NotNull @Min(0) @Max(999_999_999) Integer authTimeKey,
        @NotNull @Pattern(regexp = "[FR]") String action) {
}
