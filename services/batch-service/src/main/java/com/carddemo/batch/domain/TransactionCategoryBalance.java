package com.carddemo.batch.domain;

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
 * The per-account, per-type, per-category running balance that posting writes and accrual reads.
 *
 * <p>This is the migrated form of {@code 01 TRAN-CAT-BAL-RECORD}, declared at
 * {@code app/cpy/CVTRA01Y.cpy} lines 4 to 10, whose own line 2 describes the record as
 * {@code RECLN = 50}. Four named fields become four columns; the trailing {@code FILLER} becomes
 * nothing. Two baseline programs govern what this type has to be able to express, and they ask for
 * different things: {@code app/cbl/CBTRN02C.cbl} <b>writes</b> it, through a create arm and an
 * update arm that stay separately observable, and {@code app/cbl/CBACT04C.cbl} <b>reads</b> it
 * sequentially as the driving input of interest accrual, using its balance as the multiplicand of
 * the one formula in this migration that has to agree to the cent.</p>
 *
 * <h2>The derivation, with the byte arithmetic that checks it</h2>
 *
 * <p>Offsets are zero-based and are recorded because they are the audit trail for the mapping:
 * every column below can be traced back to a byte range of the 50-byte record.</p>
 *
 * <pre>
 * offset  bytes  copybook field (line)      PICTURE     column        SQL type
 *      0     11  TRANCAT-ACCT-ID (L6)       9(11)       account_id    BIGINT
 *     11      2  TRANCAT-TYPE-CD (L7)       X(02)       type_cd       CHAR(2)
 *     13      4  TRANCAT-CD (L8)            9(04)       category_cd   CHAR(4)
 *     17     11  TRAN-CAT-BAL (L9)          S9(09)V99   balance       NUMERIC(11,2)
 *     28     22  FILLER (L10)               X(22)       dropped       none
 * </pre>
 *
 * <p>The last row is what makes the mapping checkable: the named fields end at offset 28, the
 * {@code FILLER} occupies the remaining 22 bytes, and 28 plus 22 is 50, which is the record length
 * the copybook declares. The first three rows together span offsets 0 to 16 and are the group item
 * {@code TRAN-CAT-KEY} at {@code app/cpy/CVTRA01Y.cpy} line 5, so the key is 17 bytes wide.</p>
 *
 * <p>Every width above is confirmed from three further places that were written independently of
 * the copybook and of each other, which is what allows the offsets to be relied upon rather than
 * recomputed by the next reader:</p>
 *
 * <ul>
 *   <li>The file description at {@code app/cbl/CBACT04C.cbl} lines 61 to 67 repeats the three key
 *       components at the same widths -- {@code PIC 9(11)} at line 64, {@code PIC X(02)} at line 65
 *       and {@code PIC 9(04)} at line 66 -- and then collapses the remainder into a single
 *       {@code FD-FD-TRAN-CAT-DATA PIC X(33)} at line 67. 11 plus 2 plus 4 plus 33 is also 50.</li>
 *   <li>{@code app/jcl/TCATBALF.jcl} defines the cluster with {@code KEYS(17 0)} at line 40, a
 *       17-byte key at offset zero, which only balances if the three components are 11, 2 and 4
 *       bytes wide, and declares {@code RECORDSIZE(50 50)} at line 41.</li>
 *   <li>{@code app/jcl/PRTCATBL.jcl} names every field for its sort utility in one-based positions:
 *       {@code TRANCAT-ACCT-ID,1,11,ZD} at line 47, {@code TRANCAT-TYPE-CD,12,2,CH} at line 48,
 *       {@code TRANCAT-CD,14,4,ZD} at line 49 and {@code TRAN-CAT-BAL,18,11,ZD} at line 50, and its
 *       line 52 sorts on {@code (TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)}, which fixes
 *       the component order as well as the widths.</li>
 * </ul>
 *
 * <p>Assumptions: the copybook rather than the file description is normative, under the migration
 * plan's transformation rule T1, and the difference between the two is exactly why that matters
 * here. The file description's {@code X(33)} remainder is the balance and the {@code FILLER} fused
 * into one unnamed run of bytes, so it cannot say where the balance ends and the padding begins;
 * that view is deliberate rather than deficient, because accrual needs only the key for its control
 * break and reaches the balance through the copybook it also copies, at
 * {@code app/cbl/CBACT04C.cbl} line 97. Only {@code app/cpy/CVTRA01Y.cpy} lines 9 and 10 separate
 * the two, which is what fixes the balance at 11 bytes and the padding at 22. The two views agree:
 * 11 plus 22 is 33.</p>
 *
 * <p>Assumptions: the {@code FILLER} at {@code app/cpy/CVTRA01Y.cpy} line 10 is dropped rather than
 * mapped, under transformation rule T1, because trailing {@code FILLER} in a fixed-length record is
 * padding to the declared length and carries no value a program reads or writes. Its width is
 * recorded in the table above anyway, so that a reader who has to reconstruct the 50-byte form can
 * still see how many pad bytes the record needs and where they begin. The committed expectation
 * files show what the padding actually holds, which is not what an empty field would suggest: in
 * {@code tests/golden/posting/happy_path/tcatbal.expected} bytes 29 to 50 are 22 zero characters
 * rather than blanks, because the row originates in a loaded dataset rather than from the create
 * arm below.</p>
 *
 * <h2>Two naming artifacts in the baseline, noted and carried as they are</h2>
 *
 * <p>Assumptions: the Java member and column names below follow the <b>copybook</b>, because
 * transformation rule T1 makes the copybook normative and a file description a program-local view.
 * Two names in the sources are worth pointing at so that a reader does not take either for a
 * transcription slip in this file, and neither is altered here or anywhere else:</p>
 *
 * <ul>
 *   <li>The file description's trailing field carries a doubled prefix,
 *       {@code FD-FD-TRAN-CAT-DATA} at {@code app/cbl/CBACT04C.cbl} line 67. It is cited with that
 *       spelling wherever it is cited.</li>
 *   <li>The copybook names the third key component {@code TRANCAT-CD} at
 *       {@code app/cpy/CVTRA01Y.cpy} line 8, not the symmetric {@code TRANCAT-CAT-CD} that the two
 *       components beside it would suggest. The column this mapping targets is
 *       {@code category_cd}, so the Java member is {@code categoryCd}: the migration named the
 *       column for the concept rather than transliterating the abbreviation, and this mapping names
 *       what the schema actually declares.</li>
 * </ul>
 *
 * <h2>The account identifier arrives from the cross-reference, never from the transaction</h2>
 *
 * <p><b>This is the single most consequential behavioural fact about this type.</b> Posting builds
 * the lookup key from two different sources. {@code app/cbl/CBTRN02C.cbl} line 469 moves
 * {@code XREF-ACCT-ID} into the account component -- the identifier returned by the card
 * cross-reference read at lines 380 to 392 -- while lines 470 and 471 move
 * {@code DALYTRAN-TYPE-CD} and {@code DALYTRAN-CAT-CD} from the transaction being posted.</p>
 *
 * <p>The daily-transaction record carries <b>no account identifier at all</b>:
 * {@code app/cpy/CVTRA06Y.cpy} declares a card number and no account. So code that tries to derive
 * the account for this key from the transaction record is not merely wrong, it has nothing to read.
 * The cross-reference read that supplies it is the same read whose {@code INVALID KEY} branch sets
 * reject reason 100 with the text {@code 'INVALID CARD NUMBER FOUND'} at
 * {@code app/cbl/CBTRN02C.cbl} line 386, which is why a transaction whose card is unknown never
 * reaches this table at all.</p>
 *
 * <h2>Two jobs, and the demands they place on this mapping differ</h2>
 *
 * <p>Assumptions: posting writes this table through a create arm and an update arm whose
 * <b>arithmetic is identical</b>, and the migration plan's section 0.5.1.7 requires the two to stay
 * distinguishable and separately tested. The branch is at {@code app/cbl/CBTRN02C.cbl} lines 467 to
 * 501, the paragraph {@code 2700-UPDATE-TCATBAL}: line 473 clears the create flag
 * {@code WS-CREATE-TRANCAT-REC}, declared {@code PIC X(01) VALUE 'N'} at line 190; the read at
 * lines 474 to 479 sets it to {@code 'Y'} on {@code INVALID KEY}; line 481 accepts file status
 * {@code '00'} <b>or</b> {@code '23'} as non-fatal, {@code '23'} being record-not-found and
 * therefore the create case rather than an error; and lines 495 to 499 branch on the flag.</p>
 *
 * <p>The create arm at lines 503 to 524 issues {@code INITIALIZE TRAN-CAT-BAL-RECORD} at line 504,
 * moves the three key components at lines 505 to 507, adds the transaction amount at line 508 and
 * {@code WRITE}s at line 510. The update arm at lines 526 to 542 adds the same amount at line 527
 * and {@code REWRITE}s at line 528. <b>The only difference between them is the
 * {@code INITIALIZE}</b>, which zeroes the balance before the add, so on the create arm the
 * resulting balance equals the transaction amount exactly. This type therefore offers both
 * starting points -- a zero-seeded construction for the create arm and a load-then-set path for the
 * update arm -- and neither is collapsed into the other.</p>
 *
 * <p>Assumptions: this table is the <b>first</b> of three writes that share one commit.
 * {@code app/cbl/CBTRN02C.cbl} lines 440 to 442 perform, in order, the category-balance update at
 * line 440, the account update at line 441 and the transaction write at line 442. The migration
 * plan's section 0.4.1.3 records that a saga with compensating reversals was considered for that
 * unit of work and <b>rejected</b>, because it would make partial-posting states observable -- a
 * posted transaction with an un-updated balance -- that do not exist in the baseline and that the
 * golden masters would correctly report as a parity failure. The order is worth preserving in the
 * Java even though no database constraint requires it, so that a reader tracing one against the
 * other finds the same sequence.</p>
 *
 * <p>Assumptions: accrual reads this table <b>sequentially</b> and drives a control break off it,
 * which places an ordering obligation on whatever query materialises this type.
 * {@code app/cbl/CBACT04C.cbl} lines 28 to 32 select the file as {@code ORGANIZATION IS INDEXED}
 * with {@code ACCESS MODE IS SEQUENTIAL} and {@code RECORD KEY IS FD-TRAN-CAT-KEY}, and lines 188
 * to 205 are the break: line 190 fetches the next row, line 194 compares its account against the
 * previous one held in {@code WS-LAST-ACCT-NUM}, and on a change lines 195 to 203 flush the
 * previous account's accumulated interest and load the new account. <b>A control break is only
 * correct if the input arrives grouped by account.</b> The baseline gets that free from the
 * cluster's key order; a relational query has no default order at all, so a query feeding this
 * pattern has to state {@code ORDER BY account_id, type_cd, category_cd} -- the key order -- and
 * omitting it makes the break fire spuriously, flush one account repeatedly and update that
 * account's balance more than once in a run.</p>
 *
 * <h2>The table is not this module's to own</h2>
 *
 * <p>{@code ledger.transaction_category_balances} belongs to {@code transaction-service}, which
 * creates it through its own migration at
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} lines 787 to
 * 885. This module writes it under the narrowly-scoped cross-schema grant that the migration plan's
 * section 0.4.1.3 records as <b>the one documented exception to database-per-service purity in the
 * whole migration</b>, and the reason the exception exists is the three-write unit of work above:
 * keeping those three writes in one ACID commit is what keeps partial-posting states unobservable.
 * The grants themselves are created by {@code data-migration/sql/V0__schemas_and_roles.sql}.</p>
 *
 * @see Account
 * @see CardXref
 * @see DailyTransaction
 */
// Alternatives Considered: reusing transaction-service's own mapping of this table instead of
//     declaring a local one. Rejected on three independent grounds. The migration plan's section
//     0.5.3.1 forbids one service from importing another's domain package, and the ArchUnit rule
//     A2 in com.carddemo.common.architecture.LayeringRulesTest enforces that at build time, so
//     the import would fail the reactor rather than a review. A compile-time dependency between
//     two independently deployable services also reintroduces exactly the coupling a bounded
//     context exists to remove: the two would then have to be released together. And common-lib
//     cannot host the type as a shared one, because it ships no persistence provider by design.
//     The cost accepted is that one table has two mappings, which is why the paragraph above
//     states that the owning migration -- not either mapping -- is the authority on the shape.
// Alternatives Considered: naming the schema explicitly here rather than leaving it to the
//     connection's search path. Rejected because this module spans four schemas at three
//     different grant levels -- batch, which it owns; ledger and account, on which it holds
//     scoped write grants; and reference, on which it holds SELECT only -- so no single search
//     path can disambiguate them. Naming the schema also puts the grant boundary in view at the
//     mapping site, where a reader deciding whether a write is legal is actually looking.
@Entity
@Table(name = "transaction_category_balances", schema = "ledger")
public class TransactionCategoryBalance {

    // WHY : Alternatives Considered: the 17-byte group at app/cpy/CVTRA01Y.cpy line 5 is mapped as
    //       an @EmbeddedId over a nested @Embeddable, and all four available shapes were weighed
    //       because this is the most consequential structural decision in the file.
    //       (a) A separate top-level file for the key. Rejected: the package charter in
    //       package-info.java closes this package at eight entity types and nine compilation units,
    //       and the key has no meaning and no consumer outside this one entity.
    //       (b) An @IdClass. Rejected: it requires every key field to be declared twice, once on
    //       the identity class and again on the entity, so the offsets and types above would exist
    //       in two places and could drift apart -- the exact failure the offset table exists to
    //       prevent.
    //       (c) A Java record for the key. Rejected for a hard persistence reason rather than a
    //       stylistic one: an embeddable type must be non-final and must expose a no-argument
    //       constructor for the provider to instantiate it reflectively, and a record is
    //       implicitly final and has neither. This is stated so that the class below is not
    //       modernised into a record later on the assumption that nothing depended on its form.
    //       (d) A nested @Embeddable, chosen: one file, each key field declared exactly once, and
    //       the grouping mirrors the source, where TRAN-CAT-KEY is itself a group item containing
    //       the three components rather than three independent fields.
    // WHY : Refactoring Rationale: the key is composite rather than a surrogate because the
    //       composite IS the record's identity in the baseline, and replacing it would be a change
    //       of contract dressed as a convenience. The file is keyed on precisely this group:
    //       app/cbl/CBACT04C.cbl line 31 declares RECORD KEY IS FD-TRAN-CAT-KEY, app/jcl/TCATBALF.jcl
    //       line 40 defines the cluster with KEYS(17 0), and app/cbl/CBTRN02C.cbl lines 469 to 471
    //       build that group element by element before every read. A surrogate identifier would add
    //       a column with no counterpart in the source, would leave the natural key still needing a
    //       uniqueness constraint of its own, and would cost an extra lookup on the posting path,
    //       since the three components are exactly what that path already holds.
    // WHY : Assumptions: @EmbeddedId is the one structural annotation this DDL-passive mapping must
    //       declare, and it is not an exception to the passivity stated above. It maps the primary
    //       key the owning migration already created at V1__ledger.sql lines 883 and 884 as
    //       pk_transaction_category_balances over (account_id, type_cd, category_cd); without it
    //       the type is not a valid entity at all, because a mapped class has to identify its rows.
    @EmbeddedId
    private TransactionCategoryBalanceId id;

    // WHY : Assumptions: the precision is derived and not chosen. TRAN-CAT-BAL PIC S9(09)V99 at
    //       app/cpy/CVTRA01Y.cpy line 9 is nine integral digits plus two fractional ones, which is
    //       eleven display bytes and therefore NUMERIC(11,2); app/jcl/PRTCATBL.jcl line 50 declares
    //       the same field to its sort utility as eleven bytes of zoned decimal, independently.
    // WHY : Assumptions: this is the NARROWER of the two money precisions that meet in the posting
    //       unit of work, and the difference is deliberate. The account side is a digit wider --
    //       ACCT-CURR-BAL, ACCT-CREDIT-LIMIT and ACCT-CASH-CREDIT-LIMIT are PIC S9(10)V99 at
    //       app/cpy/CVACT01Y.cpy lines 7 to 9, as are the two cycle accumulators at lines 13 and 14,
    //       giving twelve bytes and NUMERIC(12,2). Widening this column to match the account side
    //       would accept values the source record cannot represent, and would swallow the class of
    //       load defect that the narrower column reports.
    // WHY : Alternatives Considered: an exact decimal rather than a binary floating-point member or
    //       an integer count of cents. Binary floating point is forbidden outright by the migration
    //       plan's transformation rule T3, and the prohibition is executable rather than advisory --
    //       rule A3 in com.carddemo.common.architecture.LayeringRulesTest fails the build on a
    //       float, a double or either wrapper anywhere under com.carddemo. An integer count of cents
    //       was the more plausible alternative and is rejected because it fits nothing on either
    //       side of this member: the zoned-decimal codec in com.carddemo.common.codec decodes to a
    //       scaled decimal, the column is NUMERIC, and the committed expectation files compare
    //       formatted decimal text, so cents would add two conversions and two rounding
    //       opportunities to a path that currently has neither.
    // WHY : Assumptions: the picture is SIGNED and negative balances are legitimate values rather
    //       than defects, so no positivity or non-negativity constraint may be placed on this
    //       member. app/cbl/CBTRN02C.cbl adds the transaction amount straight onto this balance on
    //       both arms, at line 508 and at line 527, and that amount is signed: line 548 tests
    //       DALYTRAN-AMT >= 0 precisely so that it can route a negative amount to the account's
    //       debit accumulator instead of its credit accumulator. Constraining this member positive
    //       would reject a refund.
    // WHY : Assumptions: the sign decode boundary is com.carddemo.common.codec.ZonedDecimalCodec and
    //       is never re-declared here, under transformation rule T2. That boundary is load-bearing
    //       rather than incidental: tests/README.md section 5.2 records that the baseline harness
    //       must compile with -fsign=EBCDIC because the default ASCII convention misreads the sign
    //       overpunch and silently corrupts negative balances, which is the same corruption a
    //       second decode implemented here could reintroduce.
    // WHY : Assumptions: this member is the multiplicand of the one formula in the migration that
    //       has to agree to the cent, which is why its scale is load-bearing rather than cosmetic.
    //       app/cbl/CBACT04C.cbl lines 464 and 465 compute
    //       WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200 with the multiplication
    //       parenthesised in the source. This value is scale 2 and the rate is scale 2, so the raw
    //       product is scale 4 before the division; transformation rule T4 requires the Java to form
    //       that product at full precision and only then divide with an explicit scale and rounding
    //       mode, because dividing first and multiplying second changes the intermediate precision
    //       and yields a different final cent on many balances.
    // WHY : Assumptions: that division discards its surplus digits in the target exactly as the
    //       reference does -- the program carries no ROUNDED phrase on any statement at all, which was
    //       measured rather than inferred. com.carddemo.common.money.Money declares two modes:
    //       GENERAL_ROUNDING is half up under transformation rule T3 and governs every other
    //       reduction, and BASELINE_INTEREST_ROUNDING truncates and governs the accrual quotient
    //       alone, because the plan pins this formula to the reference at its section 0.7.3. No cent
    //       differs, so no divergence is registered. Line 467 then adds each row's already-reduced
    //       result into the account total, so an account's accrual is the sum of per-row reduced values
    //       and never the reduction of a summed product -- which is why Money deliberately offers no
    //       sum-then-round helper for a caller to reach for here.
    // WHY : Alternatives Considered: a domain method on this type that added an amount to the
    //       balance, mirroring the ADD statements at app/cbl/CBTRN02C.cbl lines 508 and 527.
    //       Rejected so that every monetary operation in this module passes through Money, which
    //       keeps the single arithmetic boundary that transformation rules T3 and T4 require and
    //       that rule A3 checks in one place. This type therefore holds a value and never computes
    //       one: the service layer forms the new balance through Money and assigns it through the
    //       mutator below, and the multiply-before-divide order stays inside the one type that owns
    //       it rather than being restated at each call site.
    // WHY : Refactoring Rationale: nullable = false was ADDED here to match the NOT NULL the owning
    //       migration now declares on this column at V1__ledger.sql line 869. The note this replaces
    //       held that "the column is nullable in the owning migration and this member does not
    //       restate a NOT NULL that the schema does not assert", that the migration declares NOT
    //       NULL "on the three primary-key columns only", and that balance was left nullable
    //       "because a blank fixed-width field decodes to an absent value at the load boundary". The
    //       first two parts are now false, and the third was never true of THIS field: all 50
    //       records of app/data/ASCII/tcatbal.txt carry an explicit zoned zero in the balance field
    //       and not one carries blanks, and the reference create path INITIALIZEs the record to zero
    //       at app/cbl/CBTRN02C.cbl line 504 before adding at line 508, so zero is the base rather
    //       than an absent value. The second part was already false before this change: the same
    //       migration asserts NOT NULL on proc_ts at V1__ledger.sql line 265, a column its table
    //       does not key on.
    // WHY : Assumptions: the guarantee is therefore about every row rather than only about values
    //       this type originates, and that widening is the substantive change. Both public
    //       constructors and the mutator route through Money, which has no representation for an
    //       absent amount, but a row already holding NULL is materialised by field assignment and
    //       bypasses all three -- yielding a null on the one member the posting arithmetic
    //       dereferences. The column now forbids the state, so the hydration path has nothing to
    //       admit. The three primary-key columns remain NOT NULL, at V1__ledger.sql lines 810, 816
    //       and 822.
    // WHY : Assumptions: there is no decode path that produces an absent balance, which is what makes
    //       the constraint safe as well as correct. com.carddemo.common.codec.ZonedDecimalCodec
    //       refuses a numeric body that is blank or carries a non-digit, and
    //       carddemo_migration.copybook.zoned refuses the same, so a blank TRAN-CAT-BAL field is a
    //       load FAILURE rather than an absent value. The picture agrees: a signed zoned display
    //       field always carries eleven digit positions and a sign overpunch, and the value meaning
    //       "no balance" is 0.00, which is a value.
    // WHY : Trade-offs: leaving it nullable cost more than it saved. app/cbl/CBTRN02C.cbl adds the
    //       transaction amount into this balance at line 508 on the create path and line 527 on the
    //       update path, and COBOL arithmetic over a display field has no null to propagate -- so a
    //       null reaching the Java would either raise at the addition, far from the row that
    //       introduced it, or be silently coerced to zero by a caller defending against it, which
    //       would post a transaction against a balance the ledger never held. Asserting the
    //       constraint at the mapping and in the schema makes the row that is wrong the row that
    //       fails.
    @Column(name = "balance", precision = 11, scale = 2, nullable = false)
    private BigDecimal balance;

    /**
     * The count of integer digit positions {@code TRAN-CAT-BAL} declares, being nine.
     *
     * <p>Assumptions: read from {@code TRAN-CAT-BAL PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy}
     * line 9. It is NOT the ten that {@link Money} admits, and the difference is the whole reason this
     * constant exists: {@code Money}'s own bound is the widest money field in the reference set,
     * {@code PIC S9(10)V99}, so a ten-integer-digit balance satisfies {@code Money.of} and then
     * overflows the {@code NUMERIC(11,2)} column above.</p>
     *
     * <p>Refactoring Rationale: bounding the value here rather than letting the database bound it moves
     * the refusal to the assignment that introduced it. A provider-side numeric-field-overflow names
     * neither the column nor the row, arrives after the surrounding unit of work has already done its
     * other writes, and on a batch step reports as a failed chunk rather than as a bad record.</p>
     */
    private static final int BALANCE_INTEGER_DIGITS = 9;

    /**
     * Creates an empty instance for the persistence provider to populate when it materialises a row.
     *
     * <p>Assumptions: the specification requires a persistent class to declare a no-argument
     * constructor, which the provider calls to instantiate a managed row before assigning the mapped
     * members reflectively. It is {@code protected} rather than {@code public} because the provider
     * reaches a non-public constructor without difficulty while application code cannot, which keeps
     * an instance carrying neither an identity nor a balance -- a value on which equality is
     * meaningless -- out of reach of a caller who has a fully specified constructor available.</p>
     */
    protected TransactionCategoryBalance() {
        // Assumptions: the body is deliberately empty because the provider assigns both mapped
        //     members after construction, so anything initialised here would be overwritten on a
        //     hydrate. Seeding the balance to zero here would be actively misleading rather than
        //     merely redundant: a row that failed to populate would then read as a genuine zero
        //     balance, and zero is a meaningful value on this table rather than an absent one --
        //     tests/golden/posting/reject_102_overlimit/tcatbal.expected is a real row whose
        //     balance is exactly zero.
    }

    /**
     * Creates an instance carrying a supplied identity and running balance.
     *
     * <p>Assumptions: both arguments arrive in their target representation rather than their
     * baseline one. The balance is a signed decimal that
     * {@code com.carddemo.common.codec.ZonedDecimalCodec} has already decoded from zoned decimal
     * with its sign overpunch. This constructor decodes nothing, computes nothing and enforces no
     * business rule.</p>
     *
     * <p>Assumptions: this is the load-then-set entry point, and it is also what the update arm of
     * posting reads through. It is kept distinct from the zero-seeded constructor below so that the
     * create arm and the update arm of {@code app/cbl/CBTRN02C.cbl} stay separately expressible, as
     * the migration plan's section 0.5.1.7 requires.</p>
     *
     * @param id the {@link TransactionCategoryBalanceId} composite identity of the row -- the
     *     account, the transaction type code and the transaction category code that together form
     *     the 17-byte group at {@code app/cpy/CVTRA01Y.cpy} line 5; must not be {@code null}
     * @param balance the {@link BigDecimal} running balance for that account, type and category,
     *     reduced to the migration's two-decimal money contract through {@link Money}; signed,
     *     because {@code TRAN-CAT-BAL PIC S9(09)V99} is signed; must not be {@code null}
     * @throws NullPointerException if {@code id} is {@code null}, or if {@code balance} is
     *     {@code null}, which {@link Money#ofPicture(BigDecimal, int)} refuses because a monetary
     *     field under this contract has no representation for an absent amount
     * @throws ArithmeticException if {@code balance} needs more than the nine integer digits
     *     {@code TRAN-CAT-BAL} declares, which {@link Money#ofPicture(BigDecimal, int)} reports
     *     against the declared picture rather than against the widest money field in the set
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id, BigDecimal balance) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        // WHY : Assumptions: the amount is routed through Money rather than assigned as supplied, so
        //       the two-decimal invariant holds for every instance this constructor produces instead
        //       of depending on each caller having applied it first. Assigning the argument directly
        //       was the alternative and would let an instance carry a scale the rest of the money
        //       path does not use, which matters because equality on an exact decimal is sensitive
        //       to scale: a balance of 0 and a balance of 0.00 are unequal values of that type.
        this.balance = Money.ofPicture(balance, BALANCE_INTEGER_DIGITS).amount();
    }

    /**
     * Creates an instance whose balance is zero, for a category that carries no balance yet.
     *
     * <p>Assumptions: a newly created row starts from zero rather than from an absent value, and
     * that mirrors the create arm of posting exactly. In {@code app/cbl/CBTRN02C.cbl} the paragraph
     * {@code 2700-A-CREATE-TCATBAL-REC} issues {@code INITIALIZE TRAN-CAT-BAL-RECORD} at line 504,
     * moves the three key components at lines 505 to 507, and only then adds the transaction amount
     * at line 508. That {@code INITIALIZE} is the whole of the difference between the create arm and
     * the update arm, and it is what makes zero the base the first amount accumulates onto. This
     * constructor exists so that the same starting point is expressible here without any caller
     * having to write the literal, and so that a test can assert which arm ran.</p>
     *
     * <p>Assumptions: the zero comes from {@link Money#ZERO} rather than from a locally written
     * literal, so the type that owns the money contract is what fixes the scale. A bare
     * {@code BigDecimal.ZERO} would carry scale 0 and would compare unequal to every other zero
     * balance in this module, and a local rescaling call would restate a constant that already
     * exists -- the duplication transformation rule T2 exists to prevent.</p>
     *
     * @param id the {@link TransactionCategoryBalanceId} composite identity of the row -- the
     *     account, the transaction type code and the transaction category code that together form
     *     the 17-byte group at {@code app/cpy/CVTRA01Y.cpy} line 5; must not be {@code null}
     * @throws NullPointerException if {@code id} is {@code null}, because a balance row with no
     *     identity corresponds to no row of the baseline file
     */
    public TransactionCategoryBalance(TransactionCategoryBalanceId id) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.balance = Money.ZERO.amount();
    }

    /**
     * The composite identity of this row: account, transaction type, then transaction category.
     *
     * @return the identity whose three components form the 17-byte group at
     *     {@code app/cpy/CVTRA01Y.cpy} line 5; never {@code null} on an instance either public
     *     constructor produced
     */
    public TransactionCategoryBalanceId getId() {
        return id;
    }

    /**
     * The running balance accumulated for this account, transaction type and category.
     *
     * @return the balance as a signed exact decimal at the two places
     *     {@code TRAN-CAT-BAL PIC S9(09)V99} declares at {@code app/cpy/CVTRA01Y.cpy} line 9, which
     *     may be zero or negative; never {@code null} on an instance either public constructor
     *     produced
     */
    public BigDecimal getBalance() {
        return balance;
    }

    /**
     * The eleven-digit account component of this row's key.
     *
     * <p>Assumptions: the value reached the key from the card cross-reference and not from the
     * transaction being posted, per {@code app/cbl/CBTRN02C.cbl} line 469. The provenance is
     * restated on this accessor because a caller reading an account identifier off a balance row
     * may reasonably assume the transaction supplied it, and the transaction record has no such
     * field to supply.</p>
     *
     * @return the account identifier, or {@code null} on an instance whose identity the persistence
     *     provider has not yet populated
     */
    public Long getAccountId() {
        return id == null ? null : id.getAccountId();
    }

    /**
     * The two-character transaction type component of this row's key.
     *
     * @return the transaction type code, or {@code null} on an instance whose identity the
     *     persistence provider has not yet populated
     */
    public String getTypeCd() {
        return id == null ? null : id.getTypeCd();
    }

    /**
     * The four-character transaction category component of this row's key.
     *
     * @return the transaction category code with its leading zeros intact, or {@code null} on an
     *     instance whose identity the persistence provider has not yet populated
     */
    public String getCategoryCd() {
        return id == null ? null : id.getCategoryCd();
    }

    /**
     * Replaces the running balance with a value the caller has already computed.
     *
     * <p>Assumptions: this is the update arm's write path, and the arithmetic deliberately sits
     * outside it. {@code app/cbl/CBTRN02C.cbl} line 527 adds the transaction amount onto the
     * balance in place; the migrated equivalent forms that sum through
     * {@code com.carddemo.common.money.Money} in the service layer and assigns the result here, so
     * that the single monetary arithmetic boundary transformation rules T3 and T4 require is not
     * duplicated into this package. The argument is nonetheless routed through {@link Money} on the
     * way in, which normalises its scale without performing any arithmetic on it.</p>
     *
     * <p>Assumptions: only the balance is mutable, and that is what makes this type safe to place in
     * a hash-based collection. Equality and hashing below read the identity alone, so a balance
     * changed by this method cannot move an instance that a collection is already holding.</p>
     *
     * @param balance the new {@link BigDecimal} running balance, signed and exact, as computed by
     *     the caller through {@link Money}; must not be {@code null}
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
     * Compares this row with another for relational identity, using the composite key alone.
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when the argument is a category balance whose composite key is equal to
     *     this one's
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // Assumptions: a pattern match is used rather than an exact class comparison because
        //     the provider may return an instrumented subclass for a lazily-loaded reference, and
        //     a strict class comparison would then report a row as unequal to itself. No subclass
        //     of this type is authored, so widening the test costs nothing.
        if (!(other instanceof TransactionCategoryBalance that)) {
            return false;
        }
        // Alternatives Considered: including the balance in the comparison was evaluated and
        //     rejected, and on this type the reason is sharper than on an immutable one. The
        //     balance is the single mutable member, and posting mutates it: were equality to read
        //     it, an instance's equality and hash would change under the very addition that lines
        //     508 and 527 of app/cbl/CBTRN02C.cbl perform, so an instance placed in a hash-based
        //     collection before the add would become unfindable in it afterwards. The composite
        //     key already discriminates every row the table can hold, since no two rows may share
        //     it, so reading the balance would add no discrimination and would cost correctness.
        return Objects.equals(id, that.id);
    }

    /**
     * Produces a hash consistent with the composite-key equality above.
     *
     * <p>Assumptions: the value is stable for the life of an instance because the identity has no
     * mutator, so an instance placed in a hash-based collection cannot become unfindable there even
     * though its balance is mutable.</p>
     *
     * @return the hash of the composite identity, or the hash of an absent value on an instance the
     *     persistence provider has not yet populated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Renders the two reference codes for a log line or an assertion message, carrying neither
     * the account identifier nor the balance.
     *
     * <p>Trade-offs: <b>this is a diagnostic aid and it is expressly NOT the parity emitter.</b> The
     * distinction has to be stated here rather than assumed, because this record has a byte-exact
     * output surface that looks like something a rendering method might reasonably serve.
     * {@code app/cbl/CBACT04C.cbl} line 193 performs {@code DISPLAY TRAN-CAT-BAL-RECORD}, emitting
     * the whole 50-byte record to standard output once per row scanned, and that stream is compared
     * byte for byte -- {@code tests/golden/posting/happy_path/tcatbal.expected} is the committed
     * expectation for this table. The 50-byte form is produced through
     * {@code com.carddemo.common.codec.FixedWidthCodec}, which is where the declared widths, the
     * zoned-decimal sign overpunch and the 22 pad bytes live. Routing parity output through this
     * method instead would couple a byte-exact contract to a diagnostic convenience, and the first
     * reformatting of a log line would break the comparison with nothing to indicate why. Nothing
     * may parse what this method returns.</p>
     *
     * <p>Assumptions: the account identifier is rendered to its last four digits and the balance is
     * omitted entirely. Both are covered by the sensitive-data logging prohibition in
     * {@code docs/architecture/observability.md}, which names account and customer identifiers
     * alongside primary account numbers, and a rendering reachable from a log statement is exactly
     * the path that prohibition exists to close. Alternatives Considered: rendering the whole
     * identifier because it is an internal key rather than a card number. Rejected -- the
     * prohibition is written in terms of the identifier and not of its issuer, and a full
     * eleven-digit key is enough to address a row through this migration's own endpoints. A
     * four-digit suffix is retained because correlating two log lines to the same account is the
     * reason this rendering exists at all.</p>
     *
     * <p>Refactoring Rationale: THE ACCOUNT IDENTIFIER AND THE BALANCE ARE OMITTED, and an earlier
     * revision rendered both. It argued that "no member of this record is a primary account number,
     * a card verification value or a national identifier, so nothing here requires masking", and
     * added that "a running balance is not itself protected". Both statements are refuted by the
     * sensitive-data logging contract in {@code docs/architecture/observability.md}, which names
     * account identifiers explicitly and covers persistence-bound values as a class -- and a
     * category balance is the persisted content of this very table. The earlier reasoning worked
     * from a three-example list rather than from the contract, which is how it reached the opposite
     * conclusion.</p>
     *
     * <p>Trade-offs: the omission is total rather than partial, because abbreviating a protected
     * value IS masking and masking has one owner per context -- {@code com.carddemo.batch.mapper}
     * for this module. A second, slightly different rule inside an entity would give one value two
     * renderings and make neither authoritative. What survives is the two reference codes, which say
     * WHICH CATEGORY an entry concerns without saying whose it is or what it is worth. The cost is
     * real: a reader diagnosing a lookup that matched nothing can no longer tell two accounts' rows
     * apart from a log line alone. It is paid down by the correlation identifier
     * {@code com.carddemo.common.web.CorrelationIdFilter} publishes on a request-scoped line and by
     * the {@code batch.batch_run} step ledger for batch work, and the accessors above return both
     * omitted members to any caller that needs one.</p>
     *
     * <p>Assumptions: the exposure closed here is the RETAINED LOG rather than any request path. The
     * interest job renders one such line per category row scanned, so the aggregate of one run is a
     * log holding every account identifier and every category balance in the table.</p>
     *
     * <p>Assumptions: the masking discipline this migration states names card numbers, verification
     * values and national identifiers, and neither member withheld here is one of those. That list is a
     * FLOOR on what must never be rendered rather than a licence covering everything absent from it: an
     * account's outstanding balance per transaction category is customer financial detail, and a log is
     * a durable, widely-readable, differently-governed store, so writing it there discloses it outside
     * the authorization boundary a caller would otherwise have to pass to see it. This is recorded so
     * that a later change does not add a sensitive member to this rendering on the assumption that the
     * method was already cleared for one.</p>
     *
     * @return a single-line rendering naming the type and the two reference codes, and carrying
     *     neither the account identifier nor the balance
     */
    @Override
    public String toString() {
        return "TransactionCategoryBalance[typeCd='" + getTypeCd()
                + "', categoryCd='" + getCategoryCd() + "']";
    }

    /**
     * The three-part composite key of a category-balance row, in the copybook's physical order.
     *
     * <p>This is the migrated form of the group item {@code TRAN-CAT-KEY} at
     * {@code app/cpy/CVTRA01Y.cpy} line 5, which spans the first 17 bytes of the 50-byte record:
     * {@code TRANCAT-ACCT-ID PIC 9(11)} at line 6, {@code TRANCAT-TYPE-CD PIC X(02)} at line 7 and
     * {@code TRANCAT-CD PIC 9(04)} at line 8. The baseline reaches a row by this whole group rather
     * than by three independent lookups: {@code app/cbl/CBACT04C.cbl} line 31 declares
     * {@code RECORD KEY IS FD-TRAN-CAT-KEY}, and {@code app/jcl/TCATBALF.jcl} line 40 defines the
     * cluster with {@code KEYS(17 0)}.</p>
     *
     * <p>Assumptions: the declaration order of the three members below, and the parameter order of
     * the constructor, are both the physical order of the key -- account, then type, then category.
     * It is confirmed four times over: by {@code app/cpy/CVTRA01Y.cpy} lines 6 to 8 under the group
     * item at line 5; by the file description at {@code app/cbl/CBACT04C.cbl} lines 64 to 66; by the
     * sort statement at {@code app/jcl/PRTCATBL.jcl} line 52,
     * {@code SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)}; and by the primary key
     * the owning migration declares over {@code (account_id, type_cd, category_cd)}. Posting assigns
     * the three components in that same order at {@code app/cbl/CBTRN02C.cbl} lines 469 to 471, so
     * on this key -- unlike the disclosure-group key mapped by {@link DisclosureGroup} in this
     * package, where {@code app/cbl/CBACT04C.cbl} assigns the category at line 211 before the type
     * at line 212 -- source order and physical order agree, and transcribing the assignments in
     * sequence happens to build the key correctly.</p>
     *
     * <p>Assumptions: the arity is fixed at three by the copybook, so no fourth component and no
     * surrogate identifier is admissible. A key of any other width would not balance against the
     * {@code KEYS(17 0)} operand above.</p>
     */
    @Embeddable
    // Assumptions: the persistence specification requires the type of an embedded identifier
    //     to be serializable, because the provider holds a detached copy of the key in its own
    //     structures -- the entry key of the persistence context, and of a second-level cache
    //     region -- independently of the entity instance. The interface is therefore implemented
    //     to satisfy that contract rather than for any use this module makes of serialization, and
    //     the version below is pinned to a literal rather than left to the default computation so
    //     that recompiling this class cannot change it and invalidate a serialized form some cache
    //     region is still holding.
    // Alternatives Considered: the explicit name TransactionCategoryBalanceId rather than a
    //     bare nested Id. The short form reads more cleanly at the declaration and was rejected
    //     for two reasons that both bite away from it: the sibling DisclosureGroupId in this same
    //     package already sets the longer convention, so a second convention would make the
    //     package inconsistent for no gain; and this name appears unqualified in a repository
    //     query signature and in test assertion messages, where a bare Id would not say which
    //     row it identifies.
    public static class TransactionCategoryBalanceId implements Serializable {

        /**
         * The serialization version, pinned to a literal because an embedded identifier is required
         * to be serializable and a computed value would change with any recompilation.
         */
        private static final long serialVersionUID = 1L;

        // Assumptions: this is the LEADING component of the key, and the account identifier it
        //     carries reaches this key from the card cross-reference rather than from the
        //     transaction being posted. app/cbl/CBTRN02C.cbl line 469 moves XREF-ACCT-ID -- the
        //     value returned by the cross-reference read at lines 380 to 392 -- into this
        //     component, while lines 470 and 471 take the other two from the transaction. The
        //     daily-transaction record has no account identifier to offer: app/cpy/CVTRA06Y.cpy
        //     declares a card number and no account, so this key is necessarily assembled from two
        //     sources. Deriving it from the transaction record is not a mistake that produces a
        //     wrong account, it is one that has nothing to read.
        // Alternatives Considered: BIGINT and a long-valued member rather than a narrower
        //     integer type. TRANCAT-ACCT-ID PIC 9(11) at app/cpy/CVTRA01Y.cpy line 6 is eleven
        //     unsigned digits, whose maximum of 99999999999 exceeds what a 32-bit integer can
        //     hold, so a narrower type could not represent the declared domain at all. The
        //     migration plan's section 0.4.1.3 maps a numeric identifier of this shape to BIGINT,
        //     and using one width for every account identifier in the schema keeps a join or a
        //     lookup across them free of an implicit cast. Account.accountId and CardXref.accountId
        //     in this package carry the same type for the same reason and have to stay aligned:
        //     app/cbl/CBACT04C.cbl lines 202 and 204 feed this very value into the account and
        //     cross-reference lookups, so a divergence would surface as a cast on the hot path.
        // Assumptions: the baseline is LOOSER about this value's type than this mapping is,
        //     and the looseness is not imitated. app/cbl/CBACT04C.cbl line 167 declares the
        //     control-break holder as WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES -- a CHARACTER
        //     field -- and line 194 compares it against the numeric TRANCAT-ACCT-ID. This
        //     component is mapped numerically because the copybook picture is numeric and rule T1
        //     makes the copybook normative, so the comparison line 194 performs becomes a typed
        //     inequality between two long values in the service layer. The consequence is that the SPACES sentinel
        //     has no target equivalent and must not be emulated by a blank string coerced to a
        //     number: the "no previous account" state becomes an absent value or an explicit
        //     first-iteration flag, which is what the baseline itself falls back on at line 195
        //     where WS-FIRST-TIME guards the first flush.
        @Column(name = "account_id", nullable = false, updatable = false)
        private Long accountId;

        // Assumptions: the SECOND component, TRANCAT-TYPE-CD PIC X(02) at
        //     app/cpy/CVTRA01Y.cpy line 7, occupying zero-based offsets 11 and 12. It is carried
        //     as text because the picture is alphanumeric, so the two characters are a code rather
        //     than a quantity, and it is fixed-width rather than variable because it is a key
        //     component -- the declared width is part of the contract, which is what the
        //     KEYS(17 0) operand depends on. app/jcl/PRTCATBL.jcl line 48 declares the same field
        //     to its sort utility as CH, character, independently of the copybook.
        // Assumptions: the type has to match, field for field, DailyTransaction.typeCd and
        //     Transaction.typeCd in this package and the type component of DisclosureGroup's key,
        //     because this value is copied straight along that chain with no transformation.
        //     app/cbl/CBTRN02C.cbl line 470 copies it out of the transaction into this key, and
        //     app/cbl/CBACT04C.cbl line 212 copies it out of this key into the disclosure-group
        //     key. A type that disagreed at any link would not fail to compile; it would produce a
        //     lookup that quietly matches nothing, which the baseline then reports as a missing
        //     disclosure group rather than as a type error.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "type_cd", length = 2, nullable = false, updatable = false)
        private String typeCd;

        // WHY : Assumptions: the TRAILING component, TRANCAT-CD PIC 9(04) at
        //       app/cpy/CVTRA01Y.cpy line 8, occupying zero-based offsets 13 to 16.
        // WHY : Alternatives Considered: this member was derived one way from the copybook and
        //       settled the other way by the owning migration, and the divergence is recorded rather
        //       than smoothed over because the picture invites the wrong answer. Reading the picture
        //       alone gives a bounded four-digit numeric code, which would narrow to a small integer.
        //       The owning service's V1__ledger.sql instead declares category_cd CHAR(4) NOT NULL at
        //       line 822, and its note gives the reason that decides it: an integer column drops the
        //       leading zeros the baseline writes, so 0001 would come back as 1. Being part of the
        //       key makes that argument stronger here rather than weaker, because 0001 and 1 must not
        //       resolve to two different keys. Two further reasons make the text binding the only
        //       workable choice from inside this module: the provider asserts its mappings against
        //       the physical schema at start-up, so an integer member over a fixed-character column
        //       would fail that assertion before a row was read, and this module holds no grant that
        //       could alter the column to suit a mapping.
        // WHY : Assumptions: the numeric picture still constrains the VALUES, and preserving the
        //       leading zeros is what keeps the mapping faithful to it. app/cbl/CBACT04C.cbl line 483
        //       sets the category of the interest transaction it generates by moving the
        //       two-character literal '05' into a field of this picture, and a numeric picture
        //       right-justifies and zero-fills, so the value stored is 0005 and never '05' followed
        //       by two spaces. The four characters must therefore be supplied at their declared width
        //       with the leading zeros intact, and the fixed-character column offers no safety net
        //       for a caller who omits them: its trailing-blank insensitivity forgives a value short
        //       on the RIGHT, whereas a missing leading zero is short on the LEFT. A value of '5' is
        //       stored as '5' plus three blanks and compares as '5', which is simply a different key
        //       from '0005', so it misses rather than being normalised.
        // WHY : Assumptions: this component and the one above it are asymmetric -- the type code is
        //       character in the source and the category code is numeric -- even though they are
        //       adjacent, always used together and always travel as one key. It is the pair most
        //       likely to be made uniform by mistake, in either direction. The asymmetry is visible
        //       twice: app/cbl/CBACT04C.cbl lines 482 and 483 move '01' into the type code and '05'
        //       into the category code, and only the second becomes a number; and
        //       app/jcl/PRTCATBL.jcl declares the type CH at line 48 and the category ZD at line 49.
        //       Both nonetheless map to a fixed-character column here, for the leading-zero reason
        //       above, so the asymmetry survives as a fact about the source rather than as a
        //       difference between these two declarations.
        // WHY : Assumptions: the digits-only half of the picture is deliberately not re-asserted as a
        //       constraint on this member. Nothing reads this field arithmetically, the owning
        //       service does not assert it either, and the baseline does not enforce it at rest,
        //       since the utility that loads the cluster copies bytes without consulting a picture
        //       clause. The two properties the composite key actually depends on -- fixed width and
        //       preserved leading zeros -- are exactly what the fixed-character binding guarantees.
        @JdbcTypeCode(SqlTypes.CHAR)
        @Column(name = "category_cd", length = 4, nullable = false, updatable = false)
        private String categoryCd;

        /**
         * The declared width of the type-code component, from {@code TRAN-TYPE-CD PIC X(02)}.
         *
         * <p>Assumptions: the number is declared once and read by the guard below, rather than written
         * into the guard call and again into its message. A width that appeared twice could be changed
         * in one place and leave the refusal message describing the other.</p>
         */
        public static final int TYPE_CD_WIDTH = 2;

        /**
         * The declared width of the category-code component, from {@code TRANCAT-CD PIC 9(04)}.
         */
        public static final int CATEGORY_CD_WIDTH = 4;

        /**
         * Creates an empty identity for the persistence provider to populate.
         *
         * <p>Assumptions: the specification requires a no-argument constructor on an embeddable
         * type, which the provider calls before assigning the three mapped components reflectively.
         * It is {@code protected} rather than {@code public} so that application code, which has a
         * fully specified constructor available, cannot reach a key with no components set -- a
         * value on which equality and hashing are both meaningless.</p>
         */
        protected TransactionCategoryBalanceId() {
            // Assumptions: the body is deliberately empty because the provider assigns all
            //     three components after construction, so anything initialised here would be
            //     overwritten on a hydrate. Defaulting a component to spaces or zeros would be
            //     worse than leaving it absent: it would produce a syntactically valid key that
            //     silently matches no row, which is the failure mode this key is most prone to.
        }

        /**
         * Creates a complete key from its three components, in the key's physical order.
         *
         * <p>Assumptions: the parameter order is the physical order of the key -- account, then
         * type, then category -- which on this key is also the order posting assigns the components
         * in, at {@code app/cbl/CBTRN02C.cbl} lines 469 to 471. The two code arguments are adjacent
         * and both are short strings, so transposing them compiles and is accepted by this signature;
         * it fails only as a lookup that matches nothing.</p>
         *
         * @param accountId the {@link Long} account this balance belongs to, from
         *     {@code TRANCAT-ACCT-ID PIC 9(11)}; supplied by the card cross-reference read rather
         *     than by the transaction being posted, because the transaction record carries no
         *     account identifier
         * @param typeCd the {@link String} two-character transaction type code, from
         *     {@code TRANCAT-TYPE-CD PIC X(02)}
         * @param categoryCd the {@link String} four-character transaction category code, from
         *     {@code TRANCAT-CD PIC 9(04)}, with its leading zeros intact so that 0001 and 1 cannot
         *     become two distinct keys
         */
        public TransactionCategoryBalanceId(Long accountId, String typeCd, String categoryCd) {
            // WHY : Refactoring Rationale: the three components are checked here, and an earlier
            //       revision of this constructor assigned all three unchecked. The note on the
            //       category member above explains precisely why that could not stand: a value short
            //       on the LEFT is not forgiven by the fixed-character column, so '5' is stored as
            //       '5' plus three blanks and compares as a DIFFERENT KEY from '0005'. Two callers
            //       spelling one logical code differently therefore produced two rows for one
            //       account, type and category -- and the running balance of that category became
            //       whichever of the two a later read happened to find. Nothing downstream could
            //       detect it: both rows satisfy every declared constraint.
            // WHY : Alternatives Considered: normalising a short all-digit value by left-padding it,
            //       which the review's guidance offered as an alternative to validating. It is
            //       rejected because it accepts an ambiguity rather than removing one. A caller that
            //       supplied '5' either meant 0005 and was careless, or was carrying a value from
            //       somewhere that had already lost its leading zeros -- and padding cannot tell the
            //       two apart, so it would silently make the second case look correct while the
            //       source of the loss stayed in place. Refusing reports the omission at the point
            //       the key is built, which is the only place it can still be traced.
            this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
            this.typeCd = requireExactWidth(typeCd, TYPE_CD_WIDTH, "typeCd");
            this.categoryCd = requireExactDigits(categoryCd, CATEGORY_CD_WIDTH, "categoryCd");
        }

        /**
         * Returns a component that is present and exactly as wide as its column declares.
         *
         * <p>Assumptions: the width is checked for exact equality rather than as a maximum, because
         * this component belongs to a composite key over fixed-character columns. A value narrower
         * than the column is stored padded and compares as the padded form, so accepting it would
         * admit a second spelling of one key rather than a shorter one.</p>
         *
         * @param candidate the supplied component, possibly {@code null}
         * @param width the declared width the column carries
         * @param member the component's name, used only in the refusal message
         * @return the same value once it has been accepted, never {@code null}
         * @throws NullPointerException if {@code candidate} is {@code null}
         * @throws IllegalArgumentException if the value is not exactly {@code width} characters
         */
        private static String requireExactWidth(String candidate, int width, String member) {
            Objects.requireNonNull(candidate, member + " must not be null");
            if (candidate.length() != width) {
                throw new IllegalArgumentException(member + " must be exactly " + width
                        + " characters, because it is part of a composite key over a fixed-character"
                        + " column and a narrower value is stored padded and compares as the padded"
                        + " form; received " + candidate.length());
            }
            return candidate;
        }

        /**
         * Returns a component that is present, exactly as wide as its column declares, and all digits.
         *
         * <p>Assumptions: the digits test applies to this one component because its source picture is
         * numeric -- {@code TRANCAT-CD PIC 9(04)} at line 8 of {@code app/cpy/CVTRA01Y.cpy} -- and a
         * numeric picture right-justifies and zero-fills, so every value the reference can write is
         * four digits. The member note above declines to re-assert the digits half as a persistence
         * CONSTRAINT, and that stands: this is a check at the point a KEY is constructed, which is
         * where a non-digit spelling would create a second row rather than fail a constraint.</p>
         *
         * @param candidate the supplied component, possibly {@code null}
         * @param width the declared width the column carries
         * @param member the component's name, used only in the refusal message
         * @return the same value once it has been accepted, never {@code null}
         * @throws NullPointerException if {@code candidate} is {@code null}
         * @throws IllegalArgumentException if the value is not exactly {@code width} digits
         */
        private static String requireExactDigits(String candidate, int width, String member) {
            requireExactWidth(candidate, width, member);
            for (int index = 0; index < candidate.length(); index++) {
                char character = candidate.charAt(index);
                if (character < '0' || character > '9') {
                    throw new IllegalArgumentException(member + " must be exactly " + width
                            + " digits with its leading zeros intact, because its source picture is"
                            + " numeric and zero-filled, so 0001 and 1 must not become two keys;"
                            + " received a non-digit at position " + (index + 1));
                }
            }
            return candidate;
        }

        /**
         * The account this running balance belongs to.
         *
         * @return the eleven-digit account identifier
         */
        public Long getAccountId() {
            return accountId;
        }

        /**
         * The transaction type this running balance accumulates.
         *
         * @return the two-character transaction type code
         */
        public String getTypeCd() {
            return typeCd;
        }

        /**
         * The transaction category this running balance accumulates, within its transaction type.
         *
         * @return the four-character category code, with leading zeros preserved
         */
        public String getCategoryCd() {
            return categoryCd;
        }

        /**
         * Compares this key with another, treating all three components as significant.
         *
         * <p>Assumptions: all three components participate, because the persistence specification
         * requires a composite-key type to implement equality and hashing consistently and the
         * provider uses both for its identity-map lookups. Omitting any one component would make two
         * genuinely different balance rows compare equal, and inside a persistence context that
         * silently merges them into one managed instance -- so a posting run would add two
         * categories' amounts onto whichever row it happened to load first.</p>
         *
         * @param other the object to compare with, which may be {@code null} or of any type
         * @return {@code true} when the argument is a key whose three components are all equal
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TransactionCategoryBalanceId that)) {
                return false;
            }
            // Assumptions: the components are compared exactly as held, with no trimming and
            //     no padding applied here. Normalising either way was the alternative and is
            //     declined because it would make in-memory equality disagree with the database for
            //     one of the two forms, and a key that behaves one way in a collection and another
            //     in a query is worse than one that is simply literal. It is also unnecessary: a
            //     fixed-character column strips the trailing blanks of its declared width on the
            //     way out, so a value that arrived padded and one that arrived bare are the same
            //     string by the time either reaches this method. Equality is therefore literal on
            //     values the database has already normalised.
            return Objects.equals(accountId, that.accountId)
                    && Objects.equals(typeCd, that.typeCd)
                    && Objects.equals(categoryCd, that.categoryCd);
        }

        /**
         * Produces a hash over all three components, consistent with the equality above.
         *
         * <p>Assumptions: every component is included and none has a mutator, so the value is stable
         * for the life of an instance and a key may be used safely in a hash-based collection --
         * which is exactly how the provider itself uses it, as the entry key of its persistence
         * context.</p>
         *
         * @return the combined hash of the account identifier, the type code and the category code
         */
        @Override
        public int hashCode() {
            return Objects.hash(accountId, typeCd, categoryCd);
        }

        /**
         * Renders the two reference components of this key for a log line or an assertion message.
         *
         * <p>Refactoring Rationale: THE ACCOUNT IDENTIFIER IS OMITTED, and an earlier revision
         * rendered it. It reasoned that a key is a row's identity and carries nothing personal, so
         * withholding a component would remove the only way to tell two rows apart while protecting
         * nothing. The first half is right and the second is not: the sensitive-data logging contract
         * in {@code docs/architecture/observability.md} names account identifiers explicitly, so the
         * component was protected all along. This method is reached from the enclosing type's own
         * rendering as well as directly, so leaving it would have re-disclosed through the key
         * exactly what the enclosing type withholds.</p>
         *
         * <p>Trade-offs: this is a diagnostic aid only and nothing may parse it. The two surviving
         * components are rendered separately rather than concatenated into the 17-byte form the
         * baseline keys on, which costs a reader the ability to see the key as the reference system
         * does but makes the boundary between them unambiguous. That is the more useful property
         * here, because the failure this rendering exists to expose is a category code that lost a
         * leading zero, and a concatenated run shows it only to someone counting characters. What is
         * given up by the omission is that two accounts' rows for one category are no longer
         * distinguishable from a log line alone; the accessor for the identifier returns it to any
         * caller that needs it, and a request-scoped line already carries the correlation identifier
         * {@code com.carddemo.common.web.CorrelationIdFilter} publishes.</p>
         *
         * @return a single-line rendering naming the type, the type code and the category code, and
         *     carrying no account identifier
         */
        @Override
        public String toString() {
            return "TransactionCategoryBalanceId[typeCd='" + typeCd
                    + "', categoryCd='" + categoryCd + "']";
        }
    }
}
