package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * <p>Trade-offs: this type declares no optimistic-lock version. The write that reaches it is an upsert
 * keyed on the primary key inside one short transaction, so there is no read-then-write gap for a
 * version to guard, and the reference program's own contention handling is the duplicate-key branch its
 * insert tests rather than a before-image comparison.
 */
@Entity
@Table(name = "auth_fraud")
public class AuthFraud {

    /**
     * The two-part key: the primary account number and the composed authorization timestamp.
     */
    @EmbeddedId
    private AuthFraudKey id;

    /**
     * The four-character authorization type, {@code PA-AUTH-TYPE} at {@code cpy/CIPAUDTY.cpy} L23.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_type", length = 4)
    private String authType;

    /**
     * The four-character card expiry, {@code PA-CARD-EXPIRY-DATE} at {@code cpy/CIPAUDTY.cpy} L24.
     *
     * <p>Assumptions: four characters and not five. The detail screen composes the fifth by overlaying
     * a solidus at {@code cbl/COPAUS1C.cbl} L337, so the separator is a rendering and not stored.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_expiry_date", length = 4)
    private String cardExpiryDate;

    /**
     * The six-character message type, {@code PA-MESSAGE-TYPE} at {@code cpy/CIPAUDTY.cpy} L25.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_type", length = 6)
    private String messageType;

    /**
     * The six-character message source, {@code PA-MESSAGE-SOURCE} at {@code cpy/CIPAUDTY.cpy} L26.
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
     * <p>Assumptions: character and not numeric, because a leading zero in a processing code is data.
     * The migration records this as one of three different targets its zoned numerics take, resolved by
     * the role the field plays rather than by its picture.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "processing_code", length = 6)
    private String processingCode;

    /**
     * The amount the acquirer asked for, {@code PA-TRANSACTION-AMT PIC S9(10)V99 COMP-3}.
     *
     * <p>Assumptions: exact fixed point at scale two, never a binary floating point value. The packed
     * bytes are decoded at the wire edge and never persisted.
     */
    @Column(name = "transaction_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal transactionAmt;

    /**
     * The amount the decision approved, {@code PA-APPROVED-AMT PIC S9(10)V99 COMP-3}.
     *
     * <p>Assumptions: zero on a decline rather than absent, which is the reference rendering -- an unset
     * packed field reads as zero and not as blank.
     */
    @Column(name = "approved_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedAmt;

    /**
     * The four-character merchant category code.
     *
     * <p>Assumptions: the target column name corrects the baseline misspelling
     * {@code PA-MERCHANT-CATAGORY-CODE}. The correction is registered in
     * {@code docs/architecture/data-model-and-schema-mapping.md} so the lineage is never ambiguous.
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
     * <p>Assumptions: a bounded QUANTITY, so a small integer rather than characters -- the one of the
     * three zoned-numeric targets that applies to a value with no leading-zero significance.
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
     * <p>Assumptions: stored as supplied and NOT trimmed. {@code cbl/COPAUS2C.cbl} L130 moves the
     * literal length of the field -- always 22 -- into the Db2 variable-length prefix and L131 then
     * moves the text, so the trailing spaces are part of the stored value and a comparison against it
     * must account for them.
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
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "match_status", length = 1)
    private String matchStatus;

    /**
     * The one-character fraud indicator this row records.
     *
     * <p>Assumptions: the two values are the pair {@code cpy/CIPAUDTY.cpy} L51-L52 names -- reported and
     * removed -- and {@code cbl/COPAUS2C.cbl} L137 moves whichever the caller's action carried straight
     * in. The blank state that forces the detail table's domain open does not arise here, because this
     * row exists only as the result of an action.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_fraud", length = 1)
    private String authFraud;

    /**
     * The server date the fraud state was recorded.
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
    public static AuthFraud from(PendingAuthDetail detail, java.time.LocalDateTime authTs, Long acctId,
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
