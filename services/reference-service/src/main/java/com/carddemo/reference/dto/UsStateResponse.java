package com.carddemo.reference.dto;

/**
 * One seeded United States state code, satisfying the contract schema {@code UsState}.
 *
 * <p>Purpose: the outbound shape of a single state read and the item type of the state page. One
 * member, because membership is the whole datum: {@code app/cpy/CSLKPCDY.cpy} declares
 * {@code US-STATE-CODE-TO-EDIT PIC X(2)} at L1012, and its L1013 condition name lists the accepted
 * literals over that field, {@code 'AL'} among them, recording no attribute beside the code
 * itself.</p>
 *
 * <p>Alternatives Considered: publishing the seeded codes as one unpaged array of bare strings was
 * evaluated and rejected, and it is the shape a reader is likeliest to expect here, since the
 * seeded set is small and bounded enough to fit a single reply. Two grounds reject it. The contract
 * declares the list operation as returning {@code UsStatePage}, so this type is the item carried
 * inside {@code com.carddemo.common.web.PageResponse} rather than the reply itself, and it holds no
 * paging member of its own. And the two address lookups beside it are paged the same way, so a
 * caller writes one paging loop for all three instead of special-casing this one, and the seeded
 * list can outgrow a single reply without altering a shape a client already reads.</p>
 *
 * <p>Alternatives Considered: a state name member was evaluated and rejected. The baseline holds no
 * state name anywhere -- the copybook carries codes and nothing else -- so a name member could only
 * be filled from a source outside the specification, which would publish data this migration has no
 * authority for.</p>
 *
 * <p>Assumptions: {@code services/reference-service/src/main/resources/openapi/reference-api.yaml}
 * governs this shape. That document declares {@code UsState} as a single-member object rather than
 * a bare string, which keeps the shape stable if the seeded list ever carries an attribute the way
 * the area-code list carries its classification, and where this type and that document disagree
 * the document wins.</p>
 *
 * <p>Assumptions: the address validation that consumes these codes reads them over the published
 * HTTP contract above and never by importing this type, and no shared code exists in either
 * direction. The two bounded contexts deploy as separate containers, so an import across them would
 * compile inside one reactor and then break once they are deployed apart, and the layering test
 * published by {@code common-lib} refuses it outright.</p>
 *
 * @param stateCd the accepted state code, two characters as declared at L1012 of
 *     {@code app/cpy/CSLKPCDY.cpy} and stored in {@code reference.us_states.state_cd CHAR(2)}; a
 *     {@code String} because that declared two-character width is part of the contract a caller
 *     echoes back to address the row
 */
public record UsStateResponse(String stateCd) {
}
