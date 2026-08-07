package com.carddemo.reference.dto;

/**
 * One transaction type as this service publishes it, satisfying the contract schema
 * {@code TransactionType}.
 *
 * <p>Purpose: this is the outbound shape of a transaction-type read, of a create and of a replace,
 * and it is also the item type of the transaction-type page. It holds no logic and reaches nothing;
 * {@code com.carddemo.reference.mapper} builds it from the entity of the same name and
 * {@code com.carddemo.reference.api} returns it.</p>
 *
 * <p>Assumptions: the three members are the whole of the record the baseline declares, minus its one
 * padding field. {@code app/cpy/CVTRA03Y.cpy} states {@code TRAN-TYPE PIC X(02)} at L5 and
 * {@code TRAN-TYPE-DESC PIC X(50)} at L6, and its L7 {@code FILLER PIC X(08)} is padding to the
 * declared length of 60 rather than data, so it is not carried across. The version is not a baseline
 * field at all; it is the optimistic-lock counter the migration adds so that a replace can state
 * which revision it is replacing, and a caller needs it back in order to send it in.</p>
 *
 * <p>Alternatives Considered: withholding the version from the read and requiring a caller to send
 * a value it had never been given was evaluated and rejected -- the replace body declares the
 * version required, so a caller that could not read it could never perform a compliant replace.</p>
 *
 * @param typeCd the two-character transaction type, from {@code TRAN-TYPE PIC X(02)} at L5 of
 *     {@code app/cpy/CVTRA03Y.cpy}; carried as characters so a leading zero survives the round trip
 * @param description the type description, from {@code TRAN-TYPE-DESC PIC X(50)} at L6 of that
 *     copybook, trailing blanks removed because they are padding to the declared width
 * @param version the revision this reply describes, which a replace must echo back; zero on every
 *     seeded row, because the migration defaults the column to zero
 */
public record TransactionTypeResponse(String typeCd, String description, long version) {
}
