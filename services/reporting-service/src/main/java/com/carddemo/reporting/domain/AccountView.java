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
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of {@code reporting.v_accounts}, supplying the account values a statement
 * heading prints.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} reads the account row once per card, at
 * {@code 3000-ACCTFILE-GET.} L392, and prints exactly one of its values into the statement: the
 * current balance, rendered under the mask declared at L113 and placed in the basic-detail block. The
 * remaining values this projection carries are the ones the view supplies for the report side and for
 * the disclosure-group resolution a rate lookup needs, and they are carried rather than dropped
 * because the view already projects them and a projection narrower than its view would have to be
 * widened -- with a migration owned by another package -- the first time one of them was needed.
 *
 * <h2>Assumptions: the balance is exact fixed point and never a binary floating point value</h2>
 *
 * <p>{@code ACCT-CURR-BAL} is declared {@code PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy} L7, which
 * is twelve significant digits, and {@code ACCT-CREDIT-LIMIT} is declared the same at L8. IEEE-754
 * binary floating point cannot represent most two-decimal fractions exactly and has no margin at all
 * at twelve digits, so both are held as the shared exact decimal type through the converter nested
 * below. The prohibition is not a preference of this file: it is asserted for the whole reactor by the
 * layering rules held in {@code LayeringRulesTest}, so a member declared as a binary floating point
 * quantity here would fail a build rather than a review.
 *
 * <h2>Assumptions: three dates, and the misspelling correction that reaches one of them</h2>
 *
 * <p>The baseline declares the expiration date as {@code ACCT-EXPIRAION-DATE} at
 * {@code app/cpy/CVACT01Y.cpy}, with the {@code T} missing from the middle of the word. AAP rule T1
 * names that as one of exactly three baseline misspellings corrected in target column names, so the
 * column is {@code expiration_date} and the member is {@code expirationDate}. The correction is
 * recorded here because a reader searching the baseline for the target name will not find it, and a
 * reader searching the target for the baseline name will not find that either.
 *
 * <p>Assumptions: all three dates are held as calendar dates rather than as the ten characters the
 * baseline stores. The stored form is already ISO-ordered, so a lexical comparison over the characters
 * and a chronological comparison over the dates agree, which is what makes the change of type
 * behaviour-preserving; the type is changed anyway because a date member cannot be accidentally
 * concatenated, sliced or compared against a differently formatted string.
 *
 * <h2>Assumptions: no write path exists on this type</h2>
 *
 * <p>The type is mapped {@code @Immutable} and every column is declared not updatable. The login role
 * this module authenticates as holds {@code SELECT} on this view and nothing else, so a write would
 * be refused at the database as well; both controls are kept because the annotation fails at
 * development time and the privilege fails in production, and neither substitutes for the other.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring regardless of visibility, because user-specified Rule 1
 * (Explainability) attaches its presence clause to every function and class and names no visibility.
 * The four rationale labels are written in the plural, unparenthesised forms that rule gives.
 */
@Entity
@Immutable
@Table(name = "v_accounts", schema = "reporting")
public class AccountView {

    /**
     * Declared width of the account status flag, in characters.
     *
     * <p>Assumptions: 1 is the width {@code ACCT-ACTIVE-STATUS PIC X(01)} declares at
     * {@code app/cpy/CVACT01Y.cpy} L6.</p>
     */
    public static final int ACTIVE_STATUS_WIDTH = 1;

    /**
     * Declared width of the disclosure group identifier, in characters.
     *
     * <p>Assumptions: 10 is the width {@code ACCT-GROUP-ID PIC X(10)} declares at
     * {@code app/cpy/CVACT01Y.cpy} L16, and it is the key the interest calculation resolves a rate
     * by -- the same identifier whose absence falls back to the {@code DEFAULT} group.</p>
     */
    public static final int GROUP_ID_WIDTH = 10;

    // Assumptions: the identifier is a magnitude rather than eleven characters, because
    // app/cpy/CVACT01Y.cpy L5 declares ACCT-ID PIC 9(11), a numeric picture, and the migration that
    // owns the base table carries it as BIGINT.
    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "active_status", length = ACTIVE_STATUS_WIDTH, nullable = false,
            updatable = false)
    private String activeStatus;

    // Assumptions: the balance is the ONE value of this row a statement prints, at
    // app/cbl/CBSTM03A.CBL L621 under the mask declared at L113. It is carried at scale 2 through the
    // converter below and never as a binary floating point quantity.
    @Column(name = "curr_bal", nullable = false, updatable = false)
    @Convert(converter = AccountMoneyConverter.class)
    private Money currentBalance;

    @Column(name = "credit_limit", nullable = false, updatable = false)
    @Convert(converter = AccountMoneyConverter.class)
    private Money creditLimit;

    @Column(name = "open_date", nullable = false, updatable = false)
    private LocalDate openDate;

    // Assumptions: the member and the column both carry the CORRECTED spelling. The baseline field is
    // ACCT-EXPIRAION-DATE and AAP rule T1 names it as one of three corrections; the type-level charter
    // above records the correction so a reader can find either spelling from the other.
    @Column(name = "expiration_date", nullable = false, updatable = false)
    private LocalDate expirationDate;

    @Column(name = "reissue_date", nullable = false, updatable = false)
    private LocalDate reissueDate;

    @Column(name = "group_id", length = GROUP_ID_WIDTH, nullable = false, updatable = false)
    private String groupId;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a persistence entity to declare a constructor taking
     * no arguments. Visibility is protected because no caller outside this type's hierarchy has a use
     * for a half-built row.</p>
     */
    protected AccountView() {
        // Assumptions: the body is empty by design rather than unfinished. The provider assigns all
        // eight mapped fields directly after construction.
    }

    /**
     * Creates an instance from already-projected values.
     *
     * <p>Alternatives Considered: validating the arguments here. Rejected on the ground the sibling
     * projections of this package record: every value originates in a relation account-service owns,
     * so a guard here would make this context an arbiter of data it does not own and would fail a
     * read of a row the owning context wrote on purpose.</p>
     *
     * @param accountId the account identifier declared {@code PIC 9(11)} at
     *     {@code app/cpy/CVACT01Y.cpy} L5
     * @param activeStatus the one-character status declared at L6
     * @param currentBalance the balance declared {@code PIC S9(10)V99} at L7, the one value of this
     *     row a statement prints
     * @param creditLimit the credit limit declared {@code PIC S9(10)V99} at L8
     * @param openDate the open date declared at L10
     * @param expirationDate the expiration date declared at L11, whose baseline identifier carries
     *     the misspelling AAP rule T1 corrects
     * @param reissueDate the reissue date declared at L12
     * @param groupId the ten-character disclosure group identifier declared at L16
     */
    public AccountView(Long accountId, String activeStatus, Money currentBalance, Money creditLimit,
            LocalDate openDate, LocalDate expirationDate, LocalDate reissueDate, String groupId) {
        this.accountId = accountId;
        this.activeStatus = activeStatus;
        this.currentBalance = currentBalance;
        this.creditLimit = creditLimit;
        this.openDate = openDate;
        this.expirationDate = expirationDate;
        this.reissueDate = reissueDate;
        this.groupId = groupId;
    }

    /**
     * Returns the account identifier this row is keyed by.
     *
     * @return the value of the {@code account_id} column
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Returns the one-character account status.
     *
     * @return the value of the {@code active_status} column
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Returns the current balance, which the statement heading prints.
     *
     * @return the value of the {@code curr_bal} column at scale 2
     */
    public Money getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Returns the credit limit.
     *
     * @return the value of the {@code credit_limit} column at scale 2
     */
    public Money getCreditLimit() {
        return creditLimit;
    }

    /**
     * Returns the date the account was opened.
     *
     * @return the value of the {@code open_date} column
     */
    public LocalDate getOpenDate() {
        return openDate;
    }

    /**
     * Returns the date the account expires.
     *
     * @return the value of the {@code expiration_date} column, whose baseline identifier carries the
     *     misspelling AAP rule T1 corrects
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Returns the date the account is reissued.
     *
     * @return the value of the {@code reissue_date} column
     */
    public LocalDate getReissueDate() {
        return reissueDate;
    }

    /**
     * Returns the disclosure group this account resolves a rate through.
     *
     * @return the value of the {@code group_id} column
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Reports whether another object denotes the same account row as this one.
     *
     * <p>Assumptions: equality rests on the identifier alone. Including the balance would make two
     * loads of one row compare unequal across a posting run, which would make row identity depend on
     * a figure that changes by design.</p>
     *
     * @param other the object to compare against, which may be of any type and may be {@code null}
     * @return {@code true} when the argument is an account projection carrying an equal identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountView that)) {
            return false;
        }
        return Objects.equals(this.accountId, that.accountId);
    }

    /**
     * Returns a hash consistent with the identifier-only equality above.
     *
     * @return the hash of the account identifier, or zero when none has been projected yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.accountId);
    }

    /**
     * Returns a diagnostic rendering naming the identifier and the status, and no monetary figure.
     *
     * <p>Trade-offs: both monetary members are omitted, which is the same decision the sibling
     * transaction projection records for its own amount. The cost accepted is that a reader cannot
     * tell a row's balance from a log line; the compensation is that no log line written from this
     * type carries a monetary figure at all.</p>
     *
     * @return a single-line rendering naming the type, the account identifier and the status
     */
    @Override
    public String toString() {
        return "AccountView[accountId=" + accountId + ", activeStatus=" + activeStatus + ']';
    }

    /**
     * Converts the two monetary columns of this projection between the shared money type and the
     * exact decimal the columns carry.
     *
     * <p>Purpose: the shared money type is declared final with a non-public constructor, so the
     * persistence provider cannot instantiate it and a converter is the supported way to map it.</p>
     *
     * <p>Assumptions: automatic application is deliberately off, so this converter binds only where
     * it is named. Registering it to apply to every monetary attribute in the persistence unit was
     * rejected because the balance declared at {@code app/cpy/CVACT01Y.cpy} L7 is
     * {@code PIC S9(10)V99} while the transaction amount at {@code app/cpy/CVTRA05Y.cpy} L10 is
     * {@code PIC S9(09)V99}, one integer digit narrower, so an automatic registration would reach
     * attributes of two different declared precisions and would do so silently.</p>
     *
     * <p>Trade-offs: the sibling projections of this package carry their own copies of the same short
     * conversion, so it is stated more than once across the package. That duplication is accepted
     * because the alternatives are a shared file the package's closed-set contract forbids or an
     * automatic registration that would apply where it was never inspected.</p>
     */
    @Converter(autoApply = false)
    public static class AccountMoneyConverter implements AttributeConverter<Money, BigDecimal> {

        /**
         * Creates a converter instance for the persistence provider to use.
         *
         * <p>Assumptions: the provider instantiates a converter reflectively and requires a public
         * constructor taking no arguments, so it is declared explicitly rather than left implicit, in
         * order that the requirement is visible to a reader of this file.</p>
         */
        public AccountMoneyConverter() {
            // WHY : Assumptions: the body is empty because a converter holds no state. Holding state
            //       here would be unsafe, since one instance is shared across every thread reading
            //       through this mapping.
        }

        /**
         * Converts a monetary value to the decimal form the column carries.
         *
         * <p>Assumptions: this direction is reachable on a read despite this projection having no
         * write path, because the provider also calls it when a monetary value is bound as a query
         * parameter. A converter that refused this direction would turn a legitimate comparison
         * against an amount into a failure.</p>
         *
         * @param attribute the monetary value to convert; may be {@code null}
         * @return the amount as a decimal at a scale of exactly two, or {@code null} when the
         *     argument is {@code null}
         */
        @Override
        public BigDecimal convertToDatabaseColumn(Money attribute) {
            return attribute == null ? null : attribute.amount();
        }

        /**
         * Converts the column's decimal form to a monetary value.
         *
         * @param dbData the decimal value read from the column; may be {@code null}
         * @return the amount as a monetary value at a scale of exactly two, or {@code null} when the
         *     argument is {@code null}
         * @throws ArithmeticException if the value read exceeds the magnitude the monetary type
         *     admits, which points at a view definition to correct rather than at an amount to round
         */
        @Override
        public Money convertToEntityAttribute(BigDecimal dbData) {
            return dbData == null ? null : Money.of(dbData);
        }
    }
}
