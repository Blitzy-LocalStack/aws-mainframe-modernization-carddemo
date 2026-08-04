package com.carddemo.reporting.domain;

import com.carddemo.common.money.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of one posted-transaction row behind the 133-column report. Every
 * attribute below comes from {@code app/cpy/CVTRA05Y.cpy}, which declares {@code 01 TRAN-RECORD}
 * at L4 and states its own record length as 350 in the header comment at L2.
 *
 * <p>Purpose: this type stands in for the sequential {@code TRANFILE} input that
 * {@code app/jcl/TRANREPT.jcl} supplies to {@code CBTRN03C} at L65-L66, so the report assembly
 * reads rows returned by a query rather than records read from a dataset. Thirteen of the
 * fourteen declared members become mapped attributes; the fourteenth is the trailing
 * {@code FILLER} at L18, whose removal is argued beside the attribute block below.
 *
 * <p>The one-based byte positions, each beside the {@code CVTRA05Y.cpy} line that declares it,
 * are:
 * <ul>
 *   <li>L5 {@code TRAN-ID PIC X(16)}, positions 1 to 16, the key. </li>
 *   <li>L6 {@code TRAN-TYPE-CD PIC X(02)}, positions 17 to 18. </li>
 *   <li>L7 {@code TRAN-CAT-CD PIC 9(04)}, positions 19 to 22. </li>
 *   <li>L8 {@code TRAN-SOURCE PIC X(10)}, positions 23 to 32. </li>
 *   <li>L9 {@code TRAN-DESC PIC X(100)}, positions 33 to 132. </li>
 *   <li>L10 {@code TRAN-AMT PIC S9(09)V99}, positions 133 to 143, eleven bytes. </li>
 *   <li>L11 {@code TRAN-MERCHANT-ID PIC 9(09)}, positions 144 to 152. </li>
 *   <li>L12 {@code TRAN-MERCHANT-NAME PIC X(50)}, positions 153 to 202. </li>
 *   <li>L13 {@code TRAN-MERCHANT-CITY PIC X(50)}, positions 203 to 252. </li>
 *   <li>L14 {@code TRAN-MERCHANT-ZIP PIC X(10)}, positions 253 to 262. </li>
 *   <li>L15 {@code TRAN-CARD-NUM PIC X(16)}, positions 263 to 278. </li>
 *   <li>L16 {@code TRAN-ORIG-TS PIC X(26)}, positions 279 to 304. </li>
 *   <li>L17 {@code TRAN-PROC-TS PIC X(26)}, positions 305 to 330. </li>
 *   <li>L18 {@code FILLER PIC X(20)}, positions 331 to 350, not mapped. </li>
 * </ul>
 *
 * <p>Those positions are asserted rather than estimated, because two sources with nothing in
 * common agree on them. Summing the fourteen declared widths in order -- 16, 2, 4, 10, 100, 11,
 * 9, 50, 50, 10, 16, 26, 26 and 20 -- places the card number at one-based 263 and the processing
 * timestamp at one-based 305, and closes at exactly 350. Independently
 * {@code app/jcl/TRANREPT.jcl} names the same two positions in its {@code SYMNAMES} block, at
 * L41 {@code TRAN-CARD-NUM,263,16,ZD} and at L42 {@code TRAN-PROC-DT,305,10,CH}. A third,
 * narrower agreement comes from {@code app/cbl/CBTRN03C.cbl} L137, whose card-change break
 * variable {@code WS-CURR-CARD-NUM PIC X(16)} carries the same width as L15 of the copybook.
 *
 * <p>Assumptions: this type and {@code StatementTransactionView} are two separate types and
 * never aliases of one another, because their geometry genuinely differs. Here the transaction
 * identifier occupies positions 1 to 16 and the card number 263 to 278; in
 * {@code app/cpy/COSTM01.CPY} the card number occupies 1 to 16 at L22 and the identifier 17 to
 * 32 at L23, the two forming the 32-byte composite key group {@code TRNX-KEY} declared at L21.
 * Both records are 350 bytes and carry the same thirteen member names, so nothing but the two
 * lexically distinct class names stands between a reader and a mapping that reads a card number
 * where an identifier lives. No mapped ancestor is shared between them and no byte-position
 * constant crosses from one to the other.
 *
 * <p>Assumptions: this projection takes part in the report read alone. The four data inputs of
 * that read are declared by {@code app/jcl/TRANREPT.jcl} at L65-L66, L67-L68, L69-L70 and
 * L71-L72, and are corroborated by the {@code COPY} set of {@code app/cbl/CBTRN03C.cbl} at L93,
 * L98, L103 and L108; the fifth entry at L73-L74 is the reporting date range supplied as a
 * parameter dataset and is not a join participant. So the sibling projections this row is
 * combined with are the card cross-reference, the transaction type and the transaction category,
 * and the combination is composed by a query in the repository layer rather than by any
 * relationship declared here. The statement read at {@code app/jcl/CREASTMT.JCL} L83-L86 is a
 * different read with a different transaction participant. {@code app/cpy/CVTRA07Y.cpy}, copied
 * by {@code CBTRN03C.cbl} at L113, is the report output layout rather than an input record and
 * is deliberately not projected by this package at all.
 *
 * <p>Assumptions: no attribute here carries an account identifier, and that absence is
 * deliberate. {@code app/cbl/CBTRN03C.cbl} builds each detail line in the paragraph beginning at
 * L361, and its L364 reads {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID}, so the account
 * identifier the report prints is sourced from the cross-reference record and belongs to
 * {@code CardXrefView}. Adding one here would duplicate a value this record never held.
 *
 * <p>Documentation contract: user-specified Rule 1 (Explainability) L15 requires a docstring on
 * this type and on every method it declares, including the private and the overriding ones, and
 * L22 makes the Javadoc block the required form for Java. Of the four content elements L18-L21
 * enumerates, Purpose at L18 applies to the type itself, while Parameters at L19 and Return
 * values at L20 apply per method and are carried by the at-clauses below. Exceptions or errors
 * at L21 is inapplicable throughout: no method declared in this file raises anything, and the
 * one place where a raise would be conceivable is argued where it arises. The written convention
 * this file conforms to is stated once at {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by
 * path and owned elsewhere, and the type-mapping rules shared by the seven projections in this
 * package are stated once in this package's own {@code package-info.java} rather than repeated
 * here.
 */
@Entity
// WHY : Trade-offs: mapped immutable, with no optimistic-locking version attribute, no setter,
//       no cascade and no write path of any kind. Three layers make that hold and the outermost
//       is the strongest: with no setter at all, an assignment meant as an edit does not
//       compile; with this annotation the provider excludes the type from dirty checking and
//       emits no update for it; and with every non-key column declared below as neither
//       insertable nor updatable, no value read here can travel back through this mapping. Only
//       after all three were bypassed would the SELECT-only database role become the last line,
//       and a failure there surfaces as an opaque privilege error at a call site far from the
//       assignment that caused it, which is the outcome this ordering exists to avoid. What is
//       given up is real and is accepted deliberately: dirty checking and the convenience of
//       merging a detached instance are forgone outright, and a value needing change has to be
//       changed by the context that owns the underlying table. The baseline shows the cost is
//       nominal: app/cbl/CBTRN03C.cbl opens its four data inputs for input only at L378, L414,
//       L432 and L450, opens the parameter dataset the same way at L468, opens exactly one
//       output at L396, and declares no record-replacing or record-removing verb anywhere --
//       its single writing verb, at L345, targets the report record.
@Immutable
// WHY : Alternatives Considered: this maps a database view, and this context declares no table.
//       Two other shapes were weighed. Declaring a table here was rejected because the rows are
//       already owned by another context -- app/jcl/TRANREPT.jcl L65-L66 supplies TRANFILE from
//       the transaction master, whose relational form is declared at
//       services/transaction-service/src/main/resources/db/migration/V1__ledger.sql -- so a
//       local copy would become a second truth for figures whose whole purpose is to state the
//       first one exactly. Reading a replica instead was rejected on the migration plan's own
//       ground that a replica adds cost and replica-lag semantics for no parity benefit: a
//       report disagreeing with the ledger by one replication interval is a support case rather
//       than a feature. What remains is the schema-ownership row the plan records for this
//       context with no owned tables at all, which is exactly what a view mapping expresses.
// WHY : Assumptions: agreement with the context that owns these rows runs through the physical
//       view and the narrowly-scoped SELECT-only privileges behind it, never through code. The
//       ArchUnit layering test at
//       services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java
//       forbids one context's domain package importing another's, and being a test it cannot
//       rot; this module also declares exactly one dependency inside the reactor, the shared
//       kernel, so no other service module is on its compile classpath to import from. The
//       database half is authored elsewhere and is cited by path:
//       data-migration/sql/V0__schemas_and_roles.sql establishes the schemas, the roles and the
//       read-only privileges reaching ledger at its L816, and the view named here is declared by
//       data-migration/sql/V1__reporting_views.sql, ordered after the per-service migrations
//       because a view cannot precede the table it reads. A view missing at run time is a defect
//       to report against those two artifacts and never one to work around from inside this
//       file. The name below is stated in full rather than left to a search path, so the row
//       source cannot change with connection configuration.
@Table(schema = "reporting", name = "v_report_transactions")
// WHY : Alternatives Considered: a Java record was the first shape considered for a thirteen-part
//       immutable row and it cannot be used, because a mapped persistent type is instantiated by
//       the provider through an accessible no-argument constructor and then has its state
//       written into it, which a record's final components forbid outright. A class with a
//       no-argument constructor for the provider, no setter for anyone else and a documented
//       all-argument constructor for construction in a test gives the same immutability from the
//       outside. Lombok was rejected for an unrelated and stronger reason: its generated
//       accessors cannot carry the Javadoc user-specified Rule 1 L15 requires on every method.
public class ReportTransactionView {

    // WHY : Alternatives Considered: a single-column identifier, not a composite. TRAN-ID at
    //       app/cpy/CVTRA05Y.cpy L5 is the key the baseline itself declares for this record, and
    //       the relational form agrees: V1__ledger.sql declares pk_transactions over
    //       transaction_id alone. The composite alternative genuinely exists but belongs to the
    //       other transaction record: app/cpy/COSTM01.CPY L21-L23 declares TRNX-KEY as the card
    //       number followed by the identifier, 32 bytes in all, because that dataset is re-keyed
    //       for the statement read. Carrying that composite here would key a row by a value this
    //       record places 262 bytes further along.
    // WHY : Assumptions: CHAR(16) and never a numeric type, even though every measured value is
    //       all digits. app/cpy/CVTRA05Y.cpy L5 declares PIC X(16), an alphanumeric picture, so
    //       the declared width is part of the contract and a leading zero is data rather than
    //       presentation. V1__ledger.sql carries the same reading, declaring transaction_id as
    //       CHAR(16) and recording measured values such as 0000000000683580 that an integer
    //       column would shorten.
    @Id
    @Column(name = "transaction_id", length = 16)
    private String transactionId;

    // WHY : Assumptions: every layout member here is scoped to app/cpy/CVTRA05Y.cpy and every
    //       citation names that copybook, because the member names collide across the copybook
    //       library and a repository-wide name map cannot be built. TRAN-TYPE-CD and TRAN-CAT-CD
    //       are declared at CVTRA05Y.cpy L6 and L7 and again at CVTRA04Y.cpy L6 and L7; the same
    //       two-byte type code is named TRAN-TYPE with no code suffix at CVTRA03Y.cpy L5; and
    //       the group name TRAN-CAT-KEY is six bytes at CVTRA04Y.cpy L5 but seventeen at
    //       CVTRA01Y.cpy L5. The baseline adopts this discipline itself, qualifying precisely
    //       the two ambiguous names with OF TRAN-RECORD at app/cbl/CBTRN03C.cbl L365 and L367
    //       and leaving every unambiguous name bare.
    @Column(name = "type_cd", length = 2, insertable = false, updatable = false)
    private String typeCd;

    // WHY : Assumptions: the category code is carried as a four-character value rather than as a
    //       small integer, and the two readings really do differ. app/cpy/CVTRA05Y.cpy L7
    //       declares PIC 9(04), which a width-only reading would map to a small integer since
    //       9999 fits one, but the code is a label rather than a magnitude: no program performs
    //       arithmetic on it and its leading zeros are significant. Two independent sources
    //       settle it. V1__ledger.sql declares this column as CHAR(4) and records that every
    //       value in app/data/ASCII/tcatbal.txt is 0001, which an integer column would render as
    //       1; and the baseline's own relational expression of the same field,
    //       app/app-transaction-type-db2/ddl/TRNTYCAT.ddl L3, declares
    //       TRC_TYPE_CATEGORY CHAR(4) NOT NULL. A projection over a four-character column must
    //       agree with it, or the leading zeros are lost between the view and the report line.
    @Column(name = "category_cd", length = 4, insertable = false, updatable = false)
    private String categoryCd;

    // WHY : Assumptions: the source is carried at the declared width of ten that
    //       app/cpy/CVTRA05Y.cpy L8 gives it, rather than as variable-length text, which
    //       preserves the blank-padded comparison the baseline performs. COBOL compares a
    //       PIC X(10) member padded to its full width, so POS TERM
    //       and POS TERM followed by two blanks are one value under the source semantics and
    //       would be two under a variable-length column. V1__ledger.sql declares this column
    //       CHAR(10) for that reason and records that both distinct values measured in
    //       app/data/ASCII/dailytran.txt are shorter than the declared width and blank-padded on
    //       all 300 records.
    @Column(name = "source", length = 10, insertable = false, updatable = false)
    private String source;

    // WHY : Assumptions: the description is the one member here whose trailing blanks are
    //       padding to the record length rather than part of a code, so it is carried as
    //       variable-length text with the 100 that app/cpy/CVTRA05Y.cpy L9 declares as its
    //       ceiling. V1__ledger.sql declares
    //       it VARCHAR(100) on the same reading and records that the longest trimmed value
    //       measured in app/data/ASCII/dailytran.txt is 48 of the declared 100 characters.
    @Column(name = "description", length = 100, insertable = false, updatable = false)
    private String description;

    // WHY : Assumptions: the amount stays at precision 11 and scale 2 and is never widened to
    //       match an account balance. app/cpy/CVTRA05Y.cpy L10 declares TRAN-AMT as
    //       PIC S9(09)V99, nine integer digits and two fractional digits, whereas
    //       app/cpy/CVACT01Y.cpy L7 declares ACCT-CURR-BAL as PIC S9(10)V99, one integer digit
    //       wider, which is precision 12 and belongs to AccountView. The report corroborates the
    //       narrower width from inside: app/cbl/CBTRN03C.cbl L134, L135 and L136 declare
    //       WS-PAGE-TOTAL, WS-ACCOUNT-TOTAL and WS-GRAND-TOTAL as PIC S9(09)V99 each, so the
    //       accumulators are exactly as wide as the member they accumulate and the report never
    //       needs the wider precision. Unifying the two would silently widen a declared contract,
    //       and a column accepting a value the source record cannot express stops being evidence
    //       of what the source record held.
    // WHY : Assumptions: the value is held as the shared money type and never as a bare decimal,
    //       and never as an IEEE-754 binary floating point value, because such a representation
    //       cannot hold an exact cent and the whole chain from view to response exists to hold
    //       one. The eleven bytes app/cpy/CVTRA05Y.cpy L10 declares become an exact numeric column
    //       at scale 2, the Java attribute is the shared money type at the same scale, and the
    //       conversion between the two is the nested converter named here.
    @Column(name = "amount", precision = 11, scale = 2, insertable = false, updatable = false)
    @Convert(converter = MoneyAmountConverter.class)
    private Money amount;

    // WHY : Assumptions: the merchant identifier is a magnitude rather than a label, so it is
    //       carried as an integer while the category code above is not. app/cpy/CVTRA05Y.cpy L11
    //       declares PIC 9(09), and V1__ledger.sql declares the column BIGINT on the evidence
    //       that every value measured in app/data/ASCII/dailytran.txt is 800000000 with no
    //       significant leading zero, and that app/cbl/COBIL00C.cbl L226 writes the all-nines
    //       sentinel 999999999 for a bill payment, so the column has to hold the full nine-digit
    //       width exactly.
    // WHY : Assumptions: both PIC 9 members in this record, the category code at L7 and this
    //       identifier at L11, are unsigned, and that is a geometry fact before it is a typing
    //       one. An unsigned display member occupies exactly its digit count in bytes with no
    //       separate sign position, which is what lets the fourteen declared widths sum to
    //       exactly 350 and puts the card number at 263.
    @Column(name = "merchant_id", insertable = false, updatable = false)
    private Long merchantId;

    // WHY : Assumptions: descriptive text, so variable-length by the same reading as the
    //       description above; V1__ledger.sql declares merchant_name VARCHAR(50) and records a
    //       longest trimmed value of 36 of the declared 50 characters. app/cpy/CVTRA05Y.cpy L12
    //       declares PIC X(50).
    @Column(name = "merchant_name", length = 50, insertable = false, updatable = false)
    private String merchantName;

    // WHY : Assumptions: descriptive text on the same reading, from app/cpy/CVTRA05Y.cpy L13;
    //       V1__ledger.sql declares merchant_city VARCHAR(50) and records a longest trimmed
    //       value of 19 of the declared 50 characters.
    @Column(name = "merchant_city", length = 50, insertable = false, updatable = false)
    private String merchantCity;

    // WHY : Assumptions: the postal code is matched as a code rather than read as text, so it is
    //       carried at its declared width of ten. app/cpy/CVTRA05Y.cpy L14 declares PIC X(10),
    //       and V1__ledger.sql declares merchant_zip CHAR(10) on the evidence that 27 of the 300
    //       values measured in app/data/ASCII/dailytran.txt begin with a zero, so its leading
    //       zeros are significant and its blank padding participates in comparison.
    @Column(name = "merchant_zip", length = 10, insertable = false, updatable = false)
    private String merchantZip;

    // WHY : Assumptions: the card number is CHAR(16) and never a numeric type, and the two
    //       available sources disagree in a way that has to be resolved rather than averaged.
    //       app/cpy/CVTRA05Y.cpy L15 declares PIC X(16), alphanumeric, while
    //       app/jcl/TRANREPT.jcl L41 types the very same bytes TRAN-CARD-NUM,263,16,ZD, zoned
    //       decimal, for the sort utility. The copybook is normative under the migration plan's
    //       transformation rule T1, so the alphanumeric declaration wins and the column is
    //       CHAR(16). The sort control's choice is harmless where it stands, because for an
    //       unsigned all-digit member of invariant width the character and zoned collating orders
    //       coincide, but it must not propagate into the schema: V1__ledger.sql records that 30
    //       of the 300 card numbers measured in app/data/ASCII/dailytran.txt carry a leading
    //       zero, and an integer column would shorten each of those to fifteen digits and break
    //       every lookup on the value.
    // WHY : Alternatives Considered: the report query that reads this view adds the transaction
    //       identifier as a stable secondary ordering key behind this card number, and that is a
    //       deliberate departure. app/jcl/TRANREPT.jcl L46 reads SORT FIELDS=(TRAN-CARD-NUM,A),
    //       a single sort key with no EQUALS option, so the baseline leaves the order among rows
    //       sharing a card number unspecified. Reproducing that single key exactly was possible
    //       and was rejected, because an unspecified order cannot be compared against a golden
    //       master: two runs over identical data could interleave the same card's rows
    //       differently and every byte after the first difference would read as a regression.
    //       Adding the identifier accepts a total order the baseline never guaranteed in exchange
    //       for output that is reproducible run to run, and it costs nothing else because the
    //       identifier is unique. Only the report read needs this decision: the statement
    //       pipeline already sorts on two keys, at app/jcl/CREASTMT.JCL L53
    //       SORT FIELDS=(263,16,CH,A,1,16,CH,A). The ordering itself is expressed by the query in
    //       the repository layer, so nothing about it is declared on this type.
    @Column(name = "card_num", length = 16, insertable = false, updatable = false)
    private String cardNum;

    // WHY : Assumptions: a timestamp of twenty-six blanks is a legitimate value in this pipeline
    //       rather than an error to reject, so this attribute and the processing timestamp below
    //       are both left able to hold nothing and neither is declared non-null here.
    //       app/cpy/CVTRA05Y.cpy declares both as PIC X(26), at L16 and L17, and a blank
    //       twenty-six-character member is what a COBOL initialised record holds before a value
    //       is moved into it. The pipeline produces partly blank ones on purpose:
    //       app/jcl/CREASTMT.JCL L54 reads OUTREC FIELDS=(1:263,16,17:1,262,279:279,50), whose
    //       fifty bytes taken from position 279 cover the whole originating timestamp and only
    //       twenty-four of the twenty-six characters of the processing timestamp. Asserting
    //       non-null in the mapping would turn a value the baseline writes deliberately into an
    //       exception raised at the mapping layer, where the report can render it instead.
    // WHY : Assumptions: a zone-free date and time type, never a zone-bearing or zone-shifted
    //       one. app/cpy/CVTRA05Y.cpy declares both timestamps as PIC X(26), at L16 and L17, and
    //       a twenty-six-character alphanumeric member carries no zone at all, so attaching one
    //       would invent information the source record never held and make two runs in two
    //       regions disagree about the same row. Those 26 characters map to microsecond
    //       resolution exactly: nineteen to the second, a point, then six fractional digits,
    //       truncating no digit on the way in and inventing none on the way out.
    @Column(name = "orig_ts", insertable = false, updatable = false)
    private LocalDateTime origTs;

    // WHY : Assumptions: the leading ten characters of this twenty-six-character member hold the
    //       year-month-day form, so a comparison over them orders the same way a date comparison
    //       does. The evidence is direct: app/jcl/TRANREPT.jcl L42 declares
    //       TRAN-PROC-DT,305,10,CH, selecting exactly the leading ten bytes of the member the
    //       copybook declares at L17 as PIC X(26) starting at the same position, and selecting a
    //       ten-byte prefix of a timestamp is only a meaningful sort key because that prefix is
    //       year-major. That is what lets the reporting date range be expressed as an ordinary
    //       predicate over this column.
    // WHY : Assumptions: the reporting date range is a row-selection predicate and never a step
    //       gate, and the two forms are easy to conflate because they share a keyword.
    //       app/jcl/TRANREPT.jcl L47-L48 reads
    //       INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND, TRAN-PROC-DT,LE,PARM-END-DATE)
    //       inside a sort step, with the two literals declared in SYMNAMES at L43 and L44, and
    //       that selects records rather than deciding whether a step runs; the range is inclusive
    //       at both ends. Elsewhere in the same tree COND= on an EXEC statement does gate a step,
    //       and modelling this one as a gate would silently report every transaction. The
    //       predicate belongs to the query in the repository layer, so it is not declared here.
    // WHY : Assumptions: no index is declared on this type, and that is ownership rather than
    //       oversight. The two indexes over these rows belong to the context that owns the table,
    //       declared in V1__ledger.sql at L275 and L299 as idx_transactions_card_num and
    //       idx_transactions_proc_ts; the baseline equivalent is the alternate index
    //       app/jcl/TRANIDX.jcl declares at L27 with KEYS(26 304), zero-based position 304 being
    //       one-based 305 and therefore exactly this member. The BLDINDEX step that job runs at
    //       L52 has no successor at all, because PostgreSQL maintains an index transactionally
    //       and needs no separate build. A projection over a view cannot own an index in any
    //       case, so declaring one here would assert a structure this context does not create.
    @Column(name = "proc_ts", insertable = false, updatable = false)
    private LocalDateTime procTs;

    // WHY : Assumptions: the trailing FILLER PIC X(20) that app/cpy/CVTRA05Y.cpy declares at L18,
    //       occupying one-based positions 331 to 350, has no attribute above and no column below.
    //       It is padding to the declared 350-byte record length and holds nothing a reader of a
    //       report could act on, so mapping it would add a member whose only content is blanks.
    //       Naming the line it came from is what keeps its removal auditable rather than
    //       invisible, which is what the migration plan's normative-copybook rule T1 asks for.
    //       Its absence is also why thirteen attributes describe a fourteen-member record.
    // WHY : Assumptions: no relationship is declared to any sibling projection in this package.
    //       The three rows this one is combined with live in schemas another context owns, reached
    //       by read-only privileges rather than by a compile-time bond, so a relationship
    //       annotation here would assert a foreign key this context neither declares nor can
    //       enforce. The four-way combination is composed by a query over the physical views in
    //       the repository layer, which is the same discipline the baseline read under:
    //       app/cbl/CBTRN03C.cbl reached its four data inputs through job control alone, declared
    //       at app/jcl/TRANREPT.jcl L65-L66, L67-L68, L69-L70 and L71-L72.

    /**
     * Creates an unpopulated instance for the persistence provider to hydrate. Application code
     * has no use for it and should reach for the all-argument constructor instead.
     *
     * <p>Purpose: a mapped persistent type is instantiated reflectively through a no-argument
     * constructor and then has each mapped attribute written into it, so this constructor exists
     * to satisfy that contract and nothing else. It is declared protected rather than public
     * because the provider reaches it reflectively and no caller outside this hierarchy has a
     * reason to produce a row with every attribute absent.
     */
    protected ReportTransactionView() {
        // WHY : Assumptions: the body is deliberately empty because the provider writes all 13
        //       attributes -- the members app/cpy/CVTRA05Y.cpy declares at L5 through L17 --
        //       directly after this returns, so assigning a placeholder here would be overwritten
        //       immediately and would only obscure which values came from the view.
    }

    /**
     * Creates a fully populated instance from thirteen already-decoded column values. This is the
     * constructor a unit test uses to build a row without a database.
     *
     * <p>Purpose: it accepts the thirteen mapped members of {@code app/cpy/CVTRA05Y.cpy} in their
     * declared order, L5 through L17, so the argument list reads in the same sequence as the
     * record and a transposition is visible on inspection.
     *
     * <p>Assumptions: no argument is validated and none is rejected. The values a caller supplies
     * are the values the view produced, blanks and absences included, and all 13 argument shapes
     * are already settled by the declared widths at {@code app/cpy/CVTRA05Y.cpy} L5 through L17
     * and by the column declarations above. Rejecting one here would turn a legitimate absence
     * into an exception raised at the mapping layer, which is precisely the outcome the
     * twenty-six-blank reasoning at L16 and L17 rules out.
     *
     * @param transactionId the sixteen-character transaction identifier from L5, the key this row
     *     is retrieved and ordered by
     * @param typeCd the two-character transaction type code from L6, which the report resolves to
     *     a description through the transaction-type row
     * @param categoryCd the four-character transaction category code from L7, carried with its
     *     leading zeros intact
     * @param sourceValue the ten-character origin of the transaction from L8, blank-padded to its
     *     declared width
     * @param description the descriptive text from L9, up to the declared 100 characters with
     *     trailing padding removed
     * @param amount the transaction amount from L10 at scale 2, or {@code null} when the view
     *     returned no amount
     * @param merchantId the nine-digit merchant identifier from L11, held as a magnitude
     * @param merchantName the merchant name from L12, up to the declared 50 characters
     * @param merchantCity the merchant city from L13, up to the declared 50 characters
     * @param merchantZip the ten-character merchant postal code from L14, carried with its leading
     *     zeros intact
     * @param cardNum the sixteen-character card number from L15, the report's primary ordering key
     * @param origTs the originating timestamp from L16, or {@code null} where the source carried
     *     twenty-six blanks
     * @param procTs the processing timestamp from L17, or {@code null} where the source carried
     *     twenty-six blanks
     */
    public ReportTransactionView(
            String transactionId,
            String typeCd,
            String categoryCd,
            String sourceValue,
            String description,
            Money amount,
            Long merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String cardNum,
            LocalDateTime origTs,
            LocalDateTime procTs) {

        // WHY : Assumptions: the fourth parameter is named sourceValue while the attribute it
        //       feeds is named source, because source is the member name
        //       app/cpy/CVTRA05Y.cpy L8 declares and the attribute keeps it, whereas a parameter
        //       of that name inside this constructor would shadow nothing useful and read as the
        //       source of the row rather than as the origin of the transaction.
        this.transactionId = transactionId;
        this.typeCd = typeCd;
        this.categoryCd = categoryCd;
        this.source = sourceValue;
        this.description = description;
        this.amount = amount;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.cardNum = cardNum;
        this.origTs = origTs;
        this.procTs = procTs;
    }

    /**
     * Returns the transaction identifier. It is the key this row is retrieved by and the stable
     * secondary ordering key the report query adds behind the card number.
     *
     * @return the sixteen-character identifier declared at {@code app/cpy/CVTRA05Y.cpy} L5, with
     *     any leading zeros intact
     */
    public String transactionId() {
        return transactionId;
    }

    /**
     * Returns the transaction type code. The report resolves it to a description by combining this
     * row with the transaction-type row.
     *
     * @return the two-character code declared at {@code app/cpy/CVTRA05Y.cpy} L6, or {@code null}
     *     where the view returned none
     */
    public String typeCd() {
        return typeCd;
    }

    /**
     * Returns the transaction category code. It is a label rather than a magnitude, so its leading
     * zeros are part of the value.
     *
     * @return the four-character code declared at {@code app/cpy/CVTRA05Y.cpy} L7, or {@code null}
     *     where the view returned none
     */
    public String categoryCd() {
        return categoryCd;
    }

    /**
     * Returns the origin of the transaction. The baseline carries a small closed set of values
     * here, blank-padded to the declared width.
     *
     * @return the ten-character origin declared at {@code app/cpy/CVTRA05Y.cpy} L8, or
     *     {@code null} where the view returned none
     */
    public String source() {
        return source;
    }

    /**
     * Returns the descriptive text of the transaction. It is the one member of this record whose
     * trailing blanks are padding rather than part of a code.
     *
     * @return the description declared at {@code app/cpy/CVTRA05Y.cpy} L9, up to 100 characters,
     *     or {@code null} where the view returned none
     */
    public String description() {
        return description;
    }

    /**
     * Returns the transaction amount as an exact value at scale 2. The report accumulates it into
     * the page, account and grand totals without ever leaving that scale.
     *
     * @return the amount declared at {@code app/cpy/CVTRA05Y.cpy} L10 as nine integer digits and
     *     two fractional digits, or {@code null} where the view returned none
     */
    public Money amount() {
        return amount;
    }

    /**
     * Returns the merchant identifier. It is a magnitude rather than a label, which is why it is
     * held as an integer where the category code is not.
     *
     * @return the nine-digit identifier declared at {@code app/cpy/CVTRA05Y.cpy} L11, or
     *     {@code null} where the view returned none
     */
    public Long merchantId() {
        return merchantId;
    }

    /**
     * Returns the merchant name. It is descriptive text and takes no part in any lookup.
     *
     * @return the name declared at {@code app/cpy/CVTRA05Y.cpy} L12, up to 50 characters, or
     *     {@code null} where the view returned none
     */
    public String merchantName() {
        return merchantName;
    }

    /**
     * Returns the merchant city. It is descriptive text and takes no part in any lookup.
     *
     * @return the city declared at {@code app/cpy/CVTRA05Y.cpy} L13, up to 50 characters, or
     *     {@code null} where the view returned none
     */
    public String merchantCity() {
        return merchantCity;
    }

    /**
     * Returns the merchant postal code. It is matched as a code, so its leading zeros and its
     * blank padding are both part of the value.
     *
     * @return the ten-character postal code declared at {@code app/cpy/CVTRA05Y.cpy} L14, or
     *     {@code null} where the view returned none
     */
    public String merchantZip() {
        return merchantZip;
    }

    /**
     * Returns the card number the report orders by. It is the value the card-change break in the
     * baseline detects, and it is never returned to a client from this type unmodified.
     *
     * @return the sixteen-character card number declared at {@code app/cpy/CVTRA05Y.cpy} L15,
     *     with any leading zeros intact, or {@code null} where the view returned none
     */
    public String cardNum() {
        return cardNum;
    }

    /**
     * Returns the originating timestamp of the transaction. A row whose source carried
     * twenty-six blanks here yields nothing rather than an error.
     *
     * @return the originating timestamp declared at {@code app/cpy/CVTRA05Y.cpy} L16 at
     *     microsecond resolution, or {@code null} where the source carried blanks
     */
    public LocalDateTime origTs() {
        return origTs;
    }

    /**
     * Returns the processing timestamp of the transaction. The reporting date range is applied to
     * this value, and its leading ten characters are the part the baseline sorts on.
     *
     * @return the processing timestamp declared at {@code app/cpy/CVTRA05Y.cpy} L17 at microsecond
     *     resolution, or {@code null} where the source carried blanks
     */
    public LocalDateTime procTs() {
        return procTs;
    }

    /**
     * Compares this row with another for equality on the transaction identifier alone. Two rows
     * carrying the same identifier denote the same posted transaction.
     *
     * <p>Alternatives Considered: comparing all thirteen attributes was evaluated and rejected.
     * The identifier is the key the baseline declares for this record at
     * {@code app/cpy/CVTRA05Y.cpy} L5 and the key the relational form declares as
     * {@code pk_transactions}, so it already determines the row uniquely; comparing every
     * attribute as well would make two reads of one row unequal the moment a query populated a
     * different subset of columns, which would quietly break membership in a set or use as a map
     * key. The identity comparison also stays cheap on the wide rows a report streams.
     *
     * <p>Assumptions: an instance produced by the no-argument constructor and not yet hydrated
     * carries no identifier, and two such instances compare equal because the sixteen-character
     * member {@code app/cpy/CVTRA05Y.cpy} declares at L5 is the only value compared and there is
     * nothing else to tell them apart. The provider never publishes an instance in that state, so
     * the case is recorded rather than guarded against.
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is a row of this type carrying an equal transaction
     *     identifier, {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Assumptions: a pattern match is used rather than a class comparison because the
        //       provider may hand back an instrumented subclass, and a strict class comparison
        //       would then report a row as unequal to itself. No subclass of this type is
        //       authored, so widening the test costs nothing, and the single value it compares is
        //       the sixteen-character member app/cpy/CVTRA05Y.cpy declares at L5.
        if (!(other instanceof ReportTransactionView that)) {
            return false;
        }
        return Objects.equals(transactionId, that.transactionId);
    }

    /**
     * Returns a hash consistent with the identifier-only equality above. It is derived from the
     * transaction identifier and from nothing else.
     *
     * <p>Assumptions: the value stays stable for the life of an instance, because this type has no
     * setter and the sixteen-character identifier declared at {@code app/cpy/CVTRA05Y.cpy} L5 is
     * written once by the provider before the instance is reachable. That is what makes the type
     * safe to place in a hash-based collection, which an entity with a mutable key would not be.
     *
     * @return the hash of the transaction identifier, or the hash of an absent value on an
     *     instance the provider has not yet hydrated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(transactionId);
    }

    /**
     * Returns a diagnostic rendering of this row that deliberately names only two of its thirteen
     * attributes. It is intended for a log line or an assertion message.
     *
     * <p>Trade-offs: the sixteen-character card number declared at {@code app/cpy/CVTRA05Y.cpy}
     * L15 and the eleven-byte amount declared at L10 are omitted outright rather than abbreviated.
     * Omission is chosen over abbreviation because abbreviating a primary account number is
     * masking, and masking belongs to this context's mapper package, which the module charter
     * names as the sole boundary where it may appear; putting a second, slightly different masking
     * rule here would give the same value two renderings and make neither authoritative. The cost
     * accepted is that a reader cannot identify a row's card or its value from a log line, and the
     * compensation is that no log line written from this type can carry a primary account number
     * or a monetary figure at all. The two of the thirteen attributes retained are the identifier
     * from L5, which locates the row exactly, and the processing timestamp from L17, which places
     * it in the reporting range.
     *
     * @return a short single-line rendering naming the type, the transaction identifier and the
     *     processing timestamp, and no other attribute
     */
    @Override
    public String toString() {
        return "ReportTransactionView[transactionId=" + transactionId + ", procTs=" + procTs + ']';
    }

    /**
     * Converts the transaction amount between the shared money type and the exact numeric column
     * the view exposes. It is applied to that one attribute and is never applied automatically.
     *
     * <p>Purpose: the shared money type is declared final with a non-public constructor, so the
     * persistence provider cannot instantiate it and a converter is the supported way to map it.
     * This one moves between the money type at scale 2 and the decimal form the column carries,
     * in both directions, and takes no rounding decision in either because the column's scale
     * already equals the money scale.
     *
     * <p>Alternatives Considered: three placements were weighed and this one was chosen. Declaring
     * the converter in the shared kernel beside the money type would have been the natural home
     * and is not available, because the kernel ships no converter and this file may not author one
     * there. Adding a converter file to this package was rejected because the package's closed set
     * of 8 files is a stated contract in its own {@code package-info.java}, and a ninth file would
     * be a defect rather than an addition. Registering a converter to apply automatically to every
     * money attribute in the reactor was rejected as the worst of the three: the amount declared
     * at {@code app/cpy/CVTRA05Y.cpy} L10 is {@code PIC S9(09)V99} while the balance declared at
     * {@code app/cpy/CVACT01Y.cpy} L7 is {@code PIC S9(10)V99}, one integer digit wider, so an
     * automatic registration would reach attributes of two different declared precisions and would
     * do so silently. A nested converter named explicitly at the one attribute it serves cannot
     * reach anything else.
     *
     * <p>Trade-offs: the sibling transaction projection needs the same conversion and carries its
     * own copy, so a short conversion is stated twice across the package. That duplication is
     * accepted because the alternatives are a ninth file the package contract forbids or an
     * automatic registration that would apply where it was never inspected, and because the two
     * copies cannot drift in behaviour that matters: each is two statements over an amount that
     * both source layouts declare identically, {@code PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy} L10 and at {@code app/cpy/COSTM01.CPY} L29, and whose scale of
     * 2 is settled by the column rather than by either copy.
     */
    @Converter
    public static final class MoneyAmountConverter implements AttributeConverter<Money, BigDecimal> {

        /**
         * Converts a money value to the decimal form the column carries. The result is exact and
         * carries scale 2.
         *
         * <p>Assumptions: this direction is reached on a read rather than on a write, which is why
         * it is implemented faithfully rather than as a refusal. The projection has no write path,
         * but a query comparing the amount column -- precision 11, scale 2, from the
         * {@code PIC S9(09)V99} declared at {@code app/cpy/CVTRA05Y.cpy} L10 -- against a money
         * value binds that value as a parameter, and the provider converts it through this method
         * to do so. It is also half of an interface that cannot be implemented in one direction
         * only.
         *
         * @param attribute the amount to render as a decimal, or {@code null} when the attribute
         *     is absent
         * @return the amount at scale 2, or {@code null} when {@code attribute} is {@code null},
         *     so that an absent amount stays absent instead of becoming zero
         */
        @Override
        public BigDecimal convertToDatabaseColumn(Money attribute) {
            if (attribute == null) {
                return null;
            }
            return attribute.amount();
        }

        /**
         * Converts a column value to the shared money type. The conversion is exact and raises
         * nothing.
         *
         * <p>Assumptions: the absent case is returned rather than substituted, because the column
         * this converter serves -- the {@code PIC S9(09)V99} declared at
         * {@code app/cpy/CVTRA05Y.cpy} L10 -- carries no non-null assertion, and a row whose
         * amount is absent is a row the report renders rather than one it rejects. Substituting
         * zero would make an absent amount indistinguishable from a genuine zero, which the three
         * accumulators at {@code app/cbl/CBTRN03C.cbl} L134, L135 and L136 would then add.
         *
         * <p>Assumptions: the money factory this method calls rejects an absent argument and
         * rejects a magnitude beyond its own ceiling, and neither rejection can occur here. The
         * absent argument is returned before the call is reached, and the column's domain is
         * strictly narrower than the factory's: the {@code PIC S9(09)V99} declared at
         * {@code app/cpy/CVTRA05Y.cpy} L10 gives nine integer digits, while the factory's ceiling
         * accommodates the ten that {@code app/cpy/CVACT01Y.cpy} L7 declares for an account
         * balance, so every value this column can hold is a value the factory accepts. That
         * containment is why this method declares no raised condition, and it is also why widening
         * the column would have to be re-argued rather than assumed harmless.
         *
         * @param dbData the decimal value read from the column, or {@code null} when the column
         *     held nothing
         * @return the amount at scale 2, or {@code null} when {@code dbData} is {@code null}
         */
        @Override
        public Money convertToEntityAttribute(BigDecimal dbData) {
            if (dbData == null) {
                return null;
            }
            return Money.of(dbData);
        }
    }
}
