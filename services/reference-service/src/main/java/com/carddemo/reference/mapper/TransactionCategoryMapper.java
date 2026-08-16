package com.carddemo.reference.mapper;

import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionCategoryUpdateRequest;

/**
 * Converts between the transaction-category entity and its wire shapes.
 *
 * <p>Purpose: the single place the transaction-category record's representation concerns are
 * resolved. Three of those concerns govern the three values this record carries, and they do not
 * agree with one another, which is most of the content of this class. The two-character type code is
 * a fixed-width key whose leading zero is data. The four-character category code is a fixed-width
 * key whose leading zeros are data, and it is the one field in this package whose target type
 * contradicts its {@code PICTURE} clause. The fifty-character description is a variable-width field
 * whose trailing blanks are padding to a record length, so it alone is normalised. A reader who
 * applies any one of those rulings to either of the other two fields either breaks a key or
 * publishes padding, which is why each is stated at the line it governs.</p>
 *
 * <p>Assumptions: every class in this package is written by hand and no code generator is introduced
 * anywhere in it. The package-scope rulings this class applies are settled once in this package's own
 * {@code package-info.java} -- the hand-written charter, the rejection of MapStruct and, on a
 * separate ground, of Lombok, the static-versus-component class shape, the trim boundary and the
 * register of dropped padding -- and they are cited here rather than argued again. What this file
 * adds is the evidence specific to this one record, which the charter summarises at its own L354 to
 * L376 and which is set out at length below because L27 of the governing rule requires a rationale to
 * sit beside the line it explains.</p>
 *
 * <p>Assumptions: this class is {@code final} with a private constructor and static members rather
 * than a Spring {@code @Component}, which is the shape the charter fixes for the four entity
 * conversions at its own L170 to L192. Each of the four is a total function of its argument, with no
 * collaborator, no configuration and no state between calls, and {@code TransactionTypeMapper} beside
 * it is written the same way. The three seeded-lookup conversions in this package are Spring
 * {@code @Component}s and are not {@code final}, so that they stay proxyable; that shape is recorded on
 * each of them and does not reach this class. Refactoring Rationale: this sentence named
 * {@code DateInquiryReplyMapper} as the package's single component exception; that class has moved to
 * {@code com.carddemo.common.codec.DateInquiryReplyCodec} in the shared kernel, so the exception is the
 * lookup trio rather than one class.</p>
 *
 * <p>Assumptions: the four-byte padding item at {@code app/cpy/CVTRA04Y.cpy} L9,
 * {@code 05 FILLER PIC X(04)}, reaches no column, and the drop is registered here once rather than
 * left to inference. The arithmetic is given so the claim is checkable rather than asserted: the
 * record declares {@code TRAN-TYPE-CD PIC X(02)} at L6, {@code TRAN-CAT-CD PIC 9(04)} at L7 and
 * {@code TRAN-CAT-TYPE-DESC PIC X(50)} at L8, and 2 + 4 + 50 + 4 = 60, the record length its L2
 * header comment states. The seed agrees byte for byte -- every row of
 * {@code app/data/ASCII/trancatg.txt} is sixty characters of which the last four are that padding,
 * and they hold the literal digits {@code 0000} rather than blanks, which is worth noting because a
 * column carrying them would look like data. Those bytes exist so the following record begins at a
 * predictable offset; a row in an individually typed, separately addressable column needs no such
 * padding, so they carry nothing a consumer could act on.</p>
 *
 * <p>Assumptions: the three columns this class writes are
 * {@code services/reference-service/src/main/resources/db/migration/V1__reference.sql} L167
 * {@code type_cd CHAR(2) NOT NULL}, L192 {@code cat_cd CHAR(4) NOT NULL} and L198
 * {@code description VARCHAR(50) NOT NULL}, with L212 {@code version BIGINT NOT NULL DEFAULT 0}
 * carrying the revision. That migration is the authority for the physical shape of this schema; where
 * it and this file could ever disagree, the migration is right.</p>
 *
 * <p>Alternatives Considered: a list-rendering member beside {@link #toResponse}, which this class
 * carried and which had no caller. Removed rather than wired, because there is no boundary for it to
 * serve: every published collection of categories is a keyset PAGE, and the one place a page is
 * assembled -- {@code com.carddemo.reference.service.ReferencePaging} -- renders the window one row at
 * a time through a per-row function, deliberately, so that the surplus-row asymmetry that decides
 * which end to trim is written once for all five browses rather than five times. Widening that
 * function to take a whole list would ripple to all five call sites and force a list renderer onto the
 * three seeded-lookup mappers that have no use for one. A method reachable from nowhere is worse than
 * an absent one: it reads as a supported entry point, and the first caller to adopt it would bypass
 * the pager that owns trimming and boundary sealing. The per-row {@code toResponse} below IS the
 * convention the pager consumes, and it is the member the sibling type mapper shares a shape
 * with.</p>
 *
 * <p>Assumptions: the four rationale labels used below are written in the one plural,
 * unparenthesised, unemphasised form that {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its
 * L218 to L245, and no other spelling of any of them appears in this file. A label is found by
 * literal search before it is read by a person, so a second spelling of one category would leave that
 * search silently partial.</p>
 */
public final class TransactionCategoryMapper {

    /**
     * Prevents instantiation of a type whose whole content is static.
     *
     * @throws AssertionError always, so a reflective instantiation fails loudly rather than yielding a
     *     useless instance
     */
    private TransactionCategoryMapper() {
        // WHY : Assumptions: a private constructor rather than an abstract class, because an abstract
        //       class invites a subclass and this type has no behaviour to extend. The form is the
        //       one TransactionTypeMapper uses at its own L64, so the two siblings read alike.
        throw new AssertionError("TransactionCategoryMapper is not instantiable");
    }

    /**
     * Renders one stored category as the shape the contract publishes.
     *
     * <p>Assumptions: the two key halves are flattened onto the response beside the description rather
     * than published as the nested identity object the entity holds. The response is a wire shape, and
     * a nested key object there would push a persistence concern -- that these two columns are
     * combined into one embeddable identity type -- into the API contract, where a caller has no use
     * for it and could not act on it. The caller receives two named string members and addresses the
     * row with them.</p>
     *
     * <p>Assumptions: the argument is a loaded, non-null row whose embedded identity and description
     * are non-null, because {@code V1__reference.sql} declares all three columns {@code NOT NULL} at
     * L167, L192 and L198 and the entity repeats each constraint as {@code nullable = false}. Nothing
     * is substituted here for a value the schema will not permit to be absent; a null would mean the
     * row was never loaded, and reporting that as an empty code or description would hide it.</p>
     *
     * @param entity the stored row to render; must not be {@code null}
     * @return the response shape carrying the type code, the category code, the description and the
     *     stored revision, never {@code null}
     * @throws NullPointerException if {@code entity} is {@code null}
     */
    public static TransactionCategoryResponse toResponse(TransactionCategory entity) {
        // WHY : Assumptions: the type code is published exactly as stored, and the call below cannot
        //       alter it. app/app-transaction-type-db2/ddl/TRNTYCAT.ddl L2 declares TRC_TYPE_CODE
        //       CHAR(2) NOT NULL and every seed code in app/data/ASCII/trancatg.txt fills both of
        //       bytes 1 and 2, 01 through 07, so a valid code occupies the whole column and leaves
        //       nothing to remove. The distinction the package trim boundary draws is not whether
        //       this member is called but whether calling it could change a value: on the
        //       ten-character account group identifier the same call would not be a no-op, which is
        //       exactly why DisclosureGroupMapper does not make it there. The difference is recorded
        //       because a reader comparing the two mappers would otherwise read it as an oversight.
        String publishedTypeCd = TransactionTypeMapper.trimTrailing(entity.getTypeCd());

        // WHY : Alternatives Considered: an int, a short or any other numeric type for the category
        //       code, which its own PICTURE clause invites. This is the one documented type exception
        //       in the package, so the evidence is set out on BOTH sides rather than only the side
        //       that was chosen, and the count is not unanimous: three authorities read the field as
        //       character and two read it as numeric.
        //       For character. First, app/app-transaction-type-db2/ddl/TRNTYCAT.ddl L3 declares
        //       TRC_TYPE_CATEGORY CHAR(4) NOT NULL. Second, the generated declaration agrees at
        //       app/app-transaction-type-db2/dcl/DCLTRCAT.dcl L42 to L43, where
        //       DCL-TRC-TYPE-CATEGORY is a plain PIC X(4); that is the host variable for a CHAR
        //       column, and the distinction is categorical rather than incidental, because only a
        //       VARCHAR column takes the split length-and-text form that the same file uses for
        //       TRC_CAT_DATA at L45 to L51. Third, the seed data agrees: the codes in
        //       app/data/ASCII/trancatg.txt occupy bytes 3 to 6 and run 0001 through 0005,
        //       zero-padded to four characters.
        //       For numeric. app/cpy/CVTRA04Y.cpy L7 declares 10 TRAN-CAT-CD PIC 9(04), and the
        //       sibling disclosure record declares the same shape at app/cpy/CVTRA02Y.cpy L8. Both
        //       read as numeric and neither is dismissed here.
        // WHY : Assumptions: what decides it is the consequence rather than the count. A numeric
        //       column would render 0001 as 1, discarding leading zeros that are part of the stored
        //       key rather than presentation, so only the character reading preserves the value a
        //       consumer of the baseline can observe today. The stakes are that the code is half of
        //       the primary key -- TRNTYCAT.ddl L5 declares PRIMARY KEY(TRC_TYPE_CODE,
        //       TRC_TYPE_CATEGORY), carried forward at V1__reference.sql L223 -- and half of the
        //       ascending composite index at app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl L1 and
        //       L3. A value that renders differently is therefore a different key, and a caller could
        //       not echo it back to address the row it had just read.
        // WHY : Assumptions: this is a ruling about type and not about naming. No field is renamed
        //       anywhere in this package, in either direction; the three misspelling corrections the
        //       wider migration makes belong to other bounded contexts and none of them is this one.
        String publishedCatCd = TransactionTypeMapper.trimTrailing(entity.getCatCd());

        // WHY : Assumptions: the stored description is published at its content length and is never
        //       re-padded to fifty. The column is VARCHAR at V1__reference.sql L198, and the trailing
        //       blanks of the source field are padding to a fixed record length rather than content --
        //       the seed descriptions in app/data/ASCII/trancatg.txt occupy bytes 7 to 56 blank-padded
        //       to the full fifty, and the baseline's own Db2 declaration of the same field is
        //       TRC_CAT_DATA VARCHAR(50) at ddl/TRNTYCAT.ddl L4. A JSON body has no fixed field to
        //       fill, so carrying the padding would give every caller a value to strip before
        //       comparing it. This is a divergence rather than parity, and it is registered as
        //       D-REFDATA-DESCRIPTION-TRIM in docs/architecture/cobol-to-service-traceability.md,
        //       which is the one place such a difference is recorded.
        String publishedDescription = TransactionTypeMapper.trimTrailing(entity.getDescription());

        // WHY : Assumptions: the revision is read off the entity rather than derived. It is the
        //       version member the persistence provider maintains, declared @Version on the entity
        //       against V1__reference.sql L212, and the replace operation answers 409 carrying it --
        //       TransactionCategoryService compares it at its own L198 and reports the stored value
        //       at L199 -- so a caller needs the number the row actually holds in order to retry
        //       against the revision that exists.
        return new TransactionCategoryResponse(
                publishedTypeCd, publishedCatCd, publishedDescription, entity.getVersion());
    }

    /**
     * Builds a new entity from a create request, composing its two-part identity.
     *
     * <p>Assumptions: the create shape carries the whole composite key in the body, which is what
     * distinguishes it from the replace shape. {@code TransactionCategoryCreateRequest} declares the
     * type code, the category code and the description as components, and each key component is
     * constrained to its exact width -- the category code to four digits -- so a code that lost its
     * leading zeros upstream is refused by validation before it reaches this method rather than being
     * stored as a second row for one logical category.</p>
     *
     * <p>Alternatives Considered: letting the service assemble the identity and passing this method
     * two loose strings, which a reader could reasonably expect since the service holds the key on
     * every other path. It is declined because the composite key is a representation concern of this
     * one record -- two separately typed fixed-width character columns combined into a single identity
     * -- and this layer is the only place in the service such concerns are permitted to appear. The
     * charter records the same ruling from the other direction at its own L481 to L488, where the
     * nested embeddable identity is named as the reason a mapper composes an identity object instead
     * of setting key members one by one.</p>
     *
     * <p>Assumptions: the revision is neither taken from the request nor set here, and it cannot be.
     * The create shape carries no revision component -- there is no prior revision to state for a row
     * that does not yet exist -- the entity exposes no setter for it, and {@code V1__reference.sql}
     * L212 defaults the column to zero, after which the persistence provider owns it.</p>
     *
     * @param request the validated create body; must not be {@code null}
     * @return a new unsaved entity carrying both key halves verbatim and the normalised description,
     *     never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws IllegalArgumentException if either key half is not exactly its declared width, if the
     *     category code holds a character that is not a digit, or if the description exceeds its
     *     declared width
     */
    public static TransactionCategory toNewEntity(TransactionCategoryCreateRequest request) {
        // WHY : Assumptions: both key halves are stored verbatim and untrimmed, for the reasons
        //       recorded at the two key lines in toResponse above -- the type code fills its CHAR(2)
        //       column and the category code's leading zeros are part of its value. A trim on either
        //       would be the one edit that turns a valid key into a key matching no row, and the
        //       identity type refuses a value that is short in any case.
        // WHY : Assumptions: the description is normalised HERE, on the way in, and that is the
        //       load-bearing half rather than tidiness. A VARCHAR column stores precisely what it is
        //       given, so a description stored with its padding intact would compare unequal to the
        //       same text without it in every later filter and ordering, and no constraint anywhere
        //       would report the discrepancy. Normalising at the point of storage is what makes a
        //       create and a replace agree on one stored form.
        // WHY : Alternatives Considered: the baseline offers three behaviours for this field and they
        //       do not agree with one another, so all three are named rather than the convenient one.
        //       First, app/app-transaction-type-db2/cbl/COTRTLIC.cbl L1841 to L1842 moves a trimmed
        //       description into the generated text host variable while L1843 to L1844 computes the
        //       accompanying length from the UNTRIMMED field. Second, COTRTUPC.cbl L1539 to L1542
        //       does the same. Third, COBTUPDT.cbl L172 to L174 binds the raw fixed host variables
        //       declared at its L74 05 INPUT-REC-NUMBER PIC X(2) and L76 05 INPUT-REC-DESC PIC X(50)
        //       instead of the generated structure, so it carries none of that trimming behaviour and
        //       preserves leading blanks.
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
        //       baseline's own trim function does to its argument, and declaring a private helper on
        //       this class to do it. Both are declined for the same reason. The trim boundary is a
        //       package-scope ruling, settled in this package's package-info.java, that removes
        //       trailing blanks only and treats a leading blank as content the source record is
        //       entitled to hold; and the single implementation of it lives on TransactionTypeMapper,
        //       the mapper of the record whose description the baseline manipulates most, which the
        //       charter explains at its own L198 to L210. Calling it by qualified name is what keeps
        //       one implementation of the rule -- a second one here would be free to drift, and a
        //       VARCHAR column would report nothing when it did.
        // WHY : Alternatives Considered: checking here that the parent transaction type exists. It is
        //       declined because the constraint already exists and is enforced where it cannot be
        //       raced: app/app-transaction-type-db2/ddl/TRNTYCAT.ddl L6 to L7 restricts deletion of a
        //       type its categories still reference, V1__reference.sql L255 to L257 carries that
        //       forward as fk_transaction_categories_type with ON DELETE RESTRICT, and a violation
        //       surfaces from the database and is translated to HTTP 409 by
        //       com.carddemo.common.error.GlobalExceptionHandler. A mapper-side existence check would
        //       duplicate the constraint and could disagree with it under concurrency, admitting a key
        //       whose parent was deleted between the check and the insert.
        // WHY : Assumptions: a non-blank description is guaranteed by bean validation on the request
        //       shape and not by this method. The component is constrained non-blank, bounded to fifty
        //       characters and required to contain at least one alphanumeric, so this method neither
        //       rejects a blank nor substitutes a default for one; the column is NOT NULL and a value
        //       that reached here blank would be a validation gap rather than something to paper over.
        return new TransactionCategory(
                new TransactionCategory.TransactionCategoryId(request.typeCd(), request.catCd()),
                TransactionTypeMapper.trimForStorage(request.description()));
    }

    /**
     * Applies a replace request onto a row already loaded, writing its description and nothing else.
     *
     * <p>Assumptions: the second argument is mutated in place and nothing is returned, which a void
     * signature does not reveal on its own. The caller holds a managed entity, so the write below is
     * what the persistence provider flushes; this method neither saves nor flushes it.</p>
     *
     * <p>Assumptions: neither key half is written, and three independent authorities agree that
     * neither may be. The replace shape does not carry them at all -- both halves travel in the
     * request path, so {@code TransactionCategoryUpdateRequest} declares only a description and a
     * revision -- the two halves together form the primary key, declared at
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L5 and indexed uniquely on the two
     * ascending columns by {@code XTRNTYCAT.ddl} L1 and L3 and carried forward at
     * {@code V1__reference.sql} L223, and the entity maps both key columns {@code updatable = false}
     * so a write would be discarded rather than honoured. The baseline draws the same line, and the
     * nearest place it draws it is the sibling type record rather than this one, because the baseline
     * issues no update against {@code TRANSACTION_TYPE_CATEGORY} at all:
     * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} L173 sets only the description while L174
     * uses the key solely to locate the row. Rekeying an existing row is a different operation from
     * updating it, and it is not this one.</p>
     *
     * <p>Assumptions: the optimistic-lock token this request carries is deliberately not consumed
     * here. The comparison belongs to {@code com.carddemo.reference.service}, which holds the loaded
     * row and reports the stored revision alongside its refusal so a caller can retry against the
     * revision that exists -- {@code TransactionCategoryService} performs it at its own L209 and
     * raises the conflict at L210. Its baseline analogue is the before-image comparison
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} performs before it commits, at its L786 to
     * L797. A mapper that quietly read the token would make it look as though the concurrency check
     * had been made when it had not, and the entity exposes no setter for the revision in any
     * case.</p>
     *
     * @param request the validated replace body carrying the new description and the revision the
     *     caller read; must not be {@code null}
     * @param entity the loaded row to update, mutated in place by this call; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the description exceeds its declared width
     */
    public static void applyUpdate(
            TransactionCategoryUpdateRequest request, TransactionCategory entity) {

        // WHY : Assumptions: the description goes through the same normalisation a create uses, by
        //       calling the same member rather than repeating the rule. That is what makes a create
        //       and a replace agree on one stored form; a second implementation here would be free to
        //       drift from the first, and a VARCHAR column would report nothing when it did. The
        //       argument order and the member name match TransactionTypeMapper.applyUpdate at its own
        //       L251, so a reader moving between the two sibling records meets one shape.
        entity.setDescription(TransactionTypeMapper.trimForStorage(request.description()));
    }
}
