package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.StatementTransactionView;
import jakarta.persistence.QueryHint;
import java.util.stream.Stream;
import org.hibernate.jpa.AvailableHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forward-only cursor over the card-ordered transaction projection the statement generator reads.
 *
 * <p>This role stands in for one data definition and one only:
 * {@code //TRNXFILE DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS} at L83 of
 * {@code app/jcl/CREASTMT.JCL}, consumed by {@code //STEP040  EXEC PGM=CBSTM03A,COND=(0,NE)} at L79
 * of the same job. Its rows are the 350-byte record {@code app/cbl/CBSTM03B.CBL} declares at L58
 * through L63 -- {@code FD-TRNXS-ID} being {@code FD-TRNX-CARD PIC X(16)} at L61 plus
 * {@code FD-TRNX-ID PIC X(16)} at L62, so 32 bytes, followed by {@code FD-ACCT-DATA PIC X(318)} at
 * L63 -- and they arrive here as {@link StatementTransactionView}, the mapping of
 * {@code 01 TRNX-RECORD} at L20 of {@code app/cpy/COSTM01.CPY}. </p>
 *
 * <p>Four of the thirteen calls into that subprogram belong to this definition: the open at L734 of
 * {@code app/cbl/CBSTM03A.CBL} and the priming read at L746, both inside
 * {@code 8100-TRNXFILE-OPEN.} at L730 and both accepting {@code '00' OR '04'} at L736 and L748; the
 * streaming read at L835 inside {@code 8500-READTRNX-READ.} at L818; and the close at L860 inside
 * {@code 9100-TRNXFILE-CLOSE.} at L856. Open, read, read, close is the whole of it, which is why the
 * surface below is a cursor and carries no keyed single-row read. </p>
 *
 * <h2>Why this is a cursor and not a keyed lookup</h2>
 *
 * <p>Assumptions: this definition is sequential-only. {@code app/cbl/CBSTM03B.CBL} L33 declares
 * {@code ACCESS MODE  IS SEQUENTIAL} for it, and its per-definition paragraph at L133 through L155
 * implements exactly three operations -- open at L135 through L138, read at L140 through L144 and
 * close at L146 through L149. The keyed-read operation code {@code 'K'} is implemented for the other
 * two definitions only, at L188 through L193 and L213 through L218, and both of those are declared
 * {@code ACCESS MODE  IS RANDOM} at L45 and L51. Register entry <b>R3</b> in
 * {@code package-info.java} beside this file settles the reading of {@code LK-M03B-KEY-LN} with five
 * proofs and records that only the two sequential roles stream; this file cites that entry rather
 * than restating it. </p>
 *
 * <p>Assumptions: two readings of {@code LK-M03B-KEY-LN PIC S9(4)}, declared at
 * {@code app/cbl/CBSTM03B.CBL} L111, are on record and both are named here rather than one being
 * quietly overwritten. A peer brief for {@code services/reporting-service/README.md}, and the same
 * wording carried at L126 through L129 of {@link StatementTransactionView}, read it as a
 * significant-key length implying a generic keyed browse, which would justify positioning into this
 * relation by key from inside the statement path. The first-hand COBOL is authoritative and it
 * contradicts that reading, on the same precedent the shared authorization codec contract sets for
 * its own comma defect: a browse-verb census across all 230 lines of the file returns zero for
 * {@code KEY IS GREATER}, {@code READ NEXT}, {@code READNEXT}, {@code READPREV}, {@code STARTBR} and
 * {@code ENDBR}, the only bare {@code START} hit being the paragraph label {@code 0000-START.} at
 * L116, and the caller computes the whole key every time -- {@code app/cbl/CBSTM03A.CBL} L373
 * through L374 yields 9 and L397 through L398 yields 11. One artifact matters specifically here:
 * {@code LK-M03B-KEY} is {@code PIC X(25)} at L110 while this definition's {@code FD-TRNXS-ID} is 32
 * bytes at L60 through L62. The mismatch is harmless because this definition supports sequential
 * reads only and never uses that key field at all. </p>
 *
 * <h2>The ordering is the baseline's own, not a preference</h2>
 *
 * <p>Assumptions: the order both methods below declare is already deterministic in the reference.
 * {@code app/jcl/CREASTMT.JCL} L53 is {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} -- two keys, both
 * character, both ascending, the card number at one-based 263 for 16 bytes and then the transaction
 * identifier at one-based 1 for 16 -- so reproducing it exactly is reproduction and not invention.
 * Register entry <b>R6</b> holds the wider ordering decision and contrasts it with the report path,
 * whose single-key sort at {@code app/jcl/TRANREPT.jcl} L46 carries no equal-records qualifier and is
 * therefore not total. The two must not be conflated: they are different declarations in different
 * jobs and only one of them is already deterministic. </p>
 *
 * <p>Assumptions: the ordering is declared in the queries below and not in the relation being read.
 * {@code data-migration/sql/V1__reporting_views.sql} states at its L289 through L294 that it
 * deliberately declares no ordering, because a relation's ordering is not something a caller can
 * rely on once an outer query is layered over it. Placing it here is therefore the only place it is
 * a contract at all. </p>
 *
 * <h2>Geometry, and why this is not the report projection</h2>
 *
 * <p>Assumptions: this record and the posting output record are two deliberately separate types with
 * different geometry rather than two names for one layout. {@code app/cpy/COSTM01.CPY} L20 through
 * L36 places the card number at one-based 1 through 16 and the transaction identifier at 17 through
 * 32, whereas {@code app/cpy/CVTRA05Y.cpy} L5 through L18 places the transaction identifier at 1
 * through 16 and the card number at 263 through 278. The cause is the physical rearrangement at
 * {@code app/jcl/CREASTMT.JCL} L54, {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}, which
 * lifts the card number to position 1, block-shifts the original first 262 bytes to position 17 and
 * copies 50 bytes from position 279 verbatim. </p>
 *
 * <p>Assumptions: that transform writes 16 plus 262 plus 50 bytes, which is 328 of the record's 350.
 * Because the origination timestamp sits at one-based 279 for 26 characters and the processing
 * timestamp at one-based 305 for 26, the 50-byte tail carries the origination timestamp whole and
 * only the first 24 characters of the processing timestamp, leaving one-based output positions 329
 * through 350 never written. {@code app/cpy/COSTM01.CPY} nonetheless declares the full 26 at L35.
 * The baseline truncates there; this cursor reads a microsecond-precision timestamp column from the
 * relation; the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed here. Register
 * entry <b>R9</b> carries the same arithmetic. A second artifact of the immutable reference is worth
 * recording beside it and is an observation and not a defect claim: the same field is typed
 * {@code CH} at {@code app/jcl/CREASTMT.JCL} L53 and {@code ZD} at {@code app/jcl/TRANREPT.jcl}
 * L41. </p>
 *
 * <h2>Identity, and the one hazard a caller must handle</h2>
 *
 * <p>Assumptions: the declared identity is the composite key {@code TRNX-KEY} of
 * {@code app/cpy/COSTM01.CPY} L21 through L23, and the identifier type is exactly the one
 * {@link StatementTransactionView} declares rather than a second type introduced here. </p>
 *
 * <p>Assumptions: the card number this relation exposes is narrowed before it reaches this cursor,
 * so it identifies a rendering and not a card. {@code data-migration/sql/V1__reporting_views.sql}
 * L298 projects it as twelve asterisks concatenated with the last four digits, and L310 projects a
 * separate keyed digest beside it, derived from the whole unnarrowed number mixed with a secret this
 * module holds no privilege to read -- that privilege is withdrawn at L225 of the same file. Two
 * distinct cards sharing their last four digits therefore reach this cursor under one identical
 * rendering. Resolving that is the caller's obligation and not this cursor's: the per-card method
 * below returns every row for the rendering it is given, each row carrying its own digest, so a
 * caller totalling a statement compares those digests and refuses rather than merging two
 * cardholders' activity into one document. The consumer in this module does exactly that. </p>
 *
 * <h2>What this cursor does not do</h2>
 *
 * <p>Trade-offs: this role reads a relation under a privilege set holding {@code SELECT} and nothing
 * else, rather than owning storage of its own, and the compromise accepted is that it cannot supply
 * or repair its own read surface. {@code data-migration/sql/V1__reporting_views.sql} conveys the
 * read at L546 and withdraws every writing privilege at L568 through L576; the schema path and the
 * pooled role arrive from {@link com.carddemo.reporting.config.DataSourceConfig}, which also pins
 * schema generation to none. Register entries <b>R11</b> and <b>R12</b> hold this decision, the
 * second on the ground the target design states in its own words, that "a replica adds cost and
 * replica-lag semantics for no parity benefit". The consequence is stated plainly so a subsequent
 * reader does not work around it: a relation absent at run time is a defect to report against the
 * data-migration package, never something to create from here. </p>
 *
 * <p>Refactoring Rationale: the separate index-rebuild step the reference runs has no counterpart
 * here, while the access path it produced survives. That rebuild appears at exactly four sites --
 * {@code app/jcl/XREFFILE.jcl} L100, {@code app/jcl/TRANIDX.jcl} L52, {@code app/jcl/CARDFILE.jcl}
 * L110 and {@code app/jcl/TRANFILE.jcl} L109 -- and because the target engine maintains an index
 * transactionally as rows change, only the step disappears. The index itself belongs to the
 * transaction context and is neither created nor removed from here; {@code app/jcl/TRANIDX.jcl}
 * stays on disk untouched and is cited only as position evidence, {@code KEYS(26 304)} at L27 with
 * space-separated operands. Register entry <b>R8</b> holds this decision. </p>
 *
 * <p>Alternatives Considered: materialising a second physical copy of the transaction rows in card
 * order, which deserves naming because the reference does precisely that --
 * {@code app/jcl/CREASTMT.JCL} L44 through L61 sorts the cluster named at L45 into the sequential
 * dataset declared at L48 through L51, then at L56 runs the dataset utility whose control card at
 * L61 is {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)}, loading the keyed dataset at L59 that L83
 * then reads. Rejected because a second copy is a second truth for figures whose only purpose is to
 * restate the first exactly. Nothing here declares a relation, defines an index or copies a byte.
 * Register entry <b>R9</b> holds this decision. </p>
 *
 * <p>Refactoring Rationale: an operation this role does not support cannot reach a silent
 * no-operation here, because no such method exists to reach one. {@code app/cbl/CBSTM03B.CBL} L103
 * through L108 declares six operation codes as condition names, but the write and rewrite codes at
 * L107 and L108 are implemented in no paragraph -- a census for their two condition names across the
 * file returns exactly two hits, which are those declarations themselves. Such a request falls
 * through every test in its per-definition paragraph and lands on the exit whose only act is to move
 * the file status into the return code, at L152 for this definition, so the caller receives the
 * status of the previous operation on that file; an unrecognised definition name is worse, since
 * {@code WHEN OTHER} at L127 through L128 branches to the goback leaving the return code never
 * assigned. Register entry <b>R4</b> holds this decision. This interface declares no writing method
 * of any kind, so the failure mode has no surface here to appear on. </p>
 *
 * <p>Assumptions: this role is a collaborator and not a job. {@code app/cbl/CBSTM03B.CBL} is a
 * subprogram, declaring {@code PROCEDURE DIVISION USING LK-M03B-AREA.} at L114, and it owns all four
 * input definitions at L58, L65, L70 and L75 while {@code app/cbl/CBSTM03A.CBL} declares none of
 * them -- only two output definitions at L44 through L47. Dispatch is by definition name first,
 * {@code EVALUATE LK-M03B-DD} at L118, and only then by operation code, with support differing per
 * definition. This interface is therefore one of four collaborators the statement generation service
 * holds, alongside the sequential cross-reference traversal and the two keyed lookups; it carries no
 * entry point of its own. </p>
 *
 * <p>Assumptions: a read here yields fully populated typed rows or an empty cursor, never a
 * half-filled buffer a caller must inspect field by field. That is hygiene the reference already
 * practises: each of the thirteen calls is preceded by {@code MOVE ZERO TO WS-M03B-RC.} together
 * with {@code MOVE SPACES TO WS-M03B-FLDT.}, at {@code app/cbl/CBSTM03A.CBL} L349 through L350, L375
 * through L376 and L399 through L400, and each read is followed by a copy into a typed record at
 * L364, L388 and L412, so the generic {@code LK-M03B-FLDT PIC X(1000)} buffer declared at
 * {@code app/cbl/CBSTM03B.CBL} L112 is never read directly. Register entry <b>R13</b> holds this
 * decision. </p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register governing every choice in this package is authored once in
 * {@code package-info.java} beside this file, and rows are cited by identifier rather than restated,
 * as that file directs at its L237 through L238. Every member below carries its own Javadoc because
 * user-specified Rule 1 (Explainability) attaches its presence clause at L15 to every function and
 * names no visibility, and its validation gate at L43 is conjunctive. The four rationale labels are
 * written as Rule 1 writes them at L31 through L34, the form
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its L209 through L212; register entry
 * <b>R14</b> records why they were retyped from the rule rather than copied from the reference
 * suite. </p>
 */
public interface StatementTransactionRepository
        extends Repository<StatementTransactionView, StatementTransactionView.StatementTransactionKey> {

    /**
     * Number of rows the driver is asked to retrieve per network round trip while a cursor is open.
     *
     * <p>Assumptions: the value is an audited anchor rather than a round number chosen freely. It is
     * the measured same-card overrun threshold of the reference statement generator, the figure
     * register entry <b>R9</b> and {@link StatementTransactionView} both record alongside the
     * declared inner arity of 10 at L228 of {@code app/cbl/CBSTM03A.CBL} and the distinct-card limit
     * of 51 at L226 and L232. Anchoring the batch to it means one round trip spans the longest
     * single-card run the reference was ever able to render, and a reader can check the number
     * against a cited measurement instead of taking it on trust. </p>
     *
     * <p>Trade-offs: 512 rows of the 350-byte record this projection maps is what the driver holds at
     * one time under this setting, and a smaller value would cost more round trips over the same
     * relation. What is given up by naming a number at all is that no single value suits every
     * relation; what is bought is that this one is checkable against a measurement rather than
     * taken on trust, which an undeclared batch size never is. It is deliberately not set to either
     * of the reference's declared arities, the 10 of L228 or the 51 of L226 and L232 of
     * {@code app/cbl/CBSTM03A.CBL}, because those bound a declared-arity table and this bounds a
     * retrieval batch -- reusing one as the other would tie two unrelated limits together. </p>
     */
    String STATEMENT_FETCH_SIZE = "512";

    /**
     * The whole-table ordered pass this interface deliberately no longer declares.
     *
     * <p>Refactoring Rationale: an ordered cursor over EVERY row of the projection was declared here,
     * named for the card-then-transaction order {@code app/jcl/CREASTMT.JCL:53} sorts the statement
     * input by, and it was reached by nothing. Its removal is recorded rather than silent because the
     * citation it carried is worth keeping: the reference really does make one sequential pass over a
     * card-ordered file and break by card in working storage, so a reader who expects that shape here
     * is not mistaken about the baseline -- only about this module.</p>
     *
     * <p>Assumptions: the migrated statement flow composes ONE statement per request. Its entry point
     * is {@code StatementService#compose(StatementRequest)}, which names a single card and reaches
     * {@link #streamByCardNumber(String)}; nothing drives an all-cards run, because the state of the
     * nightly chain that would drive one is a batch task rather than a request to this service. A
     * whole-table cursor was therefore a second access path over the same projection with no driver,
     * and the ordering it guaranteed is already guaranteed within each card by the per-card query.</p>
     *
     * <p>Alternatives Considered: keeping the method and giving it a caller by adding an all-cards
     * composition to the statement service. Declined because the orphan would only move one layer up:
     * nothing would drive THAT method either, so the module would gain an untested public surface and
     * the same finding would recur against it. The honest resolution is that this access path arrives
     * with the driver that needs it, and the driver is not in this module.</p>
     */

    /**
     * Opens a forward-only cursor over one card number's rows in transaction-identifier order.
     *
     * <p>This is the equivalent of the control break the reference performs in working storage at
     * L819 through L825 of {@code app/cbl/CBSTM03A.CBL}, where a change of card number closes one
     * card's run and begins the next. Selecting the run in the query instead means a caller
     * composing a single card's statement reads that card's rows and no others. </p>
     *
     * <p>Assumptions: the argument is the card number as this projection exposes it, which
     * {@code data-migration/sql/V1__reporting_views.sql} L298 has already narrowed to twelve
     * asterisks and the last four digits. It therefore selects a rendering, and two distinct cards
     * that share their last four digits are both selected by one call. Every row returned carries the
     * keyed digest projected at L310 of the same file, so a caller establishes from the rows
     * themselves whether one card or several were selected and refuses rather than attributing two
     * cardholders' activity to one document. That obligation is left with the caller deliberately,
     * because this cursor cannot resolve it: the digest is derived inside the relation from the
     * unnarrowed number mixed with a secret whose read privilege L225 withdraws from this
     * module. </p>
     *
     * <p>Alternatives Considered: taking the digest as the argument instead, which would identify
     * exactly one card and remove the collision entirely. Rejected because a caller cannot produce
     * one: it is computed inside the relation at L310 of
     * {@code data-migration/sql/V1__reporting_views.sql} from a value whose read privilege L225 of
     * that same file withdraws from this module, so the digest is only ever available as a value read
     * back on a row and never as one a request can supply. Narrowing a card number, by contrast, is
     * something a caller can do to a value it already holds -- L298 of that file shows the narrowing
     * is idempotent, being twelve literal asterisks and the last four digits -- which is what makes this
     * the only argument a request-time lookup can actually present. The collision is therefore
     * detected on the rows rather than prevented at the argument. </p>
     *
     * <p>Assumptions: the transaction and batching contracts are the ones the whole-projection cursor
     * above documents -- mandatory propagation so the cursor outlives this call, and the
     * {@value #STATEMENT_FETCH_SIZE}-row batch that only applies outside autocommit -- and both hold
     * identically here because the annotations below are identical. They are cross-referenced rather
     * than restated so that a change to either is made in one place. </p>
     *
     * @param cardNumber the card number as this projection exposes it, being the narrowed rendering
     *     of sixteen characters; must not be {@code null}
     * @return an open, forward-only cursor over the rows carrying that rendering, ordered by
     *     transaction identifier ascending, which is the second of the two keys
     *     {@code app/jcl/CREASTMT.JCL} L53 declares; empty when the rendering matches no row; never
     *     {@code null}. The caller owns the cursor and must close it, for which try-with-resources is
     *     the intended form, and must consume it inside the read-only transaction it requires
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
     *     in progress when this method is called, for the reason the cursor above records
     * @throws org.springframework.dao.DataAccessException if the projection cannot be read, which
     *     includes the relation being absent -- a defect to report against the data-migration
     *     package, as register entry <b>R11</b> records, and never one to work around from here
     */
    @Query("""
            select t
            from StatementTransactionView t
            where t.key.cardNumber = :cardNumber
            order by t.key.transactionId asc
            """)
    @QueryHints({
        @QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = STATEMENT_FETCH_SIZE),
        @QueryHint(name = AvailableHints.HINT_READ_ONLY, value = "true")
    })
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    Stream<StatementTransactionView> streamByCardNumber(@Param("cardNumber") String cardNumber);
}
