package com.carddemo.transaction.domain;

import com.carddemo.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The running balance one account holds within one transaction type and category, mapping
 * {@code ledger.transaction_category_balances}.
 *
 * <p><b>Purpose.</b> One instance is one row of the reference record {@code TRAN-CAT-BAL-RECORD},
 * declared at {@code app/cpy/CVTRA01Y.cpy} lines 4 to 10, whose line 2 states the length as
 * {@code RECLN = 50}. Four elementary items fill the first 28 bytes: the three key components at
 * lines 6 to 8 and {@code TRAN-CAT-BAL PIC S9(09)V99} at line 9. This type carries the shape and the
 * identity of that record and nothing else. It performs no arithmetic, takes no branch and consults
 * no other row.
 *
 * <p>A type declaration accepts no parameter, returns no value and raises nothing, so this block
 * carries no parameter, return or exception at-clause; every constructor and method below carries its
 * own. The inapplicability is stated rather than left silent, because a reader has to be able to tell
 * a declared inapplicability from an oversight.
 *
 * <h2>The key is a real composite, taken from the reference file's own record key</h2>
 *
 * <p>Assumptions: the identity below is not inferred from the column list, it is read off the
 * reference declarations. Line 5 of {@code app/cpy/CVTRA01Y.cpy} declares {@code TRAN-CAT-KEY} as a
 * group item with no {@code PICTURE} of its own, spanning the three level-10 items at lines 6, 7 and
 * 8 for a 17-byte composite. That the group really is the file's key is confirmed outside the
 * copybook: {@code app/cbl/CBACT04C.cbl} selects the file at line 28, declares it
 * {@code ORGANIZATION IS INDEXED} at line 29 and states {@code RECORD KEY IS FD-TRAN-CAT-KEY} at
 * line 31, and its file description at lines 61 to 63 puts {@code 05 FD-TRAN-CAT-KEY.} first in the
 * record. So the reference store is a keyed cluster whose key is exactly that group, and a composite
 * identity here reproduces the access path rather than inventing one.
 *
 * <h2>Rulings recorded at the annotations above</h2>
 *
 * <p>Alternatives Considered: the schema is named explicitly in {@code @Table} rather than left to
 * the connection. The alternative was to rely on the search path this module pins on every pooled
 * connection, at {@code application.yml} line 317,
 * {@code connection-init-sql: SET search_path TO ledger}. It is rejected on two counts. The mapping
 * is then readable on its own, without a reader holding the YAML open beside it; and an unqualified
 * name resolves against whatever search path the connection happens to carry, so a second login
 * reaching this table -- and there is one, described below -- could resolve the same entity somewhere
 * else entirely. Naming the schema costs one attribute and removes that possibility.
 *
 * <p>Alternatives Considered: no {@code @Index} is declared, and the omission is a decision rather
 * than an oversight. Schema generation is switched off for this module at {@code application.yml}
 * line 469, {@code ddl-auto: none}, so index metadata on an entity is never acted on by anything: it
 * would be inert from the moment it was written and free to drift out of step with the migration
 * without any build noticing. The migration at
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} owns every
 * index in this schema, and section 4 of that file declares no index on this table at all -- the
 * composite primary key is its only constraint. An {@code @Index} here would therefore assert an
 * object that does not exist.
 *
 * <p>Alternatives Considered: no {@code @Version} attribute, and the absence is recorded rather than
 * merely left, because an entity without one looks identical whether the omission was reasoned or
 * forgotten. Optimistic concurrency was the plausible alternative, since the wider migration does
 * adopt it elsewhere. It is declined here on the shape of the reference update path: in
 * {@code app/cbl/CBTRN02C.cbl} the paragraph {@code 2700-B-UPDATE-TCATBAL-REC} reads the row at line
 * 474, adds the transaction amount to the value just read at line 527 and rewrites at line 528, all
 * within one unit of work. There is no snapshot of the pre-edit image and no data-changed flag
 * anywhere in it, which is what a before-image check needs and what separation by client think-time
 * makes necessary. The reference system's genuine before-image pattern lives in the account-update
 * and card-update programs, whose records belong to other bounded contexts. A second check settles
 * it mechanically: with generation switched off, a version attribute would map to a column
 * {@code V1__ledger.sql} does not create, so it would fail when a query ran rather than degrade to
 * unversioned behaviour.
 *
 * <p>Trade-offs: {@code FILLER PIC X(22)} at {@code app/cpy/CVTRA01Y.cpy} line 10, occupying
 * one-based bytes 29 to 50, is dropped rather than carried as a member. The compromise accepted is
 * real: the fixed 50-byte image is no longer reconstructible from this type alone, so a reader
 * reconciling 50 bytes against a four-column row has to account for the difference from the copybook
 * rather than from the Java. Reconstructing a fixed-length image is the work of the codecs in
 * {@code com.carddemo.common.codec}, which own record representation for the whole migration.
 *
 * <p>Assumptions: those 22 bytes are padding and not data, and the seed extracts show it directly,
 * because the padding is not even consistent between two datasets of the same vintage. In
 * {@code app/data/ASCII/tcatbal.txt} the trailing 22 bytes of every record are ASCII zero digits,
 * while in {@code app/data/ASCII/dailytran.txt} the trailing 20 bytes are spaces. A member holding
 * either value would store a writer's padding convention and nothing about the balance.
 *
 * <h2>The create-versus-update decision belongs to the batch context, not to this type</h2>
 *
 * <p>Assumptions: this shape is written by two different behaviours, and the branch between them is
 * owned elsewhere. It is recorded here because it is the reason the zero-balance constructor below
 * exists. In {@code app/cbl/CBTRN02C.cbl} the paragraph {@code 2700-UPDATE-TCATBAL} at line 467
 * builds the key from three parts at lines 469 to 471 -- and note that the account identifier comes
 * from {@code XREF-ACCT-ID}, the cross-reference lookup, rather than from the daily record -- resets
 * a flag to {@code 'N'} at line 473, reads at line 474 and sets the flag to {@code 'Y'} on
 * {@code INVALID KEY} at line 478. Line 481 accepts a file status of {@code '00'} <em>or</em>
 * {@code '23'}, so a missing row is a normal outcome there and not an error. Line 495 then branches
 * on that flag: the create paragraph at lines 503 to 510 issues {@code INITIALIZE}, moves the three
 * key components, adds the amount and writes; the update paragraph at lines 526 to 528 adds the
 * amount to the value just read and rewrites.
 *
 * <p>Assumptions: both paths are additive against a base, and the only difference between them is
 * whether that base is zero or the persisted value. That is why this type offers a constructor
 * producing a zero balance and no method that adds to one. Which path runs, and the requirement that
 * the two remain separately observable, belong to {@code batch-service}; nothing here decides it.
 *
 * <h2>The interest program reads this balance; it does not compute here</h2>
 *
 * <p>Assumptions: this is a cross-reference so that a reader knows where the value goes, and not a
 * statement of anything this type does. {@code app/cbl/CBACT04C.cbl} reads the reference file
 * sequentially and accrues against this balance at {@code 1300-COMPUTE-INTEREST}, line 462, whose
 * statement spans lines 464 and 465 as
 * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Two properties of that
 * statement are easy to lose and both belong to whoever implements it: the multiplication is
 * parenthesised in the reference source itself, so the product is formed at full precision before
 * the division; and the word {@code ROUNDED} appears zero times in all 652 lines of that program, so
 * the accrual truncates rather than rounds. The arithmetic and its rounding mode are owned by
 * {@code batch-service} and named on {@link Money}. This type stores the balance and computes
 * nothing.
 *
 * <h2>Fixed-width columns are bound as CHAR explicitly</h2>
 *
 * <p>Assumptions: every member below whose column {@code db/migration/V1__ledger.sql} declares CHAR(n) carries
 * {@code @JdbcTypeCode(SqlTypes.CHAR)} beside its {@code @Column}. There are two such members here --
 * the two-character type code and the four-character category code of the
 * composite key -- and the annotation is not decoration. A Java String otherwise selects the JDBC
 * VARCHAR binding, so the driver sends a varying-length parameter for a column the database
 * has blank padded to its declared width; the two are then compared under padding rules the
 * reference programs never relied on, and a lookup by a value shorter than the declared width
 * can miss a row that is present. This is the same annotation the batch and authorization
 * contexts already carry on their own fixed-width columns, so one mechanism spans the
 * migration rather than one per context.
 *
 * <p>Alternatives Considered: {@code columnDefinition = "CHAR(n)"} on each member, which
 * would also fix the binding. Rejected because it embeds vendor DDL in a mapping that has no
 * authority to create this table -- {@code db/migration/V1__ledger.sql} does -- so the physical width would then be
 * stated in two places able to disagree. The declared length together with the standard CHAR
 * type code says the same thing without a second definition.
 *
 * <p>Trade-offs: a binding is only verifiable where something verifies it, and
 * {@code ddl-auto: none} on the deployed profiles deliberately verifies nothing because the
 * migration owns the schema. {@code src/test/resources/application-test.yml} therefore sets
 * {@code ddl-auto: validate}, so a repository test running against a migrated database fails
 * on a type or width disagreement instead of a deployed environment discovering it.
 *
 * <h2>The batch context agrees with this type through the schema, never through code</h2>
 *
 * <p>Alternatives Considered: {@code batch-service} writes this same table through its own
 * {@code com.carddemo.batch.domain} package under a narrowly scoped cross-schema grant, so neither
 * module imports a type from the other and the only shared artifact is the physical schema. Sharing
 * these entity types across the two modules was one alternative and would have coupled two
 * deployables through code; a saga across two databases was the other. The saga is rejected outright
 * on the shape of the posting unit of work: {@code app/cbl/CBTRN02C.cbl} lines 424 to 444 perform
 * three writes in sequence -- this table at line 440, the account record at line 441 and the posted
 * transaction at line 442 -- inside one commit. A saga would replace that single atomic commit with
 * committed steps plus compensating reversals, making partial-posting states observable that the
 * reference system does not have; a posted transaction with an unposted balance is exactly what a
 * parity comparison would flag, and correctly.
 */
@Entity
@Table(name = "transaction_category_balances", schema = "ledger")
public class TransactionCategoryBalance {

    // Alternatives Considered: the composite is mapped with @EmbeddedId over a nested @Embeddable
    //   rather than with @IdClass. @IdClass is the real alternative and is rejected because it
    //   requires the three key fields to be declared TWICE, once on this entity and once on the
    //   identifier class, so the copybook's 17-byte layout would be written out in two places that
    //   nothing keeps in step. That duplication is precisely what the house convention exists to
    //   prevent: tests/README.md lines 540 to 542 record that the reference unit tests resolve every
    //   record layout through the single compiler copybook path, `cobc -I app/cpy`, via COPY and
    //   never duplicate a layout, and com.carddemo.common is the Java form of that one include path.
    //   A nested @Embeddable keeps the group's three members declared exactly once, in one place,
    //   mirroring the group item at app/cpy/CVTRA01Y.cpy line 5 that they come from.
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    // Assumptions: exact fixed point, never an approximate type. The reference field is
    //   TRAN-CAT-BAL PIC S9(09)V99 at app/cpy/CVTRA01Y.cpy line 9, stored as zoned decimal with the
    //   sign carried in the final byte of the digits rather than in a nibble of its own: in
    //   app/data/ASCII/tcatbal.txt the balance field of the first record occupies one-based bytes 18
    //   to 28 and reads `0000000000{`, whose trailing left-brace carries the digit 0 together with a
    //   positive sign, for 0.00. The migration maps that to NUMERIC(11,2), which is the source
    //   picture's nine integral and two fractional digits and not one digit more. Decoding the
    //   overpunch is the codec package's work; what this member guarantees is that the value it
    //   holds is exact. The scale and the general rounding mode are constants of
    //   com.carddemo.common.money.Money and are deliberately not restated here.
    //
    // Trade-offs: the wire form of this value is a JSON string, applied by the serialization module
    //   in that same package rather than by an annotation here. It costs every client an explicit
    //   parse and makes a payload marginally larger. A JSON number was rejected because most clients
    //   parse one into a binary floating-point value, and an amount such as 504.77 has no exact
    //   binary representation, so the exactness the column and this member both preserve would be
    //   lost at the last hop -- the one hop a user actually sees.
    //
    // Assumptions: only @Column attributes with a runtime effect are declared, so `name` appears and
    //   `precision` and `scale` do not. With generation switched off those two are read by nothing,
    //   which puts them in the same class of inert, driftable metadata as the @Index rejected above;
    //   declaring them would state a width this file cannot enforce. V1__ledger.sql line 869 is the
    //   authority for the column, and Money is the authority for the scale.
    //
    // WHY : Refactoring Rationale: nullable = false was ADDED here and NOT NULL DEFAULT 0 was added
    //   to the column, so the member and the column now agree. They did not before, and the
    //   reasoning that let them disagree is recorded because it was specific and it was wrong twice
    //   over. It held that "the column is nullable in the migration and this member is stricter than
    //   the column, which is deliberate rather than contradictory. That file asserts NOT NULL on
    //   primary key columns only, because a blank fixed-width field decodes to NULL at the load
    //   boundary and asserting more widely would refuse a load the reference system itself accepts."
    //
    //   The second sentence was false when it was written: V1__ledger.sql L265 declares
    //   `proc_ts TIMESTAMP(6) NOT NULL`, and L275 declares `pk_transactions` over `transaction_id`
    //   alone, so a non-key column already carried the constraint. The first sentence's premise does
    //   not hold for THIS field either: there is no blank fixed-width field to decode. All 50 records
    //   of app/data/ASCII/tcatbal.txt carry `0000000000{` in bytes 18-28 -- fifty explicit zoned
    //   zeros, zero blanks, measured -- and the create path INITIALIZEs to zero at
    //   app/cbl/CBTRN02C.cbl L504 before adding at L508. Both normative zoned codecs agree that a
    //   blank body is malformed rather than absent: com.carddemo.common.codec.ZonedDecimalCodec and
    //   carddemo_migration.copybook.zoned each reject a numeric body that is blank or carries a
    //   non-digit, so such a record fails the load instead of loading as NULL.
    //
    // WHY : Assumptions: the guarantee is now about every row and not only about values this type
    //   originates, which is the substantive change. The earlier note was careful to limit itself --
    //   "a row already carrying NULL is materialised by the provider through field assignment and
    //   bypasses all three" -- and that limitation was the defect rather than a caveat on it: a
    //   member that cannot express an absent amount, reading a column that can hold one, yields a
    //   null BigDecimal on a field the arithmetic below then dereferences. The column now forbids the
    //   state, so the hydration path has nothing to admit.
    //
    // WHY : Assumptions: this member and the batch module's mapping of the same table were changed
    //   together with the migration, and that is a requirement rather than tidiness. Two mappings of
    //   one table that disagree about nullability are worse than either being wrong alone, because
    //   whichever one a caller happens to read becomes the answer.
    //
    // WHY : Trade-offs: the width and scale are still not restated as `precision` and `scale`
    //   attributes, for the reason given above the previous note -- they are inert metadata this
    //   file cannot enforce. Only the nullability is declared here, because that one IS enforced: the
    //   provider reads it when it validates a mapping against the live schema, and the schema refuses
    //   the value outright, so the two agree at both layers instead of one asserting what the other
    //   permits.
    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    /**
     * The count of integer digit positions {@code TRAN-CAT-BAL} declares, being nine.
     *
     * <p>Assumptions: read from {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy}
     * line 9. It is NOT the ten {@link Money} admits, and the gap is why this constant exists:
     * {@code Money}'s own bound is the widest money field in the reference set, {@code PIC S9(10)V99},
     * so a ten-integer-digit balance satisfies {@code Money.of} and then overflows the
     * {@code NUMERIC(11,2)} column {@code V1__ledger.sql} declares for this member.</p>
     *
     * <p>Refactoring Rationale: bounding the value here rather than leaving it to the database moves
     * the refusal to the assignment that introduced it. A provider-side numeric-field-overflow names
     * neither the column nor the row and arrives after the surrounding unit of work has performed its
     * other writes, so the record that is wrong is not the record that fails.</p>
     */
    private static final int BALANCE_INTEGER_DIGITS = 9;

    /**
     * Creates an empty instance for the persistence provider to populate when it materialises a row.
     *
     * <p>Assumptions: the specification requires a persistent class to declare a no-argument
     * constructor, which the provider calls to instantiate a managed row before assigning the mapped
     * members reflectively. It is protected rather than public because an instance carrying neither an
     * identity nor a balance is not a state any caller has a use for: a row exists in the table before
     * an instance representing it exists, so the provider is its only legitimate user.</p>
     */
    protected TransactionCategoryBalance() {
        // Assumptions: empty by design; the provider assigns both mapped members after construction,
        //   so there is nothing to initialise here and nothing that could yet be validated.
    }

    /**
     * Creates an instance carrying a supplied identity and balance.
     *
     * @param id the composite identity of the row, being the account, the transaction type code and
     *     the transaction category code that together form the 17-byte group at
     *     {@code app/cpy/CVTRA01Y.cpy} line 5; must not be {@code null}
     * @param balance the running balance for that account, type and category, reduced to the
     *     migration's two-decimal money contract through {@link Money}; must not be {@code null}
     * @throws NullPointerException if {@code id} is {@code null}, or if {@code balance} is
     *     {@code null}, which {@link Money#ofPicture(BigDecimal, int)} refuses because a monetary
     *     field under this contract has no representation for an absent amount
     * @throws ArithmeticException if {@code balance} needs more than the nine integer digits
     *     {@code TRAN-CAT-BAL} declares, which is a narrower bound than {@link Money#of(BigDecimal)}
     *     applies and is the bound the {@code NUMERIC(11,2)} column actually enforces
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal balance) {
        this.id = Objects.requireNonNull(id, "id");
        // Assumptions: the amount is routed through Money rather than assigned as supplied, so the
        //   two-decimal invariant this package states holds for every instance produced here instead
        //   of depending on each caller having already applied it, and the null rejection comes from
        //   the one type that owns the money contract. Assigning the argument directly was the
        //   alternative and would let an instance carry a scale the rest of the money path does not
        //   use, which equality on a decimal value is sensitive to.
        this.balance = Money.ofPicture(balance, BALANCE_INTEGER_DIGITS).amount();
    }

    /**
     * Creates an instance whose balance is zero, for a category that carries no balance yet.
     *
     * <p>Assumptions: a newly created row starts from zero and never from an absent value, and that
     * mirrors the reference create path exactly. In {@code app/cbl/CBTRN02C.cbl} the paragraph
     * {@code 2700-A-CREATE-TCATBAL-REC} issues {@code INITIALIZE TRAN-CAT-BAL-RECORD} at line 504,
     * moves the three key components at lines 505 to 507, and only then adds the transaction amount at
     * line 508. That {@code INITIALIZE} is what sets the balance to zero before anything is added, so
     * zero is the base the first amount accumulates onto and the record has no state in which the
     * balance is unset. This constructor exists so that the same starting point is expressible here
     * without any caller having to know the literal.</p>
     *
     * <p>Assumptions: the zero comes from {@link Money#ZERO} rather than from a literal written here,
     * so the type that owns the money contract is what fixes the scale. A local
     * {@code BigDecimal.ZERO} would carry scale 0, and a local {@code setScale} call would restate a
     * constant that already exists, which is the duplication this package avoids for every shared
     * concern.</p>
     *
     * @param id the composite identity of the row, being the account, the transaction type code and
     *     the transaction category code that together form the 17-byte group at
     *     {@code app/cpy/CVTRA01Y.cpy} line 5; must not be {@code null}
     * @throws NullPointerException if {@code id} is {@code null}, because a balance row with no
     *     identity corresponds to no row of the reference file
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id) {
        this.id = Objects.requireNonNull(id, "id");
        this.balance = Money.ZERO.amount();
    }

    /**
     * Returns the composite identity of this row.
     *
     * @return the identity carrying the account, the transaction type code and the transaction
     *     category code, which together form the 17-byte group at {@code app/cpy/CVTRA01Y.cpy} line
     *     5; never {@code null} on an instance this class constructed
     */
    public TransactionCategoryBalanceId getId() {
        return this.id;
    }

    /**
     * Returns the running balance for this account, transaction type and category.
     *
     * @return the balance as an exact decimal at the two places
     *     {@code TRAN-CAT-BAL PIC S9(09)V99} declares at {@code app/cpy/CVTRA01Y.cpy} line 9; never
     *     {@code null} on an instance this class constructed
     */
    public BigDecimal getBalance() {
        return this.balance;
    }

    /**
     * Replaces the running balance with a supplied value.
     *
     * <p>Alternatives Considered: this mutator replaces the balance rather than adding to it, and
     * there is deliberately no accumulating method beside it. Both reference paths do accumulate --
     * {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at {@code app/cbl/CBTRN02C.cbl} line 508 on the create
     * path and line 527 on the update path -- but the decision of which base to add onto is the
     * create-versus-update branch at line 495, and that branch belongs to {@code batch-service}.
     * Offering an add here would move part of that rule into a type whose contract is shape and
     * identity, leaving two modules each holding half of one behaviour.</p>
     *
     * @param balance the balance to carry, reduced to the migration's two-decimal money contract
     *     through {@link Money}; must not be {@code null}
     * @throws NullPointerException if {@code balance} is {@code null}, which
     *     {@link Money#ofPicture(BigDecimal, int)} refuses because a monetary field under this
     *     contract has no representation for an absent amount
     * @throws ArithmeticException if {@code balance} needs more than the nine integer digits
     *     {@code TRAN-CAT-BAL} declares, which is a narrower bound than {@link Money#of(BigDecimal)}
     *     applies and is the bound the {@code NUMERIC(11,2)} column actually enforces
     */
    public void setBalance(BigDecimal balance) {
        this.balance = Money.ofPicture(balance, BALANCE_INTEGER_DIGITS).amount();
    }

    /**
     * Compares this row with another by composite identity alone.
     *
     * <p>Alternatives Considered: only the identity participates, and including the balance was the
     * alternative. It is rejected because the balance changes on every posting while the row identity
     * does not: two references to the same row, one taken before an accrual and one after, would then
     * compare unequal, and an instance's hash would shift while a hash-based collection still held it.
     * Uniqueness in the schema is asserted on the key and on nothing else, by
     * {@code pk_transaction_category_balances} at {@code V1__ledger.sql} line 806, so identity is what
     * equality here follows.</p>
     *
     * @param other the object to compare against; may be {@code null}
     * @return {@code true} when {@code other} is a category balance carrying an equal composite
     *     identity, and {@code false} otherwise, including when {@code other} is {@code null}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionCategoryBalance)) {
            return false;
        }
        TransactionCategoryBalance that = (TransactionCategoryBalance) other;
        return Objects.equals(this.id, that.id);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return a hash code derived from the composite identity alone, so that it stays stable across a
     *     change of balance for the reason recorded on {@link #equals(Object)}; zero when no identity
     *     has been assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.id);
    }

    /**
     * Returns a diagnostic rendering of the identity alone.
     *
     * <p>Refactoring Rationale: the balance is omitted, and an earlier revision rendered it beside the
     * identity. That revision reasoned that the record at {@code app/cpy/CVTRA01Y.cpy} lines 4 to 10
     * carries three codes and one money amount and nothing else -- no card number, no cardholder name,
     * no other account-holder detail -- so no field on it is reached by the migration's masking rules.
     * The reading of the record is correct and the conclusion does not follow, because it tests the
     * fields against the masking rules only and a running balance is not protected by being
     * impersonal. A rendering reaches a log, which is retained, aggregated and readable by every
     * holder of log access; one line per row across a posting or interest run therefore accumulates
     * into a per-account, per-category statement of what every balance was, which is the substance of
     * this file rather than an incidental detail of it.
     *
     * <p>Refactoring Rationale: THE ACCOUNT IDENTIFIER IS OMITTED TOO, and an earlier revision of this
     * block argued at length that it need not be. That argument ran: the three components are an
     * account identifier and two reference codes, the migration's disclosure rules name the primary
     * account number, the card verification value, the national identifier and the government-issued
     * identifier, the internal account identifier is none of those, and the published contracts of
     * this context carry it in full as eleven digits anyway, so withholding it from a log while
     * publishing it to a client would defend nothing. Two things are wrong with it. The list it checked
     * against is incomplete -- the sensitive-data logging contract in
     * {@code docs/architecture/observability.md} names account and customer identifiers in a clause of
     * their own -- and the appeal to the published contract compares two different surfaces. A response
     * body goes to one authenticated caller that already holds authority over that account and is not
     * retained; a log line is retained, aggregated, and readable by every holder of log access
     * regardless of which accounts they may act on. That a value is disclosed to an authorised
     * requester is not an argument for disclosing it to everyone who can read a log.
     *
     * <p>Assumptions: the omission takes effect through the identity's own rendering rather than by
     * this method reaching past it, so there is exactly one place where a component of this key is
     * withheld. Composing the surviving components here instead would give one key two renderings, and
     * the nested one -- reached whenever an identity is rendered directly, as a persistence-context
     * entry key or a map key is -- would be the one nothing had reviewed.
     *
     * <p>Trade-offs: a reader diagnosing a balance discrepancy from logs alone now sees which CATEGORY
     * was touched, and neither whose row it was nor what it held. Both costs are accepted and both are
     * paid down: the accessors return the identity and the balance to any caller that needs either, a
     * test asserting a balance asserts it through the accessor rather than through this string, and a
     * request-scoped line already carries the correlation identifier
     * {@code com.carddemo.common.web.CorrelationIdFilter} publishes.
     *
     * @return the two reference codes of the composite identity, and neither the account identifier
     *     nor any monetary value
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance[id=" + this.id + "]";
    }

    /**
     * The three-part identity of one category-balance row: account, transaction type code and
     * transaction category code.
     *
     * <p><b>Purpose.</b> This is the migrated form of the group item {@code TRAN-CAT-KEY} declared at
     * {@code app/cpy/CVTRA01Y.cpy} line 5, a 17-byte composite over the three level-10 items at lines
     * 6, 7 and 8, and the key the reference file is defined on. As with the enclosing type, a type
     * declaration accepts no parameter, returns no value and raises nothing, so this block carries no
     * parameter, return or exception at-clause and each constructor and method below carries its
     * own.</p>
     *
     * <p>Assumptions: the type is serializable because the specification requires an embedded
     * identifier to be, and it is nested inside the entity it identifies rather than declared beside
     * it because this package's inventory is closed at five source files -- four entities and the
     * package charter -- so a top-level identifier class would be a sixth. Nesting also puts the
     * three components in the same compilation unit as the record they come from, which is what keeps
     * the layout declared exactly once.</p>
     *
     * <p>Assumptions: no field is renamed in the crossing, and the one visible difference is scoping
     * rather than naming. Each reference item carries a {@code TRANCAT-} prefix, which is a COBOL
     * group-scoping artifact: a flat namespace needs the prefix to keep these items apart from
     * similarly named ones in other records, whereas the table and this class already scope them. The
     * prefix is therefore dropped from the target names while every field itself crosses unchanged,
     * and none of the migration's three documented spelling corrections applies here -- those are the
     * account and card expiration dates and the merchant category code, belonging to the account,
     * card and authorization contexts respectively.</p>
     *
     * <p>Assumptions: one name here is easily confused with a different field in a different record,
     * and the two are kept apart deliberately. The category component below is {@code TRANCAT-CD}, at
     * {@code app/cpy/CVTRA01Y.cpy} line 8. It is not {@code TRAN-CAT-CD}, which is declared at
     * {@code app/cpy/CVTRA05Y.cpy} line 7 inside the 350-byte {@code TRAN-RECORD} that
     * {@code Transaction} maps. The two share a picture of {@code 9(04)} and nothing else: one is a
     * key component of this 50-byte record and the other an ordinary field of another record, so
     * neither citation substitutes for the other and neither name is a shorthand for the other.</p>
     *
     * <p>Alternatives Considered: the three components are documented as required but are not guarded
     * against {@code null} in the constructor. Guarding them here was the alternative and is declined
     * because it would duplicate a constraint the schema already asserts -- all three columns are
     * declared {@code NOT NULL} in {@code V1__ledger.sql} -- and because validating the width and
     * form of a reference field is a representation concern that this module's mapper package owns,
     * as the package charter reserves. The cost accepted is that a partially built identity can be
     * constructed in memory and is refused when it reaches the database rather than at the point of
     * construction.</p>
     */
    @Embeddable
    public static class TransactionCategoryBalanceId implements Serializable {

        /**
         * The serialization version, fixed because an embedded identifier is required to be
         * serializable and a generated value would change with any recompilation.
         */
        private static final long serialVersionUID = 1L;

        // Assumptions: this is the LEADING component, and the declaration order of the three members
        //   in this class is load-bearing rather than cosmetic. It is the copybook's own order --
        //   TRANCAT-ACCT-ID at app/cpy/CVTRA01Y.cpy line 6, TRANCAT-TYPE-CD at line 7, TRANCAT-CD at
        //   line 8 -- which is the order of the group item at line 5 and therefore the order of the
        //   reference file's record key, and V1__ledger.sql line 806 declares the primary key on
        //   (account_id, type_cd, category_cd) to match. The order is confirmed a third time outside
        //   both the copybook and the program, by app/jcl/PRTCATBL.jcl line 52, which sorts the same
        //   file on (TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A).
        //
        // Assumptions: the interest program depends on this component leading, specifically.
        //   app/cbl/CBACT04C.cbl reads the file sequentially -- PERFORM 1000-TCATBALF-GET-NEXT at line
        //   190, inside the loop opened at line 188 -- and takes a control break on account change at
        //   line 194, IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM, resetting its running total at line
        //   200 and fetching the account and cross-reference rows at lines 201 to 205. That logic is
        //   correct only if rows arrive account-major, which is exactly what a leading account
        //   component provides. Reordering these three members would leave every column present and
        //   silently break that control break, so the order is part of the contract.
        //
        // Assumptions: BIGINT rather than a narrower integer is required and not merely chosen. The
        //   declared domain of PIC 9(11) reaches 99999999999, which exceeds the 2147483647 an int32
        //   holds, so a narrower type could not carry the values the picture admits. It maps to an
        //   integer type at all -- unlike the two code components below -- because it is an
        //   identifier and a magnitude rather than a fixed-width code.
        //
        // Assumptions: nullable and updatable are declared here while precision, scale and length are
        //   omitted throughout this file, and the distinction is that these two have a runtime effect
        //   whereas those three are read only by schema generation, which is switched off. Declaring
        //   nullable false mirrors the NOT NULL the migration asserts on all three key columns, and
        //   declaring updatable false states that a primary key component is not rewritten in place.
        @Column(name = "account_id", nullable = false, updatable = false)
        private Long accountId;

        // Assumptions: the SECOND component, TRANCAT-TYPE-CD PIC X(02) at app/cpy/CVTRA01Y.cpy line 7,
        //   occupying one-based bytes 12 to 13. It is carried as text and not as a number because the
        //   picture is alphanumeric, so the two characters are a code rather than a quantity, and the
        //   migration maps it to CHAR(2) at V1__ledger.sql line 741 -- deliberately the same type the
        //   transaction tables in this schema give the same code, so a lookup across them needs no
        //   cast. Its position between the account and the category is what makes an account's rows
        //   arrive grouped by type within the account.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "type_cd", nullable = false, updatable = false)
        private String typeCd;

        // Assumptions: the TRAILING component, TRANCAT-CD PIC 9(04) at app/cpy/CVTRA01Y.cpy line 8,
        //   occupying one-based bytes 14 to 17.
        //
        // Assumptions: two properties of this member were derived one way from the copybook and then
        //   settled the other way by the migration, which is normative for every column name, type,
        //   precision, scale and nullability in this package, and the divergence is recorded rather
        //   than smoothed over. Deriving from the picture alone would narrow a bounded four-digit
        //   numeric code to a small integer, the way the migration narrows a three-digit credit score
        //   elsewhere, and would name the column cat_cd. V1__ledger.sql line 747 instead declares
        //   category_cd CHAR(4) NOT NULL, and its own note at lines 565 to 568 gives the reason: the
        //   value is a code rather than a quantity, and being part of the key makes that stronger
        //   here, not weaker, because 0001 and 1 must not resolve to two different keys. The column
        //   name and the text type therefore follow the migration, and the picture is recorded above
        //   for provenance.
        //
        // Assumptions: the four characters are supplied at their declared width with leading zeros
        //   intact. The column is fixed-width, so a shorter value is stored space-padded and reads
        //   back padded, which would not compare equal to the value written; the first record of
        //   app/data/ASCII/tcatbal.txt carries 0001 in these bytes and that is the form the key takes.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "category_cd", nullable = false, updatable = false)
        private String categoryCd;

        /**
         * Creates an empty identity for the persistence provider to populate.
         *
         * <p>Assumptions: the specification requires a no-argument constructor on an embeddable type,
         * which the provider calls before assigning the three mapped components reflectively. It is
         * protected because an identity carrying none of its three parts identifies nothing, so no
         * caller outside this type's hierarchy has a use for one.</p>
         */
        protected TransactionCategoryBalanceId() {
            // Assumptions: empty by design; the provider assigns all three components after
            //   construction, so there is nothing to initialise here.
        }

        /**
         * Creates a fully specified identity from its three components.
         *
         * @param accountId the account the balance belongs to, from
         *     {@code TRANCAT-ACCT-ID PIC 9(11)} at line 6 of the copybook; must not be {@code null}
         *     on an identity that is to be persisted
         * @param typeCd the transaction type code, from {@code TRANCAT-TYPE-CD PIC X(02)} at line 7,
         *     supplied at its declared width of two characters; must not be {@code null}
         * @param categoryCd the transaction category code, from {@code TRANCAT-CD PIC 9(04)} at line
         *     8, supplied at its declared width of four characters with leading zeros intact; must
         *     not be {@code null}
         */
        public TransactionCategoryBalanceId(Long accountId, String typeCd, String categoryCd) {
            this.accountId = accountId;
            this.typeCd = typeCd;
            this.categoryCd = categoryCd;
        }

        /**
         * Returns the account the balance belongs to.
         *
         * @return the account identifier, never {@code null} on a persisted identity
         */
        public Long getAccountId() {
            return this.accountId;
        }

        /**
         * Returns the transaction type code this balance is held under.
         *
         * @return the two-character type code, never {@code null} on a persisted identity
         */
        public String getTypeCd() {
            return this.typeCd;
        }

        /**
         * Returns the transaction category code this balance is held under.
         *
         * @return the four-character category code with its leading zeros intact, never {@code null}
         *     on a persisted identity
         */
        public String getCategoryCd() {
            return this.categoryCd;
        }

        /**
         * Compares this identity with another across all three components.
         *
         * <p>Assumptions: the specification requires value equality on an embedded identifier,
         * because the provider uses it to decide whether two loaded rows are the same row. All three
         * components participate, and this is the one place in this file where equality over every
         * field is the correct contract rather than the careless one: the enclosing entity compares
         * on identity alone precisely because this type compares on everything. Omitting any one
         * component would make two genuinely different balances compare equal -- two categories of
         * the same type on one account, for instance -- and the provider would then return the first
         * for a lookup of the second.</p>
         *
         * @param other the object to compare against; may be {@code null}
         * @return {@code true} when {@code other} is an identity with the same account, type code and
         *     category code, and {@code false} otherwise, including when {@code other} is
         *     {@code null}
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TransactionCategoryBalanceId)) {
                return false;
            }
            TransactionCategoryBalanceId that = (TransactionCategoryBalanceId) other;
            return Objects.equals(this.accountId, that.accountId)
                    && Objects.equals(this.typeCd, that.typeCd)
                    && Objects.equals(this.categoryCd, that.categoryCd);
        }

        /**
         * Returns a hash code consistent with {@link #equals(Object)}.
         *
         * @return a hash code derived from all three components, so that it agrees with an equality
         *     that also considers all three
         */
        @Override
        public int hashCode() {
            return Objects.hash(this.accountId, this.typeCd, this.categoryCd);
        }

        /**
         * Returns a diagnostic rendering of the two reference components, in key order.
         *
         * <p>Refactoring Rationale: THE ACCOUNT IDENTIFIER IS OMITTED, and an earlier revision
         * rendered it. That revision was explicit that its reason was "narrower than an identity is
         * safe": it checked the three components against the primary account number, the card
         * verification value, the national identifier and the government-issued identifier, found
         * the internal account identifier among none of them, and added that the published contracts
         * of this context carry it in full as eleven digits so withholding it from a log would defend
         * nothing. The care was real and the list was incomplete. The sensitive-data logging contract
         * in {@code docs/architecture/observability.md} names account and customer identifiers in a
         * clause of their own, so the component was protected the whole time; and a response body
         * reaching one authenticated caller who already holds authority over that account is not the
         * same surface as a retained log readable by every holder of log access.</p>
         *
         * <p>Trade-offs: the omission is total rather than partial. Abbreviating a protected value IS
         * masking, and masking has one owner in this context, {@code com.carddemo.transaction.mapper};
         * a second and slightly different rule inside an entity key would give one value two
         * renderings with neither authoritative. This method is also reached from the enclosing type's
         * own rendering, not only directly, so leaving the component here would have re-disclosed
         * through the key exactly what the enclosing type set out to withhold -- which is why the fix
         * belongs here rather than there. What survives is the two reference codes in key order, so
         * the text still reads the way the composite key sorts and still says WHICH CATEGORY a line
         * concerns; what is given up is that two accounts' rows for one category are no longer
         * distinguishable from a log line alone. The accessor returns the identifier to any caller
         * that needs it.</p>
         *
         * @return the type code and the category code, in key order, and no account identifier
         */
        @Override
        public String toString() {
            return "TransactionCategoryBalanceId[typeCd=" + this.typeCd
                    + ", categoryCd=" + this.categoryCd + "]";
        }
    }
}
