package com.carddemo.batch.mapper;

import com.carddemo.batch.domain.DisclosureGroup;
import com.carddemo.batch.dto.DisclosureGroupKey;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Converts between the 50-byte disclosure-group record and the interest-rate row interest accrual
 * reads.
 *
 * <p>This is the representation boundary for {@code 01 DIS-GROUP-RECORD}, declared at
 * {@code app/cpy/CVTRA02Y.cpy} lines 4 to 10, whose own line 2 records the record length as
 * {@code RECLN = 50}. One baseline program reaches this record: {@code app/cbl/CBACT04C.cbl} accrues
 * interest, and it obtains the rate it multiplies by through this record's three-part key and
 * through nothing else. Under the migration plan's transformation rule T1 the copybook is the
 * normative source for every width and position below, and the file description that repeats it is
 * not.</p>
 *
 * <h2>The layout, with the arithmetic that checks it</h2>
 *
 * <p>Offsets are zero-based. They are recorded because they are the audit trail: every value this
 * class reads or writes traces back to a byte range of the record.</p>
 *
 * <pre>
 * offset  bytes  copybook field (line)      PICTURE      kind    target
 *      0     10  DIS-ACCT-GROUP-ID (L6)     X(10)        TEXT    acct_group_id  CHAR(10)
 *     10      2  DIS-TRAN-TYPE-CD  (L7)     X(02)        TEXT    tran_type_cd   CHAR(2)
 *     12      4  DIS-TRAN-CAT-CD   (L8)     9(04)        UINT    tran_cat_cd    CHAR(4)
 *     16      6  DIS-INT-RATE      (L9)     S9(04)V99    ZONED   interest_rate  NUMERIC(6,2)
 *     22     28  FILLER            (L10)    X(28)        TEXT    dropped
 * </pre>
 *
 * <p>Ten plus two plus four plus six plus twenty-eight is fifty, which is the length the copybook
 * declares, and the first three fields together span sixteen bytes, which is the width of the group
 * item {@code DIS-GROUP-KEY} at {@code app/cpy/CVTRA02Y.cpy} line 5. Both totals are confirmed from
 * places that know nothing of each other. {@code app/jcl/DISCGRP.jcl} line 40 defines the cluster
 * with {@code KEYS(16 0)}, a sixteen-byte key at offset zero, which balances only if the three
 * components are 10, 2 and 4 bytes wide, and its line 41 declares {@code RECORDSIZE(50 50)}.
 * Independently of both, {@code app/cbl/CBACT04C.cbl} line 50 names the group as
 * {@code RECORD KEY IS FD-DISCGRP-KEY} over the components its lines 78 to 81 restate at the same
 * widths.</p>
 *
 * <p>Assumptions: the copybook and not the file description fixes where the rate ends and the
 * padding begins, and the difference is why T1 matters on this record specifically.
 * {@code app/cbl/CBACT04C.cbl} line 82 collapses everything after the key into a single
 * {@code FD-DISCGRP-DATA PIC X(34)}, and 10 plus 2 plus 4 plus 34 is also 50, so that description is
 * arithmetically consistent and still cannot say which 6 of those 34 bytes are the rate. Only
 * {@code app/cpy/CVTRA02Y.cpy} lines 9 and 10 separate them.</p>
 *
 * <h2>Read-only means read-only against the database, and not against the byte image</h2>
 *
 * <p>Assumptions: {@code reference.disclosure_groups} is a table this module reads and cannot write.
 * The migration plan's section 0.4.1.3 scopes this module's cross-schema write grants to the
 * {@code ledger} and {@code account} schemas alone, so the role it connects as holds {@code SELECT}
 * and nothing else on {@code reference}; the grants are created by
 * {@code data-migration/sql/V0__schemas_and_roles.sql}, the entity is annotated immutable and
 * declares no mutator, and the repository over it deliberately extends the narrow Spring Data
 * {@code Repository} base rather than one that would inherit a save or a delete.</p>
 *
 * <p><b>Read-only in that sense does not make the byte image decode-only, and conflating the two is
 * the trap this paragraph exists to close.</b> Encoding an entity here writes no database row: it
 * returns a byte array, and a byte array is not a write path to anything. The disclosure-group
 * backup generation family needs exactly that array --
 * {@code app/jcl/DEFGDGD.jcl} lines 74 to 76 define {@code AWS.M2.CARDDEMO.DISCGRP.BKUP} as a
 * generation data group at {@code LIMIT(5)} with {@code SCRATCH}, one of the ten generation families
 * the migration reproduces as versioned object-storage prefixes -- and a backup generation is only
 * useful if it is byte-identical to the record it copies. Both directions are therefore provided.
 * {@link #toRecord(DisclosureGroup)} is not dead code and must not be deleted as such; equally it is
 * not a licence to persist, because nothing it returns reaches this schema.</p>
 *
 * <h2>Three lookup outcomes, none of them decided here</h2>
 *
 * <p>Assumptions: a rate of zero is a present value and not an absent one, and a reader of this class
 * needs that distinction because the accrual pipeline turns on it.
 * {@code app/cbl/CBACT04C.cbl} line 214 gates on {@code IF DIS-INT-RATE NOT = 0}, and its lines 215
 * and 216 put both the interest computation and the fee paragraph inside that gate, so a zero rate
 * suppresses accrual entirely and no interest transaction is written for the category at all. A
 * resolved rate therefore has three outcomes to keep apart -- found, defaulted, and found-but-zero --
 * and the baseline's own data contains the third: {@code app/data/ASCII/discgrp.txt} carries rows
 * whose rate span is five zeros followed by the positive-zero overpunch character, an exact zero.
 * This class decodes such a rate to a present
 * {@code BigDecimal} of {@code 0.00} and never to an absent or null value, which is what lets the
 * gate be evaluated at all. <b>The gate itself lives in
 * {@code com.carddemo.batch.dto.InterestRateLookup} and in the interest service; it is noted here
 * and is deliberately not implemented here.</b></p>
 *
 * <p>Assumptions: a missing {@code 'DEFAULT'} group row stops an interest run, which is recorded here
 * as operational context and is likewise not this class's concern.
 * {@code app/cbl/CBACT04C.cbl} lines 455 to 458 display
 * {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} and abend, because the retry read at line 444
 * carries no {@code INVALID KEY} clause and the status test at line 446 accepts only {@code '00'}.
 * There is no third fallback and no rate compiled into the program. Those rows are reference data
 * that {@code reference-service} creates and seeds, so its seed migration is a cross-service
 * precondition for the interest job, and an abend on a missing disclosure group is not a defect in
 * this module or in this class.</p>
 *
 * <h2>Why the rate crosses this boundary exactly</h2>
 *
 * <p>Assumptions: the rate is never rounded here, and the reason is the shape of the formula that
 * consumes it. {@code app/cbl/CBACT04C.cbl} lines 464 and 465 compute
 * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, forming the product of the balance and the rate
 * <b>before</b> dividing, so any imprecision in the rate is multiplied by a balance first and only
 * then scaled down. Rounding a rate at this boundary would therefore not lose a hundredth of a
 * percent, it would lose that hundredth multiplied by every balance the rate is applied to. The
 * multiply-before-divide ordering is itself owned by the interest service and by
 * {@code com.carddemo.common.money.Money} under transformation rule T4; this class performs no
 * arithmetic on the rate at all, and the ordering is cited only to explain why it must hand the value
 * across intact.</p>
 *
 * <p>Assumptions: the rate is also not money, and is carried as {@code java.math.BigDecimal} rather
 * than as {@code com.carddemo.common.money.Money}. It is an operand that money is multiplied by,
 * never an amount summed with another amount; the package charter records it as the one number this
 * package handles that is not money. No binary floating-point type appears anywhere in this class,
 * which the {@code LayeringRulesTest} architecture gate asserts rather than requests.</p>
 *
 * <h2>Round-tripping, and its two documented limits</h2>
 *
 * <p>Trade-offs: {@code toRecord(toEntity(image))} reproduces {@code image} byte for byte, subject to
 * two limits that are stated rather than engineered away. The first is the {@code FILLER} asymmetry
 * the package charter rules on: the span at {@code app/cpy/CVTRA02Y.cpy} line 10 is dropped on decode
 * and rebuilt as blanks on encode, so the round trip is exact when the source padding was blank and
 * substitutes blanks when it was not. That case is real rather than hypothetical --
 * {@code app/data/ASCII/discgrp.txt} fills those 28 bytes with ASCII zeros -- so a caller comparing a
 * re-encoded seed record against its source will see the padding differ and nothing else. The second
 * is negative zero: an exact decimal has no signed zero, so a rate whose source span carried the
 * negative-zero overpunch character, a right brace, re-encodes carrying the positive-zero overpunch
 * character, a left brace. A caller that needs byte exactness across that one span has
 * {@code com.carddemo.common.codec.FixedWidthCodec.encodeRecordPreservingSign} available, which takes
 * the source image and restores the carrier; no overload is offered here, because the baseline's own
 * disclosure-group data holds no negative rate at all and an unused overload is a second encode path
 * to keep in step with this one.</p>
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It converts representations and nothing else. It reads no repository, opens no transaction,
 * performs no arithmetic, and resolves no fallback. It imports no type from another service's domain
 * package -- the table lives in the {@code reference} schema and its owning service's entities are
 * nonetheless unreachable from here, which {@code LayeringRulesTest} asserts. Every geometry decision
 * is read from the shared registry rather than declared locally, and every fixed-width rendering of a
 * key component is taken from {@link DisclosureGroupKey} rather than derived again.</p>
 */
public final class DisclosureGroupRecordMapper {

    /**
     * The logical name the disclosure-group layout is registered under in the shared kernel.
     *
     * <p>Assumptions: the name is the whole of the coupling between this class and the descriptor it
     * reads, because the registry resolves geometry by exact name and offers no other handle. Naming
     * it once rather than at each use makes that coupling a single line a reader can find.</p>
     */
    private static final String RECORD_NAME = "DISGROUP";

    // WHY : Assumptions: the descriptor is resolved from the registry rather than declared here, and
    //       the dependency is mechanical rather than stylistic. FixedWidthCodec recognises a trailing
    //       pad only when the descriptor it was handed is the REGISTERED instance -- its padding test
    //       compares CopybookLayout.layout(name) against the supplied spec by IDENTITY. A locally
    //       built spec with byte-identical contents therefore fails that test, and encode would then
    //       reject this four-entry field map with a missing-required-field failure naming FILLER
    //       instead of blank-filling its 28 bytes. Holding the registered instance in one constant
    //       makes that impossible to get wrong, and it satisfies transformation rule T2, under which
    //       one former COPY statement becomes one import from the single package that owns the
    //       contract.
    /**
     * The registered 50-byte disclosure-group descriptor, transcribed from
     * {@code app/cpy/CVTRA02Y.cpy}.
     */
    private static final CopybookLayout.RecordSpec LAYOUT = CopybookLayout.layout(RECORD_NAME);

    // WHY : Alternatives Considered: modelling DIS-GROUP-KEY, the group item at
    //       app/cpy/CVTRA02Y.cpy:5, as a Java group type whose three subordinates at
    //       app/cpy/CVTRA02Y.cpy:5-8 hang beneath it. A group wrapper was available and is rejected,
    //       because a copybook group name does not identify a shape across the library: the name
    //       TRAN-CAT-KEY is 11 plus 2 plus 4 bytes at app/cpy/CVTRA01Y.cpy:5 and 2 plus 4 bytes at
    //       app/cpy/CVTRA04Y.cpy:5, so one name denotes a 17-byte key in one record and a 6-byte key
    //       in another. A global group model would have to pick one of those two and be wrong for
    //       every reader of the other. Layouts therefore resolve per copybook and a key is expressed
    //       as GEOMETRY -- the spec's own keyLength and keyOffset, 16 and 0 -- and never as a type.
    //       The flattening loses nothing: the group item at line 5 contributes no bytes of its own,
    //       its width being the sum of its children, so the three leaf constants below and that
    //       geometry carry everything it declared.
    /**
     * The copybook name of the ten-character account group identifier at offset zero.
     */
    private static final String FIELD_ACCT_GROUP_ID = "DIS-ACCT-GROUP-ID";

    /**
     * The copybook name of the two-character transaction type code, the key's second component.
     */
    private static final String FIELD_TRAN_TYPE_CD = "DIS-TRAN-TYPE-CD";

    /**
     * The copybook name of the four-digit transaction category code, the key's third component.
     */
    private static final String FIELD_TRAN_CAT_CD = "DIS-TRAN-CAT-CD";

    /**
     * The copybook name of the signed annual percentage rate that follows the key.
     */
    private static final String FIELD_INT_RATE = "DIS-INT-RATE";

    // WHY : Assumptions: this span is SIX bytes and not seven. PIC S9(04)V99 at
    //       app/cpy/CVTRA02Y.cpy:9 declares four digit positions before the implied decimal point and
    //       two after it, which is six digits in total, and the leading S adds no byte of its own
    //       because the sign is an overpunch folded into the high nibble of the low-order digit rather
    //       than a sign character occupying a position. Counting it as four plus two plus one is the
    //       classic fixed-width error, and what makes it dangerous is that the arithmetic still
    //       balances: a seven-byte rate would put the FILLER at offset 23 across 27 bytes, and
    //       10 plus 2 plus 4 plus 7 plus 27 is also 50, so the contiguity-and-sum proof the registry
    //       runs over a whole record would pass and the misalignment would have to be caught
    //       elsewhere. It is caught: the registry DERIVES a zoned field's width from its digit counts
    //       and refuses a declared length that disagrees with them, so the six here is computed from
    //       the picture clause rather than asserted beside it, and this class reads both the scale and
    //       the magnitude ceiling below from that same derivation rather than from a literal.
    /**
     * The registered descriptor of the rate span, the single source of its scale and digit capacity.
     *
     * <p>Assumptions: the required scale and the magnitude ceiling this class enforces are both read
     * from this descriptor rather than written as literals, so a picture clause restated in the
     * registry cannot disagree with a bound hard-coded here.</p>
     */
    private static final CopybookLayout.FieldSpec RATE_FIELD = LAYOUT.field(FIELD_INT_RATE);

    /**
     * The exclusive magnitude ceiling of the rate span, being ten raised to its integer-digit count.
     *
     * <p>Assumptions: four integer digit positions admit magnitudes strictly below ten thousand, so
     * {@code 9999.99} is representable and {@code 10000.00} is not. The bound is derived from
     * {@link #RATE_FIELD} so that it follows the picture clause automatically instead of restating a
     * number the picture already fixes.</p>
     */
    private static final BigDecimal RATE_MAGNITUDE_LIMIT = BigDecimal.TEN.pow(RATE_FIELD.intDigits());

    /**
     * Prevents construction of this stateless converter.
     */
    private DisclosureGroupRecordMapper() {
        // WHY : Alternatives Considered: an injectable instance shaped for constructor injection, as
        //       the service and repository layers of this module are. Rejected because this type holds
        //       no collaborator that could vary -- the descriptor is a registered constant and the
        //       codecs are themselves stateless holders -- so an instance would contribute a bean
        //       whose only distinguishing state is none, and a job would have to be wired to obtain a
        //       conversion that depends on nothing. The shape matches the shared-kernel types this
        //       file consumes and the sibling mappers beside it, each a final class with a private
        //       constructor and static members, so a reader meeting all of them meets one shape. The
        //       package charter separately forbids a shared supertype among these mappers, so no
        //       polymorphism is given up that was available in the first place.
    }

    /**
     * Decodes one 50-byte disclosure-group image into the interest-rate row it represents.
     *
     * <p>Assumptions: exactly one image in, exactly one row out. The record is addressed by a unique
     * sixteen-byte key -- {@code app/jcl/DISCGRP.jcl} line 40 declares {@code KEYS(16 0)} with no
     * alternate index over this cluster anywhere in the baseline -- so unlike the cross-reference
     * record there is no non-unique access path a caller could mistake a single conversion for the
     * answer to.</p>
     *
     * <p>Assumptions: {@code DIS-ACCT-GROUP-ID} is returned at its full declared width and its
     * trailing blanks are not trimmed, for the reason recorded on {@link #toEmbeddedId}. The
     * two-character {@code DIS-TRAN-TYPE-CD} is likewise returned whole: it is a fixed code and part
     * of the key, so both characters are the contract rather than an upper bound.</p>
     *
     * <p>Assumptions: {@code DIS-TRAN-CAT-CD} is read as plain unsigned digits and never through the
     * sign-overpunch path, because {@code app/cpy/CVTRA02Y.cpy} line 8 declares {@code PIC 9(04)}
     * without the leading {@code S} that would make it signed. Signedness is taken from the
     * descriptor's own flag and never inferred from the bytes, and a non-digit anywhere in that span
     * is rejected by the shared codec with the relative offset of the offending byte rather than
     * being coerced or parsed as far as it goes. Parsing the leading digits of a shifted record would
     * return a short number that still reads as a category code, which is the failure that never gets
     * noticed.</p>
     *
     * <p>Assumptions: the rate arrives at scale exactly two and is never stripped to fewer decimal
     * places, so a rate of {@code 15.00} keeps both cent positions rather than becoming {@code 15}.
     * That is not cosmetic: an exact decimal compares by scale as well as by value, so a
     * scale-stripped zero would fail an equality test against the scale-2 zero constant and the
     * non-zero gate at {@code app/cbl/CBACT04C.cbl} line 214 would open when it should stay shut.</p>
     *
     * <p>Assumptions: the trailing {@code FILLER} is not carried onto the row. The shared codec omits
     * a blank registered pad from the decoded map, and where the pad is NOT blank -- which
     * {@code app/data/ASCII/discgrp.txt} demonstrates by filling those 28 bytes with ASCII zeros --
     * the value is present in the map and is simply not read, because the target row declares no
     * counterpart to it. Either way nothing inert reaches the row or any consumer of it.</p>
     *
     * @param record the byte array holding one physical record, which must be exactly 50 bytes -- the
     *     length the registered descriptor declares and the length {@code app/jcl/DISCGRP.jcl} line 41
     *     fixes as {@code RECORDSIZE(50 50)}
     * @return the equivalent row, never {@code null}, carrying the group identifier at all ten
     *     characters, the category code as four digits with its leading zeros intact, and the rate as
     *     an exact decimal at scale two
     * @throws FixedWidthCodec.RecordLengthException if {@code record} is {@code null} or is not
     *     exactly 50 bytes long
     * @throws FixedWidthCodec.FieldCodecException if the category span holds a byte that is not an
     *     ASCII digit, or if a character span does not survive a byte-reversible conversion
     * @throws CopybookLayout.LayoutException if the registered descriptor no longer declares a field
     *     this class names
     * @throws IllegalArgumentException if a decoded value contradicts the type its declared storage
     *     kind implies, or if the rate does not arrive at the scale the descriptor declares, either of
     *     which means the registered descriptor and {@code app/cpy/CVTRA02Y.cpy} have diverged
     */
    public static DisclosureGroup toEntity(byte[] record) {
        // WHY : Alternatives Considered: the null and length checks are left to the shared codec
        //       rather than repeated here. Its own check runs before the first slice and names both
        //       the expected and the received width, so a guard added here would duplicate a stricter
        //       check with a weaker message and would then have to be kept in step with it.
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(record, LAYOUT);

        // WHY : Assumptions: the three components are supplied in the key's PHYSICAL order -- group,
        //       then TYPE, then CATEGORY -- which app/cpy/CVTRA02Y.cpy:6-8 fixes and which the record
        //       key at app/cbl/CBACT04C.cbl:50 addresses. The interest program assigns them in a
        //       DIFFERENT order: app/cbl/CBACT04C.cbl:210 moves the group identifier, :211 moves the
        //       CATEGORY code and :212 moves the TYPE code, so its last two statements are the
        //       opposite way round. That sequence is presentational in the calling paragraph, where
        //       each MOVE names its destination field and the record layout alone decides where that
        //       field sits, and it is NOT evidence of key order. It becomes material here because the
        //       constructor below is positional: both codes are short and either arrangement compiles
        //       and satisfies every signature, so a transposition is invisible in the source and
        //       surfaces only as a key that never matches a row -- which the baseline then reports as
        //       a missing disclosure group rather than as a malformed key.
        DisclosureGroupKey key = new DisclosureGroupKey(
                textField(decoded, FIELD_ACCT_GROUP_ID),
                textField(decoded, FIELD_TRAN_TYPE_CD),
                categoryCodeField(decoded));

        return new DisclosureGroup(toEmbeddedId(key), rateField(decoded));
    }

    /**
     * Encodes one interest-rate row into its 50-byte physical image.
     *
     * <p>Assumptions: the returned array is always exactly 50 bytes, never shorter and never merely
     * long enough for the values supplied. A caller writing a backup generation depends on that: a
     * record right in its first 22 bytes and 28 bytes short still writes, still reads back, and shifts
     * every field of every record after it.</p>
     *
     * <p>Assumptions: the {@code FILLER} span is supplied to the codec by OMISSION and is written back
     * as blanks. Leaving the freshly allocated array's zero bytes in place instead would preserve the
     * record's length but not its content, and a reader of the record distinguishes low values from
     * spaces even though both look empty once decoded into text. Naming the pad explicitly with a run
     * of 28 spaces would produce the same bytes today and would stop doing so the moment the
     * descriptor's pad width changed, because the literal would then be measured against a span it no
     * longer fits.</p>
     *
     * <p>Assumptions: encoding is lossless or it throws. A rate needing more than the descriptor's
     * four integer digit positions, or carrying a scale other than the two it declares, is refused
     * rather than rounded or truncated, because a rounding decision is a business decision that
     * belongs to {@code com.carddemo.common.money.Money} at the service layer where an audit trail can
     * show it, not to an encoder where it would be invisible.</p>
     *
     * <p>Assumptions: all three key components are rendered by {@link DisclosureGroupKey} rather than
     * padded again here. The group identifier is reached through that type's blank-padding entry
     * point, because a value read back out of a fixed-character column arrives with its trailing
     * blanks already stripped, and the category code is rendered by that type's four-digit form. Two
     * places that pad one key are how two representations of one key drift apart, and only one of the
     * two is ever brought back into line.</p>
     *
     * @param entity the row to render, whose composite identifier supplies the three key components
     *     and whose rate supplies the fourth field; must not be {@code null}
     * @return a newly allocated byte array of exactly 50 bytes, carrying the group identifier
     *     left-justified and blank-filled to ten characters, the type code at two characters, the
     *     category code zero-filled to four digits, the rate as six zoned-decimal display characters
     *     with the sign overpunched onto the last of them, and 28 blank pad bytes at offset 22
     * @throws NullPointerException if {@code entity} is {@code null}
     * @throws IllegalArgumentException if the row's identifier or any of its three components is
     *     {@code null}, if the category code is not exactly four ASCII digits, or if the rate is
     *     {@code null}, carries a scale other than the descriptor's, or has a magnitude the
     *     descriptor's integer digit positions cannot hold
     * @throws CopybookLayout.LayoutException if the registered descriptor no longer declares a field
     *     this class names
     * @throws FixedWidthCodec.FieldCodecException if a supplied value does not fit its declared field,
     *     which covers a group identifier or type code longer than its declared width
     */
    public static byte[] toRecord(DisclosureGroup entity) {
        Objects.requireNonNull(entity, "disclosure group entity must not be null");

        // WHY : Assumptions: an encode path exists on a row this module cannot write, and the two
        //       senses of read-only are different. The grant on reference.disclosure_groups is SELECT
        //       only, per the migration plan's section 0.4.1.3, so no row is ever written from here --
        //       but this method writes no row. It returns a byte array, and the disclosure-group
        //       backup generation group defined at app/jcl/DEFGDGD.jcl:74-76 at LIMIT(5) with SCRATCH
        //       needs that array to be byte-identical to the record it copies. Reading the database
        //       restriction as making the byte image decode-only would delete this path as dead code
        //       and take the backup family with it; reading this path as a licence to persist would
        //       reach a grant that refuses it. Neither reading is right, which is why both are named.
        DisclosureGroupKey key = toJobKey(entity.getId());

        // WHY : Assumptions: insertion order is preserved so the map reads in copybook declaration
        //       order, matching the order the codec walks the descriptor in. The codec keys by name
        //       and so does not require it, but a map that reads in a different order from the record
        //       it produces is a map a reader has to reconcile against the descriptor by hand.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_ACCT_GROUP_ID, key.accountGroupId());
        fields.put(FIELD_TRAN_TYPE_CD, key.transactionTypeCode());
        fields.put(FIELD_TRAN_CAT_CD, key.transactionCategoryCodeField());
        fields.put(FIELD_INT_RATE, encodableRate(entity.getInterestRate()));

        return FixedWidthCodec.encodeRecord(fields, LAYOUT);
    }

    /**
     * Converts the job-boundary lookup key into the composite identifier the persistence mapping uses.
     *
     * <p>Trade-offs: two representations of one key legitimately coexist, and this method is the whole
     * of the bridge between them. {@link DisclosureGroupKey} is the job-boundary form: it publishes the
     * ten-character {@code 'DEFAULT'} literal the fallback substitutes, it publishes the fixed-width
     * renderings a 50-byte record needs, and it carries the category code as a number because that is
     * what a job argument and a category balance both supply.
     * {@code DisclosureGroup.DisclosureGroupId} is the persistence form: its three components are
     * fixed-character strings because that is what the owning service's columns are, the category
     * component among them, so a numeric member there would fail the provider's start-up assertion
     * against the physical schema. Collapsing the two into one type would force one of those two
     * contracts to give way -- either the job boundary would carry a category code it has to render
     * from a string at every use, or the persistence mapping would name a type its column is not.
     * What the pair costs is this conversion and the discipline of knowing which form is in hand; what
     * it buys is that neither contract is bent to suit the other.</p>
     *
     * <p>Assumptions: the transaction type and category components are carried across untouched, and
     * the group identifier is carried across at its full ten characters. The fallback the interest
     * program performs is available on the argument -- that type publishes a predicate reporting
     * whether a key names the {@code 'DEFAULT'} group and a derivation replacing that one component
     * while retaining the other two -- and neither is invoked here, for the reason recorded below.</p>
     *
     * @param key the job-boundary lookup key whose three components address one rate; must not be
     *     {@code null}
     * @return the composite identifier carrying the same three components in the same physical order,
     *     never {@code null}
     * @throws IllegalArgumentException if {@code key} is {@code null}
     */
    public static DisclosureGroup.DisclosureGroupId toEmbeddedId(DisclosureGroupKey key) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "disclosure group lookup key is required and was null");
        }

        // WHY : Assumptions: the group identifier keeps all TEN characters and is not trimmed on the
        //       way across. app/cbl/CBACT04C.cbl:437 moves the seven-character literal 'DEFAULT' into
        //       FD-DIS-ACCT-GROUP-ID, declared PIC X(10) at app/cpy/CVTRA02Y.cpy:6, and an
        //       alphanumeric move left-justifies and space-fills, so the value the retry searches for
        //       is DEFAULT followed by three blanks. app/data/ASCII/discgrp.txt holds it in exactly
        //       that form. The lookup only matches while that padding survives, so trimming here would
        //       break the fallback path in the interest service -- and it would break it quietly,
        //       because a shortened key still renders, still compares and simply finds nothing.
        return new DisclosureGroup.DisclosureGroupId(
                key.accountGroupId(), key.transactionTypeCode(), key.transactionCategoryCodeField());
    }

    /**
     * Converts a persisted composite identifier back into the job-boundary lookup key.
     *
     * <p>Assumptions: the group identifier may arrive SHORT of its declared width and is padded back
     * through the one entry point {@link DisclosureGroupKey} sanctions for that. A fixed-character
     * column treats trailing blanks as insignificant in both directions, blank-filling a short value on
     * the way in and stripping the blanks again on the way out, so a row whose stored key occupies all
     * ten octets yields the seven-character {@code 'DEFAULT'} when read back. The canonical
     * constructor of the key type refuses that width deliberately, which is what keeps a key to one
     * canonical form; the padding entry point is where the width is restored, and it passes a value
     * already at ten characters through untouched.</p>
     *
     * <p>Assumptions: the category component is required to be exactly four ASCII digits and a
     * non-digit is refused rather than coerced, for the reason recorded on
     * {@link #requiredCategoryCode(String)}.</p>
     *
     * @param id the composite identifier read from the persistence mapping, whose three components
     *     supply the key; must not be {@code null} and must carry no {@code null} component
     * @return the equivalent job-boundary key, whose group component is exactly ten characters and
     *     whose category component is the numeric value of the persisted digits, never {@code null}
     * @throws IllegalArgumentException if {@code id} is {@code null}, if any of its three components
     *     is {@code null}, if the group identifier exceeds its declared ten-character width, if the
     *     type code is not exactly two characters, or if the category code is not exactly four ASCII
     *     digits
     */
    public static DisclosureGroupKey toJobKey(DisclosureGroup.DisclosureGroupId id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "disclosure group composite identifier is required and was null");
        }

        // WHY : Alternatives Considered: resolving the DEFAULT-group fallback here, so that a caller
        //       holding a key that matched no row got a rate back regardless. Putting the retry in this
        //       class was the obvious convenience and is rejected, because the fallback is an
        //       OBSERVABLE step and not an implementation detail. app/cbl/CBACT04C.cbl:436-439 tests
        //       the file status for '23', substitutes 'DEFAULT' into the group component alone at :437
        //       and performs a SECOND read at :438, and that two-step control flow shows up in the
        //       golden masters the migration is compared against. The plan's section 0.5.1.7 therefore
        //       places it in InterestCalculationService, where a caller can see which of the two reads
        //       produced the rate it holds. A mapper that silently resolved the fallback would erase
        //       that step: the same rate would come back with no record of having been defaulted, and
        //       the distinction the DEFAULT predicate on the key type exists to report would be gone.
        //       Nothing here substitutes any component of the key it was handed.
        return DisclosureGroupKey.ofBlankPaddedAccountGroupId(
                requiredComponent(id.getAcctGroupId(), "acctGroupId", FIELD_ACCT_GROUP_ID),
                requiredComponent(id.getTranTypeCd(), "tranTypeCd", FIELD_TRAN_TYPE_CD),
                requiredCategoryCode(id.getTranCatCd()));
    }

    /**
     * Reads one decoded character field at its declared width, with no trailing blank removed.
     *
     * <p>Assumptions: both character fields this method serves are key components, so their declared
     * widths are part of the contract rather than an upper bound and a trimmed value would compare
     * unequal to the key it is looked up by. This is the opposite of the treatment a descriptive
     * character field receives elsewhere in this package, where trailing blanks are padding.</p>
     *
     * @param decoded the map of copybook field name to decoded value the shared codec produced for
     *     this record
     * @param fieldName the exact copybook name of the character field to project
     * @return the field's characters including every trailing blank the source carried
     * @throws IllegalArgumentException if the decoded value is absent or is not character data, either
     *     of which means the registered descriptor no longer agrees with this class
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under that
     *     name
     */
    private static String textField(Map<String, Object> decoded, String fieldName) {
        Object value = decoded.get(fieldName);
        if (value instanceof String text) {
            return text;
        }
        throw registryDisagreement(fieldName, "character text", value);
    }

    /**
     * Reads the decoded category code as the number the job-boundary key carries.
     *
     * <p>Assumptions: the shared codec returns an unsigned display field as an integral value, and the
     * range check below is a guard against descriptor drift rather than against data. Four digit
     * positions cannot express a value outside the range the key type admits, so a record cannot reach
     * this method with one; a descriptor whose category span had been widened could, and narrowing the
     * result silently would then produce a key addressing a different row.</p>
     *
     * @param decoded the map of copybook field name to decoded value the shared codec produced for
     *     this record
     * @return the category code as a number between the minimum and maximum the four-digit picture
     *     admits, with the leading zeros of the source digits absorbed into its numeric value
     * @throws IllegalArgumentException if the decoded value is absent, is not integral, or falls
     *     outside the range four unsigned digits admit, any of which means the registered descriptor
     *     no longer agrees with this class
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     category field's name
     */
    private static int categoryCodeField(Map<String, Object> decoded) {
        Object value = decoded.get(FIELD_TRAN_CAT_CD);
        if (!(value instanceof Long integral)) {
            throw registryDisagreement(FIELD_TRAN_CAT_CD, "an unsigned integral value", value);
        }
        if (integral < DisclosureGroupKey.MIN_TRANSACTION_CATEGORY_CODE
                || integral > DisclosureGroupKey.MAX_TRANSACTION_CATEGORY_CODE) {
            throw new IllegalArgumentException("record " + RECORD_NAME + " "
                    + describeField(FIELD_TRAN_CAT_CD) + " decoded as " + integral
                    + ", which is outside " + DisclosureGroupKey.MIN_TRANSACTION_CATEGORY_CODE
                    + " through " + DisclosureGroupKey.MAX_TRANSACTION_CATEGORY_CODE
                    + "; the registered descriptor and app/cpy/CVTRA02Y.cpy have diverged");
        }
        return (int) integral.longValue();
    }

    /**
     * Reads the decoded rate as an exact decimal at the scale the descriptor declares.
     *
     * <p>Assumptions: the scale is verified rather than imposed. The descriptor declares two decimal
     * places for this span and the shared codec returns a value at exactly that scale, so a value
     * arriving at any other scale means the registered descriptor and {@code app/cpy/CVTRA02Y.cpy}
     * have diverged. Reporting that is preferred to rescaling, because rescaling here would compensate
     * locally for a defect in a descriptor every other consumer of this record also reads, hiding it
     * from all of them.</p>
     *
     * <p>Assumptions: a zero is returned as a present value at scale two and is never normalised to an
     * absent one, which the non-zero gate recorded on this class depends upon.</p>
     *
     * @param decoded the map of copybook field name to decoded value the shared codec produced for
     *     this record
     * @return the signed annual percentage rate at exactly the descriptor's declared scale, which may
     *     be zero and may be negative because the source picture carries a leading sign
     * @throws IllegalArgumentException if the decoded value is absent, is not an exact decimal, or does
     *     not carry exactly the number of decimal places the descriptor declares
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     rate field's name
     */
    private static BigDecimal rateField(Map<String, Object> decoded) {
        Object value = decoded.get(FIELD_INT_RATE);
        if (!(value instanceof BigDecimal rate)) {
            throw registryDisagreement(FIELD_INT_RATE, "an exact decimal rate", value);
        }
        if (rate.scale() != RATE_FIELD.decDigits()) {
            throw new IllegalArgumentException("record " + RECORD_NAME + " "
                    + describeField(FIELD_INT_RATE) + " decoded at scale " + rate.scale()
                    + " but the descriptor declares " + RATE_FIELD.decDigits()
                    + " decimal places; the registered descriptor and app/cpy/CVTRA02Y.cpy have"
                    + " diverged");
        }
        return rate;
    }

    /**
     * Requires that a key component supplying a mapped field has a value, and returns it unchanged.
     *
     * <p>Assumptions: there is no permitted-null case to render, which is why this rejects rather than
     * substituting blanks. All three key columns are declared not-null by the owning service, so a
     * null component is a key no owner should hold. Encoding one anyway would produce a record whose
     * key span is blank, which writes successfully and then addresses nothing.</p>
     *
     * @param value the component read from the composite identifier, which may be {@code null}
     * @param propertyName the name of the identifier property the value was read from, used to point a
     *     caller at the object they supplied rather than at the copybook
     * @param fieldName the exact copybook name of the field that component supplies
     * @return the same component unchanged, so the check can be applied inline where it is used
     * @throws IllegalArgumentException if {@code value} is {@code null}
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under that
     *     name
     */
    private static String requiredComponent(String value, String propertyName, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("disclosure group identifier property " + propertyName
                    + ", which supplies " + describeField(fieldName)
                    + ", is null; every key column this record maps is declared not-null, so there is"
                    + " no permitted-null case to render as blanks");
        }
        return value;
    }

    /**
     * Converts the persisted four-character category code into the number the key type carries.
     *
     * <p>Assumptions: the value is required at exactly its declared width and every character must be
     * an ASCII digit, and a non-digit is refused rather than coerced. The picture at
     * {@code app/cpy/CVTRA02Y.cpy} line 8 is numeric-display, which right-justifies and zero-fills, so
     * the record holds a value of five as four digits rather than as one digit and three blanks.
     * Accepting a short or blank-filled value and parsing what it could would silently renumber the
     * key: a persisted value of one digit would come back out rendered with three leading zeros, which
     * is not a shorter key but a DIFFERENT one, and it would address a row the caller never asked
     * for.</p>
     *
     * <p>Assumptions: the parse cannot overflow, because a value confined to exactly four ASCII digits
     * is at most 9999, and it cannot carry a sign, because a sign character is not a digit and is
     * rejected above.</p>
     *
     * @param tranCatCd the category code exactly as the persistence mapping holds it, which must be
     *     exactly four ASCII digits with its leading zeros intact
     * @return the numeric value of those four digits, between the minimum and maximum the picture
     *     admits
     * @throws IllegalArgumentException if {@code tranCatCd} is {@code null}, is not exactly the
     *     declared width, or holds any character that is not an ASCII digit
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     category field's name
     */
    private static int requiredCategoryCode(String tranCatCd) {
        String digits = requiredComponent(tranCatCd, "tranCatCd", FIELD_TRAN_CAT_CD);
        if (digits.length() != DisclosureGroupKey.TRANSACTION_CATEGORY_CODE_LENGTH) {
            throw new IllegalArgumentException("disclosure group identifier property tranCatCd '"
                    + digits + "' is " + digits.length() + " characters, not exactly "
                    + DisclosureGroupKey.TRANSACTION_CATEGORY_CODE_LENGTH
                    + "; the leading zeros of a numeric-display code are part of the key, so a short"
                    + " value is a different key rather than the same one padded");
        }
        for (int index = 0; index < digits.length(); index++) {
            char candidate = digits.charAt(index);
            if (candidate < '0' || candidate > '9') {
                throw new IllegalArgumentException("disclosure group identifier property tranCatCd '"
                        + digits + "' holds a non-digit at relative offset " + index
                        + ", which " + describeField(FIELD_TRAN_CAT_CD)
                        + " cannot carry; a numeric-display span holds ASCII digits only");
            }
        }
        return Integer.parseInt(digits);
    }

    /**
     * Verifies that a rate can be written to its span without losing precision, and returns it
     * unchanged.
     *
     * <p>Assumptions: the two bounds are read from the descriptor rather than written as literals, so
     * the check follows the picture clause at {@code app/cpy/CVTRA02Y.cpy} line 9 automatically. The
     * scale must equal the declared decimal-digit count exactly, and the magnitude must be strictly
     * below ten raised to the declared integer-digit count.</p>
     *
     * <p>Assumptions: a rate outside either bound is refused rather than rounded or truncated, and the
     * refusal is the point. {@code app/cbl/CBACT04C.cbl} lines 464 and 465 multiply a category balance
     * by this rate BEFORE dividing the product by 1200, so a hundredth of a percent quietly rounded
     * away here would be multiplied by every balance the rate is applied to before anything scaled it
     * back down. Where a business rule genuinely decides a value should be reduced,
     * {@code com.carddemo.common.money.Money} performs that reduction at the service layer under its
     * own documented mode; an encoder that did it silently would hide a decision the audit trail needs
     * to show.</p>
     *
     * <p>Assumptions: a negative rate is accepted, because the picture carries a leading sign and the
     * span has a carrier for it. Narrowing the domain to non-negative values would refuse a record the
     * baseline accepts, and it would do so in this module over data another service owns.</p>
     *
     * @param rate the annual percentage rate read from the row, which may be zero or negative
     * @return the same rate unchanged, so the check can be applied inline where the value is used
     * @throws IllegalArgumentException if {@code rate} is {@code null}, carries a scale other than the
     *     one the descriptor declares, or has a magnitude the declared integer digit positions cannot
     *     hold
     * @throws CopybookLayout.LayoutException if the registered descriptor declares no field under the
     *     rate field's name
     */
    private static BigDecimal encodableRate(BigDecimal rate) {
        if (rate == null) {
            throw new IllegalArgumentException("disclosure group property interestRate, which supplies "
                    + describeField(FIELD_INT_RATE)
                    + ", is null; the column is declared not-null and a zero rate is a meaningful"
                    + " value rather than an absent one, so there is no null to render");
        }
        if (rate.scale() != RATE_FIELD.decDigits()) {
            throw new IllegalArgumentException("disclosure group property interestRate "
                    + rate.toPlainString() + " carries scale " + rate.scale() + " but "
                    + describeField(FIELD_INT_RATE) + " declares exactly "
                    + RATE_FIELD.decDigits()
                    + " decimal places; the value is refused rather than rescaled because rounding a"
                    + " rate is a business decision that belongs to the service layer");
        }
        if (rate.abs().compareTo(RATE_MAGNITUDE_LIMIT) >= 0) {
            throw new IllegalArgumentException("disclosure group property interestRate "
                    + rate.toPlainString() + " needs more than the " + RATE_FIELD.intDigits()
                    + " integer digit positions " + describeField(FIELD_INT_RATE)
                    + " provides, so its magnitude must be strictly below "
                    + RATE_MAGNITUDE_LIMIT.toPlainString());
        }
        return rate;
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
     * is marked sensitive, since a disclosure group holds only reference codes and a rate, but the
     * package charter's discipline is applied uniformly so that no field added later is disclosed by a
     * branch that was safe when it was written.</p>
     *
     * <p>Assumptions: where the descriptor and {@code app/cpy/CVTRA02Y.cpy} disagree, the copybook is
     * right and the descriptor is the defect, under transformation rule T1. This message therefore
     * reports the disagreement instead of compensating for it locally, because a local compensation
     * would leave the shared descriptor wrong for every other consumer of this record.</p>
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
                + " written against, and app/cpy/CVTRA02Y.cpy is the authority on which of the two is"
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
}
