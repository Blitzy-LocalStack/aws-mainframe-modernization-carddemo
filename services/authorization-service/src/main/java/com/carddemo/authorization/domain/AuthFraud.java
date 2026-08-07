package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One fraud row: the record the reference system wrote to Db2 when a pending authorization was
 * reported as fraudulent or had that report removed.
 *
 * <p><strong>Purpose.</strong> Carry the twenty-six columns of {@code authorization.auth_fraud} so the
 * fraud-marking write can be one local transaction alongside the detail row it marks.
 *
 * <p>Assumptions: the column count is TWENTY-SIX, and it is worth stating because the reference file
 * invites an off-by-one. {@code ddl/AUTHFRDS.ddl} carries NO licence header -- its line 1 is already
 * {@code CREATE TABLE CARDDEMO.AUTHFRDS} -- whereas every copybook in the same tree opens with an
 * eighteen-line header and reaches its first data line at L19. A reader who carries the copybook habit
 * across to the table definition shifts the whole file and counts a column that is not there. The count
 * is corroborated five independent ways: the definitions occupy {@code ddl/AUTHFRDS.ddl} L2-L27, where
 * L28 is the {@code PRIMARY KEY} clause and not a column; {@code dcl/AUTHFRDS.dcl} L88 states 26 as
 * DCLGEN's own generated count, which is decisive because the utility counted them rather than a person;
 * the {@code DECLARE TABLE} body at {@code dcl/AUTHFRDS.dcl} L25-L50 enumerates the same 26; the COBOL
 * host structure at that file's L55-L86 enumerates 26 fields, once {@code MERCHANT-NAME} at L73 is read
 * as the ONE group its two {@code 49}-level children at L74-L77 belong to; and {@code cbl/COPAUS2C.cbl}
 * L143-L168 spells out a 26-name {@code INSERT} column list, {@code CARD_NUM} through {@code CUST_ID}.
 * The fields below are declared in that same L2-L27 order, so the declaration order of this type is
 * itself part of what it records.
 *
 * <p>Assumptions: {@code acctId} and {@code custId} are the LAST two columns, not the first two. A
 * reader used to seeing identifiers lead a row will be tempted to hoist them, and the reference file
 * puts them last three times over: {@code ddl/AUTHFRDS.ddl} L26-L27, the {@code DECLARE TABLE} at
 * {@code dcl/AUTHFRDS.dcl} L49-L50, and the host structure at that file's L85-L86. The target migration
 * keeps that position, so moving them here would put this type's order at odds with the table it maps.
 *
 * <p>Assumptions: {@code CARDDEMO.AUTHFRDS} declares no {@code FILLER} at all, across
 * {@code ddl/AUTHFRDS.ddl} L2-L27 and {@code dcl/AUTHFRDS.dcl} L25-L50. That absence is the reference
 * system's own precedent for the two padding fields the hierarchical siblings drop --
 * {@code cpy/CIPAUSMY.cpy} L31 is {@code FILLER PIC X(34)} and {@code cpy/CIPAUDTY.cpy} L54 is
 * {@code FILLER PIC X(17)} -- because the relational form of the same data already omits the padding
 * that a fixed-length segment needs to reach its declared length. Each drop is registered per record in
 * {@code docs/architecture/data-model-and-schema-mapping.md}. A {@code FILLER} carrying a {@code VALUE}
 * clause would be content rather than padding and would not be droppable; neither of these does.
 *
 * <p>Assumptions: the primary key and the descending index over the same two columns are TWO objects,
 * and neither is declared here -- {@code V1__authorization.sql} owns both. This matters because a reader
 * who sees {@code pk_auth_fraud} over {@code (card_num, auth_ts)} can conclude that
 * {@code idx_auth_fraud_card_recent} over the same pair is a duplicate and delete it. In PostgreSQL it
 * is not: the constraint's own btree ascends in both columns, so it does not carry the descending access
 * path {@code ddl/XAUTHFRD.ddl} L3 declares as {@code (CARD_NUM ASC, AUTH_TS DESC)}. The root cause of
 * that descending column is visible at {@code cbl/COPAUA0C.cbl} L874-L875, which stores a nines
 * complement of the date and time: the hierarchical side reached newest-authorization-first by
 * complementing the KEY, because an IMS sequence field ascends only, and the relational side re-expressed
 * the same intention as an index instead. Both encode one behaviour -- for a given card the most recent
 * authorization is read first -- and the index is where it survives.
 *
 * <p>Refactoring Rationale: this type declares no value-domain constraint and no {@code CHECK}, and the
 * shape of the reference definition is why. {@code ddl/AUTHFRDS.ddl} L1-L28 and the {@code DECLARE TABLE}
 * at {@code dcl/AUTHFRDS.dcl} L24-L51 contain no {@code CHECK} clause and no {@code DEFAULT} clause
 * anywhere, and mark only {@code CARD_NUM} and {@code AUTH_TS} as {@code NOT NULL}; the two value domains
 * live instead as {@code 88}-level condition names at {@code cpy/CIPAUDTY.cpy} L46-L49 and L51-L52, which
 * are program-level and reach no database. The consequence for the target is that those domains had to be
 * placed deliberately rather than inherited: {@code V1__authorization.sql} asserts the fraud domain on
 * {@code pending_auth_detail}, at its L702-L703, and asserts neither on this table, because the single
 * path that writes here writes only the two marked values.
 *
 * <p>Assumptions: the fraud domain asserted on the detail table admits a single SPACE as well as the two
 * marked values, and that tolerance is a fact about the reference system rather than a defensive
 * widening. {@code cbl/COPAUA0C.cbl} L908-L909 moves {@code SPACE} into both fraud fields on every insert
 * into the pending segment, and those two lines together with {@code cbl/COPAUS2C.cbl} L101 are the only
 * writes either field receives, so an unmarked authorization holds a blank and not a null.
 * {@code cbl/COPAUS1C.cbl} L344-L350 corroborates it from the read side by rendering a bare {@code '-'}
 * at L349 when neither fraud condition holds. A row in THIS table exists only as the result of a marking
 * action, so the blank state does not arise here -- which is why the tolerance belongs to the table where
 * it does.
 *
 * <p>Assumptions: the match-status domain shared with the detail table has FOUR values even though the
 * producer emits two. {@code cpy/CIPAUDTY.cpy} L46-L49 names pending, declined, expired and matched,
 * while {@code cbl/COPAUA0C.cbl} L902-L906 sets only pending or declined; the remaining two are reached
 * by other programs of the same context. Narrowing the domain to what one writer produces would reject
 * states the reference system can hold.
 *
 * <p>Refactoring Rationale: this table is the SECOND resource manager of the baseline's fraud path, and
 * mapping it here is what eliminates that path's distributed transaction rather than emulating it.
 * {@code csd/CRDDEMO2.csd} L69-L74 defines the Db2 entry with {@code DROLLBACK(YES)} and L75-L79
 * attaches Db2 to one transaction only, while the IMS side is replaced at {@code cbl/COPAUS1C.cbl}
 * L525-L528; the single commit point for both is the lone syncpoint in the caller at L557-L559 with the
 * rollback at L565-L568. Both rows now live in one PostgreSQL schema, so the two writes commit together
 * under one local transaction and there is no second manager left to coordinate. This is documented
 * divergence D-6 in {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Alternatives Considered: reproducing the two-manager shape as a saga with a compensating reversal.
 * Rejected because a saga commits its steps separately, which would make a detail row marked fraudulent
 * with no fraud row beside it an observable intermediate state -- a state the reference system cannot
 * produce, since its rollback backs out both writes together, and one a caller could read through this
 * context's own detail operation.
 *
 * <p>Assumptions: most of this row is a COPY of the detail row's columns at the moment of marking, and
 * that duplication is the baseline's own rather than a normalisation failure. {@code cbl/COPAUS2C.cbl}
 * L113-L139 moves twenty-four values out of the segment it was handed and into the Db2 row, so the
 * fraud row is a point-in-time snapshot: a later change to the authorization does not change what the
 * fraud report recorded. Replacing the copy with a foreign key to the detail row would lose that
 * property, and would also fail on the two columns the segments do not carry at all.
 *
 * <p>Assumptions: {@code acctId} and {@code custId} are PARAMETERS rather than values derived from the
 * snapshotted segment, because neither IMS segment carries them -- {@code cbl/COPAUS2C.cbl} L138-L139
 * moves two working-storage values straight into the columns. They do not, however, come from the HTTP
 * caller: the marking flow takes the account from the row key it recovers by redeeming the sealed
 * selector and reads the customer from the authorization's parent summary. Accepting either from the
 * request body would let a caller file a fraud report against an account it does not name.
 *
 * <p>Assumptions: {@code fraudRptDate} is a real {@code LocalDate} here while the same-named field on
 * {@link PendingAuthDetail} is eight characters, and the two are populated from DIFFERENT sources. Both
 * of this row's write paths take the database's own current date -- the insert supplies it positionally
 * at {@code cbl/COPAUS2C.cbl} L194 and the update sets it at L224-L225 -- whereas the {@code MM/DD/YY}
 * string built at L101 populates the segment field instead. One column is parsed from characters and the
 * other is a server date; conflating them would change one of the two.
 *
 * <p>Alternatives Considered: expressing the two-part identity as a static member type nested inside this
 * class, rather than as the separate {@link AuthFraudKey} it uses. The nested form keeps the identity
 * beside the only entity that has one and adds no file to the package. It is not the form in force
 * because the identity is named as a top-level type by callers this entity does not own: the repository
 * declares itself over the pair, so the key appears in its own type parameters, and the marking service
 * constructs one to probe for an existing row before deciding between an insert and an update. A nested
 * type could still be named by those callers, but only through this class, which would make every such
 * reference read as though the key were an implementation detail of the row rather than the address of
 * one. The separate embeddable also matches the sibling detail row and its own key, so one reading serves
 * both. The count of members is unchanged either way -- the two key columns are two of the twenty-six.
 *
 * <p>Alternatives Considered: generating the accessors below from field declarations with an annotation
 * processor, which would remove roughly one line in three from this file. Rejected because a generated
 * accessor cannot carry a docstring, and Rule 1 requires one on every method with its purpose and its
 * return value; the generated members would be exactly the ones no reader could consult. Writing them out
 * also leaves room for the return description to say something the field declaration does not, such as
 * which values are absent on a row this type's own factory did not build.
 *
 * <p>Trade-offs: sixteen of the twenty-six columns are mapped but have no accessor, so this type stores
 * more than it publishes. The compromise is deliberate: the columns exist because the table has them and
 * the snapshot has to be written whole, whereas an accessor exists only where a caller reads one, and the
 * ones present are the identity, the fraud state and date, the two amounts, the acquirer transaction
 * identifier, the merchant name and the two identifiers the marking flow supplies. Publishing all
 * twenty-six would add surface no caller exercises, and each addition would then have to be kept
 * documented for its own sake. The cost is that a future reader wanting, say, the merchant city has to add
 * the accessor and say why, which is the point at which that need gets recorded rather than assumed.
 *
 * <p>Alternatives Considered: an optimistic-lock version column on this row, of the kind the migration
 * does place on the account, customer and card rows in other services. Declined on two grounds. Those
 * rows carry one because the reference programs guard them with an explicit before-image comparison
 * across a pseudo-conversational gap, and no such comparison exists anywhere in this context's programs.
 * The write that reaches this table is instead an upsert keyed on the primary key inside one short
 * transaction: {@code cbl/COPAUS2C.cbl} attempts the insert, tests {@code SQLCODE} at L199 and, on the
 * duplicate-key condition at L203, performs the update at L204 whose {@code WHERE} clause at L226-L228
 * matches the key and nothing else. There is no read-then-write gap for a version to guard, and
 * {@code V1__authorization.sql} declares no version column on this table, so adding one here would put
 * the mapping at odds with the schema.
 */
@Entity
@Table(name = "auth_fraud")
public class AuthFraud {

    /**
     * The two-part key: the primary account number and the composed authorization timestamp.
     *
     * <p>Assumptions: the timestamp half is stored at MICROSECOND precision, and the precision is
     * load-bearing because that column is half of the primary key. Two reference facts fix it at six
     * fractional digits: {@code dcl/AUTHFRDS.dcl} L57 declares the host field {@code PIC X(26)}, which is
     * exactly the width of {@code 'YYYY-MM-DD HH:MM:SS.mmmmmm'}, and the conversion mask at
     * {@code cbl/COPAUS2C.cbl} L171-L172 reads {@code 'YY-MM-DD HH24.MI.SSNNNNNN'}, whose trailing run of
     * six {@code N} positions is the fractional field. {@code V1__authorization.sql} therefore declares
     * {@code auth_ts TIMESTAMP(6)}. Declaring a shorter fraction would silently merge two authorizations
     * that differ only below the truncation point into one key -- a collision that surfaces only once two
     * such rows actually occur, which is far from the change that caused it.
     */
    @EmbeddedId
    private AuthFraudKey id;

    /**
     * The four-character authorization type, {@code PA-AUTH-TYPE} at {@code cpy/CIPAUDTY.cpy} L25.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_type", length = 4)
    private String authType;

    /**
     * The four-character card expiry, {@code PA-CARD-EXPIRY-DATE} at {@code cpy/CIPAUDTY.cpy} L26.
     *
     * <p>Assumptions: this is a four-character CODE and not a calendar date, so it is stored as
     * characters. {@code ddl/AUTHFRDS.ddl} L5 declares {@code CARD_EXPIRY_DATE CHAR(4)}, and four
     * characters cannot carry a day, so parsing the value into a date type would have to invent one. The
     * detail screen composes a five-character rendering by overlaying a solidus at
     * {@code cbl/COPAUS1C.cbl} L337, which confirms from the other direction that the separator is
     * presentation and is not part of the stored value.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_expiry_date", length = 4)
    private String cardExpiryDate;

    /**
     * The six-character message type, {@code PA-MESSAGE-TYPE} at {@code cpy/CIPAUDTY.cpy} L27.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_type", length = 6)
    private String messageType;

    /**
     * The six-character message source, {@code PA-MESSAGE-SOURCE} at {@code cpy/CIPAUDTY.cpy} L28.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_source", length = 6)
    private String messageSource;

    /**
     * The six-character authorization identification code, {@code PA-AUTH-ID-CODE}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_id_code", length = 6)
    private String authIdCode;

    /**
     * The two-character authorization response code, {@code PA-AUTH-RESP-CODE}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_resp_code", length = 2)
    private String authRespCode;

    /**
     * The four-character authorization response reason, {@code PA-AUTH-RESP-REASON}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_resp_reason", length = 4)
    private String authRespReason;

    /**
     * The six-character processing code, {@code PA-PROCESSING-CODE}.
     *
     * <p>Assumptions: characters and not an integer, even though the field's picture upstream is numeric.
     * {@code cpy/CIPAUDTY.cpy} L33 declares {@code PIC 9(06)} while {@code ddl/AUTHFRDS.ddl} L11 declares
     * {@code PROCESSING_CODE CHAR(6)}, and the relational form is the one this table maps. The reason the
     * two disagree is that a leading zero in a processing code is part of the code: routing it through an
     * integer would turn a stored {@code 001000} into {@code 1000} and lose the distinction the six
     * positions exist to carry.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "processing_code", length = 6)
    private String processingCode;

    /**
     * The amount the acquirer asked for, {@code PA-TRANSACTION-AMT PIC S9(10)V99 COMP-3}.
     *
     * <p>Assumptions: exact fixed point at scale two, and never an approximate binary numeric type.
     * {@code ddl/AUTHFRDS.ddl} L12 declares {@code DECIMAL(12,2)}, which the target carries as
     * {@code NUMERIC(12,2)}; arithmetic on the value belongs to
     * {@code com.carddemo.common.money.Money}, which rounds half up at scale two, and the value reaches a
     * caller as a JSON string through {@code com.carddemo.common.money.MoneyModule} so that no client
     * parses a cent into an approximate representation of its own. An approximate type here would return
     * an amount that reads correctly and is wrong in its last place, and the discrepancy would then
     * surface at the far end of the pipeline rather than at the conversion that caused it. The prohibition
     * covers fields, parameters and return types alike and is asserted by {@code LayeringRulesTest}, so it
     * is a failing test rather than a review note.
     *
     * <p>Assumptions: no packed byte reaches this column. The host field is {@code USAGE COMP-3} at
     * {@code dcl/AUTHFRDS.dcl} L66, as are the two identifiers at that file's L85-L86, and every one of
     * them is decoded once at the mapper and ETL edge. Storing the packed form would put the sign nibble
     * inside the database, where no SQL predicate can read it.
     */
    @Column(name = "transaction_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal transactionAmt;

    /**
     * The amount the decision approved, {@code PA-APPROVED-AMT PIC S9(10)V99 COMP-3}.
     *
     * <p>Assumptions: zero on a decline rather than absent, which is the reference representation -- an
     * unset packed field at {@code dcl/AUTHFRDS.dcl} L67 reads as zero and not as blank, so the column is
     * declared {@code NOT NULL} in {@code V1__authorization.sql} and this mapping agrees with it.
     */
    @Column(name = "approved_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedAmt;

    /**
     * The four-character merchant category code.
     *
     * <p>Refactoring Rationale: the reference system spells this concept {@code CATAGORY} in four places
     * -- {@code cpy/CIPAUDTY.cpy} L36, {@code cpy/CCPAURQY.cpy} L28, {@code ddl/AUTHFRDS.ddl} L14 and
     * {@code dcl/AUTHFRDS.dcl} L37 -- and the target names the column {@code merchant_category_code}
     * instead. Because one of those four places is a persisted column name, the consequence is a
     * deliberate divergence at the schema boundary and not a rename confined to Java: a query written
     * against the reference table does not run against this one. The reference files are never modified,
     * the divergence is registered in
     * {@code docs/architecture/data-model-and-schema-mapping.md} so the lineage stays traceable, and the
     * WIRE deliberately keeps the reference spelling -- the request payload at {@code cpy/CCPAURQY.cpy}
     * L28 is an external contract, so only internal and persisted names change.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_category_code", length = 4)
    private String merchantCategoryCode;

    /**
     * The three-character acquirer country code, {@code PA-ACQR-COUNTRY-CODE}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acqr_country_code", length = 3)
    private String acqrCountryCode;

    /**
     * The point-of-sale entry mode, {@code PA-POS-ENTRY-MODE}.
     *
     * <p>Assumptions: a small integer and not characters, which is the opposite resolution from the
     * processing code above and rests on the same authority. {@code ddl/AUTHFRDS.ddl} L16 declares
     * {@code POS_ENTRY_MODE SMALLINT} and {@code dcl/AUTHFRDS.dcl} L71 declares the host field
     * {@code PIC S9(4) USAGE COMP}, a binary halfword; the hierarchical segment pictures the same concept
     * as {@code PIC 9(02)} at {@code cpy/CIPAUDTY.cpy} L38, and where the two disagree the relational form
     * is the authority for this table's column type. A leading zero in an entry mode is not a distinct
     * value the way it is in a processing code, so nothing is lost by holding it as a number.
     */
    @Column(name = "pos_entry_mode")
    private Short posEntryMode;

    /**
     * The fifteen-character merchant identifier, {@code PA-MERCHANT-ID}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", length = 15)
    private String merchantId;

    /**
     * The merchant name, {@code PA-MERCHANT-NAME PIC X(22)}.
     *
     * <p>Assumptions: this is the ONLY variable-length character column in the table, and it is stored as
     * supplied and NEVER trimmed. {@code ddl/AUTHFRDS.ddl} L18 declares {@code VARCHAR(22)} while every
     * other character column on L2-L27 is a fixed-width {@code CHAR}, and the distinction decides what
     * trailing blanks mean: in a {@code CHAR} column they are padding the column adds to reach its width,
     * whereas here the length travels with the value. {@code dcl/AUTHFRDS.dcl} L73-L77 shows the length
     * being carried explicitly, as a {@code 49}-level halfword prefix ahead of 22 text bytes, and
     * {@code cbl/COPAUS2C.cbl} L130 then moves {@code LENGTH OF} the source field -- always 22, whatever
     * the name -- into that prefix before L131 moves the text. The stored length is therefore 22 by
     * construction, so trimming would discard characters the reference system counted as present, and a
     * comparison against the value has to account for them. It is also the one column here that carries no
     * fixed-character JDBC type, precisely so the provider does not treat it as blank-padded.
     */
    @Column(name = "merchant_name", length = 22)
    private String merchantName;

    /**
     * The thirteen-character merchant city, {@code PA-MERCHANT-CITY}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_city", length = 13)
    private String merchantCity;

    /**
     * The two-character merchant state, {@code PA-MERCHANT-STATE}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_state", length = 2)
    private String merchantState;

    /**
     * The nine-character merchant postal code, {@code PA-MERCHANT-ZIP}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_zip", length = 9)
    private String merchantZip;

    /**
     * The fifteen-character acquirer transaction identifier, {@code PA-TRANSACTION-ID}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", length = 15)
    private String transactionId;

    /**
     * The one-character match status carried over from the detail row at the moment of marking.
     *
     * <p>Assumptions: one of the four values {@code cpy/CIPAUDTY.cpy} L46-L49 names, snapshotted rather
     * than tracked. Because the row is a point-in-time copy, this holds the status the authorization had
     * when the fraud report was filed, which a later match can change on the detail row without changing
     * here.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "match_status", length = 1)
    private String matchStatus;

    /**
     * The one-character fraud indicator this row records.
     *
     * <p>Assumptions: one of exactly two values, from the closed {@code 88}-level pair
     * {@code cpy/CIPAUDTY.cpy} L51-L52 names as reported and removed and
     * {@code cbl/COPAUS2C.cbl} L80-L82 restates in its own working storage, whose selected value L137
     * moves into the row. The blank third state the detail table's domain has to admit does not arise
     * here, because a row in this table exists only as the result of a marking action.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_fraud", length = 1)
    private String authFraud;

    /**
     * The server date the fraud state was recorded.
     *
     * <p>Assumptions: a real nullable {@code DATE} here, unlike the same-named field on the detail
     * segment, and the two are populated from different sources. {@code ddl/AUTHFRDS.ddl} L25 declares
     * {@code DATE} and {@code dcl/AUTHFRDS.dcl} L84 declares the host field {@code PIC X(10)}, the width
     * of an ISO {@code 'YYYY-MM-DD'}; both of this table's write paths take the database's own current
     * date, supplied positionally at {@code cbl/COPAUS2C.cbl} L194 and set again at its L224-L225. The
     * segment field is instead {@code PIC X(08)} at {@code cpy/CIPAUDTY.cpy} L53 and receives the string
     * built at {@code cbl/COPAUS2C.cbl} L101. Conflating a parsed character field with a server date would
     * change one of the two.
     *
     * <p>Assumptions: where the segment holds the blank that {@code cbl/COPAUA0C.cbl} L908-L909 writes,
     * the value arrives here as {@code null}, and that conversion happens at the mapper edge only -- the
     * single layer permitted to know about copybook representation. Doing it here would put a blank-to-null
     * rule inside the persistence mapping, where every other reader of this field would then have to know
     * it too.
     */
    @Column(name = "fraud_rpt_date")
    private LocalDate fraudRptDate;

    /**
     * The account the fraud row is filed against, taken from the redeemed row key.
     *
     * <p>Assumptions: the caller supplies no account. The value comes from the key the marking flow
     * recovers by redeeming the sealed selector it was handed, so it is a server-minted value that
     * describes the row actually being marked rather than a claim the request made about it.</p>
     */
    @Column(name = "acct_id")
    private Long acctId;

    /**
     * The customer the fraud row is filed against, read from the authorization's parent summary.
     *
     * <p>Assumptions: the caller supplies no customer either. {@code PA-CUSTOMER-ID} sits on the summary
     * segment rather than on the detail segment this row snapshots, so the marking flow reads it from the
     * parent inside the same transaction and refuses when the account has no parent to read.</p>
     *
     * <p>Assumptions: this is the twenty-sixth and LAST column, and the account identifier immediately
     * above it is the twenty-fifth. Both are held as {@code BIGINT}: {@code dcl/AUTHFRDS.dcl} L49-L50
     * declare them {@code DECIMAL(11, 0)} and {@code DECIMAL(9, 0)}, so eleven and nine digits at scale
     * zero, which is an identifier rather than a quantity and needs no fractional part.</p>
     */
    @Column(name = "cust_id")
    private Long custId;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires a no-argument constructor on an entity. It
     * is protected because a fraud row with no key and no amounts is meaningless to any caller outside
     * this hierarchy.
     */
    protected AuthFraud() {
        // Assumptions: empty by design; the provider assigns every mapped field after construction.
    }

    /**
     * Assembles a fraud row from the authorization it marks, the identifiers no segment carries and the
     * state.
     *
     * <p>Refactoring Rationale: this is a factory rather than a twenty-six argument constructor, and the
     * reason is that twenty-four of the twenty-six values have exactly ONE legitimate source -- the
     * detail row being marked. {@code cbl/COPAUS2C.cbl} L113-L139 moves them out of the segment it was
     * handed, so a constructor taking them individually would let a caller supply a value that
     * disagreed with the row it claimed to describe, which is the one error this shape makes
     * unrepresentable. Only the three values the segment does not carry -- the account, the customer and
     * the state -- and the two derived values are parameters.
     *
     * <p>Assumptions: the composed timestamp and the server date are PARAMETERS rather than read from a
     * clock here, so the marking flow controls both stored values and the same call is reproducible in a
     * test. That mirrors the reference split: the timestamp is composed by the caller from the
     * authorization's own date and time, while the date is the database's current date.
     *
     * @param detail the authorization being marked; must not be {@code null}
     * @param authTs the composed authorization timestamp forming the second half of the key; must not be
     *     {@code null}
     * @param acctId the account identifier carried by the redeemed row key; must not be {@code null}
     * @param custId the customer identifier read from the authorization's parent summary; must not be
     *     {@code null}
     * @param fraudState the one-character indicator to record, either {@link PendingAuthDetail#FRAUD_REPORTED}
     *     or {@link PendingAuthDetail#FRAUD_REMOVED}; must not be {@code null}
     * @param reportDate the server date to record; must not be {@code null}
     * @return a fully populated fraud row, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static AuthFraud from(PendingAuthDetail detail, LocalDateTime authTs, Long acctId,
            Long custId, String fraudState, LocalDate reportDate) {
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(acctId, "acctId must not be null");
        Objects.requireNonNull(custId, "custId must not be null");

        AuthFraud row = new AuthFraud();
        row.id = new AuthFraudKey(
                Objects.requireNonNull(detail.getCardNum(), "detail cardNum must not be null"),
                Objects.requireNonNull(authTs, "authTs must not be null"));
        row.authType = detail.getAuthType();
        row.cardExpiryDate = detail.getCardExpiryDate();
        row.messageType = detail.getMessageType();
        row.messageSource = detail.getMessageSource();
        row.authIdCode = detail.getAuthIdCode();
        row.authRespCode = detail.getAuthRespCode();
        row.authRespReason = detail.getAuthRespReason();
        row.processingCode = detail.getProcessingCode();
        row.transactionAmt = detail.getTransactionAmount();
        row.approvedAmt = detail.getApprovedAmount();
        row.merchantCategoryCode = detail.getMerchantCategoryCode();
        row.acqrCountryCode = detail.getAcqrCountryCode();
        row.posEntryMode = detail.getPosEntryMode();
        row.merchantId = detail.getMerchantId();
        row.merchantName = detail.getMerchantName();
        row.merchantCity = detail.getMerchantCity();
        row.merchantState = detail.getMerchantState();
        row.merchantZip = detail.getMerchantZip();
        row.transactionId = detail.getTransactionId();
        row.matchStatus = detail.getMatchStatus();
        row.acctId = acctId;
        row.custId = custId;
        row.applyState(fraudState, reportDate);
        return row;
    }

    /**
     * Replaces the fraud state and the report date on an existing row.
     *
     * <p>Assumptions: these are the only two columns the reference {@code UPDATE} touches --
     * {@code cbl/COPAUS2C.cbl} L222-L225 sets the indicator and the current date and nothing else -- so
     * the snapshot the row took when it was inserted is deliberately left as it was. A row updated here
     * therefore still describes the authorization as it stood at the first report, which is the property
     * that makes the snapshot worth taking.
     *
     * @param fraudState the one-character indicator to record; must not be {@code null}
     * @param reportDate the server date to record; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void applyState(String fraudState, LocalDate reportDate) {
        this.authFraud = Objects.requireNonNull(fraudState, "fraudState must not be null");
        this.fraudRptDate = Objects.requireNonNull(reportDate, "reportDate must not be null");
    }

    /**
     * Returns the two-part key of this row.
     *
     * @return the key, never {@code null} on a populated row
     */
    public AuthFraudKey getId() {
        return this.id;
    }

    /**
     * Returns the fraud indicator this row records.
     *
     * @return the one-character indicator, never {@code null} on a populated row
     */
    public String getAuthFraud() {
        return this.authFraud;
    }

    /**
     * Returns the server date the fraud state was recorded.
     *
     * @return the date, never {@code null} on a populated row
     */
    public LocalDate getFraudRptDate() {
        return this.fraudRptDate;
    }

    /**
     * Returns the account identifier the redeemed row key carried.
     *
     * @return the account identifier, never {@code null} on a row this type's factory built
     */
    public Long getAcctId() {
        return this.acctId;
    }

    /**
     * Returns the customer identifier read from the authorization's parent summary.
     *
     * @return the customer identifier, never {@code null} on a row this type's factory built
     */
    public Long getCustId() {
        return this.custId;
    }

    /**
     * Returns the acquirer transaction identifier snapshotted from the authorization.
     *
     * @return the fifteen-character identifier, or {@code null} when the authorization carried none
     */
    public String getTransactionId() {
        return this.transactionId;
    }

    /**
     * Returns the amount the acquirer asked for, snapshotted from the authorization.
     *
     * @return the exact fixed-point amount, never {@code null} on a populated row
     */
    public BigDecimal getTransactionAmt() {
        return this.transactionAmt;
    }

    /**
     * Returns the amount the decision approved, snapshotted from the authorization.
     *
     * @return the exact fixed-point amount, never {@code null} on a populated row
     */
    public BigDecimal getApprovedAmt() {
        return this.approvedAmt;
    }

    /**
     * Returns the merchant name snapshotted from the authorization, trailing spaces intact.
     *
     * @return the twenty-two characters as stored, or {@code null} when the authorization carried none
     */
    public String getMerchantName() {
        return this.merchantName;
    }

    /**
     * Renders this row for a diagnostic without disclosing the primary account number.
     *
     * <p>Assumptions: the key's own rendering already masks the card number, so delegating to it is
     * what keeps one masking decision rather than two that could diverge.
     *
     * @return a single-line rendering naming this type, the masked key, the indicator and the date
     */
    @Override
    public String toString() {
        return "AuthFraud[" + this.id + ", authFraud=" + this.authFraud
                + ", fraudRptDate=" + this.fraudRptDate + ']';
    }
}
