package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes individual pending authorizations, and pages them by key.
 *
 * <p>This is the migrated form of the CHILD segment {@code PAUTDTL1}, declared at 200 bytes in
 * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} L36 with its fields laid out at
 * {@code cpy/CIPAUDTY.cpy} L19 to L54. Every citation below is relative to
 * {@code app/app-authorization-ims-db2-mq} unless another root is named, because that tree is read
 * as this interface's specification and is never modified. The package charter in
 * {@code package-info.java} carries the rulings that apply to all four interfaces here -- the
 * absence of a transaction boundary, the absence of a lock mode, the unqualified table names, the
 * exclusion of masking and the prohibition on binary floating-point in the money path -- and this
 * file relies on them rather than restating them.</p>
 *
 * <p>Three access paths reach this table, and they are the three the reference programs use. One
 * authorization is read by its whole composite key, for the detail screen and for the fraud
 * marking that re-reads before it writes. A page of an account's authorizations is read newest
 * first, for the summary screen's five-row list. Every authorization beneath one account is read in
 * one go, for the expiry sweep and the extract, both of which visit children row by row.</p>
 *
 * <h2>The page shape</h2>
 *
 * <p>Assumptions: every paging method returns ONE row more than the caller's page size, and the
 * extra row is included in the returned list and is deliberately not removed here. The reference
 * program establishes the same fact the same way. {@code cbl/COPAUS0C.cbl} fills its five-row
 * screen array under an index bounded at five at L424, storing each displayed row's key into
 * {@code CDEMO-CPVS-PAUKEY-LAST} INSIDE that loop at L434 and L435; then, AFTER the loop has
 * closed, it issues one further retrieval at L446 for no purpose other than to discover whether a
 * further row exists, setting {@code NEXT-PAGE-YES} at L448 when one did and {@code NEXT-PAGE-NO}
 * at L450 when none did. That sixth row's key is never stored -- there is no move to
 * {@code CDEMO-CPVS-PAUKEY-LAST} after L446 -- so the page's closing key remains the key of the
 * last row actually DISPLAYED. A limit of page size plus one, with the page's closing cursor taken
 * from the last row RETURNED rather than the last row read, is therefore a one-for-one structural
 * equivalent and not an approximation of one. The flag those two settings drive,
 * {@code CDEMO-CPVS-NEXT-PAGE-FLG} at L123 with its unprefixed condition names at L124 and L125,
 * is what a caller reports as its has-next state.</p>
 *
 * <p>Assumptions: the discarded row costs the reference program something it does not cost a
 * predicate here, and the difference explains a paragraph that would otherwise look redundant. That
 * sixth retrieval ADVANCES the hierarchical position, so the reference program cannot simply
 * continue from where it stands and must re-seek from the key it saved --
 * {@code PROCESS-PF8-KEY} at L388 to L412 restores that key at L394 and re-seeks through
 * {@code REPOSITION-AUTHORIZATIONS} at L397, or begins from {@code LOW-VALUES} at L392 when the
 * saved key is still blank. A SQL predicate consumes no position, so seeking from the key is
 * inherent to every call here rather than a compensation for the probe.</p>
 *
 * <p>Assumptions: the page size is a method PARAMETER on this boundary and not a constant declared
 * in it, even though this screen's size is held at five in three separate places --
 * {@code CDEMO-CPVS-AUTH-KEYS PIC X(08) OCCURS 5 TIMES} at L126, the fill loop's bound at L424 and
 * the clearing loop's bound at L611. It is a parameter because the size belongs to the screen and
 * the sizes differ across the reference screens, so a constant here would be one screen's number
 * sitting in a place every screen reaches through.</p>
 *
 * <p>Alternatives Considered: returning the assembled page envelope
 * {@code com.carddemo.common.web.PageResponse} from these methods, so the extra row never leaves
 * this package. Rejected for two concrete reasons. The shared kernel that declares that envelope
 * depends on neither Spring Data JPA nor a JDBC driver, so making it a query return type would
 * either put persistence on the kernel's classpath or copy the envelope into each context, and it
 * is one shared type precisely so that nine modules cannot each grow their own. Separately, a
 * derived or native query materialises entities and projections, not an arbitrary carrier holding a
 * computed flag and two encoded cursor tokens, so the assembly has to run after the query in either
 * design. The service layer therefore discards the extra row, derives has-next from whether it
 * arrived, encodes the two cursors and builds the envelope; a caller that assumed a list of exactly
 * the page size would silently drop a row.</p>
 *
 * <h2>The ordering, and the direction it forces</h2>
 *
 * <p>Refactoring Rationale: the reference key is a NINES COMPLEMENT, and dropping the complement is
 * what makes the ordering here explicit rather than incidental. The consumer builds the child key by
 * subtracting the real values from all-nines constants -- {@code cbl/COPAUA0C.cbl} L874 computes
 * {@code PA-AUTH-DATE-9C} as {@code 99999} less the Julian date and L875 computes
 * {@code PA-AUTH-TIME-9C} as {@code 999999999} less the millisecond time. The hierarchical
 * retrieval walks twins in ASCENDING key order over the sequence field
 * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C} at {@code ims/DBPAUTP0.dbd} L37, and a
 * plain character sequence field offers no descending option at all, so the complement is the only
 * way that structure can present an account's authorizations newest first -- ascending order over a
 * complemented value IS descending order over the value it complements. The rejected alternative was
 * to persist the complemented values here too, preserving ascending traversal unchanged. Its
 * concrete cost is that no column would then hold a date: the reference system has to undo the
 * complement the moment it needs date arithmetic, which is exactly what {@code cbl/CBPAUP0C.cbl}
 * L280 does when it computes {@code WS-AUTH-DATE} as {@code 99999} less
 * {@code PA-AUTH-DATE-9C} -- the exact inverse of L874 -- before it can subtract one date from
 * another. Storing decoded values makes the age comparison a comparison and pays for it by having to
 * state the direction, which the next paragraph does.</p>
 *
 * <p>Assumptions: because the stored values are decoded, newest first is
 * {@code order by auth_date desc, auth_time desc} and THE FORWARD-PAGE PREDICATE IS STRICTLY LESS
 * THAN. This is stated separately and prominently because the intuitive keyset shape is the wrong
 * one here: a predicate of the form {@code (auth_date, auth_time) > cursor} ordered ascending is
 * what a reader who has not followed the complement would write, and it returns the OLDEST rows and
 * walks progressively FURTHER BACK through history on every page. It compiles, it runs, it returns
 * real rows in a plausible order, and nothing in the schema contradicts it. No recorded output
 * exists for any path in this module against which such a page could be checked, so an integration
 * test that seeds an account and asserts both that the first page is newest first AND that paging
 * forward reaches progressively OLDER rows is the only thing that distinguishes the two forms.</p>
 *
 * <p>Assumptions: that ordering is answered by the table's own PRIMARY KEY and no secondary index is
 * added for it. {@code V1__authorization.sql} L561 declares
 * {@code pk_pending_auth_detail PRIMARY KEY (account_id, auth_date, auth_time)}, whose supporting
 * B-tree carries the three key columns in exactly the order every query here restricts and orders
 * by, and a B-tree is walked in either direction, so a descending order over a leading-equality
 * account is a reverse scan of that same index. The alternative was a secondary index over the same
 * three columns in descending order; its concrete consequence is a second tree maintained on every
 * insert and delete that answers nothing the primary key does not already answer. The package
 * charter records that the only directional index this package queries through is the one on the
 * fraud table, and this paragraph is consistent with it rather than an exception to it.</p>
 *
 * <h2>Cursors, empty pages and boundaries</h2>
 *
 * <p>Assumptions: the cursor a caller carries is opaque, and the reference key is what makes that
 * possible without loss. {@code PA-AUTHORIZATION-KEY} at {@code cpy/CIPAUDTY.cpy} L19 is a group of
 * exactly two packed items, {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} at L20 in three bytes and
 * {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} at L21 in five, totalling the eight bytes
 * {@code ims/DBPAUTP0.dbd} L37 declares -- a position that was already a single opaque token rather
 * than a pair of readable numbers. The two decoded components travel as separate arguments to the
 * methods below and are recomposed into one token above them, so the envelope's cursors stay opaque
 * strings that may be absent. Absence is how a boundary is expressed there; the reference program
 * expresses the same boundary with sentinel content instead, testing
 * {@code IF CDEMO-CPVS-PAUKEY-LAST = SPACES OR LOW-VALUES} at L391 and seeding the key with
 * {@code LOW-VALUES} at L392 and again at L422 to mean "start at the beginning". Those are the two
 * sentinels this program uses; a high-value sentinel appears in a different program, at
 * {@code app/cbl/COBIL00C.cbl} L212, and is named here only so it is not attributed to this
 * one.</p>
 *
 * <p>Assumptions: an exhausted page is a NORMAL terminal outcome and never an error, so every
 * method below returns an empty list or an empty optional rather than raising anything. The
 * reference program reaches that conclusion through a COBOL fall-through: in the status evaluation
 * at L467 to L484, {@code WHEN SEGMENT-NOT-FOUND} at L470 and {@code WHEN END-OF-DB} at L471 share
 * one consequent at L472, so the code meaning "no such segment" and the code meaning "end of
 * database" are treated identically as end of page. The same collapse is written independently at
 * {@code REPOSITION-AUTHORIZATIONS} L503 to L505 and again in another program at
 * {@code cbl/CBPAUP0C.cbl} L263 to L265, so it is the module's settled reading rather than one
 * paragraph's shorthand. Only the residual {@code WHEN OTHER} arm is an error there, and its
 * analogue here is the data-access exception the framework raises, which is why no method declares
 * one of its own.</p>
 */
public interface PendingAuthDetailRepository
        extends JpaRepository<PendingAuthDetail, PendingAuthDetailKey> {

    /**
     * Reads one authorization by its whole three-part key.
     *
     * <p>Assumptions: this read serves TWO callers, and naming it here rather than leaving it
     * inherited is what lets the second one keep its narrow request contract. The detail screen
     * reaches it to render one authorization, and the fraud marking reaches it to re-read the row
     * before it applies a fraud state. Because the row is re-read from the key, the fraud request
     * body carries only the three key components and not the 200-byte segment the reference
     * program passes in its communication area, so a client cannot dictate what is persisted -- it
     * can only name which row is acted on. Removing this read would leave the marking path with
     * nothing to re-read from and would push the segment back into the request.</p>
     *
     * <p>Assumptions: the fraud state is then applied by mutating this managed row and letting the
     * surrounding transaction write it, which is the whole-record analogue the reference program
     * uses. {@code cbl/COPAUS1C.cbl} moves a complete prepared record over the segment area at L522
     * and replaces the segment at L525 to L528, and that replace names the segment with no field
     * list, so all 200 bytes are rewritten. The inherited save of a re-read entity carries the same
     * meaning. This is deliberately NOT modelled on the narrower write the fraud table takes, which
     * touches two columns and belongs to {@code AuthFraudRepository} alone: one user action drives
     * two different write shapes, and applying the narrow one here would rewrite a different
     * contract.</p>
     *
     * <p>Assumptions: no lock mode is declared on this read, and the reference programs are the
     * reason rather than an omission. {@code cpy/IMSFUNCS.cpy} declares all three get-hold
     * retrieval codes, {@code FUNC-GHU} at L19, {@code FUNC-GHN} at L21 and {@code FUNC-GHNP} at
     * L23, and no program in the reference tree passes any of them to a retrieval: the codes used
     * throughout are the non-hold forms, so no occurrence is ever held between a read and the write
     * that follows it. Where two concurrent markings of one authorization could otherwise both
     * observe the same absent fraud row, it is the fraud table's own primary key that settles it --
     * which is how the reference system settles it too, taking its update branch on the duplicate-key
     * code at {@code cbl/COPAUS2C.cbl} L203 and L204 after the insert at L199 rather than preventing
     * the duplicate in advance.</p>
     *
     * @param id the composite key of account identifier, decoded Julian authorization date and
     *     decoded millisecond authorization time; must not be {@code null}
     * @return the authorization that key names, or an empty optional when the key names no row,
     *     which is a normal outcome and not an error
     */
    Optional<PendingAuthDetail> findById(PendingAuthDetailKey id);

    /**
     * Reads the opening page of one account's authorizations, newest first.
     *
     * <p>Assumptions: this is the no-cursor case, and it carries no comparison at all rather than a
     * comparison against a lowest-possible key. The reference program reaches the same state by
     * seeding its saved key with {@code LOW-VALUES} at {@code cbl/COPAUS0C.cbl} L422 before the
     * first retrieval, which makes the first read unrestricted; omitting the predicate expresses
     * that directly, and it also avoids inventing a sentinel value for a key whose columns are both
     * declared not null.</p>
     *
     * @param accountId the account whose authorizations are wanted; must not be {@code null}
     * @param limit the greatest number of rows to return, which a caller sets to its page size plus
     *     one so the extra row reports whether a further page exists; must not be {@code null}
     * @return up to {@code limit} authorizations ordered newest first, the last of which may be the
     *     look-ahead row, and an empty list when the account has none
     */
    List<PendingAuthDetail> findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
            Long accountId, Limit limit);

    /**
     * Reads the page of one account's authorizations that follows a stated position.
     *
     * <p>Assumptions: the comparison is STRICTLY LESS THAN and the order is descending, because the
     * stored date and time are decoded values and the list runs newest first. Reversing either half
     * of that -- a greater-than comparison, or an ascending order -- yields a query that returns
     * real rows in a plausible order while paging in the wrong direction through history, and no
     * schema constraint and no recorded output would contradict it.</p>
     *
     * <p>Assumptions: the two components are compared as a PAIR and not independently. A row
     * qualifies when its date is earlier, or when its date is equal and its time is earlier;
     * comparing the columns independently would either drop every remaining row that shares the
     * boundary date or return rows the caller has already been shown. The predicate is spelled out
     * here rather than derived from a method name because a derived name cannot express a composite
     * boundary at all.</p>
     *
     * <p>Alternatives Considered: locating the page by counting rows from the start of the ordering,
     * which is what a row-offset clause or the framework's own paging abstraction would do. Rejected
     * because a count from the start is only stable while nothing is inserted or removed ahead of the
     * position, and this table is written continuously by the authorization consumer and thinned by
     * the expiry sweep. An insert landing ahead of the boundary between two requests shifts every
     * later row by one place, so the reader is either shown a row it has already seen or never shown
     * one at all -- a row silently passed over, or a row silently repeated, neither of which browsing
     * by key can do because it resumes from a value rather than from a count. That concurrency is real
     * in this system and not hypothetical: {@code app/cbl/COBIL00C.cbl} generates its next identifier
     * with an unguarded read of the highest existing one, seeding the key at L212, browsing at L213,
     * reading backwards at L214, ending the browse at L215 and then moving that value at L216 and
     * adding one to it at L217, with nothing between the read and the increment to stop two concurrent
     * payments reading the same maximum.</p>
     *
     * @param accountId the account whose authorizations are wanted; must not be {@code null}
     * @param authDate the decoded Julian authorization date of the last row already shown; must not
     *     be {@code null}
     * @param authTime the decoded millisecond authorization time of the last row already shown; must
     *     not be {@code null}
     * @param limit the greatest number of rows to return, which a caller sets to its page size plus
     *     one so the extra row reports whether a further page exists; must not be {@code null}
     * @return up to {@code limit} older authorizations ordered newest first, the last of which may be
     *     the look-ahead row, and an empty list when the stated position is already the oldest
     */
    @Query("""
            select d from PendingAuthDetail d
             where d.id.accountId = :accountId
               and (d.id.authDate < :authDate
                    or (d.id.authDate = :authDate and d.id.authTime < :authTime))
             order by d.id.authDate desc, d.id.authTime desc
            """)
    List<PendingAuthDetail> findOlderThan(@Param("accountId") Long accountId,
            @Param("authDate") Integer authDate, @Param("authTime") Integer authTime, Limit limit);

    /**
     * Reads the authorizations immediately preceding a stated position, oldest of them first.
     *
     * <p>Refactoring Rationale: the reference program has no backward retrieval of any kind, and
     * stating its actual mechanism matters because the obvious description of this method is not a
     * description of it. {@code PROCESS-PF7-KEY} at {@code cbl/COPAUS0C.cbl} L362 to L385 checks it
     * is not already on the first page at L365, decrements its page counter at L366, restores the key
     * it remembered for that page from {@code CDEMO-CPVS-PAUKEY-PREV-PG} at L368 and L369, and then
     * performs {@code PROCESS-PAGE-FORWARD} at L379 -- the same forward path a next-page action runs.
     * There is no descending scan and no read-previous verb anywhere in the program: a previous page
     * is reproduced by moving FORWARD from a remembered key. What the reference holds to make that
     * work is a server-side history, {@code CDEMO-CPVS-PAUKEY-PREV-PG PIC X(08) OCCURS 20 TIMES} at
     * L120, which bounds it to twenty pages. Under the stateless model that history belongs to the
     * client, which already carries the cursor of the page it is on, so two of the reference fields
     * have no target equivalent and are dropped rather than ported: the page counter
     * {@code CDEMO-CPVS-PAGE-NUM} at L122 and the last-page-displayed flag. The has-next flag plus a
     * client-held cursor carry the same information without either.</p>
     *
     * <p>Trade-offs: this method exists so that a client holding only the cursor of the page it is
     * currently showing can still step back one page, which the remembered-key mechanism above would
     * otherwise require it to hold a full history to do. Its ascending order is a consequence of that
     * and not a claim about the reference: the rows wanted are the ones nearest the boundary, and only
     * an ascending order puts those within reach of the limit, so the caller reverses the result before
     * rendering and both directions present identically. The compromise accepted is that a client that
     * does keep its own key history need not use this at all and can re-issue the forward read from the
     * earlier key, exactly as the reference does.</p>
     *
     * @param accountId the account whose authorizations are wanted; must not be {@code null}
     * @param authDate the decoded Julian authorization date of the first row currently shown; must not
     *     be {@code null}
     * @param authTime the decoded millisecond authorization time of the first row currently shown;
     *     must not be {@code null}
     * @param limit the greatest number of rows to return, which a caller sets to its page size plus
     *     one; must not be {@code null}
     * @return up to {@code limit} newer authorizations ordered OLDEST first, which the caller reverses
     *     before rendering, and an empty list when the stated position is already the newest
     */
    @Query("""
            select d from PendingAuthDetail d
             where d.id.accountId = :accountId
               and (d.id.authDate > :authDate
                    or (d.id.authDate = :authDate and d.id.authTime > :authTime))
             order by d.id.authDate asc, d.id.authTime asc
            """)
    List<PendingAuthDetail> findNewerThan(@Param("accountId") Long accountId,
            @Param("authDate") Integer authDate, @Param("authTime") Integer authTime, Limit limit);

    /**
     * Finds the authorization already recorded for one card and one acquirer transaction identifier.
     *
     * <p>Assumptions: this is how a REDELIVERED request is recognised as one already decided. A queue
     * suppresses duplicates only inside its own deduplication window, so a redelivery arriving after
     * that window reaches the consumer as an ordinary new message, and without this read the same
     * authorization would be decided and recorded twice.</p>
     *
     * <p>Assumptions: the lookup is by the PAIR of card number and transaction identifier, and the
     * schema is what guarantees the single result rather than this signature asserting it. The
     * identifier is an acquirer-supplied fifteen-character value, so two acquirers may legitimately
     * issue the same one and a lookup by identifier alone could return one card's decision to another
     * card's requester. {@code V1__authorization.sql} L609 and L610 declare
     * {@code uq_pending_auth_detail_card_transaction UNIQUE (card_num, transaction_id)}, whose
     * supporting index makes this one seek and whose uniqueness is what makes a single result
     * well-defined.</p>
     *
     * @param cardNum the sixteen-character primary account number the request carried; must not be
     *     {@code null}
     * @param transactionId the acquirer's transaction identifier from that request; must not be
     *     {@code null}
     * @return the authorization already recorded for that pair, or an empty optional when the request
     *     has not been decided before
     */
    Optional<PendingAuthDetail> findByCardNumAndTransactionId(String cardNum, String transactionId);

    /**
     * Reads every authorization beneath one account, newest first.
     *
     * <p>Assumptions: this is the unpaged child walk, and both callers need every child before they
     * can conclude anything about the parent. The expiry sweep visits each child to decide whether it
     * has aged out, and the extract writes each child in sequence; the reference programs express both
     * as a retrieval repeated until the parent's children are exhausted, with no page boundary
     * anywhere in the loop.</p>
     *
     * <p>Alternatives Considered: a single set-based delete of the aged rows behind one modifying
     * query, which would remove the whole child set of an account in one statement. Rejected because
     * the reference sweep does per-row arithmetic on the PARENT while it walks, and a set-based delete
     * would perform none of it. {@code cbl/CBPAUP0C.cbl} L287 to L292 branches on the child's response
     * code: for an approved authorization it subtracts one from the parent's approved count and the
     * child's approved amount from the parent's approved total, and otherwise it subtracts one from the
     * declined count and the child's transaction amount from the declined total. Those are four
     * different subtractions selected per row from a value only that row carries, so a statement that
     * deleted the rows in bulk would leave the parent's four running counters holding totals for
     * children that no longer exist, with nothing in the schema able to detect the drift. Row-by-row
     * visitation is what keeps the parent's arithmetic attached to the row that drives it.</p>
     *
     * <p>Assumptions: the predicate that selects a row for removal is age ALONE and is INCLUSIVE, and
     * it belongs to the caller rather than to this read. {@code cbl/CBPAUP0C.cbl} L284 qualifies a row
     * when the day difference is greater than OR EQUAL TO the expiry threshold, so a row exactly at the
     * threshold is removed. Nothing else narrows it: that program tests no match status anywhere, so
     * neither this read nor its caller may add such a condition.</p>
     *
     * <p>Assumptions: removal proceeds CHILD BEFORE PARENT, and the order is part of the contract even
     * though the sequencing itself lives in the caller. {@code cbl/CBPAUP0C.cbl} deletes the child at
     * paragraph {@code 5000-DELETE-AUTH-DTL} at L303, whose delete of {@code PAUTDTL1} is at L310 to
     * L313, and only afterwards deletes the root at {@code 6000-DELETE-AUTH-SUMMARY} at L328, whose
     * delete of {@code PAUTSUM0} is at L335 to L338. The migrated schema states the same dependency as
     * a foreign key from this table to the summary, so reversing the order would be refused by the
     * database rather than merely diverging from the reference.</p>
     *
     * @param accountId the account whose authorizations are wanted; must not be {@code null}
     * @return every authorization beneath that account ordered newest first, and an empty list when it
     *     has none
     */
    List<PendingAuthDetail> findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(Long accountId);
}
