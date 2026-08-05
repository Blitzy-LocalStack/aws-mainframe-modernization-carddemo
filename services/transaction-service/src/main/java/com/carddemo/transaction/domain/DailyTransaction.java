package com.carddemo.transaction.domain;

import com.carddemo.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A record on the pre-posting daily feed, mapping one row of {@code ledger.daily_transactions} to
 * one migrated {@code DALYTRAN-RECORD}.
 *
 * <p>This is the persistence form of the inbound feed rather than of the posted master. A row here
 * has been extracted and loaded but has not been validated or posted, and it either becomes a
 * posted transaction or becomes a rejected one. It carries the thirteen data members its record
 * contract declares, at the widths and scales that contract declares them, and it holds no business
 * logic of its own: the preflight and posting rules belong to the batch context.
 *
 * <h2>Provenance of the record contract</h2>
 *
 * <p>Assumptions: the contract is {@code app/cpy/CVTRA06Y.cpy} lines 4 to 18. Line 4 declares
 * {@code 01 DALYTRAN-RECORD} and line 2 states the record length as 350. Fourteen {@code 05} items
 * follow: thirteen carry data and total 330 bytes, and the fourteenth, {@code FILLER PIC X(20)} at
 * line 18, pads bytes 331 to 350 out to the declared length. Every byte range named in this file is
 * one-based and inclusive. The reference tree is read as specification and is never modified, so
 * these members encode widths and scales it already states rather than redefining them.
 *
 * <p>Assumptions: the declared length agrees with the file description the posting program reads it
 * through. {@code app/cbl/CBTRN02C.cbl} line 66 declares {@code FD DALYTRAN-FILE} over a record of
 * a 16-byte identifier at line 68 and a 334-byte remainder at line 69, which is the same 350 bytes
 * partitioned differently -- the program names only the leading identifier at that level and treats
 * the rest as an undivided block, then works with the full item structure through the copybook.
 *
 * <h2>The layout is the posted master's layout</h2>
 *
 * <p>Assumptions: lines 5 to 18 of that contract were read item by item against
 * {@code app/cpy/CVTRA05Y.cpy} lines 5 to 18 and are structurally identical to them -- the same
 * thirteen pictures in the same order, closed by the same {@code FILLER PIC X(20)} at line 18 --
 * with only the field-name prefix differing. That is not an inference drawn from the two copybooks
 * alone: {@code app/cbl/CBTRN02C.cbl} lines 425 to 435 project one onto the other with eleven
 * consecutive one-to-one moves, and its line 436 moves the originating timestamp across as the
 * twelfth. The sibling {@code Transaction} in this package maps that same layout and is its
 * canonical form, so this type mirrors its member set, its ordering and its naming exactly, and the
 * arguments the sibling records for a member are not restated here where they are unchanged.
 *
 * <h2>The name prefix is not carried into Java or into SQL</h2>
 *
 * <p>Alternatives Considered: prefixing these members and their columns as the copybook prefixes
 * its fields, giving a member such as {@code dalytranId} over a column such as {@code dalytran_id}.
 * Rejected because the prefix is a record-scoping artifact of a language in which every field name
 * is global to the compilation unit, and neither target has that problem: a column is already
 * scoped by the table that declares it and a member by the type that declares it, so the prefix
 * would restate the table name in every column name. It would also obscure the one relationship a
 * reader most needs to see. The projection at {@code app/cbl/CBTRN02C.cbl} lines 425 to 435 is
 * eleven straight moves of like onto like, and identically named members make that projection
 * self-evident where a prefixed set would make eleven trivial renames look like eleven decisions.
 * The cost accepted is that a member name here no longer states which of the two record contracts
 * it came from; the type name does that.
 *
 * <h2>The migration owns the physical shape, and this type answers to it</h2>
 *
 * <p>Assumptions: the authoritative column list is
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}, which
 * declares this table at its lines 319 to 398 and records at its lines 400 to 413 why it declares
 * no key over it. The sibling {@code application.yml} sets {@code ddl-auto: none} at its line 469,
 * so the persistence provider generates no schema at all and every column name, length, precision,
 * scale and nullability written below has to match that migration exactly. Nothing reconciles the
 * two at start-up once generation is switched off, so a mismatch stays invisible until a query
 * runs.
 *
 * <p>Assumptions: the Java member names follow the record contract while the column names follow
 * the migration, so the two differ on six of the thirteen members and that split is deliberate. The
 * parent package charter rules that no field beneath it is renamed, so {@code tranId} keeps the
 * spelling of {@code DALYTRAN-ID} shorn of its prefix, and the migration's own column name is
 * attached explicitly through {@code @Column(name = ...)}. Reading either name off the other is
 * therefore unsafe, which is why every mapping below names its column rather than relying on a
 * naming strategy to derive one.
 *
 * <p>Assumptions: where a width-only derivation from the copybook and the migration could be read
 * differently, the migration governs, because it is the physical contract this type answers to.
 * Three members are affected and each is argued where it is declared: the category code carries a
 * numeric picture that four digits would fit into a small integer, and the migration declares it a
 * fixed-width character column; the originating source and the postal code carry alphanumeric
 * pictures that a descriptive reading would map to varying columns, and the migration declares both
 * fixed width.
 *
 * <h2>Two mappings differ from the posted master's, and both differences are the migration's</h2>
 *
 * <p>Assumptions: this table and {@code ledger.transactions} carry the same thirteen columns from
 * the same thirteen pictures, and the migration nonetheless constrains two of them differently
 * here. The processing timestamp is nullable where the master's is not null, and the transaction
 * identifier carries neither a not-null constraint nor a primary key where the master's carries
 * both. Neither difference comes from the copybooks, which declare the two records identically;
 * both come from what the reference programs do with the two files, and each is argued on the
 * member that maps it. A reader who expects the two types to differ in exactly one place will find
 * two, and the second is the reason this heading is plural.
 *
 * <h2>This table has no key, and the identifier is still the mapped identity</h2>
 *
 * <p>Assumptions: the migration declares no primary key, no unique constraint and no not-null
 * column over this table, and its lines 400 to 413 record that as the baseline contract rather than
 * as an omission. The evidence is the feed's own organisation: {@code app/cbl/CBTRN02C.cbl} line 29
 * selects it as {@code ORGANIZATION IS SEQUENTIAL} with {@code ACCESS MODE IS SEQUENTIAL} at line
 * 31 and declares no record key at all, and {@code app/jcl/POSTTRAN.jcl} lines 30 and 31 supply it
 * as a physical sequential dataset. The posting job reads it front to back and never keys into it.
 *
 * <p>Trade-offs: the identifier is nevertheless mapped as this type's identity, because the
 * persistence provider requires an identity for every entity and has no other way to tell one row
 * of a result from another, to decide whether an instance is new, or to build the predicate of an
 * update. What is bought is that the feed is reachable through the same repository abstraction as
 * every other record in this package. What is given up is that the identity is an assertion of this
 * mapping rather than of the schema: the column carries no unique constraint, so a feed with a
 * repeated identifier -- which the sequential baseline would process without complaint -- loads
 * without error and produces two rows that this type cannot tell apart. Declaring the constraint
 * instead was the alternative and is rejected because it would reject at load time a feed the
 * baseline accepts, which is a behavioural change in the loader rather than a safeguard. The
 * identity is workable on the extract that exists: the 300 records of
 * {@code app/data/ASCII/dailytran.txt} carry 300 distinct identifiers and are already in ascending
 * order of them, though that is a property of one extract and not a contract.
 *
 * <h2>No index is declared here</h2>
 *
 * <p>Alternatives Considered: naming an index on the table annotation of this type, as the
 * persistence API's index element allows. Rejected on two independent grounds. The migration
 * declares none over this table -- the two secondary indexes in that file, created at its lines 269
 * and 291, are both over {@code ledger.transactions} -- so any index named here would be an index
 * that does not exist. And because {@code ddl-auto: none} means such metadata is never acted on, it
 * would neither create nor verify one, so it could drift out of step with the migration while still
 * reading like a specification. The access pattern needs none: the posting job reads this feed in
 * one pass.
 *
 * <h2>No version attribute, and the absence is recorded rather than merely left</h2>
 *
 * <p>Alternatives Considered: annotating this type with {@code @Version} for optimistic
 * concurrency. That is the plausible alternative, because the wider migration does adopt exactly
 * that pattern for the account and card records, and it is rejected on evidence measured on the
 * reference branch. Nothing rewrites this feed. {@code app/cbl/CBTRN01C.cbl} only reads it, at line
 * 203. {@code app/cbl/CBTRN02C.cbl} reads it sequentially through the paragraph its line 204
 * performs and never writes it back: the only two rewrite statements in that program are at line
 * 528, the category balance, and line 554, the account record. The first of those is a read, then
 * an add, then the rewrite, all inside one unit of work, with no snapshot of the pre-edit image and
 * no data-changed flag -- which is what a before-image concurrency check would need. The baseline's
 * genuine optimistic-concurrency pattern is the before-image comparison in the account-update and
 * card-update programs, whose entities belong to the account and card services, so adopting it here
 * would import a pattern from records that need it onto a record that does not.
 *
 * <p>Assumptions: a second check is mechanical. Because schema generation is switched off, a
 * version attribute would map to a column the migration does not create and would fail when a query
 * ran rather than degrade to unversioned behaviour. The absence is stated because an entity with no
 * version attribute looks identical whether the omission was reasoned or overlooked.
 *
 * <h2>The lifecycle of a row</h2>
 *
 * <p>Assumptions: a row arrives from outside this module and leaves it in one of two shapes, and
 * nothing in this package moves it. It is loaded from the exported flat feed the reference job
 * reads, whose seed copy is {@code app/data/ASCII/dailytran.txt}, by the migration's extract and
 * load tooling. The preflight job derived from {@code app/cbl/CBTRN01C.cbl} reads it and resolves
 * the cross-reference and account records around it. The posting job derived from
 * {@code app/cbl/CBTRN02C.cbl} then reads it and takes one of two paths, decided by the validation
 * sequence its line 210 performs: on success its line 212 posts, projecting this record onto
 * {@code ledger.transactions}; on failure its line 215 writes a row of
 * {@code ledger.transaction_rejects}.
 *
 * <p>Assumptions: the reject path retains this record whole rather than decomposed, which is why
 * the reject row's image column is a fixed-width 350 characters and is never split into fields.
 * Line 447 of that program moves the entire {@code DALYTRAN-RECORD} into the reject area in one
 * move, with no field-level handling at all. A record that has already failed validation cannot be
 * trusted to parse, so retaining its bytes verbatim is what leaves a re-drive possible once the
 * cause is addressed. That the record image survives at its full 350 bytes is also why the padding
 * member dropped below costs nothing on the reject path.
 *
 * <p>Assumptions: one property of the preflight program is worth recording so that a reader does
 * not go looking for something that is not there. {@code app/cbl/CBTRN01C.cbl} has no job control
 * driving it anywhere in the reference tree -- it is reached only by the repository's own test
 * suite -- and it is migrated regardless, as a state of the nightly chain. That is an observation
 * about how the reference tree is arranged and not a judgement about it.
 *
 * <h2>The batch context agrees with this type through the schema, never through code</h2>
 *
 * <p>Alternatives Considered: the two jobs that read this table belong to the batch context rather
 * than to this module, and the two modules could have been coupled by sharing this type. Instead
 * the batch context declares its own domain package and reaches these rows under a narrowly scoped
 * cross-schema grant, so neither module imports a type from the other and the only shared artifact
 * is the physical schema. The grant exists because the posting unit of work at
 * {@code app/cbl/CBTRN02C.cbl} lines 424 to 444 performs three writes in sequence -- the category
 * balance at line 440, the account record at line 441 and the posted transaction at line 442 -- and
 * has to stay one atomic commit. A saga across two services was the other alternative and is
 * rejected because it would replace that single commit with committed steps plus compensating
 * reversals, making partial-posting states observable that the baseline does not have; a posted
 * transaction with an unposted balance is precisely what a parity comparison would flag, and
 * correctly.
 *
 * <p>Assumptions: this table is an input stream on that side of the boundary. No job in the batch
 * context writes it, so the grant it needs over these rows is a read.
 *
 * <h2>The padding member is dropped</h2>
 *
 * <p>Trade-offs: the {@code FILLER PIC X(20)} at line 18 of the record contract is dropped rather
 * than carried as a fourteenth member, so the 350-byte record length is not reconstructible from
 * this type alone and a reader reconciling 350 bytes against thirteen members has to account for
 * the difference from the copybook. Reconstructing a fixed-length image is the work of
 * {@code com.carddemo.common.codec}, which owns record representation for the whole migration. The
 * supporting evidence that those bytes are padding rather than data is that their content is not
 * even consistent between two extracts of the same vintage: in {@code app/data/ASCII/dailytran.txt}
 * the trailing twenty bytes of every one of the 300 records are spaces, while in
 * {@code app/data/ASCII/tcatbal.txt} the trailing bytes of a record are ASCII zero digits. A column
 * holding either would store a writer's padding convention and nothing about the transaction.
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
//       together, and it is that context which reads this feed. Naming the schema here means this
//       type resolves to one table whichever connection loads it.
@Table(name = "daily_transactions", schema = "ledger")
public class DailyTransaction {

    /**
     * The transaction identifier, which is this type's mapped identity.
     *
     * <p>Assumptions: {@code DALYTRAN-ID PIC X(16)} at line 5 of the record contract, bytes 1 to
     * 16, mapped to {@code transaction_id CHAR(16)}. The migration declares that column without a
     * not-null constraint and declares no key over the table, so no {@code nullable} attribute is
     * written here: the mapping states what the migration states and nothing more. The same value
     * becomes the posted row's primary key once {@code app/cbl/CBTRN02C.cbl} line 425 moves it
     * across, which is where the constraint the feed lacks is asserted.
     */
    // WHY : Trade-offs: the identity is mapped although the schema constrains nothing, and the
    //       compromise that buys is argued in this type's class documentation rather than twice.
    //       What belongs on the member is the consequence: two rows sharing this value are
    //       indistinguishable to this type, and it is the loader's business to notice a feed that
    //       carries such a pair rather than this type's to refuse it.
    // WHY : Alternatives Considered: an integer column and a Long member, which is the obvious
    //       reading because sixteen decimal digits fit a signed sixty-four-bit integer. Rejected
    //       because line 5 of the record contract declares an alphanumeric picture and the copybook
    //       is normative, so the fixed width is the contract and a leading zero is significant --
    //       the first identifier in app/data/ASCII/dailytran.txt is 0000000000683580, which an
    //       integer column would store as 683580, ten characters short of the sixteen the contract
    //       fixes. The value is therefore treated as an opaque sixteen-character token. The sibling
    //       records the further evidence that two incompatible generation schemes share the posted
    //       column.
    // WHY : Assumptions: the column is mapped non-updatable because an identity is populated once
    //       by whatever loads the row and is never reassigned afterwards. Allowing an update would
    //       let a persisted instance be re-keyed, which would silently invalidate any hash-based
    //       collection already holding it.
    @Id
    @Column(name = "transaction_id", length = 16, updatable = false)
    private String tranId;

    /**
     * The two-character transaction type code.
     *
     * <p>Assumptions: {@code DALYTRAN-TYPE-CD PIC X(02)} at line 6 of the record contract, bytes 17
     * to 18, mapped to {@code type_cd CHAR(2)}. The two values measured across the 300 records of
     * {@code app/data/ASCII/dailytran.txt} are {@code 01} and {@code 03}, both occupying the full
     * declared width. {@code app/cbl/CBTRN02C.cbl} line 426 moves this member onto the posted row
     * unchanged, so the target column matches the posted one exactly.
     */
    // WHY : Alternatives Considered: a JPA association to the reference context's transaction-type
    //       entity, and correspondingly for the category code below and the card number further
    //       down. All three are rejected: those rows are owned by the reference and card contexts,
    //       and a type here may not import another service's domain package. The transaction domain
    //       charter fixes that boundary, and this file's dependency whitelist admits only
    //       com.carddemo.common as shared Java code, so all three stay plain scalars and
    //       cross-context data is reached over HTTP rather than by import. On this table the
    //       argument is stronger still: a row here has not been validated yet, so a mapping that
    //       could only load a code the reference data already knows would refuse exactly the rows
    //       the posting job exists to reject.
    @Column(name = "type_cd", length = 2)
    private String tranTypeCd;

    /**
     * The four-character transaction category code.
     *
     * <p>Assumptions: {@code DALYTRAN-CAT-CD PIC 9(04)} at line 7 of the record contract, bytes 19
     * to 22, mapped to {@code category_cd CHAR(4)}. Every value measured across the 300 records of
     * {@code app/data/ASCII/dailytran.txt} is {@code 0001}, zero-filled to the declared width by
     * the numeric picture. {@code app/cbl/CBTRN02C.cbl} line 427 moves it onto the posted row, and
     * its category-balance paragraph keys on the same value.
     */
    // WHY : Assumptions: a character member and a character column, although the picture at line 7
    //       is numeric and four digits would fit a Short. The migration settles it at its lines 330
    //       to 332, pointing at the argument it makes for the posted column: this is a label rather
    //       than a magnitude, no reference program performs arithmetic on it, and its leading zeros
    //       are significant -- the measured 0001 would render as 1 through a numeric column. Where
    //       a width-only derivation and the migration disagree the migration governs, because it is
    //       the physical contract this type answers to.
    @Column(name = "category_cd", length = 4)
    private String tranCatCd;

    /**
     * The ten-character source that originated the transaction.
     *
     * <p>Assumptions: {@code DALYTRAN-SOURCE PIC X(10)} at line 8 of the record contract, bytes 23
     * to 32, mapped to {@code source CHAR(10)}. {@code app/cbl/CBTRN02C.cbl} line 428 moves it onto
     * the posted row unchanged.
     */
    // WHY : Assumptions: a fixed-width column rather than a varying one, because this is a small
    //       closed set used as a code and the reference programs compare it blank-padded to its
    //       declared width. Both distinct values measured across the 300 records of
    //       app/data/ASCII/dailytran.txt, 'OPERATOR' and 'POS TERM', are shorter than ten
    //       characters and blank-padded there. Under a varying column 'POS TERM' and 'POS TERM   '
    //       would be two values where the baseline has one.
    @Column(name = "source", length = 10)
    private String tranSource;

    /**
     * The transaction description, up to one hundred characters.
     *
     * <p>Assumptions: {@code DALYTRAN-DESC PIC X(100)} at line 9 of the record contract, bytes 33
     * to 132, mapped to {@code description VARCHAR(100)}. This one is descriptive text rather than
     * a code, so its trailing blanks are padding to the fixed record length and are never compared:
     * across the 300 records of {@code app/data/ASCII/dailytran.txt} every description is
     * blank-padded and the longest trimmed value is 48 of the declared 100 characters.
     * {@code app/cbl/CBTRN02C.cbl} line 429 moves it onto the posted row unchanged.
     */
    @Column(name = "description", length = 100)
    private String tranDesc;

    /**
     * The transaction amount, exact to the cent.
     *
     * <p>Assumptions: {@code DALYTRAN-AMT PIC S9(09)V99} at line 10 of the record contract, nine
     * integral digits and two fractional, occupying eleven bytes at 133 to 143 in display usage,
     * mapped to {@code amount NUMERIC(11,2)}. The reference form is zoned decimal with sign
     * overpunch rather than packed, so the sign travels in the final byte of the digits: the first
     * record of {@code app/data/ASCII/dailytran.txt} reads {@code 0000005047G} in that field, whose
     * trailing {@code G} carries both the digit 7 and a positive sign for +504.77, and the second
     * record ends in a right brace, which carries the digit 0 together with a negative sign for
     * -919.00. Both signs therefore occur on the feed and the column has to hold either. Decoding
     * that encoding is the work of {@code com.carddemo.common.codec}; what this member guarantees
     * is only that the value it holds is exact. {@code app/cbl/CBTRN02C.cbl} line 430 moves it onto
     * the posted row unchanged, so no approximate type may appear on either side of that move.
     */
    // WHY : Assumptions: the scale is taken from Money.SCALE in the shared kernel rather than
    //       written as the literal 2, because the parent charter rules that shared types are
    //       consumed from com.carddemo.common and never re-declared beneath it, and the money scale
    //       is one of those shared declarations. The precision stays a literal because 11 is
    //       specific to this record's picture and not a shared constant.
    // WHY : Trade-offs: exact fixed point is carried end to end, and the compromise accepted is at
    //       the wire hop rather than here. Neither binary floating-point type may appear in the
    //       money path -- a value such as 504.77 has no exact binary representation, so an amount
    //       could differ from the baseline by a cent with nothing in the schema to reveal it, and
    //       an amount that differs before posting differs in the posted balance too. The shared
    //       kernel's architecture-test contract names this prohibition, and this type honours it
    //       structurally by declaring only BigDecimal for the amount. On the wire the value is a
    //       JSON string, applied by com.carddemo.common.money.MoneyModule rather than by an
    //       annotation here, which costs every client an explicit parse and buys exactness at the
    //       one hop a user actually sees.
    @Column(name = "amount", precision = 11, scale = Money.SCALE)
    private BigDecimal tranAmt;

    /**
     * The merchant that originated the transaction.
     *
     * <p>Assumptions: {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at line 11 of the record contract,
     * bytes 144 to 152, mapped to {@code merchant_id BIGINT}. Unlike the category code above this
     * one is a magnitude with no significant leading zero, so a numeric column is right: every
     * value measured across {@code app/data/ASCII/dailytran.txt} is {@code 800000000}, and the
     * column has to hold the full nine-digit width. {@code Long} is the target because nine digits
     * already exceed what a signed thirty-two-bit integer holds at the top of that range.
     * {@code app/cbl/CBTRN02C.cbl} line 431 moves it onto the posted row unchanged.
     */
    @Column(name = "merchant_id")
    private Long merchantId;

    /**
     * The merchant name, up to fifty characters.
     *
     * <p>Assumptions: {@code DALYTRAN-MERCHANT-NAME PIC X(50)} at line 12 of the record contract,
     * bytes 153 to 202, mapped to {@code merchant_name VARCHAR(50)}. Descriptive, so a varying
     * column by the same reasoning as the description above; blank-padded across all 300 measured
     * records with a longest trimmed value of 36 of the declared 50. {@code app/cbl/CBTRN02C.cbl}
     * line 432 moves it onto the posted row unchanged.
     */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * The merchant city, up to fifty characters.
     *
     * <p>Assumptions: {@code DALYTRAN-MERCHANT-CITY PIC X(50)} at line 13 of the record contract,
     * bytes 203 to 252, mapped to {@code merchant_city VARCHAR(50)}. Descriptive for the same
     * reason; the longest trimmed value measured is 19 of the declared 50.
     * {@code app/cbl/CBTRN02C.cbl} line 433 moves it onto the posted row unchanged.
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * The merchant postal code, up to ten characters.
     *
     * <p>Assumptions: {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} at line 14 of the record contract,
     * bytes 253 to 262, mapped to {@code merchant_zip CHAR(10)}. {@code app/cbl/CBTRN02C.cbl} line
     * 434 moves it onto the posted row unchanged.
     */
    // WHY : Assumptions: a fixed-width column even though its two descriptive neighbours are
    //       varying, because a postal code is matched as a code and its leading zeros are
    //       significant: 27 of the 300 values in app/data/ASCII/dailytran.txt begin with a zero.
    //       Keeping it fixed width preserves the blank-padded comparison the reference programs
    //       perform on it.
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * The sixteen-character card number the transaction was made against.
     *
     * <p>Assumptions: {@code DALYTRAN-CARD-NUM PIC X(16)} at line 15 of the record contract, bytes
     * 263 to 278, mapped to {@code card_num CHAR(16)}. This member carries more weight on the feed
     * than on the posted master: {@code app/cbl/CBTRN02C.cbl} line 382 moves it into the
     * cross-reference key to resolve the account, and a value that failed to match is the first of
     * the four documented reject reasons. Its line 435 moves the same value onto the posted row
     * once that lookup succeeds.
     */
    // WHY : Alternatives Considered: a numeric column, which the digits invite. Rejected on a
    //       measurement rather than a preference: 30 of the 300 card numbers in
    //       app/data/ASCII/dailytran.txt begin with a zero, so a numeric column would silently
    //       shorten a tenth of the extract to fifteen digits, and on this table the consequence is
    //       specific -- the shortened value would fail the cross-reference lookup at line 382 of
    //       the posting program and turn a valid row into a rejected one. No example value is
    //       reproduced here, because a primary account number does not belong in source prose even
    //       when it comes from a seed extract, and it is the aggregate count the type decision
    //       rests on.
    // WHY : Assumptions: the report job's sort control at app/jcl/TRANREPT.jcl line 41 types the
    //       field at these same offsets as zoned decimal, 'TRAN-CARD-NUM,263,16,ZD', for the sort
    //       utility's own purposes, and that is not the authority here. The copybook is the
    //       normative declaration under the migration's copybook-is-normative transformation rule
    //       and it declares the field alphanumeric, so the alphanumeric picture governs.
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * The originating timestamp, to microsecond precision.
     *
     * <p>Assumptions: {@code DALYTRAN-ORIG-TS PIC X(26)} at line 16 of the record contract, bytes
     * 279 to 304, mapped to {@code orig_ts TIMESTAMP(6)}. The twenty-six characters and microsecond
     * precision match exactly, so nothing is truncated on the way in and nothing invented on the
     * way out: the reference layout at {@code app/cpy/CSDAT01Y.cpy} lines 42 to 55 is thirteen
     * elementary items summing to 26, with a space separator at line 48 landing on position 11, a
     * period at line 54 landing on position 20 and a six-digit fraction at line 55, giving the form
     * {@code YYYY-MM-DD HH:MM:SS.mmmmmm}. All 300 records of {@code app/data/ASCII/dailytran.txt}
     * carry a populated value here, namely {@code 2022-06-10 19:27:53.000000}, which is what
     * distinguishes this member from the processing timestamp below.
     *
     * <p>Assumptions: this member is what the posting program compares against the account
     * expiration date. Line 414 of {@code app/cbl/CBTRN02C.cbl} takes the leading ten characters of
     * the feed's originating stamp for that comparison, which is the fourth documented reject
     * reason, and its line 436 copies the whole value onto the posted row.
     */
    // WHY : Alternatives Considered: a zone-bearing temporal representation or a UTC timeline
    //       point. Both are rejected because there is no zone in the source to carry, and the
    //       reference timestamp builder proves it rather than merely omitting it: the thirteen
    //       items of the reference layout are four date and time components and six separators with
    //       no offset item at all, and the builder at app/cbl/CBTRN02C.cbl lines 692 to 705 leaves
    //       the offset behind explicitly -- the current-date value it reads at line 693 lands in a
    //       structure whose final item, declared at line 158, holds that offset, and the paragraph
    //       moves seven components across and then writes four literal zeros at line 701 instead of
    //       moving it. Either alternative would therefore have to invent an offset, and no reader
    //       could then tell an invented one from a recorded one.
    // WHY : Assumptions: rendering and parsing the twenty-six character form belongs to
    //       com.carddemo.common.time.TimestampFormatter, whose length constant is 26 and whose
    //       pattern uses the proleptic year letter under a strict resolver. No pattern is declared
    //       here and that class is not imported either, because this type stores an already-parsed
    //       value and never renders one; keeping the single authority for the form in one place is
    //       what stops two patterns drifting apart.
    @Column(name = "orig_ts")
    private LocalDateTime origTs;

    /**
     * The processing timestamp, to microsecond precision, absent until the row is posted.
     *
     * <p>Assumptions: {@code DALYTRAN-PROC-TS PIC X(26)} at line 17 of the record contract, bytes
     * 305 to 330, mapped to {@code proc_ts TIMESTAMP(6)} and nullable. The form, the precision and
     * the rejection of a zoned type are argued on the originating member above and are not
     * restated. What is specific to this member is that it is the one place this type's mapping
     * differs from the posted master's, where the same column is not null.
     *
     * <p>Assumptions: the semantic is that a record awaiting posting has no processing timestamp
     * while a posted transaction always has one, and two independent findings establish it. The
     * first is the data: in {@code app/data/ASCII/dailytran.txt}, 105300 bytes holding exactly 300
     * records of 350 bytes, bytes 305 to 330 hold exactly one distinct value across all 300 records
     * -- twenty-six spaces -- and a blank fixed-width field decodes to no value at all. That
     * blankness is specific to this field rather than a gap in the extract, because the originating
     * stamp in the same 300 records is populated 300 times. The second is the code:
     * {@code app/cbl/CBTRN02C.cbl} mints this stamp only in the posting path, performing its
     * timestamp builder at line 437 and moving the result onto the POSTED row at line 438, inside
     * the posting paragraph its line 424 opens. Nothing anywhere in the reference tree writes a
     * processing timestamp onto the feed itself.
     */
    // WHY : Assumptions: nullable is therefore the only mapping that admits the extract. Declaring
    //       it not null, to match the posted master and make the two types symmetrical, would
    //       reject every one of the 300 seed records outright. Defaulting it to a clock reading
    //       instead would be worse than useless here: the business date in this migration is always
    //       injected as a parameter and never sampled, so a sampled stamp would both fabricate a
    //       posting that has not happened and make a rerun of the same feed produce a different
    //       row.
    @Column(name = "proc_ts")
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
    protected DailyTransaction() {
        // WHY : Assumptions: intentionally empty. The provider assigns every member after
        //       construction, so any initialisation written here would be overwritten on a load and
        //       would silently mask an absent column on an insert.
    }

    /**
     * Creates a fully populated feed record.
     *
     * <p>Assumptions: every member is supplied by the caller and none is derived here, including
     * the processing timestamp, which a record awaiting posting simply does not carry. Passing no
     * value for it is the normal case rather than an incomplete one, and it is what the seed
     * extract yields on all 300 of its records. No value is minted here for it either, because
     * minting one would assert a posting that has not happened.
     *
     * <p>Assumptions: no validation is performed here. The reference validation sequence is the
     * paragraph {@code app/cbl/CBTRN02C.cbl} performs at its line 210, it belongs to the batch
     * context, and it decides between posting a record and rejecting it. Enforcing a subset of it
     * in this constructor would refuse to represent exactly the records that sequence exists to
     * reject, so a row that the baseline would have written to its reject stream could not be
     * loaded at all.
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
     *     becomes this instance's mapped identity
     * @param tranTypeCd the two-character transaction type code, not yet validated against the
     *     reference data
     * @param tranCatCd the four-character transaction category code, zero-filled to its width
     * @param tranSource the ten-character originating source
     * @param tranDesc the transaction description, up to one hundred characters
     * @param tranAmt the transaction amount, exact at two decimal places, positive or negative
     * @param merchantId the nine-digit merchant identifier
     * @param merchantName the merchant name, up to fifty characters
     * @param merchantCity the merchant city, up to fifty characters
     * @param merchantZip the merchant postal code, up to ten characters
     * @param cardNum the sixteen-character card number the transaction was made against, which the
     *     posting path resolves to an account
     * @param origTs the originating timestamp, to microsecond precision and carrying no zone, which
     *     the feed supplies on every record
     * @param procTs the processing timestamp, to microsecond precision and carrying no zone, or
     *     {@code null} on a record that has not been posted
     */
    public DailyTransaction(String tranId, String tranTypeCd, String tranCatCd, String tranSource,
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
     * Returns the sixteen-character transaction identifier that is this type's mapped identity.
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
     * <p>Assumptions: this is the mapped identity and the column is mapped
     * {@code updatable = false}, so assigning it after the row is persisted changes the member
     * without changing the row. The accessor exists for the loader to populate a new instance, not
     * to re-key an existing one.
     *
     * @param tranId the sixteen-character transaction identifier to assign, an opaque fixed-width
     *     token supplied by the feed rather than generated by this type
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
     *     transaction types by the posting job rather than here
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
     *     transaction categories by the posting job rather than here
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
     * rescaling would hide a decoder that had lost a fraction of a cent while reading the feed.
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
     * @param merchantId the nine-digit merchant identifier to assign
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
     * boundary and the posting path that resolves an account from it needs every digit. Masking it
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
     * <p>Assumptions: the caller supplies an already-parsed value, so the twenty-six character form
     * is decoded once at the boundary that read the feed rather than again here. That keeps the
     * single authority for the form in {@code com.carddemo.common.time.TimestampFormatter} and
     * leaves this type holding a value rather than a representation.
     *
     * @param origTs the originating timestamp to assign, to microsecond precision and without a
     *     zone
     */
    public void setOrigTs(LocalDateTime origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the processing timestamp, which is absent until the row is posted.
     *
     * @return the processing timestamp to microsecond precision and carrying no zone, or
     *     {@code null} on a record awaiting posting, which is what the whole seed extract carries
     */
    public LocalDateTime getProcTs() {
        return this.procTs;
    }

    /**
     * Assigns the processing timestamp.
     *
     * <p>Assumptions: the column is nullable, so a caller with no value passes none rather than a
     * substitute. Minting a stamp in this accessor was the alternative and is rejected twice over:
     * it would make the value depend on when the object was built rather than on when the
     * transaction posted, which is the distinction the reference posting path draws at
     * {@code app/cbl/CBTRN02C.cbl} line 438, and on this table it would additionally claim a
     * posting that has not occurred.
     *
     * @param procTs the processing timestamp to assign, to microsecond precision and without a
     *     zone, or {@code null} to leave the record marked as awaiting posting
     */
    public void setProcTs(LocalDateTime procTs) {
        this.procTs = procTs;
    }

    /**
     * Compares this record with another on the transaction identifier alone. Two instances carrying
     * the same identifier denote the same feed record.
     *
     * <p>Alternatives Considered: comparing all thirteen members. Rejected because the identifier
     * is the value the record contract leads with at line 5 and the value this type maps as its
     * identity, so it already denotes the record. Comparing every member as well would make two
     * reads of one row unequal the moment a query populated a different subset of columns, and
     * would additionally break identity across a persistence flush, when the provider writes values
     * into an instance that a collection is already holding.
     *
     * <p>Trade-offs: because the migration puts no unique constraint on this column,
     * identifier-only equality is an assertion this type makes rather than one the schema
     * guarantees. A feed that carried a repeated identifier would therefore yield two rows this
     * method reports as equal. That is accepted as the honest consequence of mapping a keyless
     * sequential feed: the alternative, all-member comparison, would report those two rows as
     * distinct while still leaving the provider unable to tell them apart, so it would move the
     * ambiguity rather than remove it.
     *
     * <p>Assumptions: an instance built by the no-argument constructor and not yet hydrated carries
     * no identifier, so two such instances compare equal because the identifier is the only value
     * compared and there is nothing else to tell them apart. The provider never publishes an
     * instance in that state, so the case is recorded rather than guarded against.
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is a daily transaction of this type carrying an equal
     *     transaction identifier, {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Assumptions: a pattern match rather than a class comparison, because the provider
        //       may hand back an instrumented subclass for a lazy proxy and a strict class
        //       comparison would then report a row as unequal to itself. No subclass of this type
        //       is authored, so widening the test costs nothing.
        if (!(other instanceof DailyTransaction that)) {
            return false;
        }
        return Objects.equals(this.tranId, that.tranId);
    }

    /**
     * Returns a hash consistent with the identifier-only equality above. It is derived from the
     * transaction identifier and from nothing else.
     *
     * <p>Assumptions: the identifier arrives with the record and this table is written only by the
     * load that populates it, so the value stays stable for the life of an instance in practice
     * even though a setter exists for the loader to populate a new one. That stability is what
     * makes the type safe to place in a hash-based collection; re-keying a persisted instance would
     * break it, which is why the identifier column is mapped {@code updatable = false}.
     *
     * @return the hash of the transaction identifier, or the hash of an absent value on an instance
     *     the provider has not yet hydrated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.tranId);
    }

    /**
     * Returns a diagnostic rendering of this record that deliberately names only three of its
     * thirteen members. It is intended for a log line or an assertion message.
     *
     * <p>Trade-offs: the card number declared at line 15 of the record contract and the amount
     * declared at line 10 are omitted outright rather than abbreviated, so this rendering is not
     * round-trippable and a reader cannot identify a record's card or its value from a log line.
     * That cost is accepted because the compensation is absolute: no log line written from this
     * type can carry a primary account number or a monetary figure at all. Abbreviation was the
     * alternative and is rejected because abbreviating a primary account number IS masking, and the
     * parent charter names the mapper package as the sole boundary where masking may appear; a
     * second, slightly different masking rule here would give one value two renderings and make
     * neither authoritative.
     *
     * <p>Assumptions: the three members retained are chosen for what they let a reader do with a
     * line from the posting run. The identifier from line 5 locates the record, the type code from
     * line 6 places it, and the processing timestamp from line 17 says whether it has been posted
     * at all -- which on this table is the one piece of state that changes, and which renders as an
     * absent value for every record the feed supplies.
     *
     * @return a short single-line rendering naming the type, the transaction identifier, the
     *     transaction type code and the processing timestamp, and no other member
     */
    @Override
    public String toString() {
        return "DailyTransaction[tranId=" + this.tranId
                + ", tranTypeCd=" + this.tranTypeCd
                + ", procTs=" + this.procTs + ']';
    }
}
