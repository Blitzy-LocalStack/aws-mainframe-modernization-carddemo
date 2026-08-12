package com.carddemo.reference.dto;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
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
     * @throws IllegalArgumentException when the value is neither of the two the contract publishes,
     *     carrying a message that names the admitted domain and does NOT reproduce what was supplied
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
        // WHY : Refactoring Rationale: this refusal appended the supplied value to its message, and
        //       is fixed with its sibling in PhoneAreaCodeResponse.CodeClass rather than after it.
        //       The two are the only @JsonCreator refusals in this package, they had the same echo,
        //       and this type's own charter cross-references that one -- so correcting one and
        //       leaving the other would make the package inconsistent about the same decision. The
        //       direction arrives as a query parameter, which is caller-controlled text of arbitrary
        //       length, and the deserialiser wraps whatever is thrown here into a logged message that
        //       can reach a response body.
        // WHY : Assumptions: the admitted domain is closed and has two members, so naming it tells a
        //       caller everything the echo did without putting the caller's own bytes in a log line.
        throw new IllegalArgumentException(MESSAGE_UNADMITTED_DIRECTION);
    }

    /** The sentence a value outside the two-member domain is refused with, on either entry point. */
    public static final String MESSAGE_UNADMITTED_DIRECTION =
            "direction must be one of next or previous";

    /** The published name of the query parameter this type is bound from, and the field a refusal names. */
    public static final String PARAMETER_NAME = "direction";

    /**
     * Reads the query-parameter spelling of a direction, refusing an unadmitted value as caller input.
     *
     * <p>⚠️ Purpose and Refactoring Rationale: this member exists because the framework's own
     * string-to-enumeration conversion could not read the values this contract publishes. A handler
     * parameter declared as this type is bound by {@code StringToEnumConverterFactory}, which resolves
     * through {@code Enum.valueOf} against the CONSTANT NAME -- so {@code NEXT} and {@code PREVIOUS} were
     * accepted and {@code next} and {@code previous}, the only two values the contract declares and the
     * only two {@code ui/src/api/reference.ts} sends, were refused. The {@link JsonCreator} above did not
     * help, because it is consulted for a request BODY and a direction arrives as a query parameter. Every
     * paging route that took a direction was therefore unreachable to its own published clients. The five
     * handlers now bind a {@code String} and convert here, which is correct with no framework
     * registration at all and is provable by driving a real request at each route.</p>
     *
     * <p>Alternatives Considered: registering a {@code Converter<String, PageDirection>} through a
     * {@code WebMvcConfigurer}, which is the tidier one-line change and is why it was considered first.
     * Rejected because its correctness would then depend on wiring the module's test dispatchers cannot
     * see: this module's HTTP cases are assembled with {@code standaloneSetup}, which registers no
     * application context, so each would have to install the converter itself -- and a test that installs
     * the mechanism it is verifying proves the converter works while proving nothing about whether the
     * running service registers it. Converting at the boundary needs no registration to be right.</p>
     *
     * <p>Assumptions: an unadmitted value is refused as CALLER INPUT and not as a conversion fault, which
     * is a second improvement rather than an incidental one. Under the framework's conversion the refusal
     * arrived as a type mismatch carrying no field, so a client was told the request was malformed without
     * being told which parameter to correct; this names {@value #PARAMETER_NAME} as the field. The sentence
     * is shared with the body path above, so the two entry points cannot come to disagree about the
     * domain.</p>
     *
     * <p>Assumptions: a {@code null} input yields {@code null} rather than the forward default, exactly as
     * the body path does, because an omitted direction is meaningful only alongside a position and the
     * pairing rule is checked by the service. Defaulting here would make an omitted direction
     * indistinguishable from an explicit forward one at the point that check is made.</p>
     *
     * @param parameterValue the value the caller sent on the query string, possibly {@code null}
     * @return the matching constant, or {@code null} when no direction was supplied
     * @throws ClientInputException when the value is neither of the two the contract publishes, carrying
     *     the shared sentence and naming {@value #PARAMETER_NAME} as the field at fault
     */
    public static PageDirection fromRequestParameter(String parameterValue) {
        try {
            return fromWireValue(parameterValue);
        } catch (IllegalArgumentException unadmitted) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, PARAMETER_NAME,
                    MESSAGE_UNADMITTED_DIRECTION);
        }
    }
}
