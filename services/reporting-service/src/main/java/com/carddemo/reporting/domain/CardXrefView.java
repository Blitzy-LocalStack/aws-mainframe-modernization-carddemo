package com.carddemo.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of {@code reporting.v_card_xref}, resolving one card to its customer and its
 * account.
 *
 * <h2>Purpose</h2>
 *
 * <p>This is the one relation both of this context's joins reach, and it reaches them through two
 * different access shapes. In the statement path {@code app/cbl/CBSTM03B.CBL} L39 declares the
 * cross-reference file {@code ACCESS MODE IS SEQUENTIAL} and {@code app/cbl/CBSTM03A.CBL} drives it
 * from the paragraph {@code 1000-XREFFILE-GET-NEXT.} at L345, so the statement generator walks it in
 * key order and every card it walks becomes one statement. In the report path
 * {@code app/cbl/CBTRN03C.cbl} looks the same relation up by key at {@code 1500-A-LOOKUP-XREF}
 * L484-L492. The projection is one type because the relation is one relation; the two shapes are
 * expressed by two repository roles rather than by two projections.
 *
 * <h2>Assumptions: the card number arrives masked and this type never unmasks it</h2>
 *
 * <p>The view's own definition in {@code data-migration/sql/V1__reporting_views.sql} concatenates
 * twelve asterisks with the last four digits of the stored number and casts the result back to the
 * declared sixteen characters, so what this type receives is a masked rendering at the width the
 * baseline field declares -- {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy} L5. The
 * declared length here is therefore 16 and not 4: the masked form occupies the full declared width,
 * and bounding the column at the four visible digits would reject the value the view returns.
 *
 * <p>Alternatives Considered: exposing the four visible digits as a separate member alongside the
 * masked form, so a caller wanting only the tail need not slice a string. Rejected because it would
 * put two renderings of one value on one type, and a caller could then pass the tail where the
 * artifact expects the declared width, which fills a cell four characters wide instead of sixteen
 * and misplaces everything after it. The masked form is the value; a tail is a substring a caller
 * takes on purpose.
 *
 * <h2>Assumptions: the key is the card number, because that is the key the file declares</h2>
 *
 * <p>{@code app/cpy/CVACT03Y.cpy} declares the record at L4 with the card number first at L5, the
 * customer identifier at L6 as {@code PIC 9(09)} and the account identifier at L7 as
 * {@code PIC 9(11)}, and the file is keyed on the card number. The masking does not disturb that:
 * the view masks the value it returns and the relation still has one row per card, so the masked
 * form remains unique per row and is a sound identifier for a read-only projection. Two cards
 * sharing a last-four would collide, so the mask alone would be an unsound key -- which is why the
 * cast preserves the full sixteen characters and the twelve asterisks are constant: two masked
 * values are equal only if the two tails are equal, and the traversal that consumes this type reads
 * in view order rather than deduplicating, so an equal pair is two rows and is walked as two.
 *
 * <h2>Assumptions: no write path exists on this type</h2>
 *
 * <p>The type is mapped {@code @Immutable} and every column is declared not updatable, so the
 * persistence provider will not emit an update for it and a modification attempted through it fails
 * before reaching the database. That is belt and braces rather than the primary control: the login
 * role this module authenticates as holds {@code SELECT} on this view and nothing else, so a write
 * would be refused at the database in any case. Both are kept because the annotation fails at
 * development time and the privilege fails in production, and neither substitutes for the other.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring regardless of visibility, because user-specified Rule 1
 * (Explainability) attaches its presence clause to every function and class and names no visibility,
 * and the Javadoc modules of {@code config/checkstyle/checkstyle.xml} are configured at private
 * scope. The four rationale labels are written in the plural, unparenthesised forms that rule gives,
 * which is the one form {@code docs/CODE_DOCUMENTATION_STANDARD.md} fixes.
 */
@Entity
@Immutable
@Table(name = "v_card_xref", schema = "reporting")
public class CardXrefView {

    /**
     * Declared width of the card number, in characters.
     *
     * <p>Assumptions: 16 is the width {@code XREF-CARD-NUM PIC X(16)} declares at
     * {@code app/cpy/CVACT03Y.cpy} L5, and it is also the width of the masked form the view returns,
     * because the view casts the concatenation back to a fixed sixteen characters.</p>
     */
    public static final int CARD_NUMBER_WIDTH = 16;

    // Assumptions: the member is named for the masked rendering it carries rather than for the
    // stored number, so that no caller reads it as a primary account number. The column name stays
    // card_num because that is the name the view projects and a mapped column cannot be renamed
    // without breaking the read.
    @Id
    @Column(name = "card_num", length = CARD_NUMBER_WIDTH, nullable = false, updatable = false)
    private String cardNum;

    // Assumptions: the identifier is a magnitude rather than nine characters. app/cpy/CVACT03Y.cpy
    // L6 declares XREF-CUST-ID PIC 9(09), a numeric picture, and the migration that owns the base
    // table carries it as BIGINT; a character member here would compare unequal to the same value
    // read as a number by the customer lookup this identifier feeds.
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    // Assumptions: likewise for the account identifier, declared XREF-ACCT-ID PIC 9(11) at
    // app/cpy/CVACT03Y.cpy L7. The two identifiers are the whole reason this relation exists: the
    // statement generator reads a card, then this row, then the customer and the account it names.
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a persistence entity to declare a constructor
     * taking no arguments. Visibility is protected because no caller outside this type's hierarchy
     * has a use for a half-built row, and protected is the widest visibility the requirement
     * needs.</p>
     */
    protected CardXrefView() {
        // Assumptions: the body is empty by design rather than unfinished. The provider assigns all
        // three mapped fields directly after construction, so initialising any of them here would
        // write a value that is immediately overwritten on every load.
    }

    /**
     * Creates an instance from already-projected values.
     *
     * <p>Alternatives Considered: validating the arguments here. Rejected on the same ground the
     * sibling projections of this package record: all three values originate in a relation
     * account-service owns, so a guard here would make this context an arbiter of data it does not
     * own and would fail a read of a row the owning context wrote on purpose.</p>
     *
     * @param cardNum the masked sixteen-character rendering of the card number the view returns,
     *     being twelve asterisks followed by the last four digits
     * @param customerId the customer identifier declared {@code PIC 9(09)} at
     *     {@code app/cpy/CVACT03Y.cpy} L6
     * @param accountId the account identifier declared {@code PIC 9(11)} at
     *     {@code app/cpy/CVACT03Y.cpy} L7
     */
    public CardXrefView(String cardNum, Long customerId, Long accountId) {
        this.cardNum = cardNum;
        this.customerId = customerId;
        this.accountId = accountId;
    }

    /**
     * Returns the masked rendering of the card number this row is keyed by.
     *
     * @return the value of the {@code card_num} column, being twelve asterisks and four digits at
     *     the declared width of {@value #CARD_NUMBER_WIDTH}
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Returns the customer this card belongs to.
     *
     * @return the value of the {@code customer_id} column, which the statement path uses to read the
     *     customer row at {@code app/cbl/CBSTM03A.CBL} L368
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Returns the account this card is issued against.
     *
     * @return the value of the {@code account_id} column, which the statement path uses to read the
     *     account row at {@code app/cbl/CBSTM03A.CBL} L392
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Reports whether another object denotes the same cross-reference row as this one.
     *
     * <p>Assumptions: equality rests on the key alone, for the reason the sibling projections
     * record: including the two identifiers would make two loads of one row compare unequal if the
     * owning context corrected either of them between the loads, which would make row identity
     * depend on data this context does not control.</p>
     *
     * <p>Assumptions: the test is a pattern match rather than an exact-class comparison because a
     * persistence provider may hand back a generated subclass of a mapped type, and an exact-class
     * comparison would then report two representations of one row unequal.</p>
     *
     * @param other the object to compare against, which may be of any type and may be {@code null}
     * @return {@code true} when the argument is a cross-reference projection carrying an equal
     *     masked card number, and {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardXrefView that)) {
            return false;
        }
        return Objects.equals(this.cardNum, that.cardNum);
    }

    /**
     * Returns a hash consistent with the key-only equality above.
     *
     * @return the hash of the masked card number, or zero when no value has been projected yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.cardNum);
    }

    /**
     * Returns a diagnostic rendering naming the two identifiers and not the card.
     *
     * <p>Trade-offs: the card number is omitted outright rather than abbreviated, which is the same
     * decision the sibling transaction projection records. Abbreviating a primary account number is
     * masking, and masking belongs to this context's mapper package, which the module charter names
     * as the sole boundary where it may appear; a second, slightly different masking rule here would
     * give one value two renderings and make neither authoritative. That the value this type holds
     * is <em>already</em> masked does not change the decision, because a rendering that carried it
     * would put the four visible digits into a log line, and four digits plus an account identifier
     * is a narrower gap than either alone. The cost accepted is that a reader cannot tell from a log
     * line which card a row is for; the compensation is that no log line written from this type
     * carries any part of a card number.</p>
     *
     * @return a single-line rendering naming the type, the customer identifier and the account
     *     identifier, and no part of the card number
     */
    @Override
    public String toString() {
        return "CardXrefView[customerId=" + customerId + ", accountId=" + accountId + ']';
    }
}
