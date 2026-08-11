package com.carddemo.reference.mapper;

import com.carddemo.reference.domain.UsState;
import com.carddemo.reference.dto.UsStateResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders a seeded United States state or territory code as its published shape.
 *
 * <p>Purpose: the single place the state row's representation concerns are resolved. One member is
 * carried, because one member is the whole row, so the substance of this class is not the assignment
 * but the reason the value crosses unaltered -- and that reason is recorded at the line which
 * publishes it rather than gathered into this header.</p>
 *
 * <p>Assumptions: every class in this package is written by hand and no code generator is introduced
 * anywhere in it. The package-scope rulings this class applies are settled once in this package's own
 * {@code package-info.java} -- the hand-written charter, the rejection of MapStruct and, on a separate
 * ground, of Lombok, the trim boundary that follows the column type at its L303 to L318, the
 * items-only boundary at its L457 to L462, and the absence of an inbound member on the seeded lookups
 * at its L497 to L500 -- and they are cited here rather than argued again. What this file adds is the
 * evidence specific to this one code.</p>
 *
 * <p>Alternatives Considered: this class is a Spring {@code @Component} with instance members and is
 * deliberately not declared {@code final}, rather than the {@code final} class with a private
 * constructor and static members that the charter fixes for the four entity conversions at its own
 * L197 to L223. The static shape was the alternative and was not taken here. The charter already
 * admits this second shape for {@code DateInquiryReplyMapper} at its L161 to L164, and records at its
 * L222 to L223 that a component is left non-final precisely so that it remains proxyable; both of
 * those properties are wanted here. This conversion is reached by constructor injection, which is the
 * dependency-injection pattern the Agent Action Plan fixes for this migration in place of the
 * baseline's static linkage, and an injected bean can be substituted in a slice test where a static
 * member cannot.</p>
 *
 * <p>Trade-offs: the cost of that choice is that this class does not read identically to its four
 * static siblings -- {@code TransactionTypeMapper}, {@code TransactionCategoryMapper},
 * {@code DisclosureGroupMapper} and {@code LookupMapper}, enumerated in the charter's roster at its
 * L142 to L160 -- so a reader moving between them meets two shapes inside one package. It is accepted
 * because the alternative forecloses both properties above, and because the charter's own exception at
 * its L161 to L164 shows the package already carries the two shapes deliberately rather than by
 * accident.</p>
 *
 * <p>Assumptions: this is the SAME single-row conversion {@code LookupMapper} performs at its L53 to
 * L55, and the duplication is stated rather than glossed, because a reader finding two
 * implementations of one conversion needs to know which one is reached.
 * {@code AddressLookupController} routes both of its state operations through
 * {@code LookupMapper} -- as a method reference on the paged route at its L287 and directly on the
 * single-code route at its L306 -- so <b>no call site in the delivered code reaches this class</b>.
 * What it adds over that sibling is the list member and the per-entity home for the rulings below,
 * which is the reason it exists as a bean rather than as a fourth static member on
 * {@code LookupMapper}. Trade-offs: an unreached conversion is dead weight and is recorded as such
 * here rather than defended; removing it or routing the controller through it are both single-caller
 * changes, and either is preferable to leaving a reader to guess which member the state route uses.
 * The charter records the identical position for this class's area-code counterpart at its L166 to
 * L178.</p>
 *
 * <p>Assumptions: there is no inbound member here, and the absence is a decision rather than
 * something outstanding. The charter records at its L497 to L500 that the three seeded lookup tables
 * carry none: these rows are seeded reference data, loaded by
 * {@code services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql}, so the
 * DTO package publishes no create and no update shape for them and there would be nothing for an
 * inbound member to accept. A reader comparing this class against {@code TransactionTypeMapper},
 * which does carry one, finds the reason here instead of inferring an omission.</p>
 *
 * <p>Assumptions: the caller that depends on this data reaches it across a service boundary over
 * HTTP and not by importing anything. The baseline's only user of these codes is an account-side
 * address edit at {@code app/cbl/COACTUPC.cbl} L2493 to L2495, and the migration places the
 * equivalent address validation in account-service while this bounded context owns the table, so the
 * code has to travel in the response body rather than being resolved locally by the consumer.
 * Nothing in this file imports any type from the account context, and nothing may be added that
 * does: a cross-context domain import is forbidden in both directions, and the layering test
 * published by {@code common-lib} is what makes that enforceable rather than merely intended. Naming
 * the consumer is what invites the mistake, which is why the prohibition is restated beside it -- and
 * the account context's own package name is deliberately not written anywhere in this file, so that a
 * search for it returns nothing at all.</p>
 *
 * <p>Assumptions: the four rationale labels used below are written in the one plural,
 * unparenthesised, unemphasised form that {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its
 * L230 to L251, and no other spelling of any of them appears in this file. A label is found by
 * literal search before it is read by a person, so a second spelling of one category would leave that
 * search silently partial.</p>
 */
@Component
public class UsStateMapper {

    /**
     * Renders one stored state or territory code as the shape the contract publishes.
     *
     * <p>Assumptions: the argument is a loaded, non-null row whose single member is present.
     * {@code V1__reference.sql} declares {@code state_cd CHAR(2) NOT NULL} at its L438 and makes that
     * same column the primary key through {@code pk_us_states} at its L440, so the member has no
     * absent case for this method to substitute a value for. A null in that position means the row
     * was never loaded, and reporting it as a blank code would hide exactly that.</p>
     *
     * @param entity the stored {@code UsState} row to render, carrying the two-character state or
     *     territory code as its only member; must not be {@code null}
     * @return the {@code UsStateResponse} carrying that code exactly as stored, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public UsStateResponse toResponse(UsState entity) {
        // WHY : Assumptions: the code is published exactly as stored, with no normalisation of any
        //       kind applied to it. app/cpy/CSLKPCDY.cpy L1012 declares
        //       01 US-STATE-CODE-TO-EDIT  PIC X(2), and its L1013 condition name
        //       VALID-US-STATE-CODE carries the admitted literals over that item, every one of them
        //       quoted and compared as two characters. The stored column is CHAR(2) at
        //       V1__reference.sql L438, so a code fills its whole declared width and there is no
        //       padding to remove; this package's charter settles at its L309 to L314 that a key of
        //       declared width is never altered in a way that could change its value, in either
        //       direction.
        // WHY : Assumptions: the baseline settles that by contrast rather than by assertion, which is
        //       why two line ranges of one program are cited together instead of one.
        //       app/cbl/COACTUPC.cbl opens the state edit at L2493, moves the candidate address value
        //       straight into the two-character item at L2494 with no intervening function, and tests
        //       the condition at L2495. The area-code edit in that same program does not: at L2296 to
        //       L2297 it moves a TRIMMED candidate before testing its own condition at L2298. Two
        //       edits sitting inside one program and treating their inputs differently is what
        //       establishes that this code is meant to be compared at its declared width, and it is
        //       far stronger evidence than an appeal to declared-width semantics in general. That is
        //       why no normalisation member exists in this file for a caller to reach for.
        // WHY : Assumptions: the consequence of altering the value is silent, which is why it is
        //       recorded at this line rather than left to the column type to imply.
        //       V1__reference.sql records at its L431 to L437 that bpchar ignores trailing blanks, so
        //       a probe arriving as 'AL ' from a declared-width source still matches its row, whereas
        //       under a varying-width column the same probe returns nothing -- and raises no error
        //       either, because a trailing blank is truncated away rather than rejected. A value
        //       altered here would therefore not fail here at all; it would surface as an address
        //       that another service reports as invalid.
        // WHY : Alternatives Considered: a numeric type for the code, which is not merely awkward
        //       here but unavailable. The literals between app/cpy/CSLKPCDY.cpy L1014 and L1069 are
        //       alphabetic throughout, and 'AL' has no integer value at all, so this domain could not
        //       be represented numerically even in principle. No conversion to a numeric type happens
        //       at any point below, not even transiently.
        // WHY : Assumptions: this member is domain-agnostic. It renders whatever row it is handed and
        //       applies no membership test of its own, because membership is the question this table
        //       answers rather than a precondition for publishing one of its rows. The failure path
        //       at app/cbl/COACTUPC.cbl L2498 to L2499 raises the input-error and the state
        //       field-error flags, which shows the value is a validation key rather than descriptive
        //       text; presenting that refusal is not this layer's concern either, because
        //       com.carddemo.common.error owns the error payload and this module declares no second
        //       controller advice of its own.
        // WHY : Alternatives Considered: testing the code against a set of literals written into this
        //       file, rejected on two independent grounds. The authoritative membership is the seeded
        //       table together with the condition name VALID-US-STATE-CODE at
        //       app/cpy/CSLKPCDY.cpy L1013, so a set written here would be a second source of truth
        //       with nothing comparing the two, free to drift from the seed the moment either moved.
        //       And a set admitting the states alone would refuse rows the baseline's own condition
        //       name admits: that list carries 'DC' at L1064 and then 'AS', 'GU', 'MP', 'PR' and
        //       'VI' at L1065 through L1069, not one of which is a state and every one of which is a
        //       member of the same condition name as the states. Refusing them would change
        //       observable behaviour, and it would fail quietly -- a refused territory address
        //       presents as a data-entry mistake rather than as a narrowed allow-list.
        String publishedCode = entity.getStateCode();

        // WHY : Assumptions: this invokes the canonical constructor of a single-component record
        //       positionally, which the charter records at its L528 to L529 as the calling convention
        //       throughout this package. One naming detail is worth stating because it reads as a
        //       slip and is not: the column is state_cd, this entity's member is stateCode, and the
        //       record component is stateCd. The DTO package owns the published name, and the charter
        //       settles at its L536 to L542 that this package follows those names rather than
        //       renaming either side to make the group look uniform.
        return new UsStateResponse(publishedCode);
    }

    /**
     * Renders a page of stored state and territory codes, preserving the order they arrive in.
     *
     * <p>Assumptions: this yields the items alone. The first key, the last key and the more-pages
     * indicator of {@code com.carddemo.common.web.PageResponse} are assembled outside this package,
     * which the charter fixes at its L457 to L462 on the ground that only the layer holding the
     * keyset cursor can say whether a further page exists; a mapper is handed rows and knows nothing
     * about the query that produced them. That is the reason this file does not import that envelope,
     * and it holds whatever shape the reply takes.</p>
     *
     * <p>Alternatives Considered: the shape of the reply this feeds is worth stating plainly, because
     * a small bounded seeded set invites the assumption that it is answered in one piece. Publishing
     * these codes as a single unpaged array was evaluated and rejected, and the decision belongs to
     * the DTO package rather than to this one: {@code UsStateResponse} records it at its L12 to L19,
     * on the ground that the published contract declares the list operation as returning
     * {@code UsStatePage} and that the two address lookups beside it page the same way, so a caller
     * writes one paging loop for all three instead of special-casing this one. The reply is therefore
     * a keyset page -- {@code AddressLookupController} declares
     * {@code PageResponse<UsStateResponse>} at its L262 and mints the page's positions at its L283 to
     * L287 -- and the seeded list can outgrow a single reply without altering a shape a client
     * already reads. There is accordingly no paging asymmetry between the three lookup conversions
     * for a reader to account for.</p>
     *
     * <p>Trade-offs: an empty input yields an empty list, while a {@code null} input propagates a
     * {@code NullPointerException} rather than being folded into one. Folding it would make a
     * genuinely empty page and a defect that returned nothing at all report identically, and the
     * second would then reach a caller as a page with no rows instead of as a fault anyone could act
     * on. The returned list is unmodifiable because {@code java.util.stream.Stream#toList()} specifies
     * an unmodifiable result, so a caller needing to sort or extend it copies it first; that costs a
     * copy at the one call site which would need it and removes the possibility of a shared response
     * list being mutated after it is built.</p>
     *
     * @param entities the {@code List} of stored {@code UsState} rows to render, in the order they
     *     are to be published; must not be {@code null}, and every element must be a loaded row
     * @return an unmodifiable {@code List} of {@code UsStateResponse} in the same order, empty when
     *     the input was empty, never {@code null}
     * @throws NullPointerException if {@code entities} is {@code null} or holds a {@code null}
     *     element
     */
    public List<UsStateResponse> toResponseList(List<UsState> entities) {
        // WHY : Assumptions: the caller's order is preserved and nothing is sorted here. A backward
        //       page is read in descending key order -- UsStateRepository declares
        //       findByStateCodeLessThanOrderByStateCodeDesc at its L47 against the ascending
        //       findByStateCodeGreaterThanOrderByStateCodeAsc at its L38 -- and is reversed by its
        //       caller before it is rendered, so a sort applied at this point would undo that
        //       reversal silently and hand a backward page back in the wrong direction.
        // WHY : Assumptions: the per-row conversion is delegated to the member above by reference
        //       rather than repeated here, which is what keeps the single-row rulings recorded there
        //       -- the untouched declared-width code anchored on app/cpy/CSLKPCDY.cpy L1012 and the
        //       refusal to test the condition name at its L1013 -- from acquiring a second
        //       implementation free to drift from the first. The name and shape of this member match
        //       UsPhoneAreaCodeMapper.toResponseList at its L194 deliberately rather than by
        //       coincidence, so that the conversions in this package present one list idiom to a
        //       reader.
        return entities.stream().map(this::toResponse).toList();
    }
}
