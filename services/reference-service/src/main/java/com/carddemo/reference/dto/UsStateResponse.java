package com.carddemo.reference.dto;

/**
 * One seeded United States state code, satisfying the contract schema {@code UsState}.
 *
 * <p>This is the outbound shape of a single state read and the item type of the state page. It holds
 * one member because membership is the whole datum: {@code app/cpy/CSLKPCDY.cpy} declares
 * {@code US-STATE-CODE-TO-EDIT PIC X(2)} at L1012, and its L1013 condition name lists the accepted
 * literals over that field, {@code 'AL'} among them, recording no attribute beside the code
 * itself.</p>
 *
 * <p>Assumptions: {@code services/reference-service/src/main/resources/openapi/reference-api.yaml}
 * governs this shape and wins where the two disagree. That document declares {@code UsState} as a
 * single-member object rather than a bare string, and declares the list operation as returning
 * {@code UsStatePage}, so this type is the item carried inside
 * {@code com.carddemo.common.web.PageResponse} and holds no paging member of its own -- which also
 * keeps one paging loop serving this lookup and the two address lookups beside it.</p>
 *
 * <p>Assumptions: no state name is published, because the baseline holds none. The copybook carries
 * codes and nothing else, so a name member could only be filled from a source outside the
 * specification.</p>
 *
 * <p>Assumptions: the address validation that consumes these codes reads them over the published HTTP
 * contract and never by importing this type. The two bounded contexts deploy as separate containers,
 * so an import across them would compile inside one reactor and break once they are deployed apart,
 * and the layering test published by {@code common-lib} refuses it outright.</p>
 *
 * @param stateCd the accepted state code, two characters as declared at L1012 of
 *     {@code app/cpy/CSLKPCDY.cpy} and stored in {@code reference.us_states.state_cd CHAR(2)}; a
 *     {@code String} because that declared two-character width is part of the contract a caller
 *     echoes back to address the row
 */
public record UsStateResponse(String stateCd) {
}
