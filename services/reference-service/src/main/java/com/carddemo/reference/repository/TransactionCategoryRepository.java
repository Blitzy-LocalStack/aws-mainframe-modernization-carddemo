//=============================================================================
// WHY : Assumptions: the charter beside this file, package-info.java, is where
//       the rulings every interface in this package obeys are recorded -- what
//       may bound a walk, which key a reply publishes, which rows a walk
//       positioned by key selects, and whether a category code is character or
//       numeric. They are CITED below and deliberately NOT restated, because
//       the charter says of itself that a restated ruling drifts while still
//       reading as agreement. Where this file and the charter could ever
//       disagree, the charter is right and this file is the defect.
// WHY : Assumptions: the authoritative column contract for every table read
//       here is this module's src/main/resources/db/migration/
//       V1__reference.sql. Column names, declared widths and constraints are
//       cited to it by line rather than restated, for the same anti-drift
//       reason. Where this file and that migration could ever disagree, the
//       migration is right.
// WHY : Assumptions: every line number cited in this file is a PHYSICAL line
//       number, and each was verified positionally by reading the line at that
//       address rather than by searching for a printed sequence value. In the
//       one program cited here the two disagree:
//       app/app-transaction-type-db2/cbl/COTRTLIC.cbl is 2098 physical lines,
//       and from physical line 1808 to the end its six-digit sequence field
//       runs four ahead -- physical line 1901 carries sequence 190500. The
//       charter records the hazard in full; this note exists only so a reader
//       of this file alone does not check a citation the unverifiable way.
// WHY : Assumptions: everything beneath app/ is the behavioural oracle of this
//       migration. It is read, cited by path and physical line, and never
//       written to; where the migrated behaviour departs from it deliberately
//       the departure is registered in
//       docs/architecture/cobol-to-service-traceability.md, which is
//       maintained elsewhere and referenced rather than reproduced here.
//=============================================================================
package com.carddemo.reference.repository;

import com.carddemo.reference.domain.TransactionCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.transaction_categories}: one keyed read, six bounded walks over a
 * two-column key, and the child count a refused parent delete is reported with.
 *
 * <h2>Purpose</h2>
 *
 * <p>This interface is the migrated form of every access path the baseline has to the category
 * table and holds nothing else: no rule, no transfer object, no attempt at an HTTP status. Business
 * behaviour lives in {@code com.carddemo.reference.service}, whose {@code TransactionCategoryService}
 * and {@code TransactionTypeService} are the only consumers of these members, and the mapping onto
 * published shapes lives in {@code com.carddemo.reference.mapper}, which is where a representation
 * concern such as padding is allowed to appear.</p>
 *
 * <p>This is the child side of the only foreign key the {@code reference} schema declares, and that
 * is what distinguishes it from its five siblings. Two of its members exist for that reason alone:
 * the child count below, which lets a refused parent delete reach a user as an intelligible
 * conflict, and nothing else here, because the refusal itself is the database's to make.</p>
 *
 * <p>On parameters, return values and exceptions at the level of this type: an interface declaration
 * accepts no argument and returns no value, so this block carries no parameter or return at-clause;
 * each member below carries its own. Nor does any member declare a thrown type. Every one is a
 * declaration the persistence provider derives an implementation for, so the failure surface is the
 * framework's rather than this file's: a data-access failure arrives as an unchecked
 * {@code org.springframework.dao.DataAccessException} subclass translated by the provider, and the
 * one such failure this interface exists to anticipate is named under the referential rule below.
 * The inapplicability is stated rather than left silent, because the project Explainability rule
 * lists a docstring that omits its parameters or return values among its forbidden patterns at line
 * 39, and a reader has to be able to tell a declared inapplicability from an oversight.</p>
 *
 * <h2>The identity, and why the walks do not simply use it</h2>
 *
 * <p>Refactoring Rationale: the identity is
 * {@link TransactionCategory.TransactionCategoryId}, a nested {@code @Embeddable} of the entity
 * rather than a top-level class of this package. The composite key is meaningless away from the row
 * it identifies, so nesting keeps the two textually inseparable, and declaring it as the entity's
 * embedded identity is what earns {@code save}, {@code delete} and {@code existsById} from
 * {@link JpaRepository} keyed on the WHOLE key rather than on half of it. The decision belongs to
 * the {@code com.carddemo.reference.domain} package, which records it and the alternatives it
 * rejected at the type itself; it is cited here rather than re-argued.</p>
 *
 * <p>Alternatives Considered: the six walks take the two key halves as separate {@code String}
 * parameters rather than an assembled identity. An identity argument would read better at the call
 * site, but it cannot express what these predicates need. A walk is bounded by an INEQUALITY against
 * the key pair, not by equality with it, and an embedded identity compares only as a whole -- so
 * passing one would either force the query to reach into its components anyway or invite an
 * equality that selects a single row instead of a range. The keyed read below, whose comparison IS
 * an equality, takes the identity for exactly that reason. The compromise accepted is that the two
 * member families disagree on their parameter shape, which is deliberate and is why both shapes are
 * explained here rather than harmonised into one that fits only one of them.</p>
 *
 * <h2>The category code is character data, and every member here treats it so</h2>
 *
 * <p>Assumptions: {@code cat_cd} is {@code CHAR(4)} in the schema, so every occurrence of it in
 * this interface -- each walk parameter, each bound, the count's argument -- is a {@code String} and
 * never a boxed or primitive integral type. No convenience member taking an integral code is
 * offered, and none may be added. The charter settles this as the first of its three type rulings
 * and the migration argues it at {@code V1__reference.sql} lines 168 to 192; the ruling is cited
 * rather than restated, but its consequence for the members below is stated here because these are
 * the declarations it governs.</p>
 *
 * <p>Alternatives Considered: an integral parameter type, which is a genuinely reasonable
 * alternative and is why leaving the choice silent would be the omission the Explainability rule
 * names at line 40. Two copybooks declare the field numeric -- {@code app/cpy/CVTRA04Y.cpy} line 7
 * states {@code TRAN-CAT-CD PIC 9(04)} and {@code app/cpy/CVTRA02Y.cpy} line 8 states
 * {@code DIS-TRAN-CAT-CD PIC 9(04)} -- and a numeric picture taken mechanically becomes an integer.
 * Three character-typed sources outweigh them, and they win because they describe the relational
 * table this service replaces rather than a record layout reached by a different access path:
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} line 3 declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} and line 5 places it in the primary key;
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} generates the host variable across lines 42
 * and 43 as {@code PIC X(4)}, character and not numeric; and {@code app/data/ASCII/trancatg.txt}
 * stores every code zero-padded to four digits, its first key being the concatenation
 * {@code 010001}. An integral parameter would hold {@code 0001} as 1, and a bound or an equality
 * built from that value would compare against a blank-padded four-character column and locate
 * nothing -- so the leading zeros are not presentation, they are part of the key.</p>
 *
 * <h2>The members, and what each one answers</h2>
 *
 * <dl>
 *   <dt>{@link #findByIdIs(TransactionCategory.TransactionCategoryId)}</dt>
 *   <dd>The keyed read: one row by both halves of its key at once.</dd>
 *
 *   <dt>{@link #findFirstPage(Limit)}</dt>
 *   <dd>The opening window over every category, taken with no position.</dd>
 *
 *   <dt>{@link #findPageAfter(String, String, Limit)}</dt>
 *   <dd>The window after a two-part position, ascending.</dd>
 *
 *   <dt>{@link #findPageBefore(String, String, Limit)}</dt>
 *   <dd>The window before a two-part position, descending and reversed by its caller.</dd>
 *
 *   <dt>{@link #findFirstPageOfType(String, Limit)}</dt>
 *   <dd>The opening window narrowed to the children of one type.</dd>
 *
 *   <dt>{@link #findPageOfTypeAfter(String, String, Limit)}</dt>
 *   <dd>The narrowed window after a category position, ascending.</dd>
 *
 *   <dt>{@link #findPageOfTypeBefore(String, String, Limit)}</dt>
 *   <dd>The narrowed window before a category position, descending and reversed by its caller.</dd>
 *
 *   <dt>{@link #countByTypeCd(String)}</dt>
 *   <dd>How many categories still reference one type: the diagnosis a refused parent delete is
 *       reported with, and never the authority for refusing it.</dd>
 * </dl>
 *
 * <p>Assumptions: there are six walks rather than three because the composite key admits two
 * questions where a single-column key admits one -- a browse of every category, and a browse of the
 * children of one type -- and each needs the charter's three directions. The charter's roster
 * declares this interface in exactly that shape, so the count is a ruling this file honours rather
 * than a size it chose.</p>
 *
 * <h2>Why the composite walks are written out rather than derived from their names</h2>
 *
 * <p>Alternatives Considered: a derived method name, which is the form the charter's normative
 * interface uses for its unfiltered walks and which needs no query text at all. It cannot express
 * this predicate. A row belongs after a two-part position when its type is greater, OR when its
 * type is equal and its category is greater -- a disjunction over a grouped comparison of the pair.
 * A derived name can express neither the disjunction nor the grouping, and the nearest thing it CAN
 * express is actively wrong rather than merely weaker: a conjunction of two greater-thans silently
 * drops every row whose type advanced while its category fell, which on seeded data means the walk
 * would skip from type {@code 01} category {@code 0005} straight past type {@code 02} categories
 * {@code 0001} through {@code 0003}. The cost accepted is that six predicates are maintained as
 * text, which is why each is written to read as the same shape as its siblings.</p>
 *
 * <p>Assumptions: the two narrowed walks need no disjunction, because their type half is pinned by
 * an equality and only the category half can vary. They are still written out rather than derived so
 * that all six carry their ordering in the same place and can be compared line for line.</p>
 *
 * <h2>The ordering, and why it names a column that cannot vary</h2>
 *
 * <p>Assumptions: every walk orders by the type code and then the category code, in that sequence,
 * and the sequence is chosen to match the column order of the {@code pk_transaction_categories}
 * constraint that {@code V1__reference.sql} declares at line 223 over {@code (type_cd, cat_cd)}.
 * PostgreSQL enforces that key with a unique B-tree on exactly those two columns in exactly that
 * order -- which, as the migration records at lines 225 to 222, is also what already satisfies the
 * baseline's own unique index, {@code X_TRAN_TYPE_CATG}, named at
 * {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl} line 1 and defined at line 3 over
 * {@code (TRC_TYPE_CODE ASC, TRC_TYPE_CATEGORY ASC)}. An ordering that matches an available index in
 * both its columns and their sequence can be answered by walking that index in order; an ordering
 * that matched no index would oblige the engine to materialise the matching rows and sort them before
 * the bound could be applied, which is a different plan shape and not merely a slower one -- a bound
 * cannot be pushed into a sort that has not finished. Measured on the seeded schema, the unnarrowed
 * ascending walk plans as an index scan over {@code pk_transaction_categories} with no sort step, and
 * the narrowed walks reach the same index by its leading column.</p>
 *
 * <p>Assumptions: which plan the engine actually chooses remains its decision and is not something
 * this file can assert. On a table this small a cheaper scan that discards ordering, followed by a
 * sort of the few rows of one type, is a legitimate choice and was observed being made. The property
 * these orderings buy is therefore that an ordered walk of the key is AVAILABLE at every size this
 * table might reach, not that it is used at the size it has today.</p>
 *
 * <p>Alternatives Considered: the two narrowed walks name the type code in their ordering even
 * though their predicate pins it to a single value, so it cannot vary between their rows. Naming only
 * the category code is equivalent in result and shorter to read, and the two forms were compared
 * rather than reasoned about: they plan identically, to the same index scan at the same estimated
 * cost, because the engine drops a provably constant column from a sort key on its own. So the choice
 * costs nothing either way and is settled on legibility instead -- all six walks then carry their
 * ordering in the same shape, and each one visibly names the whole key it is ordered on, which is
 * what lets a reader confirm the match against the constraint from the text alone. The claim that the
 * shorter form might not be recognised was tested and is NOT the reason; recording that here is
 * cheaper than someone re-deriving it.</p>
 *
 * <p>Assumptions: no walk accepts a caller-supplied ordering, and no member takes one. The ascending
 * direction of a forward walk and the descending direction of a backward one are transcribed from
 * the baseline cursor pair the charter quotes, so the direction is part of the contract each member
 * reproduces rather than a choice its caller makes. A member handed an ordering could be given one
 * under which its own bound selects different rows, which would make the transcription
 * unverifiable.</p>
 *
 * <p>Assumptions: a walk is bounded by a {@link Limit} and by nothing else, which is the charter's
 * ruling on what may bound a walk. A bound in that form states a row count with no starting
 * position, so it cannot express a window beginning anywhere other than at the first row the
 * predicate admits -- and the predicate is the only thing that positions these walks. The charter
 * rejects positioning a window by ordinal on a behavioural ground rather than a preference, and
 * excludes the types and the vocabulary of that approach from this package entirely, including from
 * its prose; this file holds to that, so the reasoning is cited and the vocabulary does not
 * appear.</p>
 *
 * <h2>The referential rule: two halves, and only one of them is the authority</h2>
 *
 * <p>Assumptions: {@code V1__reference.sql} declares the foreign key from this table to its parent
 * with restrict-on-delete semantics, the constraint {@code fk_transaction_categories_type} at lines
 * 266 to 268, preserving what {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} states across
 * lines 6 and 7. The baseline treats a refused delete as a distinct user-facing outcome rather than
 * as a failure: {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} deletes the parent at physical
 * line 1901, branches on {@code WHEN SQLCODE = -532} at physical line 1914, sets its
 * delete-requested state at 1915, moves the message
 * {@code 'Please delete associated child records first:'} at physical line 1919 and leaves through
 * the normal exit at 1925 -- whereas its {@code WHEN OTHER} arm at 1926 reports a failed delete
 * instead. Two outcomes, distinguished by the baseline and therefore distinguished here.</p>
 *
 * <p>{@link #countByTypeCd(String)} is the first half of that distinction. It answers whether a type
 * still has children, so the parent's delete path can report the refusal as the migrated outcome
 * with a message a user can act on, instead of surfacing a driver-level failure whose text names a
 * constraint rather than a remedy.</p>
 *
 * <p>The second half is the constraint, and it is the half that actually refuses. A delete that
 * reaches the engine against a referenced parent raises PostgreSQL {@code SQLSTATE 23503}, which the
 * provider translates to {@code org.springframework.dao.DataIntegrityViolationException} and which
 * {@code com.carddemo.common.error.GlobalExceptionHandler} in {@code common-lib} answers as HTTP
 * 409. That handler is inherited: this service declares no advice of its own and this file adds
 * none, no exception translator and no catch block, so the status a refused delete produces cannot
 * drift away from the shared one.</p>
 *
 * <p>Trade-offs: the count is advisory and NOT authoritative, and it is racy by construction. Two
 * callers acting at once -- one deleting a type, the other inserting a category beneath it -- can
 * each read a count of zero, so no count could ever be the thing that refuses. That is accepted
 * precisely because the constraint is the backstop, and both halves are load-bearing in different
 * ways: without the count a user meets an opaque error where the baseline gave a remedy, and without
 * the constraint a category inserted between the reading and the delete would be orphaned. Removing
 * either half would be a defect, which is why the asymmetry is written down rather than left to be
 * inferred from the fact that one of them is only read and never acted on.</p>
 *
 * <p>Assumptions: the count needs no index of its own, and none is declared. The migration records
 * at lines 271 to 281 that {@code type_cd} is the LEADING column of
 * {@code pk_transaction_categories} and that a B-tree can be searched on a leading-column prefix
 * alone, so both this count and the engine's own child search are already covered by the key. The
 * compromise the migration accepts there is that the cover depends on that key's column order, and
 * the ordering of every walk above depends on the same property -- which is why this file names the
 * constraint rather than an index of its own in both places.</p>
 *
 * <h2>What no member of this interface does</h2>
 *
 * <p>Assumptions: nothing here navigates to the parent type. The entity deliberately maps no
 * association on {@code type_cd}, and the {@code com.carddemo.reference.domain} package records why
 * together with the compromise it accepted; a query written here against a traversal would have to
 * reverse that decision from the far side of it. A caller needing the parent's description joins
 * explicitly or issues a second read, which the sibling service and mapper packages do.</p>
 *
 * <p>Assumptions: no member declares a field, a parameter or a return type of a binary
 * floating-point type, at the declaration or as a generic argument of one. Nothing here is monetary,
 * so this is not a rule this file has to reach for -- but architecture rule A3 in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * is scoped to the whole {@code com.carddemo} namespace rather than to the shared kernel's money
 * package alone, so the prohibition here is mechanically enforced and not merely reviewed.</p>
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategory.TransactionCategoryId> {

    /**
     * Reads one category by both halves of its key at once.
     *
     * @param id the composite identity to read, its category half carrying the leading zeros the
     *     stored key includes rather than a value normalised to significant digits
     * @return the row, or empty when no row carries that identity, which is the condition a caller
     *     turns into the migrated not-found refusal rather than into an insert
     */
    // WHY : Alternatives Considered: relying on findById, which JpaRepository already inherits with
    //       this exact identity type and which would make this declaration redundant. It is declared
    //       for two reasons. Explainability: the project rule requires a docstring on every member
    //       this module relies on, and an inherited member cannot carry one at a call site's own
    //       package. Semantics: a member derived from its name is resolved as a query, so it
    //       observes the database, whereas identity lookup is specified to answer from the
    //       persistence context when the row is already managed there. The caller that matters
    //       depends on the difference -- TransactionCategoryService reads this before an insert to
    //       decide whether a row already exists -- and a check answered from a context that has not
    //       yet been written out would be answering about the caller rather than about the table.
    // WHY : Assumptions: the spelling matches DisclosureGroupRepository, the package's other
    //       composite-key interface, so both keyed reads read alike. The name is not a rename of
    //       anything: the trailing keyword is the derivation grammar's own word for equality.
    Optional<TransactionCategory> findByIdIs(TransactionCategory.TransactionCategoryId id);

    /**
     * Reads the opening window over every category, in key order, taken with no position.
     *
     * @param limit the bound the caller sets, one greater than the window it intends to publish so
     *     that the surplus row settles whether a further window exists
     * @return the bounded rows ordered by type code then category code, ascending; empty when the
     *     table holds none
     */
    @Query("""
            select c from TransactionCategory c
            order by c.id.typeCd asc, c.id.catCd asc""")
    List<TransactionCategory> findFirstPage(Limit limit);

    /**
     * Reads the window strictly after a two-part position, in key order.
     *
     * @param typeCd the type half of the position, excluded from the result
     * @param catCd the category half of the position, excluded from the result
     * @param limit the bound the caller sets, one greater than the window it intends to publish
     * @return the bounded rows ordered by type code then category code, ascending; empty when the
     *     position names the end of the table
     */
    // WHY : Assumptions: the two arms are one comparison of the key PAIR and not two independent
    //       tests, which is why the type equality is grouped with the category comparison. The
    //       grouping is what admits a row whose type advanced and whose category fell -- the row a
    //       conjunction of two greater-thans would silently drop, as the class documentation shows
    //       on seeded data.
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd > :typeCd
               or (c.id.typeCd = :typeCd and c.id.catCd > :catCd)
            order by c.id.typeCd asc, c.id.catCd asc""")
    List<TransactionCategory> findPageAfter(
            @Param("typeCd") String typeCd, @Param("catCd") String catCd, Limit limit);

    /**
     * Reads the window strictly before a two-part position, descending.
     *
     * @param typeCd the type half of the position, excluded from the result
     * @param catCd the category half of the position, excluded from the result
     * @param limit the bound the caller sets, one greater than the window it intends to publish
     * @return the bounded rows ordered by type code then category code, DESCENDING, which the caller
     *     reverses before publishing so a reader always receives ascending rows
     */
    // WHY : Assumptions: descending is the order this query returns and not the order a caller
    //       publishes. Reading backwards from a position requires the rows NEAREST that position, so
    //       the bound has to apply from the position outwards; obtaining the same rows ascending
    //       would mean reading every row that precedes the position and discarding all but the last
    //       few. The charter rules that the caller reverses the result and that the surplus row
    //       therefore sits at the OPPOSITE end here than on a forward walk, which is why the
    //       direction is the caller's parameter rather than something it infers.
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd < :typeCd
               or (c.id.typeCd = :typeCd and c.id.catCd < :catCd)
            order by c.id.typeCd desc, c.id.catCd desc""")
    List<TransactionCategory> findPageBefore(
            @Param("typeCd") String typeCd, @Param("catCd") String catCd, Limit limit);

    /**
     * Reads the opening window of the categories beneath one type, taken with no position.
     *
     * @param typeCd the type code whose children are wanted, blank-padded to its declared two
     *     characters exactly as the stored key holds it
     * @param limit the bound the caller sets, one greater than the window it intends to publish
     * @return the bounded rows of that type ordered by type code then category code, ascending;
     *     empty when the type has no children, which is also the answer that lets its parent be
     *     deleted
     */
    // WHY : Assumptions: the ordering names the type code although the equality pins it, on the
    //       legibility ground the class documentation settles by measurement rather than by
    //       reasoning -- the two forms plan identically, so naming the whole key costs nothing and
    //       keeps this walk comparable line for line with the other five.
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd = :typeCd
            order by c.id.typeCd asc, c.id.catCd asc""")
    List<TransactionCategory> findFirstPageOfType(@Param("typeCd") String typeCd, Limit limit);

    /**
     * Reads the window of one type's categories strictly after a category position.
     *
     * @param typeCd the type code whose children are wanted
     * @param catCd the category the previous window ended at, excluded from the result
     * @param limit the bound the caller sets, one greater than the window it intends to publish
     * @return the bounded rows of that type ordered by type code then category code, ascending
     */
    // WHY : Assumptions: no disjunction is needed here, unlike the unnarrowed walk above, because
    //       the equality pins the type half so only the category half can vary. The type code is
    //       still named in the ordering even though it cannot vary, on the legibility ground the
    //       class documentation settles: the two forms plan identically, so naming the whole key is
    //       free and keeps all six walks readable as one shape.
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd = :typeCd and c.id.catCd > :catCd
            order by c.id.typeCd asc, c.id.catCd asc""")
    List<TransactionCategory> findPageOfTypeAfter(
            @Param("typeCd") String typeCd, @Param("catCd") String catCd, Limit limit);

    /**
     * Reads the window of one type's categories strictly before a category position, descending.
     *
     * @param typeCd the type code whose children are wanted
     * @param catCd the category the current window begins at, excluded from the result
     * @param limit the bound the caller sets, one greater than the window it intends to publish
     * @return the bounded rows of that type ordered by type code then category code, DESCENDING,
     *     which the caller reverses before publishing
     */
    // WHY : Assumptions: descending for the same reason the unnarrowed backward walk above is
    //       descending -- reading away from a position needs the rows NEAREST it, so the bound has
    //       to apply outwards from the position. The consequence a caller must not overlook is the
    //       same one too: after its reversal the surplus row sits at the OPPOSITE end than on a
    //       forward walk, so dropping the wrong end removes a row the caller should have seen.
    @Query("""
            select c from TransactionCategory c
            where c.id.typeCd = :typeCd and c.id.catCd < :catCd
            order by c.id.typeCd desc, c.id.catCd desc""")
    List<TransactionCategory> findPageOfTypeBefore(
            @Param("typeCd") String typeCd, @Param("catCd") String catCd, Limit limit);

    /**
     * Counts the categories that still reference one transaction type.
     *
     * @param typeCd the type code whose children are counted, blank-padded to its declared two
     *     characters exactly as the stored key holds it
     * @return how many categories reference that type, zero when none do and never negative
     */
    // WHY : Trade-offs: this is a diagnosis and never an authority, and the class documentation
    //       above carries the whole reasoning: the reading is racy by construction, the declared
    //       foreign key is what refuses, and both halves are load-bearing. It is repeated in one
    //       line here rather than only above because this is the member a reader is most likely to
    //       mistake for a guard, and acting on it as though it were one is the specific error the
    //       constraint exists to make harmless.
    // WHY : Alternatives Considered: an existence test rather than a count, which would let the
    //       engine stop at the first child instead of counting them all. Rejected because the count
    //       is what the parent's refusal path reports alongside the message, so an existence answer
    //       would have to be followed by a second query to say how many -- and over a reference
    //       table this small the two resolve through the same leading-column search of
    //       pk_transaction_categories either way.
    @Query("select count(c) from TransactionCategory c where c.id.typeCd = :typeCd")
    long countByTypeCd(@Param("typeCd") String typeCd);
}
