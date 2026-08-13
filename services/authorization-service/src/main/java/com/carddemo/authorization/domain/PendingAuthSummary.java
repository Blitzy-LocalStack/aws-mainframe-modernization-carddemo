package com.carddemo.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
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
 * this context's programs; the four accumulating members are moved by a guarded arithmetic statement
 * computed in the database rather than by writing back a loaded instance, so the interleaving an
 * optimistic check exists to detect cannot arise on the path that moves them; and,
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
     *
     * <p>⚠️ Assumptions: this is PUBLIC because the atomic counter statements need it, and the reason it
     * is needed there is the reason it was not enough here. This type bounds the counters it holds in
     * memory, and the decision path does not go through this type at all -- it advances the two counters
     * with a single qualified {@code update} so that two concurrent requests for one account cannot both
     * read the same total. Those statements had no counter bound of any kind, so the invariant this
     * constant states was asserted on the path that does not write and absent from the path that does.
     * Passing it in is what puts one bound on both.</p>
     *
     * <p>⚠️ Trade-offs: the two paths bound the counters DIFFERENTLY, and the asymmetry is deliberate
     * rather than overlooked. This type REFUSES an increment that would leave the domain, because it can
     * leave the object untouched and let its caller decide; a statement has no such option -- refusing
     * means the constraint raises, the whole decision rolls back, the requester receives no reply at all
     * and the message dead-letters after five receives, which is precisely the outcome the money
     * saturation on those same statements exists to prevent. The statements therefore SATURATE, on the
     * same reasoning and registered as the same class of divergence.</p>
     */
    public static final int COUNTER_MAX = 9999;

    /**
     * The least value the reference counter fields can hold, from {@code PIC S9(04) COMP}.
     *
     * <p>Assumptions: the sign is load-bearing rather than decorative, because the purge program
     * DECREMENTS these counters at {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} lines 287
     * to 293, so a negative bound is the correct floor for a signed running total.</p>
     *
     * <p>⚠️ Assumptions: public for the same reason as {@link #COUNTER_MAX}, and load-bearing on the
     * purge path specifically. The sweep reverses a whole account's expired children in one statement,
     * subtracting accumulated counts rather than stepping by one, so the result can fall an order of
     * magnitude below this floor from a single window -- and the constraint violation that produced
     * abended the run with earlier windows already committed, leaving the table partly purged and no
     * later retention able to complete while such a row existed. Saturating at this floor keeps the
     * sweep running over the rest of the table, which is the identical argument the money floor on that
     * statement already carries.</p>
     */
    public static final int COUNTER_MIN = -9999;

    /**
     * The width of the authorization-status column.
     *
     * <p>Assumptions: {@code PA-AUTH-STATUS PIC X(01)} at {@code cpy/CIPAUSMY.cpy} L21, stored as
     * {@code auth_status CHAR(1)}. One character is the whole of the field, so a longer value is a decode
     * fault rather than a value to truncate.</p>
     */
    private static final int AUTH_STATUS_MAX_LENGTH = 1;

    /**
     * How many status occurrences the segment declares.
     *
     * <p>Assumptions: five, from {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES} at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} line 22. The arity is fixed by that
     * clause, which is why the five occurrences are five discrete members here rather than a collection:
     * the schema then enforces the arity instead of the application checking it. This constant exists so
     * the bulk-load factory can refuse a wrongly-sized array against the declaration rather than against
     * a literal.</p>
     */
    private static final int ACCOUNT_STATUS_OCCURRENCES = 5;

    /**
     * The width of each of the five account-status slots.
     *
     * <p>Assumptions: {@code PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES} at {@code cpy/CIPAUSMY.cpy} L22,
     * stored as five discrete two-character columns rather than an array, for the reason recorded in
     * {@code docs/architecture/data-model-and-schema-mapping.md}: the fixed arity of five is then enforced
     * by the schema itself.</p>
     */
    private static final int ACCOUNT_STATUS_MAX_LENGTH = 2;

    /**
     * The number of decimal places every monetary column on this row stores.
     *
     * <p>Assumptions: the four amounts are {@code NUMERIC(11,2)}, from the {@code S9(09)V99} pictures the
     * segment declares. Scale two is the contract rather than a display choice, so a value of greater scale
     * is refused on the way in instead of being rounded to fit.</p>
     */
    private static final int MONEY_SCALE = 2;

    /**
     * The greatest magnitude any monetary column on this row can hold.
     *
     * <p>Assumptions: nine integer digits and two fractional, which is exactly {@code PIC S9(09)V99 COMP-3}
     * as {@code cpy/CIPAUSMY.cpy} declares all six of this segment's money fields at L23 to L26 and L29 to
     * L30, and exactly the {@code NUMERIC(11,2)} the schema derives from it under transformation rule T1.
     * The bound is stated here so that this type refuses -- or reduces -- an out-of-domain value at the same
     * place it refuses an out-of-domain counter, rather than leaving the database to raise on the insert.</p>
     *
     * <p>⚠️ Assumptions: this domain is one order of magnitude NARROWER than its neighbours, and the
     * asymmetry is the copybook's and not this migration's. The two money columns on
     * {@code pending_auth_detail} are {@code PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L34 to L35,
     * the account master's own limit is {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code cpy/CVACT01Y.cpy}
     * L8, and {@code Money.MAX_MAGNITUDE} is that same ten-digit domain -- so a value this segment legally
     * receives from any of them can be too wide for it. The reference program moves the wider field into the
     * narrower one at {@code cbl/COPAUA0C.cbl} L810 and L811 and adds the wider transaction amount to the
     * narrower declined total at its L821, and a COBOL {@code MOVE} or {@code ADD} into a narrower numeric
     * field discards HIGH-ORDER digits with no diagnostic of any kind.</p>
     *
     * <p>⚠️ Trade-offs: this migration SATURATES at the bound where the reference truncates to it modulo
     * ten to the ninth, and the difference is registered as divergence D-AUTH-SUMMARY-MONEY-DOMAIN in
     * {@code docs/architecture/cobol-to-service-traceability.md}. Truncation is the more faithful arithmetic
     * and is rejected anyway, because the two outcomes are not comparable in consequence: a credit limit of
     * one thousand million truncates to ZERO, so the very next authorization on that account is declined for
     * want of funds, whereas saturating leaves it one cent short of the limit and the account keeps
     * transacting. Widening the column to match its neighbours was also considered and rejected, because
     * transformation rule T1 makes the copybook picture normative for the column type and the sibling
     * transaction context already recorded that same refusal as D-BILLPAY-AMOUNT-WIDTH-REFUSED.</p>
     */
    public static final BigDecimal MONEY_MAX_MAGNITUDE = new BigDecimal("999999999.99");

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
     * Reconstitutes a summary that already exists in the store, in whatever state it holds.
     *
     * <p>Purpose: this is the rehydration entry point, and it exists because neither the constructor above
     * nor the two decision operations below can serve one. That constructor creates the segment state an
     * account's FIRST authorization finds -- every numeric field zero, the two identifiers set -- and the
     * decision operations move a counter, a total and a balance together by arithmetic. A stored row
     * carries arbitrary values in all sixteen components, and there was no way to express one at all: the
     * extract load decoded a populated segment and was then refused, because the only representable state
     * was the empty one.
     *
     * <p>Refactoring Rationale: the mapper previously guarded its own crossing by REFUSING any segment
     * whose status, balances, counters or totals were populated, and documented that refusal as
     * deliberate. The refusal was the right response to the type it had -- populating four of sixteen
     * components and returning the object would have let a caller overwrite a real balance with a
     * constructor's zero -- but it made the load path unimplementable, so the aggregate could not be
     * loaded from the very data this deployment is seeded with. The correct resolution is a factory that
     * accepts and validates ALL SIXTEEN, which is what this is: nothing is defaulted, so nothing can be
     * silently discarded, and the guard that stood in for it is withdrawn.
     *
     * <p>Assumptions: the ONLINE rules are untouched and stay narrower. A caller deciding an
     * authorization still cannot set a counter or a balance directly -- the only way to move either is
     * {@link #recordApproved(BigDecimal)} or {@link #recordDeclined(BigDecimal)}, which move the members
     * that belong together in one step and refuse a counter leaving its four-digit domain. This factory is
     * for the load path, which is not deciding anything: it is restating a state that already exists.
     *
     * <p>Assumptions: every value is validated against the same rule its column declares, so a summary
     * this factory accepts is one the row can hold. The counters are bounded to the four decimal digits
     * {@code PIC S9(04) COMP} declares at {@code cpy/CIPAUSMY.cpy} L27 and L28, which is also the schema's
     * {@code ck_pending_auth_summary_counts}; the status characters are bounded to the widths their columns
     * declare; the four amounts are required present, because their columns are {@code NOT NULL}, and are
     * required to be exact at scale two, because a value of greater scale would be rounded on the way into
     * a {@code NUMERIC(11,2)} column and money is never rounded silently in this migration.
     *
     * <p>Alternatives Considered: taking the mapper's decoded carrier as a single parameter instead of
     * sixteen values. Rejected because it would make this domain type depend on a mapper type, which the
     * layering rule this module is tested against forbids in that direction, and because the carrier holds
     * {@code Money} rather than the exact decimals the columns store. The accepted cost is a long
     * parameter list, mitigated by the load path being the only caller.
     *
     * @param accountId the account this summary belongs to; must not be {@code null} and must be positive
     * @param customerId the customer the account belongs to; must not be {@code null} and must be positive
     * @param authStatus the one-character authorization status, or {@code null} when the slot is blank
     * @param accountStatus1 the first two-character account status slot, or {@code null} when blank
     * @param accountStatus2 the second slot, or {@code null} when blank
     * @param accountStatus3 the third slot, or {@code null} when blank
     * @param accountStatus4 the fourth slot, or {@code null} when blank
     * @param accountStatus5 the fifth slot, or {@code null} when blank
     * @param creditLimit the account's mirrored credit limit, exact at scale two; must not be {@code null}
     * @param cashLimit the account's mirrored cash credit limit, exact at scale two; must not be
     *     {@code null}
     * @param creditBalance the balance of authorizations taken and not yet posted, exact at scale two;
     *     must not be {@code null}
     * @param cashBalance the cash balance the reference program only ever zeroes, exact at scale two; must
     *     not be {@code null}
     * @param approvedAuthCount the count of approved authorizations; must not be {@code null} and must lie
     *     within the four-digit domain
     * @param declinedAuthCount the count of declined authorizations; must not be {@code null} and must lie
     *     within the four-digit domain
     * @param approvedAuthAmount the running total of approved amounts, exact at scale two; must not be
     *     {@code null}
     * @param declinedAuthAmount the running total of declined amounts, exact at scale two; must not be
     *     {@code null}
     * @return the reconstituted summary, never {@code null}
     * @throws NullPointerException if either identifier, either counter or any of the four amounts is
     *     {@code null}
     * @throws IllegalArgumentException if an identifier is not positive, a counter is outside the
     *     four-digit domain, a status character is wider than its column, or an amount carries more than
     *     two decimal places
     */
    public static PendingAuthSummary rehydrated(Long accountId, Long customerId, String authStatus,
            String accountStatus1, String accountStatus2, String accountStatus3,
            String accountStatus4, String accountStatus5, BigDecimal creditLimit,
            BigDecimal cashLimit, BigDecimal creditBalance, BigDecimal cashBalance,
            Short approvedAuthCount, Short declinedAuthCount, BigDecimal approvedAuthAmount,
            BigDecimal declinedAuthAmount) {

        PendingAuthSummary summary = new PendingAuthSummary(
                requirePositive(accountId, "accountId"),
                requirePositive(customerId, "customerId"));
        summary.authStatus = requireWithin(authStatus, "authStatus", AUTH_STATUS_MAX_LENGTH);
        summary.accountStatus1 =
                requireWithin(accountStatus1, "accountStatus1", ACCOUNT_STATUS_MAX_LENGTH);
        summary.accountStatus2 =
                requireWithin(accountStatus2, "accountStatus2", ACCOUNT_STATUS_MAX_LENGTH);
        summary.accountStatus3 =
                requireWithin(accountStatus3, "accountStatus3", ACCOUNT_STATUS_MAX_LENGTH);
        summary.accountStatus4 =
                requireWithin(accountStatus4, "accountStatus4", ACCOUNT_STATUS_MAX_LENGTH);
        summary.accountStatus5 =
                requireWithin(accountStatus5, "accountStatus5", ACCOUNT_STATUS_MAX_LENGTH);
        summary.creditLimit = requireExactAmount(creditLimit, "creditLimit");
        summary.cashLimit = requireExactAmount(cashLimit, "cashLimit");
        summary.creditBalance = requireExactAmount(creditBalance, "creditBalance");
        summary.cashBalance = requireExactAmount(cashBalance, "cashBalance");
        summary.approvedAuthCount = requireCounterInDomain(approvedAuthCount, "approvedAuthCount");
        summary.declinedAuthCount = requireCounterInDomain(declinedAuthCount, "declinedAuthCount");
        summary.approvedAuthAmount = requireExactAmount(approvedAuthAmount, "approvedAuthAmount");
        summary.declinedAuthAmount = requireExactAmount(declinedAuthAmount, "declinedAuthAmount");
        return summary;
    }

    /**
     * Returns an identifier once it is known to be present and positive.
     *
     * <p>Assumptions: the value itself is NOT quoted in the refusal. The sensitive-data logging contract in
     * {@code docs/architecture/observability.md} names account and customer identifiers in a clause of
     * their own, so a message naming the component is as much as can be said.</p>
     *
     * @param candidate the identifier supplied; must not be {@code null}
     * @param component the component's name, reproduced in the refusal
     * @return {@code candidate} unchanged, once it is known to be positive
     * @throws NullPointerException if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} is not positive
     */
    private static Long requirePositive(Long candidate, String component) {
        Objects.requireNonNull(candidate, component + " must not be null");
        if (candidate <= 0L) {
            throw new IllegalArgumentException(
                    component + " must be a positive identifier, and the value supplied was not");
        }
        return candidate;
    }

    /**
     * Returns a status character once it is known to fit the column that stores it.
     *
     * <p>Assumptions: an absent value is admitted and returned unchanged, because every one of these
     * columns is nullable -- the reference segment leaves a blank slot blank and the decode maps a blank
     * field to absence. Only a PRESENT value is width-checked, which is the difference between a slot
     * nobody filled and one filled with more than it can hold.</p>
     *
     * @param candidate the status characters supplied; may be {@code null}
     * @param component the component's name, reproduced in the refusal
     * @param maxLength the width the storing column declares
     * @return {@code candidate} unchanged, once it is known to be absent or within the width
     * @throws IllegalArgumentException if {@code candidate} is present and wider than {@code maxLength}
     */
    private static String requireWithin(String candidate, String component, int maxLength) {
        if (candidate != null && candidate.length() > maxLength) {
            throw new IllegalArgumentException(component + " is " + candidate.length()
                    + " characters but the column declares " + maxLength);
        }
        return candidate;
    }

    /**
     * Returns an amount once it is known to be present and exact at the scale its column stores.
     *
     * <p>Assumptions: a value of SMALLER scale is widened to scale two rather than refused, because two
     * and {@code 2.00} are the same quantity and a decoded packed field of scale zero is an ordinary
     * occurrence. A value of GREATER scale is refused rather than rounded: rounding money silently is
     * forbidden throughout this migration, and a third decimal place in a stored summary is a decode fault
     * worth reporting rather than absorbing.</p>
     *
     * @param candidate the amount supplied; must not be {@code null}
     * @param component the component's name, reproduced in the refusal
     * @return the amount at exactly scale two, never {@code null}
     * @throws NullPointerException if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} carries more than two decimal places
     */
    private static BigDecimal requireExactAmount(BigDecimal candidate, String component) {
        Objects.requireNonNull(candidate, component + " must not be null");
        if (candidate.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(component + " carries " + candidate.scale()
                    + " decimal places where the column stores " + MONEY_SCALE
                    + ", and money is never rounded silently");
        }
        return candidate.setScale(MONEY_SCALE);
    }

    /**
     * Returns a counter once it is known to be present and within the domain its picture declares.
     *
     * <p>Assumptions: the bound is the PICTURE's and not the storage type's, for the reason recorded on
     * {@link #incremented(Short)} -- {@code PIC S9(04) COMP} holds -9999 through 9999 while the
     * halfword that stores it reaches 32767, and the schema's own check constraint enforces the narrower
     * range. A load admitting the wider range would put a value in the aggregate that the row refuses.</p>
     *
     * @param candidate the counter supplied; must not be {@code null}
     * @param component the component's name, reproduced in the refusal
     * @return {@code candidate} unchanged, once it is known to be in domain
     * @throws NullPointerException if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if {@code candidate} is outside the four-digit domain
     */
    private static Short requireCounterInDomain(Short candidate, String component) {
        Objects.requireNonNull(candidate, component + " must not be null");
        if (candidate < COUNTER_MIN || candidate > COUNTER_MAX) {
            throw new IllegalArgumentException(component + " is " + candidate
                    + ", which is outside the range " + COUNTER_MIN + " to " + COUNTER_MAX
                    + " that PIC S9(04) COMP can hold");
        }
        return candidate;
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
        // WHY : ⚠️ Refactoring Rationale: both limits are reduced to this segment's own domain before they
        //       are held, where they were assigned as they arrived. The account master's limit is
        //       PIC S9(10)V99 and this segment's is PIC S9(09)V99, so an account the account context can
        //       legally hold carries a limit this row cannot store -- and the assignment then reached the
        //       database, which refused the whole insert with a numeric-overflow error. The observable
        //       consequence was that the account could not process ANY authorization, not even a small one:
        //       the requester received no decision at all and the message dead-lettered after five
        //       receives. Reducing here keeps the account transacting, and the reduction is what the
        //       reference performs too -- differently, and worse; see MONEY_MAX_MAGNITUDE.
        this.creditLimit = narrowedToStoredDomain(refreshedCreditLimit);
        this.cashLimit = narrowedToStoredDomain(refreshedCashLimit);
    }

    /**
     * Reports whether an amount is too wide for the columns this row stores money in.
     *
     * <p>Assumptions: this is published so that a CALLER can report the reduction, because the reduction
     * itself has to happen inside this type and this type writes no log. The listener and the expiry sweep
     * each name the field they were about to store when they see this return true, which is what makes a
     * saturated value traceable to the request or the row that produced it.</p>
     *
     * @param amount the amount about to be stored, which may be {@code null}
     * @return {@code true} when the amount is present and its magnitude exceeds
     *     {@link #MONEY_MAX_MAGNITUDE}
     */
    public static boolean exceedsStoredDomain(BigDecimal amount) {
        return amount != null && amount.abs().compareTo(MONEY_MAX_MAGNITUDE) > 0;
    }

    /**
     * Reduces an amount to the greatest magnitude this row's money columns can hold, preserving its sign.
     *
     * <p>Assumptions: the reduction SATURATES rather than truncating, and the sign is kept, so the result is
     * monotone in its input -- a larger amount never produces a smaller stored value. That is what
     * distinguishes it from the reference's own narrowing, which discards high-order digits and so maps one
     * thousand million to zero; the trade-off between the two is argued on
     * {@link #MONEY_MAX_MAGNITUDE}.</p>
     *
     * <p>Assumptions: an amount already inside the domain is returned UNCHANGED, including its scale, so
     * this cannot become a second place that rounds money. Only the magnitude is touched, and only when it
     * is out of range.</p>
     *
     * @param amount the amount to store, which may be {@code null}
     * @return the amount when it fits, the signed bound when it does not, or {@code null} for {@code null}
     */
    public static BigDecimal narrowedToStoredDomain(BigDecimal amount) {
        if (!exceedsStoredDomain(amount)) {
            return amount;
        }
        return amount.signum() < 0 ? MONEY_MAX_MAGNITUDE.negate() : MONEY_MAX_MAGNITUDE;
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
     * <p>Assumptions: the four statements this performs are the approved branch of
     * {@code 8400-UPDATE-SUMMARY} at {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines
     * 813 to 818, in the reference program's own order: add one to the approved count at line 814, add
     * the approved amount to the approved total at line 815, add the same amount to the credit balance
     * at line 817, and assign zero to the cash balance at line 818.</p>
     *
     * <p>Assumptions: the count, the approved total and the credit balance move TOGETHER, in one
     * method, because they are one accounting fact. Exposing three setters instead would let a caller
     * update the count and forget the balance, and the resulting row would be internally inconsistent
     * with nothing to detect it -- the same class of defect the baseline avoids by updating the segment
     * in one rewrite.</p>
     *
     * <p>Refactoring Rationale: the fourth statement, {@code MOVE 0 TO PA-CASH-BALANCE} at line 818, is
     * now reproduced, where an earlier revision omitted it. The omission rested on a whole-tree reading
     * that is true as far as it goes -- {@code PA-CASH-BALANCE} is WRITTEN at that one line and nowhere
     * else in the reference tree, and is READ only for display at {@code cbl/COPAUS0C.cbl} line 794 --
     * and concluded from it that the statement assigns zero to a member already at zero, so reproducing
     * it would add a statement with no effect. That conclusion holds for a summary this type CREATED,
     * whose constructor sets the member to zero. It does not hold for a summary the extract load
     * rehydrated: {@link #fromExtract} and {@link #rehydrated} both accept and store the cash balance the
     * segment carried, because a stored segment carries arbitrary values in all sixteen components and
     * the load path could not otherwise represent one. So an account seeded with a non-zero cash balance
     * would keep it here across every subsequent approval while the reference program zeroed it on the
     * first, which is an observable difference on the path this deployment is actually seeded through.
     * Assigning zero costs one statement and removes the difference rather than registering it.</p>
     *
     * <p>Assumptions: the assignment is unconditional on this branch and belongs to the APPROVED arm
     * only. The reference program's declined arm at lines 819 to 821 carries no balance statement of any
     * kind, so a decline leaves whatever the cash balance held -- which is why
     * {@link #recordDeclined(BigDecimal)} does not zero it and the two methods stay separate.</p>
     *
     * <p>⚠️ Assumptions: the count SATURATES at the four-digit maximum instead of raising, so this method
     * cannot fail on an account that has already recorded 9999 approvals. Refactoring Rationale: it used
     * to raise, and the refusal was invisible -- it rolled the message's unit of work back, the queue
     * redelivered, and the request dead-lettered unanswered, so a counter reaching its bound took the
     * account permanently out of service. The reasoning and the reported divergence are recorded on
     * {@link #narrowedCounterToStoredDomain(int)}.</p>
     *
     * @param amount the approved amount at scale two, added to both the approved total and the credit
     *     balance; must not be {@code null}
     */
    public void recordApproved(BigDecimal amount) {
        this.approvedAuthCount = incremented(this.approvedAuthCount);
        // WHY : ⚠️ Assumptions: each running total is reduced to this segment's domain AFTER the addition
        //       rather than the addend being reduced before it, and the order matters. Reducing the addend
        //       would let two in-domain contributions sum past the bound and reach the database, which is
        //       the overflow this reduction exists to prevent; reducing the sum bounds the stored value
        //       whatever the addends were. Both totals are running aggregates the reference adds to at
        //       cbl/COPAUA0C.cbl L815 and L817 with no size check of any kind.
        this.approvedAuthAmount = narrowedToStoredDomain(this.approvedAuthAmount.add(amount));
        this.creditBalance = narrowedToStoredDomain(this.creditBalance.add(amount));
        // WHY : Assumptions: the scale is set rather than left to the constant, because every other
        //       amount this type holds is at scale two and a mixed-scale member would compare equal to
        //       its siblings under compareTo while rendering differently through toString -- the one
        //       asymmetry a reader of a stored row would not think to check for.
        this.cashBalance = BigDecimal.ZERO.setScale(MONEY_SCALE);
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
     * <p>⚠️ Assumptions: the count SATURATES rather than raising, as on the approved path, and this arm is
     * where the bound is reached soonest -- an account with no headroom declines every further request and
     * advances only this counter. See {@link #narrowedCounterToStoredDomain(int)}.</p>
     *
     * @param amount the declined amount at scale two, added to the declined total only; must not be
     *     {@code null}
     */
    public void recordDeclined(BigDecimal amount) {
        this.declinedAuthCount = incremented(this.declinedAuthCount);
        // WHY : ⚠️ Assumptions: the declined total is the one most exposed of the four, which is why the
        //       reduction matters here even though the arithmetic is the same as the approved arm's. The
        //       approved arm is gated by the account's own headroom, so its addend cannot exceed a limit
        //       that itself fits this domain; a DECLINE has no such gate -- the requested amount is
        //       PIC S9(10)V99 and is added whatever it is, so a single request of one thousand million
        //       overflowed the column on its own.
        this.declinedAuthAmount = narrowedToStoredDomain(this.declinedAuthAmount.add(amount));
    }

    /**
     * Withdraws a previously approved authorization from this summary as it expires.
     *
     * <p>Purpose: this is the approved arm of {@code 4000-CHECK-IF-EXPIRED} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} lines 288 to 289, which the purge program
     * runs against the root it is positioned on for each expired child it is about to delete: subtract one
     * from the approved count, and subtract the child's approved amount from the approved total.
     *
     * <p>Assumptions: the credit balance is deliberately NOT reduced, and the asymmetry with
     * {@link #recordApproved(BigDecimal)} -- which DOES add to it -- is the reference program's own. The
     * purge program's approved arm carries exactly two statements and touches no balance field anywhere in
     * its 386 lines. This is recorded because the pairing looks incomplete: a reader who sees the listener
     * add an approved amount to the credit balance will expect the purge to give it back, and it does not,
     * so an account whose authorizations all expire retains the reserved balance until some other
     * process releases it. Reproducing that is required for parity; correcting it here would change an
     * observable balance the reference system leaves standing, which Rule T9 forbids without a documented
     * divergence, and there is no reference behaviour to derive the correction from.
     *
     * <p>⚠️ Assumptions: the count SATURATES at the four-digit MINIMUM rather than raising, which matters
     * on this path because a saturated counter has already lost the excess it could not record, so
     * reversing every expired child can legitimately reach the floor. Raising here abended the whole purge
     * sweep and left the table partly purged; see {@link #narrowedCounterToStoredDomain(int)}.</p>
     *
     * @param amount the approved amount recorded against the expiring authorization, at scale two;
     *     must not be {@code null}
     */
    public void reverseApproved(BigDecimal amount) {
        this.approvedAuthCount = decremented(this.approvedAuthCount);
        this.approvedAuthAmount = this.approvedAuthAmount.subtract(amount);
    }

    /**
     * Withdraws a previously declined authorization from this summary as it expires.
     *
     * <p>Purpose: this is the declined arm of {@code 4000-CHECK-IF-EXPIRED} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} lines 291 to 292: subtract one from the
     * declined count, and subtract the child's amount from the declined total.
     *
     * <p>Assumptions: the two arms take their amount from DIFFERENT fields of the same expiring
     * authorization -- the approved arm subtracts the approved amount at line 289 while this arm subtracts
     * the transaction amount at line 292 -- so the caller must supply the field its response code selects
     * and cannot pass one amount to whichever arm it takes. That distinction is invisible from inside this
     * type, which sees only a scale-two amount, so it is stated here and enforced at the call site in the
     * purge job.
     *
     * <p>⚠️ Assumptions: the count SATURATES at the four-digit minimum rather than raising, for the reason
     * recorded on {@link #reverseApproved(BigDecimal)}.</p>
     *
     * @param amount the transaction amount of the expiring authorization, at scale two; must not be
     *     {@code null}
     */
    public void reverseDeclined(BigDecimal amount) {
        this.declinedAuthCount = decremented(this.declinedAuthCount);
        this.declinedAuthAmount = this.declinedAuthAmount.subtract(amount);
    }

    /**
     * Subtracts one from a counter in a wider type and narrows a result the reference field cannot hold.
     *
     * <p>Assumptions: the arithmetic is performed in {@code int} and the result checked before it is
     * narrowed, for the same reason {@link #incremented(Short)} does so -- a cast applied to the
     * result of the subtraction would be a narrowing conversion that wraps without any diagnostic.
     *
     * <p>Trade-offs: the bound applied is -9999 rather than zero, so a counter already at zero decrements
     * to minus one rather than being narrowed. That looks like a missing guard and is a deliberate one. The
     * reference field is SIGNED four digits at {@code cpy/CIPAUSMY.cpy} lines 27 and 28, the reference
     * program subtracts with no floor test of its own, and the schema's own check constraint
     * {@code ck_pending_auth_summary_counts} admits the negative half of that range -- so a negative
     * counter is a representable state of the reference system that a caller may legitimately be
     * reproducing from an extract. Refusing it here would make this type stricter than both the copybook
     * and the column, and would turn an out-of-step extract into an unrecoverable load failure rather than
     * a value a reader can see and question.
     *
     * @param counter the current counter value; must not be {@code null}
     * @return the decremented value, always within the four-digit domain
     */
    private static Short decremented(Short counter) {
        return narrowedCounterToStoredDomain(counter - 1);
    }

    /**
     * Adds one to a counter in a wider type and narrows a result the reference field cannot hold.
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
     * <p>⚠️ Trade-offs: an approval on an account that has already reached 9999 is RECORDED against a
     * counter resting at 9999, and the total then understates the approvals. Refactoring Rationale: this
     * paragraph argued the opposite -- that refusing the request was better because "a total that lies is
     * worse than a request that fails loudly, because only the second one gets noticed" -- and the premise
     * is what failed. The refusal is not loud: on the authorization path it rolls the message's unit of
     * work back, the queue redelivers it, and after five receives the request dead-letters unanswered, so
     * nobody is notified and the account stops being answerable at all. The understatement is bounded, is
     * recognisable as a value resting exactly on the bound, and is reported by both writers; the outage was
     * neither bounded nor reported. The full argument, the rejected truncation alternative and the
     * registered divergence are on {@link #narrowedCounterToStoredDomain(int)}.</p>
     *
     * @param counter the current counter value; must not be {@code null}
     * @return the incremented value, always within the four-digit domain
     */
    private static Short incremented(Short counter) {
        return narrowedCounterToStoredDomain(counter + 1);
    }

    /**
     * Reports whether a counter value has left the four-digit domain the reference field declares.
     *
     * @param candidate the value a counter would take, of type {@code int}
     * @return {@code true} when {@code candidate} is outside {@link #COUNTER_MIN} to
     *     {@link #COUNTER_MAX}
     */
    public static boolean exceedsCounterDomain(int candidate) {
        return candidate > COUNTER_MAX || candidate < COUNTER_MIN;
    }

    /**
     * Reduces a counter value to the four-digit domain the reference field declares.
     *
     * <p>⚠️ Refactoring Rationale: the policy for a counter that leaves its domain is SATURATION, and it
     * used to be refusal -- the two increment helpers raised {@code IllegalStateException} at the bound
     * and the Trade-offs block on the increment argued that "a total that lies is worse than a request
     * that fails loudly". The measurement that overturns it is that the failure is not loud. Nothing on
     * the authorization path presents this refusal to anyone: it rolls the message's whole unit of work
     * back, the queue redelivers, and after five receives the request dead-letters unanswered -- so an
     * account reaching 9999 approvals stopped being answerable at all, permanently, and the reference it
     * is compared against does not stop: {@code cbl/COPAUA0C.cbl} L815 and L821 add to
     * {@code PIC S9(04) COMP} fields with no size clause, discarding high-order digits and carrying on.
     * The money members of this same row already saturate for exactly this reason, argued on
     * {@link #MONEY_MAX_MAGNITUDE}, so refusal here also meant one row followed two policies.</p>
     *
     * <p>Trade-offs: a saturated counter understates the number of authorizations, which is a real loss
     * of information and is why the narrowing is REPORTED at every writer -- {@code event=} lines in
     * {@code AuthorizationRequestListener} and {@code PurgeJob} name the account and the member -- and
     * why the divergence from the reference's truncation is registered as
     * {@code D-SUMMARY-COUNTER-SATURATION}. Truncating as the reference does was the other candidate:
     * rejected because discarding high-order digits turns 10000 into 0, which is not merely imprecise but
     * WRONG in a way a reader cannot detect, whereas a value resting at exactly the bound is recognisable
     * as saturated.</p>
     *
     * <p>Alternatives Considered: widening the column so the bound is unreachable. Rejected because the
     * column mirrors {@code PIC S9(04) COMP} and the extract that loads it carries four digits, so a
     * wider column would hold values no extract could round-trip and no reference reader could
     * represent.</p>
     *
     * <p>Assumptions: the narrowing is NOT reported from here. This is a domain type with no logger --
     * every sibling aggregate in this service is the same -- and it does not know the account whose
     * counter it is narrowing, which is the one identifier a report has to carry. The two writers that do
     * know it report it: {@code AuthorizationRequestListener} emits
     * {@code event=auth.summary.counter-narrowed} and {@code PurgeJob} emits
     * {@code event=authorization.purge.counter-narrowed}, both naming the account and the member.</p>
     *
     * @param candidate the value a counter would take, of type {@code int}
     * @return {@code candidate} when it is in domain, otherwise the bound it exceeded, never
     *     {@code null}
     */
    public static Short narrowedCounterToStoredDomain(int candidate) {
        if (!exceedsCounterDomain(candidate)) {
            return Short.valueOf((short) candidate);
        }
        return Short.valueOf((short) (candidate > COUNTER_MAX ? COUNTER_MAX : COUNTER_MIN));
    }

    /**
     * Reconstitutes a summary that already carries running state, for the bulk-load path only.
     *
     * <p>Purpose: the ordinary constructor above creates a summary in the shape
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} lines 801 to 806 produce -- every
     * numeric zeroed, every status unset -- and the running totals move from there only through
     * {@link #recordApproved(java.math.BigDecimal)} and {@link #recordDeclined(java.math.BigDecimal)}.
     * That is the correct and only shape for the authorization decision path. It is NOT sufficient for
     * the segment load, whose input is an extract of a database that has been running: a parent record
     * written by {@code cbl/PAUDBUNL.CBL} carries the account's authorization status, its five status
     * occurrences, both balances and all four counters, and every one of those is state this aggregate
     * would otherwise drop on the floor.</p>
     *
     * <p>Alternatives Considered: replaying the stored counters through {@code recordApproved} and
     * {@code recordDeclined} so that no new entry point was needed. Rejected on two independent grounds:
     * the counters and the amounts are stored independently, so no sequence of replays reproduces an
     * arbitrary pair of them, and neither operation can assign a status occurrence or a balance at all.
     * A replay would therefore have produced a summary that differed from the extract it was built
     * from, silently.</p>
     *
     * <p>Alternatives Considered: exposing setters for the twelve components instead. Rejected because
     * that would let the decision path assign a balance or a counter directly, which is exactly the
     * mutation this aggregate exists to prevent -- the running totals are its invariant. A single named
     * factory whose name says what it is for keeps the decision path unable to reach it by accident,
     * because the factory returns a NEW instance and cannot be applied to one the decision path holds.
     * </p>
     *
     * <p>Trade-offs: the parameter list is wide, and a wide list invites a positional mistake. It is
     * preferred to a carrier type because the only carrier available is the mapper's own decoded record,
     * and taking that here would make this domain type depend on the mapper package -- an inversion the
     * module's layering rules forbid and an architecture test enforces. The mapper is the only caller,
     * it builds the call from its own named components, and its round-trip test is what would catch a
     * transposition.</p>
     *
     * @param accountId the account key; must not be {@code null}
     * @param customerId the owning customer; must not be {@code null}
     * @param storedAuthStatus the stored authorization status, or {@code null} when unset
     * @param storedStatuses the five stored status occurrences in order, any of which may be
     *     {@code null}; must not be {@code null} and must hold exactly five entries
     * @param storedCreditLimit the stored credit limit at scale two; must not be {@code null}
     * @param storedCashLimit the stored cash credit limit at scale two; must not be {@code null}
     * @param storedCreditBalance the stored credit balance at scale two; must not be {@code null}
     * @param storedCashBalance the stored cash balance at scale two; must not be {@code null}
     * @param storedApprovedCount the stored approved counter; must not be {@code null}
     * @param storedDeclinedCount the stored declined counter; must not be {@code null}
     * @param storedApprovedAmount the stored approved total at scale two; must not be {@code null}
     * @param storedDeclinedAmount the stored declined total at scale two; must not be {@code null}
     * @return a summary holding exactly the stated state, never {@code null}
     * @throws NullPointerException if a parameter documented as required is {@code null}
     * @throws IllegalArgumentException if {@code storedStatuses} does not hold exactly five entries
     */
    public static PendingAuthSummary fromExtract(Long accountId, Long customerId,
            String storedAuthStatus, String[] storedStatuses,
            BigDecimal storedCreditLimit, BigDecimal storedCashLimit,
            BigDecimal storedCreditBalance, BigDecimal storedCashBalance,
            Short storedApprovedCount, Short storedDeclinedCount,
            BigDecimal storedApprovedAmount, BigDecimal storedDeclinedAmount) {
        Objects.requireNonNull(storedStatuses, "storedStatuses must not be null");
        if (storedStatuses.length != ACCOUNT_STATUS_OCCURRENCES) {
            throw new IllegalArgumentException("storedStatuses must hold exactly "
                    + ACCOUNT_STATUS_OCCURRENCES + " entries, matching the OCCURS clause the segment"
                    + " declares, but held " + storedStatuses.length);
        }

        PendingAuthSummary restored = new PendingAuthSummary(
                Objects.requireNonNull(accountId, "accountId must not be null"),
                Objects.requireNonNull(customerId, "customerId must not be null"));
        restored.authStatus = storedAuthStatus;
        restored.accountStatus1 = storedStatuses[0];
        restored.accountStatus2 = storedStatuses[1];
        restored.accountStatus3 = storedStatuses[2];
        restored.accountStatus4 = storedStatuses[3];
        restored.accountStatus5 = storedStatuses[4];
        restored.creditLimit = Objects.requireNonNull(storedCreditLimit,
                "storedCreditLimit must not be null, because the column is declared not null");
        restored.cashLimit = Objects.requireNonNull(storedCashLimit,
                "storedCashLimit must not be null, because the column is declared not null");
        restored.creditBalance = Objects.requireNonNull(storedCreditBalance,
                "storedCreditBalance must not be null, because the column is declared not null");
        restored.cashBalance = Objects.requireNonNull(storedCashBalance,
                "storedCashBalance must not be null, because the column is declared not null");
        restored.approvedAuthCount = Objects.requireNonNull(storedApprovedCount,
                "storedApprovedCount must not be null, because the column is declared not null");
        restored.declinedAuthCount = Objects.requireNonNull(storedDeclinedCount,
                "storedDeclinedCount must not be null, because the column is declared not null");
        restored.approvedAuthAmount = Objects.requireNonNull(storedApprovedAmount,
                "storedApprovedAmount must not be null, because the column is declared not null");
        restored.declinedAuthAmount = Objects.requireNonNull(storedDeclinedAmount,
                "storedDeclinedAmount must not be null, because the column is declared not null");
        return restored;
    }
}
