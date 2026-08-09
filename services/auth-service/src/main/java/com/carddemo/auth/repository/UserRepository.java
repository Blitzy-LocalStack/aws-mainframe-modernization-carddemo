package com.carddemo.auth.repository;

import com.carddemo.auth.domain.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The sole data-access port onto {@code auth.users}, the identity record of the AUTH bounded context.
 *
 * <p>Every relational equivalent of a file operation the baseline issues against its {@code USRSEC}
 * dataset is either declared in this interface or inherited into it, and none is expressed anywhere
 * else in this module. Two constants, one cursor sentinel, two keyset browse queries and one
 * alternate-key lookup are declared; the keyed operations are inherited. There is no third browse
 * direction and no offset paging of any kind, and both absences are load bearing rather than
 * incidental.</p>
 *
 * <p>Assumptions: the alternate-key lookup is the one member here with NO baseline counterpart, and
 * saying so is what keeps the sentence above honest. The baseline reaches a user row by one key only,
 * {@code SEC-USR-ID}, and verifies identity by comparing the plaintext {@code SEC-USR-PWD} the same
 * record carries, at {@code app/cbl/COSGN00C.cbl} line 223. The target declines parity on exactly
 * that point -- AAP section 0.7.8 records it, and
 * {@code V1__auth.sql} declares no password column -- so verification moves to the managed user pool
 * and the row is reached by the subject the pool issues. That link needs a query the baseline never
 * had, which is why {@link #findByCognitoSub(UUID)} is documented as net-new rather than cited to a
 * COBOL line that does not exist.</p>
 *
 * <h2>Four file verbs become two queries</h2>
 *
 * <p>AAP transformation rule T5 maps each CICS file verb onto exactly one target, and this interface
 * is where the browse ensemble of {@code app/cbl/COUSR00C.cbl} lands. That program drives its file
 * through four paragraphs: {@code STARTBR-USER-SEC-FILE.} at line 586 whose verb spans lines 588 to
 * 595, {@code READNEXT-USER-SEC-FILE.} at line 619 whose verb spans lines 621 to 629,
 * {@code READPREV-USER-SEC-FILE.} at line 653 whose verb spans lines 655 to 663, and
 * {@code ENDBR-USER-SEC-FILE.} at line 687 whose verb spans lines 689 to 691. Only the two reads
 * survive as members here, which is what makes the count on this boundary two rather than four.</p>
 *
 * <p>Assumptions: two is transcribed from the source rather than chosen here. The same program
 * reaches all four verbs from exactly two paragraphs, {@code PROCESS-PAGE-FORWARD} at line 282 and
 * {@code PROCESS-PAGE-BACKWARD} at line 336, entered in turn from {@code PROCESS-PF7-KEY} at line 237
 * and {@code PROCESS-PF8-KEY} at line 260. Two directions in the baseline is two queries here, so a
 * third query would describe a movement the source does not offer.</p>
 *
 * <p>Assumptions: the key both queries page by is {@code SEC-USR-ID}, the eight-character field at
 * zero-based offset 0 of the 80-byte {@code SEC-USER-DATA} layout declared at
 * {@code app/cpy/CSUSR01Y.cpy} lines 17 to 23. It is the whole key rather than the leading part of a
 * compound one, which is what makes a single-column keyset predicate sufficient: each positioned verb
 * above passes that same field as both its {@code RIDFLD} and its {@code KEYLENGTH}, so ordering by
 * one column reproduces the sequence the baseline reads in. {@code app/csd/CARDDEMO.CSD} line 94
 * declares {@code BROWSE(YES)} on the file, which is why those browse verbs exist at all.</p>
 *
 * <p>Assumptions: the ordering both queries rely on is served by the primary-key index and by nothing
 * else. {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql} creates this table
 * with a single statement and declares {@code user_id CHAR(8) PRIMARY KEY}, adding no further index.
 * That one index is exactly the access path a keyset predicate over {@code user_id} needs, so none
 * is requested here and none is missing.</p>
 *
 * <h2>What this interface deliberately does not carry</h2>
 *
 * <p>Assumptions: the keyed operations are inherited from {@code JpaRepository} and are deliberately
 * not redeclared, because redeclaring a method that behaves identically to the one it hides adds a
 * second place for the contract to drift. Each has a single baseline site: the keyed read is
 * {@code EXEC CICS READ} at {@code app/cbl/COUSR02C.cbl} lines 322 to 331 and at
 * {@code app/cbl/COUSR03C.cbl} lines 269 to 278, whose target is the inherited {@code findById}; the
 * insert is {@code EXEC CICS WRITE} at {@code app/cbl/COUSR01C.cbl} lines 240 to 248, whose target is
 * the inherited {@code save}; the update is {@code EXEC CICS REWRITE} at
 * {@code app/cbl/COUSR02C.cbl} lines 360 to 366, whose target is that same {@code save}; and the
 * removal is {@code EXEC CICS DELETE} at {@code app/cbl/COUSR03C.cbl} lines 307 to 311, whose target
 * is the inherited {@code delete}. The duplicate-key outcome the baseline reports from its paired
 * {@code DFHRESP(DUPKEY)} and {@code DFHRESP(DUPREC)} arms at lines 260 and 261 of
 * {@code app/cbl/COUSR01C.cbl} is raised by the primary-key constraint rather than by a member here,
 * so no existence probe is declared alongside the inherited {@code existsById}.</p>
 *
 * <p>Assumptions: no method below carries an exception at-clause, and the omission is uniform and
 * considered rather than overlooked. The project Explainability rule asks for exceptions at its line
 * 21 qualified by "where applicable", and {@code tests/README.md} lines 544 to 549 name the same
 * element. Every member here is a query. A query declares no checked exception, and the unchecked
 * data-access failures the framework translates -- a lost connection, a statement the caller cannot
 * influence -- are not conditions a caller handles per call site; they surface through the shared web
 * error contract in {@code com.carddemo.common.error}. Declaring a speculative at-clause on each
 * member would add unverifiable claims rather than facts. Checkstyle agrees mechanically: its
 * {@code JavadocMethod} module runs with {@code validateThrows} true, and an interface method has no
 * body from which an undocumented throw could be detected.</p>
 *
 * <p>Assumptions: this interface holds no codec and imports none. Every field of
 * {@code SEC-USER-DATA} is {@code PIC X(n)}, that is character data, so the record carries no zoned
 * decimal, no packed decimal, no money and no timestamp. Reaching for a type from
 * {@code com.carddemo.common.codec} here would decode a representation this record does not contain,
 * and reaching for {@code com.carddemo.common.time} would format a column
 * {@code V1__auth.sql} does not declare.</p>
 *
 * <h2>Documentation obligations this interface is written against</h2>
 *
 * <p>Assumptions: every member below carries a docstring and every non-obvious decision carries an
 * adjacent rationale under one of four canonical labels, because the project's Explainability rule
 * requires both halves and its validation gate at line 43 fails work missing either one. The written
 * convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated here.
 * The mechanical half is {@code config/checkstyle/checkstyle.xml}, whose {@code MissingJavadocType}
 * module lists {@code INTERFACE_DEF} among its tokens, which is why this block is required on the
 * type itself and not only on its members.</p>
 */
// WHY : Alternatives Considered: offset pagination was rejected, and with it every Spring Data shape
//       that expresses it -- the paging request abstraction and its request implementation, the
//       paged result type, the sliced result type, a SQL OFFSET clause in a declared query, a
//       criteria specification assembled around one, and a derived method that takes a page ordinal.
//       Spring Data ships all of them, so choosing none of them is a decision that has to be stated
//       rather than a road not noticed. The concrete failure mode is skip-and-repeat: an offset query
//       locates its first row by counting from the start of the ordering on every request, so an
//       insert or a delete landing ahead of that point between two page turns shifts every later row
//       by one position, and the reader then either never sees a row that moved past the boundary or
//       sees a row twice that moved back across it. A key already returned keeps its place in the
//       ordering no matter what is inserted or removed around it. The baseline is already keyed in
//       exactly this way -- its three positioned verbs at lines 588 to 595, 621 to 629 and 655 to
//       663 of app/cbl/COUSR00C.cbl each carry RIDFLD and KEYLENGTH and never a row count -- so
//       keyset paging preserves the page boundary the source produces and offset paging changes it.
// WHY : Refactoring Rationale: the browse cannot be carried across unchanged, and what has to change
//       is where the position is kept. In app/cbl/COUSR00C.cbl the position lives in a CICS cursor
//       opened against the USRSEC file and in the identifier pair the program hands back to itself
//       between screen turns, declared at lines 68 and 69, so it survives only as long as the task
//       and its cursor survive. A stateless request handler has neither. That is why STARTBR at
//       lines 588 to 595 and ENDBR at lines 689 to 691 have NO counterpart on this boundary, and
//       their absence is the point rather than an omission: a query carries its position in its own
//       predicate and closes its own result set, so there is no handle to open and none to release.
//       ENDBR is the clearest case, because its verb is bare -- lines 689 to 691 name DATASET and
//       nothing else, not even RESP or RESP2 -- so there is not even a status for a caller here to
//       inspect. The forward and backward paths perform that paragraph at lines 325 and 374; those
//       are call sites, and the verb itself sits in the paragraph declared at line 687. Both levels
//       disappear together.
// WHY : Refactoring Rationale: this interface declares NO version property and NO lock annotation,
//       and neither does the entity it reads. auth.users declares no version column under any name
//       and no system column pressed into that role, so annotating one here would describe a column
//       V1__auth.sql does not create, and that failure arrives at first use rather than in review.
// WHY : Assumptions: that absence rests on three witnesses in the baseline rather than on inference,
//       and each was read directly. First, app/cbl/COUSR02C.cbl re-reads the record inside the
//       update action at lines 215 to 217 and then compares screen input against it; its EXEC CICS
//       READ at lines 322 to 331 carries RIDFLD (SEC-USR-ID) at line 326 AND the UPDATE option at
//       line 328, so the row is read under an exclusive lock, and its EXEC CICS REWRITE at lines 360
//       to 366 carries neither RIDFLD nor KEYLENGTH because it rewrites the row that read already
//       holds. Second, app/cbl/COUSR03C.cbl lines 190 and 191 are literally adjacent statements,
//       PERFORM READ-USER-SEC-FILE then PERFORM DELETE-USER-SEC-FILE, with no intervening logic at
//       all; its READ at lines 269 to 278 likewise carries RIDFLD at line 273 and UPDATE at line
//       275, and its EXEC CICS DELETE at lines 307 to 311 carries exactly three operands and so no
//       record identifier, for the same reason. Third, app/csd/CARDDEMO.CSD defines the file itself
//       pessimistically, as RLSACCESS(NO) at line 89 and UPDATEMODEL(LOCKING) at line 93. Each lock
//       is therefore taken and released inside one task and none is held across the
//       pseudo-conversational gap, so there is no baseline concurrency behaviour for a version
//       property to reproduce and no conflict outcome for a caller of this interface to handle.
// WHY : Assumptions: the contrast with app/cbl/COACTUPC.cbl is what makes this a decision rather
//       than an oversight, because that program does implement a hand-rolled before-image check
//       across the same gap. Its line 168 declares 05 WS-DATACHANGED-FLAG PIC X(1), its line 669
//       opens the 05 ACUP-OLD-DETAILS snapshot group whose members pair a display field with a
//       numeric REDEFINES -- ACUP-OLD-ACCT-ID-X PIC X(11) at line 671 redefined at lines 672 to
//       673, and ACUP-OLD-CURR-BAL PIC X(12) at line 675 redefined as PIC S9(10)V99 at lines 676 to
//       677 -- and its lines 521 to 522 name the condition DATA-WAS-CHANGED-BEFORE-UPDATE with the
//       operator message 'Record changed by some one else. Please review'. None of the five auth
//       programs contains any equivalent construct. The four-arm block at app/cbl/COUSR02C.cbl lines
//       219 to 234 is not one: it compares submitted screen input against the record read moments
//       earlier at line 217 under that same line 328 lock, inside a single task, so it detects
//       whether the operator changed anything, and its not-modified message at line 239 asks the
//       operator to make a change, which is the semantic opposite of a conflict report. Reading it
//       as optimistic concurrency would give this boundary a refusal outcome the baseline cannot
//       produce.
// WHY : Trade-offs: no transaction boundary is declared here, on the interface or on any member, and
//       the boundaries that do exist are a Java-side design decision rather than a parity
//       requirement. The word SYNCPOINT appears zero times in every one of the five auth programs --
//       COSGN00C, COUSR00C, COUSR01C, COUSR02C and COUSR03C -- where the contrast program COACTUPC
//       contains two, so there is no baseline commit scope to transcribe. app/csd/CARDDEMO.CSD
//       strengthens that reading: JOURNAL(NO) at line 94 and RECOVERY(NONE) at line 96 mean CICS
//       performed no logging and no backout for this file, so there was literally nothing to commit
//       or to roll back. The compromise accepted is that a reader cannot learn the commit scope from
//       this file; what is bought is that the scope sits with the behaviour that owns it, in
//       com.carddemo.auth.service, where a read path can be marked read-only and a mutation cannot.
// WHY : Trade-offs: the target reads at PostgreSQL's READ COMMITTED default, which is STRICTER than
//       the READINTEG(UNCOMMITTED) recorded at app/csd/CARDDEMO.CSD line 90. The baseline could
//       therefore return a row a concurrent task had written and not yet committed, and neither
//       query below can. The compromise accepted is that this is a behavioural difference rather
//       than a transcription, so it is registered as such; it is accepted because the observable
//       effect is the removal of a dirty read, and reproducing dirty reads would mean weakening the
//       engine's default to recover a defect.
// WHY : Refactoring Rationale: the file-wide concurrency ceiling recorded as STRINGS(1) at
//       app/csd/CARDDEMO.CSD line 91 has no counterpart here, and dropping it is the intended
//       outcome. That setting admitted one concurrent VSAM request string against USRSEC, so two
//       administrators listing users serialised against each other for a reason that was a
//       storage-access limit and never a business rule. Row-level locking replaces it, so unrelated
//       rows are read and administered concurrently while each individual mutation keeps its own
//       row-level exclusion.
public interface UserRepository extends JpaRepository<User, String> {

    // WHY : Assumptions: ten is transcribed from the source and attested twice over, so a caller
    //       that derives its row cap from this constant is reproducing the baseline page rather than
    //       adopting a convention. app/cbl/COUSR00C.cbl line 56 opens 01 WS-USER-DATA and line 57
    //       declares its screen array as 02 USER-REC OCCURS 10 TIMES, and the forward fill loop at
    //       lines 300 to 306 is bounded by that same figure, stopping once its index reaches eleven.
    //       Independently, the symbolic map app/cpy-bms/COUSR00.CPY declares one selection field per
    //       displayed row on a strict thirty-line stride, from 02 SEL0001I PIC X(1). at line 72 to
    //       02 SEL0010I PIC X(1). at line 342, and 72 plus 30 times 9 is exactly 342, so the map
    //       carries ten rows and not one more. Two independent artifacts agreeing is what makes this
    //       a measurement instead of an assumption about screen size.
    int PAGE_SIZE = 10;

    // WHY : Assumptions: the row cap is the page size plus one, and the surplus row is a
    //       transcription rather than a heuristic. Having filled its ten display slots through the
    //       loop at lines 300 to 306, app/cbl/COUSR00C.cbl performs an ELEVENTH forward read at line
    //       311 for no purpose other than to discover whether anything follows, and lines 312 to
    //       316 set its availability indicator to yes at line 313 or to no at line 315 from that
    //       read's outcome alone, with the exhausted branch setting it to no at line 318. The
    //       indicator it sets is CDEMO-CU00-NEXT-PAGE-FLG, declared at line 71 with its two
    //       condition names at lines 72 and 73. Fetching one row beyond the page is therefore
    //       exactly what fills the has-next member of com.carddemo.common.web.PageResponse, and it
    //       is why no member of this interface counts rows: a count would answer a question no
    //       screen asks and would add a second scan to every page turn.
    int FETCH_LIMIT = PAGE_SIZE + 1;

    // WHY : Assumptions: this is the cursor a caller supplies to open the list, and it is the
    //       relational analogue of the sentinel the baseline uses for the same purpose rather than a
    //       shape invented here. app/cbl/COUSR00C.cbl seeds SEC-USR-ID with LOW-VALUES at line 219
    //       when no identifier was typed, and at line 240 when no first-page cursor is held, and
    //       then drives THE SAME positioned browse at line 284 that a real cursor drives. The source
    //       mechanism is consequently a sentinel key fed into one verb, not a second verb, which is
    //       why the opening page is this constant passed to the forward query and not a third member
    //       on this interface. A value that orders below every stored key is what the sentinel has to
    //       be: because user_id is CHAR(8) and PostgreSQL ignores trailing blanks when comparing that
    //       type, the empty string is less than every identifier the table can hold, so a strict
    //       greater-than against it admits the whole set beginning at the lowest key.
    // WHY : Alternatives Considered: a nullable cursor was rejected, and so was a separate
    //       unfiltered member. A null cursor would make the comparison below evaluate to unknown and
    //       silently return no rows at all, turning an opening page into an empty list that looks
    //       like an empty table -- the two are distinguishable outcomes and must stay so. A separate
    //       unfiltered member would raise the browse-query count on this boundary from two to three,
    //       which the package charter beside this file closes off, and it would additionally state a
    //       starting position in a signature where the source states it in a value.
    String BEFORE_FIRST_USER_ID = "";

    /**
     * Reads the page of users that follows a stated position, in ascending identifier order.
     *
     * <p>This is the relational form of the forward read at lines 621 to 629 of
     * {@code app/cbl/COUSR00C.cbl}, driven from the fill loop at lines 300 to 306 of
     * {@code PROCESS-PAGE-FORWARD} and probed once more at line 311. Passing
     * {@link #BEFORE_FIRST_USER_ID} opens the list at the lowest stored key, which is the same
     * request the baseline makes when it seeds its key field at line 219 and pages forward at line
     * 228.</p>
     *
     * @param lastKey the identifier of the last row the caller already holds, of type {@code String},
     *     or {@link #BEFORE_FIRST_USER_ID} to open the list; the comparison is STRICT, so a row whose
     *     identifier equals this value is excluded and the page begins at the next identifier after
     *     it; must not be {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller builds
     *     from {@link #FETCH_LIMIT} so that the surplus row answers forward availability; must not be
     *     {@code null}
     * @return the following rows as a {@code List<User>}, ASCENDING by identifier and therefore
     *     already in display order, holding up to {@code limit} rows -- so up to {@link #PAGE_SIZE}
     *     plus one, whose presence beyond the page size is what reports that a further page exists
     *     and which is a probe rather than a row to display -- and empty when the set is exhausted
     */
    // WHY : Assumptions: the strictness of this comparison is stated here because the baseline never
    //       stated it anywhere, so a reader of the source cannot see what positioning it relied on.
    //       The start-browse at app/cbl/COUSR00C.cbl lines 588 to 595 names DATASET at line 589,
    //       RIDFLD at line 590 and KEYLENGTH at line 591, and its GTEQ option at line 592 is
    //       COMMENTED OUT, the asterisk sitting in the indicator column, so whether the positioning
    //       row was itself included came from the access method's default rather than from anything
    //       the program declares. That commented line is not live behaviour and must not be read as
    //       such. Writing the predicate as a strict comparison makes the page boundary an artifact of
    //       this query instead of an artifact of a file system, and were it inclusive instead, the
    //       first row of every forward page would repeat the last row of the previous one.
    // WHY : Assumptions: one predicate serves both an inclusive opening page and a strictly
    //       exclusive continuation, and the baseline draws the distinction the same way -- through
    //       the value it seeks on, not through a second access path. app/cbl/COUSR00C.cbl guards its
    //       priming read at line 288 with IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3, so on the
    //       opening turn, where the attention identifier IS the enter key, no priming read happens
    //       and the fill loop returns the row at the seek position; on a forward page turn the guard
    //       holds and the extra read at line 289 consumes the cursor row so the loop starts after
    //       it. The backward path is shaped identically at lines 342 to 344, and although the two
    //       guards name different numbers of keys, three forward and two backward, both yield one
    //       extra positioning read, so continuation is strictly exclusive in both directions. A
    //       strict greater-than reproduces both cases here because the sentinel above is not itself a
    //       stored key: strictly greater than a value below the whole key space is the whole key
    //       space.
    // WHY : Refactoring Rationale: a caller must take the returned page's boundary keys from the
    //       ACTUAL first and last rows of the list it receives, never from a predetermined row
    //       position, and this method returns the rows in an order that makes that possible. The
    //       baseline cannot do that. Its POPULATE-USER-DATA paragraph at line 384 evaluates the row
    //       index at line 386 and writes a cursor field on only two of its eleven arms: WHEN 1 at
    //       line 387 carries CDEMO-CU00-USRID-FIRST as the second receiver of the single
    //       multi-receiver MOVE at lines 388 to 389, and WHEN 10 at line 433 carries
    //       CDEMO-CU00-USRID-LAST as the second receiver of the MOVE at lines 434 to 435. WHEN 9 at
    //       lines 428 to 432 writes no cursor field, and neither do WHEN 2 through WHEN 8 at lines
    //       393 to 427. On any short final page, one holding fewer than ten rows, the tenth arm
    //       never executes, so the last-key field is left holding a value from a previous page. The
    //       baseline behaves that way and keeps behaving that way; the Java encodes a boundary key
    //       derived from the row actually returned; the divergence is documented in
    //       docs/architecture/cobol-to-service-traceability.md. This is
    //       also the reason ascending order is part of the contract above rather than an incidental
    //       property of the query: a caller that reversed this list would take its keys from the
    //       wrong ends.
    // WHY : Assumptions: the cursor crossing this boundary is a TRIMMED logical identifier while the
    //       stored key is blank-padded, and the two order identically for the eight-character domain
    //       in use, which is what lets an untrimmed comparison be safe here. user_id is CHAR(8), and
    //       PostgreSQL ignores trailing blanks when comparing that type, so 'AA' and 'AA' followed by
    //       six blanks compare equal and sort together. The baseline performs the same right-trim
    //       explicitly: app/cbl/COUSR01C.cbl builds its confirmation text at lines 255 to 258 with
    //       SEC-USR-ID DELIMITED BY SPACE at line 256, treating the trailing blanks of the X(08) key
    //       as padding rather than as content. Trimming itself belongs to the mapper layer and is
    //       never performed here, because a repository that trimmed would be reshaping a key it was
    //       given rather than comparing it.
    List<User> findByUserIdGreaterThanOrderByUserIdAsc(String lastKey, Limit limit);

    /**
     * Reads the first page of users, in ascending identifier order.
     *
     * <p>Assumptions: this is the browse's opening read, the one taken when no cursor has been supplied
     * yet. It corresponds to the reference starting its browse at the low key rather than positioning on
     * a value -- {@code app/cbl/COUSR00C.cbl} establishes the position before its first
     * {@code READNEXT} rather than passing a key it was given.
     *
     * <p>Alternatives Considered: opening the browse with
     * {@link #findByUserIdGreaterThanOrderByUserIdAsc} passing an empty string, which would remove this
     * declaration. Rejected because it leans on a collation detail to mean "before every key": the column
     * is {@code CHAR(8)} and PostgreSQL ignores trailing blanks when comparing it, so an empty string and
     * a string of blanks compare equal, and a row whose identifier were blank would be silently excluded
     * from the first page while appearing on later ones. A query with no lower bound states the intent
     * directly and cannot be wrong about it.
     *
     * <p>Assumptions: the limit is the caller's surplus-of-one, exactly as on the two positioned reads, so
     * the browse can tell a full page from a last page by whether the extra row arrived.
     *
     * @param limit the greatest number of rows to return, normally the page size plus one
     * @return the lowest-keyed users in ascending order, at most {@code limit} of them; never {@code null}
     */
    List<User> findAllByOrderByUserIdAsc(Limit limit);

    /**
     * Reads the page of users that precedes a stated position, in descending identifier order.
     *
     * <p>This is the relational form of the backward read at lines 655 to 663 of
     * {@code app/cbl/COUSR00C.cbl}, driven from {@code PROCESS-PAGE-BACKWARD} at line 336 whose
     * priming read is guarded at lines 342 to 344.</p>
     *
     * @param firstKey the identifier of the first row the caller already holds, of type
     *     {@code String}; the comparison is STRICT, so a row whose identifier equals this value is
     *     excluded and the page ends at the identifier immediately before it; must not be
     *     {@code null}
     * @param limit the maximum number of rows to read, of type {@code Limit}, which the caller builds
     *     from {@link #FETCH_LIMIT} so that the surplus row reports whether a further page precedes
     *     this one; must not be {@code null}
     * @return the preceding rows as a {@code List<User>} in DESCENDING identifier order, nearest the
     *     stated position first, holding up to {@code limit} rows -- so up to {@link #PAGE_SIZE} plus
     *     one, the surplus row again being a probe rather than a row to display -- and empty when the
     *     caller is already at the start of the set; the caller REVERSES this list into ascending
     *     display order
     */
    // WHY : Trade-offs: the rows come back reversed relative to display order and the caller carries
    //       the cost of turning them round. Ordering ascending here instead would make the row cap
    //       keep the wrong end of the set, the lowest identifiers in the whole table rather than the
    //       ones immediately preceding the caller's position, which is a page nobody asked for. The
    //       baseline accepts the same cost in the same direction: PROCESS-PAGE-BACKWARD reads
    //       nearest-first through the backward verb at lines 655 to 663 and lands the rows into
    //       display order as it fills the screen array, rather than asking the file for them in
    //       display order.
    // WHY : Trade-offs: this direction reports no availability of its own, and the page envelope has
    //       no member for it to report into. com.carddemo.common.web.PageResponse declares exactly
    //       four components, its items, a first key, a last key and a single has-next flag, and the
    //       omission of a has-previous counterpart matches the source: the list screen's own state
    //       extension declares CDEMO-CU00-NEXT-PAGE-FLG at line 71 of app/cbl/COUSR00C.cbl with its
    //       two condition names at lines 72 and 73, and there is no previous-page flag anywhere in
    //       that structure. What the baseline does instead is decide backward availability from the
    //       screen ordinal at the moment the key is pressed, at line 248, and that ordinal has no
    //       target analogue at all. The compromise accepted is that a caller cannot learn from this
    //       method whether a further page precedes the one it received without inspecting the surplus
    //       row itself; what is bought is that no page ordinal re-enters the contract through a
    //       second flag.
    // WHY : Assumptions: the two classes of boundary message the baseline emits are a presentation
    //       concern of com.carddemo.auth.api and the browser client, not of this interface, and they
    //       must not be collapsed into one. The GUARD messages fire when a key is pressed at a
    //       boundary already known: 'You are already at the top of the page...' at line 251 behind
    //       the ordinal test at line 248, and 'You are already at the bottom of the page...' at line
    //       273 behind the availability test at line 270. The ARRIVAL messages fire when a read
    //       discovers a boundary: 'You are at the top of the page...' at line 603 keyed on
    //       DFHRESP(NOTFND) at line 600, 'You have reached the bottom of the page...' at line 637
    //       keyed on DFHRESP(ENDFILE) at line 634, and a fifth and distinct literal, 'You have
    //       reached the top of the page...', at line 671 keyed on DFHRESP(ENDFILE) at line 668 --
    //       which is NOT a repeat of the line 603 text. That the line 600 arm keys on NOTFND rather
    //       than on end-of-file is the reason a cursor naming no existing row is an ordinary
    //       positioned outcome for this boundary and never an error: an empty list here means the
    //       caller is at an edge, and the layer above chooses which of those five texts to render.
    List<User> findByUserIdLessThanOrderByUserIdDesc(String firstKey, Limit limit);

    /**
     * Resolves the local user row that an authenticated provider subject identifies.
     *
     * <p>This is the token-to-row link the target's identity design depends on: a validated token
     * carries the provider's subject claim, and the authorities a request is authorised with come from
     * the {@code user_type} of the row that subject names. It is the only lookup on this boundary that
     * does not go through the primary key.</p>
     *
     * @param cognitoSub the provider's subject reference to resolve, of type {@code UUID}, taken from a
     *     token that has already been validated; must not be {@code null}
     * @return the row that subject identifies wrapped in an {@code Optional<User>}, holding at most one
     *     row because the column is unique, and EMPTY when no row carries that subject -- which is an
     *     ordinary outcome and not an error, since a subject the pool has issued need not yet have a
     *     local row
     */
    // WHY : Assumptions: at-most-one is a SCHEMA guarantee here rather than a convention this
    //       signature hopes for, which is what makes a single-valued return type sound.
    //       services/auth-service/src/main/resources/db/migration/V1__auth.sql declares
    //       cognito_sub UUID NOT NULL UNIQUE, and com.carddemo.auth.domain.User maps it with
    //       nullable = false and unique = true, so the two agree and the engine refuses a second row
    //       carrying one subject. Were the column merely indexed, a duplicate would make this method
    //       throw at runtime on data the database had accepted -- a failure mode the constraint
    //       removes rather than one this signature papers over. UserRepositoryIT asserts both halves:
    //       that one subject resolves to its row, and that inserting a second row with the same
    //       subject is refused.
    // WHY : Alternatives Considered: returning User directly and returning List<User> were both
    //       rejected, and for opposite reasons. A bare User would express absence as null, and the
    //       absent case here is ORDINARY -- a subject with no local row is the state a
    //       just-provisioned pool user is in -- so the type has to make the caller handle it rather
    //       than let one forget to. A List would express a cardinality the unique constraint has
    //       already excluded, and every caller would then need a size check that can never fail,
    //       which is exactly the dead branch a reader cannot tell from a live one. Optional states
    //       zero-or-one, which is precisely what the constraint guarantees.
    // WHY : Alternatives Considered: a declared @Query was rejected in favour of deriving the query
    //       from the method name. The predicate is a single equality on one mapped property, so a
    //       declared query would restate in JPQL what the property name already says, and it would
    //       additionally hard-code a column or entity name that the entity mapping owns. Deriving it
    //       also fails LOUDLY and early on a typo: Spring Data resolves the property against the
    //       entity while the context starts, so a misspelled name aborts startup with a message
    //       naming the property, whereas a hand-written query naming a wrong column would compile and
    //       fail at first execution.
    // WHY : Assumptions: the parameter is java.util.UUID and NOT String, matching the entity member
    //       and the native uuid column. Accepting a String here would push a parse or a text
    //       comparison onto this boundary and would let two spellings of one subject -- differing only
    //       in letter case or in hyphenation -- reach the database as different values, which is the
    //       comparison the uuid type exists to canonicalise. The entity's own rationale records the
    //       same choice for the same reason, so the two do not drift.
    // WHY : Trade-offs: this member has no baseline citation, and that is stated rather than
    //       disguised. Every other member of this interface names the COBOL verb it replaces; this one
    //       replaces a plaintext comparison that the target deliberately does not carry forward, so
    //       there is no verb to cite. The cost is that a reader auditing this file against the
    //       baseline finds one member with no source line; what is bought is that the audit reaches
    //       the recorded divergence instead of a fabricated citation.
    Optional<User> findByCognitoSub(UUID cognitoSub);

    /**
     * Inserts one identity row, letting the primary key refuse an identifier already taken.
     *
     * <p>Purpose: this is the relational form of the keyed {@code WRITE} at
     * {@code app/cbl/COUSR01C.cbl} lines 240 to 248, whose duplicate-key and duplicate-record response
     * arms at lines 260 and 261 are answers to the write itself and not to a prior read.
     *
     * @param userId the eight-character identifier the row is keyed by; must not be {@code null}
     * @param firstName the user's first name at its declared width; must not be {@code null}
     * @param lastName the user's last name at its declared width; must not be {@code null}
     * @param userType the one-character type, {@code 'A'} or {@code 'U'}; must not be {@code null}
     * @param cognitoSub the subject reference in canonical text form, cast to the native {@code uuid}
     *     column type by the statement; must not be {@code null}
     * @return the number of rows written, which is always one when the statement completes, the key
     *     refusing the row otherwise
     * @throws org.springframework.dao.DataIntegrityViolationException if the identifier or the subject
     *     reference is already held by a row, which the caller answers as a conflict
     */
    // WHY : Refactoring Rationale: this member exists because the INHERITED save cannot express an
    //       insert for this entity. User carries an ASSIGNED identifier and no version attribute, so
    //       Spring Data's newness test reduces to "is the identifier null", which is false for every
    //       row this context builds -- the save therefore reaches EntityManager.merge, and merge
    //       against an identifier a row already holds loads that row and UPDATES it. The observable
    //       consequence was that two callers racing one identifier did not collide at all: the later
    //       one silently overwrote the earlier one's names and type and was answered as a successful
    //       create. An insert states what the baseline write states, and the key gets to refuse it.
    // WHY : Alternatives Considered: making the entity implement Persistable with a transient
    //       newness flag, which would have routed the same save to persist. Rejected because that flag
    //       has to be reset by lifecycle callbacks and is therefore load-bearing state on a persistent
    //       type, and because it would change the meaning of every save in this module -- including the
    //       update path, which relies on merge semantics against a managed row -- to fix one call site.
    // WHY : Alternatives Considered: injecting an EntityManager into the service and calling persist
    //       there. Rejected because it would move a persistence-provider call out of the one package
    //       that owns data access, which is the boundary the module's layering test asserts.
    // WHY : Assumptions: the subject reference is bound as text and cast by the statement rather than
    //       bound as a UUID. A native statement gives the driver no column metadata to infer a
    //       parameter type from, so an explicitly cast text parameter is the form that cannot depend on
    //       inference; the canonical text form of a UUID is unambiguous, and the cast is what makes the
    //       stored value the native type the unique index is built over.
    @Modifying
    @Query(value = """
            insert into users (user_id, first_name, last_name, user_type, cognito_sub)
            values (:userId, :firstName, :lastName, :userType, cast(:cognitoSub as uuid))
            """, nativeQuery = true)
    int insertUser(@Param("userId") String userId,
            @Param("firstName") String firstName,
            @Param("lastName") String lastName,
            @Param("userType") String userType,
            @Param("cognitoSub") String cognitoSub);
}
