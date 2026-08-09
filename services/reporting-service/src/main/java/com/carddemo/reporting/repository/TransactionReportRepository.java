package com.carddemo.reporting.repository;

import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reporting.domain.ReportTransactionView;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.hibernate.jpa.AvailableHints;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The report-side query surface, standing in for the four-way join the transaction report program
 * opens.
 *
 * <p>Two access shapes are declared below and they answer two different questions. One is an
 * ordered cursor over a whole selected range, which is what generating the report itself needs. The
 * other is a pair of key-positioned windows, forward and backward, which is what an interactive
 * list of the same lines needs. Nothing else is declared, and nothing here writes. </p>
 *
 * <h2>What this replaces</h2>
 *
 * <p>{@code app/cbl/CBTRN03C.cbl} is 649 lines and opens six file selections: the transaction
 * input at L29, the card cross-reference at L33, the transaction type at L39, the transaction
 * category at L45, the report output at L51 and the date parameters at L55. That is a four-way join
 * plus an output layout plus a parameter record, and <b>this interface owns the four-way join
 * alone</b>. The 133-column output layout belongs to the writing side of this module and the date
 * range arrives here as arguments. The driving job is {@code app/jcl/TRANREPT.jcl}, 84 lines, whose
 * L59 reads {@code //STEP10R EXEC PGM=CBTRN03C}. </p>
 *
 * <h2>The eight columns, and no others</h2>
 *
 * <p>Assumptions: the projected column list is determined by the detail-line assembly rather than
 * chosen. Paragraph {@code 1120-WRITE-DETAIL.} begins at {@code app/cbl/CBTRN03C.cbl} L361, clears
 * its output group at L362 and then moves exactly eight values: the transaction identifier at L363,
 * the <b>cross-reference</b> account identifier at L364, the transaction type code at L365, the
 * transaction type description at L366, the transaction category code at L367, the transaction
 * category description at L368, the transaction source at L369 and the transaction amount at L370.
 * It moves the assembled group to the output record at L371 and advances the line counter at L373.
 * Eight, no more and no fewer. </p>
 *
 * <p>Assumptions: one of those eight cannot come from the driving row, which is the whole reason
 * the cross-reference is a join participant. L364 is {@code MOVE XREF-ACCT-ID TO
 * TRAN-REPORT-ACCOUNT-ID} -- the account identifier is read from the cross-reference record,
 * declared {@code PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy} L7 inside a 50-byte layout, and never
 * from the transaction. Two more come from the two dimension records: the type description declared
 * {@code PIC X(50)} at {@code app/cpy/CVTRA03Y.cpy} L6, and the category description declared
 * {@code PIC X(50)} at {@code app/cpy/CVTRA04Y.cpy} L8, both inside 60-byte layouts. The two
 * descriptions are the same width, so width cannot tell them apart; what tells them apart is that
 * the reference moves them to two different output fields, at L366 and L368. </p>
 *
 * <p>Trade-offs: the projection below carries one column beyond those eight, the card rendering, and
 * it is retrieved for positioning rather than for printing. The reference needs no such column
 * because it holds the card it is on in working storage across its single sequential pass, latched at
 * {@code app/cbl/CBTRN03C.cbl} L185 and compared at L181, whereas a request-time window has no
 * working storage between two requests and has to name its position in the answer it returns. The
 * cost is one 16-character column per row; the alternative was a second query per window purely to
 * recover the position of a row already read. It is not one of the eight the reference prints, so a
 * caller rendering a report line ignores it. </p>
 *
 * <h2>The range is an argument, never a clock read</h2>
 *
 * <p>Assumptions: the reference reads its range from a sequential parameter record rather than from
 * the wall clock, and that property is preserved by passing the range in.
 * {@code app/cbl/CBTRN03C.cbl} L88 declares {@code FD-DATEPARM-REC PIC X(80)}, redefined at
 * L123-L125 as a ten-character start date, a single one-character separator and a ten-character end
 * date -- ten, one, ten, so exactly one separator byte. The 80-byte record size agrees with
 * {@code RECORDSIZE(80)} at {@code app/csd/CARDDEMO.CSD} L502. Parameterising the business dates,
 * here as method arguments supplied by a job parameter or a request field, is what makes a rerun
 * over the same range produce the same rows; reading a clock instead would make yesterday's report
 * unreproducible tomorrow. </p>
 *
 * <p>Assumptions: whether the two bounds are the right way round is the caller's refusal to make,
 * not this interface's. An inverted range simply selects nothing here, which is also what the
 * reference's own inclusive comparison does with one; turning that into a message a client can act
 * on needs a message catalogue and an error code, which
 * {@link com.carddemo.reporting.service.TransactionReportService} holds and a data-access type does
 * not. The reference behaves the same way: its comparison at {@code app/cbl/CBTRN03C.cbl} L173-L174
 * has no inverted-range arm and simply admits no record. </p>
 *
 * <h2>A record-selection condition, not a step gate</h2>
 *
 * <p>Refactoring Rationale: the construct being replaced selects records, and a different construct
 * sharing its keyword gates steps, so translating either as the other inverts a behaviour.
 * {@code app/jcl/TRANREPT.jcl} L47-L48 carries
 * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND, TRAN-PROC-DT,LE,PARM-END-DATE)}, which
 * is a <b>record-selection predicate</b>, inclusive on both bounds and acting on the date part
 * alone; it becomes the {@code where} clause of every query below. The program states the same
 * predicate independently at {@code app/cbl/CBTRN03C.cbl} L173-L174, comparing the first ten
 * characters of the processing timestamp against the two endpoints with the same inclusive
 * operators, so the reading rests on two artefacts that share no code. </p>
 *
 * <p>Trade-offs: the step gates {@code COND=(0,NE)} at {@code app/jcl/CREASTMT.JCL} L56, L66 and
 * L79 look like the same construct and are not. Each states when its step is <b>bypassed</b>, so
 * its sense negates when it is expressed as a condition for running, and a gate of that form
 * belongs to orchestration and never to a query. The cost of saying this in a data-access type is a
 * paragraph about something this file does not implement; it is accepted because the two forms share
 * a keyword and conflating them in either direction silently changes either which rows appear or
 * which steps run. Register entry <b>R5</b> holds the decision. </p>
 *
 * <h2>The date part, and who owns it</h2>
 *
 * <p>Assumptions: the ten-character date part of a 26-character timestamp is owned by
 * {@link TimestampFormatter}, which declares the width as a constant at its L210 and exposes the
 * two accessors for the part itself, {@code datePrefix(String)} at its L601 and
 * {@code toLocalDate(String)} at its L640. <b>No ten-character slice is written locally anywhere in
 * this interface</b>, and none arises at all, because the column being compared is a typed
 * timestamp rather than a character field. {@code app/jcl/TRANREPT.jcl} L42 declares the sort symbol
 * {@code TRAN-PROC-DT,305,10,CH}, and that formatter's contract names that exact line as the only
 * production consumer of the field anywhere in the migration; since L59 of the same job drives the
 * program this surface stands in for, <b>the predicate below is that consumer</b>, which is why the
 * citation is carried here rather than left in the shared kernel. Register entry <b>R7</b> holds the
 * decision. </p>
 *
 * <p>Assumptions: comparing the date part lexically and comparing it as a date agree, and that
 * agreement is what makes the substring predicate and a typed predicate interchangeable. The part is
 * already in year, month, day order, most significant component first, so its character order and
 * its calendar order are the same order -- the ten characters the reference compares at
 * {@code app/cbl/CBTRN03C.cbl} L173 are the leading ten of the 26 declared at
 * {@code app/cpy/CVTRA05Y.cpy} L17. Had the reference rendered it day-first the two would diverge
 * and no typed predicate could reproduce the comparison. </p>
 *
 * <p>Trade-offs: the parity oracle cannot validate that field's punctuation, and claiming otherwise
 * would overstate what the golden masters prove. {@code tests/helpers/golden_compare.py} L25-L26
 * records that its record comparison blanks the run-generated processing timestamp in place, to
 * spaces at its own byte position and keeping its width, while its L22-L23 record the comparison as
 * byte-exact otherwise, so <b>the goldens constrain the field's width and its position only and say
 * nothing about its separators</b>.
 * The punctuation is held by the shared formatter's own unit tests instead, and that is the honest
 * division of evidence. </p>
 *
 * <p>Alternatives Considered: applying a date extraction to the timestamp column, which is the
 * literal transcription of the reference's ten-character comparison. Rejected on two independent
 * grounds. A function wrapped around the column makes the secondary access path over that column
 * unusable, so a one-day report would read every row of the relation rather than the day's rows. And
 * the extraction has to agree with the engine's notion of where a day begins, which introduces a
 * zone question the reference does not have. What is declared instead is a half-open instant range:
 * at or after the first instant of the start date, strictly before the first instant of the day
 * after the end date. That selects exactly the rows an inclusive date range selects, keeps the
 * predicate on the bare column, and has no boundary ambiguity. Both bounds are reduced through
 * {@code TimestampFormatter.normalize(LocalDateTime)} at its L473 so that a bound and a stored value
 * are compared at the one resolution the 26-character contract carries; that method truncates and
 * never rounds, for the measured reason recorded at its L440-L458. </p>
 *
 * <h2>Ordering, and why it is load-bearing beyond output order</h2>
 *
 * <p>Assumptions: the ordering the reference declares is not total, so reproducing it literally
 * would leave the output non-deterministic. {@code app/jcl/TRANREPT.jcl} L46 is
 * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} -- a single key, with no equal-records qualifier -- so the
 * relative order of two rows sharing a card number is whatever the sort happens to produce. Every
 * ordering below therefore places the transaction identifier behind the card number as a stable
 * secondary key. That this is an omission in one job rather than a house style is shown by two of
 * the reference's own sorts already being total: {@code app/jcl/CREASTMT.JCL} L53 declares
 * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}, two keys, and {@code app/jcl/PRTCATBL.jcl} L52
 * declares three. The reference orders on one key and this orders on two; the divergence is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md} rather than absorbed
 * silently. Register entry <b>R6</b> holds the decision. </p>
 *
 * <p>Assumptions: the card ordering is not cosmetic, because the reference's control break depends
 * on it. {@code app/cbl/CBTRN03C.cbl} L181 tests {@code IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM}; on
 * a break it writes the account totals when this is not the first record, at L182-L183, latches the
 * new card at L185 and then performs the cross-reference lookup <b>once per card, from inside the
 * break</b>, at L186-L187. The type and category lookups by contrast fire <b>once per row</b>, at
 * L189-L195. So the grouping is what makes the once-per-card lookup correct: rows arriving out of
 * card order would change both the account totals and the number of lookups performed. </p>
 *
 * <p>Assumptions: the field positions the ordering rests on are cross-checked from two unrelated
 * artefacts. Summing the fourteen declared widths of {@code app/cpy/CVTRA05Y.cpy} L5-L18 gives
 * exactly 350 bytes, placing the card number at zero-based 262 by its L15 and the processing
 * timestamp at zero-based 304 by its L17, with a 20-byte filler at L18. Independently
 * {@code app/jcl/TRANREPT.jcl} L41-L42 declare {@code TRAN-CARD-NUM,263,16,ZD} and
 * {@code TRAN-PROC-DT,305,10,CH} in one-based positions, and {@code app/jcl/TRANIDX.jcl} L27
 * declares {@code KEYS(26 304)} with space-separated operands. All three agree once the single
 * conversion rule is applied, a zero-based position being the one-based position minus one. The rule
 * is stated because the three artefacts genuinely use two conventions and a silent off-by-one moves
 * a key onto the wrong field instead of failing loudly. An artefact observation belongs beside it and
 * is not a defect claim: the same field is typed {@code ZD} at {@code app/jcl/TRANREPT.jcl} L41 and
 * {@code CH} at {@code app/jcl/CREASTMT.JCL} L53. </p>
 *
 * <p>Assumptions: the card number this interface orders and positions on is the narrowed rendering
 * the relation exposes, not the card number itself.
 * {@code data-migration/sql/V1__reporting_views.sql} L256 projects it as twelve asterisks
 * concatenated with the last four digits, cast to a 16-character type, and its L525 narrows the
 * cross-reference the same way. Two consequences follow and both are stated rather than left to be
 * discovered. The order is therefore by last four digits rather than by whole card number, which is
 * a divergence from the reference's sort on the unnarrowed field and is registered in the
 * traceability document. And two distinct cards sharing their last four digits reach this join under
 * one identical rendering, so the join can yield more rows than the driving relation admits --
 * which the reconciliation below detects, because it compares the two counts for equality rather
 * than for a shortfall. </p>
 *
 * <h2>Report banding is not key-based positioning</h2>
 *
 * <p>Alternatives Considered: treating the reference's own output banding as the positioning model,
 * which would have made this interface hand back windows of twenty rows. Rejected because the two
 * are separate concepts. {@code app/cbl/CBTRN03C.cbl} L131-L132 declares
 * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}, tested at L282 by
 * {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}, and paragraph
 * {@code 1110-WRITE-PAGE-TOTALS.} at L293 folds the band total into the grand total at L297 and
 * resets the band total at L298, over accumulators declared {@code PIC S9(09)V99} at L134-L136. That
 * is the layout of a printed report: it decides where a header band repeats and where a page total
 * prints. Key-based positioning is a request-time concern over an interactive list. Neither drives
 * the other, and <b>this interface implements only the second</b> -- the band, its headers and its
 * page and grand totals belong to the writing side of this module. </p>
 *
 * <h2>Key-based positioning, and the cursor the reference already carried</h2>
 *
 * <p>Alternatives Considered: addressing a window by the row ordinal of its first row was evaluated
 * and rejected, because an ordinal is not stable. Insert a row ahead of it and every ordinal behind
 * the insertion moves by one, so two consecutive requests over a relation being written omit rows
 * entirely and return others twice. Positioning strictly past the last key already returned has no
 * such failure, because a key does not move when a neighbour is inserted. That insertion really is
 * unsynchronised in the reference is shown by {@code app/cbl/COBIL00C.cbl} L212-L217, an unlocked
 * read-then-increment: {@code MOVE HIGH-VALUES TO TRAN-ID} at L212, then a browse start at L213, a
 * read-previous at L214 and a browse end at L215 to land on the highest key, then
 * {@code MOVE TRAN-ID TO WS-TRAN-ID-NUM} at L216 and {@code ADD 1} at L217. Nothing holds a lock
 * across those six statements. That site is a maximum-key next-identifier generator and is cited
 * <b>only</b> as the evidence that insertion is unsynchronised; it is not a browse and is not the
 * ground for the design. Register entry <b>R1</b> holds the decision. </p>
 *
 * <p>Assumptions: the shape is grounded on the two genuine browse screens.
 * {@code app/cbl/COCRDLIC.cbl} renders 7 rows, declared as {@code WS-MAX-SCREEN-LINES} at its L177
 * with {@code VALUE 7} at L178, and {@code app/cbl/COTRN00C.cbl} renders 10, bounded at its L290 and
 * L344. Their cursor is already a key rather than an ordinal:
 * {@code app/cbl/COCRDLIC.cbl} L229-L244 persists a last-key pair at L230-L232, a first-key pair at
 * L233-L235, a screen counter at L237, a last-page-displayed flag at L239 and a next-page indicator
 * at L242-L244, and carries no row ordinal anywhere. The three components map one to one onto the
 * envelope this interface returns: first key, last key and the next-page indicator become
 * {@code firstKey}, {@code lastKey} and {@code hasNext}. The indicator's absent state is declared
 * {@code VALUE LOW-VALUES} at L243, which is why both cursor tokens are nullable rather than
 * mandatory. </p>
 *
 * <p>Assumptions: the shape is <b>not</b> grounded on anything in the statement path, and a peer
 * reading says otherwise, so both readings are named. A peer brief for
 * {@code services/reporting-service/README.md} reads {@code LK-M03B-KEY-LN PIC S9(4)}, declared at
 * {@code app/cbl/CBSTM03B.CBL} L111, as a significant-key length implying a generic browse, which
 * would justify key-based positioning from inside the statement read path. The first-hand COBOL is
 * authoritative and contradicts that reading, on the same precedent the shared authorization codec
 * contract sets for its own comma defect: a browse-verb census across all 230 lines of that file
 * returns zero for {@code KEY IS GREATER}, {@code READ NEXT}, {@code READNEXT}, {@code READPREV},
 * {@code STARTBR} and {@code ENDBR}; its two keyed selections declare
 * {@code ACCESS MODE IS RANDOM} at L45 and L51, which makes them unbrowsable; its callee bodies at
 * L188-L193 and L213-L218 are a reference-modified move followed by a plain keyed read; and the
 * caller computes the whole key every time, 9 characters at {@code app/cbl/CBSTM03A.CBL} L373-L374
 * and 11 at its L397-L398. The consequence for this file is exact: <b>key-based positioning is
 * justified here by the two browse screens and by nothing in the statement path</b>. Register entry
 * <b>R3</b> carries the five proofs and is cited rather than restated. </p>
 *
 * <p>Trade-offs: the reference is inconsistent about which key a cursor carries, so one of its two
 * screens cannot be reproduced exactly and the divergence is deliberate.
 * {@code app/cbl/COCRDLIC.cbl} L1194-L1195 stores the key of the last row actually displayed, then
 * issues its lookahead read and <b>overwrites</b> that stored key with the probe row's key at
 * L1207-L1214, under both the normal and the duplicate-record arms.
 * {@code app/cbl/COTRN00C.cbl} L305-L313 runs the same lookahead and <b>discards</b> the probe key,
 * incrementing only a display counter at L306-L307. This interface returns the key of the last row
 * the client actually received, which is what the second screen already does, because the probe row
 * is by construction a row the client never saw and a cursor carrying its key names a position the
 * user was never shown. What is preserved either way is the observable behaviour: the window
 * boundary and the availability of a further window. Register entry <b>R2</b> holds the
 * decision. </p>
 *
 * <h2>A missing dimension aborts the run; it is never discarded</h2>
 *
 * <p>Assumptions: the reference treats an unresolvable dimension as fatal, and the policy here rests
 * on that rather than on what SQL does by default. All three lookups abort:
 * {@code 1500-A-LOOKUP-XREF.} at {@code app/cbl/CBTRN03C.cbl} L484-L492 displays
 * {@code 'INVALID CARD NUMBER : '} at L487, {@code 1500-B-LOOKUP-TRANTYPE.} at L494-L502 displays
 * {@code 'INVALID TRANSACTION TYPE : '} at L497 and {@code 1500-C-LOOKUP-TRANCATG.} at L504-L512
 * displays {@code 'INVALID TRAN CATG KEY : '} at L507 -- each message carrying a space both before
 * and after its colon -- and each then moves 23 into the status field, displays it and performs the
 * abend paragraph. <b>The hazard is that a plain inner join drops the unresolvable row instead</b>:
 * different bytes, different account and grand totals, different band boundaries, and a report that
 * is quietly short rather than a run that fails. The policy adopted, matching what the four
 * statement roles beside this one state, is an inner join plus an integrity reconciliation at the
 * query boundary: the count of driving rows the predicate admits is compared with the count the join
 * yields, and any inequality raises naming the offending key, which is what those three paragraphs
 * do. Register entry <b>R10</b> holds the decision and its rejected alternatives. </p>
 *
 * <h2>Three schemas, read-only, and none of them owned here</h2>
 *
 * <p>Trade-offs: this context reads relations it does not own and accepts that it cannot supply
 * them. The transaction rows come from the {@code ledger} schema, the card cross-reference from the
 * {@code account} schema, and the type and category rows from the {@code reference} schema; the
 * target design records this context's owned tables as "(none)". Reach across a schema boundary is
 * by <b>read privilege</b> and never by a build dependency on another service module -- the only
 * intra-reactor dependency this module declares is the shared kernel.
 * {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the {@code reporting} schema
 * under a no-login owner at its L796-L797, conveys schema usage to that owner at its L1265 and read
 * over the four data schemas at its L1270, L1275, L1280 and L1285, withdraws every such read from
 * the service login role at its L1328-L1340, and leaves that login role usage on the
 * {@code reporting} schema alone at its L1344.
 * {@code data-migration/sql/V1__reporting_views.sql} declares the four relations this interface
 * reads at its L243, L367, L396 and L522, each behind a security barrier, conveys read on them by
 * name to the service login role at its L554-L560 and withdraws every writing privilege at its L577.
 * Both files are another agent's ownership, so the operating rule is blunt: <b>a relation missing at
 * run time is a data-migration defect to report, and never something to declare from here.</b> The
 * schema resolution path and the pooled read-only role arrive from
 * {@link com.carddemo.reporting.config.DataSourceConfig}, which also pins schema generation to none.
 * Register entries <b>R11</b> and <b>R12</b> hold this decision, the second on the target design's
 * own ground that "a replica adds cost and replica-lag semantics for no parity benefit". </p>
 *
 * <h2>The join is composed here, not mapped</h2>
 *
 * <p>Assumptions: none of the four projections this interface reads declares a mapped association to
 * another, and the join is expressed in the queries below instead. That is deliberate twice over. A
 * mapped association would let a deferred load fire outside the read-only transaction the cursor
 * requires, which is a read the caller never asked for and cannot see. And it would hide the join
 * that the reference makes explicit through three separate keyed lookups, at
 * {@code app/cbl/CBTRN03C.cbl} L484-L492, L494-L502 and L504-L512 -- the very structure the
 * reconciliation above depends on being visible. </p>
 *
 * <h2>Money</h2>
 *
 * <p>Assumptions: the amount is exact fixed point at every hop. {@code TRAN-AMT} is declared
 * {@code PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy} L10, which becomes a numeric column of
 * precision 11 and scale 2 and arrives here as {@link Money} at scale 2 with half-up rounding.
 * IEEE-754 binary floating point cannot represent those values exactly and appears nowhere in this
 * path; the prohibition is asserted for the reactor by the layering rules held in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}.
 * This interface returns typed numeric columns only: the 133-column fixed-width layout and its
 * {@code -ZZZ,ZZZ,ZZZ.ZZ} and {@code +ZZZ,ZZZ,ZZZ.ZZ} edit masks, declared at
 * {@code app/cpy/CVTRA07Y.cpy} L30 and at its L54, L60 and L66, belong to the writing side of this
 * module and never to a query. </p>
 *
 * <h2>The retired index-rebuild step</h2>
 *
 * <p>Refactoring Rationale: what disappears is a build step and not an access path, and the
 * distinction decides who owns what. The target engine maintains a secondary access path
 * transactionally as rows change, so the separate rebuild the reference runs has no counterpart,
 * while the path it produced survives. That rebuild appears at exactly four sites repository-wide --
 * {@code app/jcl/XREFFILE.jcl} L100, {@code app/jcl/TRANIDX.jcl} L52, {@code app/jcl/CARDFILE.jcl}
 * L110 and {@code app/jcl/TRANFILE.jcl} L109 -- and the one this surface reads through is the
 * processing-timestamp path from {@code app/jcl/TRANIDX.jcl}. In the target that path is the
 * non-unique index over the transaction table's processing timestamp in the {@code ledger} schema,
 * <b>owned by the context that owns that schema and neither declared nor removed from here</b>.
 * {@code app/jcl/TRANIDX.jcl} stays on disk untouched and is cited only as position evidence:
 * {@code KEYS(26 304)} at its L27, with {@code NONUNIQUEKEY} at L28 and {@code UPGRADE} at L29
 * confirming the path is non-unique and maintained on update, which are the two properties the
 * target index carries. Register entry <b>R8</b> holds the decision. </p>
 *
 * <h2>No write path</h2>
 *
 * <p>Alternatives Considered: extending a broader Spring Data base interface, either the general
 * create-read-update-delete one or the persistence-specific one. Rejected because either would
 * inherit five writing methods -- save, save-all, delete, delete-all and delete-by-identifier --
 * onto a type whose entire contract is that it has none, over relations another context owns and
 * under a role holding read privileges only. The marker base is extended instead and every method
 * below is declared explicitly, so the surface is exactly what is written here. </p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register governing every choice in this package is authored once in
 * {@code package-info.java} beside this file, and rows are cited by identifier rather than restated,
 * as that file directs at its L237-L238. Every member below carries its own Javadoc because
 * user-specified Rule 1 (Explainability) attaches its presence clause at L15 to every function and
 * names no visibility, and because its validation gate at L43 is conjunctive; the content elements
 * are the four at its L18-L21. The four rationale labels are written as Rule 1 writes them at
 * L31-L34, the one form {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes at its L222-L227 with the
 * four load-bearing properties at its L232-L240; register entry <b>R14</b> records why they were
 * retyped from the rule rather than copied from the reference suite. </p>
 */
public interface TransactionReportRepository extends Repository<ReportTransactionView, String> {

    /**
     * Number of rows the driver is asked to retrieve per network round trip while a cursor is open.
     *
     * <p>Assumptions: the value is anchored to a cited measurement rather than chosen as a round
     * number. It is 25 times the 20-line output band the reference declares at
     * {@code app/cbl/CBTRN03C.cbl} L131-L132, so one round trip always spans a whole number of bands
     * and a band is never left half-retrieved across two trips. </p>
     *
     * <p>Trade-offs: 500 rows of the 350-byte record {@code app/cpy/CVTRA05Y.cpy} L5-L18 declares is
     * 175 000 bytes held in the driver at one time under this setting, and a smaller value would
     * cost more round trips over the same relation. It is deliberately not set to the 512 the
     * card-ordered statement cursor beside this file uses: that figure bounds a measured overrun in a
     * different program, and reusing one limit as another ties two unrelated numbers together so that
     * changing either appears to require changing both. </p>
     */
    String REPORT_FETCH_SIZE = "500";

    /**
     * Character joining the two ordering components inside one cursor key.
     *
     * <p>Assumptions: a vertical bar cannot occur inside either component, so the join is reversible
     * without escaping. The leading component is the narrowed card rendering
     * {@code data-migration/sql/V1__reporting_views.sql} L256 projects, which is twelve asterisks and
     * four digits, and the trailing component is the 16-character transaction identifier declared at
     * {@code app/cpy/CVTRA05Y.cpy} L5. Neither alphabet contains a bar. </p>
     *
     * <p>Alternatives Considered: reusing the colon the sealing utility itself places between a
     * token's issue instant and its cursor key. Rejected because reading a sealed token back splits
     * on the FIRST colon only, at
     * {@code services/common-lib/src/main/java/com/carddemo/common/web/CursorToken.java} L310, and
     * returns everything after it as the key at its L323, so a colon inside the key survives that
     * round trip but reads as though the key had structure the utility owns. A separator the utility
     * does not use keeps the two levels of structure visibly separate. </p>
     */
    char CURSOR_KEY_SEPARATOR = '|';

    /**
     * Number of ordering components one cursor key carries, being the card rendering and then the
     * transaction identifier.
     *
     * <p>Assumptions: the arity is two because the ordering is two keys deep, for the reason the
     * type-level charter records against {@code app/jcl/TRANREPT.jcl} L46. A key presenting any other
     * arity was not produced by this interface and is refused rather than partially read. </p>
     */
    int CURSOR_KEY_COMPONENTS = 2;

    /**
     * One fully resolved report line: a transaction with its three joined dimensions.
     *
     * <p>Assumptions: this is a <b>closed</b> interface projection, so every query producing it
     * supplies an alias for each accessor declared here and the engine retrieves exactly those
     * columns -- the eight the reference prints at {@code app/cbl/CBTRN03C.cbl} L363-L370 plus the
     * ordering key. An open projection -- one carrying a derived accessor -- would retrieve all
     * thirteen columns of the driving row behind the scenes and would then hide which columns the
     * report actually depends on, which is the one thing this type exists to make visible. </p>
     *
     * <p>Assumptions: the accessors take the get-prefixed form rather than the record-component form,
     * because a projection's accessors are resolved by property name and the prefixed form is the one
     * resolved without further configuration. The response type in the sibling data-transfer package
     * carries the same eight values in the record form, and the two are not interchangeable: that one
     * is a serialisation contract and this one is a query result. </p>
     */
    interface ReportLine {

        /**
         * Returns the transaction identifier, which is also the ordering key behind the card
         * rendering.
         *
         * @return the 16-character identifier declared at {@code app/cpy/CVTRA05Y.cpy} L5, moved to
         *     the detail line at {@code app/cbl/CBTRN03C.cbl} L363; never {@code null}
         */
        String getTransactionId();

        /**
         * Returns the card rendering this line belongs to, which is the leading ordering key.
         *
         * <p>Assumptions: this is the narrowed rendering
         * {@code data-migration/sql/V1__reporting_views.sql} L256 projects and not the card number,
         * and it is declared on the projection because the cursor key is built from it. It is not one
         * of the eight values the reference prints -- the detail line at
         * {@code app/cbl/CBTRN03C.cbl} L361-L370 does not carry it -- so a caller rendering a report
         * line ignores it and only the positioning below reads it. </p>
         *
         * @return the 16-character narrowed rendering the relation exposes, being twelve asterisks
         *     and the last four digits; never {@code null}
         */
        String getCardNum();

        /**
         * Returns the account the transaction's card is issued against.
         *
         * <p>Assumptions: this is the one printed value that comes from the cross-reference rather
         * than from the transaction, which is why the cross-reference is a join participant at all.
         * {@code app/cbl/CBTRN03C.cbl} L364 is {@code MOVE XREF-ACCT-ID TO
         * TRAN-REPORT-ACCOUNT-ID} and the reference resolves it at
         * {@code 1500-A-LOOKUP-XREF.} L484-L492. </p>
         *
         * @return the account identifier declared {@code PIC 9(11)} at
         *     {@code app/cpy/CVACT03Y.cpy} L7; never {@code null}
         */
        Long getAccountId();

        /**
         * Returns the two-character transaction type code.
         *
         * @return the code declared at {@code app/cpy/CVTRA05Y.cpy} L6 and printed at
         *     {@code app/cbl/CBTRN03C.cbl} L365; never {@code null}
         */
        String getTypeCd();

        /**
         * Returns the description of that transaction type.
         *
         * <p>Assumptions: this is the TYPE description declared at {@code app/cpy/CVTRA03Y.cpy} L6
         * and is a different value from the CATEGORY description below, declared at
         * {@code app/cpy/CVTRA04Y.cpy} L8. Both are fifty characters, so width cannot distinguish
         * them; what does is that {@code app/cbl/CBTRN03C.cbl} moves them to two different printed
         * fields, at L366 and L368. </p>
         *
         * @return the fifty-character type description; never {@code null}
         */
        String getTypeDescription();

        /**
         * Returns the four-character transaction category code.
         *
         * @return the code declared at {@code app/cpy/CVTRA05Y.cpy} L7 and printed at
         *     {@code app/cbl/CBTRN03C.cbl} L367, leading zeros intact; never {@code null}
         */
        String getCategoryCd();

        /**
         * Returns the description of that transaction category.
         *
         * @return the fifty-character category description declared at
         *     {@code app/cpy/CVTRA04Y.cpy} L8 and printed at {@code app/cbl/CBTRN03C.cbl} L368;
         *     never {@code null}
         */
        String getCategoryDescription();

        /**
         * Returns the ten-character origin of the transaction.
         *
         * @return the source declared at {@code app/cpy/CVTRA05Y.cpy} L8 and printed at
         *     {@code app/cbl/CBTRN03C.cbl} L369; never {@code null}
         */
        String getSource();

        /**
         * Returns the transaction amount as an exact decimal.
         *
         * <p>Assumptions: the type is the shared exact decimal, for the reason the type-level charter
         * records against {@code app/cpy/CVTRA05Y.cpy} L10. Rendering it through the reference's edit
         * masks is the writing side's work, not a query's. </p>
         *
         * @return the amount at scale 2, printed at {@code app/cbl/CBTRN03C.cbl} L370; never
         *     {@code null}
         */
        Money getAmount();
    }

    /**
     * Seals one cursor key into the opaque token a client receives, on behalf of the caller that
     * holds the sealing material.
     *
     * <p>Assumptions: the envelope this interface returns refuses any boundary token that is not
     * sealed -- {@link PageResponse} validates both tokens in its canonical constructor at its
     * L342-L343 against the shape test declared at
     * {@code services/common-lib/src/main/java/com/carddemo/common/web/CursorToken.java} L345-L348 --
     * and sealing needs two things a data-access type must not hold: the keyed authentication
     * material and the identity of the authenticated caller a token is bound to. So the algebra of
     * positioning lives here and the sealing lives with the caller, which is what this one-method
     * type carries across the boundary. </p>
     *
     * <p>Trade-offs: passing a function into a query method is unusual and is accepted for a specific
     * reason. The envelope cannot be built without tokens at all -- its constructor rejects a
     * populated page whose boundaries are absent -- so the alternative was to return a bare row list
     * and leave the probe arithmetic, the trimming and the choice of which row the boundary names to
     * every caller. That is exactly the arithmetic register entry <b>R2</b> records the reference
     * itself getting inconsistent about, so leaving it to a caller invites the same divergence to
     * reappear one layer up. What is given up is that this interface names a callback type; what is
     * bought is that the boundary is derived in one place. </p>
     *
     * <p>Assumptions: a caller that seals a row ordinal rather than a key produces a token that
     * satisfies this contract syntactically while addressing a position register entry <b>R1</b>
     * rejects. The keys this interface hands the sealer are always key renderings, so honouring this
     * contract is sufficient to stay inside that decision. </p>
     */
    @FunctionalInterface
    interface CursorSealer {

        /**
         * Seals one cursor key produced by this interface into a client-facing token.
         *
         * @param cursorKey the opened cursor key naming one row boundary, in the rendering this
         *     interface produces, being at most 33 characters; must not be {@code null} or blank
         * @return the sealed token carrying that key, which the envelope accepts as a boundary; never
         *     {@code null}
         * @throws NullPointerException if {@code cursorKey} is {@code null}, which the sealing
         *     utility refuses rather than sealing an absent position
         * @throws IllegalArgumentException if {@code cursorKey} is blank or longer than the sealing
         *     utility admits, both of which it refuses at its own boundary
         */
        String seal(String cursorKey);
    }

    /**
     * Opens a forward-only cursor over every resolved report line inside one instant range.
     *
     * <p>Assumptions: the two arguments are the half-open instant pair the type-level charter
     * describes, selecting the same rows as the inclusive comparison at
     * {@code app/cbl/CBTRN03C.cbl} L173-L174, and the sibling method taking two business dates is the
     * shape a caller normally reaches for. This one is declared separately because a query method
     * cannot both accept dates and keep the predicate on the bare column, and the conversion is
     * stated once in {@link #streamReportLines(LocalDate, LocalDate)} rather than at every call
     * site. </p>
     *
     * <p>Alternatives Considered: returning a list instead of a cursor. Rejected because the report
     * is unbounded in principle -- the reference writes each line to its output as it goes and holds
     * no table of lines at all, and nothing in the target carries a fixed arity either -- so a list
     * would put an entire range's rows in memory before the first one could be written. A cursor
     * hands the writing side one row at a time, which is the same shape as the reference's own single
     * sequential pass at {@code app/cbl/CBTRN03C.cbl} L170-L196. </p>
     *
     * <p>Trade-offs: a cursor is an open resource and its lifetime becomes the caller's obligation,
     * which a list would not have been. The reference carries the same obligation and discharges it
     * explicitly, closing its input in {@code 9000-TRANFILE-CLOSE.} at
     * {@code app/cbl/CBTRN03C.cbl} L514-L516. The propagation declared below refuses the call
     * outright when no transaction is in progress, so the failure is a refusal at the call rather
     * than a cursor that closes underneath the reader partway through a report. </p>
     *
     * @param rangeStart the first instant admitted, being the start of the first business date of the
     *     range; must not be {@code null}
     * @param rangeEnd the first instant excluded, being the start of the day after the last business
     *     date of the range; must not be {@code null}
     * @return an open, forward-only cursor over the resolved lines, ordered by card rendering
     *     ascending and then transaction identifier ascending, empty when the range admits no row and
     *     never {@code null}. The caller owns the cursor and must close it, for which
     *     try-with-resources is the intended form, and must consume it inside the read-only
     *     transaction this method requires
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
     *     in progress when this method is called, which the mandatory propagation below enforces so
     *     that a cursor cannot be opened outside the scope that keeps it usable
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read, which
     *     includes one of them being absent -- a defect to report against the data-migration package,
     *     as register entry <b>R11</b> records, and never one to work around from here
     */
    @Query("""
            select t.transactionId as transactionId,
                   t.cardNum as cardNum,
                   x.accountId as accountId,
                   t.typeCd as typeCd,
                   ty.typeDescription as typeDescription,
                   t.categoryCd as categoryCd,
                   c.categoryDescription as categoryDescription,
                   t.source as source,
                   t.amount as amount
            from ReportTransactionView t
            join CardXrefView x on x.cardNum = t.cardNum
            join TransactionTypeView ty on ty.typeCd = t.typeCd
            join TransactionCategoryView c
                on c.key.typeCode = t.typeCd and c.key.categoryCode = t.categoryCd
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            order by t.cardNum asc, t.transactionId asc
            """)
    @QueryHints({
        @QueryHint(name = AvailableHints.HINT_FETCH_SIZE, value = REPORT_FETCH_SIZE),
        @QueryHint(name = AvailableHints.HINT_READ_ONLY, value = "true")
    })
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    Stream<ReportLine> streamReportLinesWithin(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * Opens a forward-only cursor over every resolved report line inside one inclusive business-date
     * range.
     *
     * <p>Assumptions: this is the report-generation surface and the inclusive pair is the reference's
     * own contract, taken from the ten, one, ten parameter record at {@code app/cbl/CBTRN03C.cbl}
     * L123-L125 and from the predicate at its L173-L174. Both endpoints are admitted, and a
     * transaction timed at any hour of either endpoint's day is inside the range, because the
     * comparison is on the date part alone. </p>
     *
     * @param startDate the first business date admitted, inclusive; must not be {@code null}
     * @param endDate the last business date admitted, inclusive; must not be {@code null}
     * @return an open, forward-only cursor over the resolved lines in card-then-identifier order,
     *     with the same closing and transaction obligations
     *     {@link #streamReportLinesWithin(LocalDateTime, LocalDateTime)} states; never {@code null}
     * @throws NullPointerException if either date is {@code null}, refused here rather than reaching
     *     the query as an unbounded comparison
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is
     *     in progress, propagated from the query this method delegates to
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read
     */
    default Stream<ReportLine> streamReportLines(LocalDate startDate, LocalDate endDate) {
        // Assumptions: the conversion lives here so that exactly one place in this interface knows
        //       how an inclusive date pair becomes a half-open instant pair. Repeating it per query
        //       method is how one of them acquires an exclusive upper bound unnoticed, which would
        //       silently discard the whole of the last day the comparison at
        //       app/cbl/CBTRN03C.cbl L174 includes.
        return streamReportLinesWithin(firstInstantOf(startDate), firstInstantAfter(endDate));
    }

    /**
     * Reads the leading window of resolved report lines inside one instant range.
     *
     * <p>Assumptions: the row bound is supplied by the caller rather than hard-coded here, because this
     * method serves both the bounded composition the writing side performs and the leading window the
     * positioning below requests. The positioning path always asks for exactly one row beyond the
     * window it intends to hand back, which is the reference's own device -- the lookahead read at
     * {@code app/cbl/COCRDLIC.cbl} L1197 -- and is what makes the further-window answer a fact about
     * the relation rather than an estimate. </p>
     *
     * @param rangeStart the first instant admitted; must not be {@code null}
     * @param rangeEnd the first instant excluded; must not be {@code null}
     * @param rowBound the greatest number of rows to return; must not be {@code null}
     * @return the resolved lines from the start of the range in card-then-identifier order, at most
     *     {@code rowBound} of them, possibly empty; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read
     */
    @Query("""
            select t.transactionId as transactionId,
                   t.cardNum as cardNum,
                   x.accountId as accountId,
                   t.typeCd as typeCd,
                   ty.typeDescription as typeDescription,
                   t.categoryCd as categoryCd,
                   c.categoryDescription as categoryDescription,
                   t.source as source,
                   t.amount as amount
            from ReportTransactionView t
            join CardXrefView x on x.cardNum = t.cardNum
            join TransactionTypeView ty on ty.typeCd = t.typeCd
            join TransactionCategoryView c
                on c.key.typeCode = t.typeCd and c.key.categoryCode = t.categoryCd
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            order by t.cardNum asc, t.transactionId asc
            """)
    List<ReportLine> findReportLines(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd,
            Limit rowBound);

    /**
     * Reads the window of resolved report lines that follows one ordering position, ascending.
     *
     * <p>Assumptions: the continuation compares the ordering pair as a whole rather than the card
     * rendering alone, so a card whose transactions span a window boundary continues inside that card
     * instead of continuing at the next card. Comparing the card rendering alone would discard every
     * remaining transaction of the card the window ended inside, and the account total the reference
     * writes at {@code app/cbl/CBTRN03C.cbl} L182-L183 would then be short by exactly those rows.
     * This is the read-next half of the browse quartet register entry <b>R1</b> records. </p>
     *
     * @param cardNum the card rendering of the last row already returned; must not be {@code null}
     * @param transactionId the transaction identifier of the last row already returned; must not be
     *     {@code null}
     * @param rangeStart the first instant admitted; must not be {@code null}
     * @param rangeEnd the first instant excluded; must not be {@code null}
     * @param rowBound the greatest number of rows to return, which the positioning path sizes one
     *     beyond the window it hands back; must not be {@code null}
     * @return the following resolved lines in the same ascending order, at most {@code rowBound} of
     *     them, empty when the range holds nothing further; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read
     */
    @Query("""
            select t.transactionId as transactionId,
                   t.cardNum as cardNum,
                   x.accountId as accountId,
                   t.typeCd as typeCd,
                   ty.typeDescription as typeDescription,
                   t.categoryCd as categoryCd,
                   c.categoryDescription as categoryDescription,
                   t.source as source,
                   t.amount as amount
            from ReportTransactionView t
            join CardXrefView x on x.cardNum = t.cardNum
            join TransactionTypeView ty on ty.typeCd = t.typeCd
            join TransactionCategoryView c
                on c.key.typeCode = t.typeCd and c.key.categoryCode = t.categoryCd
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
              and (t.cardNum > :cardNum
                   or (t.cardNum = :cardNum and t.transactionId > :transactionId))
            order by t.cardNum asc, t.transactionId asc
            """)
    List<ReportLine> findReportLinesAfter(
            @Param("cardNum") String cardNum,
            @Param("transactionId") String transactionId,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd,
            Limit rowBound);

    /**
     * Reads the window of resolved report lines that precedes one ordering position, descending.
     *
     * <p>Assumptions: the ordering is reversed in the query rather than after the rows arrive,
     * because the rows wanted are the ones nearest the position and a forward query could only find
     * them by reading the range from its start. The reference reads backward from the store for the
     * same reason, priming at {@code app/cbl/COCRDLIC.cbl} L1294 and looping at its L1322. This is
     * the read-previous half of the quartet, and declaring it is what makes the surface
     * bidirectional: a forward-only surface is not a migration of a browse that both
     * {@code app/cbl/COCRDLIC.cbl} and {@code app/cbl/COTRN00C.cbl} drive in both directions. </p>
     *
     * <p>Assumptions: the rows arrive furthest-first and the positioning path reverses them before
     * handing them back, because a client renders a window ascending whichever direction it was
     * reached from. The reference does the same thing in working storage, filling its screen array
     * from a backward browse at {@code app/cbl/COCRDLIC.cbl} L1273-L1376 and displaying it in
     * ascending order. </p>
     *
     * @param cardNum the card rendering of the first row already returned; must not be {@code null}
     * @param transactionId the transaction identifier of the first row already returned; must not be
     *     {@code null}
     * @param rangeStart the first instant admitted; must not be {@code null}
     * @param rangeEnd the first instant excluded; must not be {@code null}
     * @param rowBound the greatest number of rows to return, sized one beyond the window that is
     *     handed back; must not be {@code null}
     * @return the preceding resolved lines in descending order, nearest the position first, at most
     *     {@code rowBound} of them, empty when nothing precedes the position; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read
     */
    @Query("""
            select t.transactionId as transactionId,
                   t.cardNum as cardNum,
                   x.accountId as accountId,
                   t.typeCd as typeCd,
                   ty.typeDescription as typeDescription,
                   t.categoryCd as categoryCd,
                   c.categoryDescription as categoryDescription,
                   t.source as source,
                   t.amount as amount
            from ReportTransactionView t
            join CardXrefView x on x.cardNum = t.cardNum
            join TransactionTypeView ty on ty.typeCd = t.typeCd
            join TransactionCategoryView c
                on c.key.typeCode = t.typeCd and c.key.categoryCode = t.categoryCd
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
              and (t.cardNum < :cardNum
                   or (t.cardNum = :cardNum and t.transactionId < :transactionId))
            order by t.cardNum desc, t.transactionId desc
            """)
    List<ReportLine> findReportLinesBefore(
            @Param("cardNum") String cardNum,
            @Param("transactionId") String transactionId,
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd,
            Limit rowBound);

    /**
     * Reads the next window of report lines after a stated position and returns it in the shared
     * cursor envelope.
     *
     * <p>Assumptions: the further-window answer comes from a probe and never from a tally. One row
     * beyond the window is requested; if it arrives, a further window exists and the extra row is
     * discarded rather than returned. That is precisely how the reference establishes it -- the
     * lookahead read at {@code app/cbl/COCRDLIC.cbl} L1197-L1214 and the one at
     * {@code app/cbl/COTRN00C.cbl} L305-L313 both read one record past the screen and set a flag from
     * whether it was found. A tally of how many rows exist altogether would answer a different
     * question at a cost that grows with the range. </p>
     *
     * <p>Trade-offs: the boundary token names the last row <b>returned</b> and never the probe row,
     * which is the divergence register entry <b>R2</b> records against
     * {@code app/cbl/COCRDLIC.cbl} L1207-L1214. The probe row points at a record the client never
     * received, so a token carrying its key would name a position the user was never shown and a
     * following request would begin one row late. </p>
     *
     * @param startDate the first business date admitted, inclusive; must not be {@code null}
     * @param endDate the last business date admitted, inclusive; must not be {@code null}
     * @param openedLastKey the already-verified cursor key of the last row of the window the caller
     *     currently holds, in the rendering this interface produces, or {@code null} to read from the
     *     start of the range
     * @param rowsWanted the number of rows the window is to carry, before the probe row is added;
     *     must be greater than zero
     * @param sealer the caller's sealing function, which holds the material this interface must not;
     *     must not be {@code null}
     * @return an envelope whose rows are the window in ascending order, whose {@code firstKey} and
     *     {@code lastKey} are the sealed keys of its first and last returned rows, and whose
     *     {@code hasNext} is the probe result; both tokens are absent and {@code hasNext} is false
     *     when the range holds nothing further, and the method returns that exhausted envelope rather
     *     than raising; never {@code null}
     * @throws NullPointerException if either date or the sealer is {@code null}
     * @throws IllegalArgumentException if {@code rowsWanted} is not greater than zero, or if
     *     {@code openedLastKey} is present but does not carry the two components this interface
     *     produces
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read
     */
    default PageResponse<ReportLine> readNextReportLines(
            LocalDate startDate,
            LocalDate endDate,
            String openedLastKey,
            int rowsWanted,
            CursorSealer sealer) {

        Objects.requireNonNull(sealer, "sealer must not be null");
        requireRowsWanted(rowsWanted);
        LocalDateTime rangeStart = firstInstantOf(startDate);
        LocalDateTime rangeEnd = firstInstantAfter(endDate);

        // Assumptions: an absent position means the start of the range rather than an error, because
        //       the reference's own first entry has no cursor either -- the card list arrives with its
        //       key pair at low values, the condition declared at app/cbl/COCRDLIC.cbl L243. Routing
        //       that case to the unpositioned read reuses one query instead of encoding a
        //       lowest-possible key, which no key alphabet here can express without inventing a
        //       sentinel the relation does not carry.
        Limit probe = Limit.of(rowsWanted + 1);
        List<ReportLine> probed = openedLastKey == null
                ? findReportLines(rangeStart, rangeEnd, probe)
                : findReportLinesAfter(cardRenderingOf(openedLastKey), transactionIdOf(openedLastKey),
                        rangeStart, rangeEnd, probe);

        boolean furtherAhead = probed.size() > rowsWanted;
        List<ReportLine> rows = furtherAhead ? probed.subList(0, rowsWanted) : probed;
        if (rows.isEmpty()) {
            return PageResponse.empty();
        }
        return PageResponse.ofRows(
                rows,
                sealer.seal(cursorKeyOf(rows.get(0))),
                sealer.seal(cursorKeyOf(rows.get(rows.size() - 1))),
                furtherAhead);
    }

    /**
     * Reads the previous window of report lines before a stated position and returns it in the shared
     * cursor envelope.
     *
     * <p>Assumptions: the position a backward step is taken from is the <b>first</b> row of the window
     * the caller holds, which is the reference's own arrangement:
     * {@code app/cbl/COCRDLIC.cbl} L1268 moves the stored first key into the field its backward browse
     * is positioned from before starting it. A backward step therefore requires a position and cannot
     * be taken from nowhere, which is why an absent key is refused here rather than being read as the
     * start of the range. </p>
     *
     * <p>Assumptions: the envelope reports a further window unconditionally on this path, and the
     * reference does the same -- {@code app/cbl/COCRDLIC.cbl} L1287 sets its next-page-exists
     * condition on the backward path without testing anything. It is not an assumption but a fact
     * about the request: a caller can only step backward from a window it already holds, and that
     * window lies ahead of the rows returned here. </p>
     *
     * <p>Trade-offs: when nothing precedes the position, the envelope returned carries no rows and one
     * continuation position, built through the shared factory whose name describes rows filtered away
     * rather than a range exhausted behind. The shapes are identical -- no rows, one boundary present,
     * a further window reported -- and it is the only factory that can express them, because the
     * exhausted factory reports no further window and would strand a caller that has a window ahead of
     * it -- {@link PageResponse} declares the two at its L464 and its L387 respectively. The
     * compromise accepted is a factory used slightly outside its name; the alternative was a caller
     * unable to step back to where it came from. </p>
     *
     * @param startDate the first business date admitted, inclusive; must not be {@code null}
     * @param endDate the last business date admitted, inclusive; must not be {@code null}
     * @param openedFirstKey the already-verified cursor key of the first row of the window the caller
     *     currently holds, in the rendering this interface produces; must not be {@code null}, since a
     *     backward step from no position names nothing
     * @param rowsWanted the number of rows the window is to carry, before the probe row is added;
     *     must be greater than zero
     * @param sealer the caller's sealing function; must not be {@code null}
     * @return an envelope whose rows are the preceding window <b>reversed into ascending order</b>,
     *     whose {@code firstKey} and {@code lastKey} are the sealed keys of its first and last
     *     returned rows, and which reports a further window; when nothing precedes the position the
     *     rows are empty, {@code firstKey} is absent and {@code lastKey} is the sealed position the
     *     step was taken from; never {@code null}
     * @throws NullPointerException if either date, the key or the sealer is {@code null}
     * @throws IllegalArgumentException if {@code rowsWanted} is not greater than zero, or if
     *     {@code openedFirstKey} does not carry the two components this interface produces
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read
     */
    default PageResponse<ReportLine> readPreviousReportLines(
            LocalDate startDate,
            LocalDate endDate,
            String openedFirstKey,
            int rowsWanted,
            CursorSealer sealer) {

        Objects.requireNonNull(openedFirstKey,
                "openedFirstKey must not be null; a backward step is taken from the first row of the"
                        + " window the caller holds and names nothing without it");
        Objects.requireNonNull(sealer, "sealer must not be null");
        requireRowsWanted(rowsWanted);
        LocalDateTime rangeStart = firstInstantOf(startDate);
        LocalDateTime rangeEnd = firstInstantAfter(endDate);

        List<ReportLine> probed = findReportLinesBefore(
                cardRenderingOf(openedFirstKey), transactionIdOf(openedFirstKey),
                rangeStart, rangeEnd, Limit.of(rowsWanted + 1));

        // Assumptions: the probe row on this path is the FURTHEST row back, because the query orders
        //       descending -- the order declared by the query above and the reverse of the single
        //       ascending key at app/jcl/TRANREPT.jcl L46 -- so it is the last element rather than
        //       the first. Discarding the first element here would discard the row nearest the
        //       caller's window and leave a one-row hole between two adjacent windows, which would
        //       be invisible in every test that reads only one direction.
        boolean furtherBehind = probed.size() > rowsWanted;
        List<ReportLine> nearestFirst = furtherBehind ? probed.subList(0, rowsWanted) : probed;
        if (nearestFirst.isEmpty()) {
            return PageResponse.ofFilteredEmpty(sealer.seal(openedFirstKey), null);
        }

        List<ReportLine> rows = new ArrayList<>(nearestFirst);
        Collections.reverse(rows);
        return PageResponse.ofRows(
                rows,
                sealer.seal(cursorKeyOf(rows.get(0))),
                sealer.seal(cursorKeyOf(rows.get(rows.size() - 1))),
                true);
    }

    /**
     * Counts the transactions the date predicate admits, before any dimension is joined.
     *
     * <p>Assumptions: this is the first half of the integrity reconciliation register entry
     * <b>R10</b> prescribes. It counts the driving relation alone, so its result is unaffected by
     * whether a type, a category or a cross-reference row exists for any given transaction, which is
     * exactly the property that makes a comparison against the joined count meaningful. </p>
     *
     * <p>Assumptions: neither this count nor its joined counterpart takes any part in positioning.
     * Whether a further window exists is established by the probe row inside
     * {@link #readNextReportLines(LocalDate, LocalDate, String, int, CursorSealer)} and by nothing
     * else, exactly as the reference establishes it at {@code app/cbl/COTRN00C.cbl} L305-L313. These
     * two counts answer a different question -- whether every driving row resolved to exactly one of
     * each dimension -- and a positioning answer derived from either of them would change meaning
     * the moment a dimension failed to resolve. </p>
     *
     * @param rangeStart the first instant admitted; must not be {@code null}
     * @param rangeEnd the first instant excluded; must not be {@code null}
     * @return the number of transactions inside the range, zero when the range holds none
     * @throws org.springframework.dao.DataAccessException if the relation cannot be read
     */
    @Query("""
            select count(t)
            from ReportTransactionView t
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            """)
    long countDrivingRows(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * Counts the report lines the three inner joins yield inside the same range.
     *
     * <p>Assumptions: this is the second half of the reconciliation, and its predicate and its three
     * joins are character for character those of
     * {@link #findReportLines(LocalDateTime, LocalDateTime, Limit)}, which is the predicate
     * {@code app/jcl/TRANREPT.jcl} L47-L48 declares. A count taken under any other predicate would
     * reconcile against a different population, and would then report a divergence that did not exist
     * or, worse, fail to report one that did. </p>
     *
     * <p>Assumptions: the comparison a caller makes is for equality and not for a shortfall, because
     * divergence is possible in both directions. A shortfall means a dimension did not resolve, which
     * the reference treats as fatal at {@code app/cbl/CBTRN03C.cbl} L484-L492, L494-L502 and
     * L504-L512. A surplus means a join matched more than one dimension row, which the narrowed card
     * rendering at {@code data-migration/sql/V1__reporting_views.sql} L256 and L525 makes possible for
     * two cards sharing their last four digits. Either way the report's totals would not be the
     * reference's totals. </p>
     *
     * @param rangeStart the first instant admitted; must not be {@code null}
     * @param rangeEnd the first instant excluded; must not be {@code null}
     * @return the number of fully resolved report lines inside the range, which equals
     *     {@link #countDrivingRows(LocalDateTime, LocalDateTime)} exactly when every dimension
     *     resolved to exactly one row
     * @throws org.springframework.dao.DataAccessException if the relations cannot be read
     */
    @Query("""
            select count(t)
            from ReportTransactionView t
            join CardXrefView x on x.cardNum = t.cardNum
            join TransactionTypeView ty on ty.typeCd = t.typeCd
            join TransactionCategoryView c
                on c.key.typeCode = t.typeCd and c.key.categoryCode = t.categoryCd
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            """)
    long countJoinedRows(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd);

    /**
     * Reads the driving transactions the date predicate admits, without joining a dimension.
     *
     * <p>Assumptions: this exists so that a reconciliation divergence can name the offending row and
     * not only its count. Register entry <b>R10</b> requires the refusal to name the unresolved key,
     * which is what the three reference lookup paragraphs do when they display the offending key
     * before abending -- {@code 'INVALID CARD NUMBER : '} at {@code app/cbl/CBTRN03C.cbl} L487 and
     * its two counterparts at L497 and L507 -- and a caller cannot name what it never read. It is a
     * diagnostic path, issued only when the two counts disagree, which is why it is bounded by the
     * caller rather than returning the range. </p>
     *
     * @param rangeStart the first instant admitted; must not be {@code null}
     * @param rangeEnd the first instant excluded; must not be {@code null}
     * @param rowBound the greatest number of rows to return, which bounds the diagnostic rather than
     *     the report; must not be {@code null}
     * @return the driving transactions in the order the report uses, at most {@code rowBound} of them,
     *     possibly empty; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the relation cannot be read
     */
    @Query("""
            select t
            from ReportTransactionView t
            where t.procTs >= :rangeStart and t.procTs < :rangeEnd
            order by t.cardNum asc, t.transactionId asc
            """)
    List<ReportTransactionView> findDrivingRows(
            @Param("rangeStart") LocalDateTime rangeStart,
            @Param("rangeEnd") LocalDateTime rangeEnd,
            Limit rowBound);

    /**
     * Reduces one inclusive business date to the first instant the predicate admits for it.
     *
     * <p>Assumptions: the bound is passed through the shared timestamp utility's normalisation, at
     * {@code services/common-lib/src/main/java/com/carddemo/common/time/TimestampFormatter.java}
     * L473, so that a bound and a stored value are compared at the one resolution the 26-character
     * contract carries. That utility truncates and never rounds, for the measured reason recorded at
     * its L440-L458, and rounding a bound could move it across a day boundary and change which rows
     * the range admits. </p>
     *
     * @param date the inclusive business date to reduce; must not be {@code null}
     * @return the first instant of that date at the contract's resolution; never {@code null}
     * @throws NullPointerException if {@code date} is {@code null}
     */
    private static LocalDateTime firstInstantOf(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        return TimestampFormatter.normalize(date.atStartOfDay());
    }

    /**
     * Reduces one inclusive business date to the first instant the predicate excludes after it.
     *
     * <p>Assumptions: the upper bound is the start of the FOLLOWING day and the comparison against it
     * is strict, which is what makes an inclusive date range expressible without applying a function
     * to the column. The reference's own upper comparison is inclusive on the date part, at
     * {@code app/cbl/CBTRN03C.cbl} L174, and the two select the same rows: every instant of the last
     * day is before the start of the next one, and no instant of the next day is. </p>
     *
     * @param date the last inclusive business date of the range; must not be {@code null}
     * @return the first instant of the day after that date, at the contract's resolution; never
     *     {@code null}
     * @throws NullPointerException if {@code date} is {@code null}
     */
    private static LocalDateTime firstInstantAfter(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        return TimestampFormatter.normalize(date.plusDays(1).atStartOfDay());
    }

    /**
     * Refuses a window size that cannot describe a window.
     *
     * <p>Assumptions: a size of zero or below is a caller defect rather than an empty result, because
     * the probe would then be the only row read and the envelope would report a further window while
     * carrying none. The reference's two browse screens both declare a positive screen size, 7 at
     * {@code app/cbl/COCRDLIC.cbl} L177-L178 and 10 at {@code app/cbl/COTRN00C.cbl} L290, and neither
     * has a representation for a screen of nothing. </p>
     *
     * @param rowsWanted the window size to check
     * @throws IllegalArgumentException if {@code rowsWanted} is not greater than zero
     */
    private static void requireRowsWanted(int rowsWanted) {
        if (rowsWanted <= 0) {
            throw new IllegalArgumentException(
                    "rowsWanted is " + rowsWanted + "; a window carries at least one row, and a"
                            + " request for none would read only the probe row and report a further"
                            + " window while carrying nothing");
        }
    }

    /**
     * Renders the ordering position of one returned row as a single cursor key.
     *
     * <p>Assumptions: the key carries both ordering components because the ordering is two keys deep,
     * for the reason the type-level charter records against {@code app/jcl/TRANREPT.jcl} L46. A key
     * carrying the card rendering alone could not resume inside a card whose rows span a window
     * boundary, and one carrying the transaction identifier alone could not resume at all, because the
     * identifier is the secondary key and is not ordered across cards. </p>
     *
     * @param line the row whose position is to be named; must not be {@code null}
     * @return the cursor key, being the card rendering, the separator and the transaction identifier,
     *     33 characters for the declared widths and so inside the 64 the sealing utility admits at its
     *     L102; never {@code null}
     * @throws NullPointerException if {@code line} is {@code null}
     */
    private static String cursorKeyOf(ReportLine line) {
        Objects.requireNonNull(line, "line must not be null");
        return line.getCardNum() + CURSOR_KEY_SEPARATOR + line.getTransactionId();
    }

    /**
     * Locates the separator inside a cursor key and refuses a key this interface did not produce.
     *
     * <p>Assumptions: a key is checked before it is split rather than after, because an unchecked
     * split silently yields a position instead of an error. A well-formed key is 16 characters of
     * card rendering, per {@code data-migration/sql/V1__reporting_views.sql} L256, then the separator,
     * then the 16 declared at {@code app/cpy/CVTRA05Y.cpy} L5. A key with no separator would position
     * on a card rendering of the whole key and an empty identifier, which selects nothing and reads to
     * a client as a range exhausted rather than as a request refused. </p>
     *
     * @param cursorKey the opened cursor key to examine; must not be {@code null}
     * @return the index of the single separator, which is neither the first nor the last character
     * @throws NullPointerException if {@code cursorKey} is {@code null}
     * @throws IllegalArgumentException if the key does not carry exactly two non-empty components
     */
    private static int separatorIndexOf(String cursorKey) {
        Objects.requireNonNull(cursorKey, "cursorKey must not be null");
        int separator = cursorKey.indexOf(CURSOR_KEY_SEPARATOR);
        boolean wellFormed = separator > 0
                && separator < cursorKey.length() - 1
                && cursorKey.indexOf(CURSOR_KEY_SEPARATOR, separator + 1) < 0;
        if (!wellFormed) {
            throw new IllegalArgumentException(
                    "cursor key does not carry the " + CURSOR_KEY_COMPONENTS + " non-empty components"
                            + " this query surface produces, joined by one separator");
        }
        return separator;
    }

    /**
     * Extracts the leading ordering component, the card rendering, from a cursor key.
     *
     * @param cursorKey the opened cursor key; must not be {@code null}
     * @return the card rendering the key positions on; never {@code null}
     * @throws NullPointerException if {@code cursorKey} is {@code null}
     * @throws IllegalArgumentException if the key is not well formed
     */
    private static String cardRenderingOf(String cursorKey) {
        return cursorKey.substring(0, separatorIndexOf(cursorKey));
    }

    /**
     * Extracts the trailing ordering component, the transaction identifier, from a cursor key.
     *
     * @param cursorKey the opened cursor key; must not be {@code null}
     * @return the transaction identifier the key positions on; never {@code null}
     * @throws NullPointerException if {@code cursorKey} is {@code null}
     * @throws IllegalArgumentException if the key is not well formed
     */
    private static String transactionIdOf(String cursorKey) {
        return cursorKey.substring(separatorIndexOf(cursorKey) + 1);
    }
}
