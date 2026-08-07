package com.carddemo.batch.mapper;

import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Converts between the 50-byte transaction-category-balance record and the running-balance row
 * posting accumulates onto.
 *
 * <p>This is the representation boundary for {@code 01 TRAN-CAT-BAL-RECORD}, declared at
 * {@code app/cpy/CVTRA01Y.cpy} lines 4 to 10, whose own line 2 records the record length as
 * {@code RECLN = 50}. Two baseline programs reach this record and they reach it differently:
 * {@code app/cbl/CBTRN02C.cbl} addresses one row by key and accumulates a transaction amount onto
 * it, while {@code app/cbl/CBACT04C.cbl} walks the whole file sequentially and multiplies each
 * balance by a disclosure-group rate. Under the migration plan's transformation rule T1 the
 * copybook is the normative source for every width and position below.</p>
 *
 * <h2>The layout, with the arithmetic that checks it</h2>
 *
 * <p>Offsets are zero-based. They are recorded because they are the audit trail: every value this
 * class reads or writes traces back to a byte range of the record.</p>
 *
 * <pre>
 * offset  bytes  copybook field (line)     PICTURE      kind    target
 *      0     11  TRANCAT-ACCT-ID   (L6)    9(11)        UINT    account_id   BIGINT
 *     11      2  TRANCAT-TYPE-CD   (L7)    X(02)        TEXT    type_cd      CHAR(2)
 *     13      4  TRANCAT-CD        (L8)    9(04)        UINT    category_cd  CHAR(4)
 *     17     11  TRAN-CAT-BAL      (L9)    S9(09)V99    ZONED   balance      NUMERIC(11,2)
 *     28     22  FILLER            (L10)   X(22)        TEXT    dropped
 * </pre>
 *
 * <p>Eleven plus two plus four plus eleven plus twenty-two is fifty, which is the length the
 * copybook declares, and the first three fields together span seventeen bytes, which is the width
 * of the group item {@code TRAN-CAT-KEY} at {@code app/cpy/CVTRA01Y.cpy} line 5. Both totals are
 * corroborated from a place that knows nothing of the copybook: {@code app/jcl/TCATBALF.jcl} line 40
 * defines the cluster with {@code KEYS(17 0)}, a seventeen-byte key at offset zero, which balances
 * only if the three components are 11, 2 and 4 bytes wide, and its line 41 declares
 * {@code RECORDSIZE(50 50)}.</p>
 *
 * <h2>The balance offset is confirmed three times over</h2>
 *
 * <p>Assumptions: this record carries the clearest offset corroboration in the package, and all
 * three sources are independent of one another. Summing the declared field widths above places
 * {@code TRAN-CAT-BAL} at offset 17. The cluster definition at {@code app/jcl/TCATBALF.jcl} line 40
 * declares a key of 17 bytes at offset 0, which puts the first byte after the key at exactly that
 * offset. And {@code app/jcl/PRTCATBL.jcl} line 50 declares the sort symbol
 * {@code TRAN-CAT-BAL,18,11,ZD} in ONE-based positions, so the conversion
 * {@code zeroBased = oneBased - 1} gives 18 minus 1, which is 17 again -- while its declared type
 * {@code ZD} independently establishes that the field is zoned decimal rather than packed, which is
 * the distinction that decides which codec may touch it. Its three companion symbols at lines 47 to
 * 49 agree with the other three offsets the same way, converting one-based 1, 12 and 14 into
 * zero-based 0, 11 and 13. A copybook summation, an access-method key definition and a sort utility's
 * symbol table have no way of agreeing by accident, so no offset in this record is in doubt.</p>
 *
 * <h2>The seventeen-byte key is geometry, and deliberately not a type</h2>
 *
 * <p>Alternatives Considered: modelling {@code TRAN-CAT-KEY}, the group item at
 * {@code app/cpy/CVTRA01Y.cpy} line 5, as a Java group type with the three subordinates at
 * {@code app/cpy/CVTRA01Y.cpy} lines 5 to 8 hanging beneath it. A group wrapper was available and is
 * rejected, because a copybook group name does not identify a shape across the library. <b>The name
 * {@code TRAN-CAT-KEY} denotes a seventeen-byte key in this copybook and a SIX-byte key in
 * {@code app/cpy/CVTRA04Y.cpy}</b>, where line 5 brackets only {@code TRAN-TYPE-CD PIC X(02)} at
 * line 6 and {@code TRAN-CAT-CD PIC 9(04)} at line 7. A key type keyed on the COBOL name would have
 * to pick one of those two widths and would then be wrong for every reader of the other, so layouts
 * resolve per copybook and a key is expressed as GEOMETRY -- the registered specification's own
 * {@link #keyLength()} and {@link #keyOffset()}, 17 and 0 -- and never as a shared type. The
 * flattening loses nothing, because the group item at line 5 contributes no bytes of its own: its
 * width is the sum of its children, so the three leaf fields plus that geometry carry everything it
 * declared.</p>
 *
 * <h2>Where the account identifier comes from, and where it does not</h2>
 *
 * <p>Assumptions: <b>the account identifier is supplied by the caller and is never read off the
 * transaction being posted, because the daily-transaction record has no such field to read.</b>
 * {@code app/cbl/CBTRN02C.cbl} line 469 builds the key with
 * {@code MOVE XREF-ACCT-ID TO FD-TRANCAT-ACCT-ID}, and {@code XREF-ACCT-ID} is the value the card
 * cross-reference read produced -- not a field of the daily transaction, whose type and category
 * codes are what lines 470 and 471 move into the remaining two components. This class therefore
 * takes the account identifier from the identity it is handed, and a mapper that instead derived one
 * from a transaction would be wrong in the way that is hardest to notice: every balance would still
 * be a plausible number, and only a transaction whose card belongs to a different account than
 * expected would reveal it. The provenance is also recorded on the entity's own account accessor, so
 * a reader who meets the value on either side of this boundary meets the same statement.</p>
 *
 * <h2>A missing row is an ordinary outcome, not a failure</h2>
 *
 * <p>Assumptions: <b>the absence of a category-balance row is expected and is what selects the
 * create arm of posting.</b> {@code app/cbl/CBTRN02C.cbl} line 474 reads the file with an
 * {@code INVALID KEY} clause that sets a create flag at line 478, and line 481 then accepts file
 * status {@code '00'} OR {@code '23'} -- the second being the not-found status -- treating both as
 * success. Only a third status reaches the abend path at lines 489 to 492. This is stated at the
 * representation boundary because a later reader holding an empty lookup result has to know that it
 * means a row to create rather than a defect to report; the committed
 * {@code tests/fixtures/posting/zero_balance/tcatbal.txt} is empty for exactly this reason, and its
 * scenario is a passing one.</p>
 *
 * <h2>The balance is eleven bytes, and a reader will expect twelve</h2>
 *
 * <p>Assumptions: <b>{@code TRAN-CAT-BAL} occupies ELEVEN bytes, not twelve.</b>
 * {@code PIC S9(09)V99} at {@code app/cpy/CVTRA01Y.cpy} line 9 declares nine digit positions before
 * the implied decimal point and two after it, which is eleven digits in total, and the leading
 * {@code S} adds no byte of its own: the sign is an overpunch folded into the low-order digit rather
 * than a character occupying a position. The contrast that makes this worth stating is inside this
 * same package -- the account master's money is {@code PIC S9(10)V99}, twelve bytes, for example
 * {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy} line 7 -- so a reader arriving from
 * {@code AccountRecordMapper} will expect twelve here and would place the {@code FILLER} at offset 29
 * across 21 bytes. That mistake balances arithmetically, since 11 plus 2 plus 4 plus 12 plus 21 is
 * also 50, and a whole-record contiguity proof would pass while every byte from offset 28 onwards was
 * misread. The width below is therefore DERIVED from the picture clause through the shared codec's
 * own rule rather than written as a literal, which makes it an assertion about the rule instead of a
 * restatement of a number.</p>
 *
 * <p>Assumptions: the balance crosses this boundary as an exact decimal at scale two and is never
 * rounded here, because of what consumes it. {@code app/cbl/CBACT04C.cbl} lines 464 and 465 compute
 * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, forming the product of this balance and the rate
 * <b>before</b> the division -- so this value is the MULTIPLICAND of the one formula in the migration
 * that has to agree to the cent. A cent quietly rounded away at this boundary would be multiplied by
 * a rate before anything scaled it back down, so the error would be amplified rather than absorbed.
 * The multiply-before-divide ordering is owned by {@code com.carddemo.common.money.Money} and the
 * interest service under transformation rule T4; this class performs no arithmetic on the balance at
 * all, and the ordering is cited only to explain why it must hand the value across intact. No
 * IEEE-754 binary64 primitive or wrapper appears anywhere in this class, which the
 * {@code LayeringRulesTest} architecture gate asserts rather than requests.</p>
 *
 * <h2>Create versus update: recorded here, decided elsewhere</h2>
 *
 * <p>Assumptions: the posting flow has two arms over this record and <b>this class serves both of
 * them identically</b>. {@code app/cbl/CBTRN02C.cbl} lines 495 to 499 select between them on the
 * flag its line 478 sets, performing {@code 2700-A-CREATE-TCATBAL-REC} at lines 503 to 524 or
 * {@code 2700-B-UPDATE-TCATBAL-REC} at lines 526 to 542. The create arm issues
 * {@code INITIALIZE TRAN-CAT-BAL-RECORD} at line 504, populates the three key components at lines 505
 * to 507, adds the transaction amount at line 508 and writes at line 510; the update arm performs the
 * SAME add at line 527 and rewrites at line 528.</p>
 *
 * <p>Assumptions: <b>both arms are additive, and the natural assumption to the contrary is wrong.</b>
 * A reader expects a create to SET the balance and an update to ADD to it. Neither arm sets anything:
 * they differ only by the {@code INITIALIZE}, the key population and write versus rewrite, so a
 * created row starts from zero and RECEIVES the amount rather than being seeded with it. The
 * committed expectation files show this in bytes. In
 * {@code tests/golden/posting/zero_balance/tcatbal.expected} the create arm ran -- its paired fixture
 * is empty -- and the balance span holds the transaction amount alone; in
 * {@code tests/golden/posting/happy_path/tcatbal.expected} the update arm ran over a seeded row and
 * the balance span holds the sum of the seeded balance and the same amount.</p>
 *
 * <p>Assumptions: <b>the byte image of a created row and the byte image of an updated row are
 * produced by the same code path here</b>, so any divergence between the two arms originates in the
 * service and never in this class. That is precisely why nothing below distinguishes them and why
 * nothing below should be made to: the migration plan's section 0.5.1.7 requires the two paths to
 * remain distinguishable and separately testable, and they are -- in
 * {@code com.carddemo.batch.service.CategoryBalanceService}, which decides which arm runs, and in the
 * entity, whose zero-seeded constructor expresses the {@code INITIALIZE} and whose two-argument
 * constructor expresses a loaded row. <b>This class opens no transaction, reaches no repository and
 * carries no branch on which arm is in flight.</b></p>
 *
 * <h2>Why the field order is load-bearing</h2>
 *
 * <p>Assumptions: <b>the physical order -- account identifier, then type code, then category code --
 * is the VSAM key order, and it must never be re-sorted.</b> The order is proved by the
 * seventeen-byte key at offset zero in {@code app/jcl/TCATBALF.jcl} line 40 read against the three
 * widths at {@code app/cpy/CVTRA01Y.cpy} lines 6 to 8, and {@code app/jcl/PRTCATBL.jcl} line 52
 * sorts on {@code TRANCAT-ACCT-ID}, {@code TRANCAT-TYPE-CD} and {@code TRANCAT-CD} ascending in that
 * same sequence. What the order buys is the correctness of the interest job's control break: because
 * the account identifier is the LEADING component, a sequential walk in key order groups every row
 * for one account contiguously, which is the whole premise of
 * {@code app/cbl/CBACT04C.cbl} line 194 -- {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM} -- and of
 * the account update its line 196 performs on each break. {@code TransactionCategoryBalanceRepository}
 * orders its walk by account identifier, then type code, then category code for this reason.</p>
 *
 * <p>Assumptions: the consequence for this class is specific. A record encoded with its fields in a
 * different order would still be exactly 50 bytes, would still satisfy every length check, and would
 * still decode into plausible values -- while destroying the walk order, so the control break would
 * fire on accounts it should not and an account's accrual would be split across several partial
 * totals. The field order below is therefore the copybook's declaration order and is taken from the
 * registered specification rather than chosen, and the encoded map is populated in that same order.</p>
 *
 * <h2>Both directions, and what the encode path is for</h2>
 *
 * <p>Assumptions: an encode path exists because a backup generation needs one.
 * {@code app/jcl/DEFGDGB.jcl} lines 43 to 45 define {@code AWS.M2.CARDDEMO.TCATBALF.BKUP} as a
 * generation data group at {@code LIMIT(5)} with {@code SCRATCH}, one of the ten generation families
 * the migration reproduces as versioned object-storage prefixes retaining five noncurrent versions,
 * and {@code app/jcl/PRTCATBL.jcl} lines 35 to 39 write that generation from the cluster with
 * {@code LRECL=50}. A backup generation is only useful if it is byte-identical to the record it
 * copies, so neither encode overload is dead code and neither must be removed as such -- and it is
 * {@link #toRecord(TransactionCategoryBalance, byte[])} rather than
 * {@link #toRecord(TransactionCategoryBalance)} that a generation must be written through, for the
 * reason the next section gives.</p>
 *
 * <h2>The parity obligation, stated where the bytes are produced</h2>
 *
 * <p>Assumptions: the encoded image is compared BYTE FOR BYTE against the committed expectation
 * files {@code tests/golden/posting/*}{@code /tcatbal.expected} after timestamp normalisation, and
 * this record carries no timestamp for that normalisation to touch. The significance is that there is
 * no near miss available: one blank too many in the twenty-two byte {@code FILLER} span, or one
 * digit of scale in the balance, is a failed comparison rather than an approximate match. The nine
 * committed posting scenarios and the two interest scenarios are the parity oracle for this record.
 * <b>Everything under {@code tests/**} is reference-only under the migration plan's section 0.2.2:
 * those files are read as the specification, they are never modified, and the suite's pinned
 * dependencies are never moved.</b></p>
 *
 * <p>Assumptions: {@code toRecord(toEntity(image), image)} reproduces {@code image} byte for byte, and
 * the SECOND ARGUMENT is what makes that true. Two regions of this record survive a decode only as
 * bytes. The first is the {@code FILLER} asymmetry the package charter rules on: the span at
 * {@code app/cpy/CVTRA01Y.cpy} line 10 is dropped on decode and has no entity member to rebuild it
 * from, so the single-argument overload writes blanks there. That matters rather than being
 * hypothetical, and this record demonstrates it twice over with two DIFFERENT non-blank paddings --
 * {@code tests/golden/posting/happy_path/tcatbal.expected} carries twenty-two ASCII zeros in that span,
 * inherited from the seed row the update arm rewrote, while
 * {@code tests/golden/posting/zero_balance/tcatbal.expected} carries twenty-two low-value bytes,
 * because {@code INITIALIZE} does not reach a {@code FILLER} item and the create arm therefore wrote
 * whatever the record area held. The authoritative seed agrees with the goldens and not with blanks:
 * bytes 28 to 49 of the first record of {@code app/data/ASCII/tcatbal.txt} are ASCII zeros. The second
 * region is negative zero: an exact decimal has no signed zero, so a balance whose source span carried
 * the negative-zero overpunch character, a right brace, re-encodes through the single-argument overload
 * carrying the positive-zero overpunch character, a left brace.</p>
 *
 * <p>Refactoring Rationale: {@link #toRecord(TransactionCategoryBalance, byte[])} restores both regions
 * from the source image, and it exists because an earlier revision of this class argued that no such
 * overload was warranted -- on the grounds that "every committed expectation file for this record
 * carries the positive-zero form" and that an unused overload would be a second encode path to keep in
 * step. Both halves of that were wrong. The claim was about the SIGN and silently left the PAD
 * unaddressed, and two committed expectations carry a non-blank pad; and the overload is not unused,
 * because the backup generation described above is exactly the caller that needs it. The two overloads
 * are kept in step by sharing one field map, so the only difference between them is what the second one
 * restores afterwards. The single-argument overload remains correct, and remains the right entry point,
 * for a row this module ORIGINATED and which therefore has no source image at all.</p>
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It converts representations and nothing else. It opens no transaction, reaches no repository,
 * performs no arithmetic and decides no branch. It declares no second identifier type: the composite
 * identity it produces and consumes is the nested {@code @Embeddable} declared inside
 * {@link TransactionCategoryBalance}, and a parallel key type in this package would be a second
 * truth about one key. <b>It imports no type from another service's {@code ..domain..} package.</b>
 * That restraint is easy to overlook on this record specifically, because
 * {@code ledger.transaction_category_balances} lives in a schema {@code transaction-service} owns and
 * reaching for that service's entity would look like reuse; it is not, because a compile-time
 * dependency between two independently deployable services means the two must be built, versioned and
 * released together. This module and that one agree through the physical schema, and
 * {@code LayeringRulesTest} asserts it. Every geometry decision here is read from the shared registry
 * rather than declared locally.</p>
 */
public final class TransactionCategoryBalanceRecordMapper {

    /**
     * The logical name the transaction-category-balance layout is registered under in the shared
     * kernel.
     *
     * <p>Assumptions: the name is the whole of the coupling between this class and the descriptor it
     * reads, because the registry resolves geometry by exact name and offers no other handle. Naming
     * it once rather than at each use makes that coupling a single line a reader can find.</p>
     */
    private static final String RECORD_NAME = "TCATBAL";

    // WHY : Assumptions: the descriptor is resolved from the registry rather than declared here, and
    //       the dependency is mechanical rather than stylistic. FixedWidthCodec recognises a trailing
    //       pad only when the descriptor it was handed is the REGISTERED instance -- its padding test
    //       compares CopybookLayout.layout(name) against the supplied spec by IDENTITY. A locally
    //       built spec with byte-identical contents therefore fails that test, and encode would then
    //       reject this four-entry field map with a missing-required-field failure naming FILLER
    //       instead of blank-filling its 22 bytes. Holding the registered instance in one constant
    //       makes that impossible to get wrong, and it satisfies transformation rule T2, under which
    //       one former COPY statement becomes one import from the single package that owns the
    //       contract.
    /**
     * The registered 50-byte category-balance descriptor, transcribed from
     * {@code app/cpy/CVTRA01Y.cpy}.
     */
    private static final CopybookLayout.RecordSpec LAYOUT = CopybookLayout.layout(RECORD_NAME);

    /**
     * The copybook name of the eleven-digit account identifier, the key's leading component.
     */
    private static final String FIELD_ACCOUNT_ID = "TRANCAT-ACCT-ID";

    /**
     * The copybook name of the two-character transaction type code, the key's second component.
     */
    private static final String FIELD_TYPE_CD = "TRANCAT-TYPE-CD";

    /**
     * The copybook name of the four-digit transaction category code, the key's third component.
     */
    private static final String FIELD_CATEGORY_CD = "TRANCAT-CD";

    /**
     * The copybook name of the signed running balance that follows the key.
     */
    private static final String FIELD_BALANCE = "TRAN-CAT-BAL";

    /**
     * The copybook name of the trailing padding span, named only so the assertion below can reach it.
     *
     * <p>Assumptions: the span is a genuine pad rather than content, so it is dropped on decode and
     * blank-filled on encode under the package charter's ruling. It is named here because its offset
     * and width are what reconcile the four mapped fields to the declared record length, and an
     * assertion that could not reach it would leave twenty-two of the fifty bytes unproven.</p>
     */
    private static final String FIELD_FILLER = "FILLER";

    /**
     * The eleven digit positions {@code PIC 9(11)} declares at {@code app/cpy/CVTRA01Y.cpy} line 6.
     */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /**
     * The two character positions {@code PIC X(02)} declares at {@code app/cpy/CVTRA01Y.cpy} line 7.
     */
    private static final int TYPE_CODE_WIDTH = 2;

    /**
     * The four digit positions {@code PIC 9(04)} declares at {@code app/cpy/CVTRA01Y.cpy} line 8.
     */
    private static final int CATEGORY_CODE_WIDTH = 4;

    /**
     * The twenty-two character positions {@code PIC X(22)} declares at {@code app/cpy/CVTRA01Y.cpy}
     * line 10.
     */
    private static final int FILLER_WIDTH = 22;

    /**
     * The registered descriptor of the four-digit category component, the source of its declared
     * width.
     *
     * <p>Assumptions: the width used to restore this component's leading zeros is read from this
     * descriptor rather than written as the literal four, so the rendering follows the picture clause
     * at {@code app/cpy/CVTRA01Y.cpy} line 8 automatically instead of restating a number the picture
     * already establishes.</p>
     */
    private static final CopybookLayout.FieldSpec CATEGORY_FIELD = LAYOUT.field(FIELD_CATEGORY_CD);

    /**
     * The registered descriptor of the balance span, the single source of its scale and digit
     * capacity.
     *
     * <p>Assumptions: the required scale and the magnitude ceiling this class enforces are both read
     * from this descriptor rather than written as literals, so a picture clause restated in the
     * registry cannot disagree with a bound hard-coded here.</p>
     */
    private static final CopybookLayout.FieldSpec BALANCE_FIELD = LAYOUT.field(FIELD_BALANCE);

    /**
     * The nine digit positions {@code PIC S9(09)V99} declares before the implied decimal point.
     *
     * <p>Assumptions: this is transcribed from the picture clause at {@code app/cpy/CVTRA01Y.cpy}
     * line 9 and is what the balance span's width is derived FROM, so that the eleven bytes asserted
     * below is a consequence of the sign-overpunch rule rather than a number written twice.</p>
     */
    private static final int BALANCE_INTEGER_DIGITS = 9;

    /**
     * The exclusive magnitude ceiling of the balance span, being ten raised to its integer-digit
     * count.
     *
     * <p>Assumptions: nine integer digit positions admit magnitudes strictly below one thousand
     * million, so {@code 999999999.99} is representable and {@code 1000000000.00} is not. The bound is
     * derived from {@link #BALANCE_FIELD} so that it follows the picture clause automatically, and it
     * is deliberately NOT {@code com.carddemo.common.money.Money.MAX_MAGNITUDE}: that constant is the
     * ceiling of the twelve-byte account precision, a full decimal order of magnitude wider than this
     * span, so borrowing it here would admit a value this record cannot hold.</p>
     */
    private static final BigDecimal BALANCE_MAGNITUDE_LIMIT =
            BigDecimal.TEN.pow(BALANCE_FIELD.intDigits());

    /**
     * The record length {@code app/cpy/CVTRA01Y.cpy} line 2 states as {@code RECLN = 50}.
     */
    private static final int EXPECTED_RECORD_LENGTH = 50;

    /**
     * The key width {@code app/jcl/TCATBALF.jcl} line 40 declares as the first argument of
     * {@code KEYS(17 0)}.
     */
    private static final int EXPECTED_KEY_LENGTH = 17;

    /**
     * The key offset {@code app/jcl/TCATBALF.jcl} line 40 declares as the second argument of
     * {@code KEYS(17 0)}.
     */
    private static final int EXPECTED_KEY_OFFSET = 0;

    /**
     * The ONE-based position {@code app/jcl/PRTCATBL.jcl} line 50 declares for the balance symbol.
     *
     * <p>Assumptions: this is carried in the sort utility's own one-based convention rather than
     * pre-converted, so that the conversion {@code zeroBased = oneBased - 1} is performed visibly in
     * the assertion below. Storing 17 here instead would record the conclusion and discard the
     * evidence, leaving a reader unable to check the constant against the line it cites.</p>
     */
    private static final int BALANCE_SORT_SYMBOL_POSITION = 18;

    static {
        // WHY : Assumptions: the geometry is asserted at class load rather than trusted, and the
        //       assertion is not redundant with the registry's own. The registry proves contiguity and
        //       summation when CopybookLayout loads; this proves the specific offsets, widths and key
        //       geometry THIS class was written against, and it runs when this class loads, so no
        //       dependence remains on which of the two a job happens to touch first. Every expected
        //       value below is accompanied by the independent artifact that corroborates it, so a
        //       failure names two sources and lets a reader see which one moved.
        requireRegisteredGeometry();
    }

    /**
     * Prevents construction of this stateless converter.
     */
    private TransactionCategoryBalanceRecordMapper() {
        // WHY : Alternatives Considered: an injectable instance shaped for constructor injection, as
        //       the service and repository layers of this module are. Rejected because this type holds
        //       no collaborator that could vary -- the descriptor is a registered constant and the
        //       codecs are themselves stateless holders -- so an instance would contribute a bean
        //       whose only distinguishing state is none, and a job would have to be wired to obtain a
        //       conversion that depends on nothing. The shape matches the sibling mappers beside it,
        //       each a final class with a private constructor and static members, so a reader meeting
        //       all of them meets one shape. The package charter separately forbids a shared supertype
        //       among these mappers, so no polymorphism is given up that was available in the first
        //       place.
    }

    /**
     * Decodes one 50-byte category-balance image into the running-balance row it represents.
     *
     * <p>Assumptions: exactly one image in, exactly one row out. The record is addressed by a unique
     * seventeen-byte key -- {@code app/jcl/TCATBALF.jcl} line 40 declares {@code KEYS(17 0)} and the
     * baseline defines no alternate index over this cluster anywhere -- so a single conversion is the
     * whole answer for a given key and there is no secondary access path a caller could mistake it
     * for.</p>
     *
     * <p>Assumptions: the two-character type code is returned whole, with no trailing blank removed.
     * It is a fixed code and a component of the key, so both characters are the contract rather than
     * an upper bound, and a trimmed value would compare unequal to the key it is looked up by.</p>
     *
     * <p>Assumptions: the category code is returned as four characters with its leading zeros
     * restored, even though the shared codec hands it over as a number. The picture at
     * {@code app/cpy/CVTRA01Y.cpy} line 8 is numeric-display, which right-justifies and zero-fills, so
     * a category of one is stored as {@code 0001}; the owning service's migration declares the column
     * {@code CHAR(4)} for precisely that reason, and {@code 0001} and {@code 1} must not resolve to two
     * different keys. The restoration uses the descriptor's declared width rather than the literal
     * four.</p>
     *
     * <p>Assumptions: the balance arrives at scale exactly two and is never stripped to fewer decimal
     * places, so a balance of {@code 604.00} keeps both cent positions rather than becoming
     * {@code 604}. That is not cosmetic: an exact decimal compares by scale as well as by value, so a
     * scale-stripped zero would compare unequal to the scale-two zero the entity's own zero-seeded
     * constructor produces, and a create arm's result would then differ from a genuine zero for no
     * reason a reader could see.</p>
     *
     * <p>Assumptions: the trailing {@code FILLER} is not carried onto the row, and on this record it is
     * usually still present in the decoded map. The shared codec omits a registered pad only when it is
     * blank, and neither committed expectation file for this record is: one holds ASCII zeros and the
     * other holds low values. Either way the span is simply not read, because the target row declares
     * no counterpart to it.</p>
     *
     * @param record the byte array holding one physical record, which must be exactly 50 bytes -- the
     *     length the registered descriptor declares, which {@code app/cpy/CVTRA01Y.cpy} line 2 states
     *     as {@code RECLN = 50} and {@code app/jcl/TCATBALF.jcl} line 41 repeats as
     *     {@code RECORDSIZE(50 50)}
     * @return the equivalent row, never {@code null}, carrying the composite identity in the key's
     *     physical order and the balance as a signed exact decimal at scale two
     * @throws FixedWidthCodec.RecordLengthException if {@code record} is {@code null} or is not
     *     exactly 50 bytes long
     * @throws FixedWidthCodec.FieldCodecException if the account or category span holds a byte that is
     *     not an ASCII digit, or if a character span does not survive a byte-reversible conversion
     * @throws ZonedDecimalCodec.ZonedDecimalException if the balance span violates the sign-overpunch
     *     contract, which covers a low-order byte that is neither a digit nor a member of either
     *     overpunch table
     * @throws CopybookLayout.LayoutException if the registered descriptor no longer declares a field
     *     this class names
     * @throws IllegalArgumentException if a decoded value contradicts the type its declared storage
     *     kind implies, or if the balance does not arrive at the scale the descriptor declares, either
     *     of which means the registered descriptor and {@code app/cpy/CVTRA01Y.cpy} have diverged
     */
    public static TransactionCategoryBalance toEntity(byte[] record) {
        // WHY : Alternatives Considered: the null and length checks are left to the shared codec
        //       rather than repeated here. Its own check runs before the first slice and names both the
        //       expected and the received width, so a guard added here would duplicate a stricter
        //       check with a weaker message and would then have to be kept in step with it.
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(record, LAYOUT);

        // WHY : Assumptions: the three components are supplied in the key's PHYSICAL order -- account,
        //       then TYPE, then CATEGORY -- which app/cpy/CVTRA01Y.cpy:6-8 declares and which the
        //       cluster key at app/jcl/TCATBALF.jcl:40 addresses. That is also the order posting
        //       assigns them in, at app/cbl/CBTRN02C.cbl:469-471. The order matters here rather than
        //       merely reading tidily, because the constructor below is positional and the two code
        //       components are adjacent short strings: a transposition compiles, satisfies every
        //       signature and produces a key that simply matches no row, which the baseline then
        //       reports as a category balance to create rather than as a malformed key.
        TransactionCategoryBalance.TransactionCategoryBalanceId id =
                new TransactionCategoryBalance.TransactionCategoryBalanceId(
                        accountIdField(decoded),
                        typeCodeField(decoded),
                        categoryCodeField(decoded));

        // WHY : Assumptions: the two-argument constructor is used rather than the zero-seeded one even
        //       when the decoded balance happens to be zero, and the distinction is the create-versus-
        //       update expressiveness the migration plan's section 0.5.1.7 requires. A decode always
        //       represents a row that EXISTS, whose balance is whatever the record carried; the
        //       zero-seeded constructor represents the INITIALIZE at app/cbl/CBTRN02C.cbl:504, which is
        //       a row that did not exist. Reaching for the zero-seeded form on a decoded zero would
        //       make the two arms indistinguishable at exactly the point a test needs to tell them
        //       apart -- and tests/golden/posting/reject_100_card_missing/tcatbal.expected is a real
        //       row whose balance is an exact zero.
        return new TransactionCategoryBalance(id, balanceField(decoded));
    }

    /**
     * Encodes one running-balance row into its 50-byte physical image.
     *
     * <p>Assumptions: the returned array is always exactly 50 bytes, never shorter and never merely
     * long enough for the values supplied. A caller writing a backup generation depends on that: a
     * record right in its first 28 bytes and 22 bytes short still writes, still reads back, and shifts
     * every field of every record after it.</p>
     *
     * <p>Assumptions: the {@code FILLER} span is supplied to the codec by OMISSION and is written back
     * as blanks. Leaving the freshly allocated array's zero bytes in place instead would preserve the
     * record's length but not its content, and a reader of the record distinguishes low values from
     * spaces even though both look empty once decoded into text. Naming the pad explicitly with a run
     * of twenty-two spaces would produce the same bytes today and would stop doing so the moment the
     * descriptor's pad width changed, because the literal would then be measured against a span it no
     * longer fits.</p>
     *
     * <p>Assumptions: the four values are inserted in the copybook's declaration order, which is the
     * key order the interest job's control break depends on. The shared codec keys by field name and
     * so does not require any particular insertion order, but a map that reads in a different order
     * from the record it produces is a map a reader has to reconcile against the descriptor by hand,
     * and this is the record where a reordering is least visible and most damaging.</p>
     *
     * <p>Assumptions: encoding is lossless or it throws. A balance needing more than the descriptor's
     * nine integer digit positions, or carrying a scale other than the two it declares, is refused
     * rather than rounded or truncated, because a rounding decision is a business decision that
     * belongs to {@code com.carddemo.common.money.Money} at the service layer where an audit trail can
     * show it, not to an encoder where it would be invisible.</p>
     *
     * <p>Assumptions: <b>this method is the single code path for both posting arms.</b> A row the
     * create arm produced and a row the update arm produced differ only in the value of their balance,
     * and nothing here inspects which arm was in flight, so the two byte images are produced
     * identically. Any divergence between them therefore originates in
     * {@code com.carddemo.batch.service.CategoryBalanceService} and never here, which is what makes
     * this method usable as the shared oracle for both.</p>
     *
     * @param entity the row to render, whose composite identifier supplies the three key components
     *     and whose balance supplies the fourth field; must not be {@code null}
     * @return a newly allocated byte array of exactly 50 bytes, carrying the account identifier
     *     zero-filled to eleven digits, the type code at two characters, the category code zero-filled
     *     to four digits, the balance as eleven zoned-decimal display characters with the sign
     *     overpunched onto the last of them, and twenty-two blank pad bytes at offset 28
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws IllegalArgumentException if the row's identifier or any of its three components is
     *     {@code null}, if the account identifier is negative or wider than its span, if the category
     *     code is not exactly four ASCII digits, or if the balance is {@code null}, carries a scale
     *     other than the descriptor's, or has a magnitude the descriptor's integer digit positions
     *     cannot hold
     * @throws CopybookLayout.LayoutException if the registered descriptor no longer declares a field
     *     this class names
     * @throws FixedWidthCodec.FieldCodecException if a supplied value does not fit its declared field,
     *     which covers a type code longer than its declared width
     * @throws ZonedDecimalCodec.ZonedDecimalException if the balance cannot be represented in its span
     *     without losing precision
     */
    public static byte[] toRecord(TransactionCategoryBalance entity) {
        return FixedWidthCodec.encodeRecord(fieldMap(entity), LAYOUT);
    }

    /**
     * Encodes one running-balance row into its 50-byte image, restoring from a source image what the
     * entity cannot carry.
     *
     * <p>Refactoring Rationale: this overload exists because the plain one CANNOT satisfy the
     * byte-identical obligation this class states for a backup generation, and an earlier revision of
     * this file argued the opposite -- that no overload was warranted because "every committed
     * expectation file for this record carries the positive-zero form". That reasoning was wrong on the
     * pad and incomplete on the sign. Two of the committed expectations carry a NON-BLANK pad in the
     * twenty-two byte span at {@code app/cpy/CVTRA01Y.cpy} line 10:
     * {@code tests/golden/posting/happy_path/tcatbal.expected} carries twenty-two ASCII zeros,
     * inherited from the seed row the update arm rewrote, and
     * {@code tests/golden/posting/zero_balance/tcatbal.expected} carries twenty-two low-value bytes,
     * because {@code INITIALIZE} does not reach a {@code FILLER} item so the create arm wrote whatever
     * the record area held. The authoritative seed agrees: bytes 28 to 49 of the first record of
     * {@code app/data/ASCII/tcatbal.txt} are ASCII zeros, not spaces. Re-encoding either row through
     * the plain overload therefore substitutes blanks across that span and produces a generation that
     * differs from the dataset it copies, which is precisely the failure the parity comparison would
     * report and precisely what a backup generation must not do.</p>
     *
     * <p>Assumptions: the two regions restored here are the two a decode cannot carry, and each has a
     * named cause. The {@code FILLER} span has no entity member at all, because the package charter
     * drops a pad carrying neither a {@code REDEFINES} nor a {@code VALUE} clause, so it is copied
     * unconditionally. The sign carrier of a zero balance is restored because an exact decimal has no
     * signed zero, so a span whose source carried the negative-zero overpunch character would otherwise
     * re-encode carrying the positive-zero one.</p>
     *
     * <p>Assumptions: the plain overload REMAINS and is not deprecated. It is the correct entry point
     * for a row this module ORIGINATED -- one the posting or interest service just computed, which has
     * no source image because no image was ever read -- and for that row a blank pad is the right pad.
     * The two overloads therefore answer two different questions, "render this row" and "reproduce the
     * record this row came from", and collapsing them would force a caller with no image to invent one.</p>
     *
     * <p>Trade-offs: the restoration is unconditional for both spans rather than conditional on the
     * value being unchanged, which is a deliberate difference from the timestamp handling on the
     * daily-transaction feed. It is safe here because neither restored region is derived from an entity
     * member: the pad has no member to disagree with, and the sign carrier is restored by the shared
     * codec only where the encoded and source magnitudes already agree. There is no third region whose
     * value a caller could have legitimately changed, so no equality test is needed to keep the method
     * from ignoring its own input.</p>
     *
     * @param entity the row to render, whose composite identifier supplies the three key components and
     *     whose balance supplies the fourth field; must not be {@code null}
     * @param sourceImage the byte array of exactly 50 bytes the entity was decoded from; it is read and
     *     never modified, and it supplies the twenty-two byte trailing pad and the zero-balance sign
     *     carrier
     * @return a newly allocated byte array of exactly 50 bytes that reproduces {@code sourceImage}
     *     wherever the entity's members still agree with it, including the pad exactly as it was read
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws IllegalArgumentException if the row's identifier or any of its three components is
     *     {@code null}, if the account identifier is negative or wider than its span, if the category
     *     code is not exactly four ASCII digits, or if the balance is {@code null}, carries a scale
     *     other than the descriptor's, or has a magnitude the descriptor's integer digit positions
     *     cannot hold
     * @throws CopybookLayout.LayoutException if the registered descriptor no longer declares a field
     *     this class names
     * @throws FixedWidthCodec.RecordLengthException if {@code sourceImage} is {@code null} or is not
     *     exactly 50 bytes
     * @throws FixedWidthCodec.FieldCodecException if a supplied value does not fit its declared field
     * @throws ZonedDecimalCodec.ZonedDecimalException if the balance cannot be represented in its span
     *     without losing precision
     */
    public static byte[] toRecord(TransactionCategoryBalance entity, byte[] sourceImage) {
        // WHY : Assumptions: the sign-preserving entry point is used rather than the plain one because
        //       it also performs the width check on the source image, so a caller that passed an image
        //       of the wrong length is told so by width rather than by an array bounds failure raised
        //       from inside the pad copy below.
        byte[] encoded =
                FixedWidthCodec.encodeRecordPreservingSign(fieldMap(entity), LAYOUT, sourceImage);

        copySpan(sourceImage, encoded, LAYOUT.field(FIELD_FILLER));
        return encoded;
    }

    /**
     * Builds the four-entry field map both encode overloads render.
     *
     * <p>Assumptions: the four values are inserted in the copybook's declaration order, which is the key
     * order the interest job's control break depends on. The shared codec keys by field name and so does
     * not require any particular insertion order, but a map that reads in a different order from the
     * record it produces is a map a reader has to reconcile against the descriptor by hand, and this is
     * the record where a reordering is least visible and most damaging.</p>
     *
     * <p>Assumptions: the {@code FILLER} span is supplied to the codec by OMISSION. The plain overload
     * relies on that to get blanks; the source-image overload relies on it so that the copy it performs
     * afterwards is writing over blanks rather than fighting a value this map had asserted.</p>
     *
     * <p>Refactoring Rationale: extracted so the two overloads share one construction of the map rather
     * than each building its own. Two copies would be two places for a field name or an insertion order
     * to drift, and the whole point of the second overload is that it differs from the first ONLY in
     * what it restores afterwards.</p>
     *
     * @param entity the row whose four field values are wanted; must not be {@code null}
     * @return a mutable map in copybook declaration order, carrying the three key components and the
     *     balance and deliberately omitting the pad
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws IllegalArgumentException if the identifier, any component or the balance is unusable, on
     *     the terms the two public overloads document
     */
    private static Map<String, Object> fieldMap(TransactionCategoryBalance entity) {
        Objects.requireNonNull(entity, "transaction category balance entity must not be null");
        TransactionCategoryBalance.TransactionCategoryBalanceId id = requiredIdentity(entity.getId());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_ACCOUNT_ID, requiredAccountId(id.getAccountId()));
        fields.put(FIELD_TYPE_CD, requiredComponent(id.getTypeCd(), "typeCd", FIELD_TYPE_CD));
        fields.put(FIELD_CATEGORY_CD, requiredCategoryCode(id.getCategoryCd()));
        fields.put(FIELD_BALANCE, encodableBalance(entity.getBalance()));
        return fields;
    }

    /**
     * Renders one composite identity as the seventeen-byte key span a keyed read addresses.
     *
     * <p>Assumptions: this is the direct analogue of {@code app/cbl/CBTRN02C.cbl} lines 469 to 471,
     * where posting builds {@code FD-TRAN-CAT-KEY} component by component before every read of the
     * file. It exists so that the key geometry this class asserts is reachable rather than merely
     * documented, and so that a caller needing the key image does not rebuild it from three padded
     * renderings of its own.</p>
     *
     * <p>Assumptions: <b>the three key fields can be written into an array of only
     * {@link #keyLength()} bytes solely because {@link #keyOffset()} is zero</b>, so each field's
     * record offset is already its offset within the key. The class-load assertion above proves that
     * offset is zero against {@code app/jcl/TCATBALF.jcl} line 40, which is what makes this method
     * correct rather than coincidentally correct; on a record whose key began anywhere else the same
     * code would write outside the array and the shared codec would reject it.</p>
     *
     * @param id the composite identity to render, whose three components are the account identifier,
     *     the transaction type code and the transaction category code; must not be {@code null}
     * @return a newly allocated byte array of exactly {@link #keyLength()} bytes, carrying the three
     *     components in the key's physical order at their declared widths
     * @throws IllegalArgumentException if {@code id} is {@code null}, if any component is {@code null},
     *     if the account identifier is negative or wider than its span, or if the category code is not
     *     exactly four ASCII digits
     * @throws CopybookLayout.LayoutException if the registered descriptor no longer declares a field
     *     this class names
     * @throws FixedWidthCodec.FieldCodecException if a supplied value does not fit its declared field
     */
    public static byte[] toKeyImage(TransactionCategoryBalance.TransactionCategoryBalanceId id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "transaction category balance identity is required and was null");
        }

        byte[] key = new byte[LAYOUT.keyLength()];
        FixedWidthCodec.encodeField(requiredAccountId(id.getAccountId()),
                LAYOUT.field(FIELD_ACCOUNT_ID), key);
        FixedWidthCodec.encodeField(requiredComponent(id.getTypeCd(), "typeCd", FIELD_TYPE_CD),
                LAYOUT.field(FIELD_TYPE_CD), key);
        FixedWidthCodec.encodeField(requiredCategoryCode(id.getCategoryCd()),
                LAYOUT.field(FIELD_CATEGORY_CD), key);
        return key;
    }

    /**
     * The byte width of this record's primary key, as the registered descriptor declares it.
     *
     * @return the key width, which {@code app/jcl/TCATBALF.jcl} line 40 declares as the 17 of
     *     {@code KEYS(17 0)} and which the three key components at {@code app/cpy/CVTRA01Y.cpy} lines
     *     6 to 8 sum to
     */
    public static int keyLength() {
        return LAYOUT.keyLength();
    }

    /**
     * The zero-based byte offset at which this record's primary key begins.
     *
     * @return the key offset, which {@code app/jcl/TCATBALF.jcl} line 40 declares as the 0 of
     *     {@code KEYS(17 0)}, meaning the key leads the record
     */
    public static int keyOffset() {
        return LAYOUT.keyOffset();
    }

    /**
     * Proves the registered descriptor still has the geometry this class was written against.
     *
     * <p>Assumptions: every expected value is either transcribed from a picture clause or derived by
     * summation from ones that are, and each is then compared against the registered descriptor rather
     * than against another literal. That is what makes each comparison a runtime assertion about the
     * registry instead of arithmetic the compiler folds away. The three sums proved here are the key
     * arithmetic, {@code 11 + 2 + 4 = 17}; the record arithmetic,
     * {@code 11 + 2 + 4 + 11 + 22 = 50}; and the balance offset, which the summation places at 17.</p>
     *
     * <p>Assumptions: the balance span's expected WIDTH is derived through
     * {@code ZonedDecimalCodec.widthOf} from the nine and two of {@code PIC S9(09)V99} rather than
     * written as eleven, so this asserts the sign-overpunch rule instead of restating its result. The
     * fractional count comes from {@code Money.SCALE}, which additionally proves the record's money
     * scale IS the migration's money scale rather than merely resembling it.</p>
     *
     * <p>Assumptions: the balance offset is separately checked against
     * {@link #BALANCE_SORT_SYMBOL_POSITION} converted out of the sort utility's one-based convention.
     * That check is what makes the third of the three independent sources an assertion rather than a
     * remark in a comment: if the copybook summation and the DFSORT symbol ever disagreed, this is
     * where it would surface.</p>
     *
     * @throws IllegalStateException if the registered descriptor's record length, key width, key
     *     offset, field offsets, field widths, digit counts or signedness differ from the values
     *     {@code app/cpy/CVTRA01Y.cpy}, {@code app/jcl/TCATBALF.jcl} and {@code app/jcl/PRTCATBL.jcl}
     *     agree on
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under one
     *     of the five names this class uses, or if its own contiguity proof fails
     */
    private static void requireRegisteredGeometry() {
        // WHY : Assumptions: contiguity is re-proved here even though the registry validates it when
        //       CopybookLayout loads, because the two orderings differ. Re-proving costs one pass over
        //       five descriptors and removes any dependence on which class a job happens to touch
        //       first.
        LAYOUT.validateGeometry();

        int accountIdStart = EXPECTED_KEY_OFFSET;
        int typeCodeStart = accountIdStart + ACCOUNT_ID_WIDTH;
        int categoryCodeStart = typeCodeStart + TYPE_CODE_WIDTH;
        int balanceStart = categoryCodeStart + CATEGORY_CODE_WIDTH;
        int balanceWidth = ZonedDecimalCodec.widthOf(BALANCE_INTEGER_DIGITS, Money.SCALE);
        int fillerStart = balanceStart + balanceWidth;

        if (LAYOUT.reclen() != EXPECTED_RECORD_LENGTH) {
            throw new IllegalStateException("layout " + RECORD_NAME + " must declare "
                    + EXPECTED_RECORD_LENGTH + " bytes, which app/cpy/CVTRA01Y.cpy:2 states as"
                    + " RECLN = 50 and app/jcl/TCATBALF.jcl:41 repeats as RECORDSIZE(50 50), but it"
                    + " declares " + LAYOUT.reclen());
        }
        if (LAYOUT.keyLength() != EXPECTED_KEY_LENGTH) {
            throw new IllegalStateException("layout " + RECORD_NAME + " must declare a key of "
                    + EXPECTED_KEY_LENGTH + " bytes, which app/jcl/TCATBALF.jcl:40 declares as the"
                    + " first argument of KEYS(17 0), but it declares " + LAYOUT.keyLength());
        }
        if (LAYOUT.keyOffset() != EXPECTED_KEY_OFFSET) {
            throw new IllegalStateException("layout " + RECORD_NAME + " must place its key at offset "
                    + EXPECTED_KEY_OFFSET + ", which app/jcl/TCATBALF.jcl:40 declares as the second"
                    + " argument of KEYS(17 0), but it places it at " + LAYOUT.keyOffset());
        }
        if (categoryCodeStart + CATEGORY_CODE_WIDTH != LAYOUT.keyLength()) {
            throw new IllegalStateException("the three key component widths transcribed from"
                    + " app/cpy/CVTRA01Y.cpy:6-8 sum to "
                    + (categoryCodeStart + CATEGORY_CODE_WIDTH) + " bytes, which disagrees with the "
                    + LAYOUT.keyLength() + " byte key layout " + RECORD_NAME + " declares");
        }
        if (fillerStart + FILLER_WIDTH != LAYOUT.reclen()) {
            throw new IllegalStateException("the five field widths transcribed from"
                    + " app/cpy/CVTRA01Y.cpy:6-10 sum to " + (fillerStart + FILLER_WIDTH)
                    + " bytes, which disagrees with the " + LAYOUT.reclen() + " byte record layout "
                    + RECORD_NAME + " declares");
        }

        requireFieldGeometry(FIELD_ACCOUNT_ID, accountIdStart, ACCOUNT_ID_WIDTH,
                "app/jcl/PRTCATBL.jcl:47 declares TRANCAT-ACCT-ID,1,11 in ONE-based positions");
        requireFieldGeometry(FIELD_TYPE_CD, typeCodeStart, TYPE_CODE_WIDTH,
                "app/jcl/PRTCATBL.jcl:48 declares TRANCAT-TYPE-CD,12,2 in ONE-based positions");
        requireFieldGeometry(FIELD_CATEGORY_CD, categoryCodeStart, CATEGORY_CODE_WIDTH,
                "app/jcl/PRTCATBL.jcl:49 declares TRANCAT-CD,14,4 in ONE-based positions");
        requireFieldGeometry(FIELD_BALANCE, balanceStart, balanceWidth,
                "a signed zoned field overpunches its sign onto the low-order digit, so S9(09)V99"
                        + " occupies nine plus two positions and not twelve");
        requireFieldGeometry(FIELD_FILLER, fillerStart, FILLER_WIDTH,
                "app/cpy/CVTRA01Y.cpy:10 pads the record to its declared length with FILLER PIC X(22)");

        if (BALANCE_FIELD.start() != BALANCE_SORT_SYMBOL_POSITION - 1) {
            throw new IllegalStateException("field " + FIELD_BALANCE + " of layout " + RECORD_NAME
                    + " begins at offset " + BALANCE_FIELD.start()
                    + ", but app/jcl/PRTCATBL.jcl:50 declares TRAN-CAT-BAL,18,11,ZD in ONE-based"
                    + " positions, which converts to zero-based offset "
                    + (BALANCE_SORT_SYMBOL_POSITION - 1));
        }
        if (BALANCE_FIELD.intDigits() != BALANCE_INTEGER_DIGITS
                || BALANCE_FIELD.decDigits() != Money.SCALE) {
            throw new IllegalStateException("field " + FIELD_BALANCE + " of layout " + RECORD_NAME
                    + " declares " + BALANCE_FIELD.intDigits() + " integer and "
                    + BALANCE_FIELD.decDigits() + " fractional digit positions, but"
                    + " app/cpy/CVTRA01Y.cpy:9 declares PIC S9(09)V99, which is "
                    + BALANCE_INTEGER_DIGITS + " and " + Money.SCALE);
        }
        if (!BALANCE_FIELD.signed()) {
            throw new IllegalStateException("field " + FIELD_BALANCE + " of layout " + RECORD_NAME
                    + " must be signed, because app/cpy/CVTRA01Y.cpy:9 declares PIC S9(09)V99 with a"
                    + " leading S and app/cbl/CBTRN02C.cbl:508 and :527 both add a signed transaction"
                    + " amount onto it, so a negative balance is a legitimate value");
        }
    }

    /**
     * Asserts that one named field occupies the offset and width this class depends on.
     *
     * <p>Assumptions: the corroborating artifact is carried into the failure rather than left in a
     * comment, so that a reader meeting the failure can check the disagreement against a second source
     * without going looking for one. Where the registry and {@code app/cpy/CVTRA01Y.cpy} disagree the
     * copybook is right and the registry is the defect, under transformation rule T1, so this reports
     * the disagreement instead of compensating for it locally.</p>
     *
     * @param fieldName the exact copybook name of the field to check, which the registered descriptor
     *     must declare
     * @param expectedStart the zero-based offset the field is asserted to begin at
     * @param expectedLength the byte count the field is asserted to occupy
     * @param corroboration a phrase naming the independent artifact that agrees with those numbers,
     *     carried into any failure
     * @throws IllegalStateException if the registered descriptor's offset or width for that field
     *     differs from the asserted values
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under that
     *     name
     */
    private static void requireFieldGeometry(String fieldName, int expectedStart, int expectedLength,
            String corroboration) {
        CopybookLayout.FieldSpec field = LAYOUT.field(fieldName);
        if (field.start() != expectedStart || field.length() != expectedLength) {
            throw new IllegalStateException("layout " + RECORD_NAME + " must place " + fieldName
                    + " at offset " + expectedStart + " across " + expectedLength + " bytes, but it"
                    + " declares offset " + field.start() + " across " + field.length()
                    + " bytes; " + corroboration);
        }
    }

    /**
     * Reads the decoded account identifier as the value the composite identity carries.
     *
     * <p>Assumptions: the shared codec returns an unsigned display field as an integral value and the
     * span is eleven digits wide, which exceeds the range of a thirty-two bit integer -- nine thousand
     * nine hundred and ninety-nine million and more -- so the target column is {@code BIGINT} and the
     * component is a long. Narrowing it would truncate exactly the accounts whose identifiers use the
     * eleventh digit.</p>
     *
     * <p>Assumptions: signedness is taken from the picture clause and never inferred from the bytes.
     * {@code app/cpy/CVTRA01Y.cpy} line 6 declares {@code PIC 9(11)} without the leading {@code S}
     * that would make it signed, so the span is plain ASCII digits and its final byte is a digit like
     * any other. A trailing byte that happens to be a letter is therefore a malformed record rather
     * than an overpunched sign, and the shared codec rejects it with the relative offset of the
     * offending byte instead of coercing it or parsing as far as it can. Parsing the leading digits of
     * a shifted record would return a short number that still reads as an account identifier, which is
     * the failure that never gets noticed.</p>
     *
     * @param decoded the map of copybook field name to decoded value the shared codec produced for
     *     this record
     * @return the account identifier, with the leading zeros of the source digits absorbed into its
     *     numeric value
     * @throws IllegalArgumentException if the decoded value is absent or is not integral, either of
     *     which means the registered descriptor no longer agrees with this class
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     account field's name
     */
    private static Long accountIdField(Map<String, Object> decoded) {
        Object value = decoded.get(FIELD_ACCOUNT_ID);
        if (value instanceof Long integral) {
            return integral;
        }
        throw registryDisagreement(FIELD_ACCOUNT_ID, "an unsigned integral value", value);
    }

    /**
     * Reads the decoded transaction type code at its declared width, with no trailing blank removed.
     *
     * <p>Assumptions: this field is a key component, so its declared width is part of the contract
     * rather than an upper bound and a trimmed value would compare unequal to the key it is looked up
     * by. This is the opposite of the treatment a descriptive character field receives elsewhere in
     * this package, where trailing blanks are padding.</p>
     *
     * @param decoded the map of copybook field name to decoded value the shared codec produced for
     *     this record
     * @return the type code's two characters, including any blank the source carried
     * @throws IllegalArgumentException if the decoded value is absent or is not character data, either
     *     of which means the registered descriptor no longer agrees with this class
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     type field's name
     */
    private static String typeCodeField(Map<String, Object> decoded) {
        Object value = decoded.get(FIELD_TYPE_CD);
        if (value instanceof String text) {
            return text;
        }
        throw registryDisagreement(FIELD_TYPE_CD, "character text", value);
    }

    /**
     * Reads the decoded category code and restores the leading zeros its numeric picture implies.
     *
     * <p>Assumptions: this component is derived one way from the copybook and settled the other way by
     * the owning migration, and the conversion happens here because this is the boundary that owns
     * representation concerns. The picture at {@code app/cpy/CVTRA01Y.cpy} line 8 is
     * {@code PIC 9(04)}, so the shared codec decodes the span to a number and a category of one
     * arrives as {@code 1}. The column is {@code CHAR(4)}, because an integral column drops the leading
     * zeros the baseline writes and {@code 0001} and {@code 1} must not resolve to two different keys.
     * Restoring the zeros is therefore not formatting: it is what keeps the decoded key equal to the
     * key the record was stored under.</p>
     *
     * <p>Assumptions: the width comes from {@link #CATEGORY_FIELD} rather than from the literal four,
     * so the rendering follows the picture clause automatically. A descriptor whose category span had
     * been widened would then produce a wider rendering that the entity's own width check refuses,
     * rather than a silently truncated one.</p>
     *
     * @param decoded the map of copybook field name to decoded value the shared codec produced for
     *     this record
     * @return the category code as exactly {@code CATEGORY_FIELD.length()} ASCII digits, with its
     *     leading zeros restored
     * @throws IllegalArgumentException if the decoded value is absent, is not integral, or needs more
     *     digit positions than the span provides, any of which means the registered descriptor no
     *     longer agrees with this class
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     category field's name
     */
    private static String categoryCodeField(Map<String, Object> decoded) {
        Object value = decoded.get(FIELD_CATEGORY_CD);
        if (!(value instanceof Long integral)) {
            throw registryDisagreement(FIELD_CATEGORY_CD, "an unsigned integral value", value);
        }

        String digits = Long.toString(integral.longValue());
        if (digits.length() > CATEGORY_FIELD.length()) {
            throw new IllegalArgumentException("record " + RECORD_NAME + " "
                    + describeField(FIELD_CATEGORY_CD) + " decoded as " + digits + ", which needs "
                    + digits.length() + " digit positions rather than the " + CATEGORY_FIELD.length()
                    + " the span provides; the registered descriptor and app/cpy/CVTRA01Y.cpy have"
                    + " diverged");
        }
        return "0".repeat(CATEGORY_FIELD.length() - digits.length()) + digits;
    }

    /**
     * Reads the decoded balance as an exact decimal at the money contract's scale.
     *
     * <p>Assumptions: the scale is verified rather than imposed. The descriptor declares two decimal
     * places for this span, the class-load assertion above proves that count equals
     * {@link Money#SCALE}, and the shared codec returns a value at exactly the declared scale -- so a
     * value arriving at any other scale means the registered descriptor and
     * {@code app/cpy/CVTRA01Y.cpy} have diverged. Reporting that is preferred to rescaling, because
     * rescaling here would compensate locally for a defect in a descriptor every other consumer of
     * this record also reads, hiding it from all of them.</p>
     *
     * <p>Assumptions: a zero is returned as a present value at scale two and is never normalised to an
     * absent one. Four of the nine committed posting scenarios expect an exact zero in this span, so
     * treating zero as absence would turn a passing scenario into a null the row cannot hold.</p>
     *
     * @param decoded the map of copybook field name to decoded value the shared codec produced for
     *     this record
     * @return the signed running balance at exactly {@link Money#SCALE} decimal places, which may be
     *     zero and may be negative because the source picture carries a leading sign
     * @throws IllegalArgumentException if the decoded value is absent, is not an exact decimal, or does
     *     not carry exactly the money contract's number of decimal places
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     balance field's name
     */
    private static BigDecimal balanceField(Map<String, Object> decoded) {
        Object value = decoded.get(FIELD_BALANCE);
        if (!(value instanceof BigDecimal balance)) {
            throw registryDisagreement(FIELD_BALANCE, "an exact decimal balance", value);
        }
        if (balance.scale() != Money.SCALE) {
            throw new IllegalArgumentException("record " + RECORD_NAME + " "
                    + describeField(FIELD_BALANCE) + " decoded at scale " + balance.scale()
                    + " but the money contract requires exactly " + Money.SCALE
                    + "; the registered descriptor and app/cpy/CVTRA01Y.cpy have diverged");
        }
        return balance;
    }

    /**
     * Requires that a row carries the composite identity every one of its key spans is rendered from.
     *
     * <p>Assumptions: an identity is absent only on an instance the persistence provider has begun to
     * materialise and not finished, which is not a state a caller can legitimately encode. Rendering
     * one anyway would produce a record whose whole seventeen-byte key span is blank, and such a record
     * writes successfully and then addresses nothing.</p>
     *
     * @param id the composite identity read from the row, which may be {@code null}
     * @return the same identity unchanged, so the check can be applied inline where it is used
     * @throws IllegalArgumentException if {@code id} is {@code null}
     */
    private static TransactionCategoryBalance.TransactionCategoryBalanceId requiredIdentity(
            TransactionCategoryBalance.TransactionCategoryBalanceId id) {
        if (id == null) {
            throw new IllegalArgumentException("transaction category balance property id is null;"
                    + " every key column this record maps is declared not-null, so there is no"
                    + " permitted-null case to render as blanks");
        }
        return id;
    }

    /**
     * Requires that the account component has a value the unsigned span can carry, and returns it.
     *
     * <p>Assumptions: <b>this component reaches the identity from the card cross-reference and not
     * from the transaction being posted</b>, per {@code app/cbl/CBTRN02C.cbl} line 469, so a caller
     * holding no account identifier has a cross-reference read to perform rather than a zero to
     * substitute. That is why an absent value is refused here instead of being rendered as eleven
     * zeros, which would be a valid-looking key addressing account zero.</p>
     *
     * <p>Assumptions: a negative value is refused because the span has nowhere to put the sign.
     * {@code app/cpy/CVTRA01Y.cpy} line 6 declares {@code PIC 9(11)} with no leading {@code S}, so
     * every one of its eleven bytes is a digit and no overpunch carrier exists. The width is left to
     * the shared codec, whose own check names the field, the digits needed and the digits available;
     * repeating it here would duplicate a stricter check with a weaker message.</p>
     *
     * @param accountId the account component read from the composite identity, which may be
     *     {@code null}
     * @return the same component unchanged, so the check can be applied inline where it is used
     * @throws IllegalArgumentException if {@code accountId} is {@code null} or is negative
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     account field's name
     */
    private static Long requiredAccountId(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("transaction category balance identity property"
                    + " accountId, which supplies " + describeField(FIELD_ACCOUNT_ID)
                    + ", is null; the value is obtained from the card cross-reference read rather than"
                    + " from the transaction being posted, so an absent one is a missing lookup");
        }
        if (accountId.longValue() < 0L) {
            throw new IllegalArgumentException("transaction category balance identity property"
                    + " accountId " + accountId + " is negative, which "
                    + describeField(FIELD_ACCOUNT_ID)
                    + " cannot carry; an unsigned display span has no sign carrier");
        }
        return accountId;
    }

    /**
     * Requires that a character key component has a value, and returns it unchanged.
     *
     * <p>Assumptions: there is no permitted-null case to render, which is why this rejects rather than
     * substituting blanks. All three key columns are declared not-null by the owning service, so a
     * null component is a key no owner should hold, and a blank rendering would produce a record whose
     * key writes successfully and then addresses nothing.</p>
     *
     * @param value the component read from the composite identity, which may be {@code null}
     * @param propertyName the name of the identity property the value was read from, used to point a
     *     caller at the object they supplied rather than at the copybook
     * @param fieldName the exact copybook name of the field that component supplies
     * @return the same component unchanged, so the check can be applied inline where it is used
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under that
     *     name
     */
    private static String requiredComponent(String value, String propertyName, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("transaction category balance identity property "
                    + propertyName + ", which supplies " + describeField(fieldName)
                    + ", is null; every key column this record maps is declared not-null, so there is"
                    + " no permitted-null case to render as blanks");
        }
        return value;
    }

    /**
     * Requires that the category component is exactly the declared number of ASCII digits.
     *
     * <p>Assumptions: the value is required at exactly its declared width and a short value is refused
     * rather than zero-extended. The shared codec would left-pad a short value to the span's width, and
     * that is precisely what must not be allowed to happen silently here: a persisted {@code 5}
     * padded to {@code 0005} is not the same key rendered more fully, it is a DIFFERENT key, and the
     * record would be written against a category the caller never named. The check therefore runs
     * before the codec sees the value, and the codec's own padding is left with nothing to do.</p>
     *
     * <p>Assumptions: a non-digit is refused rather than coerced, because the picture at
     * {@code app/cpy/CVTRA01Y.cpy} line 8 is numeric-display and a fixed-character column's
     * trailing-blank insensitivity offers no protection here. That insensitivity forgives a value short
     * on the RIGHT, whereas a missing leading zero is short on the LEFT: a stored {@code 5} compares as
     * {@code 5} and simply misses rather than being normalised.</p>
     *
     * @param categoryCd the category component exactly as the persistence mapping holds it, which must
     *     be exactly {@code CATEGORY_FIELD.length()} ASCII digits with its leading zeros intact
     * @return the same digits unchanged, so the check can be applied inline where it is used
     * @throws IllegalArgumentException if {@code categoryCd} is {@code null}, is not exactly the
     *     declared width, or holds any character that is not an ASCII digit
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     category field's name
     */
    private static String requiredCategoryCode(String categoryCd) {
        String digits = requiredComponent(categoryCd, "categoryCd", FIELD_CATEGORY_CD);
        if (digits.length() != CATEGORY_FIELD.length()) {
            throw new IllegalArgumentException("transaction category balance identity property"
                    + " categoryCd '" + digits + "' is " + digits.length() + " characters, not exactly "
                    + CATEGORY_FIELD.length() + "; the leading zeros of a numeric-display code are part"
                    + " of the key, so a short value is a different key rather than the same one"
                    + " padded");
        }
        for (int index = 0; index < digits.length(); index++) {
            char candidate = digits.charAt(index);
            if (candidate < '0' || candidate > '9') {
                throw new IllegalArgumentException("transaction category balance identity property"
                        + " categoryCd '" + digits + "' holds a non-digit at relative offset " + index
                        + ", which " + describeField(FIELD_CATEGORY_CD)
                        + " cannot carry; a numeric-display span holds ASCII digits only");
            }
        }
        return digits;
    }

    /**
     * Verifies that a balance can be written to its span without losing precision, and returns it.
     *
     * <p>Assumptions: both bounds follow the picture clause at {@code app/cpy/CVTRA01Y.cpy} line 9
     * automatically rather than being written as literals. The scale must equal the money contract's
     * two places, which the class-load assertion proves the descriptor also declares, and the magnitude
     * must be strictly below ten raised to the descriptor's own integer-digit count.</p>
     *
     * <p>Assumptions: a balance outside either bound is refused rather than rounded or truncated, and
     * the refusal is the point. {@code app/cbl/CBACT04C.cbl} lines 464 and 465 multiply this balance by
     * a disclosure-group rate BEFORE dividing the product by 1200, so a cent quietly rounded away here
     * would be multiplied by a rate before anything scaled it back down. Where a business rule
     * genuinely decides an amount should be reduced, {@code com.carddemo.common.money.Money} performs
     * that reduction at the service layer under its own documented mode; an encoder that did it
     * silently would hide a decision the audit trail needs to show.</p>
     *
     * <p>Assumptions: a negative balance is accepted, because the picture carries a leading sign and
     * the span has an overpunch carrier for it. {@code app/cbl/CBTRN02C.cbl} adds the transaction
     * amount straight onto this balance on both arms, at line 508 and at line 527, and that amount is
     * signed -- its line 548 tests whether the amount is non-negative precisely so that it can route a
     * negative one to the account's debit accumulator -- so narrowing the domain to non-negative values
     * would refuse a refund the baseline accepts.</p>
     *
     * @param balance the running balance read from the row, which may be zero or negative
     * @return the same balance unchanged, so the check can be applied inline where the value is used
     * @throws IllegalArgumentException if {@code balance} is {@code null}, carries a scale other than
     *     the money contract's, or has a magnitude the declared integer digit positions cannot hold
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     balance field's name
     */
    private static BigDecimal encodableBalance(BigDecimal balance) {
        if (balance == null) {
            throw new IllegalArgumentException("transaction category balance property balance, which"
                    + " supplies " + describeField(FIELD_BALANCE)
                    + ", is null; a zero balance is a meaningful value rather than an absent one, and"
                    + " the create arm at app/cbl/CBTRN02C.cbl:504 starts a new row from exactly that"
                    + " zero, so there is no null to render");
        }
        if (balance.scale() != Money.SCALE) {
            throw new IllegalArgumentException("transaction category balance property balance "
                    + balance.toPlainString() + " carries scale " + balance.scale() + " but "
                    + describeField(FIELD_BALANCE) + " declares exactly " + Money.SCALE
                    + " decimal places; the value is refused rather than rescaled because rounding an"
                    + " amount is a business decision that belongs to the service layer");
        }
        if (balance.abs().compareTo(BALANCE_MAGNITUDE_LIMIT) >= 0) {
            throw new IllegalArgumentException("transaction category balance property balance "
                    + balance.toPlainString() + " needs more than the " + BALANCE_FIELD.intDigits()
                    + " integer digit positions " + describeField(FIELD_BALANCE)
                    + " provides, so its magnitude must be strictly below "
                    + BALANCE_MAGNITUDE_LIMIT.toPlainString());
        }
        return balance;
    }

    /**
     * Builds the exception for a decoded value whose type or range contradicts its declared storage
     * kind.
     *
     * <p>Assumptions: this reports a descriptor that has changed shape rather than a bad record, so it
     * names what the field was expected to be and what arrived, and stops there. A record cannot reach
     * a projection with the wrong value type: the shared codec decodes strictly by declared kind and
     * raises on its own before returning, so the only way a projection finds an unexpected type is that
     * the registered geometry no longer matches what this class was written against -- a field renamed,
     * or its kind changed.</p>
     *
     * <p>Assumptions: the arriving TYPE is named and the arriving VALUE is not. No field of this record
     * is marked sensitive, since a category balance holds an account identifier, two reference codes
     * and an amount, but the package charter's discipline is applied uniformly so that no field added
     * later is disclosed by a branch that was safe when it was written.</p>
     *
     * @param fieldName the exact copybook name of the field whose projection failed
     * @param expected a short phrase naming the value shape the declared kind implies
     * @param value the value the decoded field map held for that name, which may be {@code null} when
     *     the name is absent altogether
     * @return the exception to throw, carrying the field's name, offset, length and declared kind
     *     together with the type that arrived, and no field content
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under that
     *     name at all
     */
    private static IllegalArgumentException registryDisagreement(
            String fieldName, String expected, Object value) {
        String arrived = value == null
                ? "absent from the decoded field map"
                : value.getClass().getName();
        return new IllegalArgumentException("record " + RECORD_NAME + " " + describeField(fieldName)
                + " decoded as " + arrived + " where " + expected
                + " was required; the registered layout no longer matches the geometry this mapper was"
                + " written against, and app/cpy/CVTRA01Y.cpy is the authority on which of the two is"
                + " wrong");
    }

    /**
     * Renders one field's identity for a diagnostic, without any of its content.
     *
     * <p>Assumptions: the four properties reported here -- name, offset, length and declared kind --
     * are exactly the set the package charter permits a message about a sensitive field to carry, and
     * the same wording is used for every field so that no caller has to know which fields are marked
     * sensitive in order to report safely.</p>
     *
     * @param fieldName the exact copybook name of the field to describe
     * @return a description carrying the field's name, its zero-based offset, its length in bytes and
     *     its declared storage kind
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under that
     *     name
     */
    private static String describeField(String fieldName) {
        CopybookLayout.FieldSpec field = LAYOUT.field(fieldName);
        return "field " + field.name() + " at offset " + field.start() + " with length "
                + field.length() + " and kind " + field.kind();
    }

    /**
     * Copies one field's span verbatim from a source image into an encoded image.
     *
     * <p>Assumptions: the span is addressed through the registered descriptor rather than through
     * literal offsets, so a change to the record's geometry moves this copy with it instead of
     * silently overwriting the wrong bytes.</p>
     *
     * @param source the byte array to read from, of the record's declared length
     * @param target the byte array to write into, of the same length; the span is overwritten in place
     * @param field the {@code CopybookLayout.FieldSpec} descriptor naming the offset and length of the
     *     span to copy
     */
    private static void copySpan(byte[] source, byte[] target, CopybookLayout.FieldSpec field) {
        System.arraycopy(source, field.start(), target, field.start(), field.length());
    }

}
