package com.carddemo.reference.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The direction a keyset page is taken in, satisfying the contract schema {@code PageDirection}.
 *
 * <p>Purpose: one shared enumeration for the paging direction of every browse in this context. The
 * contract declares the two values in lower case, and this type carries that wire spelling explicitly
 * rather than relying on the constant name, because a Java constant cannot be spelled in lower case
 * without breaking every naming convention the build enforces.</p>
 *
 * <p>Alternatives Considered: a constrained string on each list shape was evaluated and rejected. It
 * would compile and would accept exactly the same two values, but it would leave every consumer to
 * compare against a literal, so a misspelling would be a run-time behaviour rather than a compile
 * error -- and a direction compared wrongly does not fail, it pages the wrong way.</p>
 *
 * <p>Alternatives Considered: a nested enumeration inside each of the three list shapes, which is the
 * form a sibling context uses for its single browse, was evaluated and rejected here on a
 * count. This context has three browses sharing one direction domain, so a nested form would declare
 * the same two constants and the same two conversion members three times, and the contract would have
 * one schema answering to three Java types. A top-level type named after that schema is what this
 * package's naming convention asks for.</p>
 *
 * <p>Assumptions: this type is shared by composition and not by inheritance. A Java record cannot
 * extend a class, so the three list shapes each declare a member of this type rather than deriving
 * from a common paging parent -- which is also why a nested paging sub-record was rejected in this
 * package's charter, since a nested object binds as a dotted query parameter and would publish a
 * different query string from the one the contract declares.</p>
 */
public enum PageDirection {

    /**
     * Forward, from the position given toward higher keys. This is the value the contract publishes as
     * its default, so an absent direction is read as this one.
     */
    NEXT("next"),

    /**
     * Backward, from the position given toward lower keys. The baseline's backward cursor treats the
     * position it is handed exclusively while its forward cursor treats it inclusively, and preserving
     * that asymmetry is the service layer's obligation rather than this type's.
     */
    PREVIOUS("previous");

    /** The spelling the contract publishes and a client sends. */
    private final String wireValue;

    /**
     * Binds a constant to the wire spelling the contract publishes for it.
     *
     * @param wireValue the lower-case value a client sends and receives
     */
    PageDirection(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Returns the wire spelling, which is what is serialised rather than the constant name.
     *
     * @return the lower-case contract value, never {@code null}
     */
    @JsonValue
    public String wireValue() {
        return this.wireValue;
    }

    /**
     * Reads a wire value into a constant, treating an absent value as absent rather than as a default.
     *
     * <p>Assumptions: a null input yields null rather than the forward default. Defaulting here would
     * make an omitted direction indistinguishable from an explicit forward one at the point where the
     * pairing rule is checked, and the charter records that a direction is meaningful only alongside a
     * position. Applying the default is the service's step, after that check.</p>
     *
     * @param wireValue the value a client sent, possibly {@code null}
     * @return the matching constant, or {@code null} when the input was {@code null}
     * @throws IllegalArgumentException when the value is neither of the two the contract publishes
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
