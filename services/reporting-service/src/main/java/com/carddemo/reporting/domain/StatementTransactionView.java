package com.carddemo.reporting.domain;

import com.carddemo.common.money.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of the card-ordered transaction record that drives statement generation.
 *
 * <p>One row of this projection is one row of the view
 * {@code reporting.v_statement_transactions}, declared by
 * {@code data-migration/sql/V1__reporting_views.sql} as a card-number-leading projection of
 * {@code ledger.transactions}, and that view's row shape is the 350-byte record declared
 * as {@code 01 TRNX-RECORD} at L20 of {@code app/cpy/COSTM01.CPY}. The copybook has exactly one
 * consumer in the baseline: {@code app/cbl/CBSTM03A.CBL} copies it at L51. Note the uppercase
 * extension, which is not a transcription slip -- that member is the single uppercase-extension
 * copybook in {@code app/cpy}, so a lowercase spelling of its name points at nothing.</p>
 *
 * <h2>Why a second transaction record exists at all</h2>
 *
 * <p>The baseline holds two 350-byte transaction records that carry the same field names at different
 * one-based positions, and they are not two spellings of one thing. The record mapped here places the
 * card number at positions 1 through 16 and the transaction identifier at 17 through 32. The posting
 * output record, declared in {@code app/cpy/CVTRA05Y.cpy}, places the transaction identifier at 1
 * through 16 -- its L5, which is the indexed key of the ledger cluster -- and the card number at 263
 * through 278.</p>
 *
 * <p>The re-keying is deliberate and its mechanism is legible in one job: STEP010 of
 * {@code app/jcl/CREASTMT.JCL} at L44 invokes a sort whose L53 statement orders by card number then
 * transaction identifier and whose L54 {@code OUTREC} rebuilds each record with the card number
 * hoisted to position 1, and STEP020 at L56 loads the sorted result into an indexed cluster. The
 * card-leading order is therefore the access path the statement read exists to use, and this
 * projection preserves it as its declared identity.</p>
 *
 * <p>Assumptions: the report transaction record and this statement transaction record stay two
 * separate types with no shared mapped ancestor, because their geometry genuinely differs rather than
 * merely their names. Position 1 through 16 holds a card number here and a transaction identifier in
 * {@code CVTRA05Y.cpy}; positions 263 through 278 hold a card number there and part of a merchant
 * city here. Both records are 350 bytes and both carry the same thirteen field names, so a single
 * mapped ancestor would compile, run, and read a card number out of the bytes where an identifier
 * lives. That is why a shared superclass, a shared mapped ancestor and a shared attribute converter
 * for the two key components are all declined here.</p>
 *
 * <h2>Record geometry, asserted from two agreeing sources</h2>
 *
 * <p>Every column below names the copybook line its width was read from, and the arithmetic closes
 * exactly. {@code TRNX-KEY} at L21 is two sixteen-character fields, so 32 bytes; {@code TRNX-REST} at
 * L24 sums to 318 bytes; and 32 plus 318 is 350.</p>
 *
 * <p>Assumptions: that the sum closes at 350 and not at 351 is itself the evidence that the amount's
 * sign is overpunched onto a digit rather than carried in a byte of its own. The amount at L29 is
 * {@code PIC S9(09)V99}, and counting it as eleven bytes -- nine integer digits plus two decimal
 * digits, with the sign occupying no position -- is what makes the record close on the 350 that L50 of
 * {@code app/jcl/CREASTMT.JCL} declares as {@code LRECL=350} and that L61 through L63 of
 * {@code app/cbl/CBSTM03B.CBL} reach independently. Counting a separate sign byte would put every
 * field after position 148 one byte out and the record at 351, which is why the width is stated as
 * eleven here rather than left to a reader's assumption about signed display fields.</p>
 *
 * <ul>
 *   <li>L22 {@code TRNX-CARD-NUM PIC X(16)}, one-based 1 through 16, column {@code card_num}
 *       {@code CHAR(16)}, held as a {@code String}. </li>
 *   <li>L23 {@code TRNX-ID PIC X(16)}, one-based 17 through 32, column {@code transaction_id}
 *       {@code CHAR(16)}, held as a {@code String}. </li>
 *   <li>L25 {@code TRNX-TYPE-CD PIC X(02)}, one-based 33 through 34, column {@code type_cd}
 *       {@code CHAR(2)}, held as a {@code String}. </li>
 *   <li>L26 {@code TRNX-CAT-CD PIC 9(04)}, one-based 35 through 38, column {@code category_cd}
 *       {@code CHAR(4)}, held as a {@code String}. </li>
 *   <li>L27 {@code TRNX-SOURCE PIC X(10)}, one-based 39 through 48, column {@code source}
 *       {@code CHAR(10)}, held as a {@code String}. </li>
 *   <li>L28 {@code TRNX-DESC PIC X(100)}, one-based 49 through 148, column {@code description}
 *       {@code VARCHAR(100)}, held as a {@code String}. </li>
 *   <li>L29 {@code TRNX-AMT PIC S9(09)V99}, one-based 149 through 159, eleven bytes, column
 *       {@code amount} {@code NUMERIC(11,2)}, held as a {@link Money}. </li>
 *   <li>L30 {@code TRNX-MERCHANT-ID PIC 9(09)}, one-based 160 through 168, column
 *       {@code merchant_id} {@code BIGINT}, held as a {@code Long}. </li>
 *   <li>L31 {@code TRNX-MERCHANT-NAME PIC X(50)}, one-based 169 through 218, column
 *       {@code merchant_name} {@code VARCHAR(50)}, held as a {@code String}. </li>
 *   <li>L32 {@code TRNX-MERCHANT-CITY PIC X(50)}, one-based 219 through 268, column
 *       {@code merchant_city} {@code VARCHAR(50)}, held as a {@code String}. </li>
 *   <li>L33 {@code TRNX-MERCHANT-ZIP PIC X(10)}, one-based 269 through 278, column
 *       {@code merchant_zip} {@code CHAR(10)}, held as a {@code String}. </li>
 *   <li>L34 {@code TRNX-ORIG-TS PIC X(26)}, one-based 279 through 304, column {@code orig_ts}
 *       {@code TIMESTAMP(6)}, held as a {@link LocalDateTime}, nullable. </li>
 *   <li>L35 {@code TRNX-PROC-TS PIC X(26)}, one-based 305 through 330, column {@code proc_ts}
 *       {@code TIMESTAMP(6)}, held as a {@link LocalDateTime}, nullable. </li>
 *   <li>L36 {@code FILLER PIC X(20)}, one-based 331 through 350, dropped and mapped to no column. </li>
 * </ul>
 *
 * <p>An independent oracle carrying no copybook at all reaches the same 350.
 * {@code app/cbl/CBSTM03B.CBL} declares {@code FD TRNX-FILE} at L58, and its file record is
 * {@code PIC X(16)} at L61 plus {@code PIC X(16)} at L62 plus {@code PIC X(318)} at L63. Two unrelated
 * sources agreeing on the key width, the remainder width and the total is why these positions are
 * asserted here rather than estimated. A third sighting of the same 318 sits at L230 of
 * {@code app/cbl/CBSTM03A.CBL}, in the working-storage row that program stages a browsed record
 * into.</p>
 *
 * <h2>Which read this projection serves</h2>
 *
 * <p>It participates in the statement read and in no other. STEP040 of {@code app/jcl/CREASTMT.JCL}
 * at L79 invokes {@code CBSTM03A} with four data inputs: {@code TRNXFILE} at L83, {@code XREFFILE} at
 * L84, {@code ACCTFILE} at L85 and {@code CUSTFILE} at L86. That program's own copybook set
 * corroborates the same four, at L51, L53, L55 and L57. This projection is the first of those four, so
 * its read partners are {@code CardXrefView}, {@code AccountView} and {@code CustomerView}, and it has
 * no part in the transaction report, whose transaction participant is {@code ReportTransactionView}.
 * </p>
 *
 * <p>Assumptions: no card entity takes part in either read, and this projection therefore never joins
 * one. Neither the statement generator's four copybooks at {@code CBSTM03A.CBL} L51, L53, L55 and L57
 * nor the inputs listed at {@code CREASTMT.JCL} L83 through L86 name a card record or a card dataset.
 * The near miss worth naming is the cross-reference input at L84, which is the card cross-reference
 * and belongs to the account context rather than to the card context, so a reader who cannot find a
 * card read here has not overlooked one.</p>
 *
 * <p>Assumptions: the transfer area of the baseline's generic input-output module is not a competing
 * declaration of this record's key width. {@code app/cbl/CBSTM03B.CBL} L110 declares
 * {@code LK-M03B-KEY PIC X(25)} while the key mapped here is 32 bytes, and L111 declares a separate
 * significant-key-length parameter beside it -- the signature of a generic keyed browse rather than a
 * whole-key read of one declared width. The 25-byte field is that module's own transfer buffer sized
 * for the widest key it handles generically, and the normative width remains the copybook's. Because
 * the read path this projection sits on holds {@code SELECT} and nothing else, no write path of any
 * kind is introduced here.</p>
 *
 * <h2>Decisions</h2>
 *
 * <p>Each entry below is a choice a reasonable alternative could have gone the other way on, and names
 * the line, the declared width or the byte count it rests on. The written convention these blocks
 * conform to is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and owned elsewhere.</p>
 *
 * <p>Trade-offs: this type is mapped immutable, and it carries no optimistic-locking version column,
 * no setter, no cascade and no write path at all. What is given up is real and is accepted
 * deliberately: dirty checking and the convenience of merging a detached instance both go, and a value
 * that needs changing has to be changed through the context that owns the underlying rows. What is
 * bought is <b>where</b> an accidental write stops. A change to a mapped-immutable instance is
 * discarded by the mapping layer, which emits no statement for it at all, so nothing reaches the
 * database; the same change against an ordinary mapping travels to the database and is refused there by
 * the {@code SELECT}-only role, surfacing as an opaque privilege error at a call site far from the
 * assignment that caused it. Two honest limits are stated rather than glossed. The discard is silent
 * rather than reported -- a flush after altering an attribute of a loaded instance emits nothing and
 * raises nothing -- so it is the absence of a setter, and not the annotation, that tells a caller the
 * value cannot be changed, which is exactly why no setter is declared. And the annotation reaches the
 * update path only: an insert is still refused by that same role rather than by this mapping, and the
 * fact that no constructor on this entity accepts data is what keeps a populated instance from being
 * fabricated in the first place. The baseline establishes that nothing is lost by any of this, because
 * all four statement inputs are opened for input only and neither consuming program executes a
 * record-replacing or record-removing verb against them.</p>
 *
 * <p>Alternatives Considered: this type maps a view and this context declares no table, no index, no
 * constraint and no schema-migration artifact of its own -- it is the one entry in the migration plan's
 * schema-ownership table recorded with no owned tables. Two other shapes were evaluated. Declaring a
 * base table here was rejected because the rows are already owned elsewhere, as the four statement
 * inputs at {@code CREASTMT.JCL} L83 through L86 show, so a local copy would create a second truth for
 * figures whose entire purpose is to restate the first one exactly. Reading a replica was rejected on
 * the plan's own ground that a replica adds cost and replica-lag semantics for no parity benefit: a
 * statement disagreeing with the ledger by one replication interval is a support case and not a
 * feature. What remains is read-only access through a cross-schema view.</p>
 *
 * <p>Assumptions: agreement with the contexts that own these rows runs through the physical view and
 * the narrowly-scoped read privilege behind it, never through code, which is why this type declares no
 * relationship annotation to {@code CardXrefView}, {@code AccountView} or {@code CustomerView} and the
 * four-way statement read is composed in the repository layer by a query over the view. Two mechanisms
 * hold that line: the shared kernel's layering rules, which the {@code architecture-rules} Surefire
 * execution in {@code services/pom.xml} selects by the simple name {@code LayeringRulesTest} and
 * evaluates against this module's own compiled classes, forbid one context's domain package importing
 * another's, and being a build rule rather than prose that cannot decay unnoticed; and this module's
 * build declares exactly one dependency inside the reactor, the shared kernel, so no other service
 * module is on its compile classpath to be imported from. That is the discipline the baseline
 * read under too, where the statement generator reached its four inputs through job control alone and
 * never through a compile-time bond. The database half is authored elsewhere and cited by path:
 * {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the schemas, the roles and, at its
 * L944 and the default-privilege statements following it at L963 to L966, the {@code SELECT}-only
 * reach; and {@code data-migration/sql/V1__reporting_views.sql} declares the view itself, ordered
 * after every per-service migration because a view cannot precede the rows it reads. A view absent at
 * run time is a defect to report against those artifacts and never one to work around from inside
 * this type.</p>
 *
 * <p>Assumptions: the view is also where the column contract this type declares is produced, and it
 * performs no conversion at all -- which is stated here because a projection is exactly the place a
 * reader would expect one. The underlying rows are declared in
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}, and
 * {@code reporting.v_statement_transactions} in {@code data-migration/sql/V1__reporting_views.sql}
 * projects every one of them under its own name, width and type. The geometry list above therefore
 * names the base table's types, not converted ones.</p>
 *
 * <p>Alternatives Considered: having the view cast {@code category_cd} to a small whole number and
 * {@code source} and {@code merchant_zip} to variable-length text, on the reading that the normative
 * copybook declares the category numerically at L26 and treats the trailing blanks of the other two
 * as padding. Rejected for two independent reasons. First, the sibling
 * {@code ReportTransactionView} projects the same three base columns and reaches the opposite
 * reading on measured evidence: the source is compared blank-padded to its full declared width, so
 * a ten-character value and the same value trimmed are one value in the baseline and would be two
 * under a variable-length column; the postal code's leading zeros are significant and its padding
 * participates in comparison; and the category is a label whose leading zeros carry rather than a
 * magnitude. Two views over one base column that disagreed on its type would make the same stored
 * value compare differently depending on which report read it. Second, a pass-through projection is
 * auditable against the base table by inspection, whereas a casting one has to be reasoned about
 * column by column -- and the narrowing this view exists for is a privilege boundary, not a type
 * conversion. What is given up is a projection that pre-shapes its columns for a reader; what is
 * kept is one type per stored column across every context that reads it.</p>
 *
 * <p>Assumptions: the trailing {@code FILLER PIC X(20)} declared at L36 of {@code COSTM01.CPY},
 * occupying one-based positions 331 through 350, is dropped and mapped to no column, and the line it
 * came from is named here because the plan's normative-copybook rule requires each dropped field to be
 * recorded per record. {@code FILLER} is padding that carries the record out to its declared length,
 * so it holds nothing a reader of a statement could act on, and mapping it would add a column whose
 * only content is blanks. Naming the line is what keeps its removal auditable instead of
 * invisible.</p>
 *
 * <p>Assumptions: the amount stays at {@code NUMERIC(11,2)} and is never widened to match an account
 * balance. L29 of {@code COSTM01.CPY} declares {@code TRNX-AMT} as {@code PIC S9(09)V99}, eleven
 * significant digits, whereas L7 of {@code app/cpy/CVACT01Y.cpy} declares {@code ACCT-CURR-BAL} as
 * {@code PIC S9(10)V99}, twelve significant digits, which is {@code NUMERIC(12,2)} and is mapped by
 * {@code AccountView}. {@code app/cbl/CBTRN03C.cbl} corroborates independently: its three report
 * accumulators at L134, L135 and L136 are each {@code PIC S9(09)V99}, so the reporting path never
 * needs the wider precision. Unifying the two on the wider form would silently widen a declared
 * contract, and a column that accepts a value the source record cannot hold stops being evidence of
 * what the source record held.</p>
 *
 * <p>Assumptions: a timestamp of twenty-six blanks is a legitimate value in this pipeline rather than
 * an error to reject, so both timestamp attributes are nullable and are documented as such rather than
 * left to inference. L34 and L35 of {@code COSTM01.CPY} each declare {@code PIC X(26)}, and L54 of
 * {@code CREASTMT.JCL} reads {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}. Decoded, that
 * copies 16 plus 262 plus 50 bytes, which is 328 of the record's 350, and the fifty bytes it takes
 * from position 279 span the whole twenty-six characters of the originating timestamp but only
 * twenty-four of the twenty-six of the processing timestamp. The processing timestamp is therefore
 * structurally short of its last two microsecond digits in this pipeline, which is a property of the
 * baseline's own reformatting step and is recorded here as such. A partly blank or wholly blank
 * {@code X(26)} has no calendar value to carry, so the view renders it absent and this type accepts
 * that absence; a projection treating blanks as a violation would reject rows the baseline writes on
 * purpose.</p>
 *
 * <p>Assumptions: neither timestamp attribute uses a zone-bearing or zone-shifted date-time type. The
 * baseline timestamps carry no zone at all, so attaching one would invent information the source
 * record never held and would make two runs in two regions disagree about the same row.</p>
 *
 * <p>Alternatives Considered: the declared identity is the composite {@code TRNX-KEY} of L21 through
 * L23, sixteen characters of card number followed by sixteen of transaction identifier, and not the
 * transaction identifier alone. Keying on the identifier alone was genuinely available, because that
 * identifier is the ledger's own primary key -- {@code app/cpy/CVTRA05Y.cpy} L5 places it at positions
 * 1 through 16 as the indexed key -- and it was rejected because the entire purpose of the
 * STEP010 and STEP020 re-key at {@code CREASTMT.JCL} L44, L53, L54 and L56 is to make the card-leading
 * order the access path. An identity that discarded the leading component would discard exactly the
 * ordering the re-key exists to provide, and a repository keyed that way could no longer express the
 * card-ordered browse the statement read is built on.</p>
 *
 * <p>Alternatives Considered: that composite identity is expressed as an embedded identifier through
 * {@link StatementTransactionKey} rather than as a separate identifier class paired with two
 * individually annotated attributes. The deciding fact is that both components are sixteen-character
 * strings, declared at L22 and L23, so a lookup taking them as two separate arguments accepts them
 * transposed and still compiles, still runs, and silently reads nothing; a named key type cannot be
 * transposed at a call site. The separate-identifier-class shape would additionally declare the same
 * two widths twice, once on the entity and once on the identifier, so a width correction would have to
 * be made in two places or be made in one and diverge. What is given up is that the two components are
 * reached one level in, through the key rather than off the entity, which is the reason no delegating
 * shortcut accessor for either component is declared: a delegating accessor would have to decide what
 * an absent key means, and that decision belongs to the repository that produced the row.</p>
 *
 * <p>Assumptions: every width above is scoped to {@code COSTM01.CPY} and every citation names its
 * owning copybook, because field names in this baseline are not unique. {@code TRAN-TYPE-CD} and
 * {@code TRAN-CAT-CD} are declared in {@code app/cpy/CVTRA05Y.cpy} and again in
 * {@code app/cpy/CVTRA04Y.cpy}; the group {@code TRAN-CAT-KEY} is six bytes at L5 of
 * {@code CVTRA04Y.cpy} and seventeen bytes at L5 of {@code app/cpy/CVTRA01Y.cpy}; and a collision can
 * occur inside one program, since {@code app/cbl/CBSTM03B.CBL} declares {@code FD-ACCT-DATA} as
 * {@code PIC X(318)} at L63 and as {@code PIC X(289)} at L78. A repository-wide field-name map is
 * therefore impossible, as it would have to resolve one name to two widths. The baseline adopts this
 * same discipline: {@code app/cbl/CBTRN03C.cbl} qualifies precisely the two ambiguous names with an
 * explicit record qualifier at L365 and L367 and leaves every unambiguous name bare.</p>
 *
 * <p>Assumptions: the category code at L26 and the merchant identifier at L30 are declared
 * {@code PIC 9(04)} and {@code PIC 9(09)}, both unsigned, so each is carried as a number and neither
 * carries a sign. Their zero padding in the 350-byte record is the fixed-width encoding of that
 * number rather than part of its value, and re-applying that padding when a fixed-width line is
 * written belongs to the mapper layer, which the context's charter names as the one boundary where
 * padding may appear. Carrying either as text here would move a presentation concern into a row shape
 * and would make an ordering comparison lexical where the declaration is numeric.</p>
 *
 * <p>Trade-offs: this type declares no fixed-size collection and no array member of any kind, so
 * nothing here can overrun. The baseline's statement generator holds two independent unchecked tables,
 * an outer card table declared {@code OCCURS 51 TIMES} at L226 of {@code app/cbl/CBSTM03A.CBL} with a
 * nested transaction table declared {@code OCCURS 10 TIMES} at L228, and a separate counter table
 * declared {@code OCCURS 51 TIMES} at L232. Three numbers travel with those tables and none of them is
 * the same number as another: the declared inner arity of 10, the measured same-card overrun threshold
 * of 512, and the declared and measured distinct-card limit of 51. The baseline behaves as its own
 * declarations say; this projection has no fixed arity to overrun, and that divergence is registered as
 * D-2 in {@code docs/architecture/cobol-to-service-traceability.md}, cited by path and owned
 * elsewhere. The compromise accepted is that an unbounded read has no built-in ceiling to protect
 * memory, and the ceiling therefore has to come from the query in the repository layer instead of from
 * the row shape.</p>
 */
@Entity
@Immutable
@Table(name = "v_statement_transactions", schema = "reporting")
public class StatementTransactionView {

    // WHY : Assumptions: the declared lengths and the decimal precision below restate the copybook
    //       widths so that a reader of this mapping never has to open COSTM01.CPY to learn one. They
    //       are metadata and not a schema declaration: this context emits no schema-generation
    //       artifact, so the physical column types are the view's contract, and the table in this
    //       type's own documentation is where each one is stated.
    // WHY : Assumptions: the identity is the copybook's declared key group, TRNX-KEY at L21, so it is
    //       mapped as one embedded value rather than as two loose attributes; the reasoning for that
    //       choice, and for the separate-identifier-class shape it was chosen over, is recorded on
    //       this type.
    @EmbeddedId
    private StatementTransactionKey key;

    // WHY : Assumptions: two characters is the declared width at L25 of COSTM01.CPY, and for a code
    //       the width is part of the contract rather than an upper bound, which is why this and the
    //       key components read a fixed-width column while the descriptive attributes below read a
    //       variable-length one whose trailing blanks are padding.
    @Column(name = "type_cd", length = 2)
    private String typeCode;

    // WHY : (1) Assumptions: L26 of COSTM01.CPY declares PIC 9(04), unsigned, and a width-only
    //       reading maps that to a whole-number type since 9999 fits one. The code is a label
    //       rather than a magnitude, though: no program performs arithmetic on it, and its leading
    //       zeros carry. Two independent sources settle it against the numeric reading. The
    //       physical column is CHAR(4) -- ledger.transactions declares category_cd that way in
    //       transaction-service's V1__ledger.sql, and reporting.v_statement_transactions projects it
    //       unchanged in data-migration/sql/V1__reporting_views.sql -- and the baseline's own
    //       relational expression of the same field declares
    //       TRC_TYPE_CATEGORY CHAR(4) NOT NULL at app/app-transaction-type-db2/ddl/TRNTYCAT.ddl L3.
    //       A whole-number mapping over a four-character column renders 0001 as 1, losing the
    //       zeros between the projection and the statement line.
    //       (2) Assumptions: the declared length of four is stated rather than left off, for the
    //       same reason the two-character type code above states its own: for a code the width is
    //       part of the contract rather than an upper bound.
    //       (3) Alternatives Considered: keeping the whole-number type and re-padding to four
    //       digits at the rendering layer. Rejected because it puts the width contract in a
    //       formatter rather than in the mapping, so a second reader of the same column that
    //       forgot to pad would emit a different value for one row -- and the sibling
    //       ReportTransactionView, which projects the same base column, would then disagree with
    //       this one on the type of a shared field.
    @Column(name = "category_cd", length = 4)
    private String categoryCode;

    @Column(name = "source", length = 10)
    private String source;

    @Column(name = "description", length = 100)
    private String description;

    // WHY : Assumptions: L29 declares PIC S9(09)V99, so eleven significant digits at a scale of two,
    //       and the precision and scale declared here are that declaration and not a rounded-up
    //       convenience. The attribute type is the shared kernel's monetary type, never an
    //       IEEE-754 binary floating point value and never a bare arbitrary-precision decimal, so
    //       every arithmetic and comparison a caller performs on it goes through one contract.
    // WHY : Alternatives Considered: the converter is named explicitly here rather than applied
    //       automatically, so that a sibling projection's own monetary attribute is never bound, by
    //       side effect, to a converter nested inside this type. Two projections that are
    //       deliberately kept separate must not acquire a hidden coupling through a global mapping
    //       default.
    @Convert(converter = MoneyConverter.class)
    @Column(name = "amount", precision = 11, scale = 2)
    private Money amount;

    // WHY : Assumptions: L30 declares PIC 9(09), unsigned, so nine decimal digits with no sign. Nine
    //       digits exceed what a 32-bit whole number holds at its upper end, so the wider whole-number
    //       type is the correct one and is not a precaution.
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    // WHY : Assumptions: the copybook name at L33 abbreviates the postal code and the physical column
    //       keeps that abbreviation, because the column name belongs to the view and matching it is
    //       what lets the mapping be read against the view definition. The attribute name is spelled
    //       out, because nothing outside the mapping depends on the abbreviation.
    @Column(name = "merchant_zip", length = 10)
    private String merchantPostalCode;

    // WHY : Assumptions: nullable is stated rather than inherited on both timestamps, because their
    //       absence is a documented property of this pipeline and not an oversight. The derivation is
    //       on this type: L34 and L35 declare PIC X(26), and the OUTREC statement at L54 of
    //       CREASTMT.JCL writes 328 of the record's 350 bytes and reaches only twenty-four of the
    //       processing timestamp's twenty-six characters. A blank X(26) has no calendar value to
    //       carry, so the view presents it as absent.
    // WHY : Assumptions: the type is a zoneless local date and time. The baseline records carry no
    //       zone at all, so a zone-bearing type would invent information the source never held.
    @Column(name = "orig_ts", nullable = true)
    private LocalDateTime originatingTimestamp;

    @Column(name = "proc_ts", nullable = true)
    private LocalDateTime processingTimestamp;

    // WHY : Refactoring Rationale: this member exists because the card number this projection exposes
    //       is MASKED to its last four digits, and a statement is a per-card document that has to be
    //       grouped by card. Grouping on the masked value would merge two genuinely different cards
    //       that happen to share their last four digits into one statement, and with a twelve-digit
    //       prefix suppressed that collision is a certainty rather than a risk -- the baseline never
    //       had the problem because it broke on the full card number read through its alternate index.
    // WHY : Trade-offs: the alternative was to project the full card number and mask it in the
    //       presentation mapper instead. Rejected because it would give the reporting service role read
    //       access to every primary account number in the ledger and reduce the masking from a boundary
    //       the database enforces to a convention the reporting code is trusted to follow -- which is
    //       exactly the arrangement data-migration/sql/V0__schemas_and_roles.sql withdrew.
    // WHY : Assumptions: the value is a KEYED digest of the trimmed card number -- a SHA-256 over a
    //       secret concatenated with the number, rendered as 64 hexadecimal characters -- computed by
    //       reporting.v_statement_transactions in data-migration/sql/V1__reporting_views.sql and never
    //       by this type. The secret lives in reporting.card_grouping_key, which the reporting login
    //       role cannot select from; the view body reads it because a non-security_invoker view is
    //       evaluated as its owner. It is a GROUPING KEY and nothing else: it is not a credential, no
    //       client ever receives it, and it is deliberately not part of
    //       {@link StatementTransactionKey}, because the transaction identifier already makes that key
    //       unique and widening a key to carry a grouping column would change the identity of every row
    //       for the sake of an ordering concern.
    // WHY : Refactoring Rationale: the column is 64 characters because the digest is keyed and hex
    //       SHA-256; an earlier revision mapped 32, matching an UNKEYED md5 of the card number. The
    //       width changed because the mechanism had to: an unkeyed digest of a sixteen-digit decimal
    //       string is invertible by exhaustive search, so the token stood beside a masked card_num
    //       column and gave back what the mask withheld. Keying it removes the search, and the length
    //       here has to track the view's expression exactly -- a shorter mapping would truncate the
    //       token and silently merge cards whose digests share a prefix.
    @Column(name = "card_fingerprint", length = 64)
    private String cardFingerprint;

    /**
     * Creates an unpopulated instance for the persistence provider to fill by field access.
     *
     * <p>Trade-offs: this is the only constructor, and no constructor accepting data is declared, so
     * the sole way an instance acquires values is the mapping layer populating them from a row of
     * {@code reporting.v_statement_transactions}. Two costs are accepted knowingly. An instance cannot be
     * assembled inside a unit test without the persistence provider or reflection, and the compensating
     * mechanism is the module's container-backed repository test, which reads a real view and is the
     * test shape the migration plan prescribes for a projection. The second cost is convenience: a
     * positional constructor over these attributes was the obvious alternative and was rejected because
     * six of the twelve are of the same character type -- the type code, the source, the description,
     * the merchant name, the merchant city and the merchant postal code -- so an argument list
     * transposed among them would compile, run, and populate the wrong columns. That is the same hazard
     * {@link StatementTransactionKey} exists to contain, and admitting it back through a wide
     * constructor would undo the containment.</p>
     *
     * <p>Assumptions: the provider requires a constructor taking no arguments and requires it to be at
     * least protected, so protected is the narrowest visibility that satisfies the requirement without
     * offering the constructor to callers outside this type's own hierarchy.</p>
     */
    protected StatementTransactionView() {
        // WHY : Assumptions: the body is deliberately empty because every attribute is assigned by the
        //       provider through field access after construction. Assigning a default here would be
        //       overwritten immediately on a read and would invent a value the source row never
        //       carried.
    }

    /**
     * Returns the composite identity of this row, the card number followed by the transaction
     * identifier.
     *
     * @return the embedded key declared at L21 through L23 of {@code app/cpy/COSTM01.CPY}, which is
     *     {@code null} only on an instance the mapping layer has not populated
     */
    public StatementTransactionKey key() {
        return key;
    }

    /**
     * Returns the two-character transaction type code.
     *
     * @return the text held in column {@code type_cd}, blank-padded to its declared width of two, or
     *     {@code null} where the view presents no value
     */
    public String typeCode() {
        return typeCode;
    }

    /**
     * Returns the transaction category code at its declared four-character width.
     *
     * @return the value of column {@code category_cd}, four characters wide as the declaration at
     *     L26 fixes it and with any leading zeros intact, or {@code null} where the view presents
     *     no value
     */
    public String categoryCode() {
        return categoryCode;
    }

    /**
     * Returns the code naming where the transaction originated.
     *
     * @return the text held in column {@code source}, at most the declared width of ten, or {@code null}
     *     where the view presents no value
     */
    public String source() {
        return source;
    }

    /**
     * Returns the free-text transaction description carried onto the statement.
     *
     * @return the text held in column {@code description}, at most the declared width of one
     *     hundred, or {@code null} where the view presents no value
     */
    public String description() {
        return description;
    }

    /**
     * Returns the signed transaction amount as an exact monetary value.
     *
     * @return the monetary value held in column {@code amount} at a scale of exactly two, or
     *     {@code null} where the view presents no value
     */
    public Money amount() {
        return amount;
    }

    /**
     * Returns the merchant identifier as a whole number.
     *
     * @return the whole-number value of column {@code merchant_id}, in the range the nine-digit
     *     unsigned declaration at L30 admits, or {@code null} where the view presents no value
     */
    public Long merchantId() {
        return merchantId;
    }

    /**
     * Returns the merchant name.
     *
     * @return the text held in column {@code merchant_name}, at most the declared width of fifty, or
     *     {@code null} where the view presents no value
     */
    public String merchantName() {
        return merchantName;
    }

    /**
     * Returns the merchant city.
     *
     * @return the text held in column {@code merchant_city}, at most the declared width of fifty, or
     *     {@code null} where the view presents no value
     */
    public String merchantCity() {
        return merchantCity;
    }

    /**
     * Returns the merchant postal code.
     *
     * @return the text held in column {@code merchant_zip}, at most the declared width of ten, or
     *     {@code null} where the view presents no value
     */
    public String merchantPostalCode() {
        return merchantPostalCode;
    }

    /**
     * Returns the instant at which the transaction originated, with no zone attached.
     *
     * @return the zoneless date and time held in column {@code orig_ts} to microsecond precision, or
     *     {@code null}, which is a
     *     legitimate value here because a blank twenty-six-character field carries no calendar value
     */
    public LocalDateTime originatingTimestamp() {
        return originatingTimestamp;
    }

    /**
     * Returns the instant at which the transaction was posted, with no zone attached.
     *
     * @return the zoneless date and time held in column {@code proc_ts} to microsecond precision, or
     *     {@code null}, which is a
     *     legitimate value here because the reformatting step at L54 of {@code app/jcl/CREASTMT.JCL}
     *     reaches only twenty-four of this field's twenty-six characters
     */
    public LocalDateTime processingTimestamp() {
        return processingTimestamp;
    }

    /**
     * Returns the collision-free token that groups this row's card together with its siblings.
     *
     * <p>Assumptions: this is what a statement run breaks on, and not {@link #key()}'s card number.
     * That card number is masked to its last four digits, so breaking on it would merge two genuinely
     * different cards that share those four digits into a single statement; this token is a
     * deterministic KEYED digest of the full card number, so it distinguishes them while disclosing
     * neither. The digest is computed by {@code reporting.v_statement_transactions} and never by this
     * type, and it mixes in a secret the reporting role cannot read -- which is what makes it
     * non-invertible, since an unkeyed digest of a sixteen-digit number is recoverable by exhaustive
     * search.</p>
     *
     * <p>Trade-offs: the token has no meaning outside a grouping comparison. It is not an identifier
     * any client receives, it is not ordered in any way that corresponds to card ordering, and it must
     * not be rendered -- the card number a statement displays is the masked value on the key.</p>
     *
     * @return the sixty-four-character hexadecimal digest held in column {@code card_fingerprint},
     *     never {@code null} for a row read from the view because the source card number is itself
     *     not-null
     */
    public String cardFingerprint() {
        return cardFingerprint;
    }

    /**
     * Compares this row with another for equality of declared identity.
     *
     * <p>Alternatives Considered: equality is decided by the embedded key alone and not by comparing
     * every attribute. Comparing all twelve was evaluated and rejected: two reads of the same row
     * through the same view are the same row, and an attribute-wise comparison would call them
     * different the moment the view's own definition changed a value, which turns a mapping question
     * into a data question. The declared key at L21 through L23 of {@code app/cpy/COSTM01.CPY} is what
     * the baseline itself treats as identity, since it is the key the sorted cluster is loaded under at
     * L56 of {@code app/jcl/CREASTMT.JCL}.</p>
     *
     * <p>Trade-offs: an instance whose key has not been populated is equal only to itself. The
     * alternative -- treating two unpopulated instances as equal because both keys are absent -- was
     * rejected because it would collapse every such instance into one element of a set, and the
     * unpopulated state exists solely between construction and population by the mapping layer, so
     * declaring two of them equal describes nothing a caller could act on.</p>
     *
     * @param other the object to compare against; may be {@code null} and may be of any type, both of
     *     which yield {@code false}
     * @return {@code true} when the argument is the same instance, or is another instance of this type
     *     whose populated key is equal to this one's; {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof StatementTransactionView that)) {
            return false;
        }

        // WHY : Assumptions: the null test is what makes an unpopulated instance equal only to itself.
        //       Delegating straight to a null-tolerant comparison would report two unpopulated
        //       instances as equal, for the reason set out on this method.
        return key != null && key.equals(that.key);
    }

    /**
     * Returns a hash code consistent with equality of declared identity.
     *
     * <p>Assumptions: the value is derived from the embedded key alone, so that it agrees with the
     * equality decision above. A null-tolerant derivation is used deliberately: an unpopulated instance
     * hashes to a single bucket, which is permitted because unequal objects are allowed to share a hash
     * code, whereas equal objects sharing one is mandatory and is what this preserves.</p>
     *
     * @return the hash code of the embedded key, or zero when the key has not been populated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(key);
    }

    /**
     * Renders a diagnostic form of this row that never discloses a full primary account number.
     *
     * <p>Trade-offs: the card number reaches this rendering only through
     * {@link StatementTransactionKey#toString()}, which masks it to its last four characters, and the
     * description, the merchant name and the merchant city are left out altogether. Two things are
     * given up. Diagnostic completeness goes, so a reader of a log line cannot reconstruct the row from
     * it and has to query the view instead. Brevity is also a factor: the description alone is
     * declared one hundred characters wide at L28 of {@code app/cpy/COSTM01.CPY} and the two merchant
     * free-text attributes fifty each at L31 and L32, so including them would add up to two hundred
     * characters that identify nothing. What is bought is that no default rendering of this type can
     * put a sixteen-digit account number into a log, which the migration plan requires to be masked
     * everywhere outside the administrative card detail read, and that read belongs to another
     * context.</p>
     *
     * <p>Refactoring Rationale: THE AMOUNT AND THE MERCHANT IDENTIFIER ARE ALSO OMITTED, and an earlier
     * revision rendered both. That revision reasoned about ONE prohibition -- the primary account
     * number -- concluded correctly that the masked key satisfied it, and then treated the remaining
     * attributes as unremarkable, leaving out only the three free-text attributes and leaving those two
     * in. The sensitive-data logging contract in {@code docs/architecture/observability.md} covers
     * persistence-bound values as a class, and both of these are exactly that: the amount is the
     * monetary content of a statement line, and the merchant identifier names the counterparty of a
     * real cardholder purchase. The earlier reasoning also cited BREVITY as part of its case for
     * omitting the free-text attributes -- two hundred characters "that identify nothing" -- which is
     * the wrong axis. Brevity and disclosure are separate concerns, and reaching the right answer on
     * length is what stopped it examining these two on content.</p>
     *
     * <p>Assumptions: the exposure closed here is the RETAINED LOG, and a statement run is the worst
     * shape of it in this context. One rendered line per statement row means the aggregate of a single
     * run is a log holding every amount and every counterparty of every statement produced -- the
     * statement file's whole substance in a second place no migration control governs, and readable by
     * every holder of log access rather than only by the one cardholder each statement is addressed
     * to.</p>
     *
     * <p>Trade-offs: what survives is the masked key, the two reference codes, the source and the
     * processing timestamp -- enough to say WHICH ROW and WHAT KIND, and nothing about what it was worth
     * or with whom. The source and the timestamp are kept deliberately: a ten-character source code is
     * a closed reference domain, and the sibling {@code ReportTransactionView} renders its processing
     * timestamp for the same reason, so keeping both here leaves the two projections of the same base
     * table consistent. The omission is total rather than partial, because abbreviating or rounding a
     * monetary value IS masking and masking has one owner per context. The cost is that a reader
     * reconciling a statement discrepancy from logs alone must query the view, which the accessors above
     * serve.</p>
     *
     * @return a single-line rendering carrying the masked key, the type code, the category code, the
     *     source and the processing timestamp, and neither the amount nor the merchant identifier
     */
    @Override
    public String toString() {
        return "StatementTransactionView[key=" + key
                + ", typeCode=" + typeCode
                + ", categoryCode=" + categoryCode
                + ", source=" + source
                + ", processingTimestamp=" + processingTimestamp
                + "]";
    }

    /**
     * The composite identity of a statement transaction row, card number first and identifier second.
     *
     * <p>This is the copybook's own key group, {@code 05 TRNX-KEY} at L21 of
     * {@code app/cpy/COSTM01.CPY}, whose two members are {@code TRNX-CARD-NUM PIC X(16)} at L22 and
     * {@code TRNX-ID PIC X(16)} at L23, giving a declared key width of 32 bytes. The card-leading order
     * is the whole point of the record: the sort control statement at L53 of
     * {@code app/jcl/CREASTMT.JCL} orders on the card number ahead of the identifier, and L56 loads
     * that order into an indexed cluster so the statement read can browse it.</p>
     *
     * <p>Alternatives Considered: this type exists so that a lookup names its components once, in one
     * constructor whose parameters are documented, instead of accepting them positionally at every
     * finder in the repository layer. Both components are sixteen-character strings, so a transposed
     * pair compiles and runs and matches nothing; concentrating that hazard in a single constructor is
     * strictly better than spreading it across every signature that takes the pair. The residual risk
     * is acknowledged rather than claimed away: this constructor itself can still be called with the
     * two arguments the wrong way round, which is why the parameter documentation below names the
     * copybook line each one comes from.</p>
     *
     * <p>Assumptions: both components read fixed-width character columns, so a value read back is
     * blank-padded out to its declared width of sixteen. No length is enforced here. A caller holding
     * an unpadded card number is not holding a defect, because a fixed-width character comparison
     * disregards trailing blanks, and re-applying padding when a fixed-width line is written belongs to
     * the mapper layer, which the context's charter names as the one boundary where padding may
     * appear.</p>
     */
    @Embeddable
    public static class StatementTransactionKey implements Serializable {

        // WHY : Assumptions: the persistence specification requires an embedded identifier to be
        //       serializable, so the interface is implemented rather than optional. The version is
        //       declared explicitly instead of being derived by the compiler, because a
        //       compiler-derived value changes whenever a member is added and would silently
        //       invalidate an already-serialized form.
        private static final long serialVersionUID = 1L;

        // WHY : Trade-offs: four is the number of trailing characters the masked rendering keeps, and
        //       it is named here rather than written inline so the rendering and its documentation
        //       cannot disagree. Four is what the migration plan's masking requirement states; showing
        //       more would weaken the masking and showing fewer would leave the rendering unable to
        //       tell two rows apart at all.
        private static final int MASK_TRAILING_CHARACTERS = 4;

        // WHY : Assumptions: sixteen characters is the declared width at L22, and the column is the
        //       leading component of the key precisely because the re-key at L53 and L54 of
        //       CREASTMT.JCL hoisted the card number to position 1.
        @Column(name = "card_num", length = 16, nullable = false)
        private String cardNumber;

        // WHY : Assumptions: sixteen characters is the declared width at L23. In the posting output
        //       record this same identifier is the whole key; here it is the trailing component, which
        //       is the one difference the two records' documentation turns on.
        @Column(name = "transaction_id", length = 16, nullable = false)
        private String transactionId;

        /**
         * Creates an unpopulated key for the persistence provider to fill by field access.
         *
         * <p>Assumptions: the provider requires a constructor taking no arguments on an embeddable
         * type, and protected is the narrowest visibility that satisfies that requirement, so callers
         * outside this type's own hierarchy still have to state both components through the public
         * constructor below.</p>
         */
        protected StatementTransactionKey() {
            // WHY : Assumptions: the body is empty because both components are assigned by the provider
            //       through field access after construction, and a default assigned here would be
            //       overwritten on every read.
        }

        /**
         * Creates a key from its two declared components.
         *
         * @param cardNumber the card number as text, {@code TRNX-CARD-NUM} at L22 of
         *     {@code app/cpy/COSTM01.CPY}, sixteen characters wide and the LEADING component; must not
         *     be {@code null}
         * @param transactionId the transaction identifier as text, {@code TRNX-ID} at L23 of the
         *     same copybook, sixteen characters wide and the TRAILING component; must not be
         *     {@code null}
         * @throws NullPointerException if either component is {@code null}, which is raised at the point
         *     the key is built rather than absorbed, because a key missing a component addresses no row
         *     and a lookup carrying one would return nothing for a reason the caller could not see
         */
        public StatementTransactionKey(String cardNumber, String transactionId) {
            this.cardNumber = Objects.requireNonNull(cardNumber, "cardNumber must not be null");
            this.transactionId = Objects.requireNonNull(transactionId, "transactionId must not be null");
        }

        /**
         * Returns the card number, the leading component of this key.
         *
         * @return the card number as text, the value of column {@code card_num}, blank-padded to its
         *     declared width of sixteen
         */
        public String cardNumber() {
            return cardNumber;
        }

        /**
         * Returns the transaction identifier, the trailing component of this key.
         *
         * @return the transaction identifier as text, the value of column {@code transaction_id},
         *     blank-padded to its declared width of sixteen
         */
        public String transactionId() {
            return transactionId;
        }

        /**
         * Compares this key with another for equality of both declared components.
         *
         * <p>Assumptions: the persistence specification requires an embedded identifier to implement
         * value equality, because the provider uses it to decide whether two instances denote the same
         * row. Both components take part, since either one alone identifies many rows: a card number
         * alone selects every transaction on that card, which is exactly the browse the card-leading
         * order at L53 of {@code app/jcl/CREASTMT.JCL} was built to serve.</p>
         *
         * @param other the object to compare against; may be {@code null} and may be of any type, both
         *     of which yield {@code false}
         * @return {@code true} when the argument is another key of this type whose card number and
         *     transaction identifier both equal this one's; {@code false} otherwise
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }

            if (!(other instanceof StatementTransactionKey that)) {
                return false;
            }

            // WHY : Assumptions: a null-tolerant comparison is used on the components because the
            //       provider populates them after construction, so a key observed mid-population must
            //       compare without raising rather than fail the read that is populating it.
            return Objects.equals(cardNumber, that.cardNumber)
                    && Objects.equals(transactionId, that.transactionId);
        }

        /**
         * Returns a hash code derived from both declared components.
         *
         * <p>Assumptions: both components take part, matching the equality decision above, so that two
         * keys the provider treats as the same row land in the same bucket.</p>
         *
         * @return a hash code combining the card number and the transaction identifier
         */
        @Override
        public int hashCode() {
            return Objects.hash(cardNumber, transactionId);
        }

        /**
         * Renders this key with the card number masked to its last four characters.
         *
         * <p>Trade-offs: masking here rather than at each call site is what makes the protection
         * unconditional, since a rendering reached through string concatenation, a log template or a
         * debugger cannot be asked to remember to mask. The cost is that this rendering cannot be used
         * to identify a specific card, and a reader needing that has to query the view under the
         * authority that permits it. The migration plan requires an account number to be masked to its
         * last four characters everywhere outside the administrative card detail read, and that read
         * belongs to another context, so no path through this type may disclose one.</p>
         *
         * @return a rendering of the form {@code StatementTransactionKey[cardNumber=************3456,
         *     transactionId=...]}, in which the card number is masked and the transaction identifier is
         *     not, the identifier carrying no account holder information of its own
         */
        @Override
        public String toString() {
            return "StatementTransactionKey[cardNumber=" + maskTrailing(cardNumber)
                    + ", transactionId=" + transactionId
                    + "]";
        }

        /**
         * Masks every character of a value except its last four.
         *
         * @param value the text to mask; may be {@code null}, and may be shorter than the number of
         *     trailing characters normally kept, both of which are handled rather than rejected because
         *     this runs inside a diagnostic rendering that must not raise
         * @return the literal text {@code null} when the argument is {@code null}; a run of asterisks of
         *     the argument's own length when the argument is no longer than the number of trailing
         *     characters kept, so that a short value discloses nothing at all; otherwise the argument
         *     with every character but its last four replaced by an asterisk
         */
        private static String maskTrailing(String value) {
            if (value == null) {
                return "null";
            }

            // WHY : Assumptions: trailing blanks are stripped before measuring, because the column is
            //       fixed-width and a value read back is padded out to sixteen characters. Measuring the
            //       padded form would count blanks as digits and produce a mask whose asterisk run
            //       misstated the length of the value it hid.
            String significant = value.strip();

            if (significant.length() <= MASK_TRAILING_CHARACTERS) {
                // WHY : Trade-offs: a value no longer than the number of characters normally kept is
                //       masked in full rather than shown, accepting that the rendering then identifies
                //       nothing. Showing it would disclose the entire value in exactly the case where
                //       the mask is supposed to be doing the most work.
                return "*".repeat(significant.length());
            }

            int maskedLength = significant.length() - MASK_TRAILING_CHARACTERS;
            return "*".repeat(maskedLength) + significant.substring(maskedLength);
        }
    }

    /**
     * Converts between the shared kernel's monetary type and the decimal column the view presents.
     *
     * <p>Alternatives Considered: a converter is used because the monetary type cannot be mapped any
     * other way. It is final and its only constructor is private, so it can be neither an embeddable
     * value nor a directly persisted attribute type, and those were the two alternatives. Holding the
     * attribute as a bare arbitrary-precision decimal instead was also available and was rejected: the
     * whole reason a monetary type exists is that scale, rounding and the prohibition on an IEEE-754
     * binary floating point representation are stated once and enforced by that type, and an attribute
     * typed as a bare decimal would let each caller decide those again. The decimal appears in this
     * converter and nowhere else in this file, which is precisely the boundary a converter is for.</p>
     *
     * <p>Assumptions: automatic application is deliberately left off, so this converter binds only where
     * it is named. A converter nested inside one projection that applied itself to every monetary
     * attribute in the persistence unit would couple projections that are deliberately kept
     * independent.</p>
     */
    @Converter(autoApply = false)
    public static class MoneyConverter implements AttributeConverter<Money, BigDecimal> {

        /**
         * Creates a converter instance for the persistence provider to use.
         *
         * <p>Assumptions: the provider instantiates a converter reflectively and requires a public
         * constructor taking no arguments, so this constructor is declared explicitly rather than left
         * implicit, in order that the requirement is visible to a reader of this file.</p>
         */
        public MoneyConverter() {
            // WHY : Assumptions: the body is empty because a converter holds no state. Holding state
            //       here would be unsafe, since one instance is shared across every thread reading
            //       through this mapping.
        }

        /**
         * Converts a monetary value to the decimal form the column carries.
         *
         * <p>Assumptions: this direction is reachable on a read despite this projection having no write
         * path, because the provider also calls it when a monetary value is bound as a query parameter.
         * That is why it is implemented faithfully rather than left to raise: a comparison against an
         * amount is a legitimate read, and a converter that refused this direction would turn it into a
         * failure.</p>
         *
         * @param attribute the monetary value to convert; may be {@code null}, which the view permits
         *     wherever it presents no amount
         * @return the amount as a decimal at a scale of exactly two, or {@code null} when the argument
         *     is {@code null}
         */
        @Override
        public BigDecimal convertToDatabaseColumn(Money attribute) {
            // WHY : Assumptions: null is passed through rather than substituted with zero. Zero is a
            //       real amount in this domain and an absent amount is not, so substituting one for the
            //       other would make a row with no amount indistinguishable from a row whose amount is
            //       nothing.
            return attribute == null ? null : attribute.amount();
        }

        /**
         * Converts the column's decimal form to a monetary value.
         *
         * @param dbData the decimal value read from column {@code amount}; may be {@code null}, which
         *     the view permits wherever it presents no amount
         * @return the amount as a monetary value at a scale of exactly two, or {@code null} when the
         *     argument is {@code null}
         * @throws ArithmeticException if the value read exceeds the magnitude the monetary type admits,
         *     which is raised at the boundary that read it rather than silently reduced, because a value
         *     that wide cannot have come from a column declared {@code NUMERIC(11,2)} and points at a
         *     view definition to correct rather than at an amount to round
         */
        @Override
        public Money convertToEntityAttribute(BigDecimal dbData) {
            // WHY : Assumptions: the column is declared NUMERIC(11,2), so the driver returns a value
            //       already at a scale of two and the factory's rounding is not reached. The factory is
            //       still used rather than bypassed, because it is the single place the scale and
            //       magnitude contract is enforced, and a view presenting an unexpected scale should be
            //       reported by that contract rather than pass through unchecked.
            return dbData == null ? null : Money.of(dbData);
        }
    }
}
