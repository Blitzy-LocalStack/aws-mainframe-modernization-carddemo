package com.carddemo.reference.service;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionCategory;
import com.carddemo.reference.domain.TransactionCategory.TransactionCategoryId;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionCategoryCreateRequest;
import com.carddemo.reference.dto.TransactionCategoryListRequest;
import com.carddemo.reference.dto.TransactionCategoryResponse;
import com.carddemo.reference.dto.TransactionCategoryUpdateRequest;
import com.carddemo.reference.mapper.TransactionCategoryMapper;
import com.carddemo.reference.repository.TransactionCategoryRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction-category rules, the child side of the transaction-reference feature.
 *
 * <p>Purpose: this class owns the category browse, the keyed read and the three write operations the
 * published contract declares for {@code /api/v1/reference/transaction-categories}. Its consumer is
 * {@code com.carddemo.reference.api}, and the status each refusal reaches is decided by the shared
 * handler {@code ReferenceApplication} imports rather than here.</p>
 *
 * <p>This class has no single COBOL program of its own, and stating otherwise would misplace every
 * rule in it. Its lineage is assembled from the Db2 schema artefacts of the transaction-type
 * extension together with the browse shape of the type-list screen: the referential rule from
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 and 7, the key ordering from
 * {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl}, the Db2 host structure from
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl}, the seeded membership from
 * {@code app/app-transaction-type-db2/ctl/DB2LTCAT.ctl}, the sixty-byte record layout from
 * {@code app/cpy/CVTRA04Y.cpy}, and the paging and filter shape from
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}.</p>
 *
 * <p>Refactoring Rationale: every refusal below reaches the caller through the shared handler that
 * {@code ReferenceApplication} imports, and this class declares no advice of its own. The referential
 * conflict the baseline reaches on SQLCODE -532 is re-expressed as the constraint plus that one
 * mapping, so the conflict status and its sentence are inherited rather than restated. A second
 * advice declared in this module would compete with the imported one for the same exception types,
 * and which of the two answered a given failure would depend on bean ordering -- so the outcomes here
 * are types for that handler to recognise, never responses this class composes.</p>
 *
 * <p>Alternatives Considered: re-deriving the paging and filter behaviour from the baseline screen
 * independently of {@code TransactionTypeService}, which browses the parent table from the same
 * program. Rejected because the two list endpoints are published side by side and a caller pages
 * through them in one session: two independent derivations of one screen's behaviour are free to
 * drift, and the drift would show up as the same query string answering differently on the two
 * collections. This class therefore mirrors that class -- the window size, the direction handling,
 * the cursor sealing, the filter gating and the outcome classification are all the sibling's shapes
 * applied to a two-part key rather than second opinions about them.</p>
 *
 * <p>Trade-offs: paging is by key and never by offset. An offset walk is simpler to write and would
 * need no cursor at all, but it identifies a page by how many rows precede it, so a row inserted or
 * deleted ahead of the caller between two requests shifts every later page: the caller then skips
 * rows it never saw and sees rows it already had. A keyed walk names a boundary row instead, so
 * concurrent writes elsewhere in the table cannot move it.</p>
 *
 * <p>Assumptions: the ordering is {@code (type_cd ASC, cat_cd ASC)}, which is the order the baseline
 * index {@code CARDDEMO.X_TRAN_TYPE_CATG} declares in {@code ddl/XTRNTYCAT.ddl} and the order the
 * composite primary key is declared in by {@code db/migration/V1__reference.sql}. The index name
 * carries an underscore after its leading character; the parent table's index is
 * {@code CARDDEMO.XTRAN_TYPE} and carries none. The two names genuinely differ in the baseline and
 * are cited here exactly as declared, because a reader checking one against the other would
 * otherwise take the difference for a transcription slip.</p>
 *
 * <p>Assumptions: the category code is a four-character string of digits and never an integer. The
 * copybook declares it {@code TRAN-CAT-CD PIC 9(04)} while both the Db2 table and the migrated table
 * store it as a character column, which is the same declared-numeric and stored-character tension
 * {@code COTRTLIC.cbl} shows on the type code at lines 226 to 228, where {@code TRAN-TYPE-CD-X PIC
 * X(02)} is redefined as {@code PIC 9(02)}. {@code reference-api.yaml} publishes it as a string with
 * a four-digit pattern for the same reason. Carrying it as a number would drop the leading zeros
 * that {@code '0005'} depends on, and the seeded rows are exactly the case that breaks.</p>
 *
 * <p>Assumptions: the two key halves are character keys of declared width and are never trimmed --
 * the width is part of the key contract, and a trimmed half would not match a stored one. The
 * description is a {@code VARCHAR(50)} and is the opposite: it is trimmed on the way in, by the
 * mapper rather than here, and is never re-padded on the way out.</p>
 *
 * <p>Assumptions: the seeded membership is taken from {@code app/data/ASCII/trancatg.txt}, which
 * carries eighteen rows of sixty-one characters each, in preference to the Db2 load card at
 * {@code ctl/DB2LTCAT.ctl}. The two disagree on casing across every row, and the ruling in favour of
 * the file with the VSAM lineage is applied by {@code db/migration/V2__seed_reference.sql} rather
 * than here. This class states the ruling because the descriptions it publishes come from that
 * decision.</p>
 *
 * <p>Trade-offs: no arithmetic in this class touches IEEE-754 binary floating point, and none should
 * -- the codes are character data and the only number present is a revision counter. The prohibition
 * here rests on review rather than on a mechanical gate, because the architecture rule that forbids
 * the binary types scopes its subject set to {@code com.carddemo.common.money..} and does not reach
 * this package. Recorded so that a reader does not mistake a green build for proof of it.</p>
 *
 * @see DisclosureGroupService for the pair {@code ('01','0005')}, which is a legitimate category
 *     carrying no disclosure row in any group; the gap is disclosure-side and is that class's
 *     concern, not this one's, and the category is never excluded here
 */
@Service
public class TransactionCategoryService {

    /** The cursor binding for this browse, distinct from every other browse in this service. */
    public static final String CURSOR_BINDING = "reference-transaction-category-list";

    /**
     * The cursor binding for the by-parent browse, distinct from the unnarrowed one above.
     *
     * <p>Assumptions: a separate binding rather than the same one narrowed by a filter value, because
     * the by-parent listing is reached by a different route -- the type appears in the request path
     * rather than in a query parameter -- and a position minted on one route must not open on the
     * other. Sealing them apart makes that structural instead of depending on the two routes
     * happening to compute the same narrowing.</p>
     */
    public static final String CURSOR_BINDING_BY_TYPE = "reference-transaction-category-by-type";

    /**
     * The number of rows one page publishes, seven.
     *
     * <p>Assumptions: seven is the baseline's own window, declared as
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at line 60 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}. That program browses the parent table
     * rather than this one, so the number is inherited from the screen this browse is modelled on
     * rather than from a screen of its own; the transaction-type extension ships two maps only and
     * neither browses categories. Taking the sibling's window is what makes the two lists step by
     * the same amount, which is the only property a caller can observe across the pair.</p>
     */
    public static final int PAGE_SIZE = 7;

    /**
     * The verbatim refusal when no category carries the key asked for.
     *
     * <p>Alternatives Considered: declaring the other baseline sentences this feature uses here as
     * well -- the vanished-row wording at line 1864 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, the no-change wording at its lines 261
     * and 262, the referential wording at its line 1919 selected on SQLCODE -532 at 1914, and the
     * unclassified-delete wording at 1929. Declined because each is already published exactly once:
     * the first two by {@link TransactionTypeService} in this package, the referential one by the
     * shared handler as its referenced-row sentence, and the data-changed one by
     * {@link com.carddemo.common.error.ApiError#COACTUPC_RECORD_CHANGED}. Transformation rule T8
     * (user-visible strings are verbatim) is served by one copy of each sentence and undermined by
     * four second copies that a later edit could move apart. This one is declared here because it
     * names the category specifically and is the only such sentence this class raises.</p>
     */
    public static final String MESSAGE_CATEGORY_NOT_FOUND = "Transaction category NOT found...";

    /**
     * The code reported when a supplied position cannot be read as a two-part key.
     *
     * <p>Assumptions: a validation code rather than a conflict code, because the value came from the
     * caller and the collection is not contended.</p>
     */
    public static final String CURSOR_MALFORMED_CODE = "CURSOR_MALFORMED";

    /** The request component a malformed position is reported against. */
    public static final String FIELD_CURSOR = "cursor";

    /** The sentence a caller receives when the position it supplied cannot be read. */
    public static final String MESSAGE_CURSOR_MALFORMED =
            "The paging position supplied is not one this list issued.";

    /**
     * The separator between the two key halves inside a sealed position.
     *
     * <p>Assumptions: a vertical bar, because neither half can contain one -- the type half is two
     * characters from a published two-digit pattern and the category half is four digits. A separator
     * that a half could contain would let one position parse as a different one.</p>
     */
    private static final char POSITION_SEPARATOR = '|';

    /**
     * The number of rows one narrowing scan reads at a time.
     *
     * <p>Assumptions: sixty-four covers the whole seeded table in a single read, so the narrowed
     * browse costs one round trip on the data this service actually holds -- eighteen rows from
     * {@code app/data/ASCII/trancatg.txt}. It is a read size and not a page size: the window a caller
     * receives is {@link #PAGE_SIZE} whatever this value is, so changing it alters how many
     * statements a narrowed page costs and never which rows it contains.</p>
     */
    private static final int NARROWED_SCAN_ROWS = 64;

    /**
     * The number of cause-chain elements the SQLSTATE search inspects.
     *
     * <p>Assumptions: the bound makes the search total rather than expressing a real expectation about
     * depth. A driver refusal wrapped by the provider and again by the data-access abstraction is
     * three elements, so eight is well clear of anything this service produces; it exists so that a
     * self-referencing chain cannot hold the search open. It matches the bound the sibling type
     * service applies to the same search.</p>
     */
    private static final int CAUSE_CHAIN_LIMIT = 8;

    /** The diagnostic channel for refusals an operator may need to correlate. */
    private static final Logger LOG = LoggerFactory.getLogger(TransactionCategoryService.class);

    /** Access to the category table. */
    private final TransactionCategoryRepository categories;

    /**
     * Builds the service over the repository it reads and writes.
     *
     * <p>Alternatives Considered: also injecting {@code TransactionTypeRepository} so that a create
     * could confirm the named parent exists before attempting the insert. Rejected on the same ground
     * the referential guard rests on throughout this class: such a read is overtaken by a concurrent
     * delete of the parent, so it can only ever be advisory, while the declared foreign key refuses
     * the orphan whether the read ran or not. The published contract routes that refusal through the
     * integrity branch and gives it the referential sentence, so a second opinion here would add a
     * dependency and a statement without changing any answer.</p>
     *
     * <p>Alternatives Considered: injecting {@code TransactionCategoryMapper}. It is a final class
     * with a private constructor exposing static methods only, so there is no instance to inject; it
     * is reached statically, which is how the sibling type service reaches its own mapper.</p>
     *
     * @param categories access to the category table, supplying the six keyed walks, the keyed read
     *     and the advisory child count; must not be {@code null}
     */
    public TransactionCategoryService(TransactionCategoryRepository categories) {
        this.categories = categories;
    }

    /**
     * Returns one keyed page of categories, optionally narrowed by type code and by description.
     *
     * <p>Assumptions: both narrowings are sealed into the position this page mints, alongside the
     * caller and the direction. A position is therefore only reopenable under the filters it was
     * issued under, which is what stops a caller replaying a position from an unnarrowed page against
     * a narrowed one and receiving a window that belongs to neither set.</p>
     *
     * @param request the validated paging and narrowing parameters; must not be {@code null}
     * @param cursorToken the sealer that mints and opens the opaque position; must not be
     *     {@code null}
     * @param subject the authenticated caller's identity, sealed into every position this page mints
     *     so that a position is not transferable between callers; must not be {@code null}
     * @return one page of categories in {@code (type_cd ASC, cat_cd ASC)} order, carrying the two
     *     sealed positions and the availability flags; never {@code null}
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if a supplied position is
     *     not one this browse minted, for this caller, under these narrowings and for this direction
     * @throws com.carddemo.common.error.ClientInputException if a paging direction arrives without
     *     the position it would move from, or if an opened position cannot be read as a two-part key
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionCategoryResponse> list(
            TransactionCategoryListRequest request, CursorToken cursorToken, String subject) {

        return pageOf(CURSOR_BINDING, request, request.typeCode(), cursorToken, subject);
    }

    /**
     * Returns one keyed page of the categories belonging to a single transaction type.
     *
     * <p>Assumptions: the type in the request path is the sole narrowing this route applies, and the
     * {@code typeCode} query parameter belongs to {@link #list} rather than here. Honouring both
     * would give one request two sources for one narrowing with no rule for reconciling them, and the
     * path segment is the one the route is addressed by.</p>
     *
     * <p>Assumptions: this route seals its positions under {@link #CURSOR_BINDING_BY_TYPE} rather
     * than under the unnarrowed binding, so a position minted here does not open on the collection
     * route and vice versa.</p>
     *
     * @param typeCd the two-character parent type code whose categories are wanted; must not be
     *     {@code null}
     * @param request the validated paging parameters, whose description narrowing still applies;
     *     must not be {@code null}
     * @param cursorToken the sealer that mints and opens the opaque position; must not be
     *     {@code null}
     * @param subject the authenticated caller's identity, sealed into every position this page mints;
     *     must not be {@code null}
     * @return one page of that type's categories in {@code (type_cd ASC, cat_cd ASC)} order; never
     *     {@code null}
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if a supplied position is
     *     not one this route minted for this caller, this type and this direction
     * @throws com.carddemo.common.error.ClientInputException if a paging direction arrives without
     *     the position it would move from, or if an opened position cannot be read as a two-part key
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionCategoryResponse> listByType(String typeCd,
            TransactionCategoryListRequest request, CursorToken cursorToken, String subject) {

        return pageOf(CURSOR_BINDING_BY_TYPE, request, typeCd, cursorToken, subject);
    }

    /**
     * Reads one category by both halves of its composite key.
     *
     * @param typeCd the two-character type half; must not be {@code null}
     * @param catCd the four-digit category half, leading zeros intact; must not be {@code null}
     * @return the category as the published contract renders it; never {@code null}
     * @throws TransactionCategoryNotFoundException carrying the verbatim refusal when no row holds
     *     that pair of codes
     */
    @Transactional(readOnly = true)
    public TransactionCategoryResponse find(String typeCd, String catCd) {
        return TransactionCategoryMapper.toResponse(require(typeCd, catCd));
    }

    /**
     * Reads one category by both halves of its composite key, under the name the controller binds.
     *
     * <p>Assumptions: this is the same operation as {@link #find(String, String)} and delegates to it
     * rather than repeating it, so the two names cannot come to mean different things. Both are
     * published because the transformation plan names the operation {@code find} while the authored
     * controller and its tests already bind {@code read}; keeping one implementation behind both
     * names satisfies each without giving the class two reads to keep in step.</p>
     *
     * @param typeCd the two-character type half; must not be {@code null}
     * @param catCd the four-digit category half, leading zeros intact; must not be {@code null}
     * @return the category as the published contract renders it; never {@code null}
     * @throws TransactionCategoryNotFoundException carrying the verbatim refusal when no row holds
     *     that pair of codes
     */
    @Transactional(readOnly = true)
    public TransactionCategoryResponse read(String typeCd, String catCd) {
        return find(typeCd, catCd);
    }

    /**
     * Reports whether any category currently references the given transaction type.
     *
     * <p>Trade-offs: this reading is advisory and is never the authority. It is overtaken the instant
     * it returns -- a category inserted against the type immediately afterwards makes a {@code false}
     * answer stale, and a category deleted immediately afterwards makes a {@code true} answer stale.
     * The declared foreign key {@code ON DELETE RESTRICT} at lines 6 and 7 of
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl}, carried into
     * {@code db/migration/V1__reference.sql}, is what actually refuses the parent's delete, and it
     * refuses it whether this method was consulted or not. It exists so that the parent can produce
     * the clearer sentence on the common case, and so that this class states the caveat in one place
     * instead of every caller restating it.</p>
     *
     * <p>Alternatives Considered: exposing the count rather than a flag. The count is what the
     * parent's refusal path reports alongside its message, and it remains available on the repository
     * for that purpose; a flag is published here because the question this method answers is a
     * yes-or-no one and a caller that only needs the flag should not have to know that a count is how
     * it is obtained.</p>
     *
     * @param typeCd the two-character parent type code being tested; must not be {@code null}
     * @return {@code true} when at least one category referenced that type at the moment of reading,
     *     {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean existsForType(String typeCd) {
        return this.categories.countByTypeCd(typeCd) > 0;
    }

    /**
     * Adds a category beneath an existing transaction type.
     *
     * <p>Alternatives Considered: reporting a single merged integrity outcome for whatever the
     * constraint refused. Rejected because the one foreign key declared at lines 6 and 7 of
     * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} bites in two directions that mean
     * completely different things to a caller: deleting a type that categories still point at is the
     * parent's refusal, while inserting a category under a type that does not exist is this one, and
     * both arrive as the same SQLSTATE. A merged outcome would tell a caller who mistyped a parent
     * code to go and delete dependent rows. The two are therefore separate types here, so that the
     * api package can choose different sentences and different statuses for them without re-reading
     * the SQLSTATE itself.</p>
     *
     * <p>Trade-offs: the existence reading above is advisory and the declared constraint is the
     * authority, exactly as on the parent's delete path. A concurrent insert of the same pair races
     * the reading, and a concurrent delete of the parent races any parent reading; the classifier
     * below is what answers those races, and it answers them identically to the reading so that a
     * caller cannot tell from the response whether it lost one.</p>
     *
     * @param request the validated create body carrying both key halves and the description; must not
     *     be {@code null}
     * @return the stored category as the published contract renders it; never {@code null}
     * @throws DuplicateTransactionCategoryException when a category already carries that pair of
     *     codes, whether the existence reading or the primary key detected it
     * @throws UnknownParentTransactionTypeException when no transaction type carries the type code
     *     named in the request, so the foreign key refused the row
     * @throws DataIntegrityViolationException unchanged, when a constraint refused the row under a
     *     SQLSTATE that is neither of the two above; it is re-raised rather than reshaped so that an
     *     unrecognised condition reaches the shared handler exactly as it did before this classifier
     *     existed
     */
    @Transactional
    public TransactionCategoryResponse create(TransactionCategoryCreateRequest request) {
        TransactionCategoryId id = new TransactionCategoryId(request.typeCd(), request.catCd());
        if (this.categories.findByIdIs(id).isPresent()) {
            LOG.warn("event=reference.category.duplicate-key typeCd={}", request.typeCd());
            throw new DuplicateTransactionCategoryException();
        }

        TransactionCategory candidate = TransactionCategoryMapper.toNewEntity(request);
        try {
            // WHY : Refactoring Rationale: the insert is FLUSHED inside the try, which is the whole
            //       point of the block. A save only registers the row with the persistence context;
            //       the INSERT reaches the database when that context is flushed, which for a
            //       transactional method is at commit -- after this catch has already returned. Both
            //       conditions the catch exists to classify are raised by the statement itself, so
            //       without the explicit flush the duplicate key and the absent parent would both
            //       escape unclassified and reach the caller as the shared handler's generic
            //       integrity answer. This is the same arrangement the sibling type service uses on
            //       its own delete and replace for the same reason.
            TransactionCategory stored = this.categories.saveAndFlush(candidate);
            return TransactionCategoryMapper.toResponse(stored);
        } catch (DataIntegrityViolationException failure) {
            throw classifyIntegrityViolation(failure);
        }
    }

    /**
     * Replaces the description of an existing category, comparing the revision the caller read.
     *
     * <p>Assumptions: the revision is compared here and not left to the persistence provider, which
     * is the migrated form of the pre-edit comparison the baseline performs across a screen turn --
     * the change flag declared at line 168 of {@code app/cbl/COACTUPC.cbl} and tested by
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} at its lines 521 and 522. Comparing before the write
     * is what lets the refusal carry the stored revision back, which a provider-raised failure at
     * commit could not do.</p>
     *
     * <p>Assumptions: the revision is compared BEFORE the no-change reading, in the order the
     * baseline classifier declares at lines 1580 to 1589 of
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}, where the arm selecting
     * {@code TTUP-SHOW-DETAILS} sits below the failure arms. A caller holding a stale revision is
     * refused even when what it submitted happens to match the store, because it read a different row
     * from the one it is now agreeing with.</p>
     *
     * <p>Assumptions: no flush is issued on this path, unlike on the create above, because no
     * constraint can refuse this statement. Both key halves are mapped not updatable, so the primary
     * key and the foreign key are untouched, and the only column written carries nothing beyond a
     * NOT NULL and a declared width the request has already enforced. A flush here would add a
     * statement whose failure modes are all already answered by the shared handler.</p>
     *
     * @param typeCd the two-character type half; must not be {@code null}
     * @param catCd the four-digit category half, leading zeros intact; must not be {@code null}
     * @param request the validated replace body carrying the new description and the revision read;
     *     must not be {@code null}
     * @return the stored category as the published contract renders it, which on a submission
     *     matching the store is the untouched row; never {@code null}
     * @throws TransactionCategoryNotFoundException carrying the verbatim refusal when no row holds
     *     that pair of codes
     * @throws TransactionCategoryDataChangedException carrying the stored revision when the revision
     *     supplied is not the one the row now holds
     */
    @Transactional
    public TransactionCategoryResponse update(String typeCd, String catCd,
            TransactionCategoryUpdateRequest request) {

        TransactionCategory stored = require(typeCd, catCd);
        if (stored.getVersion() != request.version()) {
            throw new TransactionCategoryDataChangedException(stored.getVersion());
        }

        // WHY : Assumptions: a submission that describes what is already stored is answered as the
        //       no-op the baseline reports at lines 261 and 262 of COTRTLIC.cbl rather than as a
        //       write, and the two outcomes stay deliberately distinct: the no-change sentence says
        //       the caller's input matched, while the data-changed refusal above says somebody else
        //       edited the row. Answering a matching submission with a write would also advance the
        //       revision, invalidating every other caller's read for a request that changed nothing.
        // WHY : Assumptions: the comparison strips surrounding blanks on both sides because the
        //       stored form is trimmed on the way in by the mapper. Comparing the raw submission
        //       against the trimmed store would report a change for a value that stores identically.
        if (describesSameStoredValues(stored, request.description())) {
            return TransactionCategoryMapper.toResponse(stored);
        }

        // WHY : Refactoring Rationale: the write is routed through the mapper rather than performed
        //       here, where an earlier form duplicated the mapper's own body -- the same
        //       normalisation of the same component assigned to the same member. Two implementations
        //       of one storage rule are free to drift and a character-varying column would report
        //       nothing when they did: a description trimmed by one path and not by the other stores
        //       as two different values for one input.
        // WHY : Assumptions: the mapper writes the description and nothing else. Neither key half is
        //       written, because both travel in the request path and are mapped not updatable, and
        //       the revision is not written either, because the provider maintains it and it is
        //       compared above rather than assigned. That is asserted by the mapper's own test.
        TransactionCategoryMapper.applyUpdate(request, stored);
        return TransactionCategoryMapper.toResponse(this.categories.save(stored));
    }

    /**
     * Replaces the description of an existing category, under the name the controller binds.
     *
     * <p>Assumptions: this is the same operation as
     * {@link #update(String, String, TransactionCategoryUpdateRequest)} and delegates to it rather
     * than repeating it, for the reason given on {@link #read(String, String)} -- the transformation
     * plan names the operation {@code update} while the authored controller and its tests already
     * bind {@code replace}, and one implementation behind both names cannot drift.</p>
     *
     * @param typeCd the two-character type half; must not be {@code null}
     * @param catCd the four-digit category half, leading zeros intact; must not be {@code null}
     * @param request the validated replace body carrying the new description and the revision read;
     *     must not be {@code null}
     * @return the stored category as the published contract renders it; never {@code null}
     * @throws TransactionCategoryNotFoundException carrying the verbatim refusal when no row holds
     *     that pair of codes
     * @throws TransactionCategoryDataChangedException carrying the stored revision when the revision
     *     supplied is not the one the row now holds
     */
    @Transactional
    public TransactionCategoryResponse replace(String typeCd, String catCd,
            TransactionCategoryUpdateRequest request) {

        return update(typeCd, catCd, request);
    }

    /**
     * Deletes one category.
     *
     * <p>Assumptions: a category is the child of the referential rule rather than its parent, so no
     * delete of one is ever refused for referential reasons -- nothing in this schema references a
     * category row. The refusals the baseline reaches on SQLCODE -532 at lines 1914 and 1919 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, and the unclassified one at its line
     * 1929, belong to the parent's delete path and are unreachable from here. That asymmetry is the
     * whole of the difference between this delete and the parent's.</p>
     *
     * <p>Assumptions: a row that has already gone is reported as the miss below rather than passed off
     * as a successful delete -- the condition the baseline names at line 1864 of that program, whose
     * sentence {@link TransactionTypeService} publishes for the feature. Treating an absent row as
     * success would tell a caller it had removed something that was never there.</p>
     *
     * @param typeCd the two-character type half; must not be {@code null}
     * @param catCd the four-digit category half, leading zeros intact; must not be {@code null}
     * @throws TransactionCategoryNotFoundException carrying the verbatim refusal when no row holds
     *     that pair of codes
     */
    @Transactional
    public void delete(String typeCd, String catCd) {
        this.categories.delete(require(typeCd, catCd));
    }

    /**
     * Assembles one keyed page for whichever of the two browse routes called in.
     *
     * <p>Assumptions: both narrowings are sealed into the position, in a stable argument order, so
     * that a position carries the filters it was issued under. The narrowing sealed for the
     * description is the stripped search text rather than the raw parameter, so two spellings of the
     * same absent filter -- omitted, empty, or blanks only -- seal identically and a position survives
     * the difference.</p>
     *
     * <p>Assumptions: the envelope is assembled here and never by the mapper. The mapper renders one
     * row and knows nothing of windows, boundaries or availability, which is what lets the same mapper
     * serve the browse, the keyed read and both writes.</p>
     *
     * @param binding the cursor binding naming which browse route is minting, so that positions from
     *     the two routes are not interchangeable; must not be {@code null}
     * @param request the validated paging and narrowing parameters; must not be {@code null}
     * @param typeFilter the type code to narrow to, or {@code null} for every type
     * @param cursorToken the sealer that mints and opens the opaque position; must not be
     *     {@code null}
     * @param subject the authenticated caller's identity, sealed into every position minted; must not
     *     be {@code null}
     * @return one page of categories with both positions sealed under {@code binding}; never
     *     {@code null}
     * @throws com.carddemo.common.web.CursorToken.InvalidCursorException if the supplied position was
     *     not sealed for this binding, this caller, these narrowings and this direction
     * @throws ClientInputException if a direction arrives with no position to move from, or if an
     *     opened position does not hold a readable two-part key
     */
    private PageResponse<TransactionCategoryResponse> pageOf(String binding,
            TransactionCategoryListRequest request, String typeFilter, CursorToken cursorToken,
            String subject) {

        ReferencePaging.requireCursorForDirection(request.cursor(), request.direction());

        boolean backward = request.direction() == PageDirection.PREVIOUS;
        String searchText = searchTextOf(request.description());

        String position = ReferencePaging.openPosition(cursorToken, binding, subject,
                request.cursor(), request.direction(), typeFilter, searchText);

        List<TransactionCategory> rows =
                readWindow(decodeCursor(position), backward, typeFilter, searchText);

        return ReferencePaging.page(rows, PAGE_SIZE, backward,
                ReferencePaging.binding(binding, subject, true, typeFilter, searchText),
                ReferencePaging.binding(binding, subject, false, typeFilter, searchText),
                cursorToken, TransactionCategoryMapper::toResponse,
                TransactionCategoryService::positionOf);
    }

    /**
     * Reads one window of rows in ascending key order, one row wider than the page.
     *
     * <p>Assumptions: the extra row is a probe and never an item. Requesting one more row than the
     * window and observing whether it came back is how forward availability is established, and it is
     * deliberately not a count of the collection: a count answers a different question, costs a second
     * statement, and is stale the moment it is taken. The shared assembly drops the probe from the end
     * on a forward walk and from the start on a backward one, so the boundary a page publishes is
     * always the key of a row the caller actually received.</p>
     *
     * <p>Assumptions: no backward availability is computed from this read, because the baseline never
     * computed one. Its backward reader in {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} takes
     * no wider window than it displays and the program declares no previous-page flag anywhere. The
     * absence is fidelity rather than an omission, and the shared envelope publishes the backward
     * POSITION rather than a backward answer, so there is no flag here to derive.</p>
     *
     * @param position the opened two-part boundary, or {@code null} on a first page
     * @param backward whether the caller asked to step back from that boundary
     * @param typeFilter the type code to narrow to, or {@code null} for every type
     * @param searchText the literal text a description must contain, or {@code null} for no
     *     description narrowing
     * @return the rows in ascending key order, with the probe row at the end on a forward walk and at
     *     the start on a backward one; never {@code null}
     */
    private List<TransactionCategory> readWindow(KeyPosition position, boolean backward,
            String typeFilter, String searchText) {

        // WHY : Assumptions: a backward walk is only meaningful from a boundary, so a request that
        //       asks to step back without one is answered with the first page rather than refused
        //       here. The refusal for a direction with no position at all is raised earlier, by the
        //       shared paging guard; what reaches this point with a null position is a first page.
        boolean walkBackward = backward && position != null;

        if (searchText == null) {
            List<TransactionCategory> rows =
                    readChunk(position, walkBackward, typeFilter, Limit.of(PAGE_SIZE + 1));
            return walkBackward ? reversed(rows) : rows;
        }
        return readNarrowedWindow(position, walkBackward, typeFilter, searchText);
    }

    /**
     * Reads one bounded run of rows from a boundary, in the direction of travel.
     *
     * <p>Assumptions: the comparison against the boundary is lexicographic over the key PAIR and is
     * not a conjunction of two independent column comparisons. The repository states it as
     * {@code type_cd > :typeCd or (type_cd = :typeCd and cat_cd > :catCd)}, which is the only form
     * that admits the row whose type advanced while its category fell. Written as
     * {@code type_cd >= :typeCd and cat_cd > :catCd}, the plausible wrong alternative, the walk would
     * silently drop every row at each type boundary whose category code is at or below the boundary
     * row's -- on the seeded data, stepping past {@code ('01','0005')} would lose {@code ('02','0001')}
     * outright and the loss would look like a short page rather than an error. Naming the whole key in
     * the ordering keeps all six walks readable as one shape even where an equality pins the type
     * half.</p>
     *
     * <p>Assumptions: the type-narrowed walks take the narrowing type code and the boundary's CATEGORY
     * half, because the equality pins the type so only the category half can vary. The unnarrowed
     * walks take both halves of the boundary.</p>
     *
     * @param position the two-part boundary to read away from, or {@code null} to read from the start
     * @param backward whether to read descending away from the boundary rather than ascending
     * @param typeFilter the type code to narrow to, or {@code null} for every type
     * @param limit the row bound this run may return; must not be {@code null}
     * @return the rows the run returned, ascending on a forward read and descending on a backward
     *     one; never {@code null}
     */
    private List<TransactionCategory> readChunk(KeyPosition position, boolean backward,
            String typeFilter, Limit limit) {

        if (position == null) {
            return typeFilter == null
                    ? this.categories.findFirstPage(limit)
                    : this.categories.findFirstPageOfType(typeFilter, limit);
        }
        if (backward) {
            return typeFilter == null
                    ? this.categories.findPageBefore(position.typeCd(), position.catCd(), limit)
                    : this.categories.findPageOfTypeBefore(typeFilter, position.catCd(), limit);
        }
        return typeFilter == null
                ? this.categories.findPageAfter(position.typeCd(), position.catCd(), limit)
                : this.categories.findPageOfTypeAfter(typeFilter, position.catCd(), limit);
    }

    /**
     * Fills one window with rows whose description contains the search text, reading in runs.
     *
     * <p>Alternatives Considered: narrowing the description in the statement, as the sibling type
     * browse does through its own filtered walks. That is the better placement and it is where this
     * belongs, but the category repository publishes six keyed walks and no description-narrowed one,
     * and adding a query to a published interface is not this class's change to make. The narrowing is
     * therefore applied here over the keyed walks that do exist.</p>
     *
     * <p>Alternatives Considered: narrowing the single page-wide read after taking it, which is the
     * obvious form and is wrong twice over -- it publishes a window shorter than the page whenever any
     * row in it fails the test, and it derives forward availability from the unnarrowed collection, so
     * a caller is told there is more when there may be nothing further that matches. Reading in runs
     * until the window is full, or until the collection is exhausted, answers both: the window is the
     * full page when the rows exist, the probe row is a matching row, and an empty answer means
     * genuinely nothing further matches rather than that this one run held nothing.</p>
     *
     * <p>Assumptions: the test applied here is a literal substring test, and it is exactly the
     * predicate the sibling endpoint sends to the database. The baseline supplies the wildcards itself
     * -- {@code STRING '%' FUNCTION TRIM(WS-IN-TYPE-DESC) '%'} at lines 1155 to 1163 of
     * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, bound into the two cursors at its lines
     * 348 to 349 and 364 to 365 and into the count at 1812 to 1813 -- and the sibling repository
     * escapes every metacharacter the caller supplied before wrapping it, so its LIKE matches
     * descriptions containing the caller's text taken literally. A literal substring test is that same
     * predicate, which is what keeps the two published lists answering one query string the same way.
     * {@code reference-api.yaml} states the caller-facing half of this: send the text being searched
     * for, not a pattern.</p>
     *
     * <p>Trade-offs: escaping is what makes the caller's own percent and underscore literal, and the
     * repository-layer guidance to escape LIKE metacharacters is followed rather than overridden here.
     * The alternative reading -- leaving the caller's metacharacters live because the baseline escapes
     * nothing -- was evaluated and declined for two reasons: the published parameter documents the
     * contained-text meaning to callers, and the sibling list already implements it, so live
     * metacharacters here would make the same query string mean different things on two lists
     * published side by side. Injection safety does not turn on this either way, since every value
     * reaches the database as a bound parameter.</p>
     *
     * <p>Trade-offs: a narrowed page costs more than one statement when matches are sparse, where a
     * narrowed statement would cost exactly one. The run size is set so that the seeded collection is
     * covered by a single read, and the walk is bounded by the collection because each run resumes
     * strictly past the last row of the one before it.</p>
     *
     * @param position the two-part boundary to read away from, or {@code null} on a first page
     * @param backward whether the walk travels backward from that boundary
     * @param typeFilter the type code to narrow to, or {@code null} for every type
     * @param searchText the literal text a description must contain; must not be {@code null}
     * @return up to one page plus a probe row, in ascending key order; never {@code null}
     */
    private List<TransactionCategory> readNarrowedWindow(KeyPosition position, boolean backward,
            String typeFilter, String searchText) {

        int wanted = PAGE_SIZE + 1;
        List<TransactionCategory> matched = new ArrayList<>(wanted);
        Limit run = Limit.of(NARROWED_SCAN_ROWS);
        KeyPosition readFrom = position;

        while (matched.size() < wanted) {
            List<TransactionCategory> rows = readChunk(readFrom, backward, typeFilter, run);
            if (rows.isEmpty()) {
                break;
            }
            for (TransactionCategory row : rows) {
                if (matched.size() == wanted) {
                    break;
                }
                if (matchesDescription(row, searchText)) {
                    matched.add(row);
                }
            }
            // WHY : Assumptions: the next run resumes from the last row this one READ and not from
            //       the last row it matched. Resuming from the last match would re-read every
            //       non-matching row that followed it on every subsequent run, and a run that matched
            //       nothing would resume where it started and never terminate.
            if (rows.size() < NARROWED_SCAN_ROWS) {
                break;
            }
            TransactionCategory boundary = rows.get(rows.size() - 1);
            readFrom = new KeyPosition(boundary.getTypeCd(), boundary.getCatCd());
        }
        return backward ? reversed(matched) : matched;
    }

    /**
     * Reports whether one row's description contains the search text.
     *
     * @param row the stored row being tested; must not be {@code null}
     * @param searchText the literal text sought; must not be {@code null}
     * @return {@code true} when the stored description contains that text, {@code false} otherwise
     */
    private static boolean matchesDescription(TransactionCategory row, String searchText) {
        String stored = row.getDescription();
        return stored != null && stored.contains(searchText);
    }

    /**
     * Reduces a supplied description narrowing to the text to search for, or to nothing.
     *
     * <p>Assumptions: a narrowing that is absent, empty, or blanks only is one narrowing and not
     * three, which is the migrated form of the three-valued gate the baseline expresses as
     * {@code AND ((:FLAG = '1' AND col = :VALUE) OR (:FLAG <> '1'))} with a companion flag per filter.
     * A null return is that flag being off, and an unset narrowing therefore reads as a genuine no-op
     * rather than as a match against blanks -- the outcome the baseline is careful to avoid by
     * refusing to bind the value at all. The strip is the baseline's own
     * {@code FUNCTION TRIM} applied to the same input.</p>
     *
     * @param supplied the description narrowing as the request carried it, possibly {@code null}
     * @return the stripped text to search for, or {@code null} when no narrowing was supplied
     */
    private static String searchTextOf(String supplied) {
        if (supplied == null) {
            return null;
        }
        String searched = supplied.strip();
        return searched.isEmpty() ? null : searched;
    }

    /**
     * Returns a copy of the rows in the opposite order.
     *
     * <p>Assumptions: a copy rather than an in-place reversal, because a run returned by the
     * repository may be an immutable list and reversing it in place would fail at run time on a
     * backward page only -- the one direction least likely to be exercised first.</p>
     *
     * @param rows the rows to reverse; must not be {@code null}
     * @return a mutable copy in reverse order; never {@code null}
     */
    private static List<TransactionCategory> reversed(List<TransactionCategory> rows) {
        List<TransactionCategory> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);
        return ordered;
    }

    /**
     * Renders the two-part ordering key of one row as the value a position seals.
     *
     * @param entity the row whose position is wanted; must not be {@code null}
     * @return the sealed position's plain text, never {@code null}
     */
    private static String positionOf(TransactionCategory entity) {
        return encodeCursor(entity.getTypeCd(), entity.getCatCd());
    }

    /**
     * Joins the two key halves into the single opaque position the envelope carries.
     *
     * <p>Alternatives Considered: the envelope carrying the key as a typed value rather than as one
     * string, by giving {@code PageResponse} a second type parameter for its key. That was evaluated
     * where the envelope is declared and declined there, because the envelope is shared with lists
     * whose key is a single column and one whose key is a pair, and a key type parameter would force
     * every list to name a key type in order to serve the few that have a composite one. The envelope
     * therefore carries one string, and encoding the pair into it is this class's own obligation
     * rather than something a sibling supplies.</p>
     *
     * <p>Alternatives Considered: two separate boundary components on the envelope, one per key half.
     * Declined for the same reason: the envelope is shared, and it would grow a shape that only
     * composite-keyed lists ever populate, leaving every scalar-keyed list publishing a component that
     * is always absent.</p>
     *
     * <p>Assumptions: both halves are joined at their declared widths with nothing stripped, so a type
     * half of {@code '01'} and a category half of {@code '0005'} reconstruct exactly. Stripping either
     * would lose the leading zeros the key depends on and the position would name a row that does not
     * exist.</p>
     *
     * <p>Assumptions: the result is opaque to the caller. It is sealed before it leaves this service
     * and is not part of the published response shape, so its internal arrangement is free to change
     * without a contract change and no caller may parse or construct one.</p>
     *
     * @param typeCd the two-character type half; must not be {@code null}
     * @param catCd the four-digit category half; must not be {@code null}
     * @return the two halves joined by the separator; never {@code null}
     */
    private static String encodeCursor(String typeCd, String catCd) {
        return typeCd + POSITION_SEPARATOR + catCd;
    }

    /**
     * Splits an opened position back into its two key halves.
     *
     * <p>Alternatives Considered: treating an unreadable position as an absent one and answering with
     * the first page. Declined because it is silently wrong in the direction a caller cannot detect:
     * the caller believes it is continuing a walk, receives the opening window instead, and ends up
     * with the first rows repeated in the middle of its results while whatever followed the real
     * boundary is never delivered. Refusing tells the caller its position is unusable, which is the
     * one answer from which it can recover by restarting deliberately.</p>
     *
     * <p>Assumptions: the refusal is raised as caller input and therefore reaches the caller as a
     * validation answer, not as a conflict and not as a failure of this service. The halves are also
     * checked here rather than left to the identity type's own constructor, which reports a bad width
     * or a non-digit as an illegal argument -- a condition the shared handler deliberately does not
     * map, so it would surface as an internal failure for a value the caller supplied.</p>
     *
     * @param token the position as the sealer opened it, or {@code null} on a first page
     * @return the two halves of the boundary, or {@code null} when no position was supplied
     * @throws ClientInputException when the position does not hold exactly one separator with a
     *     two-character type half and a four-digit category half on either side of it
     */
    private static KeyPosition decodeCursor(String token) {
        if (token == null) {
            return null;
        }
        int separator = token.indexOf(POSITION_SEPARATOR);
        if (separator < 0 || token.indexOf(POSITION_SEPARATOR, separator + 1) >= 0) {
            throw malformedCursor(token);
        }
        String typeHalf = token.substring(0, separator);
        String categoryHalf = token.substring(separator + 1);
        if (typeHalf.length() != TransactionCategory.TYPE_CD_WIDTH
                || categoryHalf.length() != TransactionCategory.CAT_CD_WIDTH
                || !categoryHalf.matches(TransactionCategory.CAT_CD_PATTERN)) {
            throw malformedCursor(token);
        }
        return new KeyPosition(typeHalf, categoryHalf);
    }

    /**
     * Builds the refusal for a position that cannot be read, without echoing the position back.
     *
     * <p>Assumptions: the offending value is logged and never rendered. A position is sealed, so a
     * value that failed to open is either corrupted or forged, and returning it in the response body
     * would reflect unvalidated caller input straight back to whoever supplied it.</p>
     *
     * @param token the position that could not be read; recorded for an operator, never rendered
     * @return the refusal to throw, naming the cursor component; never {@code null}
     */
    private static ClientInputException malformedCursor(String token) {
        LOG.warn("event=reference.category.cursor-malformed length={}", token.length());
        return new ClientInputException(ApiError.CODE_VALIDATION, FIELD_CURSOR,
                FieldValidationFlag.NOT_OK, MESSAGE_CURSOR_MALFORMED);
    }

    /**
     * Reads one category or raises the verbatim refusal.
     *
     * @param typeCd the two-character type half; must not be {@code null}
     * @param catCd the four-digit category half; must not be {@code null}
     * @return the stored row, never {@code null}
     * @throws TransactionCategoryNotFoundException carrying the verbatim refusal when no row holds
     *     that pair of codes
     */
    private TransactionCategory require(String typeCd, String catCd) {
        // WHY : Assumptions: neither half is stripped on its way into the identity. Both are character
        //       columns of declared width and the width is part of the key, so a stripped half would
        //       not match a stored one and the read would miss a row that is present.
        Optional<TransactionCategory> found =
                this.categories.findByIdIs(new TransactionCategoryId(typeCd, catCd));
        if (found.isEmpty()) {
            throw new TransactionCategoryNotFoundException();
        }
        return found.get();
    }

    /**
     * Reports whether a submitted description already describes what is stored.
     *
     * @param stored the row read before the write; must not be {@code null}
     * @param submitted the description the caller supplied, possibly {@code null}
     * @return {@code true} when the two agree once surrounding blanks are discounted, {@code false}
     *     otherwise
     */
    private static boolean describesSameStoredValues(TransactionCategory stored, String submitted) {
        return strippedForComparison(stored.getDescription())
                .equals(strippedForComparison(submitted));
    }

    /**
     * Reduces one description to the form the no-change comparison uses.
     *
     * @param value the description to reduce, possibly {@code null}
     * @return the value without surrounding blanks, or an empty string when it was absent; never
     *     {@code null}
     */
    private static String strippedForComparison(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Turns a constraint refusal into the outcome that names which of the two directions refused it.
     *
     * <p>Alternatives Considered: reading the condition from a persistence-provider exception subclass
     * rather than from the SQLSTATE. Declined as narrower than the conditions being told apart: a
     * subclass ties this class to the provider it happens to run on, and the duplicate-key subclass
     * recognises only one of the two states that matter here. The driver exception reports the state
     * itself through an interface that is part of the platform, so the chain is walked for that.</p>
     *
     * <p>Refactoring Rationale: the unique violation is classified apart from the foreign-key
     * violation even though both reach the caller as the same referential sentence. The baseline does
     * not separate them: {@code 9700-INSERT-RECORD} at lines 1596 to 1623 of
     * {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl} has only a zero arm and a catch-all arm,
     * with no branch for the duplicate-key code at all, so a duplicate and any other insert failure
     * are reported identically there; the Java tells them apart so that the api package and an
     * operator can, and the divergence is documented in
     * {@code docs/architecture/cobol-to-service-traceability.md}. The shared sentence is not an
     * accident either -- it is the published behaviour, recorded against
     * {@code D-REFERENCE-INTEGRITY-SENTENCE}, so separating the types must not and does not separate
     * the wording.</p>
     *
     * <p>Assumptions: the diagnostic records the SQLSTATE and a type-and-frame digest, never the
     * provider's own most-specific-cause text. That text is composed by the driver and quotes the
     * values that violated the constraint, so recording it would copy caller data into log storage --
     * the one destination the masking applied at the api edge does not reach. Nothing an operator acts
     * on is lost: the SQLSTATE names which constraint class refused the statement, which is what
     * selects the branch below and what an alert matches on.</p>
     *
     * @param failure the constraint refusal the statement raised; must not be {@code null}
     * @return the outcome to throw -- the duplicate outcome for a unique violation, the unknown-parent
     *     outcome for a foreign-key violation, and the refusal itself unchanged when the state is not
     *     one of those two; never {@code null}
     */
    private static RuntimeException classifyIntegrityViolation(
            DataIntegrityViolationException failure) {

        String sqlState = sqlStateOf(failure);
        LOG.debug("event=reference.category.integrity-violation sqlState={} failure={}",
                sqlState, ThrowableDigest.of(failure));

        // WHY : Assumptions: the two states are read from the sibling type service rather than
        //       re-declared here. Both classes classify the same two conditions raised by the same
        //       constraint pair, and a second pair of literals could be edited on one side only.
        if (TransactionTypeService.SQLSTATE_UNIQUE_VIOLATION.equals(sqlState)) {
            LOG.warn("event=reference.category.duplicate-key sqlState={}", sqlState);
            return new DuplicateTransactionCategoryException();
        }
        if (TransactionTypeService.SQLSTATE_FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            // WHY : Assumptions: on THIS statement the foreign key can only have been refused from
            //       the child side, because an insert of a category is the only write reaching this
            //       classifier and the only relationship it participates in is its own parent
            //       reference. The parent-side reading of the same state -- a type whose delete is
            //       refused by surviving categories -- is raised on the parent's path and never
            //       arrives here, which is what makes a single state safe to name unambiguously at
            //       this one call site.
            LOG.warn("event=reference.category.unknown-parent-type sqlState={}", sqlState);
            return new UnknownParentTransactionTypeException();
        }

        // WHY : Trade-offs: an unrecognised state is handed back unchanged rather than forced into
        //       whichever of the two it resembles. The inherited mapping already answers a constraint
        //       refusal, so returning it preserves exactly the answer this path gave before any
        //       classification existed and adds no new behaviour to a condition not understood.
        LOG.warn("event=reference.category.unclassified-integrity-violation sqlState={}", sqlState);
        return failure;
    }

    /**
     * Finds the SQLSTATE the database reported, by walking the cause chain.
     *
     * @param failure the refusal to inspect, possibly {@code null}
     * @return the first non-blank SQLSTATE found within the bound, or {@code null} when the chain
     *     reports none
     */
    private static String sqlStateOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < CAUSE_CHAIN_LIMIT; depth++) {
            if (current instanceof SQLException reported) {
                String state = reported.getSQLState();
                if (state != null && !state.isBlank()) {
                    return state;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * One decoded browse boundary, holding both halves of a composite key.
     *
     * <p>Assumptions: a distinct type rather than the persistent identity type, even though the two
     * carry the same two components. The identity type validates its components and reports a bad one
     * as an illegal argument, which the shared handler deliberately does not map, so constructing one
     * from a caller-supplied position would turn a bad cursor into an internal failure. This type
     * holds a boundary that {@link #decodeCursor(String)} has already checked, and it is never
     * persisted.</p>
     *
     * @param typeCd the two-character type half of the boundary, never {@code null}
     * @param catCd the four-digit category half of the boundary, leading zeros intact, never
     *     {@code null}
     */
    private record KeyPosition(String typeCd, String catCd) {
    }

    /**
     * Reports that no category carries the pair of codes asked for.
     *
     * <p>Alternatives Considered: a file of its own for this outcome and for each of the three below.
     * Declined because these types exist to be told apart by the shared handler and by the api
     * package, not to be reused anywhere else, and four more files would spread one classification
     * decision across five places. Nesting them keeps the decision and the outcomes it produces in one
     * readable unit, and each name is distinct enough to be matched on.</p>
     *
     * <p>Refactoring Rationale: this extends the platform's own no-such-element type rather than
     * introducing a parallel not-found hierarchy. The shared handler already answers that type as a
     * not-found result and renders its message, so extending it inherits the status and the wording
     * instead of requiring a second mapping; declaring an unrelated type would leave this outcome
     * answered as an unexpected failure until somebody remembered to map it.</p>
     */
    public static final class TransactionCategoryNotFoundException extends NoSuchElementException {

        /** The serialisation identity of this outcome. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the refusal carrying the verbatim category-not-found sentence.
         */
        public TransactionCategoryNotFoundException() {
            super(MESSAGE_CATEGORY_NOT_FOUND);
        }
    }

    /**
     * Reports that the row was altered underneath the caller between its read and its write.
     *
     * <p>Refactoring Rationale: this is the migrated form of the baseline's pre-edit comparison across
     * a screen turn -- the change flag at line 168 of {@code app/cbl/COACTUPC.cbl} and the condition
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} at its lines 521 and 522 -- and the arm that reaches
     * it in the reference feature is the one selecting {@code TTUP-SHOW-DETAILS} at lines 1585 and
     * 1586 of {@code app/app-transaction-type-db2/cbl/COTRTUPC.cbl}. That arm puts the stored row back
     * on the screen rather than only refusing, which is why this outcome carries the stored revision
     * instead of refusing bare.</p>
     *
     * <p>Assumptions: no sentence is composed here and none is passed to the supertype, so the
     * verbatim wording {@link com.carddemo.common.error.ApiError#COACTUPC_RECORD_CHANGED} reaches the
     * caller from the single place that publishes it, selected from the contention kind by the shared
     * handler. Composing a copy here would give one condition two sentences and no rule for choosing
     * between them.</p>
     */
    public static final class TransactionCategoryDataChangedException
            extends RecordConflictException {

        /** The serialisation identity of this outcome. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the refusal reporting the revision the row now holds.
         *
         * @param currentVersion the revision the stored row currently carries, which is the value a
         *     caller must re-read against; reported so that it need not read again to find out
         */
        public TransactionCategoryDataChangedException(long currentVersion) {
            super(Kind.STALE_VERSION, currentVersion);
        }
    }

    /**
     * Reports that a category already carries the pair of codes a create supplied.
     *
     * <p>Assumptions: the contention kind is the referential one rather than the stale-revision one,
     * so this outcome renders the referential sentence. That is the published behaviour for a
     * duplicate pair of codes, recorded against {@code D-REFERENCE-INTEGRITY-SENTENCE} in
     * {@code docs/architecture/cobol-to-service-traceability.md}, and it is what the sibling type
     * service answers a duplicate parent code with. The stale-revision kind would report a duplicate
     * primary key as somebody else's concurrent edit, which is a different condition with a different
     * remedy.</p>
     *
     * <p>Assumptions: the same outcome is raised whether the existence reading or the primary key
     * detected the duplicate, so a caller cannot tell from the answer whether it lost a race. Two
     * answers for one condition would make the response depend on timing.</p>
     */
    public static final class DuplicateTransactionCategoryException
            extends RecordConflictException {

        /** The serialisation identity of this outcome. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the refusal for a pair of codes that is already present.
         */
        public DuplicateTransactionCategoryException() {
            super(Kind.REFERENCED_ROW);
        }
    }

    /**
     * Reports that a create named a transaction type which does not exist.
     *
     * <p>Assumptions: this is the child-side reading of the one foreign key declared at lines 6 and 7
     * of {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl}. The parent-side reading of that same
     * constraint -- a type whose delete is refused because categories still reference it -- arrives as
     * the same SQLSTATE and is a separate outcome raised on the parent's path. Keeping them apart is
     * what lets the api package say "that parent does not exist" to one caller and "delete the
     * dependents first" to the other, from one constraint.</p>
     *
     * <p>Assumptions: the contention kind is the referential one, so this outcome renders the
     * referential sentence and reaches the caller as a conflict. The published contract states that a
     * category naming an absent type is refused through the same integrity branch as a restricted
     * delete and carries the same sentence; the choice of status belongs to the api package, and this
     * type exists so that the choice can be made without re-reading the SQLSTATE.</p>
     */
    public static final class UnknownParentTransactionTypeException
            extends RecordConflictException {

        /** The serialisation identity of this outcome. */
        private static final long serialVersionUID = 1L;

        /**
         * Creates the refusal for a category whose named parent type is absent.
         */
        public UnknownParentTransactionTypeException() {
            super(Kind.REFERENCED_ROW);
        }
    }
}
