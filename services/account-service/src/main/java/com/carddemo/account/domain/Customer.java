package com.carddemo.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The customer master row this context owns.
 *
 * <h2>Reference contract</h2>
 * <p>This is {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy} lines 4 through 23, the 500-byte
 * {@code CUSTDATA} record. The trailing filler that pads the record to 500 bytes is dropped.</p>
 *
 * <p>Assumptions: the national identifier and the government-issued identifier are stored ENCRYPTED, as
 * {@code byte[]} over {@code BYTEA} columns, and no accessor on this class returns either in clear text.
 * The reference stores both in clear -- {@code CUST-SSN PIC 9(09)} at {@code CVCUS01Y.cpy} L16 -- and that
 * is a documented, deliberate divergence rather than an oversight: the migration's security mapping
 * requires both at rest encrypted and returned masked, and porting the clear-text form faithfully would
 * have carried a defect across for the sake of fidelity to it.</p>
 *
 * <p>Assumptions: the two phone columns are NULLABLE while every other field here is not, and the
 * asymmetry comes from the reference rather than from taste. The shared edit routine treats a phone number
 * as not mandatory and the seed data leaves it low-values, so a {@code NOT NULL} column would refuse rows
 * the reference produces.</p>
 *
 * <p>Assumptions: the {@code @Version} column is the migrated form of the before-image comparison
 * {@code app/cbl/COACTUPC.cbl} performs across the pseudo-conversational gap, exactly as on
 * {@link Account}. It expresses an existing check natively and introduces no new behaviour.</p>
 *
 * <p>Alternatives Considered: mapping only an existence probe, since the account-context contract asks
 * this context nothing about a customer except whether the row is there. Rejected for the same reason the
 * account entity is not narrowed to three amounts: this is the context's customer master, and an entity
 * shaped to one caller's question would silently drop every unmapped column on a write. The contract
 * narrows what is published; the entity carries the row.</p>
 */
@Entity
@Table(name = "customers")
public class Customer {

    /**
     * The customer identifier, {@code CUST-ID PIC 9(09)}.
     */
    @Id
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    /**
     * The customer's first name, {@code CUST-FIRST-NAME PIC X(25)}.
     */
    @Column(name = "first_name", length = 25, nullable = false)
    private String firstName;

    /**
     * The customer's middle name, {@code CUST-MIDDLE-NAME PIC X(25)}, which the reference leaves optional.
     */
    @Column(name = "middle_name", length = 25)
    private String middleName;

    /**
     * The customer's last name, {@code CUST-LAST-NAME PIC X(25)}.
     */
    @Column(name = "last_name", length = 25, nullable = false)
    private String lastName;

    /**
     * The first address line, {@code CUST-ADDR-LINE-1 PIC X(50)}.
     */
    @Column(name = "addr_line_1", length = 50, nullable = false)
    private String addressLine1;

    /**
     * The second address line, {@code CUST-ADDR-LINE-2 PIC X(50)}, which the reference leaves optional.
     */
    @Column(name = "addr_line_2", length = 50)
    private String addressLine2;

    /**
     * The third address line, {@code CUST-ADDR-LINE-3 PIC X(50)}.
     */
    @Column(name = "addr_line_3", length = 50, nullable = false)
    private String addressLine3;

    /**
     * The state code, {@code CUST-ADDR-STATE-CD PIC X(02)}.
     */
    @Column(name = "addr_state_cd", length = 2, nullable = false)
    private String addressStateCode;

    /**
     * The country code, {@code CUST-ADDR-COUNTRY-CD PIC X(03)}.
     */
    @Column(name = "addr_country_cd", length = 3, nullable = false)
    private String addressCountryCode;

    /**
     * The postal code, {@code CUST-ADDR-ZIP PIC X(10)}.
     */
    @Column(name = "addr_zip", length = 10, nullable = false)
    private String addressZip;

    /**
     * The primary phone number, {@code CUST-PHONE-NUM-1 PIC X(15)}, optional in the reference.
     */
    @Column(name = "phone_num_1", length = 15)
    private String phoneNumber1;

    /**
     * The secondary phone number, {@code CUST-PHONE-NUM-2 PIC X(15)}, optional in the reference.
     */
    @Column(name = "phone_num_2", length = 15)
    private String phoneNumber2;

    /**
     * The encrypted national identifier, the protected form of {@code CUST-SSN PIC 9(09)}.
     *
     * <p>Assumptions: this field has no public accessor at all, which is stronger than having one that
     * masks. A masking accessor still needs every caller to use it, and the field is a decryption away
     * from being the value itself; withholding the accessor means no code path outside this context can
     * ask for it, so the question of whether a given caller masked correctly never arises.</p>
     */
    @Column(name = "ssn_encrypted", nullable = false)
    private byte[] ssnEncrypted;

    /**
     * The encrypted government-issued identifier, the protected form of
     * {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     *
     * <p>Assumptions: nullable, because the reference leaves the field optional, and likewise without a
     * public accessor.</p>
     */
    @Column(name = "govt_issued_id_encrypted")
    private byte[] governmentIssuedIdEncrypted;

    /**
     * The date of birth, {@code CUST-DOB-YYYY-MM-DD PIC X(10)}.
     */
    @Column(name = "dob", nullable = false)
    private LocalDate dateOfBirth;

    /**
     * The electronic-transfer account identifier, {@code CUST-EFT-ACCOUNT-ID PIC X(10)}.
     */
    @Column(name = "eft_account_id", length = 10, nullable = false)
    private String eftAccountId;

    /**
     * Whether this customer is the primary cardholder, {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}.
     */
    @Column(name = "pri_card_holder_ind", length = 1, nullable = false)
    private String primaryCardHolderIndicator;

    /**
     * The credit score, {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     *
     * <p>Assumptions: a {@code short} over {@code SMALLINT}, because the reference bounds the value at
     * three digits and a wider type would admit values no reference program could produce.</p>
     */
    @Column(name = "fico_credit_score", nullable = false)
    private short ficoCreditScore;

    /**
     * The optimistic-concurrency counter.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates an empty instance for the persistence provider.
     */
    protected Customer() {
    }

    /**
     * Creates a customer master row.
     *
     * <p>Trade-offs: this constructor takes eighteen parameters, which is more than a constructor should
     * ordinarily carry. It is accepted rather than replaced by a builder for one reason: the reference
     * record has eighteen meaningful fields and the schema marks fourteen of them {@code NOT NULL}, so a
     * builder would make every one of those omittable at the call site and would move the completeness
     * check from the compiler to a runtime constraint violation. The positional form is harder to read
     * once and impossible to get wrong silently.</p>
     *
     * @param customerId the customer identifier; must not be {@code null}
     * @param firstName the first name; must not be {@code null}
     * @param middleName the middle name, or {@code null} when the reference left it blank
     * @param lastName the last name; must not be {@code null}
     * @param addressLine1 the first address line; must not be {@code null}
     * @param addressLine2 the second address line, or {@code null} when blank
     * @param addressLine3 the third address line; must not be {@code null}
     * @param addressStateCode the state code; must not be {@code null}
     * @param addressCountryCode the country code; must not be {@code null}
     * @param addressZip the postal code; must not be {@code null}
     * @param phoneNumber1 the primary phone number, or {@code null} when the reference left it blank
     * @param phoneNumber2 the secondary phone number, or {@code null} when blank
     * @param ssnEncrypted the encrypted national identifier; must not be {@code null}
     * @param governmentIssuedIdEncrypted the encrypted government-issued identifier, or {@code null}
     * @param dateOfBirth the date of birth; must not be {@code null}
     * @param eftAccountId the electronic-transfer account identifier; must not be {@code null}
     * @param primaryCardHolderIndicator whether this customer is the primary cardholder; must not be
     *     {@code null}
     * @param ficoCreditScore the credit score
     * @throws NullPointerException if any argument the schema marks {@code NOT NULL} is {@code null}
     */
    public Customer(Long customerId, String firstName, String middleName, String lastName,
            String addressLine1, String addressLine2, String addressLine3, String addressStateCode,
            String addressCountryCode, String addressZip, String phoneNumber1, String phoneNumber2,
            byte[] ssnEncrypted, byte[] governmentIssuedIdEncrypted, LocalDate dateOfBirth,
            String eftAccountId, String primaryCardHolderIndicator, short ficoCreditScore) {
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.firstName = Objects.requireNonNull(firstName, "firstName must not be null");
        this.middleName = middleName;
        this.lastName = Objects.requireNonNull(lastName, "lastName must not be null");
        this.addressLine1 = Objects.requireNonNull(addressLine1, "addressLine1 must not be null");
        this.addressLine2 = addressLine2;
        this.addressLine3 = Objects.requireNonNull(addressLine3, "addressLine3 must not be null");
        this.addressStateCode =
                Objects.requireNonNull(addressStateCode, "addressStateCode must not be null");
        this.addressCountryCode =
                Objects.requireNonNull(addressCountryCode, "addressCountryCode must not be null");
        this.addressZip = Objects.requireNonNull(addressZip, "addressZip must not be null");
        this.phoneNumber1 = phoneNumber1;
        this.phoneNumber2 = phoneNumber2;
        // WHY : Assumptions: both protected arrays are copied on the way in and never handed back out.
        //   An array is mutable and a caller retaining its own reference could alter the stored
        //   ciphertext after construction, which for an encrypted identifier means corrupting it in a way
        //   no constraint would catch.
        this.ssnEncrypted =
                defensiveCopy(Objects.requireNonNull(ssnEncrypted, "ssnEncrypted must not be null"));
        this.governmentIssuedIdEncrypted = defensiveCopy(governmentIssuedIdEncrypted);
        this.dateOfBirth = Objects.requireNonNull(dateOfBirth, "dateOfBirth must not be null");
        this.eftAccountId = Objects.requireNonNull(eftAccountId, "eftAccountId must not be null");
        this.primaryCardHolderIndicator = Objects.requireNonNull(primaryCardHolderIndicator,
                "primaryCardHolderIndicator must not be null");
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Copies an array so a caller's reference cannot reach the stored value.
     *
     * @param value the array to copy, which may be {@code null}
     * @return an independent copy, or {@code null} when the input was {@code null}
     */
    private static byte[] defensiveCopy(byte[] value) {
        return value == null ? null : value.clone();
    }

    /**
     * Returns the customer identifier.
     *
     * @return the customer identifier, never {@code null} on a persisted instance
     */
    public Long getCustomerId() {
        return this.customerId;
    }

    /**
     * Returns the first name.
     *
     * @return the first name, never {@code null} on a persisted instance
     */
    public String getFirstName() {
        return this.firstName;
    }

    /**
     * Returns the middle name.
     *
     * @return the middle name, or {@code null} when the reference left it blank
     */
    public String getMiddleName() {
        return this.middleName;
    }

    /**
     * Returns the last name.
     *
     * @return the last name, never {@code null} on a persisted instance
     */
    public String getLastName() {
        return this.lastName;
    }

    /**
     * Returns the first address line.
     *
     * @return the first address line, never {@code null} on a persisted instance
     */
    public String getAddressLine1() {
        return this.addressLine1;
    }

    /**
     * Returns the second address line.
     *
     * @return the second address line, or {@code null} when blank
     */
    public String getAddressLine2() {
        return this.addressLine2;
    }

    /**
     * Returns the third address line.
     *
     * @return the third address line, never {@code null} on a persisted instance
     */
    public String getAddressLine3() {
        return this.addressLine3;
    }

    /**
     * Returns the state code.
     *
     * @return the state code, never {@code null} on a persisted instance
     */
    public String getAddressStateCode() {
        return this.addressStateCode;
    }

    /**
     * Returns the country code.
     *
     * @return the country code, never {@code null} on a persisted instance
     */
    public String getAddressCountryCode() {
        return this.addressCountryCode;
    }

    /**
     * Returns the postal code.
     *
     * @return the postal code, never {@code null} on a persisted instance
     */
    public String getAddressZip() {
        return this.addressZip;
    }

    /**
     * Returns the primary phone number.
     *
     * @return the primary phone number, or {@code null} when the reference left it blank
     */
    public String getPhoneNumber1() {
        return this.phoneNumber1;
    }

    /**
     * Returns the secondary phone number.
     *
     * @return the secondary phone number, or {@code null} when blank
     */
    public String getPhoneNumber2() {
        return this.phoneNumber2;
    }

    /**
     * Returns the date of birth.
     *
     * @return the date of birth, never {@code null} on a persisted instance
     */
    public LocalDate getDateOfBirth() {
        return this.dateOfBirth;
    }

    /**
     * Returns the electronic-transfer account identifier.
     *
     * @return the transfer account identifier, never {@code null} on a persisted instance
     */
    public String getEftAccountId() {
        return this.eftAccountId;
    }

    /**
     * Returns whether this customer is the primary cardholder.
     *
     * @return the one-character indicator, never {@code null} on a persisted instance
     */
    public String getPrimaryCardHolderIndicator() {
        return this.primaryCardHolderIndicator;
    }

    /**
     * Returns the credit score.
     *
     * @return the credit score
     */
    public short getFicoCreditScore() {
        return this.ficoCreditScore;
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
     * Renders this row for a log line or a diagnostic, disclosing no cardholder detail.
     *
     * <p>Refactoring Rationale: the rendering an entity receives by default prints every field. On this
     * entity that would emit a name, a full postal address, two phone numbers, a date of birth, a credit
     * score and the raw bytes of two encrypted identifiers -- the single most disclosing default rendering
     * anywhere in this migration, and one that would reach a log on every persistence failure the provider
     * reports. Only the identifier and the version are carried: the identifier says which row and the
     * version says which revision, and neither is cardholder data.</p>
     *
     * @return a rendering carrying the identifier and the version only, never {@code null}
     */
    @Override
    public String toString() {
        return "Customer[customerId=" + this.customerId + ", version=" + this.version + ']';
    }
}
