package com.carddemo.reference.dto;

/**
 * One transaction category as this service publishes it, satisfying the contract schema
 * {@code TransactionCategory}.
 *
 * <p>Purpose: the outbound shape of a category read, create and replace, and the item type of the
 * category page. It holds no logic and reaches nothing.</p>
 *
 * <p>Assumptions: the two key members are published separately rather than as the single
 * six-character concatenation the seed file stores, because the contract addresses a category
 * through two path segments and a caller has to be able to build that path from what it read.
 * {@code app/cpy/CVTRA04Y.cpy} declares them separately too, at L6 and L7 under the L5 group
 * {@code TRAN-CAT-KEY}; the concatenation is how the file keys the record, not how the record is
 * shaped. The L9 {@code FILLER PIC X(04)} is padding and is not carried across.</p>
 *
 * @param typeCd the two-character transaction type this category belongs to, from L6 of
 *     {@code app/cpy/CVTRA04Y.cpy}
 * @param catCd the four-digit category code with its leading zeros intact, from L7 of that
 *     copybook; a string and never an integer, because {@code 0001} rendered as {@code 1} is a key
 *     a caller cannot echo back to address the row it just read
 * @param description the category description, from L8 of that copybook, trailing blanks removed
 * @param version the revision this reply describes, which a replace must echo back
 */
public record TransactionCategoryResponse(
        String typeCd, String catCd, String description, long version) {
}
