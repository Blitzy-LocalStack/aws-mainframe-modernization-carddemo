package com.carddemo.account.domain;

import com.carddemo.common.security.CardNumberMasker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * The card-to-account-and-customer cross-reference row this context owns.
 *
 * <h2>Reference contract</h2>
 * <p>This is {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy} lines 4 through 8, the 50-byte
 * {@code CARDXREF} record: a sixteen-character card number, an eleven-digit customer identifier and an
 * eleven-digit account identifier, followed by filler to the record length. The filler is dropped, as
 * every migrated record drops it -- it is padding to a fixed physical length that a row does not have.</p>
 *
 * <p>Assumptions: the card number is the primary key, matching {@code pk_card_xref} in
 * {@code db/migration/V1__account.sql}, because the reference file is keyed on it and every online lookup
 * enters through it. The by-account access path is a SECONDARY index, {@code idx_card_xref_account_id},
 * which is the migrated form of the {@code CXACAIX} alternate index the CICS region surfaced as its own
 * file -- so both of the baseline's access paths exist here as real access paths rather than one being a
 * scan.</p>
 *
 * <p>Assumptions: the card number is a fixed-width {@code CHAR(16)} rather than a variable-width column,
 * because sixteen is part of the contract and not a maximum: the reference declares
 * {@code XREF-CARD-NUM PIC X(16)}, the messaging payload declares the same width at
 * {@code cpy/CCPAURQY.cpy} L21, and a lookup compares the whole value. Trailing-blank semantics therefore
 * matter and a variable-width column would silently accept a fifteen-character value that no reference
 * program could have produced.</p>
 *
 * <p>Alternatives Considered: mapping the two identifiers as associations to {@link Account} and
 * {@link Customer} rather than as plain identifiers. Rejected because this row is a cross-reference and
 * its whole purpose is to be read WITHOUT loading either of the records it points at -- the
 * authorization path resolves a card to an account identifier and then decides whether it needs the
 * account at all. An association would make every lookup a join or a lazy proxy, and would additionally
 * make the entity unusable in the one call that returns nothing but the two numbers.</p>
 *
 * <p>Assumptions: this entity carries no {@code @Version} column, unlike {@link Account} and
 * {@link Customer}. The reference system never updates a cross-reference row in place -- it is written
 * when a card is issued and deleted when the card is closed -- so there is no read-modify-write across a
 * client gap for an optimistic check to protect, and the migration's schema declares no version column
 * for this table to map.</p>
 */
@Entity
@Table(name = "card_xref")
public class CardXref {

    /**
     * The declared width of a card number, sixteen characters.
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * The primary account number this row is keyed on, {@code XREF-CARD-NUM PIC X(16)}.
     */
    @Id
    @Column(name = "card_num", length = CARD_NUMBER_LENGTH, nullable = false, updatable = false)
    private String cardNum;

    /**
     * The customer the card belongs to, {@code XREF-CUST-ID PIC 9(09)}.
     */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * The account the card draws on, {@code XREF-ACCT-ID PIC 9(11)}.
     *
     * <p>Assumptions: this column carries the secondary index that replaces {@code CXACAIX}. The index is
     * declared in the migration rather than here, because an index is a property of the schema and the
     * migration is this context's single authority for schema; declaring it in both places would create
     * two definitions that a later change could move apart.</p>
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Creates an empty instance for the persistence provider.
     *
     * <p>Assumptions: the provider requires a no-argument constructor to materialise a row, and it is
     * {@code protected} rather than {@code public} so application code cannot construct a half-built
     * entity by accident. The provider reaches a protected constructor by reflection.</p>
     */
    protected CardXref() {
    }

    /**
     * Creates a cross-reference row.
     *
     * @param cardNum the primary account number; must not be {@code null}
     * @param customerId the customer the card belongs to; must not be {@code null}
     * @param accountId the account the card draws on; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}, because a cross-reference row with a
     *     missing member points at nothing and is what a partial response would produce
     */
    public CardXref(String cardNum, Long customerId, Long accountId) {
        this.cardNum = Objects.requireNonNull(cardNum, "cardNum must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    }

    /**
     * Returns the primary account number this row is keyed on.
     *
     * @return the card number, never {@code null} on a persisted instance
     */
    public String getCardNum() {
        return this.cardNum;
    }

    /**
     * Returns the customer the card belongs to.
     *
     * @return the customer identifier, never {@code null} on a persisted instance
     */
    public Long getCustomerId() {
        return this.customerId;
    }

    /**
     * Returns the account the card draws on.
     *
     * @return the account identifier, never {@code null} on a persisted instance
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Renders this row for a log line or a diagnostic, disclosing no full card number.
     *
     * <p>Refactoring Rationale: the rendering an entity receives by default prints every field, so the
     * inherited form would emit the full sixteen-digit primary account number wherever this instance
     * reached a log -- and it reaches one on every persistence failure the provider reports.</p>
     *
     * <p>Trade-offs: the card number is MASKED here, whereas the card context's own entity omits its
     * card number from its rendering entirely. The difference is deliberate and follows from what each
     * row is: in that entity the number is one attribute among several and the rendering still identifies
     * the row by its account and expiry, while here the number IS the identity, so omitting it would
     * leave a rendering that cannot say which row it describes. The masked form keeps the line useful for
     * exactly the failure it appears in -- a duplicate key or a constraint violation on this table --
     * without disclosing the value. The two identifiers are shown in full because neither is cardholder
     * data on its own.</p>
     *
     * @return a rendering carrying the masked card number and the two identifiers, never {@code null}
     */
    @Override
    public String toString() {
        return "CardXref[cardNum=" + CardNumberMasker.mask(this.cardNum)
                + ", customerId=" + this.customerId
                + ", accountId=" + this.accountId + "]";
    }
}
