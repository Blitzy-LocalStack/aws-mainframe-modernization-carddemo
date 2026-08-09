package com.carddemo.reference.mapper;

import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.TransactionTypeCreateRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.dto.TransactionTypeUpdateRequest;
import java.util.List;

/**
 * Converts between the transaction-type entity and its wire shapes.
 *
 * <p>Purpose: the single place the transaction-type record's representation concerns are resolved.
 * Two of those concerns pull in opposite directions, and that opposition is most of the content of
 * this class. The two-character code is a fixed-width key whose leading zero is data, so it is
 * carried across untouched. The fifty-character description is a variable-width field whose trailing
 * blanks are padding to a record length, so it is normalised. A reader who applies either ruling to
 * the other field either breaks a key or publishes padding, which is why both are stated here at the
 * line each one governs.</p>
 *
 * <p>Assumptions: every class in this package is written by hand and no code generator is introduced
 * anywhere in it. The package-scope rulings this class applies are settled once in this package's own
 * {@code package-info.java} -- the hand-written charter, the rejection of MapStruct and, on a separate
 * ground, of Lombok, the static-versus-component class shape, the trim boundary and the register of
 * dropped padding -- and they are cited here rather than argued again. What this file adds is the
 * evidence specific to this one record.</p>
 *
 * <p>Assumptions: this class is {@code final} with a private constructor and static members rather
 * than a Spring {@code @Component}, which is the shape the charter fixes for the four entity
 * conversions at its own L139 to L156. Each of the four is a total function of its argument, with no
 * collaborator, no configuration and no state between calls. {@code DateInquiryReplyMapper} alone in
 * this package is a component and alone is not {@code final}, so that it stays proxyable; that
 * exception is recorded there and does not reach this class.</p>
 *
 * <p>Assumptions: the eight-byte padding item at {@code app/cpy/CVTRA03Y.cpy} L7,
 * {@code 05 FILLER PIC X(08)}, reaches no column, and the drop is registered here once rather than
 * left to inference. The arithmetic is given so the claim is checkable rather than asserted: the
 * record declares {@code TRAN-TYPE PIC X(02)} at L5 and {@code TRAN-TYPE-DESC PIC X(50)} at L6, and
 * 2 + 50 + 8 = 60, the record length its L2 header comment states. The seed agrees byte for byte --
 * every row of {@code app/data/ASCII/trantype.txt} is sixty characters of which the last eight are
 * that padding. Those bytes exist so the following record begins at a predictable offset; a row in an
 * individually typed, separately addressable column needs no such padding, so they carry nothing a
 * consumer could act on.</p>
 *
 * <p>Assumptions: the two columns this class writes are
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} L106
 * {@code type_cd CHAR(2) NOT NULL} and L117 {@code description VARCHAR(50) NOT NULL}, with L151
 * {@code version BIGINT NOT NULL DEFAULT 0} carrying the revision. That migration is the authority for
 * the physical shape of this schema; where it and this file could ever disagree, the migration is
 * right.</p>
 *
 * <p>Assumptions: the four rationale labels used below are written in the one plural, unparenthesised,
 * unemphasised form that {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its L218 to L245, and no
 * other spelling of any of them appears in this file. A label is found by literal search before it is
 * read by a person, so a second spelling of one category would leave that search silently partial.</p>
 */
public final class TransactionTypeMapper {

    /**
     * Prevents instantiation of a type whose whole content is static.
     *
     * @throws AssertionError always, so a reflective instantiation fails loudly rather than yielding a
     *     useless instance
     */
    private TransactionTypeMapper() {
        // WHY : Assumptions: a private constructor rather than an abstract class, because an abstract
        //       class invites a subclass and this type has no behaviour to extend.
        throw new AssertionError("TransactionTypeMapper is not instantiable");
    }

    /**
     * Renders one stored type as the shape the contract publishes.
     *
     * <p>Assumptions: the argument is a loaded, non-null row whose description is non-null, because
     * {@code V1__reference.sql} L117 declares that column {@code NOT NULL} and the entity repeats the
     * constraint as {@code nullable = false}. Nothing is substituted here for a value the schema will
     * not permit to be absent; a null would mean the row was never loaded, and reporting that as an
     * empty description would hide it.</p>
     *
     * @param entity the stored row to render; must not be {@code null}
     * @return the response shape carrying the code, the description and the stored revision, never
     *     {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public static TransactionTypeResponse toResponse(TransactionType entity) {
        // WHY : Assumptions: the code is published exactly as stored, and the call below cannot alter
        //       it. app/app-transaction-type-db2/ddl/TRNTYPE.ddl L2 declares TR_TYPE CHAR(2) NOT NULL
        //       and every seed code in app/data/ASCII/trantype.txt fills both of bytes 1 and 2, 01
        //       through 07, so a valid code occupies the whole column and leaves nothing to remove.
        //       The distinction the trim boundary draws is not whether this member is called but
        //       whether calling it could change a value: on a ten-character account group identifier
        //       the same call would not be a no-op, which is exactly why DisclosureGroupMapper does
        //       not make it there.
        // WHY : Alternatives Considered: an int or a short for the code, rejected on that same seed
        //       evidence. The leading zero is part of the value and not presentation -- rendered
        //       numerically, 01 becomes 1, a key that locates no row and that a caller cannot echo
        //       back to address the record it has just read.
        String publishedCode = trimTrailing(entity.getTypeCd());

        // WHY : Assumptions: the stored description is returned as it stands and is never re-padded to
        //       fifty, because the column is VARCHAR and the trailing blanks of the source field were
        //       padding to a fixed record length rather than content. This is a divergence and is
        //       stated as one rather than smoothed over: the baseline moves the raw fifty-byte field
        //       straight to its map at app/app-transaction-type-db2/cbl/COTRTUPC.cbl L1200 and at
        //       COTRTLIC.cbl L1417, so a 3270 field receives fifty characters where a response body
        //       here carries only the content. The baseline does what those two lines say, this
        //       package does what the charter's trim boundary says, and the difference is registered
        //       in docs/architecture/cobol-to-service-traceability.md, which is the one place such a
        //       difference is recorded. Everything under app/ is reference material this migration
        //       reads and never rewrites, so nothing above describes an edit made to it.
        String publishedDescription = trimTrailing(entity.getDescription());

        // WHY : Assumptions: the revision is read off the entity rather than derived. It is the
        //       version member the persistence provider maintains, and the replace operation answers
        //       409 carrying it -- TransactionTypeService compares it at its own L283 and reports the
        //       stored value at L285 -- so a caller needs the number the row actually holds in order
        //       to retry against the revision that exists.
        return new TransactionTypeResponse(publishedCode, publishedDescription, entity.getVersion());
    }

    /**
     * Renders a page of stored types, preserving the order they arrive in.
     *
     * <p>Assumptions: this yields the items alone. The first key, the last key and the more-pages
     * indicator of {@code com.carddemo.common.web.PageResponse} are assembled by
     * {@code com.carddemo.reference.service}, the only layer that holds the keyset cursor and so the
     * only one able to say whether a further page exists; a mapper is handed rows and knows nothing
     * about the query that produced them. Two members a caller might reach for are absent from that
     * envelope altogether -- it carries no previous-page flag and no page-size member -- so neither can
     * be obtained from this method by any route.</p>
     *
     * <p>Trade-offs: an empty input yields an empty list, while a {@code null} input propagates a
     * {@code NullPointerException} instead of being folded into one. Folding it would make a genuinely
     * empty page and a defect that returned nothing at all report identically, and the second would
     * then reach a caller as a page with no rows rather than as a fault anyone could act on. The
     * returned list is unmodifiable, so a caller needing to sort or extend it copies it first; that
     * costs a copy at the one call site that would need it and removes the possibility of a shared
     * response list being mutated after it is built.</p>
     *
     * @param entities the rows to render, in the order they are to be published; must not be
     *     {@code null}, and every element must be a loaded row
     * @return an unmodifiable list of response shapes in the same order, empty when the input was
     *     empty, never {@code null}
     * @throws NullPointerException if {@code entities} is {@code null} or holds a {@code null} element
     */
    public static List<TransactionTypeResponse> toResponseList(List<TransactionType> entities) {
        // WHY : Assumptions: the caller's order is preserved and nothing is sorted here. A backward
        //       page is read in descending key order and reversed by the service before it is
        //       rendered, at TransactionTypeService L177, so a sort applied at this point would undo
        //       that silently and hand a backward page back in the wrong direction.
        return entities.stream().map(TransactionTypeMapper::toResponse).toList();
    }

    /**
     * Builds a new entity from a create request.
     *
     * <p>Assumptions: the create shape carries the key in the body, which is what distinguishes it
     * from the replace shape. {@code TransactionTypeCreateRequest} declares both the code and the
     * description as components, and its code component is constrained to exactly two characters
     * matching a two-digit pattern, so a code that lost its leading zero upstream is refused by
     * validation before it reaches this method rather than being stored as a second row for one
     * logical type.</p>
     *
     * <p>Assumptions: the revision is neither taken from the request nor set here, and it cannot be.
     * The create shape carries no revision component -- there is no prior revision to state for a row
     * that does not yet exist -- the entity exposes no setter for it, and {@code V1__reference.sql}
     * L151 defaults the column to zero, after which the persistence provider owns it.</p>
     *
     * @param request the validated create body; must not be {@code null}
     * @return a new unsaved entity carrying the code verbatim and the normalised description, never
     *     {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public static TransactionType toNewEntity(TransactionTypeCreateRequest request) {
        // WHY : Assumptions: the description is normalised HERE, on the way in, and that is the
        //       load-bearing half rather than tidiness. A VARCHAR column stores precisely what it is
        //       given, so a description stored with its padding intact would compare unequal to the
        //       same text without it in every later filter and ordering, and no constraint anywhere
        //       would report the discrepancy. Normalising at the point of storage is what makes a
        //       create and a replace agree on one stored form. The key is NOT normalised, for the
        //       reason recorded at the code line in toResponse above.
        // WHY : Alternatives Considered: the baseline offers three behaviours for this field and they
        //       do not agree with one another, so all three are named rather than the convenient one.
        //       First, app/app-transaction-type-db2/cbl/COTRTLIC.cbl L1841 to L1842 moves a trimmed
        //       description into the generated text host variable while L1843 to L1844 computes the
        //       accompanying length from the UNTRIMMED field. Second, COTRTUPC.cbl L1539 to L1542 does
        //       the same. Third, COBTUPDT.cbl L172 to L174 binds the raw fixed host variables declared
        //       at its L74 05 INPUT-REC-NUMBER PIC X(2) and L76 05 INPUT-REC-DESC PIC X(50) instead of
        //       the generated structure, so it carries none of that trimming behaviour and preserves
        //       leading blanks.
        // WHY : Assumptions: what those three converge on is reasoned out from the language
        //       definition, and is offered as reasoning rather than as an observation of stored bytes.
        //       Both trimming sites take a fixed fifty-character argument -- COTRTLIC.cbl L172
        //       declares 30 WS-ROW-TR-DESC-IN PIC X(50) and COTRTUPC.cbl L335 declares
        //       15 TTUP-NEW-TTYP-TYPE-DESC PIC X(50) -- and because the length function applied to a
        //       fixed alphanumeric item yields that item's declared storage size rather than the
        //       length of its content, the length host variable receives fifty unconditionally. The
        //       three behaviours therefore agree on trailing blanks and differ only on leading ones,
        //       which narrows the disagreement to exactly one dimension. Corroborating that the
        //       baseline treats surrounding blanks as insignificant for equality, every description
        //       comparison it makes goes through a trim, at COTRTLIC.cbl L1065 and L1069 and at
        //       COTRTUPC.cbl L791 and L795.
        // WHY : Alternatives Considered: removing blanks from BOTH ends here, which is what the
        //       baseline's own trim function does to its argument. It is declined because the trim
        //       boundary is a package-scope ruling, settled in this package's package-info.java, that
        //       removes trailing blanks only and treats a leading blank as content the source record
        //       is entitled to hold. Two consequences carry the decision. Re-deciding it at this one
        //       mapping site would change behaviour for every other caller of the same member --
        //       TransactionCategoryMapper, TransactionCategoryService and ReferenceBatchUpdateService
        //       all normalise through it -- which is precisely the divergence between records that a
        //       package-scope ruling exists to prevent. And the resulting difference from the baseline
        //       is already registered, on the trailing-only terms, in
        //       docs/architecture/cobol-to-service-traceability.md.
        // WHY : Assumptions: a non-blank description is guaranteed by bean validation on the request
        //       shape and not by this method. The component is constrained non-blank, bounded to fifty
        //       characters and required to contain at least one alphanumeric, so this method neither
        //       rejects a blank nor substitutes a default for one; the column is NOT NULL and a value
        //       that reached here blank would be a validation gap rather than something to paper over.
        return new TransactionType(request.typeCd(), trimForStorage(request.description()));
    }

    /**
     * Applies a replace request onto a row already loaded, writing its description and nothing else.
     *
     * <p>Assumptions: the second argument is mutated in place and nothing is returned, which a void
     * signature does not reveal on its own. The caller holds a managed entity, so the write below is
     * what the persistence provider flushes; this method neither saves nor flushes it.</p>
     *
     * <p>Assumptions: the key is not written, and three independent authorities agree that it must not
     * be. The replace shape does not carry it at all -- the code travels in the request path, so
     * {@code TransactionTypeUpdateRequest} declares only a description and a revision -- the key is the
     * primary key, declared at {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} L4 and indexed
     * uniquely on the ascending code by {@code XTRNTYPE.ddl} L1 and L3, and the entity maps it
     * {@code updatable = false} so a write would be discarded rather than honoured. The baseline draws
     * the same line in its own statement: {@code COBTUPDT.cbl} L173 sets only the description while
     * L174 uses the code solely to locate the row. Rekeying an existing row is a different operation
     * from updating it, and it is not this one.</p>
     *
     * <p>Assumptions: the optimistic-lock token this request carries is deliberately not consumed
     * here. The comparison belongs to {@code com.carddemo.reference.service}, which holds the loaded
     * row and reports the stored revision alongside its refusal so a caller can retry against the
     * revision that exists; its baseline analogue is the before-image comparison
     * {@code COTRTUPC.cbl} performs before it commits. A mapper that quietly read the token would
     * make it look as though the concurrency check had been made when it had not, and the entity
     * exposes no setter for the revision in any case.</p>
     *
     * @param request the validated replace body carrying the new description and the revision the
     *     caller read; must not be {@code null}
     * @param entity the loaded row to update, mutated in place by this call; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static void applyUpdate(TransactionTypeUpdateRequest request, TransactionType entity) {
        // WHY : Assumptions: the description goes through the same normalisation a create uses, by
        //       calling the same member rather than repeating the rule. That is what makes a create
        //       and a replace agree on one stored form; a second implementation here would be free to
        //       drift from the first, and a VARCHAR column would report nothing when it did.
        entity.setDescription(trimForStorage(request.description()));
    }

    /**
     * Normalises a description for storage, which in this context means removing trailing blanks.
     *
     * <p>Purpose: the inbound half of the package trim boundary, held as a member of its own so that
     * every path which stores a description -- a create, a replace and the maintenance batch -- reaches
     * one implementation of the rule. It is deliberately a separate member from the outbound helper it
     * delegates to: the two perform the same operation, and naming them apart is what allows one to
     * change without silently changing the other, since an outbound call defends a value this service
     * may not have written while this one decides what is stored.</p>
     *
     * <p>Assumptions: this must never be applied to a key. A fixed-width key's declared width is part
     * of its contract, and for the ten-character account group identifier the padding is load-bearing
     * rather than incidental.</p>
     *
     * @param description the description a caller supplied, possibly {@code null}
     * @return the value without trailing blanks, or {@code null} when the input was {@code null}
     */
    public static String trimForStorage(String description) {
        return trimTrailing(description);
    }

    /**
     * Removes trailing blanks, treating an absent value as absent.
     *
     * <p>Assumptions: only the trailing side is stripped. A leading blank in a description is content
     * the source record is entitled to hold, and removing it would change a published value rather
     * than remove padding. The three baseline behaviours recorded at {@code toNewEntity} above differ
     * on exactly that side, and this is where the package settles which reading applies.</p>
     *
     * <p>Trade-offs: this member is package-private rather than private, which is wider than a helper
     * would otherwise be. {@code TransactionCategoryMapper} and {@code DisclosureGroupMapper} call it
     * by qualified name, and the charter records why that is preferred to a third class: only two of
     * the five converted records carry a description, so a neutral utility holding one helper for two
     * callers would add an indirection whose whole content is the loop below. Package-private is the
     * narrowest visibility those two callers can reach.</p>
     *
     * <p>Assumptions: the scan compares against the space character rather than delegating to a
     * general whitespace strip. The padding this removes is the blank fill of a fixed-width COBOL
     * field, so a tab or a newline in a description would be content that arrived from somewhere else
     * and is not this method's to discard.</p>
     *
     * @param value the stored or supplied value, possibly {@code null}
     * @return the value without trailing blanks, or {@code null} when the input was {@code null}
     */
    static String trimTrailing(String value) {
        if (value == null) {
            return null;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }
}
