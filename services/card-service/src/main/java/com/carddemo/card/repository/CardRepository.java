package com.carddemo.card.repository;

import com.carddemo.card.domain.Card;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The only way a card row is reached, and the point at which the reference application's file verbs
 * become queries.
 *
 * <p>Three methods are declared here and two more are inherited. Together they are the complete set
 * of access paths to {@code card.cards}, which is the one table this bounded context owns. The
 * charter in {@code package-info.java} beside this file states the same closure and is where a reader
 * arriving at the package starts.</p>
 *
 * <h2>How the reference file verbs map onto this interface</h2>
 *
 * <p>The migration plan maps each file verb onto a target construct by category, and this interface
 * is where that mapping terminates for cards:</p>
 *
 * <ul>
 *   <li>The four browse verbs collapse into <b>one keyset-paginated query per direction</b>. Forward
 *       is {@link #findForwardFromCursor}, backward is {@link #findBackwardFromCursor}. In the
 *       reference these are the paragraph {@code 9000-READ-FORWARD.} at
 *       {@code app/cbl/COCRDLIC.cbl:1123}, which starts a browse at {@code :1129}, reads at
 *       {@code :1146}, probes at {@code :1197} and ends the browse at {@code :1258}; and
 *       {@code 9100-READ-BACKWARDS.} at {@code :1264}, which starts at {@code :1273}, primes at
 *       {@code :1294}, loops at {@code :1322} and ends at {@code :1376}.</li>
 *   <li>A keyed read becomes {@code findById(String)}, inherited from {@link JpaRepository} and
 *       deliberately not redeclared. The reference reads the card by key at
 *       {@code app/cbl/COCRDUPC.cbl:1382} and {@code :1427}, and reaches the same read in
 *       {@code app/cbl/COCRDSLC.cbl} through {@code 9100-GETCARD-BYACCTCARD} at {@code :736} with
 *       its exit at {@code :775}.</li>
 *   <li>A rewrite becomes {@code save(Card)}, also inherited and also not redeclared. The reference
 *       rewrites the card in the block at {@code app/cbl/COCRDUPC.cbl:1477-1483}, with the verb
 *       itself on {@code :1478}.</li>
 *   <li>The second, account-keyed access path becomes {@link #findByAccountIdOrderByCardNumAsc}.</li>
 * </ul>
 *
 * <p>Assumptions: the two inherited methods carry no documentation obligation here, because the
 * project documentation rule attaches that obligation to a function this file declares and neither of
 * these is declared. Recording the mapping in this list is what keeps the correspondence visible
 * without redeclaring a method purely to hold a comment -- a redeclaration would additionally have to
 * restate a signature that the supertype already fixes, giving one contract two statements of itself
 * for no gain.</p>
 *
 * <h2>The cursor is the single sixteen-character card number</h2>
 *
 * <p>Assumptions: every predicate here positions on {@code card_num} alone, and nothing is paired
 * with it. That is the single highest-consequence decision in this file, because a two-column
 * predicate would page differently from the reference at every boundary where two cards share a
 * number prefix, so the evidence is recorded rather than summarised. All four browse verbs name the
 * sixteen-byte <b>member</b> {@code WS-CARD-RID-CARDNUM} as their record identifier and state their
 * key length as the length of that same member: at {@code app/cbl/COCRDLIC.cbl:1131} with
 * {@code :1132}, at {@code :1150} with {@code :1151}, at {@code :1275} with {@code :1276}, and at
 * {@code :1298} with {@code :1299}. The group those members sit in is declared at {@code :136-141},
 * where the card number is {@code PIC X(16)} at {@code :138} and an account identifier
 * {@code PIC 9(11)} follows it at {@code :139}; the group spans twenty-seven bytes and the browse
 * positions on the first sixteen of them. The dataset definition agrees from outside the program
 * entirely, declaring a key of sixteen bytes beginning at the first byte of the record at line 54 of
 * {@code app/jcl/CARDFILE.jcl}, inside the cluster defined at line 50. The browse also runs on the
 * base cluster rather than on any index path, since the file name it names is the literal
 * {@code 'CARDDAT '} declared at {@code app/cbl/COCRDLIC.cbl:213-214}.</p>
 *
 * <p>Assumptions: the card-number and account-identifier pair a reader will notice is storage for
 * redisplay and not a positioning key, so it must not be read as a mandate for a composite
 * predicate. The reference keeps a last-key pair and a first-key pair across the gap between screen
 * turns, declared at {@code app/cbl/COCRDLIC.cbl:229-235}. What settles the question is that the
 * account-identifier half is present at all four repositioning sites and <b>commented out at every
 * one of them</b> -- at {@code :448-449}, {@code :475-476}, {@code :490-491} and {@code :506-507} --
 * while the card-number half is moved live at {@code :446-447}, {@code :473-474}, {@code :488-489}
 * and {@code :504-505}. The batch program confirms the same key from a third direction, splitting the
 * record into a sixteen-byte key and a hundred-and-thirty-four-byte remainder at
 * {@code app/cbl/CBACT02C.cbl:39-40} under the record key declared at {@code :32}.</p>
 *
 * <p>Assumptions: reading forward strictly past the cursor is equivalent to what the reference does,
 * and the equivalence is worth stating because the two mechanisms look different. The reference
 * positions with a greater-than-or-equal-to browse on a stored key and then reads forward, and the
 * key it stores is the <b>first undisplayed</b> row, overwritten from the probe read at
 * {@code app/cbl/COCRDLIC.cbl:1212-1214}. The queries here instead resume strictly after the
 * <b>last returned</b> row. Both produce identical page boundaries: with seven rows to a page the
 * reference's second page is rows eight to fourteen, and resuming strictly after the key of row seven
 * yields rows eight to fourteen as well. The consequence a caller must honour is that the probe row's
 * key is never published as a boundary, because publishing it would advance the cursor one row too
 * far and drop a row from the following page.</p>
 *
 * <h2>Why one row beyond the page is read</h2>
 *
 * <p>Assumptions: reading one row more than the page holds is the reference's own technique carried
 * across, not an optimisation added by this migration, which is why every method here takes its bound
 * from the caller as {@code Limit.of(<page size> + 1)} rather than bounding itself. The reference
 * zeroes its row counter at {@code app/cbl/COCRDLIC.cbl:1140} and enters its read loop at
 * {@code :1144}; when the counter reaches the screen limit at {@code :1191} it leaves the loop at
 * {@code :1192}, captures the last displayed row's keys at {@code :1194-1195}, and issues one further
 * read at {@code :1197}. If that read returns a row it sets the next-page condition at
 * {@code :1210-1211}; if it reaches end of file it clears the condition at {@code :1216} and reports
 * {@code 'NO MORE RECORDS TO SHOW'} at {@code :1219}. The outer end-of-file arm at {@code :1233-1245}
 * clears the same condition and records a no-records state at {@code :1244} when the first screen
 * returned nothing at all.</p>
 *
 * <p>Alternatives Considered: counting the whole matching set alongside each page, which is what a
 * total-bearing page abstraction would provide. It is not offered by any method here for two reasons.
 * It answers a question the reference never asks, so answering it would be behaviour this migration
 * added rather than carried across; and it costs a second pass over the matching rows, which on a
 * filtered card list is the entire table. Reading one surplus row answers the only question the
 * reference actually asks -- whether a further page exists -- at the cost of one row.</p>
 *
 * <h2>Why the position is a key and never a row count</h2>
 *
 * <p>Alternatives Considered: offset pagination, evaluated and rejected on behaviour rather than on
 * taste. A query that resumes by counting rows from the start of a result set skips rows it never
 * showed and repeats rows it already showed whenever rows are inserted or removed between two
 * requests, because the number of rows preceding the resume point changes underneath it. Resuming
 * from the key of the last row shown can do neither, since that key is unaffected by an insertion
 * elsewhere in the table. The reference already resumes by key, as the record identifiers cited above
 * show, so resuming by key preserves its page boundaries rather than approximating them.</p>
 *
 * <p>Alternatives Considered: expressing the bound as a paging abstraction carrying a page number, or
 * returning one carrying a total count, instead of the plain {@link Limit} every method here takes.
 * Both were rejected because of what their vocabulary admits rather than because of how they read: a
 * page-number abstraction exposes a row count to start from, and a total-bearing return type exposes
 * a count of all matching rows. Either would put the mechanism rejected in the paragraph above back
 * within reach of a caller, and neither has a counterpart in the reference. {@link Limit} expresses
 * exactly the one thing these queries need, which is a bound on how many rows come back.</p>
 *
 * <h2>Both list filters live in the same query</h2>
 *
 * <p>Assumptions: the account-identifier and card-number filters are optional predicates of the two
 * keyset queries rather than a separate operation, and they reproduce {@code 9500-FILTER-RECORDS.} at
 * {@code app/cbl/COCRDLIC.cbl:1382}, whose exit is at {@code :1409}. The reference performs that
 * paragraph after <b>every</b> read, from {@code :1159-1160} on the forward path and
 * {@code :1335-1336} on the backward one. It admits the row by default at {@code :1383}, then
 * excludes it unless the account identifier matches when the account filter is valid, at
 * {@code :1385-1390}, and unless the card number matches when the card filter is valid, at
 * {@code :1396-1401}. The two values it compares against are declared in
 * {@code app/cpy/CVCRD01Y.cpy}, where an eleven-character account identifier at {@code :34-35} is
 * overlaid by an eleven-digit numeric form at {@code :36}, and a sixteen-character card number at
 * {@code :37-38} is overlaid by a sixteen-digit numeric form at {@code :39}. Both are populated from
 * the list screen's two input fields, declared at {@code app/cpy-bms/COCRDLI.CPY:66} and
 * {@code :72}.</p>
 *
 * <p>Alternatives Considered: naming these two predicates in the method names instead, which is how
 * the equivalent browse repository in the transaction context expresses its own paths, and how
 * {@link #findByAccountIdOrderByCardNumAsc} below is written. It is not available for the two keyset
 * queries. A predicate derived from a method name is always applied, and there is no derived form
 * that means restrict on this argument only when the caller supplied one, so each filter would have
 * to be either always on or always off. Expressing both that way would take four methods per
 * direction, one for each combination of the two being present and absent, and all eight would have
 * to restate the cursor predicate -- which is the single part a divergence in would silently move a
 * page boundary, as the equivalence recorded above shows. One statement per direction states that
 * predicate once. The account-keyed method below carries no optional filter, which is why it is
 * derived from its name and needs no statement of its own.</p>
 *
 * <p>Assumptions: that overlay of a character declaration by a numeric one is also why the two
 * filters are typed as they are here. The card number is a fixed-width string because a leading zero
 * is significant in it and because sixteen significant digits exceed what a binary floating-point
 * number represents exactly, while the account identifier is a whole number of eleven digits and maps
 * to a sixty-four-bit integer. The entity beside this package settles both, and the migration named
 * below settles the columns they compare against.</p>
 *
 * <p>Trade-offs: expressing both filters as predicates means the database returns only the rows that
 * pass them, whereas the reference reads every row in key order and discards the rejected ones in the
 * paragraph cited above. That difference is a property of the two stores rather than a shortcoming of
 * either program: a browse over an indexed data set has no way to state a non-key restriction, so the
 * reference had no place to put the filter except after the read. The behaviour a caller observes is
 * the same set of rows in the same order. What is accepted in exchange is that a page whose every
 * read row was rejected cannot arise here, so the caller assembling a page never has to represent
 * that state -- while the reference, filtering after the read, can and does reach it.</p>
 *
 * <h2>A duplicate key is a normal outcome and never an error</h2>
 *
 * <p>Assumptions: nothing in this interface models a duplicate-key condition as a failure, and no
 * caller should catch one. The reference pairs the duplicate response with the normal response in
 * every one of the four places it inspects a browse result -- at {@code app/cbl/COCRDLIC.cbl:1157}
 * with {@code :1158}, at {@code :1208} with {@code :1209}, at {@code :1305} with {@code :1306}, and
 * at {@code :1333} with {@code :1334} -- and proceeds to filter and display the row in each case.
 * That response arises because the second access path is declared with a non-unique key, so it is an
 * expected outcome of reading through it rather than a fault. The queries here simply return the rows
 * they match, which is the same disposition expressed in a store that has no such response to
 * report.</p>
 *
 * <h2>Where the envelope is assembled, and why not here</h2>
 *
 * <p>Alternatives Considered: assembling the shared page envelope
 * {@code com.carddemo.common.web.PageResponse} in default methods on this interface, so that the
 * surplus-row trim and the boundary capture would have a single owner. It was evaluated and rejected,
 * and the reason is a hard constraint rather than a preference. That envelope's canonical constructor
 * requires each cursor component to be a token sealed by {@code com.carddemo.common.web.CursorToken}
 * and refuses anything else, which is enforced at
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java:342-343} by the
 * check at {@code :357-364}. Sealing needs key material and the subject the token is issued for, and
 * an interface has neither, so a default method here could only offer a raw key and every non-empty
 * page it built would be refused. The test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/web/PageResponseTest.java:263-271}
 * asserts exactly that refusal against a raw key, so the failure is a guarded contract and not a
 * theoretical one.</p>
 *
 * <p>Assumptions: the envelope is therefore assembled one layer up, in
 * {@code com.carddemo.card.service}, which is where the migration plan places the conversion of the
 * browse into a page and where the charter in {@code package-info.java} beside this file places the
 * transcribed rules. That layer holds the sealer and the caller's identity, so it can mint the
 * boundary tokens that this interface cannot. Every method here consequently returns
 * {@code List<Card>}, which is also the shape the equivalent browse repository in the transaction
 * context returns, at
 * {@code services/transaction-service/src/main/java/com/carddemo/transaction/repository/TransactionRepository.java}.
 * Reading rows here and sealing them there keeps this interface free of configuration entirely: it
 * needs no key, no lifetime and no request context to be exercised in a test.</p>
 *
 * <p>Refactoring Rationale: that envelope is imported from the shared kernel by the layer that builds
 * it and is never re-declared anywhere in this context. Each reference program hand-rolled its own
 * browse cursor in its own communication area with its own conventions, which is why the pair at
 * {@code app/cbl/COCRDLIC.cbl:229-235} looks nothing like the cursor any other screen keeps, and why
 * no single place could see every assignment to one. A single shared envelope is the direct analogue
 * of the discipline the reference already applies to its record layouts, which it resolves through one
 * compiler include path rather than copying -- stated at {@code tests/README.md:540-542} as never
 * duplicating a layout and keeping it single-sourced.</p>
 *
 * <h2>Page size is the caller's, and the reference's seven is not repeated here</h2>
 *
 * <p>Trade-offs: no method here names a page size. The reference fixes it at seven, at
 * {@code app/cbl/COCRDLIC.cbl:176-178}, corroborated by the comment reading
 * {@code 28 CHARS X 7 ROWS = 196} at {@code :250}, by the hundred-and-ninety-six-byte row array at
 * {@code :253} redefined as seven occurrences at {@code :255} over a twenty-eight-byte row at
 * {@code :258-260}, and independently by the symbolic map, whose row-numbered fields run from
 * {@code CRDSEL1I} at {@code app/cpy-bms/COCRDLI.CPY:78} to {@code CRDSEL7I} at {@code :252} with no
 * eighth row anywhere. Seven is the row capacity of a twenty-four-row by eighty-column terminal,
 * which makes it presentation geometry and not a property of the table, so pinning it in a data-access
 * interface would let a screen redesign reach into persistence. The target carries the same seven as
 * configuration instead, under {@code carddemo.card.list.page-size} in this module's
 * {@code application.yml}. The cost accepted is one more argument on each paging query; what is
 * bought is that this interface has no opinion about how many rows fit on a screen.</p>
 *
 * <h2>The second access path, and the three artifacts that no longer reach it</h2>
 *
 * <p>Assumptions: reaching an account's cards is an indexed path rather than a scan, and its index is
 * non-unique by declaration rather than by caution. The provenance is complete and is recorded on
 * {@link #findByAccountIdOrderByCardNumAsc} itself. What belongs here is the consequence: this
 * interface owns no other context's data. No account, customer or cross-reference type appears in it,
 * and that absence traces to the reference rather than to a target simplification. Both maintenance
 * programs include the customer layout without referencing a single field of it, at
 * {@code app/cbl/COCRDSLC.cbl:240} and {@code app/cbl/COCRDUPC.cbl:359}, and each additionally
 * carries the account and cross-reference layouts commented out, at {@code :231} and {@code :237} and
 * at {@code :350} and {@code :356}. An inert include confers no ownership.</p>
 *
 * <p>Assumptions: two further artifacts of the account-keyed path are present in the reference and
 * reach nothing, recorded here as observations so that a reader does not infer a live browse from
 * them. The literal naming the index path is declared at {@code app/cbl/COCRDLIC.cbl:215-217} and
 * appears at that one site only, whereas the literal naming the base cluster is used at twelve sites
 * in the same program. And {@code 9150-GETCARD-BYACCT} in {@code app/cbl/COCRDSLC.cbl}, defined at
 * {@code :779} with its exit at {@code :810}, reads that path but is reached by nothing:
 * {@code 9000-READ-DATA.} at {@code :726} performs the card-and-account paragraph unconditionally at
 * {@code :728-729}, and no other statement names it. This is why the account-keyed method below is
 * justified by the definition of the index rather than by any browse that executes.</p>
 *
 * <h2>Where the authority for each contract lives</h2>
 *
 * <p>Assumptions: the authoritative spelling of every column and of the index is the Flyway
 * migration at {@code services/card-service/src/main/resources/db/migration/V1__card.sql}, and not
 * this file. The reason to consult it is specific rather than procedural: a query naming a property
 * that resolves to a column the migration does not create is accepted by the compiler without
 * complaint and fails only when the statement first executes, so a disagreement between the two has
 * no compile-time signal at all. The provider is configured to emit no definitions and to validate
 * nothing in this module, so nothing else will report the divergence either. The property names in
 * the queries below are the entity's, and the entity mirrors that migration.</p>
 *
 * <p>Assumptions: no schema is named in any query here. It is pinned twice outside Java, by the
 * provider's default-schema setting and by the statement each pooled connection is opened with, both
 * in this module's {@code application.yml}. A query naming it as well would be a third declaration
 * that nothing compares against the other two.</p>
 *
 * <p>Assumptions: no recorded-output comparison against mainframe behaviour is available for the
 * paths this interface carries, so no claim of that kind should be made for them. The repository
 * records at {@code tests/README.md:83-85} that the online programs cannot be run end to end without
 * a CICS runtime, which is absent on the runner, and that only their extractable field-validation
 * logic is unit-tested. The one batch program on this file does run, and its read path is a
 * sequential open at {@code app/cbl/CBACT02C.cbl:118}, a read at {@code :93} inside the paragraph at
 * {@code :92} and a close at {@code :136}, which is a useful reference for record framing but
 * exercises neither the cursor nor the account-keyed path. Correctness for these two therefore rests
 * on this module's own tests.</p>
 *
 * <p>Assumptions: every path under {@code app/} cited anywhere in this file is reference material.
 * It is read as the specification, is never modified, and keeps running; the migration adds a path
 * beside it rather than removing one.</p>
 */
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Reads the rows that follow a cursor position, in ascending card-number order.
     *
     * <p>This is the forward half of the browse, standing in for the paragraph
     * {@code 9000-READ-FORWARD.} at {@code app/cbl/COCRDLIC.cbl:1123} and for the reads it issues at
     * {@code :1146} and {@code :1197}. Both list filters are applied by this same query, and the row
     * bound is the caller's.</p>
     *
     * @param afterCardNum the sixteen-character card number to resume strictly after, which is the
     *     last card number the previous page actually returned, or {@code null} to read from the
     *     beginning of the ordered set
     * @param accountId the eleven-digit account identifier to restrict the result to, or {@code null}
     *     to apply no account restriction
     * @param cardNum the sixteen-character card number to restrict the result to, or {@code null} to
     *     apply no card-number restriction
     * @param limit the bound on how many rows are read, which a caller assembling a page sets to one
     *     more than the page holds so that the surplus row settles whether a further page exists
     * @return the matching rows in ascending card-number order, at most as many as {@code limit}
     *     admits, and empty when the cursor already stands at the end of the ordered set
     */
    // WHY : Assumptions: the cursor parameter is nullable here and mandatory on the backward query,
    //       and the asymmetry is deliberate rather than an oversight. An absent cursor means the
    //       first page, which is the state the reference is in when it positions from a
    //       communication area it has just initialised -- at app/cbl/COCRDLIC.cbl:462-463 the area is
    //       initialised before the forward read at :478. There is no corresponding backward state: a
    //       backward step is only expressible from a page that was already returned, so a null there
    //       would describe a request that cannot arise.
    // WHY : Assumptions: the comparison is strict rather than inclusive, which is what makes this
    //       query the equivalent of the reference's greater-than-or-equal-to positioning followed by
    //       a read. The reference stores the first undisplayed row and seeks to it inclusively; this
    //       resumes after the last returned row exclusively. The equivalence, and the consequence
    //       that a caller must not publish the surplus row's key as a boundary, are set out in the
    //       interface documentation above.
    // WHY : Trade-offs: both filters are guarded by a null test in the one statement rather than
    //       split across four methods for the four combinations of them. Four methods would let the
    //       provider specialise each statement, at the cost of four places for the cursor predicate
    //       to be maintained and drift; one statement keeps the cursor predicate single-sourced,
    //       which is the part a mistake in would silently change page boundaries. Each parameter is
    //       used in an equality or ordering comparison in the same statement that tests it for null,
    //       so its type is inferable and no cast is needed.
    @Query("""
            select c from Card c
            where (:afterCardNum is null or c.cardNum > :afterCardNum)
              and (:accountId is null or c.accountId = :accountId)
              and (:cardNum is null or c.cardNum = :cardNum)
            order by c.cardNum asc
            """)
    List<Card> findForwardFromCursor(
            @Param("afterCardNum") String afterCardNum,
            @Param("accountId") Long accountId,
            @Param("cardNum") String cardNum,
            Limit limit);

    /**
     * Reads the rows that precede a cursor position, returned in descending card-number order.
     *
     * <p>This is the backward half of the browse, standing in for the paragraph
     * {@code 9100-READ-BACKWARDS.} at {@code app/cbl/COCRDLIC.cbl:1264} and for the reads it issues
     * at {@code :1294} and {@code :1322}. The rows come back in the order the query reads them, which
     * is the reverse of display order; the caller reverses them, for the reason recorded below.</p>
     *
     * @param beforeCardNum the sixteen-character card number to read strictly before, which is the
     *     first card number the current page returned and which must be present, since a backward
     *     step is only expressible from a page already returned
     * @param accountId the eleven-digit account identifier to restrict the result to, or {@code null}
     *     to apply no account restriction
     * @param cardNum the sixteen-character card number to restrict the result to, or {@code null} to
     *     apply no card-number restriction
     * @param limit the bound on how many rows are read, which a caller assembling a page sets to one
     *     more than the page holds so that the surplus row settles whether a further page precedes
     *     this one
     * @return the matching rows in DESCENDING card-number order, at most as many as {@code limit}
     *     admits, and empty when the cursor already stands at the beginning of the ordered set
     */
    // WHY : Trade-offs: the rows are returned in the order the query read them, descending, and the
    //       caller reverses them before presenting a page. Reversing inside the query is not
    //       available: the bound has to apply to the rows nearest the cursor, so the ordering that
    //       selects them is necessarily the descending one, and any ascending ordering would bound
    //       the wrong end of the table. This mirrors the reference, which fills its display array
    //       from the bottom upward for exactly the same reason -- it sets its row counter one past
    //       the screen limit at app/cbl/COCRDLIC.cbl:1284-1286 and decrements it at :1307 and :1346
    //       as each row arrives, so the row it read first lands in the last display position. What
    //       is accepted is that this one method's result is not in display order; what is bought is
    //       that the page adjacent to the cursor is the page returned.
    // WHY : Assumptions: the row the reference reads at :1294 before entering its loop is read and
    //       discarded, which is why its counter is decremented at :1307 without a row being stored.
    //       That read exists only to step the browse off the boundary row it positioned onto, an
    //       adjustment a strict comparison makes unnecessary here, so this query has no counterpart
    //       to it and its first row is a row the caller keeps.
    @Query("""
            select c from Card c
            where c.cardNum < :beforeCardNum
              and (:accountId is null or c.accountId = :accountId)
              and (:cardNum is null or c.cardNum = :cardNum)
            order by c.cardNum desc
            """)
    List<Card> findBackwardFromCursor(
            @Param("beforeCardNum") String beforeCardNum,
            @Param("accountId") Long accountId,
            @Param("cardNum") String cardNum,
            Limit limit);

    /**
     * Reads every card on one account, in ascending card-number order.
     *
     * <p>This is the target form of the reference's second access path. That path is an alternate
     * index defined at {@code app/jcl/CARDFILE.jcl:83}, related to the same base cluster at
     * {@code :84}, keyed at {@code :85} on the eleven bytes at position sixteen of the record -- which
     * is the account identifier -- and declared with a non-unique key at {@code :86} and as upgraded
     * at {@code :87}. A path over it is defined at {@code :100-102}, and that path is the object the
     * online region is given as a file of its own, at {@code app/csd/CARDDEMO.CSD:13-14}, enabled for
     * browse, read, update and delete at {@code :19}. Its target form is the secondary index the
     * migration creates.</p>
     *
     * @param accountId the eleven-digit account identifier whose cards are wanted
     * @return every card on that account in ascending card-number order, and an empty list when the
     *     account holds none, which is a legitimate cardinality rather than an error
     */
    // WHY : Assumptions: the index this reads through is non-unique, and that is the declared
    //       contract rather than a cautious default: app/jcl/CARDFILE.jcl:86 declares the alternate
    //       index with a non-unique key, so one account may hold many cards. A unique index would
    //       refuse the second card on an account, which the reference admits by design. This is also
    //       why the return is a list and why an account with no cards yields an empty one -- zero,
    //       one and many are all legitimate here, and the fixture set carries an account with no
    //       records at all in order to hold the zero case.
    // WHY : Assumptions: the justification for this method is the definition of that index and not
    //       any browse that runs, because every artifact of the account-keyed path in the online
    //       programs is unreachable, as the interface documentation records. The index-building step
    //       at app/jcl/CARDFILE.jcl:110-112 has no target counterpart either: the migration plan
    //       retires it, because the target store maintains an index as part of the transaction that
    //       changes a row rather than as a separate job step.
    // WHY : Alternatives Considered: accepting a row bound on this method as well, as the two keyset
    //       queries do. Declined because the cardinality here is bounded by how many cards an account
    //       holds rather than by how many rows fit a screen, and no reference path pages through it --
    //       the paragraph that would have done so is the unreachable one. Adding a bound would invite
    //       a caller to page a result that has no cursor contract defined for it.
    // WHY : Assumptions: the ordering is stated in the method name rather than left to the index, so
    //       that the result is deterministic. The index orders by account identifier alone, so two
    //       cards on one account have no defined order under it; ordering by card number gives the
    //       same sequence the base cluster is keyed in and that the browse above returns.
    List<Card> findByAccountIdOrderByCardNumAsc(Long accountId);
}
