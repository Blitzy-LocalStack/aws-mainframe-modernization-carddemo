package com.carddemo.reference.dto;

/**
 * One seeded state-and-postal-prefix pair, satisfying the contract schema
 * {@code UsStateZipPrefix}.
 *
 * <p>Purpose: the outbound shape of a prefix read and the item type of the prefix page. One member
 * holding four characters, because that is the unit the baseline tests.</p>
 *
 * <p>Alternatives Considered: splitting the value into a state member and a prefix member was
 * evaluated and rejected. {@code app/cpy/CSLKPCDY.cpy} builds the four bytes and tests the pair as a
 * unit -- its L1071 group carries {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} at L1072 with 240 literals
 * at L1073 -- so splitting it would oblige every reader to reassemble it before comparing, and would
 * invite a comparison of one half alone, which the baseline never performs.</p>
 *
 * @param stateZipCd the state code followed by the two leading postal digits, in that order, from
 *     L1072 of {@code app/cpy/CSLKPCDY.cpy}; the leading postal digit is significant, so that half
 *     cannot become numeric without losing a prefix that begins with zero
 */
public record UsStateZipPrefixResponse(String stateZipCd) {
}
