package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * The per-account pending-authorization summary, one row per account.
 *
 * <p>This is the migrated form of the hierarchical database's ROOT segment {@code PAUTSUM0},
 * declared at 100 bytes in {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} line 28, whose
 * fields are laid out at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} lines 19 to 31.
 * The segment's key is the account identifier, so this table's primary key is the account identifier
 * and nothing else, and {@link PendingAuthDetail} rows hang beneath it.</p>
 *
 * <p>Assumptions: every monetary component is exact fixed point at scale two, never a binary
 * floating-point type. The four amounts are declared {@code PIC S9(09)V99 COMP-3}, so the mapped
 * columns are {@code NUMERIC(11,2)} and the Java type is {@link BigDecimal}. Transformation rule T3
 * binds this entity as it binds every other, and the prohibition is enforced by the architecture test
 * rather than by review.</p>
 *
 * <p>Assumptions: the packed-decimal representation does not survive into this type. The segment
 * holds its account identifier and its four amounts as {@code COMP-3}, and the ETL decodes them once
 * at the boundary; nothing here decodes a nibble, and no column stores one. That is what lets an
 * ordinary SQL predicate read a balance that the baseline could only read through a codec.</p>
 *
 * <p>Trade-offs: the five occurrences of {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES} at line
 * 22 of that copybook are five discrete members here rather than an array. The arity is fixed at five
 * by the copybook, so five members let the schema enforce it, keep each value addressable by an
 * ordinary predicate, and keep the JPA mapping free of a converter. An array member would express the
 * shape more compactly and would accept a sixth element the baseline record cannot hold.</p>
 *
 * <p>Assumptions: this entity declares no version column and therefore no optimistic-lock check,
 * which is a deliberate difference from the account and card entities. Those are edited by a user
 * across a screen turn, which is the situation a before-image comparison exists for; this row is
 * updated only by the authorization consumer, inside the same transaction that reads it, under a
 * pessimistic row lock the consumer takes with a select-for-update. Adding a version column would add
 * a conflict path that the single writer cannot produce.</p>
 *
 * <p>Trade-offs: the table name is UNQUALIFIED and resolves through the connection
 * {@code search_path} that {@code com.carddemo.authorization.config.DataSourceConfig} pins, exactly as
 * this context's repository charter requires. Naming the schema on the annotation instead would compile
 * it into the mapping, and it would also have to be spelled {@code "\"authorization\""} at every
 * occurrence, because {@code authorization} is a reserved word in this database and an unquoted
 * qualification is a syntax error rather than a wrong lookup -- a failure mode
 * {@code data-migration/sql/V0__schemas_and_roles.sql} documents at lines 471 to 493 after hitting it.
 * Resolving through one pinned {@code search_path} keeps that quoting concern in a single place.</p>
 */
@Entity
@Table(name = "pending_auth_summary")
public class PendingAuthSummary {

    /**
     * The account this summary belongs to, and the row's whole key.
     *
     * <p>Assumptions: {@code PA-ACCT-ID PIC S9(11) COMP-3} at line 19 of the copybook, so eleven
     * signed decimal digits. {@link Long} is the target because eleven digits exceed what a
     * thirty-two-bit integer holds; the value is assigned by the caller rather than generated, because
     * an account identifier originates in the account context and is never minted here.</p>
     */
    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /**
     * The customer the account belongs to, {@code PA-CUST-ID PIC 9(09)} at line 20.
     */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * The one-character authorization status of the account, {@code PA-AUTH-STATUS PIC X(01)} at
     * line 21.
     */
    @Column(name = "auth_status", length = 1)
    private String authStatus;

    /**
     * The first of the five two-character account-status slots at line 22.
     */
    @Column(name = "account_status_1", length = 2)
    private String accountStatus1;

    /**
     * The second of the five two-character account-status slots at line 22.
     */
    @Column(name = "account_status_2", length = 2)
    private String accountStatus2;

    /**
     * The third of the five two-character account-status slots at line 22.
     */
    @Column(name = "account_status_3", length = 2)
    private String accountStatus3;

    /**
     * The fourth of the five two-character account-status slots at line 22.
     */
    @Column(name = "account_status_4", length = 2)
    private String accountStatus4;

    /**
     * The fifth of the five two-character account-status slots at line 22.
     */
    @Column(name = "account_status_5", length = 2)
    private String accountStatus5;

    /**
     * The account's credit limit, {@code PA-CREDIT-LIMIT PIC S9(09)V99 COMP-3} at line 23.
     */
    @Column(name = "credit_limit", nullable = false, precision = 11, scale = 2)
    private BigDecimal creditLimit;

    /**
     * The account's cash limit, {@code PA-CASH-LIMIT PIC S9(09)V99 COMP-3} at line 24.
     */
    @Column(name = "cash_limit", nullable = false, precision = 11, scale = 2)
    private BigDecimal cashLimit;

    /**
     * The account's credit balance including authorizations not yet posted,
     * {@code PA-CREDIT-BALANCE PIC S9(09)V99 COMP-3} at line 25.
     */
    @Column(name = "credit_balance", nullable = false, precision = 11, scale = 2)
    private BigDecimal creditBalance;

    /**
     * The account's cash balance, {@code PA-CASH-BALANCE PIC S9(09)V99 COMP-3} at line 26.
     */
    @Column(name = "cash_balance", nullable = false, precision = 11, scale = 2)
    private BigDecimal cashBalance;

    /**
     * How many authorizations have been approved against the account,
     * {@code PA-APPROVED-AUTH-CNT PIC S9(04) COMP} at line 27.
     */
    @Column(name = "approved_auth_count", nullable = false)
    private Short approvedAuthCount;

    /**
     * How many authorizations have been declined against the account,
     * {@code PA-DECLINED-AUTH-CNT PIC S9(04) COMP} at line 28.
     */
    @Column(name = "declined_auth_count", nullable = false)
    private Short declinedAuthCount;

    /**
     * The total approved authorization amount, {@code PA-APPROVED-AUTH-AMT S9(09)V99 COMP-3} at
     * line 29.
     */
    @Column(name = "approved_auth_amount", nullable = false, precision = 11, scale = 2)
    private BigDecimal approvedAuthAmount;

    /**
     * The total declined authorization amount, {@code PA-DECLINED-AUTH-AMT S9(09)V99 COMP-3} at
     * line 30.
     */
    @Column(name = "declined_auth_amount", nullable = false, precision = 11, scale = 2)
    private BigDecimal declinedAuthAmount;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a no-argument constructor, which the provider invokes
     * before writing the mapped columns into the fields above. It is protected rather than public
     * because no caller outside this type's hierarchy has a use for a half-built summary.</p>
     */
    protected PendingAuthSummary() {
        // Assumptions: empty by design. The provider assigns every mapped field directly after
        // construction, so initialising one here would write a value immediately overwritten on load.
    }

    /**
     * Creates a summary for an account that has none yet, with zeroed counters and amounts.
     *
     * <p>Assumptions: a summary is created lazily, on the first authorization for an account, which is
     * the hierarchical database's own behaviour -- a root segment is inserted when a child first needs
     * one. The four limits and balances are supplied rather than zeroed because they are properties of
     * the ACCOUNT and are carried in from the account context, whereas the counters and totals are
     * properties of this context's own history and therefore start at zero.</p>
     *
     * @param accountId the account this summary belongs to; must not be {@code null}
     * @param customerId the customer the account belongs to; must not be {@code null}
     * @param creditLimit the account's credit limit at scale two; must not be {@code null}
     * @param cashLimit the account's cash limit at scale two; must not be {@code null}
     * @param creditBalance the account's credit balance at scale two; must not be {@code null}
     * @param cashBalance the account's cash balance at scale two; must not be {@code null}
     */
    public PendingAuthSummary(Long accountId, Long customerId, BigDecimal creditLimit,
            BigDecimal cashLimit, BigDecimal creditBalance, BigDecimal cashBalance) {
        this.accountId = accountId;
        this.customerId = customerId;
        this.creditLimit = creditLimit;
        this.cashLimit = cashLimit;
        this.creditBalance = creditBalance;
        this.cashBalance = cashBalance;
        this.approvedAuthCount = 0;
        this.declinedAuthCount = 0;
        this.approvedAuthAmount = BigDecimal.ZERO.setScale(2);
        this.declinedAuthAmount = BigDecimal.ZERO.setScale(2);
    }

    /**
     * Returns the account this summary belongs to.
     *
     * @return the account identifier, never {@code null} on a persisted instance
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Returns the customer the account belongs to.
     *
     * @return the customer identifier, never {@code null} on a persisted instance
     */
    public Long getCustomerId() {
        return this.customerId;
    }

    /**
     * Returns the one-character authorization status of the account.
     *
     * @return the authorization status, or {@code null} when the account has none recorded
     */
    public String getAuthStatus() {
        return this.authStatus;
    }

    /**
     * Returns the account's credit limit.
     *
     * @return the credit limit at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getCreditLimit() {
        return this.creditLimit;
    }

    /**
     * Returns the account's cash limit.
     *
     * @return the cash limit at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getCashLimit() {
        return this.cashLimit;
    }

    /**
     * Returns the account's credit balance, including authorizations not yet posted.
     *
     * @return the credit balance at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getCreditBalance() {
        return this.creditBalance;
    }

    /**
     * Returns the account's cash balance.
     *
     * @return the cash balance at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getCashBalance() {
        return this.cashBalance;
    }

    /**
     * Returns how many authorizations have been approved against the account.
     *
     * @return the approved count, never {@code null} on a persisted instance
     */
    public Short getApprovedAuthCount() {
        return this.approvedAuthCount;
    }

    /**
     * Returns how many authorizations have been declined against the account.
     *
     * @return the declined count, never {@code null} on a persisted instance
     */
    public Short getDeclinedAuthCount() {
        return this.declinedAuthCount;
    }

    /**
     * Returns the total approved authorization amount.
     *
     * @return the approved total at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getApprovedAuthAmount() {
        return this.approvedAuthAmount;
    }

    /**
     * Returns the total declined authorization amount.
     *
     * @return the declined total at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getDeclinedAuthAmount() {
        return this.declinedAuthAmount;
    }

    /**
     * Records an approved authorization against this summary.
     *
     * <p>Assumptions: the count, the approved total and the credit balance move TOGETHER, in one
     * method, because they are one accounting fact. Exposing three setters instead would let a caller
     * update the count and forget the balance, and the resulting row would be internally inconsistent
     * with nothing to detect it -- the same class of defect the baseline avoids by updating the segment
     * in one rewrite.</p>
     *
     * @param amount the approved amount at scale two, added to both the approved total and the credit
     *     balance; must not be {@code null}
     */
    public void recordApproved(BigDecimal amount) {
        this.approvedAuthCount = (short) (this.approvedAuthCount + 1);
        this.approvedAuthAmount = this.approvedAuthAmount.add(amount);
        this.creditBalance = this.creditBalance.add(amount);
    }

    /**
     * Records a declined authorization against this summary.
     *
     * <p>Assumptions: a decline moves the count and the declined total and leaves the credit balance
     * alone, because a declined authorization reserves nothing. That asymmetry with
     * {@link #recordApproved(BigDecimal)} is the whole reason the two are separate methods rather than
     * one method taking a flag.</p>
     *
     * @param amount the declined amount at scale two, added to the declined total only; must not be
     *     {@code null}
     */
    public void recordDeclined(BigDecimal amount) {
        this.declinedAuthCount = (short) (this.declinedAuthCount + 1);
        this.declinedAuthAmount = this.declinedAuthAmount.add(amount);
    }
}
