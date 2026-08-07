package com.carddemo.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The account master row this context owns.
 *
 * <h2>Reference contract</h2>
 * <p>This is {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy} lines 4 through 17, the 300-byte
 * {@code ACCTDATA} record. Every column below is one declared field of that record; the trailing filler
 * that pads the record to 300 bytes is dropped, because it is padding to a fixed physical length that a
 * row does not have.</p>
 *
 * <p>Assumptions: every money field is {@code NUMERIC(12,2)} in the schema and {@link BigDecimal} here,
 * never a binary floating-point type. The reference declares them as zoned decimal with a sign overpunch
 * -- {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code CVACT01Y.cpy} L7 -- which is an EXACT decimal
 * representation, and a binary type cannot hold every two-decimal value exactly. The prohibition is not
 * left to discipline: the shared architecture rules fail the build on a {@code double} in a money
 * position.</p>
 *
 * <p>Assumptions: the misspelled reference field {@code ACCT-EXPIRAION-DATE} becomes
 * {@code expirationDate} over the {@code expiration_date} column. That correction is registered in
 * {@code docs/architecture/data-model-and-schema-mapping.md} rather than made silently, and it is one of
 * exactly three the migration makes.</p>
 *
 * <p>Assumptions: the three date fields are {@link LocalDate} over {@code DATE} columns although the
 * reference holds them as {@code PIC X(10)} character strings. The stored form is already
 * year-month-day ordered, so a lexical comparison and a date comparison agree -- which is why the
 * reference can get away with characters -- and a real date type additionally refuses the impossible
 * values a character field admits.</p>
 *
 * <p>Assumptions: the {@code @Version} column is the migrated form of a check the reference already
 * performs. {@code app/cbl/COACTUPC.cbl} snapshots the whole pre-edit record into {@code ACUP-OLD-DETAILS}
 * at L669 onward and compares it before rewriting, because the CICS read-for-update lock is not held
 * across the client gap. This column expresses that same optimistic check natively; nothing about the
 * behaviour is new, only its expression.</p>
 *
 * <p>Alternatives Considered: mapping only the three amounts the authorization decision reads, which is
 * all the account-context contract publishes today. Rejected because this entity is the context's account
 * master and not a projection of one caller's needs -- a partial entity would have to be widened by
 * whichever screen was implemented next, and in the meantime a write through it would silently drop every
 * unmapped column. The contract narrows what is PUBLISHED; the entity carries the row.</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    /**
     * The account identifier, {@code ACCT-ID PIC 9(11)}.
     */
    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /**
     * Whether the account is active, {@code ACCT-ACTIVE-STATUS PIC X(01)}.
     *
     * <p>Assumptions: this is carried as a one-character string rather than a boolean, because the
     * reference domain is a character and the schema constrains it as one. A boolean would have to choose
     * a mapping for any third value the data holds, and the schema's own check constraint is what decides
     * that question.</p>
     */
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;

    /**
     * The posted balance, {@code ACCT-CURR-BAL PIC S9(10)V99}.
     */
    @Column(name = "curr_bal", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentBalance;

    /**
     * The credit limit an authorization is weighed against, {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}.
     */
    @Column(name = "credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /**
     * The cash credit limit, {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}.
     */
    @Column(name = "cash_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    /**
     * When the account was opened, {@code ACCT-OPEN-DATE PIC X(10)}.
     */
    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;

    /**
     * When the account expires, the corrected spelling of {@code ACCT-EXPIRAION-DATE PIC X(10)}.
     */
    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    /**
     * When the account was last reissued, {@code ACCT-REISSUE-DATE PIC X(10)}.
     */
    @Column(name = "reissue_date", nullable = false)
    private LocalDate reissueDate;

    /**
     * Credits posted in the current cycle, {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}.
     */
    @Column(name = "curr_cyc_credit", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleCredit;

    /**
     * Debits posted in the current cycle, {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}.
     */
    @Column(name = "curr_cyc_debit", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleDebit;

    /**
     * The account's postal code, {@code ACCT-ADDR-ZIP PIC X(10)}.
     */
    @Column(name = "addr_zip", length = 10, nullable = false)
    private String addressZip;

    /**
     * The disclosure group the interest rate is looked up under, {@code ACCT-GROUP-ID PIC X(10)}.
     */
    @Column(name = "group_id", length = 10, nullable = false)
    private String groupId;

    /**
     * The optimistic-concurrency counter.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates an empty instance for the persistence provider.
     *
     * <p>Assumptions: the provider requires a no-argument constructor to materialise a row, and it is
     * {@code protected} so application code cannot construct a half-built entity by accident.</p>
     */
    protected Account() {
    }

    /**
     * Creates an account master row.
     *
     * <p>Assumptions: every parameter is required and none is defaulted. The reference record declares no
     * optional field in this set -- the schema marks all of them {@code NOT NULL} -- so accepting an
     * absent value here would produce a row the schema refuses, reported as a constraint violation far
     * from the omission rather than at it.</p>
     *
     * @param accountId the account identifier; must not be {@code null}
     * @param activeStatus the one-character active indicator; must not be {@code null}
     * @param currentBalance the posted balance; must not be {@code null}
     * @param creditLimit the credit limit; must not be {@code null}
     * @param cashCreditLimit the cash credit limit; must not be {@code null}
     * @param openDate when the account was opened; must not be {@code null}
     * @param expirationDate when the account expires; must not be {@code null}
     * @param reissueDate when the account was last reissued; must not be {@code null}
     * @param currentCycleCredit credits posted this cycle; must not be {@code null}
     * @param currentCycleDebit debits posted this cycle; must not be {@code null}
     * @param addressZip the account's postal code; must not be {@code null}
     * @param groupId the disclosure group; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public Account(Long accountId, String activeStatus, BigDecimal currentBalance,
            BigDecimal creditLimit, BigDecimal cashCreditLimit, LocalDate openDate,
            LocalDate expirationDate, LocalDate reissueDate, BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit, String addressZip, String groupId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.activeStatus = Objects.requireNonNull(activeStatus, "activeStatus must not be null");
        this.currentBalance = Objects.requireNonNull(currentBalance, "currentBalance must not be null");
        this.creditLimit = Objects.requireNonNull(creditLimit, "creditLimit must not be null");
        this.cashCreditLimit =
                Objects.requireNonNull(cashCreditLimit, "cashCreditLimit must not be null");
        this.openDate = Objects.requireNonNull(openDate, "openDate must not be null");
        this.expirationDate = Objects.requireNonNull(expirationDate, "expirationDate must not be null");
        this.reissueDate = Objects.requireNonNull(reissueDate, "reissueDate must not be null");
        this.currentCycleCredit =
                Objects.requireNonNull(currentCycleCredit, "currentCycleCredit must not be null");
        this.currentCycleDebit =
                Objects.requireNonNull(currentCycleDebit, "currentCycleDebit must not be null");
        this.addressZip = Objects.requireNonNull(addressZip, "addressZip must not be null");
        this.groupId = Objects.requireNonNull(groupId, "groupId must not be null");
    }

    /**
     * Returns the account identifier.
     *
     * @return the account identifier, never {@code null} on a persisted instance
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Returns the one-character active indicator.
     *
     * @return the active status, never {@code null} on a persisted instance
     */
    public String getActiveStatus() {
        return this.activeStatus;
    }

    /**
     * Returns the posted balance.
     *
     * @return the current balance, never {@code null} on a persisted instance
     */
    public BigDecimal getCurrentBalance() {
        return this.currentBalance;
    }

    /**
     * Returns the credit limit an authorization is weighed against.
     *
     * @return the credit limit, never {@code null} on a persisted instance
     */
    public BigDecimal getCreditLimit() {
        return this.creditLimit;
    }

    /**
     * Returns the cash credit limit.
     *
     * @return the cash credit limit, never {@code null} on a persisted instance
     */
    public BigDecimal getCashCreditLimit() {
        return this.cashCreditLimit;
    }

    /**
     * Returns when the account was opened.
     *
     * @return the open date, never {@code null} on a persisted instance
     */
    public LocalDate getOpenDate() {
        return this.openDate;
    }

    /**
     * Returns when the account expires.
     *
     * @return the expiration date, never {@code null} on a persisted instance
     */
    public LocalDate getExpirationDate() {
        return this.expirationDate;
    }

    /**
     * Returns when the account was last reissued.
     *
     * @return the reissue date, never {@code null} on a persisted instance
     */
    public LocalDate getReissueDate() {
        return this.reissueDate;
    }

    /**
     * Returns credits posted in the current cycle.
     *
     * @return the current-cycle credit total, never {@code null} on a persisted instance
     */
    public BigDecimal getCurrentCycleCredit() {
        return this.currentCycleCredit;
    }

    /**
     * Returns debits posted in the current cycle.
     *
     * @return the current-cycle debit total, never {@code null} on a persisted instance
     */
    public BigDecimal getCurrentCycleDebit() {
        return this.currentCycleDebit;
    }

    /**
     * Returns the account's postal code.
     *
     * @return the postal code, never {@code null} on a persisted instance
     */
    public String getAddressZip() {
        return this.addressZip;
    }

    /**
     * Returns the disclosure group the interest rate is looked up under.
     *
     * @return the group identifier, never {@code null} on a persisted instance
     */
    public String getGroupId() {
        return this.groupId;
    }

    /**
     * Returns the optimistic-concurrency counter.
     *
     * @return the version, zero on a row that has never been updated
     */
    public long getVersion() {
        return this.version;
    }

    /**
     * Renders this row for a log line or a diagnostic, disclosing no monetary detail.
     *
     * <p>Assumptions: the three amounts and the postal code are WITHHELD while the identifier, the status,
     * the expiry and the version are shown. That split follows what a reader of an incidental log line
     * needs: the identifier says which row, the status and expiry say whether it is usable, and the
     * version says which revision was in hand -- none of which is account detail. A balance beside an
     * account identifier is, and the paths that legitimately need it have it.</p>
     *
     * @return a rendering carrying the identifier, the status, the expiry and the version, never
     *     {@code null}
     */
    @Override
    public String toString() {
        return "Account[accountId=" + this.accountId
                + ", activeStatus=" + this.activeStatus
                + ", expirationDate=" + this.expirationDate
                + ", version=" + this.version + ']';
    }
}
