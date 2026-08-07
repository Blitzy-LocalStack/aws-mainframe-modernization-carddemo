package com.carddemo.reference.dto;

/**
 * One seeded North American area code and its classification, satisfying the contract schema
 * {@code UsPhoneAreaCode}.
 *
 * <p>Purpose: the outbound shape of an area-code read and the item type of the area-code page. The
 * whole content of this reference datum is membership: the baseline tests a candidate value against
 * a list of literals, and the migrated form of that test is whether a row exists.</p>
 *
 * <p>Assumptions: the classification is one member with two admitted values rather than two boolean
 * flags, because the two sublists in {@code app/cpy/CSLKPCDY.cpy} partition the broad list exactly.
 * The broad condition name at L30 carries 490 literals, the general-purpose list at L521 carries 410
 * and the easily-recognisable list at L931 carries 80; the two are disjoint and 410 plus 80 is 490.
 * A flag pair would admit both-true and both-false rows that the copybook cannot express.</p>
 *
 * @param areaCd the three-digit area code, from {@code WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at L24
 *     of {@code app/cpy/CSLKPCDY.cpy}; characters rather than an integer because the declared type is
 *     alphanumeric and the comparison is a character comparison of a fixed-width field
 * @param codeClass the classification, {@code 'G'} for the general-purpose list at L521 or
 *     {@code 'E'} for the easily-recognisable list at L931; never absent, because the partition is
 *     total and every seeded code therefore has a class
 */
public record PhoneAreaCodeResponse(String areaCd, String codeClass) {
}
