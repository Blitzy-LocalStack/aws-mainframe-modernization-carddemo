package com.carddemo.reference.dto;

/**
 * One seeded United States state code, satisfying the contract schema {@code UsState}.
 *
 * <p>Purpose: the outbound shape of a state read and the item type of the state page. One member,
 * because membership is the whole datum: {@code app/cpy/CSLKPCDY.cpy} declares
 * {@code US-STATE-CODE-TO-EDIT PIC X(2)} at L1012 and its L1013 condition name carries 56 literals,
 * with no attribute beside the code itself.</p>
 *
 * <p>Alternatives Considered: a state name member was evaluated and rejected. The baseline holds no
 * state name anywhere -- the copybook carries codes and nothing else -- so a name member could only
 * be filled from a source outside the specification, which would publish data this migration has no
 * authority for.</p>
 *
 * @param stateCd the two-character state code, from L1012 of {@code app/cpy/CSLKPCDY.cpy}
 */
public record UsStateResponse(String stateCd) {
}
