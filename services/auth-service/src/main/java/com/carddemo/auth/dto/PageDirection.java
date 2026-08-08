package com.carddemo.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which side of a supplied cursor a user browse reads.
 *
 * <h2>What this enum is</h2>
 *
 * <p>This is the {@code PageDirection} schema of the published contract at
 * {@code services/auth-service/src/main/resources/openapi/auth-api.yaml}, which declares exactly two
 * values. Reading {@code next} seeks keys strictly greater than the cursor in ascending order; reading
 * {@code previous} seeks keys strictly less than it in descending order.
 *
 * <p>Assumptions: the reverse direction is the migrated form of the reference browse's own backward step.
 * The user list transaction pages with {@code READPREV} at {@code app/cbl/COUSR00C.cbl} line 343, so
 * backward paging is reference behaviour rather than an addition, and it is why the direction has to be
 * expressible at all instead of the browse only ever moving forward.
 *
 * <h2>Why the wire values are lower case</h2>
 *
 * <p>Assumptions: the contract enumerates {@code next} and {@code previous} in lower case, and a query
 * parameter is compared literally, so the constant names cannot serve as the wire form. The explicit
 * binding below is what keeps the Java naming convention and the published values independent of one
 * another -- renaming a constant cannot change the contract, and the contract's values are stated once.
 *
 * <p>Alternatives Considered: relying on the framework's default enum binding, which matches a query value
 * against the constant name and would require the values to be spelled {@code NEXT} and {@code PREVIOUS}
 * on the wire. Rejected because that would mean either changing the published contract to match Java's
 * naming or accepting a case-sensitive mismatch that rejects every conforming request.
 *
 * <p>Alternatives Considered: importing the identically-shaped enum another service already declares.
 * Rejected because a cross-service import of another context's transfer package is exactly what the
 * shared kernel's layering rules forbid, and the two contracts are separately published documents that
 * are free to diverge. The duplication is the price of the boundary.
 */
public enum PageDirection {

    /** Read keys strictly greater than the cursor, in ascending order. */
    NEXT("next"),

    /** Read keys strictly less than the cursor, in descending order. */
    PREVIOUS("previous");

    /** The value the contract publishes for this direction. */
    private final String wireValue;

    /**
     * Binds a constant to the value the contract publishes for it.
     *
     * @param wireValue the published value, as the contract enumerates it
     */
    PageDirection(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Reports the value the contract publishes for this direction.
     *
     * @return the published value, never {@code null}
     */
    @JsonValue
    public String wireValue() {
        return this.wireValue;
    }

    /**
     * Resolves a submitted value to its direction, refusing anything the contract does not enumerate.
     *
     * <p>Assumptions: an absent value resolves to {@code null} rather than to a default, so the caller
     * can tell "not supplied" from "supplied as next". The default the contract declares is applied where
     * the distinction stops mattering, which is the browse itself.
     *
     * @param wireValue the submitted value, or {@code null} when the parameter was omitted
     * @return the matching direction, or {@code null} when {@code wireValue} is {@code null}
     * @throws IllegalArgumentException if the value is neither published value, which the shared advice
     *     renders as a 400 naming the parameter
     */
    @JsonCreator
    public static PageDirection fromWireValue(String wireValue) {

        if (wireValue == null) {
            return null;
        }

        for (PageDirection candidate : values()) {
            if (candidate.wireValue.equals(wireValue)) {
                return candidate;
            }
        }

        throw new IllegalArgumentException(
                "direction must be one of next or previous; received " + wireValue);
    }
}
