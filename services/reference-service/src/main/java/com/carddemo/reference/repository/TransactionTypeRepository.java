//=============================================================================
// WHY : Assumptions: the charter beside this file, package-info.java, is where
//       the rulings every interface in this package obeys are recorded -- how a
//       line citation resolves, what may bound a walk, which key a reply
//       publishes, and which rows a walk positioned by key selects. They are
//       CITED below and deliberately not restated, because a restated ruling
//       can drift while still reading as agreement, and the charter says so of
//       itself.
// WHY : Assumptions: every line number cited in this file is a PHYSICAL line
//       number and each was verified positionally, by reading the line at that
//       address, never by searching for a printed sequence value. In the one
//       program transcribed here the two disagree:
//       app/app-transaction-type-db2/cbl/COTRTLIC.cbl is 2098 physical lines,
//       and from physical line 1808 to the end its printed sequence field runs
//       four ahead, so physical line 1944 carries sequence 194800. The charter
//       records the hazard in full and this note exists only so a reader of
//       this file alone does not check a citation the unverifiable way.
// WHY : Assumptions: everything beneath app/ is the behavioural oracle of this
//       migration. It is read, cited by path and physical line, and never
//       modified; where the migrated behaviour departs from it deliberately the
//       departure is registered in
//       docs/architecture/cobol-to-service-traceability.md, which is maintained
//       elsewhere and referenced rather than reproduced.
//=============================================================================
package com.carddemo.reference.repository;

import com.carddemo.reference.domain.TransactionType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code reference.transaction_types}: one keyed read, three walks positioned by a key the
 * caller already holds, the two filtered walks transcribed from the baseline's cursor pair, and the
 * aggregate that reports whether a filter matches any row at all.
 *
 * <h2>Purpose</h2>
 *
 * <p>This interface is the migrated form of every access path the baseline has to the transaction-type
 * table and holds nothing else: no rule, no transfer object, no attempt at an HTTP status. Business
 * behaviour lives in {@code com.carddemo.reference.service}, whose {@code TransactionTypeService} and
 * {@code ReferenceBatchUpdateService} are the only consumers of these members, and the mapping onto
 * published shapes lives in {@code com.carddemo.reference.mapper}.</p>
 *
 * <p>Assumptions: the charter names this interface the normative one for paging in this package, so a
 * shape settled here is the shape the other five follow. That is why the cursor pair below is quoted
 * rather than summarised: the transcription of those two predicates is what the rest of the package is
 * measured against.</p>
 *
 * <h2>The two cursors this interface transcribes</h2>
 *
 * <p>Both are declared in {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl}, and both are quoted
 * below as the source writes them, casing included, so a reader can verify each line positionally. The
 * lowercase {@code and} on two lines of the second declaration is the source's own and is not
 * harmonised: a quotation earns its place by matching byte for byte, and smoothing it would leave a
 * reader unable to confirm that this file read the program rather than a paraphrase of it.</p>
 *
 * <pre>
 * C-TR-TYPE-FORWARD, declared across physical lines 338 to 352
 *
 *        SELECT TR_TYPE
 *              ,TR_DESCRIPTION
 *          FROM  CARDDEMO.TRANSACTION_TYPE
 *             WHERE TR_TYPE &gt;= :WS-START-KEY               line 343  INCLUSIVE
 *             AND  ((:WS-EDIT-TYPE-FLAG = '1'              line 344
 *             AND   TR_TYPE = :WS-TYPE-CD-FILTER)          line 345
 *             OR   (:WS-EDIT-TYPE-FLAG &lt;&gt; '1'))      line 346  pass-through
 *             AND  ((:WS-EDIT-DESC-FLAG = '1'              line 347
 *             AND   TR_DESCRIPTION LIKE                    line 348
 *                        TRIM(:WS-TYPE-DESC-FILTER))       line 349
 *             OR   (:WS-EDIT-DESC-FLAG &lt;&gt; '1'))      line 350  pass-through
 *          ORDER BY TR_TYPE                                line 351  ASCENDING
 *
 * C-TR-TYPE-BACKWARD, declared across physical lines 354 to 368
 *
 *             WHERE TR_TYPE &lt; :WS-START-KEY             line 359  EXCLUSIVE
 *             and  ((:WS-EDIT-TYPE-FLAG = '1'              line 360  source casing
 *             and   TR_TYPE = :WS-TYPE-CD-FILTER)          line 361  source casing
 *             OR   (:WS-EDIT-TYPE-FLAG &lt;&gt; '1'))      line 362
 *             AND  ((:WS-EDIT-DESC-FLAG = '1'              line 363
 *             AND   TR_DESCRIPTION LIKE                    line 364
 *                        TRIM(:WS-TYPE-DESC-FILTER))       line 365
 *             OR   (:WS-EDIT-DESC-FLAG &lt;&gt; '1'))      line 366
 *            ORDER BY TR_TYPE DESC                         line 367  DESCENDING
 * </pre>
 *
 * <p>The projection and both optional arms are identical between the two. What differs is the two
 * things the charter's asymmetry ruling names: whether the position handed in is included, and which
 * way the rows come back. The published contract settles the same asymmetry the same way and states
 * that it must not be normalised, in the commentary above its cursor parameter in
 * {@code src/main/resources/openapi/reference-api.yaml}.</p>
 *
 * <h2>The members, and what each one replaces</h2>
 *
 * <dl>
 *   <dt>{@link #findByTypeCd(String)}</dt>
 *   <dd>The keyed read: one row by its whole code.</dd>
 *
 *   <dt>{@link #findAllByOrderByTypeCdAsc(Limit)}</dt>
 *   <dd>The opening window, taken with no position.</dd>
 *
 *   <dt>{@link #findByTypeCdGreaterThanOrderByTypeCdAsc(String, Limit)}</dt>
 *   <dd>The window after a position, ascending.</dd>
 *
 *   <dt>{@link #findByTypeCdLessThanOrderByTypeCdDesc(String, Limit)}</dt>
 *   <dd>The window before a position, descending and reversed by its caller.</dd>
 *
 *   <dt>{@link #findFilteredPageAfter(String, String, String, Limit)}</dt>
 *   <dd>{@code C-TR-TYPE-FORWARD} in full, both optional arms included.</dd>
 *
 *   <dt>{@link #findFilteredPageBefore(String, String, String, Limit)}</dt>
 *   <dd>{@code C-TR-TYPE-BACKWARD} in full, both optional arms included.</dd>
 *
 *   <dt>{@link #countFilterMatches(String, String)}</dt>
 *   <dd>Paragraph {@code 9100-CHECK-FILTERS}, whose aggregate stands at physical line 1804.</dd>
 *
 *   <dt>{@link #typeCodeFilter(String)}</dt>
 *   <dd>The filter-blank collapse the program performs at physical lines 1101 to 1106.</dd>
 *
 *   <dt>{@link #descriptionFilterPattern(String)}</dt>
 *   <dd>The pattern the program builds for itself at physical lines 1155 to 1162.</dd>
 * </dl>
 *
 * <p>Assumptions: the last two members hold the only executable statements here, and they are the only
 * two the queries above cannot express. Everything else in this interface is a declaration the
 * persistence provider derives an implementation for at run time, which is why the charter's ruling
 * that a type in this package reads or writes rows and does nothing else is not in tension with them:
 * each turns one filter argument into the form its own queries bind, and each is beside the query text
 * whose escape clause and whose absent-argument arms it has to agree with. Placing either in a caller
 * would put that agreement in two files.</p>
 *
 * <p>Assumptions: existence and removal are inherited and no member is declared for either.
 * {@code existsById} answers whether a code is a code some row carries, which is the question a
 * category's parent reference raises, and the inherited keyed {@code delete} is what a removal goes
 * through so that the declared foreign key is the thing that refuses a restricted removal.
 * {@code V1__reference.sql} declares that key across lines 266 to 268 as
 * {@code FOREIGN KEY (type_cd) REFERENCES reference.transaction_types (type_cd) ON DELETE RESTRICT},
 * transcribed from {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 to 7. The engine
 * refuses with SQLSTATE 23503, the framework translates that into a data-integrity exception, and
 * {@code com.carddemo.common.error.GlobalExceptionHandler} -- inherited from the shared kernel, never
 * re-declared in this module -- renders it as HTTP 409.</p>
 *
 * <h2>Decisions</h2>
 *
 * <p>What follows discharges the obligation user-specified Rule 1 (Explainability) sets out at its line
 * 43, whose gate is conjunctive: a docstring alone does not pass it and a rationale alone does not pass
 * it. Each entry below carries one of the four categories the rule names at lines 31 to 34, and each
 * names a path with a physical line, a declared constraint or a named gate, because line 41 rejects a
 * rationale that gives no specific justification. Entries whose subject is one member are stated again
 * at that member, since line 27 asks for adjacency and a type-level block cannot supply it.</p>
 *
 * <p>Refactoring Rationale: each of the baseline's two flag-and-value pairs becomes ONE parameter that
 * accepts an absent value, and the companion flag has no counterpart here. The flags are declared at
 * physical lines 103 to 110: {@code WS-EDIT-TYPE-FLAG PIC X(1)} carries
 * {@code 88 FLG-TYPEFILTER-NOT-OK VALUE '0'}, {@code 88 FLG-TYPEFILTER-ISVALID VALUE '1'} and
 * {@code 88 FLG-TYPEFILTER-BLANK VALUE ' '}, and {@code WS-EDIT-DESC-FLAG} carries the same three. Only
 * the {@code '1'} arm applies its filter; a blank flag means the value was not supplied and a
 * {@code '0'} flag means it was supplied and rejected, and both fall through the pass-through arms at
 * lines 346 and 350 which admit every row. A one-byte alphanumeric field cannot hold "no value", so the
 * program needed a second field to say whether the first one meant anything. A Java reference can hold
 * that state itself, so the truth table is unchanged while the second parameter disappears: an absent
 * value drops the arm exactly as an unset flag does.</p>
 *
 * <p>Alternatives Considered: there are two forward walks, one comparing strictly greater and one
 * comparing greater or equal, and they are not in disagreement. The baseline's inclusive comparison at
 * line 343 is handed the FIRST key of the window being requested: the re-display paths move
 * {@code WS-CA-FIRST-TR-CODE} into the start key at physical lines 711 to 712, 756, 841 to 842, 863 to
 * 864 and 872 to 873, and the page-down path at lines 768 to 769 moves {@code WS-CA-LAST-TR-CODE},
 * which line 1673 has already replaced with the surplus row's key -- again the first key of the window
 * being requested. The envelope this package publishes into names the last row a caller actually
 * received, per the charter's page-boundary ruling, so a caller advancing from that key needs the
 * strictly-greater comparison to avoid receiving it twice. The two forms therefore select the same rows
 * and differ only in which key is handed in, which is the charter's forward-comparison ruling; both are
 * offered because both keys exist in the target, and the parameter documentation on each member says
 * which key it expects.</p>
 *
 * <p>Alternatives Considered: a walk is bounded by {@link Limit} and by nothing else. The charter's
 * bounding ruling records why: that type expresses a count of rows and carries no starting position, so
 * it cannot express a window beginning anywhere other than at the first row its predicate admits, and
 * the predicate is the only thing that positions a walk here. The rejected alternative is named in that
 * ruling too, on a behavioural ground rather than a preference: a window measured by counting the rows
 * that precede it re-counts them on every request, so a concurrent insert or removal makes it skip a
 * row and repeat another -- and this very table is maintained by the screens that browse it. Neither
 * the type nor the vocabulary of that alternative appears anywhere in this file, including in its
 * prose, exactly as the charter requires.</p>
 *
 * <p>Alternatives Considered: no member takes an ordering argument, and the ascending and descending
 * directions are written into the queries. They are part of what each member transcribes -- line 351
 * ascending, line 367 descending -- so a caller able to invert one could be handed a result whose
 * bound selects different rows from the ones the transcription promises, which would make the
 * transcription unverifiable. This is the charter's no-caller-supplied-ordering ruling.</p>
 *
 * <p>Assumptions: a further window is discovered by asking for one row more than the window and
 * observing whether that row arrived, never by counting rows. The baseline does exactly this: physical
 * line 1657 tests whether the row just placed filled the last slot, line 1662 issues one further fetch,
 * and its outcome sets the flag -- present at lines 1670 to 1671, absent at lines 1674 to 1675. There
 * are three fetch sites in the whole program and no others: line 1627 fills a row inside the loop
 * opened at line 1622, line 1662 is that one further read, and line 1754 is the backward read. Every
 * walk here therefore takes a bound one greater than the window its caller publishes, and the surplus
 * row is the answer.</p>
 *
 * <p>Assumptions: nothing here reports whether a window exists BEFORE the one returned, because the
 * baseline never computes it. Paragraph {@code 8100-READ-BACKWARDS} asserts a further window
 * unconditionally at physical line 1738, before it has read a row and with no further fetch anywhere in
 * the paragraph, which is sound because arriving at a page by stepping back from one further on already
 * proves that a page further on exists; whether a step further back is possible comes from the counter
 * {@code WS-CA-SCREEN-NUM}, decremented at physical line 784 immediately before the paragraph is
 * invoked at lines 785 to 786. There was nothing to port, so nothing is invented: the envelope in
 * {@code com.carddemo.common.web.PageResponse} declares no such component, that record is owned by the
 * shared kernel, and no member is added here to compute one.</p>
 *
 * <p>Assumptions: the window a caller publishes is seven rows and this file does not name that number.
 * Its one authoritative source is the program -- physical line 60 declares
 * {@code 05  WS-MAX-SCREEN-LINES     PIC S9(4)      COMP VALUE 7.} and reads it at lines 60, 940, 1004,
 * 1018, 1334, 1386, 1657 and 1736, among them the further-window gate and the descending fill index --
 * and the bound belongs to the request rather than to the table, so every walk here takes it as a
 * parameter. The charter records the two counting traps around that number so they are not re-derived
 * here.</p>
 *
 * <p>Assumptions: the seeded table is one window wide, which is worth knowing before an assertion is
 * written against it. {@code src/main/resources/db/migration/V2__seed_reference.sql} inserts exactly
 * seven rows at its lines 112 to 119, from {@code 01} to {@code 07}, and the published window is seven,
 * so on seeded data alone the surplus row does not exist and a further window is correctly reported as
 * absent. A case that expects one has to insert an eighth row first. Recorded because the alternative
 * is meeting it as a puzzling failure in a case whose query and envelope are both right.</p>
 *
 * <p>Assumptions: the identity of this repository is {@code String}. Four sources carry the code as
 * characters of declared width two -- {@code V1__reference.sql} line 106 declares
 * {@code type_cd CHAR(2) NOT NULL}, {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} line 2
 * declares {@code TR_TYPE CHAR(2) NOT NULL}, {@code app/app-transaction-type-db2/dcl/DCLTRTYP.dcl}
 * line 38 generates the host variable as {@code PIC X(2)}, and {@code app/cpy/CVTRA03Y.cpy} line 5
 * declares {@code TRAN-TYPE PIC X(02)} -- and the seeded codes begin with a zero that a numeric
 * identity would discard, leaving a key that locates no row. The entity's own ruling on this is
 * recorded in {@code com.carddemo.reference.domain} and is not repeated.</p>
 *
 * <p>Trade-offs: {@code @Repository} is declared, as it is on all six interfaces of this package, and
 * it adds no behaviour: the repository proxy already applies persistence exception translation to a
 * Spring Data interface. It is carried because the other five declare it and one interface omitting it
 * would read as a claim that this one is not a bean, which is a distinction a reader would then go
 * looking for.</p>
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {

    /**
     * The character that makes the character following it in a description pattern literal.
     *
     * <p>Assumptions: an exclamation mark rather than a reverse solidus. The conventional escape
     * character would pass through two escaping layers before it reached the engine -- the Java string
     * literal that carries the query text, and the query language's own string literal, which accepts
     * Java-style escape sequences -- so each query would have to spell it with a repetition count that
     * no reader can confirm by eye and that a compiler will not check. This character carries no
     * meaning in either layer and none in a SQL {@code LIKE} pattern, and
     * {@link #descriptionFilterPattern(String)} emits it twice when a caller's text contains it, so a
     * description holding an exclamation mark is still matched literally.</p>
     *
     * <p>Trade-offs: it is spliced into each of the three query texts by constant concatenation, which
     * is why those texts are concatenated strings rather than text blocks -- a text block cannot carry
     * a constant. The alternative was three separate copies of the character, each able to disagree
     * with the one the pattern builder uses, and a disagreement of that kind does not fail: it quietly
     * stops escaping and the walk then returns rows the caller did not ask for.</p>
     */
    String LIKE_ESCAPE = "!";

    /**
     * Reads one transaction type by its whole two-character code.
     *
     * <p>Assumptions: this names the key rather than delegating to the inherited generic finder, whose
     * behaviour is identical. It is the member the two calling services and their unit cases already
     * bind to, and each sibling interface in this package declares a finder named for its own key, so
     * the six read alike.</p>
     *
     * @param typeCd the code to read, exactly as stored: two characters including a leading zero,
     *     because {@code V1__reference.sql} line 106 declares {@code type_cd CHAR(2) NOT NULL} and the
     *     seeded codes at {@code V2__seed_reference.sql} lines 113 to 119 run from {@code 01} to
     *     {@code 07}
     * @return the row, or empty when no row carries that code, which is the condition from which the
     *     calling service raises the migrated refusal rather than this member raising anything
     */
    Optional<TransactionType> findByTypeCd(String typeCd);

    /**
     * Reads the opening page of types in code order, taken with no position.
     *
     * <p>Assumptions: an absent position is a state the baseline reaches rather than a case it lacks.
     * Physical lines 515 to 517 initialise the whole communication area when the program is entered
     * with none, which leaves the two-byte start key blank, and physical lines 1134 and 1172 initialise
     * the paging fields again whenever either filter changes, so a changed filter restarts here. The
     * inclusive comparison at line 343 then admits every row.</p>
     *
     * @param limit the bound on rows returned, which a caller sets to one greater than the window it
     *     intends to publish so that the surplus row settles the further-page flag without an aggregate
     * @return the bounded rows in ascending code order, empty when the table holds none
     */
    List<TransactionType> findAllByOrderByTypeCdAsc(Limit limit);

    /**
     * Reads the page that follows a position, in ascending code order.
     *
     * <p>Assumptions: the comparison is STRICTLY GREATER, while the cursor quoted on this type is
     * inclusive at line 343, and the two select the same rows because they are handed different keys.
     * This member is handed the key of the last row the caller received, which is the key the envelope
     * publishes under the charter's page-boundary ruling, so including it would hand that row back a
     * second time.</p>
     *
     * @param lastKey the code of the last row the caller received, excluded from the result
     * @param limit the bound on rows returned, one greater than the window the caller publishes
     * @return the bounded rows in ascending code order, empty when that position was the final row
     */
    List<TransactionType> findByTypeCdGreaterThanOrderByTypeCdAsc(String lastKey, Limit limit);

    /**
     * Reads the page that precedes a position, in DESCENDING code order.
     *
     * <p>Assumptions: the rows come back descending and the caller reverses them before publishing
     * them, which {@code com.carddemo.reference.service.ReferencePaging} does for every browse in this
     * context. Descending order is what makes this a page rather than a scan: the rows wanted are the
     * ones nearest the position, and a bound applied to an ascending walk would return the rows
     * furthest from it. The baseline expresses the same reversal as a descending subscript rather than
     * as a list operation -- paragraph {@code 8100-READ-BACKWARDS} begins at physical line 1727 and
     * exits at 1794, sets its row index to the window at lines 1735 to 1736, and decrements it at line
     * 1769 until it reaches zero at lines 1770 to 1773.</p>
     *
     * <p>Assumptions: the surplus row of a descending walk carries the LOWEST key, so after the
     * reversal it sits FIRST rather than last. Which end to drop is therefore a property of the
     * direction walked, and dropping the wrong one removes a row the caller should have seen without
     * failing anything.</p>
     *
     * @param firstKey the code of the first row the caller received, excluded from the result
     * @param limit the bound on rows returned, one greater than the window the caller publishes
     * @return the bounded rows in descending code order, empty when that position was the first row
     */
    List<TransactionType> findByTypeCdLessThanOrderByTypeCdDesc(String firstKey, Limit limit);

    /**
     * Reads the page at or after a position, narrowed by either optional filter, in ascending order.
     *
     * <p>Assumptions: this is {@code C-TR-TYPE-FORWARD} from physical line 343 with both optional arms
     * across lines 344 to 350 and the ascending order at line 351, and with ONE deliberate change: the
     * comparison is strict here where the cursor declares it inclusive. The page boundary is identical,
     * and the measurement that establishes it is the surplus fetch at physical lines 1657 to 1673. When
     * the fill loop reaches {@code WS-MAX-SCREEN-LINES} it provisionally stores the last displayed code
     * in {@code WS-CA-LAST-TR-CODE} at line 1659, then fetches one more row, and when that fetch
     * succeeds it OVERWRITES that field at line 1673 with the surplus row's code. So the value page-down
     * moves into the start key at lines 768 to 769 is the first code of the NEXT page, not the last code
     * of the current one, and an inclusive comparison against it selects exactly the rows a strict
     * comparison against the last displayed code selects.
     *
     * <p>Refactoring Rationale: the strict form is the one adopted because the sealed position this
     * service publishes carries the last code the caller RECEIVED -- that is what
     * {@code service/ReferencePaging} mints, and it is what every other keyset browse in this codebase
     * seals. Reproducing the inclusive comparison instead would have required this browse alone to seal
     * the surplus row's code, giving one service two paging conventions and making the sealed value mean
     * something different here from everywhere else. Alternatives Considered: keeping the inclusive form
     * and having the service hand it the successor of the received code. Rejected because a two-digit
     * code has no total successor function that is not itself a second encoding of the key order.
     *
     * <p>Refactoring Rationale: each optional arm is written as one predicate that an absent argument
     * satisfies, rather than as the flag-guarded disjunction the baseline needs, for the reason recorded
     * under Decisions on this type: the flag existed because a one-byte field cannot represent its own
     * absence. The resulting truth table is the same one -- an argument present narrows the result, an
     * absent argument admits every row exactly as the pass-through arms at lines 346 and 350 do.</p>
     *
     * <p>Assumptions: the query is written out rather than derived from this member's name. Three
     * predicates that each have to tolerate an absent argument, and a bound, cannot be expressed by a
     * derived name at all, and the nearest name that could be derived would make every filter
     * mandatory.</p>
     *
     * @param typeCd the exact code to narrow to, produced by {@link #typeCodeFilter(String)}, or
     *     {@code null} to leave the type arm out and admit every code
     * @param descriptionPattern the pattern to match, produced by
     *     {@link #descriptionFilterPattern(String)} so that the caller's own metacharacters are already
     *     escaped, or {@code null} to leave the description arm out
     * @param startKey the last code the caller already received, EXCLUDED from the result, or
     *     {@code null} for the opening page, which admits every code
     * @param limit the bound on rows returned, one greater than the window the caller publishes, so
     *     that the surplus row settles the further-page flag
     * @return the bounded rows in ascending code order, empty when no row satisfies every arm
     */
    @Query("select t from TransactionType t"
            + " where (:startKey is null or t.typeCd > :startKey)"
            + " and (:typeCd is null or t.typeCd = :typeCd)"
            + " and (:descriptionPattern is null"
            + " or t.description like :descriptionPattern escape '" + LIKE_ESCAPE + "')"
            + " order by t.typeCd asc")
    List<TransactionType> findFilteredPageAfter(
            @Param("typeCd") String typeCd,
            @Param("descriptionPattern") String descriptionPattern,
            @Param("startKey") String startKey,
            Limit limit);

    /**
     * Reads the page before a position, narrowed by either optional filter, in DESCENDING order.
     *
     * <p>Assumptions: this is {@code C-TR-TYPE-BACKWARD} transcribed whole -- the strictly-less
     * comparison at physical line 359, both optional arms across lines 360 to 366, and the descending
     * order at line 367. The asymmetry against the inclusive comparison of the member above is the
     * baseline's own and is deliberate: going forward the key handed in is the first key of the page
     * being requested, while going back it is the first key of the page being LEFT -- physical line 1731
     * moves that key into the start key -- so the row it names belongs to the page the caller already
     * holds and has to be excluded. Reproducing one convention in both directions would shift which row
     * begins each page by one, and a caller walking forward and then back would then see a row twice or
     * not at all.</p>
     *
     * <p>Assumptions: the rows come back DESCENDING and reversing them is the caller's responsibility,
     * as it is for the unfiltered backward walk above and for every browse in this context. Stating it
     * here is not decoration: a caller that published this result unreversed would render a page upside
     * down, and nothing in the result itself would report that.</p>
     *
     * <p>Assumptions: a {@code null} position selects no row rather than every row, because a comparison
     * against an absent value is unknown in SQL and an unknown predicate admits nothing. That suits the
     * only caller a backward page has, which retreats from a page it received and therefore holds a
     * position by construction; the opening page is taken by the member above instead. The baseline is
     * in the same position -- its backward paragraph is only ever reached after a page has been
     * displayed, invoked at physical lines 785 to 786.</p>
     *
     * @param typeCd the exact code to narrow to, produced by {@link #typeCodeFilter(String)}, or
     *     {@code null} to leave the type arm out and admit every code
     * @param descriptionPattern the pattern to match, produced by
     *     {@link #descriptionFilterPattern(String)} so that the caller's own metacharacters are already
     *     escaped, or {@code null} to leave the description arm out
     * @param startKey the first code of the page being left, EXCLUDED from the result; an absent value
     *     selects no row
     * @param limit the bound on rows returned, one greater than the window the caller publishes, so
     *     that the surplus row -- which on a descending walk carries the lowest key and sits first once
     *     reversed -- settles the further-page flag
     * @return the bounded rows in descending code order, empty when no earlier row satisfies every arm
     */
    @Query("select t from TransactionType t"
            + " where t.typeCd < :startKey"
            + " and (:typeCd is null or t.typeCd = :typeCd)"
            + " and (:descriptionPattern is null"
            + " or t.description like :descriptionPattern escape '" + LIKE_ESCAPE + "')"
            + " order by t.typeCd desc")
    List<TransactionType> findFilteredPageBefore(
            @Param("typeCd") String typeCd,
            @Param("descriptionPattern") String descriptionPattern,
            @Param("startKey") String startKey,
            Limit limit);

    /**
     * Counts every row the filters admit, wherever it sits in the ordered set.
     *
     * <p>Assumptions: this carries NO comparison against a walk's position, and that omission is the
     * whole point of it. The baseline aggregate it replaces is the same shape: paragraph
     * {@code 9100-CHECK-FILTERS} begins at physical line 1801, its {@code SELECT COUNT(1)} stands at
     * physical line 1804, and its predicate across lines 1807 to 1814 carries the two filter arms and
     * no start-key comparison at all. It answers one question -- does this filter match any row
     * anywhere -- which is what distinguishes a filter that matched nothing from a walk that reached the
     * end of the set.</p>
     *
     * <p>Trade-offs: the answer must never be published as a count of pages, a count of elements or
     * anything a caller could position a window with. Doing so would reintroduce, through a value
     * rather than through a parameter, the very positioning the charter's bounding ruling rejects, and
     * it would be reintroduced in the one place least likely to be reviewed for it. The envelope in
     * {@code com.carddemo.common.web.PageResponse} declares no component able to carry such a figure
     * and the published contract declares none either, so a caller that wanted to publish it would have
     * to change both first.</p>
     *
     * @param typeCd the exact code to narrow to, produced by {@link #typeCodeFilter(String)}, or
     *     {@code null} to leave the type arm out and count every code
     * @param descriptionPattern the pattern to match, produced by
     *     {@link #descriptionFilterPattern(String)}, or {@code null} to leave the description arm out
     * @return how many rows both arms admit, zero when none do, which is the outcome that reports a
     *     filter matching nothing
     */
    @Query("select count(t) from TransactionType t"
            + " where (:typeCd is null or t.typeCd = :typeCd)"
            + " and (:descriptionPattern is null"
            + " or t.description like :descriptionPattern escape '" + LIKE_ESCAPE + "')")
    long countFilterMatches(
            @Param("typeCd") String typeCd, @Param("descriptionPattern") String descriptionPattern);

    /**
     * Turns the code a caller filtered by into the argument the filtered walks take.
     *
     * <p>Assumptions: three inputs mean "no type filter" and not "the type whose code is zero", because
     * the baseline collapses all three into one filter-blank state at physical lines 1101 to 1106: it
     * tests the input against low values, against spaces and against zeros, sets the blank flag, and
     * leaves the walk unnarrowed. The published contract states the same rule on its type-code filter
     * parameter in {@code src/main/resources/openapi/reference-api.yaml}, which is why that parameter
     * admits {@code 00} while the stored-code schema refuses it.</p>
     *
     * <p>Assumptions: the collapse belongs HERE, beside the queries that read the result, rather than in
     * each caller. A caller that omitted it would narrow a walk to the code {@code 00}, which no seeded
     * row carries, so a request that meant "every type" would answer with an empty page -- a wrong
     * answer that looks like a correct one, since an empty page is a legitimate reply to a filter that
     * matches nothing.</p>
     *
     * <p>Trade-offs: a value whose every character is a zero is treated as blank, not only the exact
     * two-character form. The published parameter is constrained to two digits, so {@code 00} is the
     * only such value that can arrive through the contract; accepting the wider family costs one
     * comparison and means a caller reaching this member from a batch record, where no such constraint
     * applies, cannot narrow a walk to a code that is zero by another spelling. No validation is
     * attempted here beyond that: rejecting a malformed code belongs to the request shape, which
     * declares the two-digit constraint, and repeating it here would stand up a second contract able to
     * drift from the first.</p>
     *
     * <p>Assumptions: this member raises nothing, and the absence of an exception at-clause below is a
     * declared inapplicability rather than an omission. Every value of its parameter type is accepted,
     * an absent value included, and each of the three states it recognises has a return value; there is
     * consequently no input for which it could have anything to raise. It is stated because the
     * Explainability rule lists a docstring that omits an element among its forbidden patterns at line
     * 39, so a reader has to be able to tell one case from the other.</p>
     *
     * @param typeCode the code a caller filtered by, which may be {@code null}, blank, or zeros
     * @return the code to narrow a walk to, or {@code null} when the input means no type filter, which
     *     is the value the filtered walks read as an absent arm
     */
    static String typeCodeFilter(String typeCode) {
        if (typeCode == null) {
            return null;
        }
        String candidate = typeCode.strip();
        // WHY : Trade-offs: the test is "every character is a zero" rather than an equality against the
        //       two-character form, which is the wider family the type-level entry on this member
        //       accepts and explains. It also keeps this member free of any width check: the request
        //       shape declares the two-digit constraint, and a second declaration of it here could
        //       drift from that one.
        if (candidate.isEmpty() || candidate.chars().allMatch(character -> character == '0')) {
            return null;
        }
        return candidate;
    }

    /**
     * Builds the description pattern the filtered walks bind, from the text a caller searched for.
     *
     * <p>Assumptions: the wildcards belong to this service and not to the caller, and this member is
     * where the baseline's own pattern construction is transcribed. Physical lines 1155 to 1162 read
     * {@code IF FLG-DESCFILTER-ISVALID / STRING '%' FUNCTION TRIM(WS-IN-TYPE-DESC) '%' DELIMITED BY SIZE
     * INTO WS-TYPE-DESC-FILTER}, so the program surrounds the trimmed input with one wildcard on each
     * side before binding it, making the search a containment test. Its filter field is declared
     * {@code WS-TYPE-DESC-FILTER PIC X(52)} at physical line 278, two characters wider than the
     * fifty-character description at {@code app/cpy/CVTRA03Y.cpy} line 6 -- room for exactly those two
     * wildcards. The published contract states the same rule on its description filter parameter in
     * {@code src/main/resources/openapi/reference-api.yaml}: send the text being searched for, not a
     * pattern.</p>
     *
     * <p>Assumptions: escaping happens HERE and nowhere else, so ownership of it is unambiguous. The two
     * filtered walks and the aggregate each declare an explicit escape clause naming
     * {@link #LIKE_ESCAPE}, and each documents that the pattern it takes has already been through this
     * member. A caller that hand-built a pattern instead would be binding an unescaped one against a
     * query whose escape clause has nothing to act on.</p>
     *
     * <p>Alternatives Considered: the derived query keywords that match a containing value would have
     * escaped the caller's metacharacters without this member, since the framework applies its own
     * escape character to them. They are not usable here: this walk also has to tolerate an absent
     * description, an absent type code and an absent position in the same predicate, and a derived name
     * can express none of those, so the query is written out and the escaping comes with it.</p>
     *
     * <p>Refactoring Rationale: the baseline binds the trimmed input through {@code TRIM} at physical
     * lines 348 to 349 with no escaping, so a caller's own wildcard reaches the pattern there and acts
     * as a wildcard, while here it matches the character the caller typed. That divergence is
     * deliberate and is registered in {@code docs/architecture/cobol-to-service-traceability.md}. The
     * ground for it is what the alternative silently costs: an unescaped metacharacter changes WHICH
     * rows match, so a search for a description containing a percent sign would return rows that do not
     * contain one, and the caller would receive a plausible page rather than an error. A scan argument
     * is deliberately not offered as the reason, because it does not hold: {@code V1__reference.sql}
     * declares only the primary key on this table at its line 164, so no pattern on the description
     * could have used an ordered index range in the first place.</p>
     *
     * <p>Trade-offs: what is given up is the ability to hand a crafted pattern through the published
     * query parameter, which is accepted because the contract declares a containment search over the
     * caller's literal text and offers no way to declare anything else. The escape character itself is
     * emitted twice when the text contains it, so a description holding one is still matched
     * literally.</p>
     *
     * <p>Assumptions: leading and trailing whitespace is removed with the Unicode-aware form rather than
     * the older one, which removes only characters at or below the space. A filter pasted with a
     * non-Latin space would otherwise be searched for with that space included and would match no
     * description, whereas the baseline's {@code FUNCTION TRIM} is dealing with a blank-padded
     * constant-width field where no such character can arise.</p>
     *
     * <p>Assumptions: this member raises nothing either, for the same reason recorded on its sibling
     * above: an absent value and a blank value each have a return value, and every remaining value of
     * its parameter type is escaped character by character rather than examined for validity. The
     * absence of an exception at-clause below is therefore declared rather than overlooked.</p>
     *
     * @param searchText the text the caller searched for, taken as literal text rather than as a
     *     pattern, and permitted to be {@code null} or blank
     * @return a pattern matching every description that contains that text, with the caller's own
     *     metacharacters escaped, or {@code null} when the text is absent or blank, which is the value
     *     the filtered walks and the aggregate read as an absent arm
     */
    static String descriptionFilterPattern(String searchText) {
        if (searchText == null) {
            return null;
        }
        String searched = searchText.strip();
        if (searched.isEmpty()) {
            return null;
        }
        char escape = LIKE_ESCAPE.charAt(0);
        // WHY : Assumptions: the two surrounding wildcards are the transcription of the STRING
        //       statement at app/app-transaction-type-db2/cbl/COTRTLIC.cbl physical lines 1155 to 1162,
        //       and they are appended here rather than escaped with the rest because they are this
        //       service's own wildcards. Everything BETWEEN them comes from the caller and is therefore
        //       escaped character by character.
        StringBuilder pattern = new StringBuilder(searched.length() + 8);
        pattern.append('%');
        for (int index = 0; index < searched.length(); index++) {
            char character = searched.charAt(index);
            // WHY : Assumptions: three characters need the escape and no others -- the two SQL LIKE
            //       metacharacters, and the escape character itself, which would otherwise consume the
            //       character after it.
            // WHY : Alternatives Considered: a chain of whole-string replacements was rejected because
            //       it is order-dependent -- replacing the metacharacters first and the escape
            //       character afterwards escapes the escape characters just emitted, turning each into
            //       a literal one and leaving the metacharacter unescaped again. One pass over the
            //       characters has no such order to get wrong.
            if (character == escape || character == '%' || character == '_') {
                pattern.append(escape);
            }
            pattern.append(character);
        }
        pattern.append('%');
        return pattern.toString();
    }
}
