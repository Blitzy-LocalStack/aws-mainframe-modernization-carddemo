package com.carddemo.reference.mapper;

import com.carddemo.reference.domain.UsPhoneAreaCode;
import com.carddemo.reference.dto.PhoneAreaCodeResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders a seeded North American telephone area code, with the sub-list it belongs to, as its
 * published shape.
 *
 * <p>Purpose: the single place the area-code row's representation concerns are resolved. Only two
 * members are carried, so the substance of this class is not the assignment but the reason the second
 * member exists at all -- the classification is the discriminator that the baseline's one real
 * area-code edit turns on, and that evidence is recorded at the line which publishes it rather than
 * gathered into this header.</p>
 *
 * <p>Assumptions: every class in this package is written by hand and no code generator is introduced
 * anywhere in it. The package-scope rulings this class applies are settled once in this package's own
 * {@code package-info.java} -- the hand-written charter, the rejection of MapStruct and, on a separate
 * ground, of Lombok, the trim boundary that follows the column type at its L303 to L318, and the
 * items-only boundary at its L457 to L462 -- and they are cited here rather than argued again. What
 * this file adds is the evidence specific to this one record.</p>
 *
 * <p>Alternatives Considered: this class is a Spring {@code @Component} with instance members and is
 * deliberately not declared {@code final}, rather than the {@code final} class with a private
 * constructor and static members that the charter fixes for the four entity conversions at its own
 * L197 to L223. The static shape was the alternative and was not taken here. The charter already
 * admits this second shape for {@code DateInquiryReplyMapper} at its L161 to L164, and records at its
 * L222 to L223 that the component is left non-final precisely so that it remains proxyable; both of
 * those properties are wanted here. This conversion is reached by constructor injection, which is the
 * dependency-injection pattern the Agent Action Plan fixes for this migration in place of the
 * baseline's static linkage, and an injected bean can be substituted in a slice test where a static
 * member cannot.</p>
 *
 * <p>Trade-offs: the cost of that choice is that this class does not read identically to its four
 * static siblings, so a reader moving between them meets two shapes inside one package. It is
 * accepted because the alternative forecloses both properties above, and because the charter's own
 * exception shows the package already carries the two shapes deliberately rather than by accident.</p>
 *
 * <p>Assumptions: one naming asymmetry is called out because it reads as a mistake and is not. This
 * class is named for the entity {@code UsPhoneAreaCode}, while the type it produces is
 * {@code PhoneAreaCodeResponse} with no geographic prefix, and the charter settles at its L536 to
 * L542 that the DTO package owns that name and this package follows it rather than renaming either
 * side to make the pair look uniform.</p>
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
 * address edit, and the migration places the equivalent address validation in account-service while
 * this bounded context owns the table, so the classification has to travel with the code in the
 * response body rather than being resolved locally by the consumer. Nothing in this file imports any
 * type from the account context, and nothing may be added that does: a cross-context domain import is
 * forbidden in both directions, and the layering test published by {@code common-lib} is what makes
 * that enforceable rather than merely intended. Naming the consumer is what invites the mistake,
 * which is why the prohibition is restated beside it -- and the account context's own package name is
 * deliberately not written anywhere in this file, so that a search for it returns nothing at all.</p>
 *
 * <p>Assumptions: the four rationale labels used below are written in the one plural,
 * unparenthesised, unemphasised form that {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its
 * L217 to L245, and no other spelling of any of them appears in this file. A label is found by
 * literal search before it is read by a person, so a second spelling of one category would leave that
 * search silently partial.</p>
 */
@Component
public class UsPhoneAreaCodeMapper {

    /**
     * Renders one stored area code as the shape the contract publishes.
     *
     * <p>Assumptions: the argument is a loaded, non-null row whose two members are both present.
     * {@code V1__reference.sql} declares {@code area_cd CHAR(3) NOT NULL} and
     * {@code code_class CHAR(1) NOT NULL} on {@code reference.us_phone_area_codes}, and restricts the
     * second to two values through the {@code ck_us_phone_area_codes_class} check constraint, so
     * neither member has an absent case for this method to substitute a value for. A null in either
     * position means the row was never loaded, and reporting that as a blank code or as an
     * unclassified one would hide it.</p>
     *
     * @param entity the stored {@code UsPhoneAreaCode} row to render, carrying the three-character
     *     code and its single-character classification; must not be {@code null}
     * @return the {@code PhoneAreaCodeResponse} carrying the code exactly as stored together with the
     *     named state of its classification, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws IllegalArgumentException if the stored classification is absent or is neither of the
     *     two characters the contract publishes, raised by the response type's own conversion rather
     *     than by this method
     */
    public PhoneAreaCodeResponse toResponse(UsPhoneAreaCode entity) {
        // WHY : Assumptions: the code is published exactly as stored and untrimmed, because its width
        //       is part of the contract rather than presentation. app/cpy/CSLKPCDY.cpy L24 declares
        //       01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX -- written as three literal X characters,
        //       which is the same picture as a three-character alphanumeric item -- and every literal
        //       on the lists carried over that field is quoted and compared as characters. The stored
        //       column is CHAR(3), so a code fills its whole declared width and there is no padding
        //       to remove; the charter's trim boundary at its L309 to L314 holds that a code of
        //       declared width is never trimmed in a way that could change its value, in either
        //       direction.
        // WHY : Alternatives Considered: a numeric type for the code, rejected outright. The value is
        //       compared as text everywhere the baseline uses it, and rendered numerically a code
        //       carrying a leading zero would lose it -- yielding a key that locates no row and that a
        //       caller cannot echo back to address the row it has just read. No conversion to a
        //       numeric type happens here, not even transiently.
        String publishedCode = entity.getAreaCode();

        // WHY : Assumptions: the classification is carried BECAUSE the baseline's one real area-code
        //       edit turns on it, which is what makes it a discriminator rather than descriptive
        //       metadata. app/cpy/CSLKPCDY.cpy hangs three condition names off the single field at its
        //       L24: the master list VALID-PHONE-AREA-CODE at L30, the general-purpose sub-list
        //       VALID-GENERAL-PURP-CODE at L521, and the easily-recognisable sub-list
        //       VALID-EASY-RECOG-AREA-CODE at L931. Exactly one of those three is ever tested anywhere
        //       in the baseline: app/cbl/COACTUPC.cbl is the only program that copies the book at all,
        //       at its L602, and it moves a trimmed candidate into that field at L2296 to L2297 and
        //       then tests VALID-GENERAL-PURP-CODE at L2298 and nothing else. The master list and the
        //       easily-recognisable sub-list are declared and never tested.
        // WHY : Assumptions: the failure path is what settles that the narrowing is observable rather
        //       than incidental. COACTUPC.cbl L2301 to L2302 raise the input-error and the field-level
        //       error flags, and L2304 to L2308 build a message whose literal names the general-purpose
        //       class explicitly. Because that text reaches a user, widening or narrowing the filter
        //       would change observable behaviour, so a consumer reproducing the edit has to know
        //       which sub-list a code came from -- and it cannot recover that from the digits of the
        //       code. Publishing the classification is what lets it reproduce the narrower test.
        // WHY : Alternatives Considered: filtering here, so that only general-purpose codes were ever
        //       emitted. Rejected on three counts. It would leave the lookup surface unable to serve
        //       any other consumer of the seeded rows; it would hide from the caller that a narrowing
        //       had occurred, because a filtered page and a complete one are indistinguishable in the
        //       response; and it would place a validation policy decision in the anti-corruption
        //       layer, whose charter is representation and not policy. Filtering belongs to the query
        //       or to the caller, and the query already offers it -- UsPhoneAreaCodeRepository declares
        //       the class-restricted browse findByCodeClassOrderByAreaCodeAsc at its L58 -- so a filter
        //       here would duplicate a capability that already exists at the layer entitled to hold it.
        // WHY : Alternatives Considered: reading the stored character here and selecting the named
        //       state for it, by comparing against UsPhoneAreaCode.CODE_CLASS_GENERAL_PURPOSE and
        //       CODE_CLASS_EASILY_RECOGNISABLE. Rejected because the response type reserves that
        //       conversion to itself: PhoneAreaCodeResponse records at its L78 to L83 that its
        //       string-accepting constructor exists so that the one place a stored character becomes a
        //       named state is that boundary, rather than each mapping step reading the character and
        //       choosing a state for itself. Deciding it again here would be a second decode site,
        //       free to disagree with the first about an unrecognised value. The stored character is
        //       therefore handed over as it stands, and no classification literal is re-declared in
        //       this file; where a value has to be named, the entity's own constants are the names.
        // WHY : Assumptions: the two representations are settled in two different places and this
        //       method only bridges them. The entity holds the classification as a String with named
        //       constants, which UsPhoneAreaCode records at its L176 to L181 as chosen over a boolean
        //       and over a persisted enumeration that would bind the stored letter to a Java constant
        //       name; the published contract names the two states instead, which PhoneAreaCodeResponse
        //       argues for at its L101 to L116. Neither of those rulings is re-decided here.
        String storedClassification = entity.getCodeClass();

        // WHY : Assumptions: this call resolves to the string-accepting constructor the response type
        //       declares at its L90 to L92 rather than to its canonical one, because both arguments
        //       are character data, and that selection is the point of the paragraph above rather than
        //       an accident of overload resolution. Both constructors take the code first and the
        //       classification second, which is the component order the charter records at its L528 to
        //       L529 as being invoked positionally throughout this package; reversing the two
        //       arguments here would still compile and would be wrong.
        return new PhoneAreaCodeResponse(publishedCode, storedClassification);
    }

    /**
     * Renders a page of stored area codes, preserving the order they arrive in.
     *
     * <p>Assumptions: this yields the items alone. The first key, the last key and the more-pages
     * indicator of {@code com.carddemo.common.web.PageResponse} are assembled by
     * {@code com.carddemo.reference.service}, which the charter fixes at its L457 to L462 as the only
     * layer holding the keyset cursor and therefore the only one able to say whether a further page
     * exists; a mapper is handed rows and knows nothing about the query that produced them. That
     * envelope is also narrower than a caller may reach for -- it carries no previous-page flag and no
     * page-size member -- so neither can be obtained from this method by any route, and this file
     * deliberately does not import it.</p>
     *
     * <p>Trade-offs: an empty input yields an empty list, while a {@code null} input propagates a
     * {@code NullPointerException} rather than being folded into one. Folding it would make a
     * genuinely empty page and a defect that returned nothing at all report identically, and the
     * second would then reach a caller as a page with no rows instead of as a fault anyone could act
     * on. The returned list is unmodifiable, so a caller needing to sort or extend it copies it
     * first; that costs a copy at the one call site which would need it and removes the possibility
     * of a shared response list being mutated after it is built.</p>
     *
     * @param entities the {@code List} of stored {@code UsPhoneAreaCode} rows to render, in the order
     *     they are to be published; must not be {@code null}, and every element must be a loaded row
     * @return an unmodifiable {@code List} of {@code PhoneAreaCodeResponse} in the same order, empty
     *     when the input was empty, never {@code null}
     * @throws NullPointerException if {@code entities} is {@code null} or holds a {@code null} element
     * @throws IllegalArgumentException if any element carries a classification outside the two the
     *     contract publishes, raised by the response type's own conversion
     */
    public List<PhoneAreaCodeResponse> toResponseList(List<UsPhoneAreaCode> entities) {
        // WHY : Assumptions: the caller's order is preserved and nothing is sorted here. A backward
        //       browse is read in descending key order -- UsPhoneAreaCodeRepository declares
        //       findByAreaCodeLessThanOrderByAreaCodeDesc at its L49 -- and is reversed by its caller
        //       before it is rendered, at AddressLookupController L170 and L178, so a sort applied at
        //       this point would undo that reversal silently and hand a backward page back in the
        //       wrong direction.
        // WHY : Assumptions: the name and shape of this member match the transaction-type mapper's
        //       list member deliberately rather than by coincidence, so that the conversions in this
        //       package present one list idiom to a reader. The per-row conversion is delegated to the
        //       member above by reference rather than repeated, which is what keeps the single-row
        //       rulings recorded there -- the untrimmed code and the classification -- from acquiring
        //       a second implementation free to drift from the first.
        return entities.stream().map(this::toResponse).toList();
    }
}
