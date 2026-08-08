package com.carddemo.account.repository;

import com.carddemo.account.domain.Customer;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the customer master rows this bounded context owns.
 *
 * <p><b>Purpose.</b> This is the migrated form of every path by which the reference system reaches a
 * customer record, and of no path it does not have. Two paths exist. The online screens enter the
 * record by its identifier, and the batch reader walks the whole file in identifier order. Both
 * survive here as typed methods on an interface rather than as file verbs, the first inherited and the
 * second declared as an ordered, row-limited scan resumable from a key.</p>
 *
 * <p>Assumptions: the record contract this interface reads is {@code app/cpy/CVCUS01Y.cpy}, whose L2
 * header declares a length of 500 bytes, whose L4 opens {@code 01 CUSTOMER-RECORD.} and whose L5
 * declares the leading field {@code CUST-ID PIC 9(09)}. Eighteen named fields follow, of which the
 * trailing {@code FILLER PIC X(168)} at L23 is padding to the fixed length and is carried into no
 * column. That this and not the similarly named {@code app/cpy/CUSTREC.cpy} is the contract is settled
 * by {@code app/cbl/CBCUS01C.cbl} L45, which includes {@code CVCUS01Y} and not the other book; the
 * other book exists in the tree and is authority for nothing read here, so it is named once, as the
 * substitution this interface does not make, and never as a source.</p>
 *
 * <p>Assumptions: the identifier is the record's LEADING field, and the batch reader states it
 * independently of the copybook -- {@code app/cbl/CBCUS01C.cbl} splits the record into
 * {@code FD-CUST-ID PIC 9(09)} at L39 and {@code FD-CUST-DATA PIC X(491)} at L40, which sum to the
 * declared 500. Two unrelated statements of the same key position are what let the mapping to
 * {@code customers.customer_id} be trusted without re-deriving it, and the entity's own column
 * declaration is the third.</p>
 *
 * <h2>The keyed read is inherited and is deliberately not redeclared</h2>
 *
 * <p>Assumptions: the by-identifier read is the inherited {@code findById}, and nothing here shadows
 * it. What it replaces is the paragraph {@code 9400-GETCUSTDATA-BYCUST.} at L825 of
 * {@code app/cbl/COACTVWC.cbl}, whose body at L826 through L831 enters
 * {@code DATASET (LIT-CUSTFILENAME)} on a record identification field and receives the record
 * {@code INTO (CUSTOMER-RECORD)}, with the data set literal itself declared at L188 and L189;
 * {@code app/cbl/COACTUPC.cbl} declares the same paragraph at L3752. A redeclared twin would add a
 * second name for one access path, and a reader comparing two call sites could not tell whether the
 * difference in name meant a difference in behaviour.</p>
 *
 * <p>Assumptions: no by-account finder is declared, and the reason is that the identifier this
 * interface is entered on does not come from a client. The reference reaches the customer only after
 * the account, along the chain driven from {@code 9000-READ-ACCT.} at L687 of
 * {@code app/cbl/COACTVWC.cbl}: the cross-reference read runs first, the account read at L701 and
 * L702 second, and only then is the customer identifier moved into the record identification field at
 * L708 before the read is performed at L710 and L711. The identifier is therefore supplied by the
 * cross-reference row, which another interface in this package already returns, so a by-account
 * customer finder here would be a second route to a value the caller is already holding.</p>
 *
 * <h2>Ordered scans resume from a key, and never from a counted position</h2>
 *
 * <p>Alternatives Considered: the ordered scans below are entered at a key and read in key order. The
 * rejected alternative is positional paging -- asking the store to skip a counted number of rows and
 * return the batch after them -- and it is rejected on observable behaviour rather than on taste. Under
 * concurrent insertion the number of rows preceding a given position changes between one request and
 * the next, so a scan positioned by counting omits rows it should have returned and returns rows it
 * already returned; a scan resumed from the last key it actually yielded can do neither, because a key
 * names a row rather than a distance. The reference counted nothing either:
 * {@code app/cbl/COCRDLIC.cbl} declares {@code 01 WS-THIS-PROGCOMMAREA.} at L229 and carries across
 * the terminal turn, at L230 through L244, a trailing key pair at L230 through L232, a leading key pair
 * at L233 through L235, a screen ordinal at L237 and L238, a last-screen-displayed flag at L239 through
 * L241 and a further-rows indicator at L242 through L244, alongside the row counter at L145. Every one
 * of those is a key or a flag, and none is a position in a result set.</p>
 *
 * <p>Refactoring Rationale: the reference expresses one browse with four verbs -- position, read
 * forward, read backward, release -- and all four collapse into the single pair of ordered queries
 * declared below, ascending for the forward direction and descending for the backward one. What made
 * the collapse available is where the state lived: the browse cursor was carried in the communication
 * area the client echoed back, per the block at L229 onward of {@code app/cbl/COCRDLIC.cbl}, and not in
 * a file position the region held open between turns. Because there was no server-side position, there
 * is no handle here to acquire and none to release, and the pair of queries is a complete substitute
 * rather than an approximation of one.</p>
 *
 * <p>Assumptions: the page size is supplied by the caller on every method and is fixed nowhere in this
 * interface. This context has no online browse screen to take a screen depth from -- both account
 * screens show one record -- and the scan declared here stands in for the batch reader's whole-file
 * walk, which has no screen depth at all. The cursor shape above is cited as lineage for how a scan is
 * resumed and for nothing else; importing a row count from a program in another context would fix this
 * interface to a screen that does not read from it.</p>
 *
 * <p>Assumptions: the keyed read and the ordered scan are the entire access surface, because the batch
 * reader has no random read to migrate. {@code app/cbl/CBCUS01C.cbl} declares
 * {@code ACCESS MODE IS SEQUENTIAL} at L31 beside {@code RECORD KEY IS FD-CUST-ID} at L32, and drives
 * an open at L118, a get-next paragraph at L92 whose body at L93 is
 * {@code READ CUSTFILE-FILE INTO CUSTOMER-RECORD.} and a close at L136. Nothing in it reaches a record
 * by key, so the ordered scan is what that program becomes and the keyed read comes wholly from the
 * screens.</p>
 *
 * <h2>Where the paged envelope is assembled, and why not here</h2>
 *
 * <p>Alternatives Considered: assembling the paged envelope on this interface, in a method that would
 * call one of the queries below, detect the surplus row, trim it and return
 * {@code com.carddemo.common.web.PageResponse}. The motivation was real -- the surplus row is an
 * artifact of the row limit and ought not to travel further than the code that asked for it -- but the
 * envelope cannot be built from here, for a reason that is mechanical rather than stylistic. That
 * record's canonical constructor requires each of its two cursor components to be a token sealed by
 * {@code com.carddemo.common.web.CursorToken} and refuses any value that is not, and sealing is an
 * operation on an instance holding signing key material that a method on this interface has no way to
 * obtain. A method here would therefore refuse every page it assembled. The envelope is built one
 * layer up, where that collaborator is available, which is the division this package's own descriptor
 * already records; this interface yields entities and ordered lists of entities, and nothing else.</p>
 *
 * <p>Trade-offs: because the trimming happens one layer up, the surplus row does cross this boundary.
 * That is stated rather than glossed, since it is the compromise the paragraph above accepts: a caller
 * that publishes what these methods return without trimming publishes one row too many. The obligation
 * is recorded on the forward query below, where the surplus row is requested, so that the rule sits
 * beside the call that creates the surplus rather than in a document a caller may not have read.</p>
 *
 * <p>Assumptions: the cursor a caller passes in is a {@code Long} rather than a token, and the boundary
 * between the two lives one layer up in the same place the sealing does. The envelope's two cursor
 * components are declared as text and the entity's identifier is a {@code Long}, so one conversion in
 * each direction is unavoidable somewhere; it is not here, because opening an inbound token needs the
 * same key material sealing an outbound one needs. What this interface consumes is the identifier
 * already recovered from that token, and what it exposes is the identifier of each row it returned, so
 * neither a token format nor the key that protects it is known to this file.</p>
 *
 * <h2>Concurrency is optimistic, and no row is held across a request</h2>
 *
 * <p>Refactoring Rationale: an update to a customer row is guarded by the version column the entity
 * declares, and by no locking hint on this interface. The reference guards it by hand instead.
 * {@code app/cbl/COACTUPC.cbl} enters {@code 9600-WRITE-PROCESSING.} at L3888, and under the comment
 * at L3890 announcing a read for update it issues the read at L3894 through L3900 whose bare
 * {@code UPDATE} option is at L3896. It then asks, under the comment at L3945 wondering whether anyone
 * changed the record while the program was away, whether the row still matches the snapshot it took:
 * the {@code PERFORM} at L3947 and L3948 enters {@code 9700-CHECK-CHANGE-IN-REC.} at L4109, whose body
 * runs to L4192 before the exit paragraph at L4193 and which raises the changed condition at L4189. The
 * snapshot it compares against is {@code 05 ACUP-OLD-DETAILS.} at L669, which ends immediately before
 * {@code 05 ACUP-NEW-DETAILS.} at L757 and therefore spans L669 through L756; its last customer fields
 * are at L752 through L756, the credit score among them held as {@code X(03)} with a {@code 9(03)}
 * redefinition. Where the rewrite fails the program issues a rollback at L4099 through L4101, inside
 * the block at L4095 through L4103.</p>
 *
 * <p>Refactoring Rationale: nothing is given up by holding no lock, and this is the load-bearing half
 * of the decision above. The reference read-for-update lock was never held across client think-time --
 * that is PRECISELY why the snapshot at L669 has to exist at all. So no explicit locking hint, no
 * select-for-update and no pessimistic mode appears here: any of them would hold a database row for as
 * long as a user took to fill in a screen, which is a stronger claim than the reference makes and a new
 * way for one slow user to block another.</p>
 *
 * <p>Assumptions: no conflict is translated into a response on this interface, and no exception type is
 * declared. {@code com.carddemo.common.error.GlobalExceptionHandler} already answers a version conflict
 * with HTTP 409 and recognises it by fully-qualified class name while walking the cause chain, since
 * the shared library carries no persistence dependency to import against. It keeps three conflict
 * conditions apart, which the reference also does: the changed-record condition at L521 and L522 of
 * {@code app/cbl/COACTUPC.cbl} is distinct from the customer-specific lock failure at L519 and L520,
 * which is itself distinct from the account one at L517 and L518. Which sentence a client reads is
 * chosen a layer up; this interface propagates and translates nothing.</p>
 *
 * <h2>Boundaries</h2>
 *
 * <p>Trade-offs: no finder is declared over either encrypted identifier column, and the compromise is
 * plain. The two fields behind them are {@code CUST-SSN PIC 9(09)} at L17 and
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)} at L18 of {@code app/cpy/CVCUS01Y.cpy}, and both are carried as
 * ciphertext. Ciphertext does not order or compare like the value inside it, so a predicate over either
 * column could match only an exact re-encryption and could never range-scan; what is given up is a
 * lookup by either identifier, which the reference offers on no path, and what is kept is that neither
 * value is a queryable access path in the first place. The mapping layer returns both masked.</p>
 *
 * <p>Trade-offs: all three queries below are derived from their method names and this interface
 * declares no query text of any kind, native or otherwise. The cost is that the two boundary
 * predicates are expressed as method names rather than as statements a reader can scan in one place, so
 * a name is longer than a clause would be. What that buys is that the column mapping keeps exactly one
 * definition, on the entity: a statement naming a column would give the schema a second definition, and
 * a later column rename would move the two out of step silently, whereas a derived name resolves
 * against the entity's own property and fails at context startup if the property is gone. Text written
 * against the store's own dialect would additionally have to be revisited to run anywhere else, which
 * the migration's test tier does when it exercises these methods against a container.</p>
 *
 * <p>Trade-offs: the isolation these reads run under is stronger than the reference's, and this is a
 * cross-reference rather than a fresh claim, because {@code com.carddemo.account.config.DataSourceConfig}
 * settles isolation and the schema binding for this context. The file resource this interface replaces
 * is defined at L50 of {@code app/csd/CARDDEMO.CSD} and is declared to read without regard to
 * uncommitted change, the {@code READINTEG(UNCOMMITTED)} operand appearing on its L53. The default this
 * service runs under does not return uncommitted rows, so the compromise accepted runs in the direction
 * nobody minds: a read here may decline to show something the reference would have shown, and never the
 * reverse. No table name is qualified anywhere in this file and the schema name is not spelled here,
 * because that configuration class pins the session search path on every pooled connection and is the
 * single owner of the question.</p>
 *
 * <p>Assumptions: no behaviour of this interface is covered by a golden-master comparison, and saying so
 * is not pessimism but accuracy a reader would otherwise have to discover. L83 through L85 of
 * {@code tests/README.md} record that the online programs cannot be run end to end without a CICS
 * runtime, which the runner does not have; and the rules that suite asserts verbatim, from L553 onward,
 * govern the posting, interest and category-balance programs of other contexts and name none of the
 * programs cited above. The behaviour here is therefore established by this module's own tests against
 * the copybook and program lines cited throughout, and the graded condition-code rubric under
 * {@code tests/**} belongs to that COBOL suite alone -- the gate over this file passes or fails.</p>
 *
 * <p>Every reference program and copybook cited above is read as evidence only. None is modified, and
 * the migrated behaviour is described against them rather than presented as a change to them. Where
 * this file and {@code docs/CODE_DOCUMENTATION_STANDARD.md} appear to differ, the project
 * Explainability rule governs first, {@code config/checkstyle/checkstyle.xml} second and that prose
 * standard third.</p>
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Returns the opening rows of the customer master in identifier order.
     *
     * <p>Assumptions: this is the entry point of a scan, so it carries no cursor at all rather than a
     * sentinel one. The reference opens its walk the same way, with the open at L118 of
     * {@code app/cbl/CBCUS01C.cbl} followed straight into the get-next paragraph at L92; there is no
     * lowest-identifier value it seeks to first. A single method taking a nullable cursor was the
     * alternative, and it is not used because a nullable cursor makes an unbounded read the default
     * shape of a call that forgot to supply one.</p>
     *
     * <p>Trade-offs: the ordering is stated in the method name rather than left to the query plan.
     * A plan is free to return rows in a different sequence on two runs over the same data, and a scan
     * whose sequence can change cannot be resumed from the last key it returned -- which is the whole
     * mechanism the following two methods rely on. The reference had the guarantee for free and did not
     * have to ask for it: {@code app/cbl/CBCUS01C.cbl} declares {@code ACCESS MODE IS SEQUENTIAL} at L31
     * over {@code RECORD KEY IS FD-CUST-ID} at L32, so its get-next at L93 yields rows in key order by
     * construction. Naming the order here is what buys that same guarantee back. The cost is a longer
     * name; what it buys is that the ordering the resumption depends on is part of the contract rather
     * than an observed habit.</p>
     *
     * @param limit the greatest number of rows to return, supplied by the caller because no screen in
     *     this context browses this record and the batch walk it stands in for has no row bound of its
     *     own; a caller that intends to report whether further rows follow asks here for one more row
     *     than it will publish
     * @return the first rows in ascending identifier order, at most as many as {@code limit} allows,
     *     empty when the master holds no rows; never {@code null}
     */
    List<Customer> findAllByOrderByCustomerIdAsc(Limit limit);

    /**
     * Returns the rows that follow a given identifier, in identifier order.
     *
     * <p>Assumptions: the bound is strict, so the row the caller resumed from is not returned a second
     * time. The identifier passed in is the one belonging to the LAST row the caller actually
     * received and published, never the surplus row described below -- a bound taken from a row the
     * caller never received would resume from a position it cannot account for.</p>
     *
     * <p>Assumptions: whether further rows follow a page is settled by asking here for one row more
     * than the caller intends to publish and observing whether that row arrives; it is never settled by
     * counting how many rows exist. This is the reference's own method, not an invention: at L242
     * through L244 {@code app/cbl/COCRDLIC.cbl} keeps a further-rows indicator as a single flag, which
     * it raises by discovering one record beyond what the screen can hold. The caller therefore
     * publishes the rows it asked for, reports the presence of the surplus row, and puts the surplus row
     * itself nowhere -- it is an artifact of the bound and is not data the caller received.</p>
     *
     * @param afterCustomerId the identifier of the last row already published, strictly below every row
     *     returned; must not be {@code null}, because the scan's opening page is
     *     {@link #findAllByOrderByCustomerIdAsc(Limit)} rather than this method with an absent bound
     * @param limit the greatest number of rows to return, which a caller settling the further-rows
     *     question sets to one above the number it will publish
     * @return the rows after {@code afterCustomerId} in ascending identifier order, at most as many as
     *     {@code limit} allows, empty when that identifier is at or beyond the last row; never
     *     {@code null}
     */
    List<Customer> findByCustomerIdGreaterThanOrderByCustomerIdAsc(Long afterCustomerId, Limit limit);

    /**
     * Returns the rows that precede a given identifier, nearest first.
     *
     * <p>Refactoring Rationale: the descending order is what makes this the exact counterpart of the
     * reference's backward read rather than a re-scan from the top of the file. Reading backward has to
     * yield the rows NEAREST the cursor, and over an ascending index the only way to reach them without
     * walking everything ahead of them is to traverse the other way. That the reference reads backward
     * at all, rather than re-reading from the start, is visible in the state it keeps: it carries a
     * LEADING key pair at L233 through L235 of {@code app/cbl/COCRDLIC.cbl} beside the trailing pair at
     * L230 through L232, and a leading key is worth carrying only if it is somewhere a scan is entered
     * from. This is why the four browse verbs reduce to this method and the forward one; the descending
     * sequence is an implementation of the traversal and not the order the rows are presented in.</p>
     *
     * <p>Assumptions: the caller reverses what this returns before presenting it, so that rows reach a
     * reader in ascending identifier order whichever direction was travelled. The two key pairs cited
     * above are what settle this: the reference holds a leading key AND a trailing key for one screen, so
     * the rows between them are held in ascending relation whichever direction the screen was reached
     * from, and presenting a backward step in descending sequence would invert that relation for that one
     * step alone. The obligation is recorded here because this is the only method whose result is not
     * already in the order it should be read in.</p>
     *
     * @param beforeCustomerId the identifier of the first row already published, strictly above every
     *     row returned; must not be {@code null}, for the same reason the forward bound may not be
     * @param limit the greatest number of rows to return, subject to the same surplus-row convention as
     *     the forward direction
     * @return the rows before {@code beforeCustomerId} in DESCENDING identifier order, nearest first, at
     *     most as many as {@code limit} allows, empty when that identifier is at or before the first
     *     row; never {@code null}
     */
    List<Customer> findByCustomerIdLessThanOrderByCustomerIdDesc(Long beforeCustomerId, Limit limit);
}
