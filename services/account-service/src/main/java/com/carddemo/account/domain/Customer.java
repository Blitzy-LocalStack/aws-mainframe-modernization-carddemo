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
 * {@code CUSTDATA} record whose header comment at line 2 declares that length. Its eighteen meaningful
 * fields sum to 332 bytes and {@code FILLER PIC X(168)} at line 23 pads the record out to 500. The filler
 * is dropped here.</p>
 *
 * <p>Assumptions: the filler is padding rather than data, so dropping it loses nothing. The region is
 * blank in every one of the fifty records of {@code app/data/ASCII/custdata.txt}, each of which is exactly
 * 500 bytes long, so there is no hidden overlay to preserve.</p>
 *
 * <p>Assumptions: {@code CVCUS01Y} is the contract for this context and {@code app/cpy/CUSTREC.cpy} is
 * not, even though the two declare a near-identical 500-byte {@code CUSTOMER-RECORD}. The customer master
 * reader settles it first-hand: {@code app/cbl/CBCUS01C.cbl} line 45 is {@code COPY CVCUS01Y.}, its file
 * record at lines 38 through 40 sums to 500 over a nine-digit key plus 491 bytes of data, and line 32
 * keys the cluster on that identifier. The other copybook names its date field
 * {@code CUST-DOB-YYYYMMDD} at its own line 19, without the two hyphens the extract actually carries, and
 * it is copied by {@code app/cbl/CBSTM03A.CBL} line 55 -- the statement generator, which belongs to the
 * reporting context rather than to this one. Taking offsets from it here would place the date and every
 * field after it at the wrong position while still compiling.</p>
 *
 * <h2>Target schema</h2>
 * <p>Assumptions: the shape below is the other half of a two-sided contract with
 * {@code db/migration/V1__account.sql}, which owns the DDL and is authored separately. It is restated
 * here so that the obligation is legible at the mapping site and not only in the migration. The
 * {@code account} schema itself is created by {@code data-migration/sql/V0__schemas_and_roles.sql}, which
 * is the exclusive authority for schemas, roles and grants.</p>
 *
 * <pre>
 * account.customers
 *   customer_id                BIGINT       NOT NULL   -- PRIMARY KEY, assigned rather than generated
 *   first_name                 VARCHAR(25)  NOT NULL
 *   middle_name                VARCHAR(25)             -- optional in the reference edit routine
 *   last_name                  VARCHAR(25)  NOT NULL
 *   addr_line_1                VARCHAR(50)  NOT NULL
 *   addr_line_2                VARCHAR(50)             -- optional in the reference edit routine
 *   addr_line_3                VARCHAR(50)  NOT NULL   -- carries the city
 *   addr_state_cd              CHAR(2)      NOT NULL
 *   addr_country_cd            CHAR(3)      NOT NULL
 *   addr_zip                   CHAR(10)     NOT NULL   -- NOT a date
 *   phone_num_1                VARCHAR(15)             -- punctuation-formatted, not fifteen digits
 *   phone_num_2                VARCHAR(15)             -- punctuation-formatted, not fifteen digits
 *   ssn_encrypted              BYTEA        NOT NULL
 *   govt_issued_id_encrypted   BYTEA
 *   dob                        DATE         NOT NULL   -- the ONE date in this record
 *   eft_account_id             CHAR(10)     NOT NULL   -- external transfer account, NOT a date
 *   pri_card_holder_ind        CHAR(1)      NOT NULL
 *   fico_credit_score          SMALLINT     NOT NULL   -- no range check; see the field below
 *   version                    BIGINT       NOT NULL DEFAULT 0
 *   CONSTRAINT pk_customers               PRIMARY KEY (customer_id)
 *   CONSTRAINT ck_customers_pri_card_holder_ind CHECK (pri_card_holder_ind IN ('Y', 'N'))
 *   -- no foreign key to account.accounts or account.card_xref
 * </pre>
 *
 * <p>Assumptions: the only value constraint in that shape is the one on the primary-holder indicator, and
 * it is safe against the migration load because all fifty seeded records carry Y. No range constraint is
 * declared on the credit score, and the field below records why one must never be added. Constraints are
 * declared in the migration and never here: an entity that also declared them would be a second statement
 * of the same fact, free to drift from the schema that actually enforces it.</p>
 *
 * <p>Assumptions: {@code @Table} names the table without qualifying it with a schema, matching
 * {@link Account} and {@link CardXref}. The qualifier is deliberately absent rather than forgotten: the
 * service pins every pooled connection's search path to the one schema it owns, so an unqualified name
 * resolves there or fails, and hard-coding the schema in the annotation would put the same fact in two
 * places that could then disagree.</p>
 *
 * <p>Assumptions: the two identifier columns are stored ENCRYPTED, as {@code byte[]} over {@code BYTEA},
 * and no accessor on this class returns either one at all. The reference holds both in clear --
 * {@code CUST-SSN PIC 9(09)} at {@code CVCUS01Y.cpy} line 17 and
 * {@code CUST-GOVT-ISSUED-ID PIC X(20)} at line 18 -- on a file the resource definition declares with
 * {@code READINTEG(UNCOMMITTED)} at {@code app/csd/CARDDEMO.CSD} line 53 and
 * {@code RECOVERY(NONE) FWDRECOVLOG(NO)} at line 59, in the {@code CUSTDAT} stanza that opens at line 50.
 * The baseline therefore stores personal identifiers in clear with no recovery journalling; the Java
 * stores them encrypted at rest and never returns them; and the divergence is documented rather than
 * silent. Trade-offs: queryability on the clear value is given up. No predicate, sort or index on this
 * column can address the identifier itself, so any lookup by identifier has to be designed around a
 * deterministic token instead. That cost is accepted because the alternative is holding the value in a
 * form that any read of the table discloses.</p>
 *
 * <p>Trade-offs: this class performs no encryption and no decryption. It holds bytes that are already
 * ciphertext and does nothing else with them, so a caller must encrypt before construction and decrypt
 * outside the domain. A JPA {@code AttributeConverter} doing the cryptography on this entity was the
 * obvious alternative and is not available: it would have to reach a key provider, and the architecture
 * test in {@code common-lib} forbids a class in a {@code domain} package from depending on an
 * infrastructure or transport root, both cloud provider SDK generations included. Keeping the boundary
 * costs an explicit conversion step at the mapper edge and buys a domain type that can be constructed and
 * asserted on with no key material anywhere in reach.</p>
 *
 * <p>Alternatives Considered: declaring the two ciphertext columns {@code @Basic(fetch = LAZY)} so that a
 * read that never touches an identifier would not fetch one. Rejected on two grounds. Lazy loading of a
 * basic attribute has no effect at all without bytecode enhancement, so the annotation would suggest a
 * guarantee the build does not deliver; and the underlying values are a nine-character identifier and a
 * twenty-character identifier, so their ciphertext is a matter of tens of bytes and there is no column
 * width here worth deferring.</p>
 *
 * <p>Assumptions: five columns are nullable and the rest are not, and the split comes from the reference
 * rather than from taste. {@code app/cbl/COACTUPC.cbl} states the distinction itself instead of leaving it
 * to be inferred: it edits the middle name through {@code 1235-EDIT-ALPHA-OPT} at line 1571, the optional
 * routine, while editing the first and last names through {@code 1225-EDIT-ALPHA-REQD} at lines 1563 and
 * 1579, the required one. A {@code NOT NULL} column on an optional field would refuse rows the reference
 * itself produces, which transformation rule T9 forbids because it is a behavioural change wearing the
 * costume of a tightened constraint.</p>
 *
 * <h2>Optimistic concurrency</h2>
 * <p>Refactoring Rationale: the {@code @Version} column replaces a manual before-image comparison the
 * reference already performs, so this expresses an existing check natively rather than adding a new one.
 * {@code app/cbl/COACTUPC.cbl} snapshots the whole pre-edit customer row into
 * {@code 10 ACUP-OLD-CUST-DATA.} at line 709, inside {@code 05 ACUP-OLD-DETAILS.} which opens at line 669
 * and ends where {@code 05 ACUP-NEW-DETAILS.} begins at line 757; the snapshot's last member is the
 * three-character credit score at lines 754 through 756. Paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC.} at line 4109 then compares the re-read row against that snapshot at
 * lines 4152 through 4191, exiting at line 4193, and is driven from lines 3947 and 3948. On a mismatch it
 * sets the flag declared as {@code 05 WS-DATACHANGED-FLAG PIC X(1).} at line 168, whose two conditions sit
 * at lines 169 and 170. The comparison exists precisely because the read-for-update lock was never held
 * across client think-time in a pseudo-conversational transaction, which is the same reason a version
 * column is the right native form: both detect a concurrent write after the fact rather than preventing
 * one.</p>
 *
 * <p>Assumptions: the reference's customer snapshot is COMPLETE where its account snapshot is not. All
 * eighteen non-filler fields of this record appear in the block at lines 709 through 756 and all seventeen
 * non-key fields are compared, whereas the account block at lines 670 through 708 carries no postal-code
 * member at all even though {@code ACCT-ADDR-ZIP PIC X(10)} exists at {@code app/cpy/CVACT01Y.cpy} line
 * 15. The asymmetry is worth recording because it says the account update screen never edited an account
 * postal code, so a reader comparing the two entities does not mistake the difference for an omission
 * here.</p>
 *
 * <p>Trade-offs: a version column is strictly MORE conservative than the comparison it replaces, and the
 * difference is observable. The reference compares the three name parts, the three address lines, the
 * state, the country and the government-issued identifier through {@code FUNCTION UPPER-CASE} at lines
 * 4152 through 4173, so a concurrent edit that only changed a letter's case counted as no change at all;
 * the postal code, both phone numbers, the national identifier, the date of birth, the transfer account,
 * the primary-holder indicator and the credit score are compared raw at lines 4168 through 4186. A version
 * counter increments on ANY committed update, case-only edits included, so it will report a conflict in a
 * narrow case where the reference reported none. Refusing a write that a concurrent case-only edit
 * overlapped is accepted as the safer direction to err, and the alternative -- reproducing the case-folded
 * field-by-field comparison in Java to preserve the blind spot exactly -- was rejected because it would
 * carry a silent lost-update window forward for the sake of matching it.</p>
 *
 * <p>Assumptions: the conflict this column raises is translated to a response elsewhere and must not be
 * translated here. The shared kernel's exception advice already answers an optimistic-lock failure with
 * HTTP 409 carrying the reference's own verbatim message, the one declared at
 * {@code app/cbl/COACTUPC.cbl} lines 521 and 522 on the {@code PIC X(75)} field at line 479. This class
 * only causes the exception at flush; it defines, catches, wraps and re-maps nothing, which is why no
 * exception type is declared or imported below. The reference's own failure path is the same shape: line
 * 4098 sets its locked-but-failed state and line 4100 issues {@code SYNCPOINT ROLLBACK}, which
 * transformation rule T5 carries over as ordinary exception propagation.</p>
 *
 * <h2>Shape decisions</h2>
 * <p>Alternatives Considered: mapping only an existence probe, since the account-context contract asks
 * this context nothing about a customer except whether the row is there. Rejected for the same reason the
 * account entity is not narrowed to three amounts: this is the context's customer master, and an entity
 * shaped to one caller's question would silently drop every unmapped column on a write. The contract
 * narrows what is published; the entity carries the row.</p>
 *
 * <p>Alternatives Considered: an association to {@link Account} or a foreign key toward it, so that a
 * customer could be navigated to its accounts. Rejected because this record holds no CardDemo account
 * identifier to navigate by. Its only field naming an account is {@code CUST-EFT-ACCOUNT-ID} at
 * {@code CVCUS01Y.cpy} line 20, and that is an EXTERNAL transfer account at another institution rather
 * than a reference into {@code account.accounts}. The join path is {@code CARD-XREF-RECORD} of
 * {@code app/cpy/CVACT03Y.cpy}, whose lines 6 and 7 carry the customer and account identifiers together,
 * and the view program reaches its three rows as three separate keyed reads --
 * {@code app/cbl/COACTVWC.cbl} lines 727 and 728, 776 and 777, and 826 and 827 -- never as a navigation.
 * A foreign key would additionally reject rows the baseline accepts: the only one declared anywhere in the
 * reference is in the transaction-type extension, at
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} lines 6 and 7, which belongs to another context
 * entirely, and every file stanza in this one is defined {@code RECOVERY(NONE) JOURNAL(NO)}.</p>
 *
 * <p>Alternatives Considered: generating the accessors with an annotation processor, or declaring this
 * type as a {@code record}. Both were rejected on the same ground and the ground is mechanical rather than
 * stylistic. Generated accessors cannot carry the Javadoc that
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md} requires of every method, and the documentation gate is
 * configured to exempt nothing, so a generated class would either fail the gate or have to be suppressed
 * out of it. A {@code record} additionally cannot serve here at all: the persistence provider needs a
 * no-argument constructor and non-final fields, neither of which a record has.</p>
 *
 * <p>Assumptions: nothing is imported from the shared kernel, and the absence is deliberate rather than an
 * oversight. This record carries no monetary amount, no twenty-six-character timestamp, and no error or
 * pagination concern, so none of the shared money, codec, error or paging types has anything to contribute
 * to it. Keyset paging over this table belongs to the repository, not to the entity.</p>
 */
@Entity
@Table(name = "customers")
public class Customer {

    /**
     * The marker standing in for a protected identifier wherever this row is rendered.
     */
    // WHY : Assumptions: a single shared constant rather than a literal written at each use site, so that
    //   the two protected fields cannot drift into rendering differently and so that a search for the
    //   marker finds every place a value is withheld. It is deliberately a fixed string that says nothing
    //   about the value it replaces: a marker that varied with length or presence would leak exactly the
    //   kind of detail the encryption is there to withhold.
    private static final String REDACTED = "[REDACTED]";

    /**
     * The customer identifier, {@code CUST-ID PIC 9(09)}.
     */
    // WHY : Assumptions: the key is ASSIGNED and never generated, which is why no generation strategy is
    //   declared. CVCUS01Y.cpy line 5 makes this a nine-digit business identifier, the cluster keys on it
    //   per app/cbl/CBCUS01C.cbl line 32, and the migration loads it straight from the source record. A
    //   generation strategy would overwrite real identity on load and break every cross-reference row that
    //   points at it. It is marked non-updatable for the same reason: a key that arrives with the data is
    //   not the application's to change.
    @Id
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    /**
     * The customer's first name, {@code CUST-FIRST-NAME PIC X(25)}.
     */
    // WHY : Assumptions: the three name parts and the three address lines are VARCHAR while the state,
    //   country, postal, phone, transfer-account and indicator columns are CHAR, and the split follows one
    //   principle rather than two. A descriptive field's declared width is a maximum -- a name shorter than
    //   twenty-five characters is a shorter name, not a defective one -- so its trailing blanks in the
    //   fixed-width record are padding and are not stored. A code's width is part of its contract, so its
    //   padding is preserved. Every seeded value here is blank-padded to the declared width, which is what
    //   makes the distinction a decision rather than an observation, and the trimming itself happens at the
    //   migration and mapper edge rather than in this class.
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
     * The third address line, {@code CUST-ADDR-LINE-3 PIC X(50)}, which carries the city.
     */
    // WHY : Assumptions: this line IS the city, and it is the only place a city is held, because the
    //   record declares no city field. A search for CUST-CITY returns nothing in app/cpy/, nothing in
    //   app/cbl/CBCUS01C.cbl and nothing anywhere under app/. The extract settles what the name leaves
    //   ambiguous: position 185 through 234 of every record in app/data/ASCII/custdata.txt holds a city
    //   name, against a street address on the first line and a unit on the second. A screen field labelled
    //   city therefore maps here, and an implementation that looked for a dedicated column would find none
    //   and would be tempted to invent one.
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
     * The postal code, {@code CUST-ADDR-ZIP PIC X(10)}, which is text and NOT a date.
     */
    // WHY : Assumptions: this field is one of THREE PIC X(10) fields in this record, and it is not the
    //   date. Only CUST-DOB-YYYY-MM-DD at CVCUS01Y.cpy line 19 is; this one at line 14 and
    //   CUST-EFT-ACCOUNT-ID at line 20 share the width and nothing else. The extract distinguishes them by
    //   punctuation position: a date carries hyphens at positions 5 and 8, whereas this field holds either
    //   a five-digit code padded to ten or a nine-digit form whose single hyphen sits at position 6. No
    //   record in app/data/ASCII/custdata.txt has a hyphen at position 5 here, so the two field kinds are
    //   distinguishable by punctuation alone. A date mapping would reject every value in the extract, and a
    //   numeric mapping would discard both the hyphen of the longer form and any leading zero.
    // WHY : Trade-offs: CHAR rather than VARCHAR, so a read returns the value blank-padded to ten. A
    //   postal code is a fixed-width code rather than free text, and the padding is part of that contract;
    //   trimming belongs at the mapper edge, where the anti-corruption layer already sits, rather than in
    //   an entity whose job is to carry the row as stored.
    @Column(name = "addr_zip", length = 10, nullable = false)
    private String addressZip;

    /**
     * The primary phone number, {@code CUST-PHONE-NUM-1 PIC X(15)}, punctuation-formatted and optional.
     */
    // WHY : Assumptions: these fifteen characters are FORMATTED TEXT, not fifteen digits, so the column is
    //   character-typed and the value is stored exactly as the reference holds it. app/cbl/COACTUPC.cbl
    //   redefines the before-image of this field at lines 723 through 731 as a one-byte filler, three
    //   digits, a one-byte filler, three digits, a one-byte filler, four digits and a two-byte filler --
    //   fifteen bytes in which four are punctuation or pad -- and does the same for the second number at
    //   lines 733 through 741. Every record in app/data/ASCII/custdata.txt agrees: an opening parenthesis
    //   at position 1, a closing one at position 5, a hyphen at position 9, and two trailing blanks. Not
    //   one value is purely numeric.
    // WHY : Trade-offs: the punctuation is deliberately NOT stripped and the value is deliberately NOT
    //   modelled as a number. Normalising to ten digits here would be the tidier representation and is
    //   rejected: the stored form is what the reference reads back and what a report renders, so
    //   normalisation would have to be undone on every read, and a numeric type would additionally destroy
    //   the leading digit of an area code beginning with zero. Any reformatting belongs at the mapper edge.
    @Column(name = "phone_num_1", length = 15)
    private String phoneNumber1;

    /**
     * The secondary phone number, {@code CUST-PHONE-NUM-2 PIC X(15)}, punctuation-formatted and optional.
     */
    // WHY : Assumptions: identical in form and treatment to the primary number above, from the same
    //   redefinition at app/cbl/COACTUPC.cbl lines 733 through 741. It is declared separately rather than
    //   folded into a collection because the reference declares two discrete fields at fixed offsets, and
    //   a collection would introduce an arity the 500-byte record cannot express.
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
    // WHY : Trade-offs: the reference declares this field NUMERIC, PIC 9(09) at CVCUS01Y.cpy line 17, and
    //   the target carries it as bytes. The representation change is deliberate and the extract shows why
    //   the plaintext must be handled as a nine-CHARACTER zero-padded string before it is ever encrypted:
    //   6 of the 50 records in app/data/ASCII/custdata.txt begin with a zero, which any integer type drops
    //   silently. Encrypting the numeric value rather than its padded character form would therefore
    //   produce ciphertext that decrypts to a different identifier than the one the reference stored, and
    //   nothing downstream could detect it.
    @Column(name = "ssn_encrypted", nullable = false)
    private byte[] ssnEncrypted;

    /**
     * The encrypted government-issued identifier, the protected form of
     * {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     *
     * <p>Assumptions: nullable, because the reference leaves the field optional, and likewise without a
     * public accessor.</p>
     */
    // WHY : Trade-offs: as with the national identifier above, the plaintext is significant to its full
    //   declared width and must be encrypted as text rather than as a number. CVCUS01Y.cpy line 18 declares
    //   twenty characters, and every one of the fifty seeded values is left-padded with zeros to fill them,
    //   so a numeric round trip would return a shorter and different identifier.
    @Column(name = "govt_issued_id_encrypted")
    private byte[] governmentIssuedIdEncrypted;

    /**
     * The date of birth, {@code CUST-DOB-YYYY-MM-DD PIC X(10)}, the ONE date in this record.
     */
    // WHY : Assumptions: this is the only one of the record's three PIC X(10) fields that holds a date, and
    //   the reference proves the character layout rather than merely implying it. app/cbl/COACTUPC.cbl
    //   compares the master field against an eight-character before-image at lines 4174 through 4179,
    //   slicing the master at (1:4), (6:2) and (9:2) while slicing the before-image at (1:4), (5:2) and
    //   (7:2). Master positions 5 and 8 are skipped in that comparison precisely because they hold the two
    //   hyphens, and the offset asymmetry between the ten-character and eight-character forms is explicit
    //   in the source. Every record in app/data/ASCII/custdata.txt carries hyphens at exactly those two
    //   positions.
    // WHY : Assumptions: the target name drops the format suffix the reference field carries. The suffix
    //   describes the CHARACTER representation of a fixed-width text field, so once the column is a true
    //   date it would actively misdescribe what is stored; recording a representation change in the target
    //   name is the same convention that turns the national identifier into an encrypted column. The
    //   mapping is behaviour-preserving because the stored order is ISO, so the reference's lexical
    //   comparison and a date comparison order values identically.
    @Column(name = "dob", nullable = false)
    private LocalDate dateOfBirth;

    /**
     * The electronic-transfer account identifier, {@code CUST-EFT-ACCOUNT-ID PIC X(10)}, text and NOT a
     * date.
     */
    // WHY : Assumptions: the third of the three PIC X(10) fields, at CVCUS01Y.cpy line 20, and like the
    //   postal code above it is not the date. It is an account at ANOTHER institution used for electronic
    //   transfer, not a reference into account.accounts, which is why no association is declared for it.
    // WHY : Trade-offs: String over any numeric type, even though every seeded value is ten digits. The
    //   leading digit is zero in all fifty records of app/data/ASCII/custdata.txt, so a numeric type would
    //   silently drop it and change the identifier; an external account number is an opaque token to be
    //   carried byte-for-byte rather than a quantity to be computed on.
    @Column(name = "eft_account_id", length = 10, nullable = false)
    private String eftAccountId;

    /**
     * Whether this customer is the primary cardholder, {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}.
     */
    // WHY : Alternatives Considered: a boolean or an enum, since the schema constrains this column to Y or
    //   N and every one of the fifty seeded records carries Y. Both were rejected in favour of the
    //   one-character string the reference declares. CVCUS01Y.cpy line 21 states only PIC X(01) and
    //   attaches no 88-level value set -- there is not one such level anywhere in this record -- and
    //   app/cbl/COACTUPC.cbl compares the field through FUNCTION TRIM at lines 1764 and 1766 rather than
    //   against a value list. Contrast the account status, whose Y-or-N domain the reference does state
    //   outright, in the message at lines 503 and 504. A boolean would also have to invent a mapping for
    //   any third character the reference would accept, and an enum would fail to construct on one rather
    //   than carrying it.
    @Column(name = "pri_card_holder_ind", length = 1, nullable = false)
    private String primaryCardHolderIndicator;

    /**
     * The credit score, {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}, carrying no range constraint.
     *
     * <p>Assumptions: a sixteen-bit integer over {@code SMALLINT}, because the reference bounds the value
     * at three digits at {@code CVCUS01Y.cpy} line 22 and a wider type would admit values no reference
     * program could produce.</p>
     */
    // WHY : Assumptions: the column carries NO range constraint, and the omission is deliberate. The
    //   industry floor for this kind of score is 300, so a CHECK BETWEEN 300 AND 850 is exactly the
    //   reasonable-looking constraint a reviewer would expect to find here. It must not be added: the
    //   scores in app/data/ASCII/custdata.txt run from 1 upward, and 21 of the 50 records fall below 300.
    //   Such a constraint would reject those 21 rows and break the migration load outright, so the widest
    //   admissible value set is the three-digit range the picture clause already states and nothing
    //   narrower.
    @Column(name = "fico_credit_score", nullable = false)
    private short ficoCreditScore;

    /**
     * The optimistic-concurrency counter.
     */
    // WHY : Assumptions: the provider owns this value entirely -- it sets it on insert and increments it on
    //   every update -- so nothing in this class or above it assigns to it, and the constructor does not
    //   take it. That is what makes it a faithful stand-in for the reference's before-image comparison
    //   described on the class above: neither mechanism is something a caller can supply or forge.
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
     * record has eighteen meaningful fields and the schema marks thirteen of them {@code NOT NULL}, so a
     * builder would make every one of those omittable at the call site and would move the completeness
     * check from the compiler to a runtime constraint violation. The positional form is harder to read
     * once and impossible to get wrong silently.</p>
     *
     * <p>Assumptions: twelve arguments are null-checked here rather than thirteen, and the difference is
     * not an omission. The credit score is the one {@code NOT NULL} column whose Java type is a primitive,
     * so the compiler already guarantees a value for it and a runtime check would be unreachable. The
     * version counter is absent from this list entirely because the persistence provider owns it.</p>
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
     * <p>Trade-offs: the two protected identifiers are named with a fixed marker rather than left out of
     * the rendering altogether. Omitting them entirely would disclose no less, but it would also leave a
     * reader unable to tell a deliberate withholding from a field somebody forgot, and it would let a later
     * edit that reinstated a real value pass as an addition rather than as a regression. Naming them and
     * withholding the value states the decision in the output itself, and the marker is a constant, so
     * nothing derived from the ciphertext -- not its bytes, not its length, not whether it is present --
     * can reach a log through this method.</p>
     *
     * @return a rendering carrying the identifier, the version and two redaction markers, never
     *     {@code null}
     */
    @Override
    public String toString() {
        return "Customer[customerId=" + this.customerId
                + ", ssn=" + REDACTED
                + ", governmentIssuedId=" + REDACTED
                + ", version=" + this.version + ']';
    }
}
