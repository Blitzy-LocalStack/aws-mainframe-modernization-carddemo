package com.carddemo.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;

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
 * reader settles it: {@code app/cbl/CBCUS01C.cbl} line 45 is {@code COPY CVCUS01Y.}. The other copybook
 * names its date field without the two hyphens the extract actually carries, and it is copied by the
 * statement generator, which belongs to the reporting context. Taking offsets from it here would place the
 * date and every field after it at the wrong position while still compiling.</p>
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
 *   -- no check constraint: no field of CUSTOMER-RECORD declares a value set
 *   -- no foreign key to account.accounts or account.card_xref
 * </pre>
 *
 * <p>Assumptions: that shape declares NO value constraint at all, and the absence is deliberate on both
 * columns where one might be expected. No range constraint bounds the credit score, and the field below
 * records why one must never be added. No closed domain bounds the primary-holder indicator either:
 * {@code CVCUS01Y.cpy} L21 declares {@code PIC X(01)} and attaches no 88-level value set -- there is not one
 * such level anywhere in this record -- so the storage layer admits any one character, exactly as the VSAM
 * cluster it replaces does. Constraints are declared in the migration and never here: an entity that also
 * declared them would be a second statement of the same fact, free to drift from the schema that actually
 * enforces it.</p>
 *
 * <p>Refactoring Rationale: a check closing the indicator at Y and N was declared and is removed. Its
 * authority was {@code FLG-PRI-CARDHOLDER-ISVALID VALUES 'Y', 'N'} at {@code app/cbl/COACTUPC.cbl:350},
 * which sits on {@code WS-EDIT-PRI-CARDHOLDER} -- a WORKING-STORAGE edit flag in one online program -- and
 * not on the record field, so it states what that screen ACCEPTS rather than what the file HOLDS. The
 * migration plan's transformation rule T1 makes the copybook normative, and its section 0.4.1.3 turns an
 * 88-level value set into a check constraint; neither licenses a domain the record layout does not state.
 * The Y-or-N rule itself is preserved where the reference applies it: {@code 1220-EDIT-YESNO} at L1856 to
 * L1894, migrated as {@code AccountUpdateService.editYesNo} and applied to this field on every update, so a
 * submission still cannot set a third character while a loaded record carrying one is no longer refused.</p>
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
 * {@code 10 ACUP-OLD-CUST-DATA.} at line 709, inside the before-image block that opens at line 669.
 * Paragraph {@code 9700-CHECK-CHANGE-IN-REC.} at line 4109 then compares the re-read row against that
 * snapshot and, on a mismatch, sets {@code WS-DATACHANGED-FLAG} at line 168 so the write is abandoned. The
 * comparison exists precisely because the read-for-update lock was never held
 * across client think-time in a pseudo-conversational transaction, which is the same reason a version
 * column is the right native form: both detect a concurrent write after the fact rather than preventing
 * one.</p>
 *
 * <p>Assumptions: the reference's customer snapshot is COMPLETE where its account snapshot is not -- all
 * eighteen non-filler fields of this record are snapshotted and all seventeen non-key fields compared,
 * whereas the account block carries no postal-code member even though {@code ACCT-ADDR-ZIP PIC X(10)}
 * exists at {@code app/cpy/CVACT01Y.cpy} line 15. The asymmetry says the account update screen never
 * edited an account postal code, so a reader comparing the two entities does not mistake the difference
 * for an omission here.</p>
 *
 * <p>Trade-offs: a version column is strictly MORE conservative than the comparison it replaces, and the
 * difference is observable. The reference compares the three name parts, the three address lines, the
 * state, the country and the government-issued identifier through {@code FUNCTION UPPER-CASE}, so a
 * concurrent edit that changed only a letter's case counted as no change at all, while a version counter
 * increments on ANY committed update. Refusing a write that a concurrent case-only edit overlapped is the
 * safer direction to err; reproducing the case-folded comparison in Java to preserve the blind spot
 * exactly was rejected because it would carry a silent lost-update window forward for the sake of matching
 * it. The divergence is registered as {@code D-UPDATE-CASE-SENSITIVE-COMPARE} in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: the conflict this column raises is translated to a response elsewhere and must not be
 * translated here. The shared kernel's exception advice already answers an optimistic-lock failure with
 * HTTP 409 carrying the reference's own verbatim message, the one declared at
 * {@code app/cbl/COACTUPC.cbl} lines 521 and 522 on the {@code PIC X(75)} field at line 479. This class
 * only causes the exception at flush; it defines, catches, wraps and re-maps nothing, which is why no
 * exception type is declared or imported below. The reference's own failure path is the same shape -- it
 * sets a locked-but-failed state and issues {@code SYNCPOINT ROLLBACK} -- which transformation rule T5
 * carries over as ordinary exception propagation.</p>
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
 * and the view program reaches its three rows as three separate keyed reads rather than as a navigation. A
 * foreign key would additionally reject rows the baseline accepts: the only one declared anywhere in the
 * reference belongs to the transaction-type extension in another context, and every file stanza in this
 * one is defined {@code RECOVERY(NONE) JOURNAL(NO)}.</p>
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
    // WHY : Assumptions: CHAR(2) in V1__account.sql, and this value is compared against the seeded
    //       reference state table rather than merely displayed, so the fixed-character binding is
    //       what keeps the comparison agreeing with the stored form.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "addr_state_cd", length = 2, nullable = false)
    private String addressStateCode;

    /**
     * The country code, {@code CUST-ADDR-COUNTRY-CD PIC X(03)}.
     */
    // WHY : Assumptions: CHAR(3) in V1__account.sql; the override is repeated per member because
    //       Hibernate infers the JDBC type independently for each one.
    @JdbcTypeCode(Types.CHAR)
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
    // WHY : Assumptions: the column is CHAR(10) in V1__account.sql, so a five-digit postal code is
    //       stored blank-padded to ten. Left as VARCHAR the padded stored value and an unpadded
    //       parameter would be different strings under PostgreSQL's text comparison rules, so a
    //       lookup would silently miss rather than fail loudly.
    @JdbcTypeCode(Types.CHAR)
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
    // WHY : Assumptions: CHAR(10) in V1__account.sql. The reference declares this field as ten
    //       characters and its seeded values are zero-filled to that width, so the padding is part
    //       of the value rather than an artefact of storage.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "eft_account_id", length = 10, nullable = false)
    private String eftAccountId;

    /**
     * Whether this customer is the primary cardholder, {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}.
     */
    // WHY : Alternatives Considered: a boolean or an enum, since every one of the fifty seeded records
    //   carries Y and the online screen accepts only Y or N. Both were rejected in favour of the
    //   one-character string the reference declares. CVCUS01Y.cpy line 21 states only PIC X(01) and
    //   attaches no 88-level value set -- there is not one such level anywhere in this record -- and
    //   app/cbl/COACTUPC.cbl compares the field through FUNCTION TRIM at lines 1764 and 1766 rather than
    //   against a value list. A boolean would have to invent a mapping for any third character the file
    //   can hold, and an enum would fail to construct on one rather than carrying it.
    // WHY : Refactoring Rationale: this comment used to say the schema constrains the column to Y or N,
    //   and used to contrast it with the account status as a domain "the reference does state outright".
    //   Both halves were wrong in the same direction. The check has been removed from V1__account.sql
    //   because the value set it enforced is declared on an EDIT FLAG in one online program and not on the
    //   record field, and the account status is in the same position -- its own value set sits on
    //   WS-EDIT-ACCT-STATUS at app/cbl/COACTUPC.cbl:193 -- so the contrast asserted a difference that does
    //   not exist. That neighbouring constraint is left in place because this correction was scoped to
    //   this column, and the asymmetry is recorded here rather than left for a reader to trip over.
    // WHY : Assumptions: CHAR(1) in V1__account.sql, and UNCONSTRAINED. The reference's Y-or-N rule is
    //       enforced on the update path by AccountUpdateService.editYesNo, which is where
    //       1220-EDIT-YESNO enforces it. VARCHAR(1) would validate against a different declared type and
    //       would compare under text rules.
    @JdbcTypeCode(Types.CHAR)
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
     * Applies an edited customer state onto this MANAGED row, leaving the provider to own the version.
     *
     * <p>Purpose: this is the migrated form of the write-back at {@code app/cbl/COACTUPC.cbl} L4009
     * through L4046, which moves each {@code ACUP-NEW-*} field into the corresponding
     * {@code CUST-UPDATE-*} field of the record it read and then rewrites that record. It mutates the
     * loaded row for exactly the reason the reference rewrites the read record rather than building a
     * fresh one: the row's identity and its concurrency state belong to the row, not to the submission.
     * </p>
     *
     * <p>Alternatives Considered: constructing a new {@code Customer} from the request and saving that.
     * Rejected because it resets {@code version} to zero, which defeats the optimistic check entirely --
     * the provider would either insert a duplicate or overwrite whatever version it found, leaving the
     * concurrent-change detection the reference performs with its before-image no target form at all.
     * Mutating the managed row is what makes {@code @Version} do the work, because the version the
     * provider compares is then the one it loaded.</p>
     *
     * <p>Assumptions: the two PROTECTED identifiers are supplied as explicit intents rather than as
     * values, which is why they are the only two parameters that are not plain fields. A blank or
     * omitted protected value cannot be distinguished from "leave it alone" by looking at the value,
     * because the submitter is shown a mask and never the ciphertext. A value-shaped parameter has no
     * representation for that difference, and the only readings left to it are destructive ones: an
     * omitted government-issued identifier resolves to {@code null} and DELETES the stored ciphertext,
     * and the national identifier is re-enciphered on every update whether or not it was edited. The
     * intent types make preserve, replace and clear three separate things a caller must choose
     * between.</p>
     *
     * <p>Assumptions: the national identifier admits only PRESERVE and REPLACE and never CLEAR, because
     * its column is declared {@code NOT NULL}; the government-issued identifier admits all three,
     * because its column is nullable and {@code app/cbl/COACTUPC.cbl} L1403 moves low values into the
     * field when the screen field is empty, which is the baseline's own encoding of an absent value.</p>
     *
     * @param editedFirstName the first name to store; must not be {@code null}
     * @param editedMiddleName the middle name to store, or {@code null} for the optional absent state
     * @param editedLastName the last name to store; must not be {@code null}
     * @param editedAddressLine1 the first address line to store; must not be {@code null}
     * @param editedAddressLine2 the second address line to store, or {@code null} when absent
     * @param editedAddressLine3 the third address line to store; must not be {@code null}
     * @param editedStateCode the state code to store; must not be {@code null}
     * @param editedCountryCode the country code to store; must not be {@code null}
     * @param editedAddressZip the postal code to store at its full stored width; must not be
     *     {@code null}
     * @param editedPhoneNumber1 the first telephone number to store, or {@code null} when absent
     * @param editedPhoneNumber2 the second telephone number to store, or {@code null} when absent
     * @param nationalIdentifier what to do with the national identifier; must not be {@code null}, and
     *     must not be a clearing intent because the column is declared not null
     * @param governmentIdentifier what to do with the government-issued identifier; must not be
     *     {@code null}
     * @param editedDateOfBirth the date of birth to store; must not be {@code null}
     * @param editedEftAccountId the transfer account identifier to store; must not be {@code null}
     * @param editedPrimaryCardHolderIndicator the primary-holder indicator to store; must not be
     *     {@code null}
     * @param editedFicoCreditScore the credit score to store
     * @throws NullPointerException if a parameter documented as required is {@code null}
     * @throws IllegalArgumentException if {@code nationalIdentifier} is a clearing intent
     */
    public void applyUpdate(String editedFirstName, String editedMiddleName, String editedLastName,
            String editedAddressLine1, String editedAddressLine2, String editedAddressLine3,
            String editedStateCode, String editedCountryCode, String editedAddressZip,
            String editedPhoneNumber1, String editedPhoneNumber2,
            ProtectedValueUpdate nationalIdentifier, ProtectedValueUpdate governmentIdentifier,
            LocalDate editedDateOfBirth, String editedEftAccountId,
            String editedPrimaryCardHolderIndicator, short editedFicoCreditScore) {
        Objects.requireNonNull(nationalIdentifier, "nationalIdentifier must not be null");
        Objects.requireNonNull(governmentIdentifier, "governmentIdentifier must not be null");
        if (nationalIdentifier.clears()) {
            throw new IllegalArgumentException("the national identifier cannot be cleared, because"
                    + " ssn_encrypted is declared NOT NULL");
        }

        this.firstName = Objects.requireNonNull(editedFirstName, "editedFirstName must not be null");
        this.middleName = editedMiddleName;
        this.lastName = Objects.requireNonNull(editedLastName, "editedLastName must not be null");
        this.addressLine1 =
                Objects.requireNonNull(editedAddressLine1, "editedAddressLine1 must not be null");
        this.addressLine2 = editedAddressLine2;
        this.addressLine3 =
                Objects.requireNonNull(editedAddressLine3, "editedAddressLine3 must not be null");
        this.addressStateCode =
                Objects.requireNonNull(editedStateCode, "editedStateCode must not be null");
        this.addressCountryCode =
                Objects.requireNonNull(editedCountryCode, "editedCountryCode must not be null");
        this.addressZip =
                Objects.requireNonNull(editedAddressZip, "editedAddressZip must not be null");
        this.phoneNumber1 = editedPhoneNumber1;
        this.phoneNumber2 = editedPhoneNumber2;

        // WHY : Assumptions: a PRESERVE intent leaves the field untouched rather than reassigning it to
        //       its own current value. The two are equivalent in the stored result, but only the
        //       untouched form is equivalent in the DIRTY-CHECKING result: reassigning a byte array
        //       makes the provider see a changed field and issue an update for a column nothing edited,
        //       which advances the version and would make a concurrent reader's precondition fail for a
        //       change that never happened.
        if (nationalIdentifier.replaces()) {
            this.ssnEncrypted = defensiveCopy(nationalIdentifier.ciphertext());
        }
        if (governmentIdentifier.replaces()) {
            this.governmentIssuedIdEncrypted = defensiveCopy(governmentIdentifier.ciphertext());
        } else if (governmentIdentifier.clears()) {
            this.governmentIssuedIdEncrypted = null;
        }

        this.dateOfBirth =
                Objects.requireNonNull(editedDateOfBirth, "editedDateOfBirth must not be null");
        this.eftAccountId =
                Objects.requireNonNull(editedEftAccountId, "editedEftAccountId must not be null");
        this.primaryCardHolderIndicator = Objects.requireNonNull(editedPrimaryCardHolderIndicator,
                "editedPrimaryCardHolderIndicator must not be null");
        this.ficoCreditScore = editedFicoCreditScore;
    }

    /**
     * What an update intends for one protected identifier: preserve it, replace it, or clear it.
     *
     * <p>Purpose: the three intents exist because a protected column cannot express them as values. The
     * submitter is shown a mask, never the ciphertext, so an absent submitted value means "I did not
     * edit this" and NOT "store nothing" -- and a value-shaped parameter cannot hold that difference.
     * Resolving it by value is what deleted stored ciphertext: an omitted government-issued identifier
     * arrived as {@code null} and was written as {@code null}, destroying a value the submitter had
     * never been shown and could not have re-supplied.</p>
     *
     * <p>Assumptions: the three intents are a sealed set with a private constructor and three named
     * factories, so a caller states which one it means and no fourth state can be constructed. A boolean
     * pair would admit a fourth combination that means nothing.</p>
     *
     * <p>Trade-offs: {@code clear} is offered even though only one of the two columns admits it, and
     * {@link #applyUpdate} refuses it for the other rather than the type doing so. One type for both
     * columns keeps the caller's shape uniform; the refusal names the column, which a type-level split
     * could not do as clearly.</p>
     *
     * <p>Refactoring Rationale: this type used to hold the caller's ciphertext array by ALIAS and hand the
     * same array back, so an intent was not immutable in the one component that carries a protected value.
     * A caller reusing a buffer across two identifiers -- or mutating one after stating its intent -- could
     * therefore have a value written that it never declared, and nothing in the type or the entity would
     * report the substitution. Both the constructor and the accessor copy now, so the intent is fixed at
     * construction whichever side the array is touched from.</p>
     */
    public static final class ProtectedValueUpdate {

        /** The preserve intent, which carries no ciphertext and is stateless, so one instance serves. */
        private static final ProtectedValueUpdate PRESERVE = new ProtectedValueUpdate(null, false);

        /** The clear intent, likewise stateless. */
        private static final ProtectedValueUpdate CLEAR = new ProtectedValueUpdate(null, true);

        /** The replacement ciphertext, or {@code null} for the preserve and clear intents. */
        private final byte[] replacement;

        /** Whether this intent clears the column. */
        private final boolean clearing;

        /**
         * Creates one intent, taking its own copy of any ciphertext.
         *
         * <p>Assumptions: the array is COPIED here rather than retained. An array is mutable, so retaining
         * the caller's would leave the value this intent declares changeable after the declaration was
         * made -- by the caller, deliberately or by reusing a working buffer -- and the value finally
         * stored would then be whatever the array held at write time rather than what the caller stated.
         * Copying at construction is what makes this type's name true: it is an INTENT, fixed when it is
         * created.</p>
         *
         * @param ciphertext the replacement ciphertext, or {@code null} when this intent replaces nothing
         * @param clears whether this intent clears the column
         */
        private ProtectedValueUpdate(byte[] ciphertext, boolean clears) {
            this.replacement = defensiveCopy(ciphertext);
            this.clearing = clears;
        }

        /**
         * The intent that leaves the stored ciphertext exactly as it is.
         *
         * @return the preserve intent, never {@code null}
         */
        public static ProtectedValueUpdate preserve() {
            return PRESERVE;
        }

        /**
         * The intent that writes new ciphertext over whatever is stored.
         *
         * @param ciphertext the ciphertext to store; must not be {@code null} or empty
         * @return the replace intent, never {@code null}
         * @throws NullPointerException if {@code ciphertext} is {@code null}
         * @throws IllegalArgumentException if {@code ciphertext} is empty, which would store a value
         *     indistinguishable from a protected one while protecting nothing
         */
        public static ProtectedValueUpdate replaceWith(byte[] ciphertext) {
            Objects.requireNonNull(ciphertext, "ciphertext must not be null");
            if (ciphertext.length == 0) {
                throw new IllegalArgumentException("ciphertext must not be empty; use clear() to remove"
                        + " a stored value, so that removing and protecting nothing stay distinct");
            }
            return new ProtectedValueUpdate(ciphertext, false);
        }

        /**
         * The intent that removes the stored ciphertext, for a nullable protected column only.
         *
         * @return the clear intent, never {@code null}
         */
        public static ProtectedValueUpdate clear() {
            return CLEAR;
        }

        /**
         * Whether this intent writes new ciphertext.
         *
         * @return {@code true} when this intent replaces the stored value
         */
        boolean replaces() {
            return this.replacement != null;
        }

        /**
         * Whether this intent removes the stored ciphertext.
         *
         * @return {@code true} when this intent clears the column
         */
        boolean clears() {
            return this.clearing;
        }

        /**
         * The replacement ciphertext.
         *
         * <p>Assumptions: a COPY is returned, for the same reason the constructor takes one. Handing out the
         * held array would let a reader alter what a later read of this intent reports, which is the whole
         * property the constructor's copy establishes -- one leak on either side is enough to lose it.</p>
         *
         * <p>Trade-offs: the value is copied twice on the write path, once here and once by
         * {@link #applyUpdate}, and the second copy is deliberately kept. That one is the ENTITY's own
         * invariant and also guards the constructor path, which takes raw arrays from a mapper; removing it
         * would make the entity's protection depend on this nested type's accessor, so the two are held
         * independently. The cost is one clone of a short ciphertext per protected column per update.</p>
         *
         * @return a copy of the ciphertext this intent stores, or {@code null} when it replaces nothing
         */
        byte[] ciphertext() {
            return defensiveCopy(this.replacement);
        }

        /**
         * Renders the intent without its ciphertext.
         *
         * <p>Assumptions: the ciphertext is withheld even though it is not plaintext, because its
         * LENGTH is derived from the identifier's length and this type is the kind of small value a
         * diagnostic prints whole.</p>
         *
         * @return the intent name, never {@code null}
         */
        @Override
        public String toString() {
            if (this.clearing) {
                return "ProtectedValueUpdate[clear]";
            }
            return this.replacement == null
                    ? "ProtectedValueUpdate[preserve]"
                    : "ProtectedValueUpdate[replace]";
        }
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
     * reports. All of that was already withheld.</p>
     *
     * <p>Refactoring Rationale: what was NOT withheld was the CUSTOMER IDENTIFIER, carried on the
     * reasoning that it says which row and is not cardholder data. The sensitive-data logging contract in
     * {@code docs/architecture/observability.md} names customer identifiers explicitly among the
     * prohibited values, so that reasoning does not hold, and the first part of the rule it states is that
     * a prohibited value is OMITTED rather than abbreviated. The identifier is therefore gone, and the
     * version counter -- which that same rule admits by name -- is what remains to say which revision was
     * in hand.</p>
     *
     * <p>Trade-offs: the two protected identifiers are still NAMED with a fixed marker rather than left
     * out of the rendering altogether, and that is a deliberate exception to the omit-rather-than-mask
     * rule with a specific justification. The rule exists to stop a protected VALUE being disclosed in
     * part; these two markers are constants that carry nothing derived from the ciphertext -- not its
     * bytes, not its length, not whether the optional government-issued identifier is present at all --
     * so they disclose strictly nothing. What naming them buys is that a reader can tell a deliberate
     * withholding from a field somebody forgot, and that a later edit reinstating a real value reads as a
     * regression rather than as an addition. That is the whole of the difference from the customer
     * identifier above, which is omitted outright because any rendering of it would be the value.</p>
     *
     * @return a rendering carrying the version and two constant redaction markers, and neither the
     *     customer identifier nor any cardholder detail, never {@code null}
     */
    @Override
    public String toString() {
        return "Customer[ssn=" + REDACTED
                + ", governmentIssuedId=" + REDACTED
                + ", version=" + this.version + ']';
    }
}
