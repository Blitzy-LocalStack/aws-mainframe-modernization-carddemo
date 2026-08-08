package com.carddemo.reference.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * One seeded North American area code together with the sub-list it belongs to, satisfying the
 * contract schema {@code UsPhoneAreaCode}.
 *
 * <p>Purpose: the outbound shape of a single area-code read, and the item type carried inside
 * {@code com.carddemo.common.web.PageResponse} for the keyset-paged area-code browse. Membership is
 * the whole of this datum. {@code app/cpy/CSLKPCDY.cpy} holds these codes as condition names over
 * one three-byte working-storage field rather than as records, so the question the baseline asks of
 * a candidate value is answered in the migrated form by the presence of a row. This shape holds no
 * logic and reaches nothing.</p>
 *
 * <p>The keyset position for that browse is the area code itself taken ascending, which is the
 * primary key {@code db/migration/V1__reference.sql} declares over {@code area_cd CHAR(3)}. No
 * paging member is declared here: {@code PageResponse} already carries the items, both boundary
 * tokens and the forward-availability flag, so a paging member on the item type would be a second
 * and unreconciled account of one position. Those boundary tokens are sealed by
 * {@code com.carddemo.common.web.CursorToken} rather than published as the bare key, so the code
 * that appears in {@code areaCd} and the token that names the page edge are not interchangeable even
 * though the position underneath them is the same value. {@code LookupPageRequest} is the
 * counterpart on the request side, carrying the position, the direction and the classification
 * filter.</p>
 *
 * <p>Assumptions: the classification is one member over two admitted states rather than a pair of
 * independent flags, and what justifies it is a structural property of the copybook rather than a
 * preference. {@code app/cpy/CSLKPCDY.cpy} declares three condition names over the single field at
 * L24 -- the broad list at L30, the general-purpose list at L521 and the easily-recognisable list
 * at L931 -- and comparing their literal sets shows that the two narrower lists PARTITION the broad
 * one: they share no literal, their union is equal to it, and no literal of the broad list falls
 * outside them. Collapsing three condition names into one table is therefore the step at which the
 * only distinction the baseline draws between accepted codes would otherwise be lost, and this
 * member is what preserves it. Because the partition is total, every accepted code has exactly one
 * state and the member is never absent; because the partition is disjoint, one member suffices where
 * a pair of flags would additionally admit a both-set and a neither-set row that the copybook cannot
 * express. {@code db/migration/V1__reference.sql} closes the same domain with a check constraint
 * over {@code code_class CHAR(1) NOT NULL}.</p>
 *
 * <p>Assumptions: the distinction is consequential rather than decorative, which is what earns it a
 * place on the wire. {@code app/cbl/COACTUPC.cbl} tests {@code VALID-GENERAL-PURP-CODE} at its line
 * 2298 and tests neither of the other two condition names anywhere, and it is the only baseline
 * program that copies this book at all. A caller reproducing that edit therefore has to know which
 * sub-list a code came from, and it cannot recover that from the digits of the code.</p>
 *
 * <p>Assumptions: {@code areaCd} is character data of a declared width and never a numeric type.
 * The edited field at L24 of {@code app/cpy/CSLKPCDY.cpy} is
 * {@code WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}, three bytes compared against three-byte literals,
 * and the stored column is {@code CHAR(3)}, so the width is part of the contract and a leading zero
 * is part of the value. This package's charter states that invariant once for every code it governs
 * and {@code TransactionCategoryResponse} carries the argument in full, so it is not restated
 * here.</p>
 *
 * <p>Assumptions: a caller outside this context depends on this data, and it depends on the
 * published HTTP contract rather than on this type. The address edit that {@code COACTUPC} performs
 * is migrated into a different bounded context, which reaches these seeded rows through the
 * operations this context publishes and holds its own equivalent of the classification behind its
 * own port. Nothing outside this context imports this type, and this type imports nothing but the
 * serialisation annotations; a cross-context type import is forbidden in both directions, and the
 * layering test published by {@code common-lib} is what makes that enforceable rather than merely
 * intended.</p>
 *
 * @param areaCd the accepted area code as a three-character string of digits with any leading zero
 *     intact, from the {@code PIC XXX} field at L24 of {@code app/cpy/CSLKPCDY.cpy}; also the
 *     ascending keyset key and the primary key {@code area_cd CHAR(3)}
 * @param codeClass which sub-list the code belongs to, exactly one of two states --
 *     {@link CodeClass#GENERAL_PURPOSE} for the list at L521 of that copybook, or
 *     {@link CodeClass#EASILY_RECOGNISABLE} for the list at L931 -- and never absent, because the
 *     partition described above is total
 */
public record PhoneAreaCodeResponse(String areaCd, CodeClass codeClass) {

    /**
     * Builds this shape from the classification as it is stored rather than from the named state.
     *
     * <p>Trade-offs: this second way in is accepted so that the one place a stored character becomes
     * a named state is this boundary, rather than each mapping step reading the character and
     * choosing a state for itself. The stored column is {@code CHAR(1)}, so a driver hands the
     * classification back as text; converting once here keeps every caller from repeating the same
     * two-way comparison and from disagreeing about an unrecognised value. The accepted cost is that
     * one shape has two constructors, so a reader has to notice which one a call site uses.</p>
     *
     * @param areaCd the accepted area code, passed through to the canonical constructor unchanged
     * @param codeClass the stored classification character, {@code "G"} or {@code "E"}
     * @throws IllegalArgumentException when {@code codeClass} is absent or is not one of the two
     *     characters the contract publishes
     */
    public PhoneAreaCodeResponse(String areaCd, String codeClass) {
        this(areaCd, CodeClass.fromWireValue(codeClass));
    }

    /**
     * Which of the two baseline sub-lists an accepted area code belongs to, satisfying the contract
     * schema {@code PhoneAreaCodeClass}.
     *
     * <p>Purpose: to name the two states of the partition described on the enclosing type, so that a
     * call site reads the state instead of decoding a character.</p>
     *
     * <p>Alternatives Considered: a boolean member was evaluated and rejected. It would carry the
     * same information in fewer bytes, but a boolean names neither of its states, so every reader
     * and every call site would have to consult the contract to learn which sub-list {@code true}
     * stands for -- and a member read the wrong way round does not fail, it silently accepts the
     * codes it should refuse. A bare character was rejected for the weaker form of the same reason:
     * it names the states, but only to a reader who already knows the two letters. Naming them here
     * makes the distinction legible at the point of use.</p>
     *
     * <p>Alternatives Considered: a top-level type in this package, which is the form
     * {@code PageDirection} takes, was evaluated and rejected for this domain. That type is shared
     * by three browses, so a nested copy of it would have declared the same states three times and
     * left one contract schema answering to three Java types. This classification belongs to one
     * shape, so nesting keeps it beside the member it describes; the name {@code CodeClass} rather
     * than the schema's own {@code PhoneAreaCodeClass} avoids the stutter that the nested form would
     * otherwise produce at a qualified use, and the correspondence between the two names is recorded
     * here so that neither has to be guessed from the other.</p>
     */
    public enum CodeClass {

        /**
         * The code appears in the general-purpose list at L521 of {@code app/cpy/CSLKPCDY.cpy}, the
         * sub-list {@code app/cbl/COACTUPC.cbl} admits at its line 2298. The literal {@code '201'}
         * is a member of this list.
         */
        GENERAL_PURPOSE("G"),

        /**
         * The code appears in the easily-recognisable list at L931 of {@code app/cpy/CSLKPCDY.cpy},
         * the sub-list of assigned codes that the address edit above nonetheless declines. The
         * literal {@code '800'} is a member of this list.
         */
        EASILY_RECOGNISABLE("E");

        /** The single character the contract publishes and the classification column stores. */
        private final String wireValue;

        /**
         * Binds a state to the character the contract publishes for it.
         *
         * @param wireValue the single-character value a client receives and the column stores
         */
        CodeClass(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the published character, which is serialised in place of the constant name.
         *
         * @return {@code "G"} for the general-purpose list or {@code "E"} for the
         *     easily-recognisable list, never {@code null}
         */
        @JsonValue
        public String wireValue() {
            return this.wireValue;
        }

        /**
         * Reads a published or stored character into the state that it names.
         *
         * <p>Assumptions: an absent value is refused here rather than returned as absent, which is
         * the opposite of what {@code PageDirection} does with a missing direction. The asymmetry
         * follows the contract: a direction is optional and defaults to forward, whereas this
         * classification is required by the schema and {@code NOT NULL} in the column, and the
         * partition on the enclosing type is what makes that safe to insist on -- every accepted
         * code has a state, so an absent one is a broken row rather than a legitimate silence.</p>
         *
         * @param wireValue the single character to read, as the contract publishes it or as
         *     {@code code_class} stores it
         * @return the state that character names, never {@code null}
         * @throws IllegalArgumentException when the value is absent or is neither of the two
         *     characters the contract publishes, carrying a message that names the admitted domain
         *     and does NOT reproduce what was supplied
         */
        @JsonCreator
        public static CodeClass fromWireValue(String wireValue) {
            for (CodeClass candidate : values()) {
                if (candidate.wireValue.equals(wireValue)) {
                    return candidate;
                }
            }
            // WHY : Refactoring Rationale: this refusal appended the supplied value to its message.
            //       The value reaches here from a query parameter and from a request body, so it is
            //       caller-controlled text of arbitrary length and content, and the exception raised
            //       inside a @JsonCreator is wrapped by the deserialiser into a message that is
            //       logged and can reach a response body. Echoing it therefore put unvalidated input
            //       into a log line and into a refusal a client reads back -- the reflection and
            //       log-injection shape, for no diagnostic gain.
            // WHY : Assumptions: naming the admitted domain is strictly more actionable than echoing
            //       the rejection. The domain here is closed and has two members, so "one of G or E"
            //       tells a caller everything the echo would have, without the caller's own bytes.
            // WHY : Alternatives Considered: reporting the supplied LENGTH instead of the value,
            //       which is the geometry-not-content form used where a width is the contract --
            //       TransactionCategoryBalance reports a length and a digit position for exactly that
            //       reason. Rejected here because a length says nothing useful about a two-member
            //       character domain: every wrong value of length one is as wrong as every other, so
            //       the number would be noise that still varied with caller input.
            throw new IllegalArgumentException("codeClass must be one of G or E");
        }
    }
}
