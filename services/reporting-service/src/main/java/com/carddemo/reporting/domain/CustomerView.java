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
 * <p>The statement generator reads one customer row per card, at {@code 2000-CUSTFILE-GET.} L368 of
 * {@code app/cbl/CBSTM03A.CBL}, and assembles from it the values the statement heading carries: the
 * name, built at L462-L469 from three parts each followed by an unconditional blank literal, and the
 * three address lines, the last of which is itself assembled from a line, a state code, a country
 * code and a postal code. This projection carries those parts exactly as they are stored and
 * assembles nothing. Assembly, and every declared width an assembled value has to reach, belongs to
 * the mapper layer.
 *
 * <h2>Provenance: the primary book is CUSTREC, and it is not the widely copied one</h2>
 *
 * <p>Assumptions: two copybooks in {@code app/cpy} declare {@code 01 CUSTOMER-RECORD} at L4 across
 * 500 bytes with the same eighteen fields at the same widths and the same trailing
 * {@code FILLER PIC X(168)} at L23. This projection is anchored on {@code app/cpy/CUSTREC.cpy},
 * because L55 of {@code app/cbl/CBSTM03A.CBL} reads {@code COPY CUSTREC.} and that statement is the
 * only consumer of that book anywhere in the repository. The twin, {@code app/cpy/CVCUS01Y.cpy}, is
 * copied by ten programs and by none of the statement chain, so it corroborates the geometry without
 * governing it. Reaching for the twin because it is the more widely used book yields a correct layout
 * for the wrong reason and hides the divergence recorded below.
 *
 * <p>Refactoring Rationale: an earlier revision of this file anchored every layout citation on the
 * twin instead. The geometry it produced was right, because the two books agree on every width, but
 * two things went wrong with it. It named a book the statement generator never copies as the
 * authority for a statement projection, and it cited that book's spelling of the date-of-birth field
 * as though only one spelling existed, which left the divergence below undocumented. It also
 * disagreed with this package's own charter, which records that this context is bound to
 * {@code CUSTREC.cpy}. Every citation here now names the book or the file record it is read from.
 *
 * <h2>The one token the two books disagree on, and how this type names it</h2>
 *
 * <p>Alternatives Considered: the ten bytes at one-based positions 309 through 318 are declared
 * {@code CUST-DOB-YYYYMMDD} at L19 of {@code app/cpy/CUSTREC.cpy} and
 * {@code CUST-DOB-YYYY-MM-DD} at L19 of {@code app/cpy/CVCUS01Y.cpy}. Same width, same positions,
 * byte-equivalent layouts, two different identifiers, and the two books have almost disjoint consumer
 * sets. Three namings were available and all three were weighed. Following the first book's spelling
 * was rejected, and following the second book's spelling was rejected with it, because privileging
 * either one implicitly marks the other as mistaken when both are legitimate declarations in two twin
 * books that no program reads together. The third option was taken: this type names the member for
 * what the field means rather than for how either book spells it, so the baseline declares two
 * spellings, the Java encodes the semantic, and the divergence is documented here instead of being
 * settled by whichever book a reader happened to open. The name is also stable against either
 * spelling, which matters because a future reader arriving through the widely copied twin will find
 * this paragraph rather than an apparent contradiction.
 *
 * <p>Assumptions: this is a naming choice and not a correction of either book. The migration plan
 * records exactly three baseline field-name changes, on the account expiration date, the card
 * expiration date and the authorization merchant category code, and this is deliberately not among
 * them; nothing in {@code app/cpy} is altered by anything written here, and both spellings remain
 * exactly as their books declare them. The column this member binds to is named separately again, by
 * the relation rather than by this type, and that reconciliation is recorded at the member itself.
 *
 * <h2>Record geometry, derived twice from unrelated sources</h2>
 *
 * <p>Assumptions: the record is 500 bytes, and that figure is asserted rather than estimated because
 * two independent sources agree on it. Summing the declared widths of the eighteen named fields at
 * L5-L22 gives 9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3,
 * which is 332 significant bytes; the trailing {@code FILLER PIC X(168)} at L23 occupies one-based
 * positions 333 through 500, and 332 plus 168 is 500 exactly. Independently,
 * {@code app/cbl/CBSTM03B.CBL} declares {@code FD CUST-FILE} at L70 with two members only,
 * {@code FD-CUST-ID PIC X(09)} at L72 and {@code FD-CUST-DATA PIC X(491)} at L73, and 9 plus 491 is
 * also 500. The second source carries no copybook at all, which is what makes the agreement
 * meaningful, and it independently confirms that the key is the leading nine bytes.
 *
 * <p>Assumptions: the fields this projection does not carry still occupy their bytes in the record.
 * The national identifier at L17 occupies nine and the government-issued identifier at L18 occupies
 * twenty, and both sit inside the 332 counted above. Declining to project a field removes it from
 * this type and from the view, never from the record, so the positions of every field after them are
 * unchanged. Closing the gap they leave would move the date of birth, the transfer-account reference,
 * the cardholder indicator and the credit score twenty-nine bytes earlier than they are, which is the
 * one arithmetic mistake here that produces plausible values rather than an obvious failure.
 *
 * <p>Assumptions: the trailing {@code FILLER} at L23 is dropped rather than mapped. It is padding out
 * to the declared 500-byte record length and carries no value of its own, so the migration's own
 * transformation rule that makes the copybook normative also makes it a field with nothing to
 * project.
 *
 * <h2>Twelve members are projected; the relation withholds six</h2>
 *
 * <p>Assumptions: the eighteen named fields of the record reach this type as twelve mapped members,
 * because the relation this type maps selects twelve columns. {@code reporting.v_customers}, declared
 * in {@code data-migration/sql/V1__reporting_views.sql}, selects the identifier, the three name parts,
 * the three address lines, the state code, the country code, the postal code, the date of birth and
 * the credit score, and nothing else. The six it does not select are the two identifier fields at L17
 * and L18, both telephone numbers at L15 and L16, the transfer-account reference at L20 and the
 * cardholder indicator at L21.
 *
 * <p>Refactoring Rationale: the credit score at L22 moved from the withheld set into the projected
 * set, and the reason it was withheld -- that no reporting band prints it -- was simply not true.
 * {@code app/cbl/CBSTM03A.CBL} moves {@code CUST-FICO-CREDIT-SCORE} into {@code ST-FICO-SCORE},
 * declared {@code PIC X(20)} at L118 of {@code app/cpy/COSTM01.CPY}, and the migrated renderer emits
 * that band. With the column withheld the statement generator had no source for a value it must
 * print, so it accepted a caller-supplied resolver function instead -- a parameter no production
 * caller ever passed, which is a large part of why the whole-run generator could not be invoked at
 * all. The paragraph below still describes why withholding at the relation is the stronger control;
 * it is the CLASSIFICATION of this one column that was wrong, not the technique.</p>
 *
 * <p>Assumptions: withholding the remaining six at the relation, namely L15, L16, L17, L18, L20 and
 * L21, is stronger than declining to expose them here, and that is why this type does not carry
 * them. A projection that mapped the record's whole
 * width and then kept part of it private would leave every withheld value one accessor away; a
 * relation that never selects them leaves them one privilege away, and the login role this module
 * connects as holds no privilege on the schema the base table lives in. Widening this type to the
 * record's full width is therefore not an improvement available to it: the columns it would have
 * to name do not exist in the relation, so each one would fail the first read rather than return a
 * value. A reporting band that genuinely needs one of the six is a change to the relation, in the
 * migration that declares it, and is reported against that artifact rather than worked around here.
 * ⚠️ Refactoring Rationale: that figure read "the seven", which was the withheld count BEFORE the
 * credit score moved into the projected set two paragraphs above. Both statements were in this same
 * comment, so the file enumerated six and then counted seven -- and the enumeration is the half a
 * reader acts on, which is why the count follows it rather than the other way round.
 *
 * <p>Trade-offs: the two identifier fields, the national identifier declared {@code PIC 9(09)} at L17
 * and the government-issued identifier declared {@code PIC X(20)} at L18, are not projected, on three
 * independent grounds. The base table holds each of them enciphered as a byte column and returns it
 * masked, and an enciphered byte column is not a value a statement projection can meaningfully hold.
 * No endpoint this bounded context exposes returns either value. And a read-only reader that does not
 * need a value should not hold it, because omitting it removes the exposure instead of managing it.
 * What is accepted is real: a consumer needing a masked national identifier cannot obtain one from
 * this type. Masking, if a statement layout ever requires it, belongs in the mapper layer, and the
 * requirement is reported rather than met by widening this projection.
 *
 * <p>Trade-offs: the cardholder indicator at L21 is declared {@code PIC X(01)}, a single character,
 * and is recorded here as a character contract even though the relation withholds it. Were it ever
 * projected it would stay a one-character string and would not narrow to a two-valued type, because
 * narrowing discards every value other than the two a reader happens to expect and the baseline
 * declares a character domain rather than a pair. Note also what its name does not imply: it is a
 * stored character on the customer record and not a reference to a card, and no card relation is read
 * by this context at all.
 *
 * <p>Assumptions: the credit score at L22 is declared {@code PIC 9(03)}, unsigned and zero-padded, so
 * its target is a bounded small integer rather than a wider numeric type. It is recorded for the same
 * reason as the indicator above, so that a reader who finds it in the record and not in this type sees
 * a documented decision rather than an apparent omission.
 *
 * <h2>Decisions</h2>
 *
 * <p>Trade-offs: this type is mapped {@code @Immutable}, declares every column not updatable, and
 * carries no setter, no cascade and no write path of any kind. The baseline establishes that nothing
 * is lost: the statement inputs are opened for input only and no record-replacing or record-removing
 * verb is executed against any of them, and the input-output subprogram that reaches them, though it
 * declares six operation codes at L103-L108 of {@code app/cbl/CBSTM03B.CBL}, exercises only the four
 * that read, namely open, close, sequential read and keyed read. What is given up is dirty checking
 * and the convenience of merging a detached instance, and a value that needs changing has to be
 * changed by the context that owns the underlying table. What is bought is where an accidental write
 * fails. Mapped immutable it fails at the mapping layer and names this type; left mutable, the same
 * write travels to the database and fails against a select-only privilege, surfacing as an opaque
 * error far from the assignment that caused it.
 *
 * <p>Trade-offs: no optimistic-locking version member is declared, even though the base table
 * {@code account.customers} carries a version column, declared in
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql} alongside the
 * eighteen columns that record's L5-L22 map to. That column exists because account-service
 * performs a before-image concurrency check when it updates a customer, so it belongs to that
 * context's writable entity. On a read-only projection that never participates in a write it would
 * have nothing to guard, and carrying it would advertise a concurrency protocol this type does not
 * take part in. No index and no unique constraint is declared here either, because this context owns
 * no secondary access path over this record.
 *
 * <p>Alternatives Considered: this type maps a view, and this context declares no schema of its own.
 * Every store the statement reads is already declared as job-control input elsewhere, at L83-L86 of
 * {@code app/jcl/CREASTMT.JCL}, so nothing here needs a table of its own. Two other shapes were
 * evaluated and both were rejected. Declaring a base table here was rejected
 * because the customer row is already owned by another context, so a local copy would create a second
 * truth for figures whose whole purpose is to restate the first one exactly; the migration plan
 * records this context with no owned tables for that reason. Reading a replica was rejected on the
 * plan's own ground that a replica adds cost and replica-lag semantics for no parity benefit, which
 * on a statement means disagreeing with the ledger by one replication interval.
 *
 * <p>Assumptions: agreement with the context that owns these rows runs through the physical view and
 * the narrowly-scoped select-only privilege behind it, never through code, and this type declares no
 * relationship annotation to any sibling projection in either direction. The statement read joins four
 * relations, and that join is composed in the repository layer by query rather than by mapped
 * association, for two reasons. The relations live in another schema reached by conveyed privilege
 * rather than by a build dependency, which is the same way the batch context reaches a schema it does
 * not own; and a domain package importing another
 * context's domain package is forbidden by the shared kernel's architecture rules, asserted by
 * {@code LayeringRulesTest} under
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture} and evaluated against
 * this module's own compiled classes on every build. The database half is authored elsewhere and is
 * cited by path: {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the schema and the
 * roles, and {@code data-migration/sql/V1__reporting_views.sql} declares the view. A view absent at
 * run time is a defect to report against those artifacts and never one to work around from inside
 * this type.
 *
 * <p>Assumptions: this projection serves the statement read only, and it is deliberately not a
 * participant in the report read. The report's job control at L65-L74 of {@code app/jcl/TRANREPT.jcl}
 * declares a transaction file, a cross-reference, a transaction type file, a category file and a
 * parameter dataset, and no customer file among them; the program it invokes copies five books, at
 * L93, L98, L103, L108 and L113 of {@code app/cbl/CBTRN03C.cbl}, and no customer book is among those
 * either. The report never reads a customer record, so wiring this type into a report query would join
 * a relation the baseline it reproduces never opened.
 *
 * <p>Assumptions: this type carries no money member and no timestamp member, and imports neither the
 * shared money type nor any date-time type beyond a calendar date. No field at L5-L23 is declared with
 * a decimal picture and none is declared twenty-six characters wide, so there is nothing here to round,
 * nothing to scale and no instant to represent. A zone-bearing or zone-shifted date-time type is
 * likewise never used, because the baseline record carries no zone at all and attaching one would
 * invent information the source never held.
 *
 * <p>Assumptions: no business rule is encoded on this type. Validation of a telephone area code, a
 * state code or a state-and-postal-code pairing against the lookup tables seeded from the allow-lists
 * in {@code app/cpy/CSLKPCDY.cpy} is owned by account-service, and the credit score carries no threshold logic anywhere in the baseline. This type
 * exposes the projected values and computes no comparison, derivation or threshold; an accessor that
 * computed one would also cease to be the trivial accessor whose single-line documentation form the
 * project's Explainability rule permits.
 *
 * <p>Assumptions: every layout citation here names the book or the file record it is read from,
 * because a field name is not unique across this baseline and a bare name does not fix a width. The
 * same identifier {@code FD-ACCT-DATA} is declared {@code PIC X(318)} at L63 and {@code PIC X(289)} at
 * L78 of {@code app/cbl/CBSTM03B.CBL}, so a collision occurs inside a single program; the group
 * {@code TRAN-CAT-KEY} is six bytes at L5 of {@code app/cpy/CVTRA04Y.cpy} and seventeen bytes at L5 of
 * {@code app/cpy/CVTRA01Y.cpy}. The baseline adopts this discipline itself, qualifying its two
 * ambiguous names explicitly at L365 and L367 of {@code app/cbl/CBTRN03C.cbl}, and it is copied here.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries documentation regardless of visibility, because the project's
 * Explainability rule attaches its presence clause to every function and class and names no
 * visibility. The rationale labels are written in the plural, unparenthesised forms that rule gives,
 * and the written convention every block here follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md},
 * cited by path and never restated.
 */
@Entity
@Immutable
@Table(name = "v_customers", schema = "reporting")
public class CustomerView {

    /**
     * Declared width of each of the three name parts, in characters.
     *
     * <p>Assumptions: 25 is the width {@code CUST-FIRST-NAME}, {@code CUST-MIDDLE-NAME} and
     * {@code CUST-LAST-NAME} each declare at L6, L7 and L8 of {@code app/cpy/CUSTREC.cpy}. Three
     * parts at 25 characters, each followed by one blank literal, is what makes the assembled name
     * 75 characters wide rather than 78.</p>
     */
    public static final int NAME_PART_WIDTH = 25;

    /**
     * Declared width of each address line, in characters.
     *
     * <p>Assumptions: 50 is the width {@code CUST-ADDR-LINE-1} through {@code CUST-ADDR-LINE-3} each
     * declare at L9, L10 and L11 of {@code app/cpy/CUSTREC.cpy}.</p>
     */
    public static final int ADDRESS_LINE_WIDTH = 50;

    /**
     * Declared width of the state code, in characters.
     *
     * <p>Assumptions: 2 is the width {@code CUST-ADDR-STATE-CD} declares at L12 of
     * {@code app/cpy/CUSTREC.cpy}, and the base column is fixed-width for that reason.</p>
     */
    public static final int STATE_CODE_WIDTH = 2;

    /**
     * Declared width of the country code, in characters.
     *
     * <p>Assumptions: 3 is the width {@code CUST-ADDR-COUNTRY-CD} declares at L13 of
     * {@code app/cpy/CUSTREC.cpy}.</p>
     */
    public static final int COUNTRY_CODE_WIDTH = 3;

    /**
     * Declared width of the postal code, in characters.
     *
     * <p>Assumptions: 10 is the width {@code CUST-ADDR-ZIP} declares at L14 of
     * {@code app/cpy/CUSTREC.cpy}.</p>
     */
    public static final int POSTAL_CODE_WIDTH = 10;

    // Alternatives Considered: the identifier is a magnitude rather than nine characters. Both
    // customer books declare CUST-ID PIC 9(09) at L5, a numeric picture, while app/cbl/CBSTM03B.CBL
    // L72 declares the same key as FD-CUST-ID PIC X(09), alphanumeric. The migration's transformation
    // rule makes the copybook normative, so the numeric declaration governs and the target is a
    // 64-bit integer. Two details in that same program show the alphanumeric spelling is a local
    // convenience and not a competing type statement: it declares the account key numerically at L77
    // as FD-ACCT-ID PIC 9(11), and it moves every field through the untyped thousand-byte transfer
    // buffer declared at L112, which can only be addressed as characters.
    @Id
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    // Assumptions: this identifier carries the same target type as XREF-CUST-ID, declared PIC 9(09)
    // at L6 of app/cpy/CVACT03Y.cpy, which is the column the cross-reference projection joins on. The
    // agreement is load-bearing rather than incidental: a character member here would compare unequal
    // to the same value read from that relation and would make the statement join coercion-bound. It
    // is the key of a single-column indexed access path, so no composite identifier is declared.
    @Column(name = "first_name", length = NAME_PART_WIDTH, nullable = false, updatable = false)
    private String firstName;

    // Assumptions: this member is nullable while the two beside it are not, because the owning
    // migration declares the 25-character field at L7 so while declaring those at L6 and L8 not null. An absent middle name is not defaulted to a blank here: a blank and
    // an absence reach the statement's name assembly identically, so substituting one for the other
    // would hide which the row actually held.
    @Column(name = "middle_name", length = NAME_PART_WIDTH, updatable = false)
    private String middleName;

    @Column(name = "last_name", length = NAME_PART_WIDTH, nullable = false, updatable = false)
    private String lastName;

    @Column(name = "addr_line_1", length = ADDRESS_LINE_WIDTH, nullable = false, updatable = false)
    private String addressLine1;

    @Column(name = "addr_line_2", length = ADDRESS_LINE_WIDTH, updatable = false)
    private String addressLine2;

    // Assumptions: the 50-character line at L11 is one of four operands the statement's third address
    // cell is assembled from, the others being the codes at L12 and L13 and the postal code at L14.
    // The assembly belongs to the mapper layer and not to this type.
    @Column(name = "addr_line_3", length = ADDRESS_LINE_WIDTH, nullable = false, updatable = false)
    private String addressLine3;

    // Trade-offs: the state code and the country code stay fixed-width character members and are not
    // mapped to enumerated types, at the two and three characters L12 and L13 declare. An enumeration
    // would make this type an arbiter of which codes are legal, and the seeded lookup tables that
    // decide that question are owned by other contexts; it would also fail a read outright on a code
    // the baseline stored but the enumeration had not anticipated. What is accepted is that a
    // malformed code reaches a caller unflagged, which is correct for a projection whose contract is
    // to report what is stored.
    @Column(name = "addr_state_cd", length = STATE_CODE_WIDTH, nullable = false, updatable = false)
    private String stateCode;

    @Column(name = "addr_country_cd", length = COUNTRY_CODE_WIDTH, nullable = false,
            updatable = false)
    private String countryCode;

    @Column(name = "addr_zip", length = POSTAL_CODE_WIDTH, nullable = false, updatable = false)
    private String postalCode;

    // Assumptions: the date of birth is held as a calendar date rather than as the ten characters the
    // baseline stores at L19. The stored form is year-month-day ordered, so a lexical comparison over
    // those ten bytes and a chronological comparison agree, and the change of type is therefore
    // behaviour-preserving. The baseline relies on exactly that property: L42 of
    // app/jcl/TRANREPT.jcl declares a ten-character field and L43 and L44 compare it against the
    // character literals C'2022-01-01' and C'2022-07-06' as a date range. The type is changed anyway
    // because a date member cannot be accidentally concatenated or sliced.
    //
    // Assumptions: the column is named for the field rather than for the format, and it is declared
    // not null. Both are properties of the relation this type maps rather than choices available
    // here: V1__account.sql declares the column a date that is not null, and the view selects it
    // under that name, so the ten characters L19 declares arrive here already resolved to a date. A blank ten-byte date is a legitimate value in the record itself, so the
    // absence of a null here records that the load resolves that case before the row reaches this
    // relation, and a row that ever arrived without a date would be a defect to report against the
    // migration rather than a state this member is expected to represent.
    @Column(name = "dob", nullable = false, updatable = false)
    private LocalDate dateOfBirth;

    // Assumptions: the credit score is mapped as a Short and not as an int or a String. The base
    // column is declared SMALLINT NOT NULL at L610 of
    // services/account-service/src/main/resources/db/migration/V1__account.sql, which is the mapping
    // its own module chose for CUST-FICO-CREDIT-SCORE PIC 9(03) at L22 of app/cpy/CVCUS01Y.cpy -- an
    // unsigned three-digit picture, so the value is bounded well inside a signed 16-bit range and a
    // wider type would only invite a value the source cannot express.
    // WHY : Refactoring Rationale: this member exists because the statement heading prints it and had
    //       no source for it. app/cbl/CBSTM03A.CBL moves the field into ST-FICO-SCORE, declared
    //       PIC X(20) at L118 of app/cpy/COSTM01.CPY, and the migrated renderer emits that band from
    //       StatementBandLayouts.ST_LINE9. The projection did not select the column, so the generator
    //       took the value from a caller-supplied resolver function instead -- and no production
    //       caller existed to supply one, which is why the whole-run generator could not be invoked.
    //       Reading the value from the row it belongs to removes the parameter and the gap together.
    // Assumptions: the column is not null, matching the base declaration, so this member is never
    // null on a projected row. A row arriving without a score would be a defect to report against the
    // migration rather than a state the statement path is expected to render around.
    @Column(name = "fico_credit_score", nullable = false, updatable = false)
    private Short ficoCreditScore;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires an entity to declare a constructor
     * taking no arguments, which the provider uses before assigning the 12 mapped columns. Visibility is protected rather than public because no caller outside this
     * type's hierarchy has a use for a half-built row.</p>
     */
    protected CustomerView() {
        // Assumptions: the body is empty by design rather than unfinished. The provider assigns all 12
        // mapped fields directly after construction, so an assignment here would be overwritten and
        // would only obscure that.
    }

    /**
     * Creates an instance from values already projected by the relation.
     *
     * <p>Alternatives Considered: validating the arguments here was evaluated and rejected. All 12
     * values originate in {@code account.customers}, a relation another context owns, so a guard here would make this context an
     * arbiter of data it does not own and would fail a read on a row the owning context considers
     * valid. Reporting what is stored is this type's contract.</p>
     *
     * <p>Assumptions: twelve arguments are declared because the relation selects twelve columns. The
     * six record fields it withholds, enumerated in the type documentation above, have no parameter
     * here and no member on this type.</p>
     *
     * @param customerId the customer identifier declared {@code CUST-ID PIC 9(09)} at L5 of
     *     {@code app/cpy/CUSTREC.cpy}, carried as a magnitude rather than as nine characters
     * @param firstName the first of the three name parts, declared 25 characters wide at L6
     * @param middleName the second name part, declared 25 characters wide at L7, which may be
     *     {@code null} because the relation permits a row to carry none
     * @param lastName the third name part, declared 25 characters wide at L8
     * @param addressLine1 the first address line, declared 50 characters wide at L9
     * @param addressLine2 the second address line, declared 50 characters wide at L10, which may be
     *     {@code null} because the relation permits a row to carry none
     * @param addressLine3 the third address line, declared 50 characters wide at L11, one of four
     *     operands the statement's third address cell is assembled from
     * @param stateCode the state code declared {@code PIC X(02)} at L12, reported as stored and
     *     validated against no lookup table by this type
     * @param countryCode the country code declared {@code PIC X(03)} at L13
     * @param postalCode the postal code declared {@code PIC X(10)} at L14
     * @param dateOfBirth the date of birth declared {@code PIC X(10)} at L19, which is L19 and not
     *     L17, L17 being the national identifier this relation never selects
     * @param ficoCreditScore the credit score declared {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
     *     L22, which the statement heading band {@code ST-FICO-SCORE} at L118 of
     *     {@code app/cpy/COSTM01.CPY} prints
     */
    public CustomerView(Long customerId, String firstName, String middleName, String lastName,
            String addressLine1, String addressLine2, String addressLine3, String stateCode,
            String countryCode, String postalCode, LocalDate dateOfBirth,
            Short ficoCreditScore) {
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
        this.ficoCreditScore = ficoCreditScore;
    }

    /**
     * Returns the customer identifier this row is keyed by.
     *
     * @return the value of the {@code customer_id} column, joining to the cross-reference relation
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Returns the first of the three name parts.
     *
     * @return the value of the {@code first_name} column
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Returns the second name part, which the row may legitimately not carry.
     *
     * @return the value of the {@code middle_name} column, or {@code null} where the row carries none
     */
    public String getMiddleName() {
        return middleName;
    }

    /**
     * Returns the third name part.
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
     * Returns the two-character state code exactly as the relation stores it.
     *
     * @return the value of the {@code addr_state_cd} column, unvalidated by this type
     */
    public String getStateCode() {
        return stateCode;
    }

    /**
     * Returns the three-character country code exactly as the relation stores it.
     *
     * @return the value of the {@code addr_country_cd} column, unvalidated by this type
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
     * Returns the date of birth as a calendar date.
     *
     * @return the value of the {@code dob} column, the ten ordered characters of L19 held as a date
     */
    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    /**
     * Returns the credit score the statement heading prints.
     *
     * <p>Assumptions: the value is returned as stored and is not bounded, clamped or defaulted here.
     * {@code app/cbl/CBSTM03A.CBL} prints whatever the customer record holds, and the renderer that
     * consumes this value applies the only constraint that exists -- the unsigned three-digit picture
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at L22 of {@code app/cpy/CVCUS01Y.cpy}, which is why a
     * negative value is refused at the band rather than silently corrected. Reporting what is stored
     * is this type's contract.</p>
     *
     * @return the value of the {@code fico_credit_score} column, being the three-digit score declared
     *     at L22, or {@code null} where no row has been projected into this instance yet
     */
    public Short getFicoCreditScore() {
        return ficoCreditScore;
    }

    /**
     * Reports whether another object denotes the same customer row as this one.
     *
     * <p>Assumptions: equality rests on the identifier alone, the key declared {@code PIC 9(09)} at L5.
     * Including a name or an address would
     * make two loads of one row compare unequal after the owning context changed either of them,
     * which would make row identity depend on data this context neither owns nor controls.</p>
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
     * <p>Assumptions: the hash is taken from the identifier at L5 and from nothing else, because a hash
     * drawn from a mutable-in-its-owning-context value would move for a row whose identity had not
     * changed and would break every hash-based collection holding it.</p>
     *
     * @return the hash of the customer identifier, or zero where none has been projected yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.customerId);
    }

    /**
     * Returns a diagnostic rendering naming the type and no projected member at all.
     *
     * <p>Assumptions: every one of the eleven projected members is withheld, and this rendering
     * therefore names only its own type. That is the outcome
     * {@code docs/architecture/observability.md} prescribes for a type left with nothing it may
     * disclose: "A rendering left with none of those omits the member rather than substituting
     * something." The ten name, address, code and date members are personal data; the customer
     * identifier declared at {@code app/cpy/CVCUS01Y.cpy} L5 is named in the same rule's prohibition
     * alongside account identifiers; and this projection carries no status code, version counter or
     * bounded response code that the rule would let a rendering keep.</p>
     *
     * <p>Refactoring Rationale: this rendering kept the customer identifier, justified on the ground
     * that it "locates the row exactly and is what a reader of a failure needs". The need is real and
     * the conclusion did not follow: the omission rule names customer identifiers explicitly, so
     * "what a reader needs" cannot be the test, and a rendering that satisfied the rule for ten
     * members by omission while exempting the eleventh by usefulness was applying two rules at once.
     * The reasoning is kept on record because it is the argument a future reader is most likely to
     * reach independently.</p>
     *
     * <p>Trade-offs: a log line written from this type now says only that a customer projection was
     * involved. That is a genuine loss of diagnostic power, and it is paid for the same way the
     * observability authority pays for it everywhere else -- by the request correlation identifier on
     * every request-scoped line and by the {@code batch.batch_run} step ledger for batch work, both of
     * which locate an event without naming a protected value.</p>
     *
     * @return a single-line rendering naming the type alone, never {@code null}
     */
    @Override
    public String toString() {
        return "CustomerView[]";
    }
}
