package com.carddemo.reference.dto;

/**
 * One seeded combination of a state code and the two leading digits of a postal code, satisfying the
 * contract schema {@code UsStateZipPrefix}.
 *
 * <p>Purpose: the outbound shape of a single-combination read, and the item type this context
 * carries inside the page a combination browse answers with. One member holding four characters,
 * because four characters are the unit the baseline assembles and tests. Nothing here reads a
 * datastore or decides a rule: {@code com.carddemo.reference.mapper} builds this shape from the
 * stored row and {@code com.carddemo.reference.api} returns it.</p>
 *
 * <p>Parameters, return values, exceptions or errors: the one record component below is this type's
 * parameter and carries its own at-clause. A type declaration yields no value and raises nothing,
 * the canonical constructor a record form supplies takes that component as declared and raises
 * nothing either, and the accessor it supplies returns the component unchanged, so no return or
 * exception at-clause appears at this level. The inapplicability is stated rather than left silent:
 * the Explainability rule lists a docstring that omits parameters or return values among its
 * forbidden patterns at its line 39, and a shape holding a single component is precisely where a
 * reader could not otherwise tell a declared inapplicability from an omission.</p>
 *
 * <p>Assumptions: the seeded reference data reaches four characters and no further, and the baseline
 * is what settles that. The group {@code US-STATE-ZIPCODE-TO-EDIT} at L1071 of
 * {@code app/cpy/CSLKPCDY.cpy} declares two subordinates. The first,
 * {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} at L1072, carries the condition name
 * {@code VALID-US-STATE-ZIP-CD2-COMBO} at L1073 and is therefore the half holding an admitted-value
 * list. The second, {@code LAST-3-OF-ZIP PIC X(3)} at L1314, carries no condition name, and no
 * program in the reference material reads it. The paragraph performing the edit shows the same reach
 * from the other side: {@code 1280-EDIT-US-STATE-ZIP-CD} at L2536 of {@code app/cbl/COACTUPC.cbl}
 * concatenates the state code with the first two postal characters into the four-character field at
 * L2540 and evaluates the condition name at L2542, so the remaining postal characters never enter
 * the comparison. This member therefore carries the combination the baseline admits, and a reader
 * who knows a postal code runs to five characters is reading the declared reach of the reference
 * data rather than a member lost in translation. No divergence arises here; where this migration
 * does depart from the baseline the departure is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is owned elsewhere and is read
 * rather than authored from here.</p>
 *
 * <p>Alternatives Considered: a state member beside a postal-prefix member, two components in place
 * of one, was evaluated and rejected. The four characters are one value on both sides of this type
 * -- the admitted-value list at L1073 of {@code app/cpy/CSLKPCDY.cpy} is keyed on the concatenation
 * and never on either half, and {@code state_zip_cd CHAR(4)} is the whole of the primary key of
 * {@code reference.us_state_zip_prefixes} in {@code db/migration/V1__reference.sql}. A split pair
 * would oblige every reader to reassemble the value before comparing it with a seeded row or using
 * it as a paging position, and it would invite a comparison of one half alone, which the baseline
 * never performs and the seeded rows cannot answer. Trade-offs: a caller wanting the state half on
 * its own takes it from the leading characters, and that is the accepted cost of publishing one
 * value that matches the key it addresses.</p>
 *
 * <p>Assumptions: {@code services/reference-service/src/main/resources/openapi/reference-api.yaml}
 * governs this shape, and this type is reconciled against that document rather than derived a second
 * time from the copybook. It declares {@code UsStateZipPrefix} as an object of exactly one required
 * member named {@code stateZipCd}, admits no further member, and types the member through
 * {@code StateZipPrefixValue} as a string of minimum and maximum length four matching two upper-case
 * letters followed by two digits. The browse takes its position and direction through
 * {@code LookupPageRequest} and answers with that object inside
 * {@code com.carddemo.common.web.PageResponse}, which is exactly {@code items}, {@code firstKey},
 * {@code lastKey} and {@code hasNext}, so no paging member belongs on this type: a window size, a
 * page number or a backward-availability flag declared here would publish something the envelope has
 * nowhere to carry. The paging key is this member itself in ascending order, the same column the
 * migration names as the primary key, so a position and a row identity are one value here.</p>
 *
 * <p>Assumptions: that document pages both of the seeded address lookups this context publishes, so
 * paging is not what sets this one apart. What sets it apart is that its collection is the materially
 * larger of the two, which is why reading it a page at a time is consequential here rather than
 * merely uniform. Stating that is worth a sentence, because the opposite conclusion is easy to reach
 * from the relative sizes alone and the document is the authority on both shapes.</p>
 *
 * <p>Assumptions: this context seeds the combinations and a second bounded context consumes them
 * over the published HTTP contract, never by importing this type. The consumer is the migrated form
 * of the address edit at L2536 of {@code app/cbl/COACTUPC.cbl}, which belongs to a different context
 * of this migration; an import reaching across that boundary would compile inside one reactor and
 * then break the moment the two contexts are deployed as separate containers, and the layering test
 * published by {@code common-lib} refuses it outright. The prohibition runs both ways, so this file
 * names no type of the consuming context either.</p>
 *
 * @param stateZipCd the admitted combination, four characters wide: a two-character state code
 *     immediately followed by the two leading digits of a postal code, in that order and with no
 *     separator, exactly as the literals are written at L1073 of {@code app/cpy/CSLKPCDY.cpy} over
 *     the {@code PIC X(4)} field at L1072. Characters rather than a numeric member, because the
 *     postal digits are positional: a combination whose postal half opens with a zero is a distinct
 *     value from one that does not, and a numeric member would render the two alike
 */
public record UsStateZipPrefixResponse(String stateZipCd) {

    /**
     * Renders the code in full, because this is a reference key and not a cardholder's postal code.
     *
     * <p>Purpose. This renderer exists to record a decision rather than to withhold a value. A component
     * whose name ends in a postal-code word is exactly what a reviewer -- or the census gate in
     * {@code com.carddemo.common.architecture} -- flags as personal data, and here it is not: the value is
     * a two-character state code followed by a two-character postal prefix, seeded from the reference
     * lookup copybook, published in full by this very operation, and shared by every address in a region
     * rather than belonging to any one of them.</p>
     *
     * <p>Assumptions: the distinction that matters is between a value that LOCATES a person and one that
     * CLASSIFIES a region. {@code docs/architecture/observability.md} L1093 to L1112 withholds an
     * identified cardholder's address components, which is why the account context's customer projection
     * withholds its own postal code; a four-character allow-list entry naming a state and a prefix
     * identifies nobody, and redacting it would leave this context unable to log which reference row a
     * validation consulted.</p>
     *
     * <p>Trade-offs: rendering it makes this the one place in the migration where a component matching the
     * protected-name vocabulary is printed in full, so the reason is written here rather than in a shared
     * exemption list -- a list of names would have to be read together with the file to be understood,
     * whereas this paragraph is read by whoever next opens the file that prints the value.</p>
     *
     * @return a rendering naming the four-character state and postal-prefix code; never {@code null}
     */
    @Override
    public String toString() {
        return "UsStateZipPrefixResponse[stateZipCd=" + this.stateZipCd + ']';
    }
}
