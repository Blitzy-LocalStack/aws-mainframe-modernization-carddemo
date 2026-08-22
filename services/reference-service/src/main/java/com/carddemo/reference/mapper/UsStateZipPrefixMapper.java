package com.carddemo.reference.mapper;

import com.carddemo.reference.domain.UsStateZipPrefix;
import com.carddemo.reference.dto.UsStateZipPrefixResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders a seeded combination of a United States state code and the two leading digits of a postal
 * code as its published shape.
 *
 * <p>Purpose: the single place the combination row's representation concerns are resolved. One
 * member is carried, because one member is the whole row, so the substance of this class is not the
 * assignment but the ruling that those four characters travel as one value and are never taken
 * apart -- and that ruling is recorded at the line which publishes it rather than gathered into this
 * header.</p>
 *
 * <p>Assumptions: every class in this package is written by hand and no code generator is introduced
 * anywhere in it. The package-scope rulings this class applies are settled once in this package's own
 * {@code package-info.java} -- the hand-written charter, the rejection of MapStruct and, on a
 * separate ground, of Lombok, the trim boundary of its <i>The trim boundary, which follows the column
 * type</i> section, the items-only boundary of its <i>What this package does not do</i> section, and
 * the absence of an inbound member on the seeded lookups under its <i>Deliberate omissions</i> -- and
 * they are cited by the heading each one sits under rather than argued again. Refactoring Rationale:
 * those three citations quoted line ranges of the charter, and every one of them had drifted past the
 * paragraph it named as that file grew, so each pointed a reader at neighbouring text that argues
 * something else. A heading is located by literal search and does not move when a paragraph above it
 * is edited, which is the citation form {@code UsPhoneAreaCodeMapper} already uses in this package.
 * What this file adds is the evidence specific to this one combination.</p>
 *
 * <p>Alternatives Considered: this class is a Spring {@code @Component} with instance members and is
 * deliberately not declared {@code final}, rather than the {@code final} class with a private
 * constructor and static members that the charter fixes for the three record conversions. The static
 * shape was the alternative and was not taken here. The charter already
 * admits this second shape for the three seeded-lookup conversions, and records that a component is
 * left non-final precisely so that it remains proxyable; both
 * of those properties are wanted here. This conversion is reached by constructor injection, which is
 * the dependency-injection pattern the Agent Action Plan fixes for this migration in place of the
 * baseline's static linkage, and an injected bean can be substituted in a slice test where a static
 * member cannot.</p>
 *
 * <p>Trade-offs: the cost of that choice is that this class does not read identically to its three
 * static siblings -- {@code TransactionTypeMapper}, {@code TransactionCategoryMapper} and
 * {@code DisclosureGroupMapper}, the three the charter's roster enumerates -- so a
 * reader moving between them meets two shapes inside one package. It is accepted because the
 * alternative forecloses both properties above, and because the charter's own account of the
 * static-versus-component split shows the package carries the two shapes deliberately rather than by
 * accident.
 * This class is the third of three lookup conversions written to the same shape, so the two it
 * stands beside, {@code UsPhoneAreaCodeMapper} and {@code UsStateMapper}, are where a reader
 * confirms that the convention is one convention.</p>
 *
 * <p>Assumptions: this class is the SOLE implementation of this conversion, and its callers are named
 * here because a reader arriving from the published route will not find them in the adapter.
 * {@code com.carddemo.reference.service.AddressLookupService} holds this bean as a final field, takes
 * it as a constructor parameter and reaches it on BOTH of this context's combination operations: as
 * the per-row render function its keyset browse hands to {@code ReferencePaging.page} in
 * {@code listZipPrefixes}, and directly on the single-combination read in {@code readZipPrefix}.
 * {@code AddressLookupController} imports no type from this package at all -- it validates its two
 * request parameters and delegates both routes to that service -- so the controller is where a reader
 * looks for the path and the service is where the projection is reached.</p>
 *
 * <p>⚠️ Refactoring Rationale: this paragraph used to state that the same single-row conversion was
 * ALSO implemented by a static {@code LookupMapper}, that {@code AddressLookupController} routed both
 * combination operations through that class, and concluded that <b>no call site in the delivered code
 * reached this class</b>. All three statements are false of the module as it stands, and the third was
 * the damaging one: it invited deletion of the one implementation both delivered combination
 * operations use, and it read as a standing instruction to reroute a controller that no longer touches
 * a mapper. No class named {@code LookupMapper} exists in this module -- it was deleted when the
 * keyset selection moved out of the controller into {@code AddressLookupService}, which the charter
 * records under <i>The six mappers, and why three of them are static</i> -- so the duplication the
 * paragraph disclosed is gone rather than merely re-described, and nothing replaces the disclosure.
 * Assumptions: what a reader needs at this position instead is the caller, which is why the paragraph
 * above names the injecting class and both of its call sites rather than only asserting that some
 * caller exists.</p>
 *
 * <p>Assumptions: there is no inbound member here, and the absence is a decision rather than
 * something outstanding. The charter records under <i>Deliberate omissions</i> that the three seeded
 * lookup tables carry none: these rows are seeded reference data, loaded by
 * {@code services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql} at its
 * L640, so the DTO package publishes no create and no update shape for them and there would be
 * nothing for an inbound member to accept. A reader comparing this class against
 * {@code TransactionTypeMapper}, which does carry one, finds the reason here instead of inferring an
 * omission.</p>
 *
 * <p>Assumptions: the caller that depends on this data reaches it across a service boundary over
 * HTTP and not by importing anything. The baseline's only user of these combinations is an
 * account-side address edit at {@code app/cbl/COACTUPC.cbl} L2536 to L2542, and the migration places
 * the equivalent address validation in account-service while this bounded context owns and seeds the
 * table, so the combination has to travel in the response body rather than being resolved locally by
 * the consumer. Nothing in this file imports any type from that context, and nothing may be added
 * that does: a cross-context domain import is forbidden in both directions, and the layering test
 * published by {@code common-lib} is what makes that enforceable rather than merely intended. Naming
 * the consumer is what invites the mistake, which is why the prohibition is restated beside it --
 * and the consuming context's own package name is deliberately not written anywhere in this file, so
 * that a search for it returns nothing at all.</p>
 *
 * <p>Assumptions: the four rationale labels used below are written in the one plural,
 * unparenthesised, unemphasised form that {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its
 * L230 to L253, and no other spelling of any of them appears in this file. A label is found by
 * literal search before it is read by a person, so a second spelling of one category would leave
 * that search silently partial.</p>
 */
@Component
public class UsStateZipPrefixMapper {

    /**
     * Renders one stored combination as the shape the contract publishes.
     *
     * <p>Assumptions: the argument is a loaded, non-null row whose single member is present.
     * {@code V1__reference.sql} declares {@code state_zip_cd CHAR(4) NOT NULL} on
     * {@code reference.us_state_zip_prefixes} and makes that same column the whole of the primary key
     * through {@code pk_us_state_zip_prefixes}, so the member has no absent case for this method to
     * substitute a value for. A null in that position means the row was never loaded, and reporting
     * it as a blank combination would hide exactly that. Refactoring Rationale: the column and the
     * constraint were cited by line number, and the comment-style rewrite that migration underwent
     * moved both citations past the declarations they named. A column name and a constraint name
     * survive that kind of edit and are what a reader searches a five-hundred-line schema by, so the
     * two names replace the two numbers.</p>
     *
     * <p>Assumptions: where the seeded set came from is recorded in the reference material itself,
     * in the comment immediately above the paragraph that consumes it, at
     * {@code app/cbl/COACTUPC.cbl} L2535. That line is cited rather than restated or characterised,
     * because what it says about the origin of the data is the baseline's own account of it and not a
     * finding of this migration. Everything under {@code app/} is read as the specification and
     * never rewritten, so nothing in this file is an edit to it; where this migration does depart
     * from the baseline the departure is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}, which is maintained elsewhere and
     * referenced from here rather than authored.</p>
     *
     * @param entity the stored {@code UsStateZipPrefix} row to render, carrying the four-character
     *     combination as its only member; must not be {@code null}
     * @return the {@code UsStateZipPrefixResponse} carrying that combination exactly as stored, never
     *     {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public UsStateZipPrefixResponse toResponse(UsStateZipPrefix entity) {
        // WHY : Assumptions: the four characters are ONE indivisible value and this member never
        //       takes them apart, in a field, in a local, in a positional slice or for any other
        //       purpose. app/cpy/CSLKPCDY.cpy L1071 opens the group
        //       01 US-STATE-ZIPCODE-TO-EDIT, its L1072 declares
        //       02 US-STATE-AND-FIRST-ZIP2 PIC X(4), and its L1073 attaches the condition name
        //       VALID-US-STATE-ZIP-CD2-COMBO to that whole four-character field rather than to
        //       either half of it. The stored column is state_zip_cd CHAR(4) on
        //       reference.us_state_zip_prefixes in V1__reference.sql, so a combination fills its
        //       declared width exactly and there is nothing to remove.
        // WHY : Assumptions: the edit settles it from the other side, and this is the decisive
        //       evidence rather than an appeal to how the field is declared. app/cbl/COACTUPC.cbl
        //       opens the paragraph at L2536 and ASSEMBLES the value: L2537 to L2540 string the
        //       two-character state code together with the first two characters of the postal code
        //       into the four-character field, and only then does L2542 ask
        //       IF VALID-US-STATE-ZIP-CD2-COMBO of the assembled whole. Those two names appear
        //       nowhere else in the reference material -- the field at its declaration and that one
        //       assembly, the condition name at its declaration and that one test -- so neither half
        //       is ever compared on its own anywhere in the baseline.
        // WHY : Alternatives Considered: a two-column model, a state column beside a two-digit
        //       postal-prefix column, either as a composite key or as two independent columns. It is
        //       rejected on the evidence above: two columns can express a pair the baseline has no
        //       way to evaluate, because the only test it performs is an equality against the
        //       concatenation, so the target column is the one CHAR(4) primary key state_zip_cd
        //       under pk_us_state_zip_prefixes in V1__reference.sql, and the domain and DTO packages
        //       both carry a single member. The corollary is worth stating because a reader comparing
        //       this file with
        //       TransactionCategoryMapper will look for it: that mapper composes a nested identity
        //       type for a genuinely composite key, whereas there is no identity object to build
        //       here at all, and its absence is a consequence of this ruling rather than an
        //       inconsistency between the two.
        // WHY : Assumptions: the three remaining postal characters have no target column and nothing
        //       here maps them. app/cpy/CSLKPCDY.cpy L1314 declares
        //       02 LAST-3-OF-ZIP           PIC X(3) as the second subordinate of the same L1071
        //       group, it carries no condition name, and that declaration is the only place the name
        //       occurs in the reference material -- no program reads it, writes it or compares it,
        //       and it takes no part in the L2542 test. Those characters belong to the value being
        //       validated rather than to the reference data, so the absence is recorded here rather
        //       than left to a reader who, counting a seven-character group against a
        //       four-character key, could otherwise read the difference as a column left unmapped.
        // WHY : Alternatives Considered: a numeric type for any part of the combination, which is
        //       not merely awkward here but unavailable. Every literal admitted at
        //       app/cpy/CSLKPCDY.cpy L1073 opens with two LETTERS, and letters have no integer
        //       value, so the value as a whole could not be represented numerically even in
        //       principle. The postal half could not be either, for a second and independent
        //       reason: its digits are positional, so a pair opening with a zero is a distinct
        //       combination from one that does not, and converting it would render the two alike.
        //       No conversion to a numeric type happens at any point below, not even transiently.
        // WHY : Assumptions: this member applies no membership test of its own, because membership is
        //       the question this table answers rather than a precondition for publishing one of its
        //       rows. Writing the admitted combinations into this file was the alternative and is
        //       rejected: the authoritative membership is the seeded table together with the
        //       condition name VALID-US-STATE-ZIP-CD2-COMBO at app/cpy/CSLKPCDY.cpy L1073, so a set
        //       written here would be a second source of truth with nothing comparing the two, free
        //       to drift from the seed the moment either moved.
        // WHY : Assumptions: one property of the refusal path is recorded because a consumer
        //       implementing the validation needs it early rather than late. When the combination
        //       test fails, app/cbl/COACTUPC.cbl raises TWO field-error flags, one for the state at
        //       L2546 and one for the postal code at L2547, against the single message literal
        //       visible at L2550, so a failed combination is not attributable to either field alone.
        //       Presenting that refusal is not this layer's concern: com.carddemo.common.error owns
        //       the error payload and its per-field array, and this module declares no second
        //       controller advice of its own, which the charter fixes under its heading
        //       "What this package does not do".
        String publishedCombination = entity.getStateZipCd();

        // WHY : Assumptions: this invokes the canonical constructor of a single-component record
        //       positionally, which the charter records as the calling convention throughout this
        //       package, in the DTO entry of its enumerated external contracts. The component is
        //       named stateZipCd on the response and the column is state_zip_cd, and no field is
        //       renamed in either direction anywhere in this package, which the charter settles where
        //       it rules that the category code is character data rather than numeric.
        return new UsStateZipPrefixResponse(publishedCombination);
    }

    /**
     * Renders a page of stored combinations, preserving the order they arrive in.
     *
     * <p>Assumptions: this yields the items alone. The first key, the last key and the more-pages
     * indicator of {@code com.carddemo.common.web.PageResponse} are assembled by
     * {@code com.carddemo.reference.service}, which the charter's <i>What this package does not do</i>
     * section fixes as the only
     * layer holding the keyset cursor and therefore the only one able to say whether a further page
     * exists; a mapper is handed rows and knows nothing about the query that produced them. That
     * envelope is also narrower than a caller may reach for -- it carries no previous-page flag and
     * no page-size member -- so neither can be obtained from this method by any route, and this file
     * deliberately does not import it.</p>
     *
     * <p>Alternatives Considered: the shape of the reply these rows travel in is stated plainly,
     * because the
     * three lookup conversions in this package read almost identically and a reader arriving at the
     * third will be looking for the respect in which it differs. Paging is not that respect, and
     * asserting an asymmetry here for the sake of a visible contrast was the alternative and is
     * rejected as untrue. The shape belongs to the DTO package, and
     * {@code UsStateZipPrefixResponse} records at its L63 to L67 that the published contract pages
     * BOTH of the seeded address lookups this context publishes;
     * {@code AddressLookupController} bears that out by declaring
     * {@code PageResponse<UsStateZipPrefixResponse>} on {@code listUsStateZipPrefixes} beside
     * {@code PageResponse<UsStateResponse>} on {@code listUsStates}, and {@code UsStateMapper}
     * records the same
     * conclusion from its own side on its own {@code toResponseList}. One paging idiom for both is
     * what lets a
     * caller write one loop instead of special-casing a route. What does distinguish this lookup is
     * recorded at those same DTO lines: its collection is the materially larger of the two, which is
     * what makes reading it a page at a time consequential here rather than merely uniform.</p>
     *
     * <p>Alternatives Considered: the keyset browse renders its page one row at a time rather than
     * through this member, and routing it through here was the alternative.
     * {@code AddressLookupService.listZipPrefixes} hands {@code toResponse} to
     * {@code ReferencePaging.page} as a per-row render function, because discarding the surplus probe
     * row and sealing both boundary positions have to stay in the layer that asked the repository for
     * the extra row -- a member handed a whole page cannot tell a probe row from a published one, and
     * would have to be told, which is a second place for one decision to live. What this member serves
     * is a caller holding a list that is already settled, and it is kept to the name, the order
     * guarantee and the null contract its two lookup counterparts use, so that the three conversions
     * in this package present one list idiom rather than three.</p>
     *
     * <p>Trade-offs: an empty input yields an empty list, while a {@code null} input propagates a
     * {@code NullPointerException} rather than being folded into one. Folding it would make a
     * genuinely empty page and a defect that returned nothing at all report identically, and the
     * second would then reach a caller as a page with no rows instead of as a fault anyone could act
     * on. The returned list is unmodifiable because {@code java.util.stream.Stream#toList()}
     * specifies an unmodifiable result, so a caller needing to sort or extend it copies it first;
     * that costs a copy at the one call site which would need it and removes the possibility of a
     * shared response list being mutated after it is built.</p>
     *
     * @param entities the {@code List} of stored {@code UsStateZipPrefix} rows to render, in the
     *     order they are to be published; must not be {@code null}, and every element must be a
     *     loaded row
     * @return an unmodifiable {@code List} of {@code UsStateZipPrefixResponse} in the same order,
     *     empty when the input was empty, never {@code null}
     * @throws NullPointerException if {@code entities} is {@code null} or holds a {@code null}
     *     element
     */
    public List<UsStateZipPrefixResponse> toResponseList(List<UsStateZipPrefix> entities) {
        // WHY : Assumptions: the caller's order is preserved and nothing is sorted here. A backward
        //       page is read in descending key order -- UsStateZipPrefixRepository declares
        //       findByStateZipCdLessThanOrderByStateZipCdDesc at its L49 against the ascending
        //       findByStateZipCdGreaterThanOrderByStateZipCdAsc at its L39 -- and is reversed before
        //       it is rendered, by the reversed helper AddressLookupService.listZipPrefixes applies
        //       to a backward walk, so a sort applied at this point would undo that reversal silently
        //       and hand a backward page back in the wrong direction.
        // WHY : Assumptions: the per-row conversion is delegated to the member above by reference
        //       rather than repeated here, which is what keeps the single-row rulings recorded there
        //       -- above all the indivisibility of the four characters, anchored on
        //       app/cpy/CSLKPCDY.cpy L1072 and app/cbl/COACTUPC.cbl L2540 -- from acquiring a second
        //       implementation free to drift from the first. The name and shape of this member match
        //       UsStateMapper.toResponseList and UsPhoneAreaCodeMapper.toResponseList deliberately
        //       rather than by coincidence, so that the conversions in this package present one list
        //       idiom to a reader.
        return entities.stream().map(this::toResponse).toList();
    }
}
