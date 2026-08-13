package com.carddemo.reporting.repository;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.CardXrefView;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.hibernate.jpa.AvailableHints;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
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
 * path, and {@code data-migration/sql/V1__reporting_views.sql} records that no insert, update,
 * delete or truncate privilege exists on any of these relations: its closing
 * {@code REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ... FROM carddemo_reporting}
 * names all seven of them, and the only privilege conveyed to this module's login role is the
 * {@code GRANT SELECT ON reporting.v_card_xref TO carddemo_reporting} beside it. So those five
 * methods would compile, appear in every completion list, and fail at the database. The marker base
 * declares nothing, so only the two methods below exist. </p>
 *
 * <h2>Reading across a schema boundary</h2>
 *
 * <p>Trade-offs: this context reads a relation it does not own, and the compromise is accepted
 * rather than worked around. The underlying cross-reference table belongs to the account context --
 * the {@code CREATE VIEW reporting.v_card_xref} statement of
 * {@code data-migration/sql/V1__reporting_views.sql} selects {@code FROM account.card_xref AS x} --
 * and the target design records this context's owned tables as "(none)". What this module reads
 * instead is that projection, {@code reporting.v_card_xref}, standing behind the
 * {@code WITH (security_barrier = true)} its declaration carries, assigned to the no-login owner
 * role by {@code ALTER VIEW reporting.v_card_xref OWNER TO carddemo_reporting_owner}, with read
 * privilege conveyed to this module's login role by name through
 * {@code GRANT SELECT ON reporting.v_card_xref TO carddemo_reporting}. Its first select item,
 * {@code ('************' || right(rtrim(x.card_num), 4))::character(16) AS card_num}, narrows the
 * card number to twelve asterisks and the last four digits, cast to a 16-character column, before
 * this module ever sees it. </p>
 *
 * <p>Refactoring Rationale: these citations name STATEMENTS and IDENTIFIERS where they previously
 * named line numbers in that file. The numbers had gone stale -- they addressed a comment about the
 * credit score, a comment about the reporting role's reach and an owner assignment on a different
 * view -- and a stale citation is worse than none, because a reader who follows it and finds
 * unrelated text cannot tell whether the claim or the pointer is wrong. A statement a reader can
 * search for survives every edit above it. </p>
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
     * plus 14, and the relation carries one row per card: the
     * {@code CREATE VIEW reporting.v_card_xref} statement of
     * {@code data-migration/sql/V1__reporting_views.sql} selects
     * {@code FROM account.card_xref AS x CROSS JOIN reporting.card_grouping_key AS k}, and that
     * second relation is constrained to a single row by its
     * {@code CONSTRAINT ck_card_grouping_key_singleton CHECK (singleton)} over a boolean primary
     * key -- so the cross join multiplies the cross-reference by exactly one. A batch of this size
     * therefore holds on the order of thirteen kilobytes at once while keeping the number of round
     * trips over a whole-relation walk low. A smaller value would cost more round trips over the
     * same relation for no reduction that matters at this row width. </p>
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
     * <p>Refactoring Rationale: the ordering is the narrowed rendering and then the per-card
     * fingerprint, where it was the narrowed rendering alone, and the claim that accompanied the
     * single key was false. It read: "The sequence is still total and still deterministic, since the
     * twelve leading asterisks are constant." The twelve asterisks being constant is exactly what
     * makes the sequence NOT total -- it reduces the key to four digits, and two cards sharing those
     * four occupy one position with no defined order between them, so two runs over identical data
     * could emit two cardholders' statements in either sequence and a golden-master comparison would
     * fail intermittently on data that had not changed. The fingerprint is unique per card, so
     * appending it makes the ordering total, which is what the original sentence claimed and did not
     * deliver. Ordering on the whole card number, which is what L53 of
     * {@code app/jcl/CREASTMT.JCL} declares, is not available to this module at all -- no relation it
     * may read publishes that value -- and that divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} rather than papered over here. </p>
     *
     * <p>Assumptions: the cursor requires an enclosing transaction rather than starting one of its
     * own, so that it outlives the call that opened it; a cursor that closed with its own
     * transaction would be exhausted before the caller read a row. The retrieval batch declared at
     * {@link #XREF_FETCH_SIZE} applies only outside autocommit, which is the same condition. The
     * calling pattern this expects is already established in this module: {@code StatementService}
     * declares the read-only transaction at its L242 and consumes the sibling cursor inside
     * try-with-resources at its L290 through L292. </p>
     *
     * <p>Alternatives Considered: keyset-continuation methods returning bounded lists, which is the
     * right shape for a caller-driven browse and is not what this traversal is. Rejected because a
     * whole-relation walk expressed as successive bounded requests has to re-establish the ordering
     * guarantee at L53 of {@code app/jcl/CREASTMT.JCL} on every request rather than holding it open
     * once, and because the driving traversal has no caller to hold a cursor position on its behalf. A
     * single cursor states that guarantee once. </p>
     *
     * @return an open, forward-only cursor over every row of the projection, ordered by the narrowed
     *     card number ascending and then by the per-card fingerprint ascending, which together make a
     *     total order over the leading key of the two {@code app/jcl/CREASTMT.JCL} L53 declares; empty
     *     when the projection holds no row; never {@code null}. The caller owns the cursor and must close it, for which try-with-resources
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
            order by x.cardNum asc, x.cardFingerprint asc
            """)
    @QueryHints({
        @QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = XREF_FETCH_SIZE),
        @QueryHint(name = AvailableHints.HINT_READ_ONLY, value = "true")
    })
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    Stream<CardXrefView> streamAllInCardNumberOrder();

    /**
     * Resolves one WHOLE card number to its cross-reference row, exactly.
     *
     * <p>Assumptions: resolution takes the WHOLE card number, and a predicate on the NARROWED
     * rendering is forbidden here rather than merely discouraged. That rendering is twelve constant
     * asterisks followed by four digits, so a predicate on it names a tail rather than a card, and both
     * outcomes it can produce are wrong while neither is visible. Where the requested card and a
     * different cardholder's card share a tail and both exist, the read matches two rows and raises,
     * refusing a legitimate request. Where the requested card does NOT exist but another card with the
     * same tail does, the read matches exactly one row and returns it, so the caller receives somebody
     * else's customer, somebody else's account and, downstream, somebody else's statement, with nothing
     * recording that a substitution happened -- a broken-object-selection defect rather than a
     * collision-handling nicety. </p>
     *
     * <p>Assumptions: the resolution is delegated to {@code reporting.resolve_card}, declared in
     * {@code data-migration/sql/V1__reporting_views.sql}, rather than expressed as a predicate here.
     * It has to be: no relation this module may read publishes the whole card number, so no query this
     * interface can compose selects a single card. The function is definer-rights, owned by the
     * barrier role, pinned to an explicit {@code search_path}, and has EXECUTE granted to this
     * module's login role alone. It returns the same four columns the projection publishes, so the
     * result maps onto the same entity. </p>
     *
     * <p>Assumptions: this is a native query because the function is a relation-valued call, which
     * the persistence query language has no syntax for, and the cast on the argument is required
     * rather than defensive: the parameter would otherwise be bound as an untyped placeholder that
     * PostgreSQL cannot resolve against a single-signature function. </p>
     *
     * <p>Alternatives Considered: adding a plain {@code card_fingerprint_for(text)} helper so that the
     * service could compute the token for a card and then use the ordinary keyed read. Rejected in the
     * migration itself and the reason is recorded there: exposing a forward oracle over a value that is
     * published only in masked form makes recovering the whole number a feasible search rather than a
     * guess. This function answers nothing for a card that does not exist, so it discloses a
     * fingerprint only to a caller that already held the number it belongs to. </p>
     *
     * <p>Alternatives Considered: withdrawing single-card resolution altogether so that the cursor
     * above is the only surface. Declined because the on-demand caller
     * would walk every row ahead of the card it was asked for, and bounding that walk would report a
     * statement with no activity that a reader cannot tell apart from a genuinely empty one. A
     * single-card request is a lookup, and expressing it as a traversal would make the answer depend
     * on where the card happens to sit in the ordering. The keyed shape also has independent
     * first-hand authority: {@code app/cbl/CBTRN03C.cbl} looks the cross-reference up by key at
     * {@code 1500-A-LOOKUP-XREF} L484 through L492, which register entry <b>R3</b> records as the
     * second of the two access shapes this one relation is reached through. </p>
     *
     * <p>Assumptions: the return is an optional over at most one row, and that is a guarantee rather
     * than a hope. The function's predicate is an equality on the whole trimmed number against a
     * relation whose own primary key is that number, so two matching rows are unrepresentable and no
     * incorrect-result-size condition can arise. </p>
     *
     * @param cardNumber the WHOLE primary account number to resolve, trimmed or padded either way
     *     because the function trims both sides of its comparison; must not be {@code null}
     * @return the cross-reference row for exactly that card, or an empty optional when no card with
     *     that number exists; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the function cannot be executed, which
     *     includes it being absent or EXECUTE not being granted -- a defect to report against the
     *     data-migration package, as register entry <b>R11</b> records, and never one to work around
     *     from here
     */
    @Query(value = """
            select r.card_num, r.card_fingerprint, r.customer_id, r.account_id
            from reporting.resolve_card(cast(:cardNumber as varchar)) as r
            """, nativeQuery = true)
    @Transactional(readOnly = true)
    Optional<CardXrefView> resolveByWholeCardNumber(@Param("cardNumber") String cardNumber);

    /**
     * Reads the cross-reference row carrying one per-card fingerprint.
     *
     * <p>Assumptions: the fingerprint is the declared identifier of {@link CardXrefView}, so this is the
     * relation's keyed read and yields at most one row by construction. It exists beside
     * {@link #resolveByWholeCardNumber(String)} because the two answer different questions: that method
     * turns a whole card number into an identity, and this one reads a row for an identity already held.
     * </p>
     *
     * @param cardFingerprint the keyed per-card fingerprint; must not be {@code null}
     * @return the cross-reference row for that card, or an empty optional when none exists; never
     *     {@code null}
     * @throws org.springframework.dao.DataAccessException if the projection cannot be read, which
     *     includes the relation being absent -- a defect to report against the data-migration package,
     *     as register entry <b>R11</b> records, and never one to work around from here
     */
    @Transactional(readOnly = true)
    Optional<CardXrefView> findById(String cardFingerprint);

    /**
     * Reads the cards of one account, bounded by the caller.
     *
     * <p>Purpose: serves the request path's account selector, which the published contract offers as the
     * alternative to a card number.</p>
     *
     * <p>Assumptions: the caller supplies the bound, and the request path supplies TWO where it wants
     * one. Reading one row could not tell an account holding a single card from an account holding
     * several, and a statement is a per-card document -- {@code app/cbl/CBSTM03A.CBL} produces one per
     * cross-reference row -- so an account with several cards has several statements and must be refused
     * rather than answered from whichever row came back first. This is the same look-ahead device the
     * reference uses to discover a further page at {@code app/cbl/COCRDLIC.cbl} L1197.</p>
     *
     * <p>Assumptions: the ordering is by fingerprint, which is total and stable, so a refusal is
     * reproducible and a single-card account always answers with the same row. Ordering by the masked
     * rendering would leave two colliding cards of one account in an undefined order.</p>
     *
     * @param accountId the account whose cards are wanted; must not be {@code null}
     * @param bound the greatest number of rows to return; must not be {@code null}
     * @return the account's cards in fingerprint order, at most {@code bound} of them, empty when the
     *     account holds none; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the projection cannot be read
     */
    @Query("""
            select x
            from CardXrefView x
            where x.accountId = :accountId
            order by x.cardFingerprint asc
            """)
    @Transactional(readOnly = true)
    List<CardXrefView> findCardsOfAccount(@Param("accountId") Long accountId, Limit bound);

    /**
     * Reads one bounded chunk of statement heading rows, each card joined to its customer and account.
     *
     * <p>Purpose: supplies the whole-run statement generator with everything a statement heading needs
     * in ONE query per chunk, instead of one query per card per dimension.</p>
     *
     * <p>Refactoring Rationale: the generator walked the ordered cursor above and, for every row it
     * produced, issued a keyed read for the customer and another for the account -- so a run over
     * {@code N} cards executed {@code 1 + 3N} statements and held the outer cursor open across every
     * one of them, including across the object-store writes each statement performed. Two separate
     * costs followed. The query count is the visible one. The open cursor is the serious one: a
     * forward-only cursor pins a database transaction for the whole run, so the run's transaction
     * lifetime became the run's wall-clock time including all of its network writes. Reading heading
     * rows in bounded chunks removes both -- each chunk is one statement inside one short transaction
     * that has ended before any artifact is written.</p>
     *
     * <p>Assumptions: both joins are OUTER joins and not inner ones, and the difference is
     * behavioural rather than stylistic. {@code app/cbl/CBSTM03A.CBL} reads the customer at
     * {@code 2000-CUSTFILE-GET} L368 and the account at {@code 3000-ACCTFILE-GET} L392 with no
     * not-found arm, and reaches its abend paragraph at L921 when either read fails. An inner join
     * would DROP such a card from the result, so a broken cross-reference would produce a run that
     * silently emitted one statement fewer instead of stopping -- the opposite of the reference's
     * behaviour. An outer join returns the row with null dimension components, which the caller
     * detects and reports as the abend the reference performs.</p>
     *
     * <p>Assumptions: the chunk is positioned by a strict keyset continuation and not by an offset. An
     * offset would skip or repeat a card when a concurrent load inserts a cross-reference row into a
     * chunk already read, and a statement run that skips a card produces no document for a cardholder
     * with no record anywhere of the omission.</p>
     *
     * <p>Assumptions: the ordering is the masked rendering and then the fingerprint, matching the
     * cursor above exactly, so the two access shapes over this relation walk cards in one order.</p>
     *
     * <p>Refactoring Rationale: the continuation is the WHOLE ordering tuple, where it compared the
     * fingerprint alone. The claim that accompanied the single-component predicate was that the
     * fingerprint is unique, so it "names the anchor card exactly" and no second component is needed.
     * Uniqueness is the wrong property: what a keyset predicate has to reproduce is the ORDER, and
     * fingerprint order is not (masked rendering, fingerprint) order. Under the single-component
     * predicate every card whose fingerprint sorted below the anchor's while its masked rendering
     * sorted above it was SKIPPED -- the sequence had not reached it and the predicate had already
     * excluded it -- and every card whose fingerprint sorted above the anchor's while its masked
     * rendering sorted below it was REPEATED in each subsequent chunk. Neither outcome is visible from
     * the run: a skipped cardholder simply has no statement, and a repeated one has two. The tail-level
     * collisions this depends on are ordinary rather than contrived, because the masked rendering is
     * twelve constant asterisks and four digits, so any two cards sharing a tail collide.</p>
     *
     * <p>Alternatives Considered: ordering and paging solely by the fingerprint, which is the other
     * shape that makes a single-component predicate sound. Rejected because the two-key sequence is
     * the one this relation's traversal order is registered as -- the sibling cursor above declares
     * it, both are documented as walking cards in one order, and the divergence from ordering on the
     * whole card number that {@code app/jcl/CREASTMT.JCL} L53 declares is registered against that
     * two-key form. Paging by digest order would emit cardholders in an order derived from a keyed
     * hash, which is neither the reference's order nor reproducible across a rotation of the grouping
     * key.</p>
     *
     * <p>Assumptions: the empty string is the start sentinel for BOTH components and works for both
     * for the same reason -- it sorts below every non-empty value, so the disjunction's first arm
     * admits every row on the opening call. The masked rendering is declared {@code NOT NULL}, so the
     * comparison needs no null arm.</p>
     *
     * @param afterCardNum the masked card rendering of the last card already produced, or the empty
     *     string to start from the beginning, which sorts below every rendering; must not be
     *     {@code null}
     * @param afterFingerprint the fingerprint of the last card already produced, which breaks the tie
     *     among the cards sharing that rendering, or the empty string to start from the beginning,
     *     which sorts below every hexadecimal digest; must not be {@code null}
     * @param limit the greatest number of cards to return in this chunk
     * @return the heading rows for the next cards in order, at most {@code limit} of them, empty when
     *     the relation holds no further card; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read, which
     *     includes any of them being absent -- a defect to report against the data-migration package,
     *     as register entry <b>R11</b> records, and never one to work around from here
     */
    @Query("""
            select x.cardNum as cardNum,
                   x.cardFingerprint as cardFingerprint,
                   x.customerId as customerId,
                   x.accountId as accountId,
                   cu.firstName as firstName,
                   cu.middleName as middleName,
                   cu.lastName as lastName,
                   cu.addressLine1 as addressLine1,
                   cu.addressLine2 as addressLine2,
                   cu.addressLine3 as addressLine3,
                   cu.stateCode as stateCode,
                   cu.countryCode as countryCode,
                   cu.postalCode as postalCode,
                   cu.ficoCreditScore as ficoCreditScore,
                   a.currentBalance as currentBalance
            from CardXrefView x
            left join CustomerView cu on cu.customerId = x.customerId
            left join AccountView a on a.accountId = x.accountId
            where x.cardNum > :afterCardNum
               or (x.cardNum = :afterCardNum and x.cardFingerprint > :afterFingerprint)
            order by x.cardNum asc, x.cardFingerprint asc
            limit :limit
            """)
    @QueryHints(@QueryHint(name = AvailableHints.HINT_READ_ONLY, value = "true"))
    @Transactional(readOnly = true)
    List<StatementHeadingRow> findHeadingChunk(
            @Param("afterCardNum") String afterCardNum,
            @Param("afterFingerprint") String afterFingerprint,
            @Param("limit") int limit);

    /**
     * One card's statement heading: the card, its customer's printed attributes and its account balance.
     *
     * <p>Assumptions: this is a closed interface projection, so the provider builds an implementation
     * from the aliases the query declares. A record was rejected for the reason the report projection
     * records: a constructor expression binds by position, so reordering fifteen select items would
     * compile and silently move one customer's postal code into another component.</p>
     *
     * <p>Assumptions: the customer and account components are nullable even though their base columns
     * are not, because the joins are outer joins. A null means the cross-reference names a dimension row
     * that does not exist, which the caller reports as the abend {@code app/cbl/CBSTM03A.CBL} performs
     * at L921 rather than rendering a statement around the gap.</p>
     */
    interface StatementHeadingRow {

        /**
         * Returns the masked rendering of the card this statement is for.
         *
         * @return twelve asterisks and the last four digits, at the declared width of
         *     {@value CardXrefView#CARD_NUMBER_WIDTH}; never {@code null}
         */
        String getCardNum();

        /**
         * Returns the keyed per-card fingerprint identifying this card exactly.
         *
         * @return the sixty-four-character digest, which is both the chunk continuation key and the
         *     selector for this card's transactions; never {@code null}
         */
        String getCardFingerprint();

        /**
         * Returns the customer the card belongs to, as the cross-reference names them.
         *
         * @return the customer identifier declared {@code XREF-CUST-ID PIC 9(09)} at
         *     {@code app/cpy/CVACT03Y.cpy} L6; never {@code null}
         */
        Long getCustomerId();

        /**
         * Returns the account the card is issued against, as the cross-reference names it.
         *
         * @return the account identifier declared {@code XREF-ACCT-ID PIC 9(11)} at
         *     {@code app/cpy/CVACT03Y.cpy} L7; never {@code null}
         */
        Long getAccountId();

        /**
         * Returns the customer's first name.
         *
         * @return the first of the three name parts, or {@code null} when the joined customer row is
         *     absent
         */
        String getFirstName();

        /**
         * Returns the customer's middle name.
         *
         * @return the second name part, which is {@code null} both when the customer carries none and
         *     when the joined customer row is absent
         */
        String getMiddleName();

        /**
         * Returns the customer's last name.
         *
         * @return the third name part, or {@code null} when the joined customer row is absent
         */
        String getLastName();

        /**
         * Returns the first address line.
         *
         * @return the first address line, or {@code null} when the joined customer row is absent
         */
        String getAddressLine1();

        /**
         * Returns the second address line.
         *
         * @return the second address line, which is {@code null} both when the customer carries none
         *     and when the joined customer row is absent
         */
        String getAddressLine2();

        /**
         * Returns the third address line.
         *
         * @return the third address line, or {@code null} when the joined customer row is absent
         */
        String getAddressLine3();

        /**
         * Returns the state code.
         *
         * @return the two-character state code as stored, or {@code null} when the joined customer row
         *     is absent
         */
        String getStateCode();

        /**
         * Returns the country code.
         *
         * @return the three-character country code as stored, or {@code null} when the joined customer
         *     row is absent
         */
        String getCountryCode();

        /**
         * Returns the postal code.
         *
         * @return the ten-character postal code as stored, or {@code null} when the joined customer row
         *     is absent
         */
        String getPostalCode();

        /**
         * Returns the credit score the heading band prints.
         *
         * @return the three-digit score declared {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at L22 of
         *     {@code app/cpy/CVCUS01Y.cpy}, or {@code null} when the joined customer row is absent
         */
        Short getFicoCreditScore();

        /**
         * Returns the account balance the heading band prints.
         *
         * @return the current balance as an exact decimal at scale two, or {@code null} when the joined
         *     account row is absent
         */
        Money getCurrentBalance();
    }
}
