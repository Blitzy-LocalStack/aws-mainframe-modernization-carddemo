package com.carddemo.reporting.domain;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.domain.ReportTransactionView.MoneyAmountConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import org.hibernate.annotations.Immutable;

/**
 * One transaction-category balance as the category-balance report reads it.
 *
 * <p>Purpose: this is the read side of {@code app/jcl/PRTCATBL.jcl}, the sort-only job that
 * produces {@code AWS.M2.CARDDEMO.TCATBALF.REPT}. That job has no COBOL program at all: its
 * {@code STEP10R} declares the four fields it needs through {@code SYMNAMES} at lines 47-50 --
 * {@code TRANCAT-ACCT-ID,1,11,ZD}, {@code TRANCAT-TYPE-CD,12,2,CH}, {@code TRANCAT-CD,14,4,ZD} and
 * {@code TRAN-CAT-BAL,18,11,ZD} -- sorts on the first three at line 52 and reformats all four at
 * lines 53-56. Those four fields are exactly the members below, and they are also the whole of
 * {@code app/cpy/CVTRA01Y.cpy} apart from its 22-byte trailing {@code FILLER}.</p>
 *
 * <p>Assumptions: the relation is {@code reporting.v_transaction_category_balances}, a masked
 * read-only view owned by the schema owner, and not {@code ledger.transaction_category_balances}
 * itself. This context holds a {@code SELECT}-only login role with no reach into the ledger schema,
 * so a mapping onto the base table would fail at run time on a permission rather than at build time
 * on a type -- which is why the view exists and why this class names it.</p>
 *
 * <p>Assumptions: the identity is the whole three-member key group {@code TRAN-CAT-KEY} declares at
 * {@code app/cpy/CVTRA01Y.cpy:5}, so it is held as one embedded value. Scoping is worth stating at
 * this exact declaration, because the group NAME collides: {@code app/cpy/CVTRA04Y.cpy:5} declares a
 * group also reachable as a transaction-category key, there over six bytes and two members, where
 * this one is seventeen bytes and three. The nested record below therefore names its owning copybook
 * on every component so the two can never be read as the same key.</p>
 *
 * <p>Trade-offs: the balance is carried as {@link Money} rather than as a {@code BigDecimal} or a
 * {@code String}. {@code Money} is the module-wide fixed-point carrier and the edit-mask formatter
 * that renders this value accepts only that type, so mapping to anything else would put a conversion
 * at every call site and leave the door open to a {@code double} appearing in one of them. The cost
 * is that the provider needs the attribute converter the shared kernel registers, which every
 * service in this repository already applies to every money column.</p>
 *
 * <p>Trade-offs: no equality or hash override is declared, for the same reason the sibling category
 * projection states: the only load-bearing sameness for a row read through this view is the equality
 * of its key, which the nested record's canonical members already carry. A second definition over
 * the key plus the balance would leave a reader unable to tell which one a collection was using.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <p>Baseline lineage: citations are provenance only. Nothing under {@code app/} is read at run time
 * and nothing under it is altered by this migration.</p>
 */
@Entity
@Immutable
@Table(name = "v_transaction_category_balances", schema = "reporting")
public class TransactionCategoryBalanceView {

    // Assumptions: the identity is the whole 17-byte group at app/cpy/CVTRA01Y.cpy:5, held as one
    // embedded value rather than as three loose members, because the reference's own sort names all
    // three in one SORT FIELDS list and a partial key would let two categories of one account
    // collapse into one report line.
    @EmbeddedId
    private TransactionCategoryBalanceKey key;

    // Assumptions: this is the balance at TRAN-CAT-BAL PIC S9(09)V99, one-based positions 18 through
    // 28 of the 50-byte record. Eleven display bytes carry nine integer digits, two decimals and a
    // sign overpunch, which is exactly the NUMERIC(11,2) the owning context declares -- so the
    // column and the picture agree on precision and scale rather than on width.
    // WHY : Assumptions: the converter is the one ReportTransactionView already declares, reached by
    //       import rather than redeclared here. A fourth nested copy was the alternative and is
    //       rejected: the conversion is Money to BigDecimal in both directions with a null passthrough
    //       and nothing else, so a second copy adds a place for the two to disagree about scale while
    //       every money column in this context must round identically.
    // WHY : Assumptions: the column is declared read-only through insertable and updatable being
    //       false, the same way the sibling projections declare theirs. This maps a VIEW and the
    //       login role holds SELECT only, so a write would fail at the database; declaring it here
    //       makes the provider refuse before the statement is issued and names the reason as
    //       mapping intent rather than as a permission fault.
    @Column(name = "balance", precision = 11, scale = 2, insertable = false, updatable = false)
    @Convert(converter = MoneyAmountConverter.class)
    private Money balance;

    /**
     * Creates an instance for the persistence provider to populate when it materialises a row.
     *
     * <p>Trade-offs: this constructor exists only because a provider requires a no-argument
     * constructor to instantiate a managed row, and it is protected rather than public so that
     * application code cannot fabricate a row shape no query returned. Its body is empty because the
     * provider assigns both members after construction, and this being a read-only projection there
     * is nothing to validate either -- a row exists in the view before an instance representing it
     * does.</p>
     */
    protected TransactionCategoryBalanceView() {
    }

    /**
     * Creates a fully populated instance from the four already-decoded column values. This is the
     * constructor a unit test uses to build a row without a database.
     *
     * <p>Purpose: it accepts the three key members of {@code TRAN-CAT-KEY} in the order
     * {@code app/cpy/CVTRA01Y.cpy:5} declares them, followed by the balance, so the argument list
     * reads in the same sequence as the record and a transposition is visible on inspection.</p>
     *
     * <p>Refactoring Rationale: this constructor was initially withheld, on the reasoning that a
     * public one "would let a caller assemble a balance the owning context never published". The
     * reasoning applies to application code and not to a test, and withholding it left the line
     * layout and the report service with no way to be exercised except through a database or through
     * reflection -- reflection being the worse of the two, since it binds a test to field names the
     * compiler would otherwise check. The sibling projection {@code ReportTransactionView} settled
     * the same question the same way and documents its constructor in the same terms, so this is the
     * package's established shape rather than an exception made for one class. What the original
     * reasoning protects is preserved by the class carrying no setter and by every column being
     * declared non-insertable and non-updatable: an instance a caller builds is unmanaged and cannot
     * be written back.</p>
     *
     * <p>Assumptions: no argument is validated and none is rejected. The values a caller supplies are
     * the values the view produced; the widths and the domains are settled by the column declarations
     * above and by the view definition, and the consumers -- {@code CategoryBalanceLineLayout} above
     * all -- refuse what they cannot render, which is where a refusal belongs because that is where
     * the declared field width is known.</p>
     *
     * @param accountId the eleven-digit account identifier from {@code TRANCAT-ACCT-ID}
     * @param typeCode the two-character transaction type code from {@code TRANCAT-TYPE-CD}
     * @param categoryCode the four-character transaction category code from {@code TRANCAT-CD},
     *     carried with its leading zeros intact
     * @param balance the category balance from {@code TRAN-CAT-BAL} at scale 2
     */
    public TransactionCategoryBalanceView(
            Long accountId, String typeCode, String categoryCode, Money balance) {
        this.key = new TransactionCategoryBalanceKey(accountId, typeCode, categoryCode);
        this.balance = balance;
    }

    /**
     * Reports the composite key identifying this balance.
     *
     * @return the key holding the account identifier, the type code and the category code, never
     *     {@code null} for a row the provider materialised
     */
    public TransactionCategoryBalanceKey getKey() {
        return this.key;
    }

    /**
     * Reports the balance this category carries.
     *
     * @return the balance as an exact fixed-point value at scale two, never {@code null} for a row
     *     the provider materialised; negative when the category is in credit, which the reference's
     *     signed picture also permits
     */
    public Money getBalance() {
        return this.balance;
    }

    /**
     * The three-part key of one transaction-category balance.
     *
     * <p>Purpose: carry the whole of {@code TRAN-CAT-KEY} as one value, so a caller cannot compose a
     * partial key and so the sort order the report needs can be expressed over the key's own members
     * in the sequence the copybook declares them.</p>
     *
     * <p>Trade-offs: this is a nested record rather than a top-level class, matching the sibling
     * category projection's shape for the same reason it gives -- an identifier is meaningless apart
     * from the row it identifies, and a top-level type would invite a caller to hold one alone.</p>
     *
     * @param accountId the eleven-digit account identifier, the leading component of the group, read
     *     from {@code TRANCAT-ACCT-ID PIC 9(11)} at {@code app/cpy/CVTRA01Y.cpy:6} and occupying
     *     one-based positions 1 through 11; carried as a {@code Long} rather than as text because the
     *     owning context declares it {@code BIGINT} and because the reference's own sort declares it
     *     {@code ZD}, a numeric form, rather than {@code CH}
     * @param typeCode the two-character transaction type code, read from
     *     {@code TRANCAT-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA01Y.cpy:7} and occupying positions
     *     12 through 13; carried as fixed-width text because its width is part of the key contract
     *     and because the reference's sort declares it {@code CH}
     * @param categoryCode the four-digit transaction category code, the trailing component, read from
     *     {@code TRANCAT-CD PIC 9(04)} at {@code app/cpy/CVTRA01Y.cpy:8} and occupying positions 14
     *     through 17; carried as fixed-width TEXT rather than as a number even though the reference
     *     sorts it as {@code ZD}, because its leading zeros are part of the printed line -- the
     *     {@code 0001} form in {@code app/data/ASCII/tcatbal.txt} is what the report renders, and an
     *     integer would lose them
     */
    @Embeddable
    public record TransactionCategoryBalanceKey(
            @Column(name = "account_id") Long accountId,
            @Column(name = "type_cd") String typeCode,
            @Column(name = "category_cd") String categoryCode) implements Serializable {

        /**
         * Renders this key without the account identifier it carries.
         *
         * <p>Purpose: a record's generated rendering prints every component, and this one holds an
         * account identifier. {@code docs/architecture/observability.md} L1095 to L1104 names an account
         * identifier among the values a diagnostic OMITS rather than abbreviates, so it is dropped here
         * rather than masked -- masking has one owner per bounded context, in that context's
         * {@code mapper} package, and a second rule inside an embeddable key would give one value two
         * renderings and make neither authoritative.</p>
         *
         * <p>Assumptions: the type and category codes are KEPT. L1109 to L1112 of that same rule names a
         * type or category code as identity that discloses nothing, so retaining them is what the rule
         * prescribes rather than a concession it tolerates.</p>
         *
         * <p>Trade-offs: a line written from this key can no longer say which account's balance row it
         * describes, only which type and category. What pays that down is the mechanism the same
         * authority names everywhere else -- the correlation identifier on every request-scoped line and
         * the {@code batch.batch_run} step ledger for batch work.</p>
         *
         * @return {@code String} rendering on a single line, naming the type and category codes and no
         *     identifier, never {@code null}
         */
        @Override
        public String toString() {
            return "TransactionCategoryBalanceKey[typeCode=" + typeCode
                    + ", categoryCode=" + categoryCode + ']';
        }
    }
}
