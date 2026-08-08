package com.carddemo.account.repository;

import com.carddemo.account.domain.Account;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads the account master rows this bounded context owns, by key and in key order.
 *
 * <p><b>Purpose.</b> This interface is the migrated form of every path by which the reference system
 * reaches {@code ACCTDAT}, and two different kinds of path arrive here. The online screens read one
 * account by its identifier; the batch reader walks the whole file in key order. The first is the
 * inherited key lookup and is not redeclared, and the second is the ordered scan declared below. The
 * file carries no alternate index of its own, so there is no third path to express.</p>
 *
 * <p><b>Parameters, return values, exceptions or errors at the type level.</b> An interface
 * declaration takes no parameter, returns no value and raises nothing, so no at-clause of those kinds
 * appears on this block and each method below carries its own. The inapplicability is stated rather
 * than left silent, because the Explainability rule lists a docstring that omits parameters or return
 * values among its forbidden patterns at L39, and a reader has to be able to tell a declared
 * inapplicability from an oversight. That rule's gate at L43 is conjunctive -- a member missing either
 * its docstring or the reason behind a non-obvious choice fails review -- which is why every method
 * below carries both, and why each rationale is labelled with one of the four categories the rule
 * enumerates at L31 through L34 rather than left as unlabelled prose.</p>
 *
 * <p>The rulings that {@code package-info.java} beside this file states once for the whole package
 * govern every member here and are cited rather than re-argued: reads are by key and in key order
 * with no positional paging, the page envelope is assembled above this package, concurrency is
 * optimistic through the entity's version column, and an alternate index becomes a real secondary
 * index. What follows records only the decisions that are this interface's own.</p>
 *
 * <h2>Provenance: three programs reach this file, and a fourth is cited only for cursor shape</h2>
 *
 * <p>Assumptions: the keyed read is the inherited {@code findById} and is deliberately not redeclared
 * here, because the key it reads on is the entity's primary key and nothing else.
 * {@code app/cbl/COACTVWC.cbl} performs that read in {@code 9300-GETACCTDATA-BYACCT.} at L774, whose
 * body at L776 through L781 enters {@code DATASET (LIT-ACCTFILENAME)} on a record identification
 * field and receives the record {@code INTO (ACCOUNT-RECORD)}, and whose exit paragraph is at L821;
 * the dataset literal it names is declared at L184 and L185 as {@code PIC X(8)} holding
 * {@code 'ACCTDAT '}. {@code app/cbl/COACTUPC.cbl} declares the same paragraph at L3701. A
 * {@code findByAccountId} twin of the inherited method would add a second name for one access path,
 * and two names for one path is the condition under which a caller eventually reaches for the wrong
 * one.</p>
 *
 * <p>Assumptions: the keyed read plus one ordered scan is the whole access surface, and the scan half
 * comes from the batch reader rather than from any screen. {@code app/cbl/CBACT01C.cbl} declares
 * {@code ACCESS MODE IS SEQUENTIAL} at L31 and {@code RECORD KEY IS FD-ACCT-ID} at L32, and it
 * contains no keyed random read at all: it drives an open, get-next, close triad, entering
 * {@code 0000-ACCTFILE-OPEN.} at L317, reading in {@code 1000-ACCTFILE-GET-NEXT.} at L165 whose body
 * at L166 is {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD.}, and closing at
 * {@code 9000-ACCTFILE-CLOSE.} at L388. Its two status conditions are the all-clear and the
 * end-of-file, which is the whole of its control flow over the file. That triad is what the three
 * ordered queries below stand in for, and the same shape recurs in the other two batch readers of
 * this context, each citing its own triad on its own interface.</p>
 *
 * <p>Assumptions: the key is the record's leading field, which two independent declarations state.
 * {@code app/cpy/CVACT01Y.cpy} announces a 300-byte record in its L2 header and declares
 * {@code ACCT-ID PIC 9(11)} at L5 as the first field of {@code 01 ACCOUNT-RECORD.} at L4, while
 * {@code app/cbl/CBACT01C.cbl} splits the same record into {@code FD-ACCT-ID PIC 9(11)} at L54 and
 * {@code FD-ACCT-DATA PIC X(289)} at L55, and 11 plus 289 is the declared 300. Eleven decimal digits
 * is what makes the key a 64-bit integer here rather than a character field.</p>
 *
 * <p>Assumptions: {@code app/cbl/COCRDLIC.cbl} is cited throughout this file for the SHAPE of a
 * browse cursor and for nothing else. It browses a different file, and no row count of its is
 * imported here. This context has no browse screen at all -- {@code COACTVWC} and {@code COACTUPC}
 * are single-record screens -- so the ordered scan below answers the batch triad, which has no screen
 * to be bounded by.</p>
 *
 * <h2>Ordered scans resume from a key, never from a counted distance</h2>
 *
 * <p>Alternatives Considered: positional paging, meaning asking the database to skip a counted number
 * of rows and return the batch after them. It is rejected on observable behaviour rather than on
 * taste. The count of rows preceding a position is evaluated against whatever the table holds at the
 * moment the second query runs, so under concurrent insertion a query positioned that way omits some
 * rows and returns others twice; a scan that resumes from a key it has already returned cannot do
 * either, because that key names a row rather than a distance. The reference never counted rows to
 * find its place either, and its browse state is ALREADY a cursor over keys, which is what makes this
 * a one-to-one carry-over rather than an approximation of something looser.
 * {@code app/cbl/COCRDLIC.cbl} declares {@code 01 WS-THIS-PROGCOMMAREA.} at L229 and persists across
 * the terminal turn, at L230 through L244, a trailing key pair at L230 through L232, a leading key
 * pair at L233 through L235, a screen ordinal at L237 and L238, a last-screen-displayed flag at L239
 * through L241 and a further-rows-exist indicator at L242 through L244, alongside its row counter at
 * L145. Every one of those is a key or a flag, and not one is an ordinal into a result set.</p>
 *
 * <p>Refactoring Rationale: the reference's four browse verbs collapse into the one query pair below,
 * ascending to read forward and descending to read backward. What was wrong with the four-verb
 * arrangement is not the verbs but where the position lived: it lived in a communication area the
 * client echoed back, declared at L229 of {@code app/cbl/COCRDLIC.cbl}, so there was no server-side
 * file position for a start verb to open or an end verb to close. Once the position travels in the
 * request there is no browse handle to manage, so a start and an end have nothing left to do, and the
 * descending query is the exact counterpart of the backward read rather than a re-scan from the top.
 * This is the same treatment the migration applies to the other browse paths, so the four verbs have
 * one replacement shape across the whole target rather than one per screen.</p>
 *
 * <h2>Where the page envelope is assembled, and why it cannot be assembled here</h2>
 *
 * <p>Alternatives Considered: assembling the shared envelope
 * {@code com.carddemo.common.web.PageResponse} in default methods on this interface, so that the
 * surplus-row trim and the capture of the two boundary keys would have a single owner next to the
 * queries that produce them. It was evaluated and rejected, and the reason is a hard constraint
 * rather than a preference. That envelope's canonical constructor requires each of its two cursor
 * components to be a token sealed by {@code com.carddemo.common.web.CursorToken} and refuses anything
 * else, enforced at
 * {@code services/common-lib/src/main/java/com/carddemo/common/web/PageResponse.java} L342 and L343
 * by the check at L357 through L364. Sealing needs key material and the binding the token is issued
 * for, and a Java interface has neither a field nor a constructor to hold them, so a default method
 * here could only offer the raw key -- and every non-empty page it built would be refused at run
 * time. The refusal is a guarded contract rather than a theoretical risk: the test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/web/PageResponseTest.java} L263
 * through L271 asserts exactly that a raw unsealed key is rejected as a cursor. Every method below
 * therefore returns {@code List<Account>}, which is also the shape the equivalent browse repositories
 * in the card and transaction contexts return.</p>
 *
 * <p>Assumptions: the envelope is assembled one layer up, in {@code com.carddemo.account.service},
 * which holds the sealer and the caller's identity and can therefore mint the boundary tokens this
 * interface cannot. That division is the package ruling stated in {@code package-info.java} beside
 * this file, not a choice taken here. Reading rows here and sealing them there is what keeps this interface free of
 * configuration entirely: it needs no key, no lifetime and no request context to be exercised. The
 * envelope it feeds declares exactly four components -- its {@code items}, a {@code firstKey}, a
 * {@code lastKey} and a single {@code hasNext} -- and the division of labour is that this interface
 * supplies the rows from which the first three are derived while the layer above settles all four.</p>
 *
 * <p>Assumptions: the surplus row read to settle whether a further page exists is an artifact of the
 * row bound and must not leak past the caller that created it. The token naming a page's trailing
 * boundary identifies the last row the caller actually RETURNED, never that surplus row. A token
 * identifying a row the caller never received cannot be verified by that caller, and a subsequent
 * request built from it would resume from a position the caller cannot account for.</p>
 *
 * <p>Assumptions: the cursor this interface accepts is already a 64-bit integer, and the textual form
 * is the envelope's concern rather than this interface's. The envelope's two cursor components are
 * text because they are opaque and sealed, while this entity's key is the unsigned eleven-digit
 * {@code ACCT-ID PIC 9(11)} of {@code app/cpy/CVACT01Y.cpy} L5, so the layer above renders the key
 * into a token on the way out and recovers a number from it on the way back in. What is assumed here is only that the value recovered is the key itself and
 * not a re-encoding of it, which is why every parameter below is typed as the key is typed and no
 * parsing happens on this boundary. A repository that parsed a token would be interpreting a value it
 * was handed rather than comparing it.</p>
 *
 * <h2>Concurrency is optimistic, and no lock is held across a request boundary</h2>
 *
 * <p>Refactoring Rationale: an update to an account is guarded by the version column the entity
 * declares, and this interface adds no locking hint of any kind. The reference performs the same check
 * by hand across the gap between two screen turns. {@code app/cbl/COACTUPC.cbl} enters
 * {@code 9600-WRITE-PROCESSING.} at L3888, and under the comment at L3890 announcing that the account
 * file is read with intent to change it, it issues at L3894 through L3900 a read naming
 * {@code FILE (LIT-ACCTFILENAME)} with the bare {@code UPDATE} option at L3896. It then asks whether
 * the row still matches the snapshot it took, under the comment at L3945 wondering aloud whether
 * anyone changed the record while the program was away: the {@code PERFORM} at L3947 and L3948 enters
 * {@code 9700-CHECK-CHANGE-IN-REC.} at L4109, whose first comparison is at L4115 and whose body runs
 * to L4192 before its exit paragraph at L4193, raising the changed condition at L4189. The snapshot
 * it compares against is {@code 05 ACUP-OLD-DETAILS.} at L669, which ends immediately before
 * {@code 05 ACUP-NEW-DETAILS.} at L757 and therefore spans L669 through L756, and which holds each
 * numeric twice -- a character declaration overlaid by a numeric one, as at L675 through L677 where
 * a twelve-character balance is redefined as {@code PIC S9(10)V99}, and again at L671 through L673,
 * L678 through L680 and L681 through L683. On a failed rewrite the program sets its locked-but-failed
 * state and issues a rollback across L4095 through L4103, at L4099 through L4101, leaving by the exit
 * at L4105.</p>
 *
 * <p>Nothing is lost by not holding a lock, and this is the load-bearing part of the decision above.
 * The reference read-for-update lock was never held across client think-time -- that
 * is PRECISELY why the snapshot at L669 has to exist at all. So no explicit locking hint, no
 * select-for-update and no pessimistic mode appears here: any of them would hold a database row
 * across a request boundary the reference never held one across, which is a stronger claim than the
 * source makes and a new way for one slow user to stall another.</p>
 *
 * <p>Assumptions: the conflict this raises is rendered as HTTP 409 by
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, which already maps the optimistic-lock
 * failure and already selects the verbatim sentence the reference declares at L521 and L522 of
 * {@code app/cbl/COACTUPC.cbl}. No exception type, no handler and no status appears in this file,
 * because a second place that decided the same status is a second place for the two to disagree.</p>
 *
 * <h2>Two accepted compromises</h2>
 *
 * <p>Trade-offs: all three queries below are derived from their method names, and no statement is
 * written for any of them -- neither a query in the provider's own language nor one in the database's.
 * The account master carries no optional filter, so each predicate is a single comparison on the key
 * plus an ordering, which a method name states completely; the card context writes statements only
 * because two of its filters are optional and a derived name cannot express an argument that is
 * sometimes absent. What is given up is the ability to hand-tune any of the three. What is bought is
 * portability in two distinct senses: a statement in the database's own dialect would bind these
 * three reads to one vendor's syntax, and it would additionally have to name the physical table,
 * which is exactly the identifier this module supplies from configuration through the search path
 * that {@code com.carddemo.account.config.DataSourceConfig} sets on every pooled connection. A
 * hand-written statement is therefore two independent ways to contradict a value this module owns
 * elsewhere.</p>
 *
 * <p>Trade-offs: the target reads at a stronger isolation than the reference did, and the difference
 * is accepted rather than engineered away. {@code app/csd/CARDDEMO.CSD} defines this file at L1 and
 * declares {@code READINTEG(UNCOMMITTED)} at L3, so a reader there could observe an uncommitted
 * change, whereas the target's database reads committed rows by default. The compromise accepted is
 * that a scan here will not see a change another transaction has not yet committed, which the
 * reference could; what is bought is that no page can be assembled from a row that never existed.
 * That ruling belongs to {@code com.carddemo.account.config.DataSourceConfig} and is cross-referenced
 * here rather than restated, so the isolation level has one owner.</p>
 *
 * <p>Assumptions: the entity beside this package names the expiry column {@code expirationDate} while
 * {@code app/cpy/CVACT01Y.cpy} declares the field at L11 with a spelling that drops a letter. The
 * baseline declares it that way; the entity carries the other spelling; the divergence is documented
 * on the entity, which is where the decision was taken. No member of this interface names that field,
 * so nothing here depends on which spelling is used.</p>
 *
 * <p>Every reference program cited above is read and cited only. None is modified. The online
 * programs of this context cannot be exercised end to end without a runtime the build host does not
 * carry, as {@code tests/README.md} records at L83 through L85, so no claim of golden-master coverage
 * is made for anything in this file.</p>
 *
 * <p>The documentation convention this file follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Reads the lowest-keyed accounts, in ascending identifier order.
     *
     * <p>This is the opening read of the ordered scan, the one taken when no cursor has been supplied
     * yet. It stands in for the batch reader's first pass through {@code 1000-ACCTFILE-GET-NEXT.} at
     * L165 of {@code app/cbl/CBACT01C.cbl}, reached immediately after the open at L317.</p>
     *
     * <p>Alternatives Considered: opening the scan through
     * {@link #findByAccountIdGreaterThanOrderByAccountIdAsc} with a cursor chosen to sort below every
     * real key, which would remove this declaration entirely. Rejected because it would oblige this
     * interface to know a value that precedes every account identifier, and the reference guarantees
     * none: {@code ACCT-ID} is declared {@code PIC 9(11)} at L5 of {@code app/cpy/CVACT01Y.cpy}, an
     * unsigned eleven-digit field whose domain includes zero, so any sentinel chosen here could
     * collide with a real row and silently drop it from the opening page while showing it on a later
     * one. A query with no lower bound states the intent directly and cannot be wrong about it.</p>
     *
     * @param limit the greatest number of rows to read, of type {@code Limit}, which a caller
     *     assembling a page sets to one more than the page is to hold; must not be {@code null}
     * @return the lowest-keyed rows as a {@code List<Account>} in ascending identifier order, holding
     *     at most as many rows as {@code limit} admits and empty when the table holds none; never
     *     {@code null}
     */
    // WHY : Assumptions: the row bound is one MORE than the page is to hold, and the presence of that
    //       surplus row is how a caller learns a further page follows. It is never a tally of how many
    //       rows or how many pages the table holds. The reference settles the same question the same
    //       way, setting the further-rows indicator it declares at app/cbl/COCRDLIC.cbl:242 -- with
    //       the two condition names at :243 and :244 -- by discovering one row more than its screen
    //       can hold. Deriving the answer from a total instead would make every page pay for a count
    //       of rows that nobody receives.
    // WHY : Assumptions: no method on this interface names a page size, and the bound is always the
    //       caller's argument. This context has no browse screen to take a row capacity from, so a
    //       size pinned in a data-access interface would be a number with no source in the reference;
    //       the batch triad this scan replaces reads through to end-of-file and has no row capacity at
    //       all.
    List<Account> findAllByOrderByAccountIdAsc(Limit limit);

    /**
     * Reads the accounts that follow a stated position, in ascending identifier order.
     *
     * <p>This is the forward half of the ordered scan, the continuation the batch reader performs by
     * re-entering {@code 1000-ACCTFILE-GET-NEXT.} at L165 of {@code app/cbl/CBACT01C.cbl} until its
     * end-of-file condition is raised, and the counterpart of a read-next against a positioned
     * browse.</p>
     *
     * @param lastKey the identifier of the last row the caller already holds, of type {@code Long};
     *     the comparison is STRICT, so a row whose identifier equals this value is excluded and the
     *     page resumes at the identifier immediately after it; must not be {@code null}
     * @param limit the greatest number of rows to read, of type {@code Limit}, set by the caller to
     *     one more than the page is to hold so that the surplus row reports whether a further page
     *     follows; must not be {@code null}
     * @return the following rows as a {@code List<Account>} in ascending identifier order, holding at
     *     most as many rows as {@code limit} admits and empty when the position already stands at the
     *     end of the ordered set; never {@code null}
     */
    // WHY : Assumptions: the comparison is strict rather than inclusive, and it is strict BECAUSE the
    //       cursor is the last row RETURNED rather than the first row not yet returned. Those two
    //       choices are one decision: a row already delivered must be excluded, or every page would
    //       repeat its predecessor's final row. An inclusive comparison would therefore require the
    //       cursor to name a row the caller never received, which is the position the interface
    //       documentation above rules out -- a caller cannot verify a key it was never given.
    // WHY : Assumptions: an empty result is an ordinary outcome and never an error. It means the
    //       caller has reached the end of the ordered set, which is the state the batch reader treats
    //       as its end-of-file condition at app/cbl/CBACT01C.cbl:165-166 rather than as a failure --
    //       the program leaves by its normal close at :388. Nothing here models exhaustion as an
    //       exception, so no caller should catch one.
    List<Account> findByAccountIdGreaterThanOrderByAccountIdAsc(Long lastKey, Limit limit);

    /**
     * Reads the accounts that precede a stated position, in descending identifier order.
     *
     * <p>This is the backward half of the ordered scan and the counterpart of a read-previous against
     * a positioned browse. The rows arrive in the order the query reads them, nearest the stated
     * position first, which is the reverse of the order a page presents them in; the caller reverses
     * them, for the reason recorded below.</p>
     *
     * @param firstKey the identifier of the first row the caller already holds, of type {@code Long};
     *     the comparison is STRICT, so a row whose identifier equals this value is excluded and the
     *     page ends at the identifier immediately before it; must not be {@code null}, since a
     *     backward step is only expressible from a page that was already returned
     * @param limit the greatest number of rows to read, of type {@code Limit}, set by the caller to
     *     one more than the page is to hold so that the surplus row reports whether a further page
     *     precedes this one; must not be {@code null}
     * @return the preceding rows as a {@code List<Account>} in DESCENDING identifier order, nearest
     *     the stated position first, holding at most as many rows as {@code limit} admits and empty
     *     when the position already stands at the beginning of the ordered set; never {@code null},
     *     and the caller REVERSES this list into ascending order before assembling a page
     */
    // WHY : Trade-offs: the rows come back reversed relative to the order a page presents them, and
    //       the caller carries the cost of turning them round. Ordering ascending here instead is not
    //       available, and the reason is arithmetic rather than stylistic: the row bound has to keep
    //       the rows NEAREST the cursor, so the ordering that selects them is necessarily the
    //       descending one. An ascending order under the same bound would return the lowest
    //       identifiers in the whole table instead of the rows immediately preceding the caller's
    //       position -- a page nobody asked for. What is accepted is that this one method's result is
    //       not in presentation order; what is bought is that the page adjacent to the position is the
    //       page returned, which is the behaviour the reference read-previous verb has.
    // WHY : Assumptions: this direction reports no availability of its own, and the shared envelope
    //       has no component for it to report into -- com.carddemo.common.web.PageResponse declares
    //       four, its items, a leading key, a trailing key and a single further-page flag. The
    //       omission matches the reference, whose communication area carries a further-rows indicator
    //       at app/cbl/COCRDLIC.cbl:242-244 and no backward counterpart anywhere; what it uses
    //       instead is the screen ordinal at :237-238, which has no target analogue because an
    //       ordinal cannot be derived from a key without enumerating everything ahead of it, and that
    //       enumeration is what resuming from a key exists to avoid.
    List<Account> findByAccountIdLessThanOrderByAccountIdDesc(Long firstKey, Limit limit);
}
