package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The per-account pending-authorization summary, one row per account.
 *
 * <p>This is the migrated form of the hierarchical database's ROOT segment {@code PAUTSUM0},
 * declared at 100 bytes in {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} line 28, whose
 * fields are laid out at {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} lines 19 to 31.
 * The segment's key is the account identifier, so this table's primary key is the account identifier
 * and nothing else, and {@link PendingAuthDetail} rows hang beneath it.</p>
 *
 * <p>Assumptions: twelve of that copybook's thirteen level-05 items become members here and the
 * thirteenth is DROPPED. {@code FILLER PIC X(34)} at line 31 is padding: the twelve data fields sum to
 * sixty-six bytes -- {@code 6 + 9 + 1 + (2 x 5) + 6 + 6 + 6 + 6 + 2 + 2 + 6 + 6} -- and the thirty-four
 * that follow exist only to reach the hundred the segment declares. Transformation rule T1 drops
 * {@code FILLER} and requires the drop to be recorded per record, which
 * {@code docs/architecture/data-model-and-schema-mapping.md} does in its per-record inventory at line
 * 838, and the reference system's own relational table shows the same treatment because
 * {@code ddl/AUTHFRDS.ddl} declares no padding column either. The nuance worth keeping in view: a
 * {@code FILLER} carrying a {@code VALUE} clause would be CONTENT rather than padding and could not be
 * dropped on these grounds -- this one carries none. Recording the drop is what lets a later reader
 * tell a deliberately absent member apart from an overlooked field.</p>
 *
 * <p>Assumptions: THREE numeric representations coexist inside this one 100-byte record, and a
 * mapping that assumes a single one of them gives some members the wrong type. Seven fields are
 * {@code COMP-3} packed decimal: {@code PA-ACCT-ID} at line 19, the two limits and two balances at
 * lines 23 to 26, and the two authorization totals at lines 29 and 30. Two are {@code COMP}, a
 * signed two-byte binary halfword -- {@code PA-APPROVED-AUTH-CNT} and {@code PA-DECLINED-AUTH-CNT},
 * both {@code PIC S9(04) COMP}, at lines 27 and 28. One, {@code PA-CUST-ID PIC 9(09)} at line 20,
 * is plain DISPLAY with no {@code USAGE} clause at all. Reaching for one codec across the whole
 * record decodes the two counters as though they were packed and stores plausible small integers
 * that are not the counts the reference program wrote, and nothing about that result looks broken
 * from the outside -- which is why the three regimes are named here rather than left to be inferred
 * from thirteen PICTURE clauses. Each member below takes the type its own declared representation
 * implies.</p>
 *
 * <p>Assumptions: every monetary component is exact fixed point at scale two, never an IEEE-754
 * binary type. The six amounts are declared {@code PIC S9(09)V99 COMP-3}, so the mapped columns are
 * {@code NUMERIC(11,2)} and the Java type is {@link BigDecimal}. Scale two and half-up rounding are
 * the shared kernel's published contract, held as {@code com.carddemo.common.money.Money.SCALE} and
 * {@code com.carddemo.common.money.Money.GENERAL_ROUNDING}, and an amount that leaves this context
 * over the wire is written as a JSON STRING by {@code com.carddemo.common.money.MoneyModule} so that
 * no client parses a cent through a binary approximation. Transformation rule T3 binds this entity as
 * it binds every other, and the prohibition is enforced by the architecture test rather than by
 * review. The precision differs from the child row on purpose: eleven here and twelve on
 * {@link PendingAuthDetail}, because {@code S9(09)V99} and {@code S9(10)V99} are different pictures
 * and carrying one precision across both would silently narrow the wider one.</p>
 *
 * <p>Refactoring Rationale: no packed byte reaches a column, and this states the consequence of the
 * reference structure rather than a judgement on it. The segment holds its account identifier and its
 * six amounts as {@code COMP-3} nibbles, sign included in the low nibble of the final byte; the
 * extract and the mapper decode each one exactly once at the boundary, and nothing in this type
 * decodes a nibble or stores one. Keeping the packed form would put a sign nibble inside a column,
 * where no SQL predicate can read it, so every reader of a balance would have to carry a codec to
 * answer a question an ordinary {@code WHERE} clause answers here. The packed layout itself is
 * untouched under {@code app/}: this adds a decoded path, it does not remove the encoded one.</p>
 *
 * <p>Alternatives Considered: the five occurrences of
 * {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES} at line 22 of that copybook -- exactly ten of
 * the hundred bytes -- become five discrete members mapped to five discrete columns. The obvious
 * alternative, one array-valued member over a PostgreSQL {@code CHAR(2)[]} column, was evaluated and
 * declined on two grounds: the arity of exactly five is part of the reference contract, and five
 * columns let the SCHEMA ITSELF hold that arity where an array would accept a sixth element in
 * silence; and five plain members keep the JPA mapping portable, where an array column needs a
 * provider-specific type. The occurrence number is carried into each member name so the ordinal
 * position of the reference table is not lost. Trade-offs: a caller that wants to walk the five now
 * walks five members instead of one collection, which is the price of having the arity enforced one
 * layer down. The table belongs to THIS segment alone -- {@link PendingAuthDetail} declares no
 * {@code OCCURS} at all -- so nothing about this shape generalises to the child row.</p>
 *
 * <p>Alternatives Considered: this entity declares no optimistic-lock version member, and the
 * omission is a decision rather than an oversight. The account, customer and card entities carry one
 * because the reference programs for those records compare a before-image across a screen turn,
 * which is the situation an optimistic check exists for. No before-image pattern exists anywhere in
 * this context's programs; this row is updated only by the authorization consumer, inside the same
 * transaction that reads it, under the row lock that consumer takes with a select-for-update; and,
 * decisively, the migration that owns the table declares no such column, so adding one would leave
 * this mapping asserting a column that does not exist. The migration is the source of truth for what
 * this type maps, and where the two could disagree the migration wins.</p>
 *
 * <p>Alternatives Considered: the constructor and the accessors below are written out rather than
 * generated. An accessor-generating annotation processor and a generated mapping library were both
 * evaluated and declined for this tree, and {@code docs/CODE_DOCUMENTATION_STANDARD.md} records that
 * decision with the two libraries named at its lines 439 and 443. The reason bites hardest exactly
 * here: Rule 1 line 15 requires a docstring on every method, its Validation Gate at line 43 fails a
 * method that lacks one, and a generated accessor carries no documentation for either a reviewer or
 * the build to read. Explicit members give the same brevity with somewhere for that documentation to
 * live, and the mapping decisions this type embodies -- a dropped padding field, a numeric regime
 * chosen per field, an arity enforced by column count -- are each a place where a generator would
 * have removed the one thing that had to be written down.</p>
 *
 * <p>Trade-offs: the table name on the annotation below is UNQUALIFIED, and it resolves through the
 * connection {@code search_path} that this module's {@code application.yml} pins at its line 125 with
 * {@code connection-init-sql: SET search_path TO "authorization"} -- the same unqualified form the
 * migration itself uses for every object it creates. Naming the schema on the annotation instead would
 * compile it into the mapping, and it would have to be spelled with embedded quotes at every
 * occurrence, because {@code authorization} is a reserved word in this database and
 * {@code CREATE SCHEMA AUTHORIZATION <role>} is valid syntax in its own right, so an unquoted
 * qualification is a syntax error pointing nowhere near its cause rather than a wrong lookup;
 * {@code data-migration/sql/V0__schemas_and_roles.sql} records that trap at its lines 517 to 538,
 * beside the quoted statement at its line 539. The same reasoning is why this module deliberately sets
 * no provider default-schema property where peer modules do, which its {@code application.yml} states
 * at lines 203 to 209. Resolving through one pinned {@code search_path} keeps the quoting concern in a
 * single place instead of on every mapped type.</p>
 */
@Entity
@Table(name = "pending_auth_summary")
public class PendingAuthSummary {

    /**
     * The greatest value the reference counter fields can hold, from {@code PIC S9(04) COMP}.
     *
     * <p>Assumptions: four decimal digits, so 9999 rather than the halfword's own 32767. The schema's
     * check constraint on the two counter columns states the identical bound, and the two are meant to
     * be read together.</p>
     */
    private static final int COUNTER_MAX = 9999;

    /**
     * The least value the reference counter fields can hold, from {@code PIC S9(04) COMP}.
     *
     * <p>Assumptions: the sign is load-bearing rather than decorative, because the purge program
     * DECREMENTS these counters at {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} lines 287
     * to 293, so a negative bound is the correct floor for a signed running total.</p>
     */
    private static final int COUNTER_MIN = -9999;

    /**
     * The account this summary belongs to, and the row's whole key.
     *
     * <p>Assumptions: {@code PA-ACCT-ID PIC S9(11) COMP-3} at line 19 of the copybook, so eleven
     * signed decimal digits. {@link Long} is the target because eleven digits exceed what a
     * thirty-two-bit integer holds; the value is assigned by the caller rather than generated, because
     * an account identifier originates in the account context and is never minted here.</p>
     *
     * <p>Assumptions: the key is the account identifier ALONE, not a composite, and four independent
     * readings of the reference material agree on that. The database description declares
     * {@code FIELD NAME=(ACCNTID,SEQ,U),START=1,BYTES=6,TYPE=P} at
     * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} line 30 -- a unique sequence field of
     * six packed bytes beginning at offset one, with no second component named. The layout agrees,
     * because {@code S9(11) COMP-3} occupies exactly those first six bytes and leaves none of them for
     * anything else. The list program reads the segment on that field and on nothing else, at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} lines 973 to 977, whose qualification
     * at line 976 is {@code WHERE (ACCNTID = PA-ACCT-ID)}; the alternative key move on the intervening
     * line 972 is commented out, so it is dead scaffolding rather than a second access path. And the
     * companion description at {@code ims/DBPAUTX0.dbd}, declared {@code ACCESS=(INDEX,VSAM,PROT)} at
     * its line 18, is the HIDAM PRIMARY index over that same field: its six-byte {@code PAUTINDX}
     * segment at lines 27 to 29 points back with {@code LCHILD NAME=(PAUTSUM0,DBPAUTP0)} and
     * {@code INDEX=ACCNTID} at lines 30 and 31. One row per account is also what makes the child rows
     * addressable by account plus their own key.</p>
     *
     * <p>Assumptions: there is NO secondary access path on this segment, and the negative finding is
     * recorded because its absence is easy to mistake for an omission. A secondary index in this
     * reference system would be declared with an {@code XDFLD} statement, and a search of the whole
     * reference tree finds no {@code XDFLD} anywhere in it -- not in this context, not in any other.
     * The migration therefore declares no index on this table beyond the primary key, and one on the
     * account identifier would merely duplicate that key. The specification's warning that alternate
     * indexes are access paths rather than decoration is about the record-file masters and their three
     * alternate indexes -- {@code CARDAIX}, {@code CXACAIX} and {@code TRANSACT.VSAM.AIX} -- and not
     * one of those three belongs to this context, which reaches its data hierarchically instead. A
     * future reader who adds an index here believing one was missed would be adding a path the
     * reference system never had.</p>
     */
    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /**
     * The customer the account belongs to, {@code PA-CUST-ID PIC 9(09)} at line 20.
     *
     * <p>Assumptions: this is the one plain DISPLAY field in the record and the only numeric that is
     * UNSIGNED. Its picture carries no {@code S} and no {@code USAGE} clause, so its nine bytes are
     * nine ordinary characters with NO sign overpunch -- unlike every amount here, whose sign travels
     * in the low nibble of its final packed byte. The sign-overpunch decoder that the extract applies
     * to the record-file masters must therefore not be applied to this field: reading a trailing
     * character as an overpunch would turn the last digit into a sign and a customer identifier into a
     * different one. {@link Long} is the target because nine digits identify rather than measure, and
     * the migration declares the column {@code BIGINT} for the same reason.</p>
     */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * The one-character authorization status of the account, {@code PA-AUTH-STATUS PIC X(01)} at
     * line 21.
     *
     * <p>Assumptions: this field has NO value domain to carry across, and neither this type nor the
     * migration invents one. Line 21 declares it {@code PIC X(01)} and no {@code 88}-level condition
     * name follows it -- the next line is {@code PA-ACCOUNT-STATUS} -- whereas the child segment closes
     * both of its flags: {@code PA-MATCH-STATUS} at {@code cpy/CIPAUDTY.cpy} line 45 is followed by
     * four condition names at lines 46 to 49, and {@code PA-AUTH-FRAUD} at line 50 by two at lines 51
     * and 52. Those two closed domains become check constraints on the child table; this open one gets
     * neither a constraint nor a Java enumeration, because a domain invented here would reject a value
     * the reference program can legitimately produce. The absence is deliberate fidelity, and it is
     * written down so that the missing constraint reads as a finding rather than as an oversight.</p>
     */
    // WHY : Alternatives Considered: relying on the declared length alone was evaluated and rejected,
    //       because a Java String otherwise selects the JDBC VARCHAR binding and schema validation then
    //       rejects this schema's CHAR columns even though every width agrees. Spelling the physical
    //       type into columnDefinition was rejected too: that would duplicate vendor DDL inside a
    //       mapping which has no authority to create the table. The type code below selects the standard
    //       CHAR binding for reads, writes and validation while leaving the physical definition wholly
    //       with V1__authorization.sql, and it is the same mechanism the batch context's own entities
    //       use for the identical reason.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "auth_status", length = 1)
    private String authStatus;

    /**
     * The first of the five two-character account-status slots at line 22.
     */
    // WHY : Alternatives Considered: the table PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES is five
    //       columns here, account_status_1 through account_status_5 at migration lines 186 to 190,
    //       carrying exactly ten of the segment's hundred bytes. A single CHAR(2)[] array column was
    //       the obvious alternative and was declined because the arity of exactly five is part of the
    //       reference contract: five columns leave that arity enforced by the schema, whereas an array
    //       would accept a sixth element that the fixed-length record cannot hold and the invariant
    //       would survive only as a convention in application code. Five columns also keep the mapping
    //       inside portable JPA, where an array column needs a provider-specific type.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_status_1", length = 2)
    private String accountStatus1;

    /**
     * The second of the five two-character account-status slots at line 22.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_status_2", length = 2)
    private String accountStatus2;

    /**
     * The third of the five two-character account-status slots at line 22.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_status_3", length = 2)
    private String accountStatus3;

    /**
     * The fourth of the five two-character account-status slots at line 22.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_status_4", length = 2)
    private String accountStatus4;

    /**
     * The fifth of the five two-character account-status slots at line 22.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_status_5", length = 2)
    private String accountStatus5;

    /**
     * The account's credit limit, {@code PA-CREDIT-LIMIT PIC S9(09)V99 COMP-3} at line 23.
     */
    @Column(name = "credit_limit", nullable = false, precision = 11, scale = 2)
    private BigDecimal creditLimit;

    /**
     * The account's cash limit, {@code PA-CASH-LIMIT PIC S9(09)V99 COMP-3} at line 24.
     */
    @Column(name = "cash_limit", nullable = false, precision = 11, scale = 2)
    private BigDecimal cashLimit;

    /**
     * The account's credit balance including authorizations not yet posted,
     * {@code PA-CREDIT-BALANCE PIC S9(09)V99 COMP-3} at line 25.
     */
    @Column(name = "credit_balance", nullable = false, precision = 11, scale = 2)
    private BigDecimal creditBalance;

    /**
     * The account's cash balance, {@code PA-CASH-BALANCE PIC S9(09)V99 COMP-3} at line 26.
     */
    @Column(name = "cash_balance", nullable = false, precision = 11, scale = 2)
    private BigDecimal cashBalance;

    /**
     * How many authorizations have been approved against the account,
     * {@code PA-APPROVED-AUTH-CNT PIC S9(04) COMP} at line 27.
     *
     * <p>Assumptions: this and the field below are the record's second numeric regime, and they are
     * the two fields a uniform codec would get wrong. {@code COMP} is a signed BINARY halfword here --
     * two bytes for four digits or fewer -- so it is neither the packed decimal of the seven fields
     * around it nor the plain DISPLAY of the customer identifier. {@link Short} is the exact Java
     * counterpart of that halfword and {@code SMALLINT} the exact column type, so the mapping widens
     * nothing and narrows nothing. The sign is load-bearing rather than decorative: the purge program
     * DECREMENTS these counters at {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} lines 287
     * to 293 as each aged authorization is removed, so both are mutable running aggregates and an
     * unsigned target would be wrong the first time a total was reduced.</p>
     */
    @Column(name = "approved_auth_cnt", nullable = false)
    private Short approvedAuthCount;

    /**
     * How many authorizations have been declined against the account,
     * {@code PA-DECLINED-AUTH-CNT PIC S9(04) COMP} at line 28.
     *
     * <p>Assumptions: identical representation to the field above -- a signed two-byte binary halfword
     * of at most four digits, mapped to {@code SMALLINT} -- and the range the picture allows is
     * narrower than the halfword that stores it, which is why {@link #COUNTER_MIN} and
     * {@link #COUNTER_MAX} bound the increment rather than the storage type's own limits.</p>
     */
    @Column(name = "declined_auth_cnt", nullable = false)
    private Short declinedAuthCount;

    /**
     * The total approved authorization amount, {@code PA-APPROVED-AUTH-AMT S9(09)V99 COMP-3} at
     * line 29.
     */
    @Column(name = "approved_auth_amt", nullable = false, precision = 11, scale = 2)
    private BigDecimal approvedAuthAmount;

    /**
     * The total declined authorization amount, {@code PA-DECLINED-AUTH-AMT S9(09)V99 COMP-3} at
     * line 30.
     */
    @Column(name = "declined_auth_amt", nullable = false, precision = 11, scale = 2)
    private BigDecimal declinedAuthAmount;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a no-argument constructor, which the provider invokes
     * before writing the mapped columns into the fields above. It is protected rather than public
     * because no caller outside this type's hierarchy has a use for a half-built summary.</p>
     */
    protected PendingAuthSummary() {
        // Assumptions: empty by design. The provider assigns every mapped field directly after
        // construction, so initialising one here would write a value immediately overwritten on load.
    }

    /**
     * Creates a summary for an account that has none yet, identified only and otherwise zeroed.
     *
     * <p>Assumptions: a summary is created lazily, on the first authorization for an account, which is
     * the hierarchical database's own behaviour -- a root segment is inserted when a child first needs
     * one. This constructor reproduces {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines
     * 801 to 806 exactly: the reference program initialises every numeric field of the segment to zero
     * and then moves in the account and customer identifiers from the cross-reference row, and nothing
     * else.</p>
     *
     * <p>Refactoring Rationale: this replaces a six-argument form that also took the four limits and
     * balances, and whose documentation asserted they were "properties of the ACCOUNT carried in from the
     * account context". For the two BALANCES that assertion was wrong, and wrong in a way that would have
     * over-reserved credit: the credit balance on this segment counts authorizations taken and not yet
     * posted, which is zero for an account whose first authorization is being decided, whereas the
     * account master's balance counts POSTED transactions. Seeding this field from that one would have
     * started the pending balance at the posted balance and then compared the sum against the same limit,
     * declining requests the reference program approves. The two LIMITS are genuinely the account's, and
     * they arrive through {@link #refreshLimits(BigDecimal, BigDecimal)} on every authorization rather
     * than only at creation, which is where the reference program sets them.</p>
     *
     * @param accountId the account this summary belongs to, {@code XREF-ACCT-ID} at line 805; must not be
     *     {@code null}
     * @param customerId the customer the account belongs to, {@code XREF-CUST-ID} at line 806; must not
     *     be {@code null}
     */
    public PendingAuthSummary(Long accountId, Long customerId) {
        this.accountId = accountId;
        this.customerId = customerId;
        this.creditLimit = BigDecimal.ZERO.setScale(2);
        this.cashLimit = BigDecimal.ZERO.setScale(2);
        this.creditBalance = BigDecimal.ZERO.setScale(2);
        this.cashBalance = BigDecimal.ZERO.setScale(2);
        this.approvedAuthCount = 0;
        this.declinedAuthCount = 0;
        this.approvedAuthAmount = BigDecimal.ZERO.setScale(2);
        this.declinedAuthAmount = BigDecimal.ZERO.setScale(2);
    }

    /**
     * Copies the account's two limits onto this summary.
     *
     * <p>Assumptions: this is {@code MOVE ACCT-CREDIT-LIMIT TO PA-CREDIT-LIMIT} and
     * {@code MOVE ACCT-CASH-CREDIT-LIMIT TO PA-CASH-LIMIT} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 810 and 811, which the reference
     * program performs on every authorization rather than only when it creates the segment. Refreshing
     * each time is what keeps a limit raised on the account master effective for the very next
     * authorization instead of only after the segment is next recreated.</p>
     *
     * <p>Assumptions: the two limits are the only account fields this method takes, and it does not touch
     * either balance. A limit is owned by the account context and mirrored here; the balances are this
     * context's own running totals, and overwriting them from the account master would discard the
     * authorizations they represent.</p>
     *
     * @param refreshedCreditLimit the account's credit limit at scale two; must not be {@code null}
     * @param refreshedCashLimit the account's cash credit limit at scale two; must not be {@code null}
     */
    public void refreshLimits(BigDecimal refreshedCreditLimit, BigDecimal refreshedCashLimit) {
        this.creditLimit = refreshedCreditLimit;
        this.cashLimit = refreshedCashLimit;
    }

    /**
     * Returns the account this summary belongs to.
     *
     * @return the account identifier, never {@code null} on a persisted instance
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Returns the customer the account belongs to.
     *
     * @return the customer identifier, never {@code null} on a persisted instance
     */
    public Long getCustomerId() {
        return this.customerId;
    }

    /**
     * Returns the one-character authorization status of the account.
     *
     * @return the authorization status, or {@code null} when the account has none recorded
     */
    public String getAuthStatus() {
        return this.authStatus;
    }

    /**
     * Returns the first of the five account-status slots.
     *
     * <p>Assumptions: the five slots are read through five accessors rather than one returning a list
     * or an array, because the arity is fixed at five by {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5
     * TIMES} and is enforced by five discrete columns. A collection accessor would admit a fourth or a
     * sixth slot in Java that the schema cannot store, and an array accessor would additionally hand
     * callers a mutable view of persistent state.</p>
     *
     * @return the two stored characters, or {@code null} when the slot is unset
     */
    public String getAccountStatus1() {
        return this.accountStatus1;
    }

    /**
     * Returns the second of the five account-status slots.
     *
     * @return the two stored characters, or {@code null} when the slot is unset
     */
    public String getAccountStatus2() {
        return this.accountStatus2;
    }

    /**
     * Returns the third of the five account-status slots.
     *
     * @return the two stored characters, or {@code null} when the slot is unset
     */
    public String getAccountStatus3() {
        return this.accountStatus3;
    }

    /**
     * Returns the fourth of the five account-status slots.
     *
     * @return the two stored characters, or {@code null} when the slot is unset
     */
    public String getAccountStatus4() {
        return this.accountStatus4;
    }

    /**
     * Returns the fifth of the five account-status slots.
     *
     * @return the two stored characters, or {@code null} when the slot is unset
     */
    public String getAccountStatus5() {
        return this.accountStatus5;
    }

    /**
     * Returns the account's credit limit.
     *
     * @return the credit limit at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getCreditLimit() {
        return this.creditLimit;
    }

    /**
     * Returns the account's cash limit.
     *
     * @return the cash limit at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getCashLimit() {
        return this.cashLimit;
    }

    /**
     * Returns the account's credit balance, including authorizations not yet posted.
     *
     * @return the credit balance at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getCreditBalance() {
        return this.creditBalance;
    }

    /**
     * Returns the account's cash balance.
     *
     * @return the cash balance at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getCashBalance() {
        return this.cashBalance;
    }

    /**
     * Returns how many authorizations have been approved against the account.
     *
     * @return the approved count, never {@code null} on a persisted instance
     */
    public Short getApprovedAuthCount() {
        return this.approvedAuthCount;
    }

    /**
     * Returns how many authorizations have been declined against the account.
     *
     * @return the declined count, never {@code null} on a persisted instance
     */
    public Short getDeclinedAuthCount() {
        return this.declinedAuthCount;
    }

    /**
     * Returns the total approved authorization amount.
     *
     * @return the approved total at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getApprovedAuthAmount() {
        return this.approvedAuthAmount;
    }

    /**
     * Returns the total declined authorization amount.
     *
     * @return the declined total at scale two, never {@code null} on a persisted instance
     */
    public BigDecimal getDeclinedAuthAmount() {
        return this.declinedAuthAmount;
    }

    /**
     * Records an approved authorization against this summary.
     *
     * <p>Assumptions: the three statements this performs are the approved branch of
     * {@code 8400-UPDATE-SUMMARY} at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines
     * 813 to 818, in the reference program's own order: add one to the approved count at line 814, add
     * the approved amount to the approved total at line 815, and add the same amount to the credit
     * balance at line 817.</p>
     *
     * <p>Assumptions: the count, the approved total and the credit balance move TOGETHER, in one
     * method, because they are one accounting fact. Exposing three setters instead would let a caller
     * update the count and forget the balance, and the resulting row would be internally inconsistent
     * with nothing to detect it -- the same class of defect the baseline avoids by updating the segment
     * in one rewrite.</p>
     *
     * <p>Assumptions: the fourth statement of that branch, {@code MOVE 0 TO PA-CASH-BALANCE} at line
     * 818, is deliberately NOT reproduced, and the reason is a whole-tree reading rather than a local
     * judgement: {@code PA-CASH-BALANCE} is written at that one line and nowhere else in the reference
     * tree, and is read only for display at {@code cbl/COPAUS0C.cbl} line 794. Nothing ever adds to it,
     * so the value the reference program leaves is zero and the value this type carries is the zero its
     * constructor set. Re-assigning zero to a member already at zero would add a statement whose only
     * effect is to invite the question of what else writes it. This is recorded because a reader
     * comparing the two side by side will count four statements in the reference branch and three
     * here, and the difference has to read as a finding rather than as an omission.</p>
     *
     * @param amount the approved amount at scale two, added to both the approved total and the credit
     *     balance; must not be {@code null}
     * @throws IllegalStateException if the approved count has already reached the four-digit maximum
     *     that {@code PIC S9(04) COMP} can represent, propagated from
     *     {@link #incremented(Short, String)}; the summary is left unchanged when that happens, so a
     *     caller that catches it holds a row still consistent with the authorizations already recorded
     */
    public void recordApproved(BigDecimal amount) {
        this.approvedAuthCount = incremented(this.approvedAuthCount, "approvedAuthCount");
        this.approvedAuthAmount = this.approvedAuthAmount.add(amount);
        this.creditBalance = this.creditBalance.add(amount);
    }

    /**
     * Records a declined authorization against this summary.
     *
     * <p>Assumptions: a decline moves the count and the declined total and leaves the credit balance
     * alone, because a declined authorization reserves nothing. The reference program's declined branch
     * at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 819 to 821 carries exactly two
     * statements against the approved branch's four -- add one to the declined count at line 820 and add
     * the transaction amount to the declined total at line 821, with no balance statement of any kind --
     * so the asymmetry is the reference program's and not an economy taken here. That asymmetry with
     * {@link #recordApproved(BigDecimal)} is also the whole reason the two are separate methods rather
     * than one method taking a flag.</p>
     *
     * @param amount the declined amount at scale two, added to the declined total only; must not be
     *     {@code null}
     * @throws IllegalStateException if the declined count has already reached the four-digit maximum
     *     that {@code PIC S9(04) COMP} can represent, propagated from
     *     {@link #incremented(Short, String)}; as on the approved path, the summary is left unchanged
     */
    public void recordDeclined(BigDecimal amount) {
        this.declinedAuthCount = incremented(this.declinedAuthCount, "declinedAuthCount");
        this.declinedAuthAmount = this.declinedAuthAmount.add(amount);
    }

    /**
     * Adds one to a counter in a wider type and refuses a result the reference field cannot hold.
     *
     * <p>Refactoring Rationale: the increment was written as {@code (short) (counter + 1)}, and the cast
     * is what made it wrong. The addition itself is performed in {@code int}, so the cast is a NARROWING
     * conversion that discards the high bits without any diagnostic: a counter at the storage type's
     * maximum wraps to its most negative value, and a running total of approvals silently becomes a
     * large negative number that every downstream reader believes. The addition now happens in
     * {@code int}, the result is compared against the bound the copybook sets, and a result outside it is
     * refused rather than stored.</p>
     *
     * <p>Assumptions: the bound is the PICTURE's and not the storage type's. The two counters are
     * {@code PIC S9(04) COMP} at lines 27 and 28 of
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy}, four decimal digits, so the values the
     * reference field can hold are -9999 through 9999 -- narrower than the halfword that stores them,
     * which reaches 32767. Checking at the halfword's limit would let this class store 10000 in a column
     * whose own check constraint refuses it, turning a representable-value question into a database error
     * raised at flush time with no field named.</p>
     *
     * <p>Trade-offs: an approval on an account that has already reached 9999 is refused, and the
     * authorization it belongs to fails rather than being recorded against a wrapped counter. That is the
     * conservative outcome of the two available: the alternatives are to store a value the reference
     * field cannot represent, or to stop counting at the bound and let the total silently understate the
     * approvals -- and a total that lies is worse than a request that fails loudly, because only the
     * second one gets noticed. The same bound is asserted by the schema's own check constraint, so the
     * two writers of these columns -- this listener and the extract load -- are held to one rule.</p>
     *
     * @param counter the current counter value; must not be {@code null}
     * @param fieldName the counter's name, used to identify it in the refusal
     * @return the incremented value, always within the four-digit domain
     * @throws IllegalStateException if the increment would leave the four-digit domain the reference
     *     field declares
     */
    private static Short incremented(Short counter, String fieldName) {
        int next = counter + 1;
        if (next > COUNTER_MAX || next < COUNTER_MIN) {
            throw new IllegalStateException(fieldName + " would reach " + next
                    + ", which is outside the range " + COUNTER_MIN + " to " + COUNTER_MAX
                    + " that PIC S9(04) COMP can hold");
        }
        return Short.valueOf((short) next);
    }
}
