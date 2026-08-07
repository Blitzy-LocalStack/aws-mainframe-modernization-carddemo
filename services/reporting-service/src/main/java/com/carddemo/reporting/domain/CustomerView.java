package com.carddemo.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of {@code reporting.v_customers}, supplying the name and address a statement
 * heads with.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} reads the customer row once per card, at
 * {@code 2000-CUSTFILE-GET.} L368, and assembles from it the four values the statement heading
 * carries: the name, built at L462-L469 from three parts each followed by an unconditional blank
 * literal, and the three address lines, the third of which is itself assembled from a line, a state
 * code, a country code and a postal code. This projection carries those parts as they are stored and
 * assembles nothing; the assembly belongs to {@code StatementTextMapper}, which owns every declared
 * width the assembled values have to reach.
 *
 * <h2>Assumptions: the view withholds more than it projects, and that is the design</h2>
 *
 * <p>{@code app/cpy/CVCUS01Y.cpy} declares eighteen members and this projection carries eleven. The
 * seven it does not carry are withheld at the view rather than dropped here, which is the stronger
 * arrangement: the two enciphered national-identifier columns, the credit score, both telephone
 * numbers and the transfer-account reference are not in the view's select list at all, so the login
 * role this module authenticates as cannot read them even by writing its own query. A projection that
 * carried the view's whole width and then declined to expose part of it would leave those values one
 * accessor away; a view that never selects them leaves them one privilege away, and this role does
 * not hold that privilege.
 *
 * <p>Assumptions: the date of birth <em>is</em> projected while the credit score is not, and the two
 * decisions are consistent rather than arbitrary. The view's own comment records that no reporting
 * band prints the score, so the score has no reader; the date of birth is projected because the view
 * projects it, and it is carried here for the same reason the account projection carries the values it
 * does not print -- narrowing a projection below its view is a change that has to be made in a
 * migration another package owns, so it is not a change this file can make when the value is next
 * needed.
 *
 * <h2>Assumptions: three name parts, and the middle one may legitimately be absent</h2>
 *
 * <p>{@code CUST-MIDDLE-NAME} is nullable in the owning migration, and that absence is load-bearing
 * rather than incidental: the statement's name assembly emits a blank literal after each of the three
 * parts unconditionally, so an absent middle name yields a blank <em>pair</em> in the assembled name,
 * and the markup rendering cuts at that pair while the plain-text rendering does not. The two
 * artifacts therefore show different names for such a customer. That is the baseline's own behaviour,
 * is reproduced rather than reconciled, and is registered as {@code D-STMT-PAIRED-BLANK-NAME} in
 * {@code docs/architecture/cobol-to-service-traceability.md}. It is noted here because this is the
 * type a reader reaches first when asking why a name renders twice and differently.
 *
 * <h2>Assumptions: no write path exists on this type</h2>
 *
 * <p>The type is mapped {@code @Immutable} and every column is declared not updatable. The login role
 * holds {@code SELECT} on this view and nothing else, so a write would be refused at the database as
 * well; both controls are kept because the annotation fails at development time and the privilege
 * fails in production.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring regardless of visibility, because user-specified Rule 1
 * (Explainability) attaches its presence clause to every function and class and names no visibility.
 * The four rationale labels are written in the plural, unparenthesised forms that rule gives.
 */
@Entity
@Immutable
@Table(name = "v_customers", schema = "reporting")
public class CustomerView {

    /**
     * Declared width of each of the three name parts, in characters.
     *
     * <p>Assumptions: 25 is the width {@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME} and
     * {@code CUST-LAST-NAME} each declare at {@code app/cpy/CVCUS01Y.cpy} L6, L7 and L8. Three parts
     * at 25 characters, each followed by one blank literal, is what makes the assembled name 75
     * characters wide and not 78.</p>
     */
    public static final int NAME_PART_WIDTH = 25;

    /**
     * Declared width of each address line, in characters.
     *
     * <p>Assumptions: 50 is the width {@code CUST-ADDR-LINE-1} through {@code CUST-ADDR-LINE-3} each
     * declare at {@code app/cpy/CVCUS01Y.cpy} L9, L10 and L11.</p>
     */
    public static final int ADDRESS_LINE_WIDTH = 50;

    /**
     * Declared width of the state code, in characters.
     */
    public static final int STATE_CODE_WIDTH = 2;

    /**
     * Declared width of the country code, in characters.
     */
    public static final int COUNTRY_CODE_WIDTH = 3;

    /**
     * Declared width of the postal code, in characters.
     */
    public static final int POSTAL_CODE_WIDTH = 10;

    // Assumptions: the identifier is a magnitude rather than nine characters, because
    // app/cpy/CVCUS01Y.cpy L5 declares CUST-ID PIC 9(09), a numeric picture, and the migration that
    // owns the base table carries it as BIGINT. It is also the value the cross-reference row names,
    // so a character member here would compare unequal to the same value read from that relation.
    @Id
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "first_name", length = NAME_PART_WIDTH, nullable = false, updatable = false)
    private String firstName;

    // Assumptions: this member is nullable and the two beside it are not, because the owning
    // migration declares it so. The type-level charter records what an absent middle name does to the
    // two statement renderings; it is not defaulted to a blank here, because a blank and an absence
    // reach the assembly identically and substituting one for the other would hide which the row
    // actually held.
    @Column(name = "middle_name", length = NAME_PART_WIDTH, updatable = false)
    private String middleName;

    @Column(name = "last_name", length = NAME_PART_WIDTH, nullable = false, updatable = false)
    private String lastName;

    @Column(name = "addr_line_1", length = ADDRESS_LINE_WIDTH, nullable = false, updatable = false)
    private String addressLine1;

    @Column(name = "addr_line_2", length = ADDRESS_LINE_WIDTH, updatable = false)
    private String addressLine2;

    // Assumptions: the third line is one of four operands the statement's third address cell is
    // assembled from, the others being the state code, the country code and the postal code below.
    // The assembly is StatementTextMapper's, not this type's.
    @Column(name = "addr_line_3", length = ADDRESS_LINE_WIDTH, nullable = false, updatable = false)
    private String addressLine3;

    @Column(name = "addr_state_cd", length = STATE_CODE_WIDTH, nullable = false, updatable = false)
    private String stateCode;

    @Column(name = "addr_country_cd", length = COUNTRY_CODE_WIDTH, nullable = false,
            updatable = false)
    private String countryCode;

    @Column(name = "addr_zip", length = POSTAL_CODE_WIDTH, nullable = false, updatable = false)
    private String postalCode;

    // Assumptions: the date of birth is held as a calendar date rather than as the ten characters the
    // baseline stores. The stored form is ISO-ordered, so a lexical and a chronological comparison
    // agree and the change of type is behaviour-preserving; the type is changed anyway because a date
    // member cannot be accidentally concatenated or sliced.
    @Column(name = "dob", nullable = false, updatable = false)
    private LocalDate dateOfBirth;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a persistence entity to declare a constructor taking
     * no arguments. Visibility is protected because no caller outside this type's hierarchy has a use
     * for a half-built row.</p>
     */
    protected CustomerView() {
        // Assumptions: the body is empty by design rather than unfinished. The provider assigns all
        // eleven mapped fields directly after construction.
    }

    /**
     * Creates an instance from already-projected values.
     *
     * <p>Alternatives Considered: validating the arguments here. Rejected on the ground the sibling
     * projections of this package record: every value originates in a relation account-service owns,
     * so a guard here would make this context an arbiter of data it does not own.</p>
     *
     * @param customerId the customer identifier declared {@code PIC 9(09)} at
     *     {@code app/cpy/CVCUS01Y.cpy} L5
     * @param firstName the first name part declared at L6
     * @param middleName the middle name part declared at L7, which may be {@code null}
     * @param lastName the last name part declared at L8
     * @param addressLine1 the first address line declared at L9
     * @param addressLine2 the second address line declared at L10, which may be {@code null}
     * @param addressLine3 the third address line declared at L11
     * @param stateCode the two-character state code declared at L12
     * @param countryCode the three-character country code declared at L13
     * @param postalCode the ten-character postal code declared at L14
     * @param dateOfBirth the date of birth declared as {@code CUST-DOB-YYYY-MM-DD PIC X(10)} at
     *     L19, which is not L17 -- L17 declares the national identifier this view never selects
     */
    public CustomerView(Long customerId, String firstName, String middleName, String lastName,
            String addressLine1, String addressLine2, String addressLine3, String stateCode,
            String countryCode, String postalCode, LocalDate dateOfBirth) {
        this.customerId = customerId;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.addressLine3 = addressLine3;
        this.stateCode = stateCode;
        this.countryCode = countryCode;
        this.postalCode = postalCode;
        this.dateOfBirth = dateOfBirth;
    }

    /**
     * Returns the customer identifier this row is keyed by.
     *
     * @return the value of the {@code customer_id} column
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Returns the first name part.
     *
     * @return the value of the {@code first_name} column
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Returns the middle name part, which the row may legitimately not carry.
     *
     * @return the value of the {@code middle_name} column, or {@code null} where the row carries none
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Returns the last name part.
     *
     * @return the value of the {@code last_name} column
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Returns the first address line.
     *
     * @return the value of the {@code addr_line_1} column
     */
    public String getAddressLine1() {
        return addressLine1;
    }

    /**
     * Returns the second address line, which the row may legitimately not carry.
     *
     * @return the value of the {@code addr_line_2} column, or {@code null} where the row carries none
     */
    public String getAddressLine2() {
        return addressLine2;
    }

    /**
     * Returns the third address line, one of four operands the statement's third address cell needs.
     *
     * @return the value of the {@code addr_line_3} column
     */
    public String getAddressLine3() {
        return addressLine3;
    }

    /**
     * Returns the two-character state code.
     *
     * @return the value of the {@code addr_state_cd} column
     */
    public String getStateCode() {
        return stateCode;
    }

    /**
     * Returns the three-character country code.
     *
     * @return the value of the {@code addr_country_cd} column
     */
    public String getCountryCode() {
        return countryCode;
    }

    /**
     * Returns the ten-character postal code.
     *
     * @return the value of the {@code addr_zip} column
     */
    public String getPostalCode() {
        return postalCode;
    }

    /**
     * Returns the date of birth.
     *
     * @return the value of the {@code dob} column
     */
    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Reports whether another object denotes the same customer row as this one.
     *
     * <p>Assumptions: equality rests on the identifier alone. Including the name or the address would
     * make two loads of one row compare unequal after the owning context corrected either, which
     * would make row identity depend on data this context does not control.</p>
     *
     * @param other the object to compare against, which may be of any type and may be {@code null}
     * @return {@code true} when the argument is a customer projection carrying an equal identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CustomerView that)) {
            return false;
        }
        return Objects.equals(this.customerId, that.customerId);
    }

    /**
     * Returns a hash consistent with the identifier-only equality above.
     *
     * @return the hash of the customer identifier, or zero when none has been projected yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.customerId);
    }

    /**
     * Returns a diagnostic rendering naming the identifier alone.
     *
     * <p>Trade-offs: every name and address member is omitted and only the identifier is kept. The
     * identifier locates the row exactly and is the value a reader of a failure needs; a name, an
     * address line or a date of birth is personal data, and a log line is the wrong place for it even
     * when the failure is genuine. The cost accepted is that a reader cannot tell whose row failed
     * without querying for it; the compensation is that no log line written from this type carries
     * personal data at all.</p>
     *
     * @return a single-line rendering naming the type and the customer identifier, and nothing else
     */
    @Override
    public String toString() {
        return "CustomerView[customerId=" + customerId + ']';
    }
}
