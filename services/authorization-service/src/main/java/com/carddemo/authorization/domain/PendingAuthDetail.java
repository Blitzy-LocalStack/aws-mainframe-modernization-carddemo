package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One pending authorization recorded against an account.
 *
 * <p>This is the migrated form of the hierarchical database's CHILD segment {@code PAUTDTL1}, declared
 * at 200 bytes in {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} line 36, whose fields are
 * laid out at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} lines 19 to 54. Its key is
 * {@link PendingAuthDetailKey}; its parent is {@link PendingAuthSummary}, and the database enforces
 * that parentage with a foreign key so a detail row cannot outlive the summary the segment hierarchy
 * required it to hang beneath.</p>
 *
 * <p>Assumptions: every field of the segment is carried across except the trailing seventeen-byte
 * {@code FILLER} at line 54, which is padding to the fixed segment length and holds no data --
 * transformation rule T1 drops it and records the drop, which this paragraph is.</p>
 *
 * <p>Assumptions: the two amounts are exact fixed point at scale two. The copybook declares them
 * {@code COMP-3}, the ETL decodes the packed nibbles once at the boundary, and nothing downstream sees
 * a packed byte. Rule T3 forbids a binary floating-point type here and the architecture test enforces
 * it.</p>
 *
 * <p>Trade-offs: this type exposes accessors only for the members that application code actually
 * reads -- the card number, the transaction identifier, the two amounts, the response code and the two
 * fraud members. The remaining members are written once at construction from the decoded request and
 * are read only by SQL, through the reporting views and the detail projection. Adding twenty-two
 * further getters would satisfy a reflex rather than a caller, and every one of them would be a member
 * whose Javadoc has to be maintained against a copybook line that nothing reads. When a later
 * boundary needs one of those values it adds the accessor together with the caller that justifies
 * it.</p>
 *
 * <p>Assumptions: this type deliberately declares no {@code toString}. A rendering of it would carry
 * a primary account number, and a diagnostic that is safe to write into a log is the one thing such a
 * rendering must never be; {@link PendingAuthDetailKey} carries the loggable identity instead.</p>
 *
 * <p>Assumptions: the table name is UNQUALIFIED and resolves through the pinned connection
 * {@code search_path}, for the reason recorded on {@link PendingAuthSummary} -- {@code authorization} is
 * a reserved word in this database, so a qualification would have to be quoted at every occurrence.</p>
 */
@Entity
@Table(name = "pending_auth_detail")
public class PendingAuthDetail {

    /**
     * The three-part key: account, authorization date and authorization time.
     */
    @EmbeddedId
    private PendingAuthDetailKey id;

    /**
     * The originating date as the acquirer supplied it,
     * {@code PA-AUTH-ORIG-DATE PIC X(06)} at line 23 of the copybook.
     */
    // WHY : Alternatives Considered: relying on the declared length alone was evaluated and rejected,
    //       because a Java String otherwise selects the JDBC VARCHAR binding and schema validation then
    //       rejects this schema's CHAR columns even though every width agrees. Spelling the physical
    //       type into columnDefinition was rejected too: that would duplicate vendor DDL inside a
    //       mapping which has no authority to create the table. The type code below selects the standard
    //       CHAR binding for reads, writes and validation while leaving the physical definition wholly
    //       with V1__authorization.sql, and it is the same mechanism the batch context's own entities
    //       use for the identical reason.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_orig_date", length = 6)
    private String authOrigDate;

    /**
     * The originating time as the acquirer supplied it,
     * {@code PA-AUTH-ORIG-TIME PIC X(06)} at line 24.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_orig_time", length = 6)
    private String authOrigTime;

    /**
     * The primary account number the authorization was presented against,
     * {@code PA-CARD-NUM PIC X(16)} at line 25.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", nullable = false, length = 16)
    private String cardNum;

    /**
     * The authorization type, {@code PA-AUTH-TYPE PIC X(04)} at line 26.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_type", length = 4)
    private String authType;

    /**
     * The card expiry date as presented, {@code PA-CARD-EXPIRY-DATE PIC X(04)} at line 27.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_expiry_date", length = 4)
    private String cardExpiryDate;

    /**
     * The network message type, {@code PA-MESSAGE-TYPE PIC X(06)} at line 28.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_type", length = 6)
    private String messageType;

    /**
     * The network message source, {@code PA-MESSAGE-SOURCE PIC X(06)} at line 29.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_source", length = 6)
    private String messageSource;

    /**
     * The authorization identification code returned to the acquirer,
     * {@code PA-AUTH-ID-CODE PIC X(06)} at line 30.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_id_code", length = 6)
    private String authIdCode;

    /**
     * The response code returned to the acquirer, {@code PA-AUTH-RESP-CODE PIC X(02)} at line 31.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_resp_code", length = 2)
    private String authRespCode;

    /**
     * The response reason returned to the acquirer, {@code PA-AUTH-RESP-REASON PIC X(04)} at line 32.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_resp_reason", length = 4)
    private String authRespReason;

    /**
     * The processing code, {@code PA-PROCESSING-CODE PIC X(06)} at line 33.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "processing_code", length = 6)
    private String processingCode;

    /**
     * The amount the acquirer requested, {@code PA-TRANSACTION-AMT S9(10)V99 COMP-3} at line 34.
     */
    @Column(name = "transaction_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal transactionAmount;

    /**
     * The amount actually approved, {@code PA-APPROVED-AMT S9(10)V99 COMP-3} at line 35.
     */
    @Column(name = "approved_amt", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    /**
     * The merchant category, {@code PA-MERCHANT-CATAGORY-CODE PIC X(04)} at line 36.
     *
     * <p>Assumptions: the copybook member name misspells "category". The column and this member spell
     * it correctly, which is one of the three documented renamings; the lineage is recorded in
     * {@code docs/architecture/data-model-and-schema-mapping.md} so no reader has to guess whether the
     * two names denote the same field.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_category_code", length = 4)
    private String merchantCategoryCode;

    /**
     * The acquirer country, {@code PA-ACQR-COUNTRY-CODE PIC X(03)} at line 37.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "acqr_country_code", length = 3)
    private String acqrCountryCode;

    /**
     * How the card details entered the terminal, {@code PA-POS-ENTRY-MODE PIC S9(04) COMP} at line 38.
     */
    @Column(name = "pos_entry_mode")
    private Short posEntryMode;

    /**
     * The merchant identifier, {@code PA-MERCHANT-ID PIC X(15)} at line 39.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_id", length = 15)
    private String merchantId;

    /**
     * The merchant name, {@code PA-MERCHANT-NAME PIC X(22)} at line 40.
     */
    @Column(name = "merchant_name", length = 22)
    private String merchantName;

    /**
     * The merchant city, {@code PA-MERCHANT-CITY PIC X(13)} at line 41.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_city", length = 13)
    private String merchantCity;

    /**
     * The merchant state, {@code PA-MERCHANT-STATE PIC X(02)} at line 42.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_state", length = 2)
    private String merchantState;

    /**
     * The merchant postal code, {@code PA-MERCHANT-ZIP PIC X(09)} at line 43.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_zip", length = 9)
    private String merchantZip;

    /**
     * The acquirer's transaction identifier, {@code PA-TRANSACTION-ID PIC X(15)} at line 44.
     *
     * <p>Assumptions: this value is also the deduplication identifier the reply message carries, which
     * is why it is stored rather than discarded after the decision -- it is how a redelivered request is
     * recognised as one already answered.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", nullable = false, length = 15)
    private String transactionId;

    /**
     * Whether the authorization has matched a posted transaction,
     * {@code PA-MATCH-STATUS PIC X(01)} at line 52, whose condition names admit exactly
     * {@code 'P'}, {@code 'D'}, {@code 'E'} and {@code 'M'}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "match_status", nullable = false, length = 1)
    private String matchStatus;

    /**
     * Whether the authorization has been marked fraudulent,
     * {@code PA-AUTH-FRAUD PIC X(01)} at line 53, whose condition names admit {@code 'F'} and
     * {@code 'R'} and whose absent state is unmarked.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_fraud", length = 1)
    private String authFraud;

    /**
     * When the fraud mark was applied, {@code PA-FRAUD-RPT-DATE PIC X(08)} at line 53.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fraud_rpt_date", length = 8)
    private String fraudReportDate;

    /**
     * The state a newly-recorded authorization starts in: pending a match against a posted
     * transaction.
     *
     * <p>Assumptions: {@code 'P'} is one of the four values the copybook's condition names admit at
     * line 52, and it is the one that means "not yet matched". The column defaults to the same literal,
     * so a row inserted by the ETL and a row inserted by the consumer agree.</p>
     */
    public static final String MATCH_STATUS_PENDING = "P";

    /**
     * The fraud indicator meaning the authorization has been reported as fraudulent.
     *
     * <p>Assumptions: {@code 'F'} is one of the two values the copybook's condition names admit at line
     * 53. The check constraint on the column admits the same two and null, so an out-of-domain value
     * cannot be stored even by a caller that bypasses this type.</p>
     */
    public static final String FRAUD_REPORTED = "F";

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a no-argument constructor. It is protected because a
     * detail row with no key and no card number is meaningless to any caller outside this hierarchy.</p>
     */
    protected PendingAuthDetail() {
        // Assumptions: empty by design; the provider assigns every mapped field after construction.
    }

    /**
     * Creates a fully-specified detail row from a decoded authorization request and the decision
     * reached on it.
     *
     * <p>Trade-offs: every value is supplied at construction and there is no setter for any of them
     * except the two fraud members. A pending authorization is an immutable record of what an acquirer
     * presented and what was answered; the only thing that legitimately changes afterwards is whether
     * it was later reported fraudulent, which is why exactly that one transition has a mutator. A
     * builder would read more comfortably at the single call site that uses this constructor and would
     * make a half-populated instance representable, which this shape makes impossible.</p>
     *
     * @param id the three-part key; must not be {@code null}
     * @param authOrigDate the originating date as supplied; may be {@code null}
     * @param authOrigTime the originating time as supplied; may be {@code null}
     * @param cardNum the primary account number presented; must not be {@code null}
     * @param authType the authorization type; may be {@code null}
     * @param cardExpiryDate the card expiry date as presented; may be {@code null}
     * @param messageType the network message type; may be {@code null}
     * @param messageSource the network message source; may be {@code null}
     * @param authIdCode the authorization identification code returned; may be {@code null}
     * @param authRespCode the response code returned; may be {@code null}
     * @param authRespReason the response reason returned; may be {@code null}
     * @param processingCode the processing code; may be {@code null}
     * @param transactionAmount the amount requested, at scale two; must not be {@code null}
     * @param approvedAmount the amount approved, at scale two; must not be {@code null}
     * @param merchantCategoryCode the merchant category; may be {@code null}
     * @param acqrCountryCode the acquirer country; may be {@code null}
     * @param posEntryMode how the card details entered the terminal; may be {@code null}
     * @param merchantId the merchant identifier; may be {@code null}
     * @param merchantName the merchant name; may be {@code null}
     * @param merchantCity the merchant city; may be {@code null}
     * @param merchantState the merchant state; may be {@code null}
     * @param merchantZip the merchant postal code; may be {@code null}
     * @param transactionId the acquirer's transaction identifier; may be {@code null}
     */
    public PendingAuthDetail(PendingAuthDetailKey id, String authOrigDate, String authOrigTime,
            String cardNum, String authType, String cardExpiryDate, String messageType,
            String messageSource, String authIdCode, String authRespCode, String authRespReason,
            String processingCode, BigDecimal transactionAmount, BigDecimal approvedAmount,
            String merchantCategoryCode, String acqrCountryCode, Short posEntryMode,
            String merchantId, String merchantName, String merchantCity, String merchantState,
            String merchantZip, String transactionId) {
        this.id = id;
        this.authOrigDate = authOrigDate;
        this.authOrigTime = authOrigTime;
        this.cardNum = cardNum;
        this.authType = authType;
        this.cardExpiryDate = cardExpiryDate;
        this.messageType = messageType;
        this.messageSource = messageSource;
        this.authIdCode = authIdCode;
        this.authRespCode = authRespCode;
        this.authRespReason = authRespReason;
        this.processingCode = processingCode;
        this.transactionAmount = transactionAmount;
        this.approvedAmount = approvedAmount;
        this.merchantCategoryCode = merchantCategoryCode;
        this.acqrCountryCode = acqrCountryCode;
        this.posEntryMode = posEntryMode;
        this.merchantId = merchantId;
        this.merchantName = merchantName;
        this.merchantCity = merchantCity;
        this.merchantState = merchantState;
        this.merchantZip = merchantZip;
        this.transactionId = transactionId;
        this.matchStatus = MATCH_STATUS_PENDING;
    }

    /**
     * Returns the three-part key.
     *
     * @return the key, never {@code null} on a persisted instance
     */
    public PendingAuthDetailKey getId() {
        return this.id;
    }

    /**
     * Returns the primary account number the authorization was presented against.
     *
     * <p>Assumptions: the value is returned unmasked because the only caller is the reply encoder,
     * which must echo the sixteen digits the acquirer sent. Masking happens at the presentation
     * boundary, in the mapper that builds a response body, not here.</p>
     *
     * @return the sixteen-digit primary account number, never {@code null} on a persisted instance
     */
    public String getCardNum() {
        return this.cardNum;
    }

    /**
     * Returns the acquirer's transaction identifier.
     *
     * @return the transaction identifier, or {@code null} when the request carried none
     */
    public String getTransactionId() {
        return this.transactionId;
    }

    /**
     * Returns the authorization identification code that was returned to the acquirer.
     *
     * @return the identification code, or {@code null} when none was assigned
     */
    public String getAuthIdCode() {
        return this.authIdCode;
    }

    /**
     * Returns the response code that was returned to the acquirer.
     *
     * @return the response code, or {@code null} when none was assigned
     */
    public String getAuthRespCode() {
        return this.authRespCode;
    }

    /**
     * Returns the response reason that was returned to the acquirer.
     *
     * @return the response reason, or {@code null} when none was assigned
     */
    public String getAuthRespReason() {
        return this.authRespReason;
    }

    /**
     * Returns the amount the acquirer requested.
     *
     * @return the requested amount at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getTransactionAmount() {
        return this.transactionAmount;
    }

    /**
     * Returns the amount actually approved.
     *
     * @return the approved amount at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getApprovedAmount() {
        return this.approvedAmount;
    }

    /**
     * Returns whether the authorization has matched a posted transaction.
     *
     * @return one of the four one-character match statuses, never {@code null} on a persisted instance
     */
    public String getMatchStatus() {
        return this.matchStatus;
    }

    /**
     * Returns the fraud indicator.
     *
     * @return the one-character indicator, or {@code null} when the authorization is unmarked
     */
    public String getAuthFraud() {
        return this.authFraud;
    }

    /**
     * Returns when the fraud mark was applied.
     *
     * @return the eight-character report date, or {@code null} when the authorization is unmarked
     */
    public String getFraudReportDate() {
        return this.fraudReportDate;
    }

    /**
     * Marks this authorization as reported fraudulent, as of a stated date.
     *
     * <p>Assumptions: the indicator and the report date move together, in one method, because the
     * baseline writes both in one segment rewrite and a row carrying one without the other is
     * meaningless. Two setters would let a caller mark the fraud and omit the date, and the check
     * constraint could not catch it because each column is individually valid.</p>
     *
     * @param reportDate the eight-character date the report was made; must not be {@code null}
     */
    public void markFraudReported(String reportDate) {
        this.authFraud = FRAUD_REPORTED;
        this.fraudReportDate = reportDate;
    }
}
