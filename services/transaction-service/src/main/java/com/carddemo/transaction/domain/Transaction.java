package com.carddemo.transaction.domain;

import com.carddemo.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A posted transaction, mapping one row of {@code ledger.transactions} to one migrated
 * {@code TRAN-RECORD}.
 *
 * <p>This is the persistence form of the posted transaction master: the record every writer of the
 * ledger context appends to and the record the list, detail and report paths read. It carries the
 * thirteen data members its record contract declares, at the widths and scales that contract
 * declares them, and it holds no business logic of its own.
 *
 * <h2>Provenance of the record contract</h2>
 *
 * <p>Assumptions: the contract is {@code app/cpy/CVTRA05Y.cpy} lines 4 to 18. Line 4 declares
 * {@code 01 TRAN-RECORD} and line 2 states the record length as 350. Fourteen {@code 05} items
 * follow: thirteen carry data and total 330 bytes, and the fourteenth, {@code FILLER PIC X(20)} at
 * line 18, pads bytes 331 to 350 out to the declared length. Every byte range named in this file is
 * one-based and inclusive. The reference tree is read as specification and is never modified, so
 * these members encode widths and scales it already states rather than redefining them.
 *
 * <p>Assumptions: two of those byte offsets carry access paths, and each is established three
 * independent ways rather than derived once. Summing the declared widths puts
 * {@code TRAN-CARD-NUM} at 263 to 278 and {@code TRAN-PROC-TS} at 305 to 330.
 * {@code app/jcl/TRANREPT.jcl} line 41 independently places the first at one-based 263 for 16 bytes
 * and its line 42 places the second at one-based 305. {@code app/jcl/TRANIDX.jcl} line 27 declares
 * {@code KEYS(26 304)}, a 26-byte key at zero-based offset 304, which is that same one-based 305.
 * The three agree exactly. The 350-byte length agrees three ways as well: the width summation,
 * {@code RECORDSIZE(350,350)} at line 30 of that index definition, and
 * {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} at line 31 of the report job.
 *
 * <p>Assumptions: line 41 of that report job types the card number {@code ZD} for the sort
 * utility's own purposes although the copybook declares it {@code PIC X(16)}. The copybook is the
 * normative declaration under the migration's copybook-is-normative transformation rule, so the
 * alphanumeric picture governs here and the sort utility's declared type does not.
 *
 * <h2>The migration owns the physical shape, and this type answers to it</h2>
 *
 * <p>Assumptions: the authoritative column list is
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}, which
 * declares this table at its lines 116 to 256. The sibling {@code application.yml} sets
 * {@code ddl-auto: none} at its line 469, so the persistence provider generates no schema at all
 * and every column name, length, precision, scale and nullability written below has to match that
 * migration exactly. Nothing reconciles the two at start-up once generation is switched off, so a
 * mismatch stays invisible until a query runs.
 *
 * <p>Assumptions: the Java member names follow the record contract and the column names follow the
 * migration, so the two differ on six of the thirteen members and that split is deliberate. The
 * parent package charter rules that no field beneath it is renamed, and {@code tranId} matches
 * {@code TRAN-ID} while {@code transactionId} would not, so the member keeps the copybook's
 * spelling and the migration's own column name is attached explicitly through
 * {@code @Column(name = ...)}. Reading either name off the other is therefore unsafe, which is why
 * every single mapping below names its column rather than relying on a naming strategy to derive
 * one.
 *
 * <p>Assumptions: the two secondary indexes over this table belong to that migration and are named
 * here so a reader can find them. {@code idx_transactions_card_num}, created at its line 269,
 * carries the card-ordered path the baseline obtained by physically sorting an extract at
 * {@code app/jcl/TRANREPT.jcl} line 46, {@code SORT FIELDS=(TRAN-CARD-NUM,A)}; it is deliberately
 * not unique, because one card has many transactions. {@code idx_transactions_proc_ts}, created at
 * its line 291, is the surviving key of the batch alternate index defined at
 * {@code app/jcl/TRANIDX.jcl} lines 25 to 30, and it is non-unique because line 28 of that
 * definition declares {@code NONUNIQUEKEY} -- many transactions share one processing timestamp, so
 * a unique index would refuse the second row of any posting run. The {@code BLDINDEX} step at line
 * 52 of that job is retired as a migration target only, because the engine maintains an index
 * transactionally as rows change; it still stands unmodified on disk.
 *
 * <p>Alternatives Considered: declaring those two indexes on this type with {@code @Index} inside
 * {@code @Table}. Rejected because {@code ddl-auto: none} means such metadata is never acted on:
 * it would neither create nor verify an index, so it could drift out of step with the migration
 * that does create them while still reading like a specification. Naming them in prose keeps one
 * author for the physical shape and leaves this type describing only what it maps.
 *
 * <h2>No version attribute, and the absence is recorded rather than merely left</h2>
 *
 * <p>Alternatives Considered: annotating this type with {@code @Version} for optimistic
 * concurrency. That is the plausible alternative, because the wider migration does adopt exactly
 * that pattern for the account and card records, and it is rejected on evidence measured on the
 * reference branch. {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN01C.cbl} and
 * {@code app/cbl/COTRN02C.cbl} contain no rewrite statement of any kind.
 * {@code app/cbl/COBIL00C.cbl} contains exactly one, at line 379, and it targets the ACCOUNT
 * record rather than a ledger row: line 234 of that program computes the account balance less the
 * transaction amount and line 379 writes that account record back.
 * {@code app/cbl/CBTRN02C.cbl} rewrites at lines 528 and 554, the category balance and the
 * account, and appends the posted transaction with a plain {@code WRITE} at line 564. This table
 * is therefore insert-only along every reference path, and there is no before-image snapshot and no
 * data-changed flag over this record anywhere to migrate.
 * A second check is mechanical: because schema generation is switched off, a version attribute
 * would map to a column the migration does not create and would fail when a query ran rather than
 * degrade to unversioned behaviour. The absence is stated because an entity with no version
 * attribute looks identical whether the omission was reasoned or overlooked.
 *
 * <h2>Who writes this table</h2>
 *
 * <p>Assumptions: four reference programs append to this record, and the field values two of them
 * hard-code are part of the behaviour this type has to be able to hold.
 *
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} posts the daily feed. Its {@code 2000-POST-TRANSACTION} at
 *       line 424 copies the feed's members across and its {@code 2900-WRITE-TRANSACTION-FILE} at
 *       line 562 appends the row with the {@code WRITE} at line 564, reached from line 442.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} adds a transaction from the online screen.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl} pays a bill, and lines 220 to 224 fix four of its members
 *       outright: type {@code '02'}, category {@code 2}, source {@code 'POS TERM'}, and the
 *       account's whole current balance as the amount. Its line 226 writes the all-nines merchant
 *       sentinel {@code 999999999}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} accrues interest. Its {@code 1300-B-WRITE-TX} at line 473
 *       fixes type {@code '01'}, category {@code '05'} and source {@code 'System'} at lines 482 to
 *       484 and appends the row at line 500.</li>
 * </ul>
 *
 * <p>Alternatives Considered: the batch context migrates the posting program and therefore writes
 * this same table, and the two modules could have been coupled by sharing this type. Instead the
 * batch context declares its own {@code com.carddemo.batch.domain} and reaches these rows under a
 * narrowly scoped cross-schema grant, so the two modules agree through the physical schema and
 * never through code. The grant exists because the posting unit of work at
 * {@code app/cbl/CBTRN02C.cbl} lines 424 to 444 performs three writes in sequence -- the category
 * balance at line 440, the account at line 441 and the posted transaction at line 442 -- and has to
 * stay one atomic commit. A saga was the alternative and is rejected because it would replace that
 * single commit with committed steps plus compensating reversals, making partial-posting states
 * observable that the baseline does not have; a posted transaction with an unposted balance is
 * exactly what a parity comparison would flag, and correctly.
 *
 * <p>Assumptions: one nearby record layout deliberately gets no entity of its own.
 * {@code app/cpy/COSTM01.CPY} declares {@code TRNX-RECORD} keyed by card number followed by
 * transaction identifier, which is a card-ordered view of the very rows this type maps. That access
 * path is served by {@code idx_transactions_card_num} over this one table, and the statement
 * projection built on it belongs to the reporting context. That file name is upper case on disk,
 * extension included, so a lower-case path does not resolve.
 *
 * <h2>The padding member is dropped</h2>
 *
 * <p>Trade-offs: the {@code FILLER PIC X(20)} at line 18 of the record contract is dropped rather
 * than carried as a fourteenth member, so the 350-byte record length is not reconstructible from
 * this type alone and a reader reconciling 350 bytes against thirteen members has to account for
 * the difference from the copybook. Reconstructing a fixed-length image is the work of
 * {@code com.carddemo.common.codec}, which owns record representation for the whole migration.
 * The supporting evidence that those bytes are padding rather than data is that their content is
 * not even consistent between two extracts: in {@code app/data/ASCII/dailytran.txt} the trailing
 * twenty bytes of every one of the 300 records are spaces, while in
 * {@code app/data/ASCII/tcatbal.txt} the trailing twenty-two bytes are ASCII zero digits. A column
 * holding either would store a writer's padding convention and nothing about the transaction.
 *
 * <h2>Fixed-width columns are bound as CHAR explicitly</h2>
 *
 * <p>Assumptions: every member below whose column {@code db/migration/V1__ledger.sql} declares CHAR(n) carries
 * {@code @JdbcTypeCode(SqlTypes.CHAR)} beside its {@code @Column}. There are six such members here --
 * the transaction identifier, the two-character type code, the four-character
 * category code, the source, the merchant postal code and the card number -- and the annotation is not decoration. A Java String otherwise selects the JDBC
 * VARCHAR binding, so the driver sends a varying-length parameter for a column the database
 * has blank padded to its declared width; the two are then compared under padding rules the
 * reference programs never relied on, and a lookup by a value shorter than the declared width
 * can miss a row that is present. This is the same annotation the batch and authorization
 * contexts already carry on their own fixed-width columns, so one mechanism spans the
 * migration rather than one per context.
 *
 * <p>Alternatives Considered: {@code columnDefinition = "CHAR(n)"} on each member, which
 * would also fix the binding. Rejected because it embeds vendor DDL in a mapping that has no
 * authority to create this table -- {@code db/migration/V1__ledger.sql} does -- so the physical width would then be
 * stated in two places able to disagree. The declared length together with the standard CHAR
 * type code says the same thing without a second definition.
 *
 * <p>Trade-offs: a binding is only verifiable where something verifies it, and
 * {@code ddl-auto: none} on the deployed profiles deliberately verifies nothing because the
 * migration owns the schema. {@code src/test/resources/application-test.yml} therefore sets
 * {@code ddl-auto: validate}, so a repository test running against a migrated database fails
 * on a type or width disagreement instead of a deployed environment discovering it.
 *
 * <h2>No member is renamed</h2>
 *
 * <p>Assumptions: every member below spells its record contract exactly, and a reader arriving from
 * another context expecting a spelling change will find none here. The migration does spell three
 * baseline names differently in its own columns -- the account expiration date, the card expiration
 * date and the merchant category code -- and all three belong to the account, card and
 * authorization contexts respectively. None of them appears in this record contract, so none
 * applies. Those three names still stand exactly as written in their copybooks; the migration
 * chooses a different column name in its own schema and registers the divergence in its
 * traceability matrix.
 */
@Entity
// WHY : Alternatives Considered: the schema is named explicitly rather than left to the
//       search_path that application.yml line 317 pins per pooled connection with
//       `connection-init-sql: SET search_path TO ledger`. Relying on that would make this mapping
//       unreadable without opening the YAML, and it would resolve differently for a connection
//       that cannot pin a single schema -- the batch context's login needs `ledger` and `account`
//       together, and the reporting context reads these rows through cross-schema views. Naming
//       the schema here means this type resolves to one table whichever connection loads it.
@Table(name = "transactions", schema = "ledger")
public class Transaction {

    /**
     * The transaction identifier, which is this row's whole primary key.
     *
     * <p>Assumptions: {@code TRAN-ID PIC X(16)} at line 5 of the record contract, bytes 1 to 16.
     * The migration declares it {@code transaction_id CHAR(16) NOT NULL} and makes it the single
     * column of {@code pk_transactions}, which is the key the baseline itself declares:
     * {@code app/cbl/CBTRN02C.cbl} lines 34 to 37 select this file as {@code ORGANIZATION IS
     * INDEXED} with {@code RECORD KEY IS FD-TRANS-ID}, and {@code app/cbl/COTRN00C.cbl} pages the
     * list screen on that same key.
     */
    // WHY : Alternatives Considered: an integer column and a Long member, which is the obvious
    //       reading because sixteen decimal digits fit a signed sixty-four-bit integer. Rejected on
    //       three independent grounds, any one of which settles it. First, line 5 of the record
    //       contract declares an alphanumeric picture, and the copybook is normative, so the fixed
    //       width is the contract and a leading zero is significant -- the first identifier in
    //       app/data/ASCII/dailytran.txt is 0000000000683580, which an integer column would store
    //       as 683580. Second, app/cbl/COTRN02C.cbl accepts the value through a character screen
    //       field. Third, and decisively, two incompatible generation schemes share this one
    //       column: app/cbl/COBIL00C.cbl lines 212 to 219 move HIGH-VALUES into the key, browse
    //       backwards to the last row, move the result into WS-TRAN-ID-NUM declared PIC 9(16) at
    //       its line 57, add one and move it back -- a zero-padded maximum-plus-one sequence, with
    //       MOVE ZEROS TO TRAN-ID at its line 488 as the empty-file fallback -- whereas
    //       app/cbl/CBACT04C.cbl lines 476 to 480 STRING a PIC X(10) business date together with a
    //       PIC 9(06) suffix into the same field, which is sixteen characters but a date-prefixed
    //       composite rather than a sequence. An integer column would have to claim both are one
    //       number. The value is therefore treated as an opaque sixteen-character token.
    // WHY : Assumptions: keyset paging over this column depends on one property of that opacity
    //       holding -- both schemes above are zero-padded to the full width, so lexicographic order
    //       equals numeric order. That is what makes the baseline's own backward and forward browse
    //       on this key, and therefore a keyset query over it, well defined.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", length = 16, nullable = false, updatable = false)
    private String tranId;

    /**
     * The two-character transaction type code.
     *
     * <p>Assumptions: {@code TRAN-TYPE-CD PIC X(02)} at line 6 of the record contract, bytes 17 to
     * 18, mapped to {@code type_cd CHAR(2)}. The baseline agrees where it expressed the same field
     * relationally: {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} line 2 declares
     * {@code TRC_TYPE_CODE CHAR(2) NOT NULL}. Two writers fix the value outright,
     * {@code app/cbl/COBIL00C.cbl} line 220 to {@code '02'} and {@code app/cbl/CBACT04C.cbl} line
     * 482 to {@code '01'}.
     */
    // WHY : Alternatives Considered: a JPA association to the reference context's transaction-type
    //       entity, and correspondingly for the category code below and the card number further
    //       down. All three are rejected: those rows are owned by the reference and card contexts,
    //       and a type here may not import another service's domain package. The transaction domain
    //       charter fixes that boundary, and this file's strict dependency whitelist admits only
    //       com.carddemo.common as shared Java code, so all three stay plain scalars and cross-
    //       context data is reached over HTTP rather than by import.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_cd", length = 2)
    private String tranTypeCd;

    /**
     * The four-character transaction category code.
     *
     * <p>Assumptions: {@code TRAN-CAT-CD PIC 9(04)} at line 7 of the record contract, bytes 19 to
     * 22, mapped to {@code category_cd CHAR(4)}. Both writers that fix it write a short value that
     * the numeric picture zero-fills to the full width: {@code app/cbl/COBIL00C.cbl} line 221 moves
     * {@code 2} and {@code app/cbl/CBACT04C.cbl} line 483 moves {@code '05'}.
     */
    // WHY : Assumptions: a character member and a character column, although the picture at line 7
    //       is numeric and four digits would fit a Short. The migration settles it at its lines 134
    //       to 146: this is a label rather than a magnitude, no reference program performs
    //       arithmetic on it, and its leading zeros are significant -- every value in
    //       app/data/ASCII/tcatbal.txt is 0001, which a numeric column would render as 1. The
    //       baseline agrees where it stored the same field in Db2:
    //       app/app-transaction-type-db2/ddl/TRNTYCAT.ddl line 3 declares TRC_TYPE_CATEGORY
    //       CHAR(4) NOT NULL. Where a width-only derivation and the migration disagree the
    //       migration governs, because it is the physical contract this type answers to.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_cd", length = 4)
    private String tranCatCd;

    /**
     * The ten-character source that originated the transaction.
     *
     * <p>Assumptions: {@code TRAN-SOURCE PIC X(10)} at line 8 of the record contract, bytes 23 to
     * 32, mapped to {@code source CHAR(10)}. {@code app/cbl/COBIL00C.cbl} line 222 writes
     * {@code 'POS TERM'} and {@code app/cbl/CBACT04C.cbl} line 484 writes {@code 'System'}.
     */
    // WHY : Assumptions: a fixed-width column rather than a varying one, because this is a small
    //       closed set used as a code and the reference programs compare it blank-padded to its
    //       declared width. Both distinct values measured across the 300 records of
    //       app/data/ASCII/dailytran.txt, 'OPERATOR' and 'POS TERM', are shorter than ten
    //       characters and blank-padded there. Under a varying column 'POS TERM' and 'POS TERM   '
    //       would be two values where the baseline has one.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source", length = 10)
    private String tranSource;

    /**
     * The transaction description, up to one hundred characters.
     *
     * <p>Assumptions: {@code TRAN-DESC PIC X(100)} at line 9 of the record contract, bytes 33 to
     * 132, mapped to {@code description VARCHAR(100)}. This one is descriptive text rather than a
     * code, so its trailing blanks are padding to the fixed record length and are never compared:
     * across the 300 records of {@code app/data/ASCII/dailytran.txt} every description is
     * blank-padded and the longest trimmed value is 48 of the declared 100 characters.
     * {@code app/cbl/COBIL00C.cbl} line 223 writes {@code 'BILL PAYMENT - ONLINE'} and
     * {@code app/cbl/CBACT04C.cbl} composes an interest description around the account identifier.
     */
    @Column(name = "description", length = 100)
    private String tranDesc;

    /**
     * The transaction amount, exact to the cent.
     *
     * <p>Assumptions: {@code TRAN-AMT PIC S9(09)V99} at line 10 of the record contract, nine
     * integral digits and two fractional, occupying eleven bytes at 133 to 143 in display usage,
     * mapped to {@code amount NUMERIC(11,2)}. The reference form is zoned decimal with sign
     * overpunch rather than packed, so the sign travels in the final byte of the digits: the first
     * record of {@code app/data/ASCII/dailytran.txt} reads {@code 0000005047G} in that field, whose
     * trailing {@code G} carries both the digit 7 and a positive sign for +504.77, and the second
     * record ends in a right brace, which carries the digit 0 together with a negative sign for
     * -919.00. Decoding that encoding is the work of {@code com.carddemo.common.codec}; what this
     * member guarantees is only that the value it holds is exact.
     */
    // WHY : Assumptions: the scale is taken from Money.SCALE in the shared kernel rather than
    //       written as the literal 2, because the parent charter rules that shared types are
    //       consumed from com.carddemo.common and never re-declared beneath it, and the money scale
    //       is one of those shared declarations. The precision stays a literal because 11 is
    //       specific to this record's picture and not a shared constant.
    // WHY : Trade-offs: exact fixed point is carried end to end, and the compromise accepted is at
    //       the wire hop rather than here. Neither binary floating-point type may appear in the
    //       money path -- a value such as 504.77 has no exact binary representation, so a balance
    //       could differ from the baseline by a cent with nothing in the schema to reveal it. The
    //       shared kernel's architecture-test contract names this prohibition, and this type
    //       honours it structurally by declaring only BigDecimal for the amount. On the wire the
    //       value is a JSON string, applied by com.carddemo.common.money.MoneyModule rather than by
    //       an annotation here, which costs every client an explicit parse and buys exactness at
    //       the one hop a user actually sees.
    @Column(name = "amount", precision = 11, scale = Money.SCALE)
    private BigDecimal tranAmt;

    /**
     * The merchant that originated the transaction.
     *
     * <p>Assumptions: {@code TRAN-MERCHANT-ID PIC 9(09)} at line 11 of the record contract, bytes
     * 144 to 152, mapped to {@code merchant_id BIGINT}. Unlike the category code above this one is
     * a magnitude with no significant leading zero, so a numeric column is right: every value
     * measured across {@code app/data/ASCII/dailytran.txt} is {@code 800000000}, and
     * {@code app/cbl/COBIL00C.cbl} line 226 writes the all-nines sentinel {@code 999999999} for a
     * bill payment, so the column has to hold the full nine-digit width. {@code Long} is the target
     * because nine digits already exceed what a signed thirty-two-bit integer holds at the
     * sentinel.
     */
    @Column(name = "merchant_id")
    private Long merchantId;

    /**
     * The merchant name, up to fifty characters.
     *
     * <p>Assumptions: {@code TRAN-MERCHANT-NAME PIC X(50)} at line 12 of the record contract, bytes
     * 153 to 202, mapped to {@code merchant_name VARCHAR(50)}. Descriptive, so a varying column by
     * the same reasoning as the description above; blank-padded across all 300 measured records
     * with a longest trimmed value of 36. {@code app/cbl/CBACT04C.cbl} line 492 writes spaces here
     * for a system-generated interest transaction.
     */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * The merchant city, up to fifty characters.
     *
     * <p>Assumptions: {@code TRAN-MERCHANT-CITY PIC X(50)} at line 13 of the record contract, bytes
     * 203 to 252, mapped to {@code merchant_city VARCHAR(50)}. Descriptive for the same reason;
     * longest trimmed value measured is 19 of the declared 50.
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * The merchant postal code, up to ten characters.
     *
     * <p>Assumptions: {@code TRAN-MERCHANT-ZIP PIC X(10)} at line 14 of the record contract, bytes
     * 253 to 262, mapped to {@code merchant_zip CHAR(10)}.
     */
    // WHY : Assumptions: a fixed-width column even though its two descriptive neighbours are
    //       varying, because a postal code is matched as a code and its leading zeros are
    //       significant: 27 of the 300 values in app/data/ASCII/dailytran.txt begin with a zero.
    //       Keeping it fixed width preserves the blank-padded comparison the reference programs
    //       perform on it.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * The sixteen-character card number the transaction was made against.
     *
     * <p>Assumptions: {@code TRAN-CARD-NUM PIC X(16)} at line 15 of the record contract, bytes 263
     * to 278, mapped to {@code card_num CHAR(16)}. This is the column
     * {@code idx_transactions_card_num} orders. Its posted value is copied from the feed at
     * {@code app/cbl/CBTRN02C.cbl} line 435, and that same feed value is what line 382 of the same
     * program moves into the cross-reference key to resolve the account, so a shortened value would
     * turn a valid row into a rejected one before it ever reached this table.
     */
    // WHY : Alternatives Considered: a numeric column, which the digits invite. Rejected on a
    //       measurement rather than a preference: 30 of the 300 card numbers in
    //       app/data/ASCII/dailytran.txt begin with a zero, so a numeric column would silently
    //       shorten a tenth of the extract to fifteen digits and break every lookup on the value.
    //       No example value is reproduced here, because a primary account number does not belong
    //       in source prose even when it comes from a seed extract, and it is the aggregate count
    //       that the type decision rests on. See also the report job's ZD typing of this field,
    //       addressed against the copybook in the class documentation above.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * The originating timestamp, to microsecond precision.
     *
     * <p>Assumptions: {@code TRAN-ORIG-TS PIC X(26)} at line 16 of the record contract, bytes 279
     * to 304, mapped to {@code orig_ts TIMESTAMP(6)}. The twenty-six characters and microsecond
     * precision match exactly, so nothing is truncated on the way in and nothing invented on the
     * way out: the reference layout at {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55 is thirteen
     * elementary items summing to 26, with a space separator at line 48, a period at line 54 and a
     * six-digit fraction at line 55, giving the form {@code YYYY-MM-DD HH:MM:SS.mmmmmm}. The first
     * record of {@code app/data/ASCII/dailytran.txt} carries {@code 2022-06-10 19:27:53.000000}
     * here, and all 300 records carry a populated value. {@code app/cbl/CBTRN02C.cbl} line 436
     * copies this member across from the feed unchanged.
     */
    // WHY : Alternatives Considered: a zone-bearing temporal representation or a UTC timeline
    //       point. Both are rejected because there is no zone in the source to carry: the thirteen
    //       items of the reference layout are four date and time components and six separators,
    //       with no offset item at all, so either alternative would have to invent an offset and no
    //       reader could then tell an invented one from a recorded one. Rendering and parsing the
    //       twenty-six character form belongs to com.carddemo.common.time.TimestampFormatter,
    //       whose length constant is 26 and whose pattern uses the proleptic year letter under a
    //       strict resolver; no pattern is declared here, so the two cannot drift apart.
    // WHY : Assumptions: three distinct twenty-six character forms reach this member and its
    //       processing counterpart, and a mapper must not normalise them together. app/cbl/
    //       COTRN02C.cbl lines 464 and 465 move screen fields declared TORIGDTI PIC X(10) at
    //       app/cpy-bms/COTRN02.CPY line 102 and TPROCDTI PIC X(10) at its line 108, which is a
    //       ten-character date-only value with no time component, validated character by character
    //       as YYYY-MM-DD; that form is not parseable by the shared formatter's full parse and
    //       needs its date-prefix entry point instead. app/cbl/COBIL00C.cbl lines 263 to 266 build
    //       a fully punctuated value whose fractional digits are literal zeros and then move that
    //       one value into both members at its lines 231 and 232. app/cbl/CBACT04C.cbl lines 496 to
    //       498 likewise put one value into both.
    @Column(name = "orig_ts")
    private LocalDateTime origTs;

    /**
     * The processing timestamp, to microsecond precision, which every posted row carries.
     *
     * <p>Assumptions: {@code TRAN-PROC-TS PIC X(26)} at line 17 of the record contract, bytes 305
     * to 330, mapped to {@code proc_ts TIMESTAMP(6) NOT NULL}. The form, the precision and the
     * rejection of a zoned type are argued on the originating member above and are not restated.
     * This is the column {@code idx_transactions_proc_ts} orders, and the column the batch
     * alternate index keyed at zero-based offset 304.
     */
    // WHY : Assumptions: this member is non-nullable here while the same member of
    //       DailyTransaction is nullable, and the asymmetry is a code-level fact rather than a data
    //       observation. app/cbl/CBTRN02C.cbl mints this stamp only in the posting path: line 437
    //       performs Z-GET-DB2-FORMAT-TIMESTAMP and line 438 moves the result into this member,
    //       inside 2000-POST-TRANSACTION at line 424, whereas line 436 copies the originating stamp
    //       straight across from the feed. A posted transaction therefore always has a processing
    //       stamp and a record still awaiting posting does not, which is why the feed's own extract
    //       leaves the field blank on all 300 of its 300 records while populating the originating
    //       stamp 300 times. A row here without this value is a row no reference path can produce.
    @Column(name = "proc_ts", nullable = false)
    private LocalDateTime procTs;

    /**
     * Creates an empty instance for the persistence provider to hydrate.
     *
     * <p>Assumptions: the provider requires a no-argument constructor in order to instantiate this
     * type reflectively when it materialises a row or builds a lazy proxy, and it populates the
     * members afterwards by field access rather than through the accessors below. The body is
     * therefore empty by design and not unfinished: there is nothing to assign that the provider is
     * not about to assign itself, and defaulting a member here would overwrite a column value on
     * every load.
     *
     * <p>Alternatives Considered: making it private and relying on the provider's reflective access
     * to a private constructor. Rejected because a subclass generated for a lazy proxy has to be
     * able to invoke it, so protected is the narrowest visibility that works. Rule 1 attaches its
     * docstring obligation to every function with no visibility qualifier, so this constructor
     * carries a full one rather than being treated as boilerplate.
     */
    protected Transaction() {
        // WHY : Assumptions: intentionally empty. The provider assigns every member after
        //       construction, so any initialisation written here would be overwritten on a load and
        //       would silently mask an absent column on an insert.
    }

    /**
     * Creates a fully populated posted transaction.
     *
     * <p>Assumptions: every member is supplied by the caller and none is derived here. That mirrors
     * the reference programs, each of which assembles the whole record before appending it -- the
     * posting path at {@code app/cbl/CBTRN02C.cbl} lines 425 to 438 and the interest path at
     * {@code app/cbl/CBACT04C.cbl} lines 476 to 498 both set every member and only then write.
     * Validation is not performed here either: the reference validation sequence belongs to the
     * service layer, and duplicating a subset of it in a constructor would give the same rule two
     * homes that could disagree.
     *
     * <p>Trade-offs: thirteen parameters is a long signature, and a builder was the alternative. It
     * is rejected because the parameter list is fixed by a record contract that is read as
     * specification and does not change, so the flexibility a builder buys has nothing to vary,
     * while the constructor keeps every member visibly required at the one point a row is created.
     * The cost accepted is that a caller must order the arguments correctly; they are declared in
     * the copybook's own order, lines 5 to 17, so the signature reads against the contract
     * directly.
     *
     * @param tranId the sixteen-character transaction identifier, an opaque fixed-width token that
     *     becomes this row's primary key
     * @param tranTypeCd the two-character transaction type code
     * @param tranCatCd the four-character transaction category code
     * @param tranSource the ten-character originating source
     * @param tranDesc the transaction description, up to one hundred characters
     * @param tranAmt the transaction amount, exact at two decimal places, positive or negative
     * @param merchantId the nine-digit merchant identifier
     * @param merchantName the merchant name, up to fifty characters
     * @param merchantCity the merchant city, up to fifty characters
     * @param merchantZip the merchant postal code, up to ten characters
     * @param cardNum the sixteen-character card number the transaction was made against
     * @param origTs the originating timestamp, to microsecond precision and carrying no zone
     * @param procTs the processing timestamp, to microsecond precision, which a posted row always
     *     carries
     */
    public Transaction(String tranId, String tranTypeCd, String tranCatCd, String tranSource,
            String tranDesc, BigDecimal tranAmt, Long merchantId, String merchantName,
            String merchantCity, String merchantZip, String cardNum, LocalDateTime origTs,
            LocalDateTime procTs) {
        this.tranId = tranId;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranSource = tranSource;
        this.tranDesc = tranDesc;
        this.tranAmt = tranAmt;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantZip = merchantZip;
        this.cardNum = cardNum;
        this.origTs = origTs;
        this.procTs = procTs;
    }

    /**
     * Returns the sixteen-character transaction identifier that is this row's primary key.
     *
     * @return the transaction identifier as stored, blank-padded to sixteen characters by the fixed
     *     width column, or {@code null} on an instance the provider has not hydrated
     */
    public String getTranId() {
        return this.tranId;
    }

    /**
     * Assigns the transaction identifier.
     *
     * <p>Assumptions: this is the primary key and the column is mapped {@code updatable = false},
     * so assigning it after the row is persisted changes the member without changing the row. The
     * accessor exists for the mapper to populate a new instance, not to re-key an existing one.
     *
     * @param tranId the sixteen-character transaction identifier to assign, an opaque fixed-width
     *     token generated by the writing path rather than by this type
     */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /**
     * Returns the two-character transaction type code.
     *
     * @return the type code, or {@code null} when the row carries none
     */
    public String getTranTypeCd() {
        return this.tranTypeCd;
    }

    /**
     * Assigns the two-character transaction type code.
     *
     * @param tranTypeCd the type code to assign; it is validated against the reference context's
     *     transaction types by the service layer rather than here
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * Returns the four-character transaction category code.
     *
     * @return the category code, zero-filled to four characters, or {@code null} when the row
     *     carries none
     */
    public String getTranCatCd() {
        return this.tranCatCd;
    }

    /**
     * Assigns the four-character transaction category code.
     *
     * @param tranCatCd the category code to assign; it is validated against the reference context's
     *     transaction categories by the service layer rather than here
     */
    public void setTranCatCd(String tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Returns the ten-character source that originated the transaction.
     *
     * @return the source as stored, blank-padded to ten characters by the fixed-width column, or
     *     {@code null} when the row carries none
     */
    public String getTranSource() {
        return this.tranSource;
    }

    /**
     * Assigns the ten-character originating source.
     *
     * @param tranSource the source to assign, one of the small closed set of labels the reference
     *     writers use
     */
    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    /**
     * Returns the transaction description.
     *
     * @return the description, up to one hundred characters, or {@code null} when the row carries
     *     none
     */
    public String getTranDesc() {
        return this.tranDesc;
    }

    /**
     * Assigns the transaction description.
     *
     * @param tranDesc the description to assign, at most one hundred characters
     */
    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    /**
     * Returns the transaction amount, exact at two decimal places.
     *
     * @return the amount, positive or negative, at two decimal places, or {@code null} when the row
     *     carries none
     */
    public BigDecimal getTranAmt() {
        return this.tranAmt;
    }

    /**
     * Assigns the transaction amount.
     *
     * <p>Assumptions: the caller supplies a value already reduced to the money scale, which is what
     * {@code com.carddemo.common.money.Money} produces. No rescaling is applied here, because
     * rounding a monetary value is a decision with an owner and this type is not it; silently
     * rescaling would hide a caller that had lost a fraction of a cent upstream.
     *
     * @param tranAmt the amount to assign, exact at two decimal places
     */
    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    /**
     * Returns the merchant that originated the transaction.
     *
     * @return the nine-digit merchant identifier, or {@code null} when the row carries none
     */
    public Long getMerchantId() {
        return this.merchantId;
    }

    /**
     * Assigns the merchant identifier.
     *
     * @param merchantId the nine-digit merchant identifier to assign, which the reference bill
     *     payment path sets to an all-nines sentinel
     */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the merchant name.
     *
     * @return the merchant name, up to fifty characters, or {@code null} when the row carries none
     */
    public String getMerchantName() {
        return this.merchantName;
    }

    /**
     * Assigns the merchant name.
     *
     * @param merchantName the merchant name to assign, at most fifty characters
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the merchant city.
     *
     * @return the merchant city, up to fifty characters, or {@code null} when the row carries none
     */
    public String getMerchantCity() {
        return this.merchantCity;
    }

    /**
     * Assigns the merchant city.
     *
     * @param merchantCity the merchant city to assign, at most fifty characters
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the merchant postal code.
     *
     * @return the postal code as stored, blank-padded to ten characters by the fixed-width column,
     *     or {@code null} when the row carries none
     */
    public String getMerchantZip() {
        return this.merchantZip;
    }

    /**
     * Assigns the merchant postal code.
     *
     * @param merchantZip the postal code to assign, at most ten characters, whose leading zeros are
     *     significant
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the sixteen-character card number the transaction was made against.
     *
     * <p>Assumptions: the value is returned whole and unmasked, because this is the persistence
     * boundary and the reference paths that resolve an account from it need every digit. Masking it
     * to its last four digits for anything a person or a log reads is the mapper package's work,
     * which the parent charter names as the sole boundary where that concern may appear.
     *
     * @return the card number as stored, sixteen characters, or {@code null} when the row carries
     *     none
     */
    public String getCardNum() {
        return this.cardNum;
    }

    /**
     * Assigns the card number the transaction was made against.
     *
     * @param cardNum the sixteen-character card number to assign, whose leading zeros are
     *     significant on roughly a tenth of the reference extract
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the originating timestamp.
     *
     * @return the originating timestamp to microsecond precision and carrying no zone, or
     *     {@code null} when the row carries none
     */
    public LocalDateTime getOrigTs() {
        return this.origTs;
    }

    /**
     * Assigns the originating timestamp.
     *
     * <p>Assumptions: the caller has already resolved which of the three reference twenty-six
     * character forms it holds, because two of them are not interchangeable -- the online add
     * screen supplies a ten-character date with no time component, while the batch and bill payment
     * paths supply a fully punctuated value. Accepting an already-parsed value here keeps that
     * decision at the mapper, where the form is known.
     *
     * @param origTs the originating timestamp to assign, to microsecond precision and without a
     *     zone
     */
    public void setOrigTs(LocalDateTime origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the processing timestamp.
     *
     * @return the processing timestamp to microsecond precision and carrying no zone, which a
     *     persisted row always carries
     */
    public LocalDateTime getProcTs() {
        return this.procTs;
    }

    /**
     * Assigns the processing timestamp.
     *
     * <p>Assumptions: the column is non-nullable, so a row inserted without this value is refused
     * by the database rather than defaulted here. Minting a stamp in this accessor was the
     * alternative and would have made the value depend on when the object was built rather than on
     * when the transaction posted, which is the distinction the reference posting path draws at
     * {@code app/cbl/CBTRN02C.cbl} line 438.
     *
     * @param procTs the processing timestamp to assign, to microsecond precision and without a zone
     */
    public void setProcTs(LocalDateTime procTs) {
        this.procTs = procTs;
    }

    /**
     * Compares this row with another on the transaction identifier alone. Two instances carrying
     * the same identifier denote the same posted transaction.
     *
     * <p>Alternatives Considered: comparing all thirteen members. Rejected because the identifier
     * is the key the record contract declares at line 5 and the key the migration declares as
     * {@code pk_transactions}, so it already determines the row uniquely. Comparing every member as
     * well would make two reads of one row unequal the moment a query populated a different subset
     * of columns, and would additionally break identity across a persistence flush, when the
     * provider writes generated or defaulted values into an instance that a collection is already
     * holding.
     *
     * <p>Assumptions: an instance built by the no-argument constructor and not yet hydrated carries
     * no identifier, so two such instances compare equal because the identifier is the only value
     * compared and there is nothing else to tell them apart. The provider never publishes an
     * instance in that state, so the case is recorded rather than guarded against.
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is a transaction of this type carrying an equal
     *     transaction identifier, {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Assumptions: a pattern match rather than a class comparison, because the provider
        //       may hand back an instrumented subclass for a lazy proxy and a strict class
        //       comparison would then report a row as unequal to itself. No subclass of this type
        //       is authored, so widening the test costs nothing.
        if (!(other instanceof Transaction that)) {
            return false;
        }
        return Objects.equals(this.tranId, that.tranId);
    }

    /**
     * Returns a hash consistent with the identifier-only equality above. It is derived from the
     * transaction identifier and from nothing else.
     *
     * <p>Assumptions: the identifier is assigned once by the writing path and this table is
     * insert-only along every reference path, so the value stays stable for the life of an instance
     * in practice even though a setter exists for the mapper to populate a new one. That stability
     * is what makes the type safe to place in a hash-based collection; re-keying a persisted
     * instance would break it, which is why the identifier column is mapped
     * {@code updatable = false}.
     *
     * @return the hash of the transaction identifier, or the hash of an absent value on an instance
     *     the provider has not yet hydrated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.tranId);
    }

    /**
     * Returns a diagnostic rendering of this row that deliberately names only three of its thirteen
     * members. It is intended for a log line or an assertion message.
     *
     * <p>Trade-offs: the card number declared at line 15 of the record contract and the amount
     * declared at line 10 are omitted outright rather than abbreviated, so this rendering is not
     * round-trippable and a reader cannot identify a row's card or its value from a log line. That
     * cost is accepted because the compensation is absolute: no log line written from this type can
     * carry a primary account number or a monetary figure at all. Abbreviation was the alternative
     * and is rejected because abbreviating a primary account number IS masking, and the parent
     * charter names the mapper package as the sole boundary where masking may appear; a second,
     * slightly different masking rule here would give one value two renderings and make neither
     * authoritative. The three members retained are the identifier from line 5, which locates the
     * row exactly, the type code from line 6 and the processing timestamp from line 17, which
     * together place it in the posting run that produced it.
     *
     * @return a short single-line rendering naming the type, the transaction identifier, the
     *     transaction type code and the processing timestamp, and no other member
     */
    @Override
    public String toString() {
        return "Transaction[tranId=" + this.tranId
                + ", tranTypeCd=" + this.tranTypeCd
                + ", procTs=" + this.procTs + ']';
    }
}
