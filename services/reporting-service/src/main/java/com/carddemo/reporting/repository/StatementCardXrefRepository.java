package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.CardXrefView;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.stream.Stream;
import org.hibernate.jpa.AvailableHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forward-only cursor over the card cross-reference projection that drives one statement per card.
 *
 * <p>This role stands in for one data definition and one only:
 * {@code //XREFFILE DD  DISP=SHR,DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS} at L84 of
 * {@code app/jcl/CREASTMT.JCL}, one of the four input definitions at L83 through L86 consumed by
 * {@code //STEP040  EXEC PGM=CBSTM03A,COND=(0,NE)} at L79 of the same job. Its rows arrive here as
 * {@link CardXrefView}, the mapping of {@code 01 CARD-XREF-RECORD} at L4 of
 * {@code app/cpy/CVACT03Y.cpy}. </p>
 *
 * <p>Three of the thirteen calls into {@code app/cbl/CBSTM03B.CBL} belong to this definition: the
 * open at L769 of {@code app/cbl/CBSTM03A.CBL} inside {@code 8200-XREFFILE-OPEN.} at L765, the
 * streaming read at L351 inside {@code 1000-XREFFILE-GET-NEXT.} at L345, and the close at L877
 * inside {@code 9200-XREFFILE-CLOSE.} at L873. Open, read, close is the whole of it, which is why
 * the primary surface below is a cursor. </p>
 *
 * <h2>Why this is a cursor rather than a keyed lookup</h2>
 *
 * <p>Assumptions: this definition is sequential-only. {@code app/cbl/CBSTM03B.CBL} L39 declares
 * {@code ACCESS MODE  IS SEQUENTIAL} for it, and its per-definition paragraph at L157 through L179
 * implements exactly three operations -- open at L159 through L162, read at L164 through L168 and
 * close at L170 through L173. There is no keyed arm in that paragraph at all, whereas the two
 * random-access definitions carry one, at L188 through L193 and L213 through L218, and are declared
 * {@code ACCESS MODE  IS RANDOM} at L45 and L51. Register entry <b>R3</b> in
 * {@code package-info.java} beside this file settles the reading with five proofs; this file cites
 * that entry rather than restating it. </p>
 *
 * <p>Assumptions: two readings of {@code LK-M03B-KEY-LN PIC S9(4)}, declared at
 * {@code app/cbl/CBSTM03B.CBL} L111, are on record, and both are named here rather than one being
 * quietly overwritten. A peer brief for {@code services/reporting-service/README.md} reads it as a
 * significant-key length implying a generic browse, which would justify positioning into this
 * relation by key from inside the statement read path. The first-hand COBOL is authoritative and it
 * contradicts that reading, on the same precedent the shared authorization codec contract sets for
 * its own comma defect. Two of the five proofs are worth naming at the point of use: a browse-verb
 * census across all 230 lines of the file returns zero for {@code KEY IS GREATER},
 * {@code READ NEXT}, {@code READNEXT}, {@code READPREV}, {@code STARTBR} and {@code ENDBR}, the
 * only bare {@code START} hit being the paragraph label {@code 0000-START.} at L116; and the caller
 * computes the whole key every time rather than a prefix, {@code app/cbl/CBSTM03A.CBL} L373 through
 * L374 yielding 9 and L397 through L398 yielding 11, which match L6 and L7 of
 * {@code app/cpy/CVACT03Y.cpy} exactly. </p>
 *
 * <p>Assumptions: the paragraph names in the reference separate the two shapes by themselves, which
 * is corroborating rather than decisive evidence. The sequential definition is driven from
 * {@code 1000-XREFFILE-GET-NEXT.} at L345 of {@code app/cbl/CBSTM03A.CBL}, while the two
 * random-access definitions are driven from {@code 2000-CUSTFILE-GET.} at L368 and
 * {@code 3000-ACCTFILE-GET.} at L392 -- a traversal against two lookups. </p>
 *
 * <h2>Why exhaustion of this cursor ends the run</h2>
 *
 * <p>Assumptions: this traversal is what terminates the statement run, and the proof is an asymmetry
 * in the caller rather than a comment anywhere. {@code app/cbl/CBSTM03A.CBL} L353 through L362
 * evaluates the return code of this definition's read with three arms: L354 through L355 continue
 * normally, L356 through L357 carry {@code WHEN '10' MOVE 'Y' TO END-OF-FILE}, and L358 through
 * L361 display {@code 'ERROR READING XREFFILE'} with the return code and then abend. It is the only
 * one of the three statement read paragraphs with an end-of-file arm at all: the customer read at
 * L379 through L386 and the account read at L403 through L410 have none, so their only non-normal
 * arm reaches {@code 9999-ABEND-PROGRAM.} at L921, whose two-statement body at L922 through L923
 * displays and then calls the language-environment abend service. </p>
 *
 * <p>Assumptions: every row this cursor yields is asserted to have both a customer and an account,
 * which is a contract on the caller and not a hope about the data. Register entry <b>R10</b> records
 * the policy and why a plain inner join alone was rejected for it; the hazard is worth stating
 * plainly at the point of use, because it is silent. A naive join would <b>discard</b> a
 * cross-reference row whose customer or account is absent, and the run would then produce different
 * bytes and different totals rather than a failure -- which is the class of defect the golden
 * masters exist to catch. A downstream empty result for a row this cursor yielded is therefore a
 * referential-integrity violation to raise on, never a benign miss to pass over. The missing-member
 * policy itself belongs to the two keyed roles beside this one and to the statement service; this
 * interface's obligation is only to state what its rows guarantee. </p>
 *
 * <h2>Why the ordering is the card number ascending</h2>
 *
 * <p>Assumptions: the reference reads this cluster in key sequence and the key is the card number,
 * declared {@code RECORD KEY   IS FD-XREF-CARD-NUM} at L40 of {@code app/cbl/CBSTM03B.CBL} over the
 * record at L65 through L68, whose two components are {@code FD-XREF-CARD-NUM PIC X(16)} at L67 and
 * {@code FD-XREF-DATA PIC X(34)} at L68 -- 50 bytes, which is the length L2 of
 * {@code app/cpy/CVACT03Y.cpy} declares. The ordering is therefore settled by the reference rather
 * than chosen here, which is why it is written into the query below and is not a caller-supplied
 * argument. </p>
 *
 * <p>Assumptions: the ordering matters beyond this relation because it is shared with the
 * transaction traversal the same run consumes. {@code app/jcl/CREASTMT.JCL} L53 declares
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}, two ascending keys, of which the leading one is the
 * card number. Because both streams lead on that same key, a caller can advance the two together
 * and match each cross-reference row to its card's transactions without holding either stream in
 * memory. Ordering this traversal any other way would forfeit that property and force the whole of
 * one side to be held before the other could be walked. </p>
 *
 * <h2>How the identifier types were derived</h2>
 *
 * <p>Assumptions: the member types are derived from the record declaration by rule and are not
 * copied from anywhere. {@code app/cpy/CVACT03Y.cpy} L5 declares
 * {@code XREF-CARD-NUM PIC X(16)}, a constant-width code used as the key, so it becomes a
 * 16-character column and a {@link String}; L6 declares {@code XREF-CUST-ID PIC 9(09)} and L7
 * declares {@code XREF-ACCT-ID PIC 9(11)}, both numeric identities, so both become 64-bit integer
 * columns and {@link Long}. The trailing {@code FILLER PIC X(14)} at L8 is padding to the 50-byte
 * record length and <b>is dropped</b>, which is recorded here because naming a dropped field is
 * required of every record this migration carries across rather than being a courtesy. The
 * projection carries no
 * monetary member at all, so no exact-decimal member arises on this relation. </p>
 *
 * <h2>Why the marker interface and not a wider base</h2>
 *
 * <p>Alternatives Considered: extending {@code CrudRepository} or {@code JpaRepository}. Either
 * would inherit {@code save}, {@code saveAll}, {@code delete}, {@code deleteAll} and
 * {@code deleteById} onto the public surface of a type whose entire contract is that it has no write
 * path, and L562 of {@code data-migration/sql/V1__reporting_views.sql} records that no insert,
 * update, delete or truncate privilege exists on any of these relations -- so those five methods
 * would compile, appear in every completion list, and fail at the database. The marker base declares
 * nothing, so only the two methods below exist. </p>
 *
 * <h2>Reading across a schema boundary</h2>
 *
 * <p>Trade-offs: this context reads a relation it does not own, and the compromise is accepted
 * rather than worked around. The underlying cross-reference table belongs to the account context --
 * L528 of {@code data-migration/sql/V1__reporting_views.sql} names it as the schema-qualified
 * relation {@code account.card_xref} -- and the target design records this context's owned tables as
 * "(none)". What this module reads instead is the projection declared at L522 of that same file,
 * {@code reporting.v_card_xref}, standing behind the security barrier its L523 sets, assigned to the
 * no-login owner role at L535, with read privilege conveyed to this module's login role by name at
 * L560. Its L525 narrows the card number to twelve asterisks and the last four digits, cast to a
 * 16-character column, before this module ever sees it. </p>
 *
 * <p>Trade-offs: cross-schema reach is therefore by database read privilege alone, <b>never by a
 * Maven dependency on a sibling service module</b>, and the only intra-reactor dependency this
 * module declares is the shared kernel. The cost of that arrangement is that this package cannot
 * supply its own read surface: when the projection is absent at run time the only correct action
 * from here is to report a defect against the data-migration package, as register entry <b>R11</b>
 * records, because declaring the same object in two places would give it two owners and the two
 * would drift. What is bought in exchange is that a write issued from here fails at the database
 * rather than succeeding against data another context is accountable for. </p>
 *
 * <p>Trade-offs: this interface neither creates nor removes the {@code idx_card_xref_account_id}
 * index. That index belongs to the account context, where it stands in for the account-path
 * alternate access route the reference surfaced to the online region, and register entry <b>R8</b>
 * records that what retires in the migration is the separate rebuild step rather than the access
 * route itself. Reads also go to the writer rather than to a replica: register entry <b>R12</b>
 * carries the target design's own ground for that, which is that "a replica adds cost and
 * replica-lag semantics for no parity benefit". </p>
 *
 * <h2>What this interface is, and what it is not</h2>
 *
 * <p>Assumptions: this is a collaborator injected into the statement generation service, and it is
 * neither a job nor a service class with an entry point of its own.
 * {@code app/cbl/CBSTM03B.CBL} is a subprogram, declaring
 * {@code PROCEDURE DIVISION USING LK-M03B-AREA.} at L114, and it owns all four input file
 * definitions, at L58, L65, L70 and L75, while {@code app/cbl/CBSTM03A.CBL} declares none of them --
 * only two output definitions at L44 through L47. Dispatch there is by data-definition name first,
 * {@code EVALUATE LK-M03B-DD} at L118, and only then by operation code, with per-definition support
 * that is asymmetric. This interface is one of the four roles that together discharge that one
 * subprogram. </p>
 *
 * <p>Refactoring Rationale: the code being replaced returns a value that is not an answer, so an
 * unsupported request raises here instead. {@code app/cbl/CBSTM03B.CBL} L103 through L108 declares
 * six operation codes as condition names, but write and rewrite are implemented in no paragraph -- a
 * census for their two condition names across the file returns exactly two hits, the L107 and L108
 * declarations themselves. Such a request therefore falls through every condition in its
 * per-definition paragraph and lands on that paragraph's exit, whose only act for this definition is
 * {@code MOVE XREFFILE-STATUS TO LK-M03B-RC} at L176, handing back the status of the <i>previous</i>
 * operation on the file; an unrecognised definition name is worse still, because {@code WHEN OTHER}
 * at L127 through L128 branches straight to the goback and leaves the return code never assigned.
 * Register entry <b>R4</b> records the decision. Because this module has no write path, no method
 * exists below for such a request to fall through in the first place. </p>
 *
 * <p>Assumptions: a query here yields a fully populated projection or nothing, which is hygiene the
 * reference already practises rather than something invented for the migration. The read at L351 of
 * {@code app/cbl/CBSTM03A.CBL} is preceded by the clear-before pair
 * {@code MOVE ZERO TO WS-M03B-RC.} and {@code MOVE SPACES TO WS-M03B-FLDT.} at L349 through L350
 * and followed by the copy-after {@code MOVE WS-M03B-FLDT TO CARD-XREF-RECORD.} at L364, so the
 * generic 1000-byte buffer declared at L112 of {@code app/cbl/CBSTM03B.CBL} is never read directly.
 * Register entry <b>R13</b> records the equivalent obligation: an empty stream or an empty optional,
 * never a half-filled instance a caller must inspect member by member to learn whether the read
 * succeeded. </p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register governing every choice in this package is authored once in
 * {@code package-info.java} beside this file, and entries are cited by identifier rather than
 * restated. Every member below carries a docstring because user-specified Rule 1 (Explainability)
 * attaches its presence clause at L15 to every function and names no visibility, and each carries
 * purpose, its parameters, its return value and the exceptions it propagates because L18 through L21
 * enumerate those elements and L43 makes the first three a gate. The written convention is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}; the four category labels are written character for
 * character as Rule 1 writes them at L31 through L34, retyped from the rule for the byte-level
 * reason register entry <b>R14</b> records. </p>
 */
public interface StatementCardXrefRepository extends Repository<CardXrefView, String> {

    /**
     * Rows the driver holds at one time while walking this relation, as a retrieval batch size.
     *
     * <p>Trade-offs: naming a number at all means no single value suits every relation, and what is
     * bought is that this one is checkable against a measurement. A row of this projection is the
     * 50-byte record {@code app/cpy/CVACT03Y.cpy} declares at L5 through L8, being 16 plus 9 plus 11
     * plus 14, and L517 through L521 of {@code data-migration/sql/V1__reporting_views.sql} record
     * that the relation carries one row per card, so a batch of this size holds on the order of
     * thirteen kilobytes at once while keeping the number of round trips over a whole-relation walk
     * low. A smaller value would cost more round trips over the same relation for no reduction that
     * matters at this row width. </p>
     *
     * <p>Assumptions: this is deliberately none of the three numbers the reference declares or
     * exhibits, and the distinction is the point rather than a detail. Those three are the declared
     * inner arity of 10 at L228 of {@code app/cbl/CBSTM03A.CBL}, the distinct-card limit of 51 at
     * L226 and L232 of the same file, and the separately measured same-card overrun threshold; they
     * bound declared-arity tables in working storage, whereas this bounds a retrieval batch. Reusing
     * one as the other would tie two unrelated limits together, so that a change to either would
     * silently move the other. </p>
     */
    String XREF_FETCH_SIZE = "256";

    /**
     * Opens a forward-only cursor over every row of the projection, in ascending card-number order.
     *
     * <p>This is the traversal {@code 1000-XREFFILE-GET-NEXT.} at L345 of
     * {@code app/cbl/CBSTM03A.CBL} performs one row at a time, and it is the driving cursor of a
     * whole statement run: one row reached is one statement produced, and exhaustion of this cursor
     * is what ends the run, for the reason the end-of-file arm at L356 through L357 of that file
     * establishes. </p>
     *
     * <p>Assumptions: the run this cursor drives has a declared driver in this module rather than
     * being an access route without one. {@code ReportingTaskRunner} declares
     * {@code generate-statements} as the first of the three accepted job tokens at L120 through
     * L121, and records at L112 through L114 that it is driven by the daily chain's
     * {@code GenerateStatements} state, which is the state standing in for
     * {@code //STEP040  EXEC PGM=CBSTM03A,COND=(0,NE)} at L79 of {@code app/jcl/CREASTMT.JCL}. That
     * token is a target contract at L106 through L111 of the runner, so the driver is a declared
     * obligation of this module and not of another. </p>
     *
     * <p>Alternatives Considered: returning the whole relation as a list instead. Rejected on arity
     * grounds rather than on throughput. {@code app/cbl/CBSTM03A.CBL} declares two independent
     * working-storage tables that are not bounds-checked: {@code 05 WS-CARD-TBL OCCURS 51 TIMES.} at
     * L226 with the nested {@code 10 WS-TRAN-TBL OCCURS 10 TIMES.} at L228, and separately
     * {@code 05 WS-TRN-TBL-CTR OCCURS 51 TIMES.} at L232. Three numbers must be kept apart: 10 is
     * the declared inner arity at L228, 51 is the declared distinct-card limit at L226 and L232, and
     * the same-card overrun threshold measured against the reference is a third and much larger
     * number. There is no one combined ceiling, and reading the distinct-card figure as a
     * transaction count would mislabel two separate limits as one. Streaming at this boundary is
     * what lets the migrated statement path carry no arity ceiling of its own: the reference
     * materialises a run into declared-width tables, the Java holds one row at a time behind the
     * retrieval batch, and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} as divergence D-2. </p>
     *
     * <p>Assumptions: the key this traversal walks is the narrowed rendering rather than the stored
     * number, because L525 of {@code data-migration/sql/V1__reporting_views.sql} narrows it to
     * twelve asterisks and the last four digits before this module sees it. Two consequences follow
     * and both are load-bearing. The sequence is still total and still deterministic, since the
     * twelve leading asterisks are constant, so a rerun over the same data walks the same rows in
     * the same sequence and a golden-master comparison stays meaningful. And the co-ordering with
     * the transaction traversal survives the narrowing, because L509 through L511 of that same file
     * record that this column is narrowed by the same expression the two transaction projections
     * use, so both streams still lead on one common key. </p>
     *
     * <p>Assumptions: the cursor requires an enclosing transaction rather than starting one of its
     * own, so that it outlives the call that opened it; a cursor that closed with its own
     * transaction would be exhausted before the caller read a row. The retrieval batch declared at
     * {@link #XREF_FETCH_SIZE} applies only outside autocommit, which is the same condition. The
     * calling pattern this expects is already established in this module: {@code StatementService}
     * declares the read-only transaction at its L242 and consumes the sibling cursor inside
     * try-with-resources at its L290 through L292. </p>
     *
     * <p>Refactoring Rationale: two keyset-continuation methods returning bounded lists stood here
     * and are replaced by this one cursor. They were reached by nothing -- the only production call
     * into this interface anywhere in the module is the keyed read at L261 of
     * {@code StatementService}, so neither had a caller or an assertion -- and they expressed a
     * whole-relation walk as successive bounded requests, which for a driving traversal means the
     * ordering guarantee at L53 of {@code app/jcl/CREASTMT.JCL} has to be re-established on every
     * request rather than held open once. A single cursor states that guarantee once, and the
     * removal is recorded rather than left silent because the shape they carried is a reasonable one
     * for a caller-driven browse and is simply not what this traversal is. </p>
     *
     * @return an open, forward-only cursor over every row of the projection, ordered by the narrowed
     *     card number ascending, which is the leading key of the two
     *     {@code app/jcl/CREASTMT.JCL} L53 declares; empty when the projection holds no row; never
     *     {@code null}. The caller owns the cursor and must close it, for which try-with-resources
     *     is the intended form, and must consume it inside the read-only transaction it requires
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
     *     in progress when this method is called, which the mandatory propagation below enforces so
     *     that the cursor cannot be opened into a scope that closes underneath it
     * @throws org.springframework.dao.DataAccessException if the projection cannot be read, which
     *     includes the relation being absent -- a defect to report against the data-migration
     *     package, as register entry <b>R11</b> records, and never one to work around from here
     */
    @Query("""
            select x
            from CardXrefView x
            order by x.cardNum asc
            """)
    @QueryHints({
        @QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = XREF_FETCH_SIZE),
        @QueryHint(name = AvailableHints.HINT_READ_ONLY, value = "true")
    })
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    Stream<CardXrefView> streamAllInCardNumberOrder();

    /**
     * Reads the cross-reference row carrying one narrowed card number.
     *
     * <p>Refactoring Rationale: the prose formerly attached to this method attributed it to the
     * report path, stating that a report line's account identifier is resolved through it. That
     * attribution is replaced rather than left standing, because it is not what the module does:
     * {@code TransactionReportRepository} resolves the cross-reference inside its own join, at L215
     * and L257 of that file, and reaches this method never. The one caller is the on-demand
     * statement path, {@code StatementService#compose(StatementRequest)}, which names a single card
     * instead of walking the relation. </p>
     *
     * <p>Assumptions: a keyed single-row read is retained here even though the data definition this
     * interface stands in for is sequential-only, and both readings are named rather than one being
     * applied silently. The sequential-only reading is correct for the whole-relation run and is the
     * whole reason the cursor above exists: L157 through L179 of {@code app/cbl/CBSTM03B.CBL} carry
     * no keyed arm for this definition. The keyed shape has independent first-hand authority over
     * the same relation from a different program, {@code app/cbl/CBTRN03C.cbl} looking the
     * cross-reference up by key at {@code 1500-A-LOOKUP-XREF} L484 through L492, which register entry
     * <b>R3</b> and the two-join analysis in {@code package-info.java} both record as the second of
     * the two access shapes this one relation is reached through. </p>
     *
     * <p>Alternatives Considered: withdrawing this method so that the cursor above is the only
     * surface. Declined because the on-demand caller would then walk every row ahead of the card it
     * was asked for, and bounding that walk would report a statement with no activity that a reader
     * cannot tell apart from a genuinely empty one -- which is the reasoning {@code StatementService}
     * records at its L273 through L277, at the point it opens its own per-card cursor. A single-card
     * request is a lookup, and expressing it as a traversal would make the answer depend on where the
     * card happens to sit in the ordering. </p>
     *
     * <p>Trade-offs: the return is one optional even though the narrowed value is not guaranteed
     * unique, since L525 of {@code data-migration/sql/V1__reporting_views.sql} keeps only the last
     * four digits and two cards sharing those four collide under one rendering. A collision makes
     * this lookup ambiguous and the underlying read raises rather than selecting one of them. That
     * is the correct failure: selecting a row would attribute one cardholder's statement to another
     * whenever the engine happened to return that row first, and the document would be wrong with
     * nothing recording why. </p>
     *
     * @param cardNum the card number as this projection exposes it, being the narrowed rendering of
     *     sixteen characters; must not be {@code null}
     * @return the cross-reference row carrying that rendering, or an empty optional when the
     *     projection holds no such row; never {@code null}
     * @throws org.springframework.dao.IncorrectResultSizeDataAccessException if two cards collide
     *     under one narrowed rendering, so that the read matches more than a single row
     * @throws org.springframework.dao.DataAccessException if the projection cannot be read, which
     *     includes the relation being absent -- a defect to report against the data-migration
     *     package, as register entry <b>R11</b> records, and never one to work around from here
     */
    Optional<CardXrefView> findByCardNum(String cardNum);
}
