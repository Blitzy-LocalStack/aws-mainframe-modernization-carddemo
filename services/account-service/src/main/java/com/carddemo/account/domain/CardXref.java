package com.carddemo.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * The card-to-account-and-customer cross-reference row owned by the account bounded context.
 *
 * <h2>Purpose</h2>
 *
 * <p>An instance maps exactly one row of {@code account.card_xref} and carries nothing else. It
 * resolves a primary account number to the customer holding the card and to the account the card
 * draws on, which is the entire content of the reference record. No business rule belongs here,
 * because a transcribed reference paragraph becomes a named method in
 * {@code com.carddemo.account.service}; no published request or response shape belongs here,
 * because those live in {@code com.carddemo.account.dto}; and no representation concern of the
 * fixed-width reference record belongs here, because widths, trailing blanks, dropped padding and
 * masking for publication are the business of {@code com.carddemo.account.mapper}. The one decision
 * left to this type is which physical column, of which type and width, each reference field maps
 * onto.</p>
 *
 * <h2>The normative reference contract</h2>
 *
 * <p>Assumptions: {@code app/cpy/CVACT03Y.cpy} is normative, so a field's picture clause decides
 * both the PostgreSQL column type and the Java member type rather than being reconciled with them
 * afterwards. That copybook declares {@code 01 CARD-XREF-RECORD.} at L4 and its header comment at
 * L2 announces a 50-byte record. Three named fields plus one padding field account for those 50
 * bytes exactly, and the arithmetic is quoted below so a later reader can check it rather than
 * trust it.</p>
 *
 * <ul>
 *   <li>L5 {@code XREF-CARD-NUM PIC X(16)} maps to {@code card_num CHAR(16)}, the primary key.</li>
 *   <li>L6 {@code XREF-CUST-ID PIC 9(09)} maps to {@code customer_id BIGINT}.</li>
 *   <li>L7 {@code XREF-ACCT-ID PIC 9(11)} maps to {@code account_id BIGINT}.</li>
 *   <li>L8 {@code FILLER PIC X(14)} maps to nothing, and the drop is recorded here rather than
 *       left silent. 16 plus 9 plus 11 is 36 named bytes, and 36 plus 14 is the declared 50.</li>
 * </ul>
 *
 * <p>Assumptions: the primary key is settled from two independent places that agree, which is why
 * it is asserted here without hedging. The copybook puts the card number first in the record, and
 * {@code app/cbl/CBACT03C.cbl} names it explicitly at L32 as {@code RECORD KEY IS
 * FD-XREF-CARD-NUM}. That program's own file-section record corroborates the record length from a
 * second direction as well: L39 declares a 16-byte key field and L40 a 34-byte remainder, and 16
 * plus 34 is again 50. L45 of the same program confirms this copybook is the contract it reads
 * through.</p>
 *
 * <h2>The physical shape this mapping answers to</h2>
 *
 * <p>Assumptions: the authoritative column list is the migration at
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql}, not this file.
 * The shape below is recorded here so the obligation is visible at the mapping site, because nothing
 * in the running system compares the two. Hibernate's schema management is switched off, so every
 * column exists at runtime only because that migration created it, and a member naming a column the
 * migration does not declare would compile, start, and then fail at the first statement touching
 * it.</p>
 *
 * <pre>{@code
 * account.card_xref
 *   card_num     CHAR(16)  NOT NULL   -- primary key, constraint pk_card_xref
 *   customer_id  BIGINT    NOT NULL
 *   account_id   BIGINT    NOT NULL
 *   -- no version column
 *   -- no foreign key to account.accounts or to account.customers
 * CREATE INDEX idx_card_xref_account_id ON account.card_xref (account_id);   -- NON-UNIQUE
 * }</pre>
 *
 * <p>Assumptions: that migration declares this table at L641, its three columns at L657, L665 and
 * L676, its primary key {@code pk_card_xref} at L695 and the secondary index at L726, and the
 * member names below are taken from those declarations rather than from an abbreviation of the
 * reference field names. The reference fields are spelled {@code XREF-CUST-ID} and
 * {@code XREF-ACCT-ID}, so {@code cust_id} and {@code acct_id} would be the closer transliteration;
 * the migration declares {@code customer_id} and {@code account_id}, and matching it is not a
 * preference but the only mapping that resolves. Three consumers in this module pin the same names
 * independently: {@code com.carddemo.account.mapper.AccountContextMapper} reads
 * {@code getAccountId()} and {@code getCustomerId()} at its L53, and
 * {@code com.carddemo.account.repository.CardXrefRepository} derives two queries from the property
 * names themselves at L48 and L61, so a renamed member there is a startup failure rather than a
 * compile error.</p>
 *
 * <p>Assumptions: neither this type nor that migration creates the {@code account} schema. It is
 * bootstrapped by {@code data-migration/sql/V0__schemas_and_roles.sql}, the single authority for
 * schemas, roles and grants, so this mapping assumes the schema already exists.</p>
 *
 * <h2>Why this row carries no version column</h2>
 *
 * <p>Refactoring Rationale: the sibling {@code Account} and {@code Customer} entities each carry an
 * optimistic-concurrency version because the reference performs a before-image comparison by hand
 * across the gap between screen turns. This row carries none, and the asymmetry is deliberate
 * rather than an omission. Optimistic concurrency control exists to protect a read-modify-write
 * cycle, and this record has no update path to protect: {@code app/cbl/CBACT03C.cbl} opens the
 * cross-reference at L30 and L31 as {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS
 * SEQUENTIAL}, keyed at L32, and only reads it. {@code app/cbl/COACTUPC.cbl}, the 4236-line
 * account-update program, rewrites the account and customer masters and does not touch the
 * cross-reference at all. A version column here would add a conflict check to a row that no path
 * updates, and the migration declares no such column for this table to map.</p>
 *
 * <h2>Why the account identifier is a plain column</h2>
 *
 * <p>Alternatives Considered: mapping the two identifiers as many-to-one associations to
 * {@link Account} and {@link Customer}, giving callers an object graph to traverse. Rejected, and
 * the reference evidence shows the relationship cannot be expressed from either other side in any
 * case. {@code app/cpy/CVACT01Y.cpy} carries no customer identifier anywhere in it, and
 * {@code app/cpy/CVCUS01Y.cpy} carries no account identifier -- the one field there whose name
 * contains the word account is L20 {@code CUST-EFT-ACCOUNT-ID PIC X(10)}, an external
 * electronic-funds-transfer account that points at nothing in this schema. These two identifier
 * columns are therefore the sole join path between the other two tables, and
 * {@code app/cbl/COACTVWC.cbl} navigates it as three separate keyed reads with no join whatsoever:
 * the cross-reference by account at L727 through the path named at L728, the account master at L776
 * and L777, and the customer master at L826 and L827. An association would additionally put
 * lazy-loading proxies into objects that cross the mapping boundary, leaking a persistence concern
 * past {@code com.carddemo.account.mapper}, which exists precisely so that none travels
 * further.</p>
 *
 * <p>Alternatives Considered: declaring a foreign key behind such an association. Rejected because
 * the reference asserts referential integrity explicitly where it wants it and does not assert it
 * here, so the absence is informative. The only foreign key declarations in the baseline belong to
 * the transaction-type extension, at {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6 and
 * L7, which reference-service owns. This context has no such declaration at all, and all four of
 * its storage resources are defined without recovery or journalling -- {@code JOURNAL(NO)} at
 * {@code app/csd/CARDDEMO.CSD} L7, L44, L57 and L70, and {@code RECOVERY(NONE)} at L9, L46, L59 and
 * L72. A foreign key would therefore refuse rows the baseline accepts, which the migration's
 * standing constraint forbids: structure may change freely, observable behaviour may not.</p>
 *
 * <h2>Why this is an ordinary class</h2>
 *
 * <p>Alternatives Considered: a Java record, which would express three values far more briefly.
 * Rejected because the persistence provider requires a no-argument constructor and non-final
 * fields, and a record can supply neither. The documentation gate points the same way: it runs with
 * missing parameter tags disallowed on type documentation, so a record would additionally require a
 * parameter tag for every component.</p>
 *
 * <p>Alternatives Considered: an annotation processor such as Lombok to generate the accessors,
 * constructors and value semantics below. Rejected because generated members cannot carry the
 * documentation this project requires on every method, and the gate runs with the
 * property-accessor exemption switched off, so generated accessors would fail it. Explicit members
 * cost more lines and are the only ones that can be documented.</p>
 *
 * <h2>What differs from the reference storage, deliberately</h2>
 *
 * <p>Trade-offs: isolation and durability are stronger here than in the reference, and that is a
 * correction rather than a port. All four account-context resources are defined with
 * {@code READINTEG(UNCOMMITTED)} at {@code app/csd/CARDDEMO.CSD} L3, L40, L53 and L66, with
 * {@code UPDATEMODEL(LOCKING)} at L6, L43, L56 and L69, and with {@code JNLSYNCWRITE(YES)
 * RECOVERY(NONE) FWDRECOVLOG(NO)} at L9, L46, L59 and L72. PostgreSQL's default read-committed
 * isolation is strictly stronger than an uncommitted read, and encryption at rest with automated
 * backups replaces resources that had neither. One behaviour is genuinely not reproduced:
 * {@code STRINGS(1)} at L4, L41, L54 and L67 meant the region serialised concurrent requests per
 * file, whereas a connection pool serves them in parallel. Nothing in this context depends on that
 * serialisation, so the concurrency it suppressed is accepted rather than re-imposed.</p>
 *
 * @see Account
 * @see Customer
 */
@Entity
// WHY : Alternatives Considered: omitting the schema qualifier and letting the pooled connection's
//       search path supply it, which is what the two sibling entities in this package do. The
//       qualifier is stated instead because it makes the mapping self-describing at the point a
//       reader is asking which table this is, and because it agrees exactly with the migration's
//       own CREATE TABLE account.card_xref at V1__account.sql L641. Stating it is inert at runtime:
//       the search path is already pinned to this one schema, and schema management is switched
//       off, so no data definition is generated from it. The accepted cost is that the schema name
//       now appears in a second place, which is the reason it is written to match the migration
//       verbatim rather than assembled from a constant.
@Table(name = "card_xref", schema = "account")
public class CardXref {

    /**
     * The declared width of a primary account number, sixteen characters.
     */
    public static final int CARD_NUMBER_LENGTH = 16;

    /**
     * The number of trailing digits a diagnostic rendering may disclose, four.
     */
    private static final int DISCLOSED_SUFFIX_LENGTH = 4;

    /**
     * The marker a masked rendering is prefixed with, standing in for the withheld digits.
     */
    private static final String MASK_MARKER = "****";

    // WHY : Assumptions: the card number is a String over a fixed-width CHAR column and never a
    //       numeric type. CVACT03Y L5 declares it PIC X(16), so the baseline itself holds it as
    //       characters; CBACT03C L39 restates the same width in its file-section record; and a
    //       primary account number is an identifier that is never an arithmetic operand, so no
    //       numeric type would earn its representation. The width is part of the contract rather
    //       than a maximum, which is why the column is CHAR and not variable-width: CHAR blank-pads
    //       on read and so reproduces the fixed-width source field, whereas a variable-width column
    //       would silently accept a fifteen-character value no reference program could produce.
    // WHY : Assumptions: columnDefinition restates the migration's own type text so the two can be
    //       compared by eye. It generates nothing, because schema management is switched off; it is
    //       written as a literal rather than assembled from CARD_NUMBER_LENGTH precisely so that it
    //       greps against V1__account.sql L657 as the same string a reader finds there.
    @Id
    @Column(name = "card_num", length = CARD_NUMBER_LENGTH, nullable = false,
            columnDefinition = "char(16)")
    private String cardNum;

    // WHY : Assumptions: both identifiers are Long over BIGINT because CVACT03Y declares them
    //       unsigned numeric -- PIC 9(09) at L6 and PIC 9(11) at L7 -- and eleven digits exceed the
    //       32-bit range, so a narrower integer type would not hold the account identifier at all.
    //       Declaring the two alike keeps them comparable with the identifiers the sibling entities
    //       key on, which the migration also declares BIGINT.
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    // WHY : Refactoring Rationale: this column carries the migrated form of the CXACAIX alternate
    //       index, which the region surfaced to programs as a file in its own right -- defined at
    //       app/csd/CARDDEMO.CSD L63, described at L64 as the alternate index to CCXREF via the
    //       account key, and pointing at the cross-reference alternate-index path at L65 over the
    //       base cluster CCXREF defined at L37. Its replacement is the NON-UNIQUE index
    //       idx_card_xref_account_id, declared in the migration at L726 rather than here because an
    //       index is a property of the schema and declaring it twice would create two definitions a
    //       later change could move apart. Non-uniqueness is load-bearing: one account holds many
    //       cards and therefore many cross-reference rows, so a unique index would refuse valid
    //       seed data. PostgreSQL maintains the index transactionally, so the baseline's separate
    //       index-build step retires with no target.
    // WHY : Alternatives Considered: mapping this as an association to Account rather than as a
    //       plain identifier. Rejected for the reasons recorded on the class above; the decisive one
    //       is that this row's purpose is to be read WITHOUT loading either record it points at.
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the provider requires a no-argument constructor in order to materialise a row,
     * and reaches a protected one by reflection. It is protected rather than public so that
     * application code cannot obtain a half-built row by accident, which on this type would mean a
     * cross-reference whose three members are all absent and which therefore links nothing.</p>
     */
    protected CardXref() {
        // WHY : Assumptions: the body is empty because the provider assigns every member directly
        //       after construction. Assigning defaults here would be overwritten immediately, and
        //       any default this type could invent -- a blank key, a zero identifier -- would be a
        //       value the reference record cannot hold.
    }

    /**
     * Creates a cross-reference row linking one card to its customer and its account.
     *
     * @param cardNum the primary account number this row is keyed on, the mapped form of
     *     {@code XREF-CARD-NUM}; must not be {@code null}
     * @param customerId the customer holding the card, the mapped form of {@code XREF-CUST-ID};
     *     must not be {@code null}
     * @param accountId the account the card draws on, the mapped form of {@code XREF-ACCT-ID};
     *     must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}, because a cross-reference
     *     missing any of its three members links nothing and the column it would map is declared
     *     not-null in the migration
     */
    public CardXref(String cardNum, Long customerId, Long accountId) {
        // WHY : Assumptions: all three arguments are rejected when absent rather than deferred to
        //       the not-null column constraints. A fixed-width reference record has no concept of an
        //       absent field -- an unset one is spaces or zeroes -- so absence is a caller defect,
        //       and failing here names the member, whereas failing at flush names only the
        //       constraint and does so long after the call that caused it.
        this.cardNum = Objects.requireNonNull(cardNum, "cardNum must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    }

    /**
     * Returns the primary account number this row is keyed on.
     *
     * @return the card number, never {@code null} on a persisted row
     */
    public String getCardNum() {
        return this.cardNum;
    }

    /**
     * Assigns the primary account number this row is keyed on.
     *
     * @param cardNum the card number to hold; must not be {@code null}
     * @throws NullPointerException if {@code cardNum} is {@code null}
     */
    public void setCardNum(String cardNum) {
        // WHY : Trade-offs: this reassigns the primary key, which is supported only before the row
        //       is persisted. It exists because the mapping contract for this type is a mutable bean
        //       with a member per column, and withholding one accessor of the three would make that
        //       contract partial for no gain. The compromise is real and is bounded two ways: the
        //       argument is null-checked as the constructor's is, so the member can never become
        //       absent, and the equality contract below is documented as resting on a key that is
        //       assigned once at construction.
        this.cardNum = Objects.requireNonNull(cardNum, "cardNum must not be null");
    }

    /**
     * Returns the customer holding the card.
     *
     * @return the customer identifier, never {@code null} on a persisted row
     */
    public Long getCustomerId() {
        return this.customerId;
    }

    /**
     * Assigns the customer holding the card.
     *
     * @param customerId the customer identifier to hold; must not be {@code null}
     * @throws NullPointerException if {@code customerId} is {@code null}
     */
    public void setCustomerId(Long customerId) {
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
    }

    /**
     * Returns the account the card draws on.
     *
     * @return the account identifier, never {@code null} on a persisted row
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Assigns the account the card draws on.
     *
     * @param accountId the account identifier to hold; must not be {@code null}
     * @throws NullPointerException if {@code accountId} is {@code null}
     */
    public void setAccountId(Long accountId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    }

    /**
     * Compares two cross-reference rows by the card number that identifies them.
     *
     * <p>Assumptions: equality rests on the card number alone, because it is this row's primary key
     * and therefore the only member that identifies it. Comparing all three members was available
     * and is declined: two rows sharing a key are the same row by definition, so a comparison that
     * called them unequal because an identifier had been corrected would contradict the key the
     * migration declares at L695. The key is assigned at construction and is not reassigned on a
     * persisted row, which is what keeps this contract stable for a hash-based collection.</p>
     *
     * <p>Alternatives Considered: comparing runtime classes with {@code getClass()} rather than
     * testing the type with a pattern. Rejected because the persistence provider hands out
     * subclassed proxies for lazily materialised rows, so a class comparison would report a proxy
     * and the row it stands for as different objects. The pattern test accepts both.</p>
     *
     * @param other the object to compare against, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is a cross-reference row with an equal card number,
     *     {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardXref that)) {
            return false;
        }
        return Objects.equals(this.cardNum, that.cardNum);
    }

    /**
     * Returns a hash derived from the card number, matching the equality contract above.
     *
     * <p>Assumptions: the hash is derived from exactly the member equality uses and from no other.
     * That is the contract the two methods share rather than a preference: mixing in an identifier
     * equality ignores would let two equal rows hash differently, so a hash-based collection could
     * hold both as distinct members and a lookup by an equal instance could miss the entry already
     * in it.</p>
     *
     * @return the hash of the card number, or zero on an instance whose card number is not yet
     *     populated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.cardNum);
    }

    /**
     * Renders this row for a log line or a diagnostic, disclosing no full card number.
     *
     * <p>Refactoring Rationale: the rendering an entity receives by default prints every member, so
     * the inherited form would emit the full sixteen-digit primary account number wherever an
     * instance reached a log -- and it reaches one on every persistence failure the provider
     * reports. Worse on this type specifically than on most: a cross-reference row's whole content
     * IS the linkage between a card number, a customer and an account, so rendering all three in
     * full would not merely disclose three values, it would reproduce the reference cross-reference
     * file itself in plain text somewhere no migration control governs.</p>
     *
     * <p>Trade-offs: readability in a log line is traded for confidentiality. Only the last four
     * digits of the card number are shown, behind a marker standing in for the withheld ones, which
     * is the same disclosure the published contracts allow and which the security design requires
     * everywhere except the administrative card-detail endpoint. The cost is that a suffix does not
     * identify the row uniquely, so a reader diagnosing from this string alone may have to
     * disambiguate rows sharing one. That is accepted because rendering nothing identifying at all
     * would leave a string of no diagnostic use, and because the accessors above return the full
     * value to a caller entitled to it. The two identifiers are shown in full: neither is
     * cardholder data on its own, and the linkage they would reveal is already broken by masking the
     * member that names the card.</p>
     *
     * <p>Assumptions: this string is a diagnostic aid and not an output contract. Nothing parses it,
     * and it is not the fixed-width rendering used for parity comparison, so narrowing or
     * reformatting it cannot disturb any compared output.</p>
     *
     * @return a single-line rendering naming the type, the masked card number and the two
     *     identifiers, never {@code null}
     */
    @Override
    public String toString() {
        return "CardXref[cardNum=" + maskedCardNumber()
                + ", customerId=" + this.customerId
                + ", accountId=" + this.accountId + ']';
    }

    /**
     * Renders the card number as the withholding marker followed by at most its last four digits.
     *
     * <p>Assumptions: an absent card number, and one shorter than the disclosed suffix, both yield
     * the marker alone rather than a partial value or a thrown exception. A rendering method called
     * from a diagnostic path must not fail, since the failure would replace the very log entry being
     * written; and a value too short to take a four-digit suffix from is either unpopulated or
     * already invalid, so disclosing what little it holds would give up digits without buying
     * identification. Truncating the marker to the available length was the alternative and is
     * declined, because a shorter marker would be mistakable for a genuine suffix.</p>
     *
     * @return the marker followed by the last four characters of the card number, or the marker
     *     alone when no card number of at least that length is populated; never {@code null}
     */
    private String maskedCardNumber() {
        if (this.cardNum == null || this.cardNum.length() < DISCLOSED_SUFFIX_LENGTH) {
            return MASK_MARKER;
        }
        return MASK_MARKER + this.cardNum.substring(this.cardNum.length() - DISCLOSED_SUFFIX_LENGTH);
    }
}
