package com.carddemo.batch.mapper;

import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.time.TimestampFormatter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts the 350-byte daily-transaction record of {@code app/cpy/CVTRA06Y.cpy} to and from the
 * feed entity, and projects that entity onto the posted transaction the way posting does.
 *
 * <h2>Purpose, and the two boundaries this one file owns</h2>
 *
 * <p>Two boundaries live here, and the pairing is deliberate rather than incidental:</p>
 *
 * <ul> <li><b>The record representation.</b> The 350-byte input image declared by
 * {@code app/cpy/CVTRA06Y.cpy} lines 5 to 18, decoded into {@link DailyTransaction} and encoded back
 * out of it.</li> <li><b>The posting projection.</b> The field map the
 * {@code 2000-POST-TRANSACTION} paragraph of {@code app/cbl/CBTRN02C.cbl} applies at its lines 424
 * to 444, carrying a feed record onto a {@link Transaction}.</li> </ul>
 *
 * <p>Alternatives Considered: an earlier shape of this package split those two into separate files,
 * and the split was rejected on a concrete dependency rather than on taste.
 * {@code TransactionRejectRecordMapper} needs this same 350-byte layout descriptor, because
 * {@code app/cbl/CBTRN02C.cbl:447} moves the whole {@code DALYTRAN-RECORD} group into the reject
 * stream's 350-byte prefix -- so the descriptor has two consumers whichever way the files are cut.
 * Splitting it yields two owners of one layout, and two owners of one layout drift silently: each
 * stays internally consistent while they disagree with each other, and a reject image written from
 * the stale one is a file that still opens at its declared length and still reads. One owner makes
 * that drift impossible rather than merely unlikely. The cost accepted is a class that answers two
 * questions instead of one, which is why this paragraph exists -- a reader meeting a mapper that
 * does two jobs would otherwise separate them again for tidiness and reintroduce the second
 * descriptor.</p>
 *
 * <p>Assumptions: <b>this file is the package's single owner of the {@code DALYTRAN} descriptor
 * reference.</b> Nothing else under {@code com.carddemo.batch.mapper} resolves that registry entry
 * and nothing declares its geometry.
 * {@code TransactionRejectRecordMapper} reaches the same 350-byte prefix either through this class
 * or through the registry's derived {@code REJECT} entry, which
 * {@code com.carddemo.common.codec.CopybookLayout} builds by extending the {@code DALYTRAN} entry
 * rather than by restating fourteen field descriptors -- never by re-declaring the layout.</p>
 *
 * <h2>Assumptions: identical geometry, two registry entries, and no aliasing</h2>
 *
 * <p>This record's geometry is <b>byte for byte the same</b> as the posted transaction record's.
 * {@code app/cpy/CVTRA06Y.cpy} and {@code app/cpy/CVTRA05Y.cpy} declare the same fourteen widths in
 * the same order at the same copybook line numbers, and differ only in the prefix on every field
 * name. The registry nevertheless holds them as two entries, and this class resolves the
 * {@code DALYTRAN} one and never substitutes the {@code TRAN} one on the ground that the geometry
 * matches.</p>
 *
 * <p>Assumptions: the reason is that the field <b>names</b> are the contract, not just the offsets.
 * {@code com.carddemo.common.codec.FixedWidthCodec} returns a decoded record as a map keyed by
 * copybook field name and accepts an encode as a map keyed the same way, so the names are the
 * addressing scheme every lookup in this class goes through. A single shared specification would
 * make every one of those lookups ambiguous across the two record types -- a decode of a daily image
 * under the posted record's specification produces a map keyed {@code TRAN-*}, and this class asking
 * it for {@code DALYTRAN-ID} would find nothing at all. The identical geometry is a coincidence of
 * how the baseline was designed, not an invitation to alias, and the two entries also disagree about
 * which timestamp a parity comparator blanks.</p>
 *
 * <h2>Assumptions: this boundary is decode-oriented, and the encode path is still not dead</h2>
 *
 * <p>Three independent facts make the decode direction the one that carries traffic. The entity is
 * annotated {@code @Immutable}, so nothing in this module updates a loaded row. No baseline program
 * writes this dataset: {@code app/cbl/CBTRN02C.cbl:29-32} and {@code app/cbl/CBTRN01C.cbl:29-32}
 * both select it for input only, and {@code app/jcl/POSTTRAN.jcl:30-31} mounts it {@code DISP=SHR}
 * with no {@code NEW} or {@code CATLG} disposition anywhere. And the rows themselves arrive from the
 * Python extract-transform-load package, which produces them from
 * {@code app/data/ASCII/dailytran.txt} rather than from any Java writer.</p>
 *
 * <p>Assumptions: the encode path therefore exists for two named purposes and is <b>not</b> a
 * persistence path, so it must not be read as one and must not be deleted as unreachable. The first
 * purpose is the daily-transaction backup generation family: {@code app/jcl/DEFGDGB.jcl:31-33}
 * defines {@code AWS.M2.CARDDEMO.TRANSACT.DALY} as a generation data group at
 * {@code LIMIT(5) SCRATCH}, whose migrated equivalent stages a generation of this dataset, and a
 * staged generation has to be written as bytes. The second is round-trip verification, which is the
 * property that makes a decode trustworthy at all: an image that decodes and re-encodes to the same
 * bytes has demonstrated that no field was read at a wrong offset, and no assertion about individual
 * fields can demonstrate that on its own.</p>
 *
 * <h2>Assumptions: reading is sequential, and the processing stamp is blank</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:29-32} selects the file {@code ORGANIZATION IS SEQUENTIAL} with
 * {@code ACCESS MODE IS SEQUENTIAL}, and {@code app/cbl/CBTRN01C.cbl:29-32} selects it identically,
 * so both readers walk it forward with no keyed retrieval at all. That walk is not implemented here
 * -- iteration belongs to the repository and the job -- but it is recorded because it explains why
 * the repository orders by transaction identifier.</p>
 *
 * <p>Assumptions: <b>{@code DALYTRAN-PROC-TS} is blank on every unposted row, which is every row the
 * feed contains.</b> The 26 bytes at zero-based offset 304 are blank on 300 of the 300 records of
 * {@code app/data/ASCII/dailytran.txt}, while the originating stamp at offset 278 is populated on
 * all 300 -- so the blankness is specific to this field rather than a gap in the extract. Two
 * consequences follow, and both would otherwise look arbitrary. The sequential walk is ordered by
 * transaction identifier and never by processing stamp, because ordering by a column that is blank
 * on precisely the rows the walk exists to find would place them arbitrarily. And the decode path
 * treats a 26-blank span as the <b>normal</b> case rather than as an exceptional one, yielding an
 * absent value instead of raising.</p>
 *
 * <h2>Assumptions: two producers stamp their timestamps differently, and the patterns never merge</h2>
 *
 * <p>Posting and interest accrual look alike here and are structurally different, which is worth
 * stating on the class because the difference is invisible from either paragraph alone. Posting
 * copies the originating stamp across from the feed at {@code app/cbl/CBTRN02C.cbl:436} and mints a
 * value into the processing stamp only, at lines 437 and 438. Interest accrual performs its
 * timestamp routine once at {@code app/cbl/CBACT04C.cbl:496} and moves that one value into
 * <b>both</b> stamps, at lines 497 and 498.</p>
 *
 * <p>Assumptions: <b>those two patterns are never factored into one shared routine that stamps the
 * timestamps.</b> A helper covering both would have to take a flag deciding whether the originating
 * stamp is copied or minted, and that flag is the whole of the difference between a deterministic
 * originating stamp that a parity comparison must compare and a clock read that it must blank.
 * Collapsing them puts that decision at every call site instead of in the two paragraphs that
 * actually make it, and a call site that passed the wrong flag would emit a record which still reads
 * as 350 bytes and no longer says what it said.</p>
 *
 * <h2>Assumptions: the reject stream carries the source bytes, not a re-encode</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl:447} performs a group move of the whole {@code DALYTRAN-RECORD}
 * into {@code REJECT-TRAN-DATA}, the {@code PIC X(350)} prefix declared at
 * {@code app/cbl/CBTRN02C.cbl:176-178}. A group move copies bytes, so the prefix retains the
 * record's own trailing {@code FILLER} span and its own processing-timestamp span exactly as they
 * were read, whatever they held. <b>The reject prefix is therefore the record as read, and never a
 * re-encoded projection of the entity.</b></p>
 *
 * <p>Assumptions: that is why {@link #decode(byte[])} hands back the verbatim source image alongside
 * the entity, and why {@code TransactionRejectRecordMapper} is expected to carry those bytes forward
 * rather than to call an encode. A re-encode from the entity would differ in two places at once: it
 * blank-fills the {@code FILLER} span, which loses a pad that was not blank, and it renders each
 * timestamp in the target form, which rewrites a span the baseline had written in its own form.
 * Either difference fails a byte-deterministic parity comparison on the reject stream while every
 * decoded field is right. Assembling the reject record is not done here -- the 80-byte trailer of
 * {@code app/cbl/CBTRN02C.cbl:180-182} belongs to that other mapper -- so this class supplies the
 * bytes and stops.</p>
 *
 * <h2>Assumptions: the amount is exact, and it is the value posting acts on</h2>
 *
 * <p>{@code DALYTRAN-AMT} is not an incidental field. {@code app/cbl/CBTRN02C.cbl:403-405} computes
 * the over-limit projection from it, and posting then adds the same value to the account and
 * category balances. It is carried as a {@code BigDecimal} at scale 2 and never as a binary floating
 * point type, under the migration plan's transformation rule T3, and the prohibition is asserted by
 * the {@code LayeringRulesTest} architecture rules rather than requested in a review. A balance
 * wrong in its cents still looks entirely plausible, which is what makes the prohibition worth
 * asserting mechanically.</p>
 *
 * <p>Assumptions: the originating stamp's leading ten characters are relied on as a date.
 * {@code app/cbl/CBTRN02C.cbl:414} compares the account expiration date against
 * {@code DALYTRAN-ORIG-TS (1:10)} -- a reference modification of the first ten characters only. That
 * comparison belongs to the posting validation service and is not performed here; it is recorded
 * because it is the reason the ten-character date prefix must stay recoverable from the decoded
 * value, and {@code com.carddemo.common.time.TimestampFormatter} exposes both a date prefix and a
 * local date for exactly that purpose so that no caller re-slices a rendered value.</p>
 *
 * <p>Assumptions: what this class does <b>not</b> do is as much a part of its boundary as what it
 * does. There is no repository access, no transaction demarcation, no validation, no arithmetic and
 * no clock read anywhere in it. Validation is the posting service's, at
 * {@code app/cbl/CBTRN02C.cbl:370-422}, and rejecting a record here would replace a counted reject
 * carrying one of the four reason codes with an exception carrying none.</p>
 */
public final class DailyTransactionMapper {

    /**
     * Registry name of the layout this class resolves, and the only one it ever resolves.
     *
     * <p>Assumptions: it is held as a constant so the same String names the entry in the resolution
     * below and in every geometry failure, which is what lets a reader of a failure look the entry
     * up without guessing which of the registry's three 350-byte layouts was in force.</p>
     */
    private static final String REGISTRY_NAME = "DALYTRAN";

    /** Copybook name of the identifier field, declared at {@code app/cpy/CVTRA06Y.cpy:5}. */
    private static final String DALYTRAN_ID = "DALYTRAN-ID";

    /** Copybook name of the transaction type code, declared at {@code app/cpy/CVTRA06Y.cpy:6}. */
    private static final String DALYTRAN_TYPE_CD = "DALYTRAN-TYPE-CD";

    /** Copybook name of the category code, declared at {@code app/cpy/CVTRA06Y.cpy:7}. */
    private static final String DALYTRAN_CAT_CD = "DALYTRAN-CAT-CD";

    /** Copybook name of the originating channel, declared at {@code app/cpy/CVTRA06Y.cpy:8}. */
    private static final String DALYTRAN_SOURCE = "DALYTRAN-SOURCE";

    /** Copybook name of the free-text description, declared at {@code app/cpy/CVTRA06Y.cpy:9}. */
    private static final String DALYTRAN_DESC = "DALYTRAN-DESC";

    /** Copybook name of the signed amount, declared at {@code app/cpy/CVTRA06Y.cpy:10}. */
    private static final String DALYTRAN_AMT = "DALYTRAN-AMT";

    /** Copybook name of the merchant identifier, declared at {@code app/cpy/CVTRA06Y.cpy:11}. */
    private static final String DALYTRAN_MERCHANT_ID = "DALYTRAN-MERCHANT-ID";

    /** Copybook name of the merchant name, declared at {@code app/cpy/CVTRA06Y.cpy:12}. */
    private static final String DALYTRAN_MERCHANT_NAME = "DALYTRAN-MERCHANT-NAME";

    /** Copybook name of the merchant city, declared at {@code app/cpy/CVTRA06Y.cpy:13}. */
    private static final String DALYTRAN_MERCHANT_CITY = "DALYTRAN-MERCHANT-CITY";

    /** Copybook name of the merchant postal code, declared at {@code app/cpy/CVTRA06Y.cpy:14}. */
    private static final String DALYTRAN_MERCHANT_ZIP = "DALYTRAN-MERCHANT-ZIP";

    /** Copybook name of the card number, declared at {@code app/cpy/CVTRA06Y.cpy:15}. */
    private static final String DALYTRAN_CARD_NUM = "DALYTRAN-CARD-NUM";

    /** Copybook name of the originating stamp, declared at {@code app/cpy/CVTRA06Y.cpy:16}. */
    private static final String DALYTRAN_ORIG_TS = "DALYTRAN-ORIG-TS";

    /** Copybook name of the processing stamp, declared at {@code app/cpy/CVTRA06Y.cpy:17}. */
    private static final String DALYTRAN_PROC_TS = "DALYTRAN-PROC-TS";

    /**
     * Copybook name of the trailing pad, declared at {@code app/cpy/CVTRA06Y.cpy:18}.
     *
     * <p>Assumptions: it is named here only so the byte-preserving encode can address its span. This
     * field carries neither a {@code REDEFINES} clause nor a {@code VALUE} clause, so it is padding
     * to the declared record length under the package charter's test, and it therefore reaches no
     * member of the entity in either direction.</p>
     */
    private static final String FILLER = "FILLER";

    /**
     * Declared record length, stated as {@code RECLN = 350} at {@code app/cpy/CVTRA06Y.cpy:2}.
     *
     * <p>Assumptions: this constant exists to be checked against the registry rather than used as a
     * length, and it is corroborated twice over independently of the copybook. Both readers declare
     * their file description as a sixteen-byte identifier followed by a 334-byte remainder --
     * {@code app/cbl/CBTRN02C.cbl:67-69} and {@code app/cbl/CBTRN01C.cbl:67-69} -- and sixteen plus
     * 334 is 350. The reject stream gives a third reading: {@code app/cbl/CBTRN02C.cbl:83-84}
     * declares a 350-byte data area plus an 80-byte trailer, and
     * {@code app/jcl/POSTTRAN.jcl:34-36} allocates that stream at {@code LRECL=430}.</p>
     */
    private static final int RECORD_LENGTH = 350;

    /** Zero-based offset the signed amount is asserted to occupy, from the width summation. */
    private static final int EXPECTED_AMOUNT_OFFSET = 132;

    /** Integer digit positions the amount declares, from {@code PIC S9(09)V99}. */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Fractional digit positions the amount declares, from {@code PIC S9(09)V99}. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /**
     * Zero-based offset the card number is asserted to occupy.
     *
     * <p>Assumptions: the posted transaction record shares this record's geometry field for field, so
     * the two artifacts that corroborate the offset there corroborate it here -- the one-based DFSORT
     * position 263 at {@code app/jcl/TRANREPT.jcl:41} and the alternate index built over the posted
     * master. The corroboration transfers because the geometry is shared; the field <b>name</b> does
     * not transfer, which is the distinction the registry keeps.</p>
     */
    private static final int EXPECTED_CARD_NUM_OFFSET = 262;

    /** Byte width the card number is asserted to occupy, from {@code PIC X(16)}. */
    private static final int EXPECTED_CARD_NUM_LENGTH = 16;

    /**
     * Zero-based offset the originating stamp is asserted to occupy.
     *
     * <p>Assumptions: this offset is load-bearing beyond the field itself, because
     * {@code app/cbl/CBTRN02C.cbl:414} reads its first ten characters as the transaction date. A
     * drift here would move the span the expiration comparison stands on, and reason code 103 would
     * then be decided from ten characters of some other field.</p>
     */
    private static final int EXPECTED_ORIG_TS_OFFSET = 278;

    /** Zero-based offset the processing stamp is asserted to occupy, from the width summation. */
    private static final int EXPECTED_PROC_TS_OFFSET = 304;

    /** The single character COBOL pads a {@code PIC X(n)} field with, and the only one trimmed here. */
    private static final char BLANK = ' ';

    /**
     * The registered specification for this record, resolved once at class initialisation.
     *
     * <p>Assumptions: the instance is the one {@code CopybookLayout.layout(String)} returns, and that
     * identity matters rather than merely the values it carries.
     * {@code com.carddemo.common.codec.FixedWidthCodec} recognises a trailing pad descriptor only
     * when the specification it was handed <b>is</b> the registered one, so a hand-built copy of the
     * same fourteen fields would silently stop dropping {@code FILLER} on decode and stop rebuilding
     * it on encode. Resolving here also means a registry that no longer holds the entry fails at
     * class initialisation rather than at the first record of a nightly run.</p>
     */
    private static final CopybookLayout.RecordSpec LAYOUT = CopybookLayout.layout(REGISTRY_NAME);

    /**
     * The 26-character timestamp form the baseline's own writer produces.
     *
     * <p>Assumptions: the pattern is transcribed from the producer's declaration rather than guessed.
     * {@code app/cbl/CBTRN02C.cbl:159} declares {@code DB2-FORMAT-TS PIC X(26)} and the overlay at
     * {@code app/cbl/CBTRN02C.cbl:160-174} decomposes it: four year characters, a hyphen, two month,
     * a hyphen, two day, <b>a third hyphen where the target form has a space</b>, two hour, a dot
     * where the target form has a colon, two minute, a dot where the target form has a colon, two
     * second, a dot, then {@code DB2-MIL PIC 9(002)} -- only two fractional digits -- followed by
     * {@code DB2-REST PIC X(04)}, which {@code app/cbl/CBTRN02C.cbl:701} fills with the literal
     * {@code 0000}. Reading those last four characters as fractional digits is what makes the whole
     * six-character tail parse as microseconds, so the pattern is {@code SSSSSS} rather than two
     * digits plus a literal.</p>
     *
     * <p>Assumptions: declaring a pattern here does <b>not</b> re-derive a format the shared kernel
     * owns, and the distinction is the one the migration plan's transformation rule T2 turns on.
     * {@code com.carddemo.common.time.TimestampFormatter} owns the <b>target</b> emission contract
     * and neither writes nor reads this one. This is a producer's own declared layout, and reading a
     * producer's layout is exactly what this package exists to do -- the target form is still parsed
     * and rendered by calling that formatter, never by a second pattern declared here.</p>
     *
     * <p>Trade-offs: the resolver style is strict, matching the shared formatter, so an impossible
     * date such as a thirty-first of February is refused rather than shifted into March. A lenient
     * resolver would accept it and yield a date no record could have carried, which is worse than a
     * refusal because nothing downstream can detect it.</p>
     */
    private static final DateTimeFormatter BASELINE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SSSSSS", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    static {
        // WHY : Assumptions: the geometry proof runs at class initialisation, following the precedent
        //       com.carddemo.common.codec.FixedWidthCodec sets with its own registry validation. Java
        //       declarations are the only layout source in this migration, so a descriptor that had
        //       drifted from the agreeing sources would otherwise surface as a decoded record whose
        //       fourteen fields are all plausible and all shifted -- and a 350-byte record still reads
        //       as 350 bytes after a shift, so nothing downstream would notice. Failing here means no
        //       job can decode a single record against a wrong geometry.
        verifyGeometry();
    }

    /**
     * Prevents instantiation of this stateless boundary.
     *
     * <p>Alternatives Considered: a Spring-managed bean with instance methods, so a job or service
     * could receive this mapper by constructor injection. Rejected because the type holds no state
     * and has no collaborator to inject -- it reads one immutable registry descriptor and calls
     * static codecs -- so a bean would add a lifecycle and a wiring point without adding a seam any
     * test needs, and every assertion in this file's coverage runs against a byte array with no
     * application context at all. The shared kernel takes the same line for the same reason,
     * declaring {@code CopybookLayout}, {@code FixedWidthCodec}, {@code ZonedDecimalCodec} and
     * {@code TimestampFormatter} as final classes with private constructors and static entry
     * points.</p>
     *
     * <p>Alternatives Considered: an abstract base mapper or a generic reflective mapper shared with
     * this package's other record boundaries, which would remove the helper methods below that
     * resemble their counterparts on the sibling mappers. Prohibited outright by the package
     * charter: mappers that each decode a fixed-width record look like instances of one pattern, and
     * factoring the pattern upward relocates each mapper's justification away from the code it
     * applies to, leaving one paragraph on a superclass that is true of none of them in particular.
     * The duplication is the charter's accepted cost, and it buys a citation of this record's own
     * copybook and program lines at every decision rather than a generic one.</p>
     */
    private DailyTransactionMapper() {
    }

    /**
     * Returns the registered {@code DALYTRAN} specification this package addresses through this file.
     *
     * <p>Assumptions: this accessor is the reason no sibling in the package resolves the registry
     * entry for itself. A caller that needs the record's declared length, a field's offset or the
     * 350-byte prefix width the reject stream inherits reads it from here, so the descriptor has one
     * reference point in this module and the geometry proof below applies to every use of it.</p>
     *
     * @return the {@code com.carddemo.common.codec.CopybookLayout.RecordSpec} the registry holds
     *     under the name {@code DALYTRAN}, which is the same instance
     *     {@code CopybookLayout.layout("DALYTRAN")} returns, so the trailing-pad handling that
     *     depends on that identity keeps working for any caller that passes it to a codec
     */
    public static CopybookLayout.RecordSpec layout() {
        return LAYOUT;
    }

    /**
     * Decodes a 350-byte daily-transaction image, retaining the image alongside the entity.
     *
     * <p>Assumptions: this is the entry point the posting job uses, in preference to
     * {@link #toEntity(byte[])}, because a record that fails validation has to reach the reject
     * stream as the bytes it arrived as. {@code app/cbl/CBTRN02C.cbl:447} moves the whole record
     * group into the reject prefix, so the prefix keeps the record's own trailing pad and its own
     * processing-timestamp span; re-encoding from the entity would blank the pad and rewrite the
     * timestamp in the target form, and either change fails a byte-deterministic comparison of the
     * reject stream while every decoded field is right.</p>
     *
     * @param record the byte array holding exactly one 350-byte record image in the fixed-width form
     *     declared by {@code app/cpy/CVTRA06Y.cpy}; it is read and never modified, and the returned
     *     value holds a copy of it rather than this array
     * @return a {@link DecodedRecord} pairing the {@link DailyTransaction} carrying the thirteen
     *     mapped fields with a verbatim copy of {@code record}
     * @throws RecordMappingException if a timestamp span is neither blank nor in either producer's
     *     26-character form, or if a decoded value is not of the Java type its declared kind implies
     * @throws FixedWidthCodec.RecordLengthException if {@code record} is {@code null} or is not
     *     exactly 350 bytes
     * @throws FixedWidthCodec.FieldCodecException if a field's bytes are invalid for its declared
     *     kind, such as a non-digit inside an unsigned numeric-display span
     */
    public static DecodedRecord decode(byte[] record) {
        // WHY : Trade-offs: the whole record is decoded in one call rather than field by field. The
        //       record-level entry point length-checks once before the first slice, so a truncated
        //       image is refused by width instead of yielding a partial map in which the money field
        //       has silently shifted; the cost is that a caller wanting one field still pays for
        //       thirteen. That cost is the right way round here, because the posting walk needs the
        //       whole row on every record it reads.
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(record, LAYOUT);

        // WHY : Assumptions: THE TRIM RULE IS ASYMMETRIC ACROSS ADJACENT PIC X(n) FIELDS, and the
        //       asymmetry is a decision rather than an inconsistency. It is the rule the sibling
        //       TransactionRecordMapper settled for the identically-shaped posted record and it is
        //       applied here rather than reconsidered, so that a value read from one of the two
        //       records compares with a value read from the other. DALYTRAN-ID, DALYTRAN-TYPE-CD and
        //       DALYTRAN-CARD-NUM keep their full declared width because each is compared at a fixed
        //       width: the identifier is the record key that app/cbl/CBTRN02C.cbl:67-68 declares as
        //       the leading sixteen bytes, the type code is a reference-data key whose counterpart
        //       column is itself fixed width, and the card number is the cross-reference lookup key
        //       app/cbl/CBTRN02C.cbl:382 moves into the read. A trimmed value of any of the three
        //       would still equal itself and would no longer equal its blank-padded counterpart in an
        //       application-level String comparison, which is a mismatch no length check reveals. The
        //       five descriptive fields are never compared, so under transformation rule T1 their
        //       trailing blanks are padding to the declared record length rather than data.
        DailyTransaction entity = new DailyTransaction(
                exactWidthText(decoded, DALYTRAN_ID),
                exactWidthText(decoded, DALYTRAN_TYPE_CD),
                paddedDigits(decoded, DALYTRAN_CAT_CD),
                trimmedText(decoded, DALYTRAN_SOURCE),
                trimmedText(decoded, DALYTRAN_DESC),
                signedAmount(decoded, DALYTRAN_AMT),
                unsignedValue(decoded, DALYTRAN_MERCHANT_ID),
                trimmedText(decoded, DALYTRAN_MERCHANT_NAME),
                trimmedText(decoded, DALYTRAN_MERCHANT_CITY),
                trimmedText(decoded, DALYTRAN_MERCHANT_ZIP),
                exactWidthText(decoded, DALYTRAN_CARD_NUM),
                timestampOf(decoded, DALYTRAN_ORIG_TS),
                timestampOf(decoded, DALYTRAN_PROC_TS));
        return new DecodedRecord(entity, record);
    }

    /**
     * Decodes a 350-byte daily-transaction image into an entity, discarding the image.
     *
     * <p>Assumptions: this overload is for callers with no reject stream to serve -- the preflight of
     * {@code app/cbl/CBTRN01C.cbl}, which reports an unresolvable record at its lines 181 to 183 and
     * writes no rejected image, and the round-trip coverage. A caller that may need to reject the
     * record uses {@link #decode(byte[])} instead, because the bytes cannot be recovered from the
     * entity afterwards.</p>
     *
     * @param record the byte array holding exactly one 350-byte record image; it is read and never
     *     modified
     * @return the {@link DailyTransaction} carrying the thirteen mapped fields, with the trailing pad
     *     dropped and each timestamp either parsed or left absent
     * @throws RecordMappingException if a timestamp span is neither blank nor in either producer's
     *     26-character form, or if a decoded value is not of the Java type its declared kind implies
     * @throws FixedWidthCodec.RecordLengthException if {@code record} is {@code null} or is not
     *     exactly 350 bytes
     * @throws FixedWidthCodec.FieldCodecException if a field's bytes are invalid for its declared
     *     kind
     */
    public static DailyTransaction toEntity(byte[] record) {
        return decode(record).entity();
    }

    /**
     * Encodes a feed entity into a 350-byte image, rendering both timestamps in the target form.
     *
     * <p>Assumptions: the trailing pad is rebuilt as blanks and each timestamp is rendered through
     * {@code com.carddemo.common.time.TimestampFormatter}, so this overload reproduces a source image
     * byte for byte only when that source had a blank pad and target-form timestamps. That is the
     * ordinary case for this dataset -- the pad is blank on 300 of the 300 records of
     * {@code app/data/ASCII/dailytran.txt} and the extract writes its originating stamp in the target
     * form -- which is why this overload is the one the round-trip coverage uses.</p>
     *
     * <p>Assumptions: one documented caveat survives, and it is a property of the sign carrier rather
     * than of this method. {@code com.carddemo.common.codec.ZonedDecimalCodec} emits the canonical
     * positive-zero overpunch for every zero amount, the target decimal type having no negative zero
     * to distinguish, so an image whose amount span carried a negative-zero overpunch re-encodes with
     * the positive one. The value is unchanged and the byte is not.
     * {@link #toRecord(DailyTransaction, byte[])} is the overload that reproduces such a span.</p>
     *
     * @param feedRecord the {@link DailyTransaction} to render; its members supply the thirteen mapped
     *     fields and the trailing pad is rebuilt as blanks
     * @return a byte array of exactly 350 bytes in the fixed-width form declared by
     *     {@code app/cpy/CVTRA06Y.cpy}, with each present timestamp in the target 26-character form
     *     and each absent one as a 26-blank span
     * @throws RecordMappingException if {@code feedRecord} is {@code null}, if the record key or the
     *     amount is absent, if a fixed-width code is present at some width other than its declared
     *     one, or if the category code is not decimal digits
     * @throws FixedWidthCodec.FieldCodecException if a value cannot be represented in its declared
     *     span, such as a descriptive value longer than its field
     * @throws ZonedDecimalCodec.ZonedDecimalException if the amount cannot be rendered in nine
     *     integer and two fractional positions without discarding precision
     */
    public static byte[] toRecord(DailyTransaction feedRecord) {
        return FixedWidthCodec.encodeRecord(fieldMap(feedRecord), LAYOUT);
    }

    /**
     * Encodes a feed entity into a 350-byte image, restoring from a source image what the entity
     * cannot carry.
     *
     * <p>Assumptions: three regions of this record survive a decode only as bytes, so an entity alone
     * cannot reproduce them, and each has a named cause. The trailing {@code FILLER} has no entity
     * member at all, because the package charter drops a pad that carries neither a
     * {@code REDEFINES} clause nor a {@code VALUE} clause, so it is copied unconditionally. The sign
     * carrier of a zero amount is copied because the shared zoned codec emits the canonical
     * positive-zero overpunch for every zero. And each 26-character timestamp span is copied when and
     * only when it decodes to the value the entity now holds, which is exactly the case where
     * re-encoding would change the form and nothing else.</p>
     *
     * <p>Assumptions: this is the overload the migrated equivalent of the daily-transaction backup
     * generation uses. {@code app/jcl/DEFGDGB.jcl:31-33} defines
     * {@code AWS.M2.CARDDEMO.TRANSACT.DALY} at {@code LIMIT(5) SCRATCH}, and a staged generation of a
     * dataset is expected to be the bytes that were read rather than a rendering of them, so a
     * generation written through the plain overload would differ from its input in the pad and in the
     * timestamp form on every record whose input used the baseline form.</p>
     *
     * <p>Trade-offs: the timestamp restoration is conditional rather than unconditional, and the
     * condition is what keeps the method honest. Copying the source span unconditionally would make
     * this entry point ignore the entity's own value, so a caller that had supplied a processing
     * stamp would silently emit a blank one -- a wrong record that still matched its source. Testing
     * for equality of the value first costs one parse of each span and means a changed value is
     * rendered in the target form while an unchanged one keeps its original bytes.</p>
     *
     * @param feedRecord the {@link DailyTransaction} to render
     * @param sourceImage the byte array of exactly 350 bytes the entity was decoded from, as
     *     {@link DecodedRecord#sourceImage()} returns it; it is read and never modified, and it
     *     supplies the trailing pad, the zero-amount sign carrier and each unchanged timestamp span
     * @return a byte array of exactly 350 bytes that reproduces {@code sourceImage} wherever the
     *     entity's members still agree with it
     * @throws RecordMappingException if {@code feedRecord} is {@code null}, if the record key or the
     *     amount is absent, if a fixed-width code is present at some width other than its declared
     *     one, if the category code is not decimal digits, or if a source timestamp span is neither
     *     blank nor in either producer's form, which means the image does not belong to this record
     * @throws FixedWidthCodec.RecordLengthException if {@code sourceImage} is {@code null} or is not
     *     exactly 350 bytes
     * @throws FixedWidthCodec.FieldCodecException if a value cannot be represented in its declared
     *     span
     * @throws ZonedDecimalCodec.ZonedDecimalException if the amount cannot be rendered in nine
     *     integer and two fractional positions without discarding precision
     */
    public static byte[] toRecord(DailyTransaction feedRecord, byte[] sourceImage) {
        Map<String, Object> fields = fieldMap(feedRecord);

        // WHY : Assumptions: the sign-preserving entry point is used rather than the plain one because
        //       it also performs the width check on the source image, so a caller that passed an image
        //       of the wrong length is told so by width rather than by an array bounds failure raised
        //       from inside the span copy below.
        byte[] encoded = FixedWidthCodec.encodeRecordPreservingSign(fields, LAYOUT, sourceImage);

        copySpan(sourceImage, encoded, LAYOUT.field(FILLER));
        restoreUnchangedTimestamp(sourceImage, encoded, DALYTRAN_ORIG_TS, feedRecord.getOrigTs());
        restoreUnchangedTimestamp(sourceImage, encoded, DALYTRAN_PROC_TS, feedRecord.getProcTs());
        return encoded;
    }

    /**
     * Projects a feed record onto the posted transaction it becomes, stamping a supplied instant.
     *
     * <p>Assumptions: this is a transcription of the {@code 2000-POST-TRANSACTION} paragraph at
     * {@code app/cbl/CBTRN02C.cbl:424-444}, and it carries the field map only. The three routines
     * that paragraph performs after the map -- the category balance at line 440, the account update
     * at 441 and the write at 442 -- are the posting service's work and none of them happens here, so
     * nothing this method returns has been persisted, balanced or validated.</p>
     *
     * <p>Alternatives Considered: <b>the processing instant is a parameter rather than a clock read
     * inside this method</b>, and reading the clock here was the obvious approach. The baseline does
     * read a clock -- {@code app/cbl/CBTRN02C.cbl:437} performs a routine whose first statement at
     * line 693 is {@code MOVE FUNCTION CURRENT-DATE TO COBOL-TS} -- so transcribing that literally
     * would have meant calling a clock from this method. It is rejected for two specific reasons.
     * First, a method that reads a clock cannot be asserted against a golden master: every run
     * produces a different processing stamp, so a comparison either fails or has to blank the field
     * and stop checking it, and the coverage for this file proves determinism by calling the
     * projection twice with the same arguments and comparing the results. Second, it contradicts the
     * batch design's own rule that a run's time inputs are injected rather than taken from the wall
     * clock: {@code app/jcl/INTCALC.jcl:22} supplies {@code PARM='2022071800'} -- the only
     * {@code PARM=} on any migrated batch program -- precisely so that a rerun reproduces its output.
     * A projection that read a clock would be the one place in the chain where a rerun could not. The
     * caller is therefore responsible for the instant's provenance, and the job that owns the run is
     * where a clock may legitimately be read once per run.</p>
     *
     * <p>Assumptions: the supplied instant is emitted through
     * {@code com.carddemo.common.time.TimestampFormatter} rather than through a 26-character pattern
     * written here, under the migration plan's transformation rule T2. The two forms are not
     * interchangeable: the baseline's own form, declared at {@code app/cbl/CBTRN02C.cbl:160-174},
     * differs from the target's at one-based positions 11, 14 and 17 and again across positions 21 to
     * 26, so a pattern re-derived here could disagree with the column the value is stored in at four
     * separate points.</p>
     *
     * <p>Assumptions: this projection is <b>not</b> the interest generator's, and the two must not be
     * merged. Interest accrual performs its timestamp routine once at
     * {@code app/cbl/CBACT04C.cbl:496} and moves that one value into both the originating stamp at
     * line 497 and the processing stamp at line 498. Posting takes the originating stamp from the
     * source record and stamps only the processing stamp. Two structurally different patterns, so
     * there is no shared routine that stamps the timestamps, and a helper covering both would need a
     * flag carrying the whole of the difference between a deterministic stamp a parity comparison
     * compares and a clock read it blanks.</p>
     *
     * @param feedRecord the {@link DailyTransaction} to project, supplying every value the posted
     *     record takes from the feed; it is read and never modified, and its own processing stamp is
     *     deliberately not among the values read
     * @param processingTimestamp the LocalDateTime the caller has established as this run's posting
     *     instant, standing for the value {@code app/cbl/CBTRN02C.cbl:437-438} obtains and moves into
     *     the posted record's processing stamp; it is reduced to microsecond precision so that it
     *     survives a round trip through the target 26-character form, and the caller owns its
     *     provenance
     * @return a {@link Transaction} carrying the twelve values copied from {@code feedRecord} and the
     *     supplied instant as its processing stamp, with nothing persisted and no balance touched
     * @throws RecordMappingException if {@code feedRecord} or {@code processingTimestamp} is
     *     {@code null}, since the posted record's processing stamp is declared not null and its key
     *     cannot be read from an absent source
     * @throws IllegalArgumentException if the year of {@code processingTimestamp} lies outside the
     *     range the shared formatter supports, because such an instant has no rendering in the target
     *     26-character form
     */
    public static Transaction toPostedTransaction(DailyTransaction feedRecord,
            LocalDateTime processingTimestamp) {
        requireFeedRecord(feedRecord, "projected onto a posted transaction");
        if (processingTimestamp == null) {
            // WHY : Assumptions: an absent instant is refused rather than defaulted, and the two
            //       tempting defaults are both wrong. Defaulting to the current instant would
            //       reintroduce the clock read this method's contract exists to keep out. Defaulting
            //       to absent would emit a posted row with no processing stamp, which
            //       com.carddemo.batch.domain.Transaction declares NOT NULL on its proc_ts column
            //       because a posted transaction that was never stamped is not a state posting can
            //       reach -- so the row would be refused later, by the database, with no indication
            //       of which caller omitted the argument.
            throw new RecordMappingException("record " + REGISTRY_NAME
                    + " cannot be projected without a processing instant, which the caller supplies"
                    + " so that a rerun reproduces its output rather than reading a clock");
        }

        // WHY : Assumptions: EXACTLY TWELVE FIELDS COPY ONE TO ONE AND NO MORE, which is the whole of
        //       the field map at app/cbl/CBTRN02C.cbl:425-436 -- the identifier at :425, the type code
        //       at :426, the category code at :427, the source at :428, the description at :429, the
        //       amount at :430, the merchant identifier at :431, the merchant name at :432, the
        //       merchant city at :433, the merchant postal code at :434, the card number at :435 and
        //       the ORIGINATING timestamp at :436. Counting them is the point: the record declares
        //       THIRTEEN mapped fields, so twelve copies leave exactly one field unaccounted for, and
        //       that one is the subject of the comment below. The identifier is passed to the
        //       constructor rather than to a mutator because the posted entity takes its record key
        //       that way and offers no mutator for it. No value is trimmed, re-scaled, re-signed or
        //       re-padded on the way across, because the paragraph's MOVEs are between fields of
        //       identical picture in two copybooks that declare the same widths in the same order --
        //       so a transformation applied here would be one the baseline does not perform.
        Transaction posted = new Transaction(feedRecord.getTransactionId());
        posted.setTypeCd(feedRecord.getTypeCd());
        posted.setCategoryCd(feedRecord.getCategoryCd());
        posted.setSource(feedRecord.getSource());
        posted.setDescription(feedRecord.getDescription());
        posted.setAmount(feedRecord.getAmount());
        posted.setMerchantId(feedRecord.getMerchantId());
        posted.setMerchantName(feedRecord.getMerchantName());
        posted.setMerchantCity(feedRecord.getMerchantCity());
        posted.setMerchantZip(feedRecord.getMerchantZip());
        posted.setCardNum(feedRecord.getCardNum());
        posted.setOrigTs(feedRecord.getOrigTs());

        // WHY : Assumptions: THE THIRTEENTH FIELD DOES NOT COPY, and this single asymmetry is the
        //       defining behaviour of the projection. app/cbl/CBTRN02C.cbl:436 copies the source's
        //       ORIGINATING stamp across, then :437 performs the timestamp routine and :438 moves ITS
        //       result into the posted record's PROCESSING stamp -- so DALYTRAN-PROC-TS is never read
        //       anywhere in the paragraph. The source record's own processing stamp is dropped and
        //       replaced, not carried. That is consistent with what the feed holds rather than an
        //       oversight in the paragraph: the span is blank on 300 of the 300 records of
        //       app/data/ASCII/dailytran.txt, so there is nothing there to carry, and a projection
        //       that copied it would emit a posted row with no processing stamp on every record.
        // WHY : Assumptions: the reduction to microseconds is applied through the shared formatter
        //       rather than left to the caller. The target form carries six fractional digits, so an
        //       instant with nanosecond precision does not survive a round trip through it, and a
        //       posted row would then disagree with its own rendered image in the last three digits.
        //       The formatter owns this reduction precisely so that it is not repeated at each
        //       persistence site, where it is eventually missed at one.
        posted.setProcTs(TimestampFormatter.normalize(processingTimestamp));
        return posted;
    }

    /**
     * Assembles the thirteen mapped field values an encode needs, in copybook declaration order.
     *
     * <p>Assumptions: the trailing pad is deliberately absent from the returned map. The shared codec
     * recognises a registered trailing-pad descriptor that no caller supplied and rebuilds it with the
     * target charset's blank byte, which is what makes the emitted image exactly 350 bytes rather than
     * merely correct in its populated 330-byte prefix. Supplying the pad explicitly would work and is
     * avoided, because it would put a second opinion about what the pad contains into this module,
     * and the byte-preserving overload is the sanctioned way to reproduce a pad that was not
     * blank.</p>
     *
     * @param feedRecord the {@link DailyTransaction} whose members supply the field values
     * @return a Map of field values keyed by copybook name, holding thirteen entries in declaration
     *     order and no entry for the trailing pad
     * @throws RecordMappingException if {@code feedRecord} is {@code null}, if the record key or the
     *     amount is absent, if a fixed-width code is present at some width other than its declared
     *     one, or if the category code is absent or is not decimal digits
     */
    private static Map<String, Object> fieldMap(DailyTransaction feedRecord) {
        requireFeedRecord(feedRecord, "encoded into a record image");

        // WHY : Trade-offs: insertion order is preserved so that a diagnostic listing the supplied
        //       keys reads in copybook order, which is the order a reader compares against
        //       app/cpy/CVTRA06Y.cpy and against a hex dump of the image. A plain hash map would
        //       encode identically, because the codec walks the SPECIFICATION rather than the map, so
        //       the only thing given up by not choosing one is a negligible amount of allocation.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DALYTRAN_ID,
                encodedKey(feedRecord.getTransactionId(), LAYOUT.field(DALYTRAN_ID)));
        fields.put(DALYTRAN_TYPE_CD,
                encodedFixedWidth(feedRecord.getTypeCd(), LAYOUT.field(DALYTRAN_TYPE_CD)));
        fields.put(DALYTRAN_CAT_CD,
                encodedDigits(feedRecord.getCategoryCd(), LAYOUT.field(DALYTRAN_CAT_CD)));
        fields.put(DALYTRAN_SOURCE, encodedDescriptive(feedRecord.getSource()));
        fields.put(DALYTRAN_DESC, encodedDescriptive(feedRecord.getDescription()));
        fields.put(DALYTRAN_AMT,
                encodedAmount(feedRecord.getAmount(), LAYOUT.field(DALYTRAN_AMT)));
        fields.put(DALYTRAN_MERCHANT_ID, encodedMerchantId(feedRecord.getMerchantId()));
        fields.put(DALYTRAN_MERCHANT_NAME, encodedDescriptive(feedRecord.getMerchantName()));
        fields.put(DALYTRAN_MERCHANT_CITY, encodedDescriptive(feedRecord.getMerchantCity()));
        fields.put(DALYTRAN_MERCHANT_ZIP, encodedDescriptive(feedRecord.getMerchantZip()));
        fields.put(DALYTRAN_CARD_NUM,
                encodedFixedWidth(feedRecord.getCardNum(), LAYOUT.field(DALYTRAN_CARD_NUM)));
        fields.put(DALYTRAN_ORIG_TS, encodedTimestamp(feedRecord.getOrigTs()));
        fields.put(DALYTRAN_PROC_TS, encodedTimestamp(feedRecord.getProcTs()));
        return fields;
    }

    /**
     * Validates the record key and returns it at its declared width.
     *
     * @param value the String the entity holds for the record key, which may be {@code null}
     * @param field the {@code CopybookLayout.FieldSpec} descriptor of the key field, supplying its
     *     declared width and the text a failure quotes
     * @return the same String, guaranteed to be exactly the declared width
     * @throws RecordMappingException if the key is absent or empty, or if it is present at some width
     *     other than the declared one
     */
    private static String encodedKey(String value, CopybookLayout.FieldSpec field) {
        if (value == null || value.isEmpty()) {
            // WHY : Assumptions: an absent key is refused rather than blank-filled, unlike every other
            //       character field here. Both readers declare this field as the leading sixteen bytes
            //       of the record -- app/cbl/CBTRN02C.cbl:67-68 and app/cbl/CBTRN01C.cbl:67-68 -- and
            //       the preflight names it when it reports a record it could not resolve, at
            //       app/cbl/CBTRN01C.cbl:181-183. A blank key is therefore not an unpopulated field
            //       but a record no diagnostic could ever identify, and it is also the column the
            //       migrated sequential walk orders by, so a blank one would sort to the front of the
            //       feed and be read first.
            throw new RecordMappingException("field " + field.describe()
                    + " is the record key and must be present, but the entity holds "
                    + (value == null ? "no value" : "an empty value"));
        }
        return encodedFixedWidth(value, field);
    }

    /**
     * Validates a fixed-width code and returns it, or blank-fills it when the entity holds no value.
     *
     * @param value the String the entity holds, which may be {@code null} or empty
     * @param field the {@code CopybookLayout.FieldSpec} descriptor, supplying the declared width, the
     *     sensitivity marking and the text a failure quotes
     * @return the same String when it is exactly the declared width, or an empty String when the
     *     entity holds no value, which the codec then renders as a blank-filled span
     * @throws RecordMappingException if the value is present at some width other than the declared one
     */
    private static String encodedFixedWidth(String value, CopybookLayout.FieldSpec field) {
        if (value == null || value.isEmpty()) {
            // WHY : Assumptions: a character span has no representation for absent, so an unpopulated
            //       member becomes blanks rather than raising. Returning an empty string rather than a
            //       run of blanks lets the shared codec pad to the declared width with the target
            //       charset's own blank byte, so this method never needs to know which byte that is --
            //       which matters because the same codec accepts an IBM code page as readily as
            //       US-ASCII and the two disagree about it.
            return "";
        }
        if (value.length() != field.length()) {
            // WHY : Trade-offs: a code of the WRONG WIDTH is refused rather than padded or truncated,
            //       and both alternatives fail in ways no length check downstream would catch.
            //       Blank-padding a short code produces a span that is a DIFFERENT value to every
            //       fixed-width comparison -- a type code of '1' padded to two characters is not the
            //       '01' that the reference-data key is declared at -- and truncating a long one
            //       silently discards a digit of a card number. Refusing costs a caller an explicit
            //       correction and buys the guarantee that every emitted code compares as the
            //       baseline's does.
            throw new RecordMappingException("field " + field.describe() + " must be exactly "
                    + field.length() + " characters so that its fixed-width comparisons still match,"
                    + " but the entity holds " + forDiagnostic(value, field));
        }
        return value;
    }

    /**
     * Validates a numeric-display code and returns the zero-padded digits its span must carry.
     *
     * @param value the String the entity holds, expected to be decimal digits, which may be
     *     {@code null}
     * @param field the {@code CopybookLayout.FieldSpec} descriptor, supplying the declared width and
     *     the text a failure quotes
     * @return the String of exactly the declared width, being the digits left-padded with zeros
     * @throws RecordMappingException if the value is absent, empty, longer than the declared width or
     *     contains a character that is not a decimal digit
     */
    private static String encodedDigits(String value, CopybookLayout.FieldSpec field) {
        if (value == null || value.isEmpty()) {
            // WHY : Assumptions: unlike a character span, a numeric-display span has no blank form the
            //       decode direction could read back -- the shared codec rejects any non-digit in it
            //       outright -- so blank-filling an absent value would emit a record this very class
            //       could not decode. Refusing is the only option that keeps the two directions
            //       consistent with each other.
            throw new RecordMappingException("field " + field.describe()
                    + " is an unsigned numeric-display span with no blank form, so it must be present,"
                    + " but the entity holds "
                    + (value == null ? "no value" : "an empty value"));
        }
        for (int index = 0; index < value.length(); index++) {
            char candidate = value.charAt(index);
            if (candidate < '0' || candidate > '9') {
                // WHY : Trade-offs: a non-digit is REFUSED rather than coerced, and this is where a
                //       two-character reading of the category code is caught. A caller that had
                //       treated DALYTRAN-CAT-CD as a two-character code would present two digits
                //       followed by blanks here, and coercing that -- by dropping the blanks, say --
                //       would emit a span that decoded back to the right number and hid the defect
                //       from every later record. Refusing surfaces it at the first one.
                throw new RecordMappingException("field " + field.describe()
                        + " is an unsigned numeric-display span and must hold decimal digits only, but"
                        + " the entity holds a non-digit at zero-based index " + index + " of "
                        + forDiagnostic(value, field));
            }
        }
        if (value.length() > field.length()) {
            throw new RecordMappingException("field " + field.describe() + " holds "
                    + field.length() + " digit positions but the entity supplies " + value.length());
        }

        // WHY : Assumptions: the padding is applied here rather than left to the shared codec even
        //       though the codec would also zero-pad, because the padded form is what the physical
        //       four-character column stores and what every counterpart key this value joins to is
        //       declared at. Doing it here means the map handed to the codec already carries the
        //       canonical form, so a diagnostic listing that map shows what will be written rather
        //       than what was asked for.
        return zeroPad(value, field.length());
    }

    /**
     * Returns a descriptive value ready for encoding, treating absent as blank.
     *
     * @param value the String the entity holds for a descriptive field, which may be {@code null}
     * @return the same String, or an empty String when the entity holds no value, which the codec then
     *     renders as a blank-filled span of the declared width
     */
    private static String encodedDescriptive(String value) {
        // WHY : Assumptions: no width check is applied on this side, unlike the fixed-width codes
        //       above. A descriptive field's trailing blanks are padding rather than data under
        //       transformation rule T1, so a value shorter than the declared width is the ordinary
        //       case -- the feed writes ten characters of channel label into DALYTRAN-SOURCE and
        //       nothing at all into much of the hundred-byte description -- and blank-padding it is
        //       exactly right. An OVER-long value is still refused, by the shared codec, which names
        //       the byte count it could not fit.
        return value == null ? "" : value;
    }

    /**
     * Returns the amount ready for encoding, insisting it is present.
     *
     * @param amount the BigDecimal the entity holds, which may be {@code null}
     * @param field the {@code CopybookLayout.FieldSpec} descriptor of the signed zoned field, quoted
     *     in a failure
     * @return the same BigDecimal, which the shared codec then renders losslessly or refuses
     * @throws RecordMappingException if the amount is absent
     */
    private static BigDecimal encodedAmount(BigDecimal amount, CopybookLayout.FieldSpec field) {
        if (amount == null) {
            // WHY : Assumptions: an absent amount is refused and NOT defaulted to zero, which is the
            //       one tempting default in this method. A signed zoned span has no absent form, so
            //       something has to be written; writing zero would emit a transaction of no value
            //       that app/cbl/CBTRN02C.cbl:403-405 would then project against a credit limit
            //       without complaint, and the comparison would show a plausible record rather than a
            //       missing one. Refusing keeps an incomplete row out of a monetary total.
            throw new RecordMappingException("field " + field.describe()
                    + " is a signed zoned span with no absent form and is never defaulted to zero, but"
                    + " the entity holds no amount");
        }

        // WHY : Assumptions: the encode is LOSSLESS-OR-THROW and the enforcement is delegated rather
        //       than repeated. com.carddemo.common.codec.ZonedDecimalCodec rescales to the field's two
        //       fractional digits with RoundingMode.UNNECESSARY, so a value carrying fewer decimal
        //       places is padded while one carrying a non-zero third place is refused rather than
        //       rounded, and it separately refuses a value needing more than the eleven digit
        //       positions this span provides. Re-checking either rule here would put a second,
        //       drifting opinion about precision next to the one a parity comparison depends on.
        return amount;
    }

    /**
     * Returns the merchant identifier ready for encoding, treating absent as zero.
     *
     * @param merchantId the Long the entity holds, which may be {@code null}
     * @return the same Long, or {@code 0} when the entity holds no value
     */
    private static Long encodedMerchantId(Long merchantId) {
        // WHY : Assumptions: absent becomes ZERO rather than raising, because zero is the value the
        //       baseline itself writes when there is no merchant -- app/cbl/CBACT04C.cbl:491 issues
        //       MOVE 0 TO TRAN-MERCHANT-ID on every generated interest transaction, into the
        //       identically-declared field of the identically-shaped posted record. A nine-byte
        //       numeric-display span has no absent form, so zero here is not a substitute for missing
        //       data but the encoding of "no merchant" that the decode direction reads straight back.
        //       A NEGATIVE value is still refused, by the shared codec, since an unsigned display
        //       field has no sign carrier at all.
        return merchantId == null ? Long.valueOf(0L) : merchantId;
    }

    /**
     * Renders a timestamp in the target 26-character form, or blanks when the entity holds no value.
     *
     * @param timestamp the LocalDateTime the entity holds, which may be {@code null}
     * @return the 26-character String the shared formatter emits, or an empty String when the entity
     *     holds no value, which the codec then renders as a 26-blank span
     */
    private static String encodedTimestamp(LocalDateTime timestamp) {
        // WHY : Assumptions: the target form is produced by calling
        //       com.carddemo.common.time.TimestampFormatter rather than by formatting a pattern here.
        //       That contract belongs to the shared kernel under transformation rule T2, and it is the
        //       form the physical column and the migrated writers agree on. The BASELINE form is
        //       deliberately NOT emitted from this path: the two are different byte patterns and
        //       neither is normalised into the other, so a caller that needs the original bytes back
        //       uses the byte-preserving encode instead of asking this method for them.
        // WHY : Assumptions: absent becomes 26 blanks, closing the loop with the decode direction,
        //       which reads a 26-blank span as absent. An unposted row genuinely has no processing
        //       stamp -- the span is blank on 300 of the 300 records of
        //       app/data/ASCII/dailytran.txt -- so this pair of conversions is the record's
        //       representation of that state rather than a tolerance for missing data.
        return timestamp == null ? "" : TimestampFormatter.format(timestamp);
    }

    /**
     * Returns one decoded field at its full declared width, with nothing trimmed.
     *
     * @param decoded the Map of decoded field values keyed by copybook name, as the record-level
     *     decode returned it
     * @param fieldName the String copybook name of the field to read
     * @return the String occupying that span, at exactly the declared width including any trailing
     *     blanks
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than a character value
     */
    private static String exactWidthText(Map<String, Object> decoded, String fieldName) {
        Object value = require(decoded, fieldName);
        if (!(value instanceof String text)) {
            throw typeFailure(fieldName, value, "a character value");
        }

        // WHY : Assumptions: the value is returned UNTRIMMED even though a reader of the seed extract
        //       would never see the difference, because two of this method's three callers hand back a
        //       key. Handing back the full span means a caller comparing this value with one read from
        //       another fixed-width record compares like with like; trimming here would make that
        //       comparison depend on which of the two values had been through a mapper.
        return text;
    }

    /**
     * Returns one decoded field with its trailing blanks removed and nothing else changed.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param fieldName the String copybook name of the field to read
     * @return the String occupying that span with trailing blank characters removed; an all-blank span
     *     yields an empty String rather than {@code null}
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than a character value
     */
    private static String trimmedText(Map<String, Object> decoded, String fieldName) {
        Object value = require(decoded, fieldName);
        if (!(value instanceof String text)) {
            throw typeFailure(fieldName, value, "a character value");
        }

        // WHY : Assumptions: an all-blank span becomes an EMPTY STRING and never null. Blank is an
        //       ordinary value in these five fields rather than a missing one --
        //       app/cbl/CBACT04C.cbl:492-494 writes SPACES into the merchant name, city and postal
        //       code of the identically-shaped posted record on every generated interest transaction,
        //       because an accrual has no merchant. Normalising blank to null would change what the
        //       encode direction emits and would therefore change bytes a parity comparison reads.
        return stripTrailingBlanks(text);
    }

    /**
     * Removes trailing blank characters from a fixed-width span, and only blank characters.
     *
     * <p>Alternatives Considered: {@code String.stripTrailing}, which is the obvious call and is
     * rejected. It removes every trailing character that counts as whitespace -- a tab, a newline, a
     * form feed -- and in a fixed-width record only the blank is padding. A description that genuinely
     * ended in a tab would lose it, and the encode direction would then rebuild that position as a
     * blank, so a round trip would differ from its source in a byte no assertion covered. Stripping
     * exactly the one character COBOL pads with keeps the round trip byte-exact by construction.</p>
     *
     * <p>Assumptions: only the trailing run is removed, and the method never raises. A COBOL
     * {@code STRING} statement does not clear the remainder of its receiving field, so bytes left from
     * an earlier use can persist beyond the meaningful content of a descriptive span; residue that is
     * not blank is therefore left exactly where it is rather than treated as an error, so neither
     * reading of the tail can turn a decodable record into a rejected one.</p>
     *
     * @param text the String span to trim, at its full declared width
     * @return the same String when its last character is not a blank; otherwise a prefix with the
     *     trailing blank run removed, which is empty when the span is entirely blank
     */
    private static String stripTrailingBlanks(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == BLANK) {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /**
     * Returns one decoded unsigned-display field as the zero-padded characters the span holds.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param fieldName the String copybook name of the unsigned-display field to read
     * @return the String zero-padded to the field's declared width, which is the exact byte image the
     *     span carried
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than an integral value
     */
    private static String paddedDigits(Map<String, Object> decoded, String fieldName) {
        Object value = require(decoded, fieldName);
        if (!(value instanceof Long integral)) {
            throw typeFailure(fieldName, value, "an integral value");
        }

        // WHY : Assumptions: DALYTRAN-CAT-CD IS A NUMBER STORED AS ZERO-PADDED DIGITS, NOT A
        //       TWO-CHARACTER CODE, and this is the reading of this record most likely to be got
        //       wrong, because the posted side's producer writes the literal '05' into the same field
        //       at app/cbl/CBACT04C.cbl:483 and the literal looks like a code.
        //       app/cpy/CVTRA06Y.cpy:7 declares the field PIC 9(04) -- an unsigned four-digit
        //       numeric-display field -- so an alphanumeric move into it right-justifies and
        //       zero-fills. The four bytes at offsets 18 through 21 of the first record of
        //       app/data/ASCII/dailytran.txt read 0001, which is the NUMBER 1 and not the string '1'.
        //       A mapper that round-tripped this as a two-character code would emit a record two bytes
        //       short, or -- worse, because it still reaches 350 bytes -- one whose remaining eleven
        //       fields are all shifted and all plausible.
        // WHY : Assumptions: the canonical form handed on is the zero-padded one at the declared width,
        //       which is also the form the physical four-character column stores and the form every
        //       counterpart key this value joins to is declared at. Handing on an unpadded '1' would be
        //       the correct number and the wrong key, and the join would simply miss.
        return zeroPad(Long.toString(integral), LAYOUT.field(fieldName).length());
    }

    /**
     * Returns one decoded unsigned-display field as an integral value.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param fieldName the String copybook name of the unsigned-display field to read
     * @return the Long the span's digits denote, which is zero when the span is all zeros
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than an integral value
     */
    private static Long unsignedValue(Map<String, Object> decoded, String fieldName) {
        Object value = require(decoded, fieldName);
        if (!(value instanceof Long integral)) {
            throw typeFailure(fieldName, value, "an integral value");
        }

        // WHY : Assumptions: an unsigned-display span carries PLAIN DIGITS and no sign, so its trailing
        //       byte must never be sign-sniffed even when it happens to be a character that would be an
        //       overpunch on a signed field. The shared codec enforces that by rejecting any non-digit
        //       in the span outright, which is why this method receives an already-validated integral
        //       rather than scanning the span itself. Zero is an ordinary value here:
        //       app/cbl/CBACT04C.cbl:491 writes MOVE 0 TO TRAN-MERCHANT-ID into the identically
        //       declared field of the posted record, so a check rejecting zero would reject one of the
        //       two producers of that record entirely.
        return integral;
    }

    /**
     * Returns one decoded signed zoned field as an exact decimal at the scale the picture declares.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param fieldName the String copybook name of the signed zoned field to read
     * @return the BigDecimal the span denotes, at scale two, with its sign taken from the trailing
     *     overpunch character
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than an exact decimal
     */
    private static BigDecimal signedAmount(Map<String, Object> decoded, String fieldName) {
        Object value = require(decoded, fieldName);
        if (!(value instanceof BigDecimal amount)) {
            throw typeFailure(fieldName, value, "an exact decimal value");
        }

        // WHY : Assumptions: the scale arrives as two from the shared codec and is NEVER stripped here.
        //       Applying stripTrailingZeros would turn 50.00 into 5E+1, which is the same number and a
        //       different value to every comparison a parity oracle makes and to the two-decimal
        //       column, and it would then re-encode with the wrong digit count. The overpunch itself is
        //       delegated to com.carddemo.common.codec.ZonedDecimalCodec, which owns the two sign
        //       tables; decoding it here would put a second reading of the same trailing byte in the
        //       tree, and the first record of app/data/ASCII/dailytran.txt carries '0000005047G' --
        //       a trailing letter that means +7 and reads as corruption to anything unaware of it.
        // WHY : Assumptions: A NEGATIVE AMOUNT IS LEGITIMATE and is not clamped or rejected. The
        //       posting service branches on exactly that sign when it splits an amount between the
        //       cycle credit and the cycle debit, so refusing a negative value here would refuse the
        //       debit half of the baseline's own domain.
        return amount;
    }

    /**
     * Returns one decoded 26-character timestamp span as a date and time, or absent when it is blank.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param fieldName the String copybook name of the timestamp field to read
     * @return the LocalDateTime the span denotes, or {@code null} when the span is entirely blank
     * @throws RecordMappingException if the span is neither blank nor in either producer's
     *     26-character form, or if the decode produced something other than a character value
     */
    private static LocalDateTime timestampOf(Map<String, Object> decoded, String fieldName) {
        Object value = require(decoded, fieldName);
        if (!(value instanceof String span)) {
            throw typeFailure(fieldName, value, "a character value");
        }

        // WHY : Assumptions: A 26-BLANK TIMESTAMP IS A LEGITIMATE VALUE and decodes to absent rather
        //       than raising, and on this record that is the NORMAL case rather than an edge one.
        //       DALYTRAN-PROC-TS is blank on 300 of the 300 records of app/data/ASCII/dailytran.txt,
        //       because app/cbl/CBTRN02C.cbl:437-438 mints the processing stamp downstream instead of
        //       reading it from the feed -- so a decode that raised on a blank span would refuse the
        //       entire extract on its first record. The shared codec signals the blank case by
        //       returning an all-blank string, so the emptiness test below reads that signal rather
        //       than testing a length.
        if (stripTrailingBlanks(span).isEmpty()) {
            return null;
        }
        return parseEitherForm(span, LAYOUT.field(fieldName));
    }

    /**
     * Parses a 26-character span written by either producer, normalising neither form into the other.
     *
     * <p>Assumptions: two different 26-character byte patterns reach these fields, and they are not
     * interchangeable even though both are exactly 26 characters. The target form is owned by
     * {@code com.carddemo.common.time.TimestampFormatter} and is what the extract writes -- the first
     * record of {@code app/data/ASCII/dailytran.txt} carries {@code 2022-06-10 19:27:53.000000} in its
     * originating span. The baseline form is declared at {@code app/cbl/CBTRN02C.cbl:159} and
     * decomposed by the overlay at lines 160 to 174, and it differs at one-based position 11, where it
     * carries a third hyphen where the target form carries a space; at positions 14 and 17, where it
     * carries a dot where the target form carries a colon; and across positions 21 through 26, where
     * only the first two are significant fractional digits and the remaining four are the literal
     * {@code 0000} that line 701 writes.</p>
     *
     * <p>Assumptions: <b>neither pattern is ever normalised into the other</b>, and no decoded span is
     * assumed to be in the target form. The two are mutually exclusive -- each fails the other's parse
     * at position 11 -- so reading defensively is a matter of trying both rather than of guessing, and
     * what comes out carries no form at all. Which form is <b>emitted</b> is then an explicit decision
     * of whichever encode entry point the caller chose, and the byte-preserving overload keeps the
     * original span rather than rewriting it. Collapsing the two would make a record that still reads
     * as 350 bytes and no longer says what it said.</p>
     *
     * <p>Assumptions: the {@code normalizeTs} marker the registry sets on the processing stamp marks
     * the field for a parity comparator and normalises nothing, so it is not consulted here. The
     * target form is parsed by calling the shared formatter rather than by a second pattern declared
     * in this class, because transformation rule T2 puts that contract in the shared kernel.</p>
     *
     * @param span the String 26-character span to parse, already known to be non-blank
     * @param field the {@code com.carddemo.common.codec.CopybookLayout.FieldSpec} the span came from,
     *     used to name the field, offset, length and kind in a failure
     * @return the LocalDateTime the span denotes under whichever of the two producer forms it matches
     * @throws RecordMappingException if the span matches neither form
     */
    private static LocalDateTime parseEitherForm(String span, CopybookLayout.FieldSpec field) {
        try {
            return TimestampFormatter.parse(span);
        } catch (DateTimeParseException notTargetForm) {
            try {
                return LocalDateTime.parse(span, BASELINE_TIMESTAMP_FORMAT);
            } catch (DateTimeParseException notBaselineForm) {
                // WHY : Trade-offs: the failure names the target attempt as its cause and mentions the
                //       baseline attempt in prose, rather than chaining both. Java attaches one cause,
                //       and the target form is the one this migration's own writers produce, so its
                //       parse position is the more useful of the two to a reader diagnosing a record
                //       this module wrote. The span itself is quoted because a timestamp is not
                //       sensitive; the descriptor is quoted alongside it so the offset is available
                //       even when the characters are not recognisable as a date at all.
                throw new RecordMappingException("field " + field.describe() + " holds '" + span
                        + "', which is neither the target 26-character form owned by"
                        + " com.carddemo.common.time.TimestampFormatter nor the baseline form declared"
                        + " at app/cbl/CBTRN02C.cbl:160-174; the two differ at positions 11, 14, 17 and"
                        + " 21 through 26 and neither is normalised into the other",
                        notTargetForm);
            }
        }
    }

    /**
     * Copies one field's span verbatim from a source image into an encoded image.
     *
     * @param source the byte array to read from, of the record's declared length
     * @param target the byte array to write into, of the same length; the span is overwritten in place
     * @param field the {@code CopybookLayout.FieldSpec} descriptor naming the offset and length of the
     *     span to copy
     */
    private static void copySpan(byte[] source, byte[] target, CopybookLayout.FieldSpec field) {
        System.arraycopy(source, field.start(), target, field.start(), field.length());
    }

    /**
     * Restores a timestamp span from a source image when the entity still holds the value it carried.
     *
     * @param source the byte array the entity was decoded from, of the record's declared length
     * @param target the byte array already encoded from the entity, whose span is overwritten only
     *     when the value has not changed
     * @param fieldName the String copybook name of the timestamp field to consider
     * @param current the LocalDateTime the entity now holds for that field, which may be {@code null}
     * @throws RecordMappingException if the source span is neither blank nor in either producer's
     *     26-character form, which means the image does not belong to this record
     */
    private static void restoreUnchangedTimestamp(byte[] source, byte[] target, String fieldName,
            LocalDateTime current) {
        CopybookLayout.FieldSpec field = LAYOUT.field(fieldName);
        Object decodedSpan = FixedWidthCodec.decodeField(source, field);
        if (!(decodedSpan instanceof String span)) {
            throw typeFailure(fieldName, decodedSpan, "a character value");
        }

        // WHY : Assumptions: the source span is decoded through the shared codec rather than sliced
        //       with a charset chosen here, so the character set the source is read under stays a
        //       single decision owned by that codec. The codec also verifies the slice is
        //       byte-reversible, which is what stops a span from being compared as characters it does
        //       not actually hold.
        LocalDateTime carried = stripTrailingBlanks(span).isEmpty()
                ? null
                : parseEitherForm(span, field);

        // WHY : Trade-offs: equality of the VALUE is the condition, not equality of the bytes.
        //       Comparing bytes would never match when the source carried the baseline form and the
        //       fresh encode carried the target form, which is precisely the case this restoration
        //       exists for; and copying unconditionally would emit a stamp the caller had already
        //       replaced. Comparing the parsed values costs one parse and distinguishes "same instant,
        //       different form" -- keep the source bytes -- from "different instant" -- keep the
        //       freshly rendered ones.
        boolean unchanged = carried == null ? current == null : carried.equals(current);
        if (unchanged) {
            copySpan(source, target, field);
        }
    }

    /**
     * Renders a field value for a diagnostic, withholding it when the layout marks the field sensitive.
     *
     * <p>Alternatives Considered: rendering a sensitive value through the shared card-number masker, so
     * that the last four digits survive into the message. Rejected here because every diagnostic this
     * class raises against the sensitive field is a <b>width</b> failure, and for a width failure the
     * observed length together with the field descriptor is the entire diagnosis -- the last four
     * digits would identify which record raised it, but the record is better identified by
     * {@code DALYTRAN-ID}, which is this record's key and is not sensitive. Withholding the characters
     * altogether is what the package charter requires of a message concerning a sensitive field, and it
     * is also the stronger position: a rendering that keeps four digits is a rendering a later edit can
     * widen, whereas a value never placed in a message cannot leak from one. A batch job raising one
     * line per record would otherwise deposit an entire feed's card numbers into a log that outlives
     * the incident that produced it.</p>
     *
     * @param value the String that could not be encoded, which may hold a primary account number
     * @param field the {@code CopybookLayout.FieldSpec} descriptor of the field it belongs to, whose
     *     sensitivity marking decides whether the characters may be shown
     * @return a String safe to place in an exception message: the value in quotes when the field is not
     *     marked sensitive, and otherwise its character count alone with no character of it
     */
    private static String forDiagnostic(String value, CopybookLayout.FieldSpec field) {
        // WHY : Assumptions: the marking is read from the descriptor rather than from a list of field
        //       names kept here, so a field that becomes sensitive in the shared layout catalogue
        //       becomes withheld here without an edit to this class.
        return field.sensitive()
                ? value.length() + " characters, withheld because the layout marks the field sensitive"
                : "'" + value + "'";
    }

    /**
     * Returns one decoded value, insisting the record-level decode actually produced it.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param fieldName the String copybook name of the field to read
     * @return the Object the decode produced for that field, never {@code null}
     * @throws RecordMappingException if the decode produced no entry for that field
     */
    private static Object require(Map<String, Object> decoded, String fieldName) {
        Object value = decoded.get(fieldName);
        if (value == null) {
            // WHY : Assumptions: the record-level decode omits ONLY a blank trailing pad, and this
            //       class never asks for the pad, so a missing entry here means the specification and
            //       this class disagree about which fields exist rather than that a record was short --
            //       a short record was already refused by width. Naming the descriptor points at that
            //       disagreement instead of surfacing later as an absent member on the entity.
            throw new RecordMappingException("layout " + LAYOUT.name() + " produced no value for "
                    + LAYOUT.field(fieldName).describe() + ", so this mapper and that layout disagree"
                    + " about the record's field set");
        }
        return value;
    }

    /**
     * Builds the failure raised when a decoded value is not of the Java type its kind implies.
     *
     * @param fieldName the String copybook name of the field whose value was of the wrong type
     * @param value the Object the decode produced, whose class is named but whose content is not quoted
     * @param expected the String describing what the field's kind should have produced, such as
     *     {@code "a character value"}
     * @return a {@link RecordMappingException} naming the field descriptor, the expectation and the
     *     class actually produced
     */
    private static RecordMappingException typeFailure(String fieldName, Object value,
            String expected) {
        // WHY : Assumptions: the CLASS of the offending value is named and its CONTENT is not, for every
        //       field rather than only the one the layout marks sensitive. A type mismatch is a
        //       descriptor defect, so the class is the whole diagnosis and the content adds nothing;
        //       withholding it uniformly also means this method cannot become the one path that leaks a
        //       card number after a later edit changes which field reaches it.
        return new RecordMappingException("layout " + LAYOUT.name() + " field "
                + LAYOUT.field(fieldName).describe() + " should decode to " + expected
                + " but produced an instance of " + value.getClass().getName());
    }

    /**
     * Left-pads a digit string with zeros to a fixed width.
     *
     * @param digits the String of decimal digits to pad, never longer than {@code width}
     * @param width the int declared byte width of the numeric-display field the digits belong to
     * @return the String of exactly {@code width} characters, being {@code digits} preceded by as many
     *     zeros as the width requires
     */
    private static String zeroPad(String digits, int width) {
        return digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Rejects an absent feed record before it can reach a codec or a projection as a null.
     *
     * @param feedRecord the {@link DailyTransaction} a caller supplied, which may be {@code null}
     * @param operation the String naming what was being attempted, such as
     *     {@code "encoded into a record image"}, so the message says which entry point was reached
     * @throws RecordMappingException if {@code feedRecord} is {@code null}
     */
    private static void requireFeedRecord(DailyTransaction feedRecord, String operation) {
        if (feedRecord == null) {
            // WHY : Assumptions: the check is here rather than at each entry point so that the two
            //       encode overloads and the projection report an absent argument identically. Letting
            //       it surface from the first getter would raise a NullPointerException naming neither
            //       the record nor the operation, which sends a maintainer to read this class rather
            //       than their own call site.
            throw new RecordMappingException("layout " + REGISTRY_NAME
                    + " cannot be " + operation + " from an absent feed record");
        }
    }

    /**
     * Proves at class initialisation that the registry layout still carries the geometry asserted here.
     *
     * <p>Assumptions: the checks are chosen because each stands for a fact an independent artifact
     * depends on, rather than because they are cheap. The record length is declared three times over
     * across the copybook, both readers' file descriptions and the reject stream's allocation. The
     * amount width is the eleven-versus-twelve distinction that separates this record's money from the
     * account master's. The card-number offset is where the cross-reference lookup key is read from.
     * And the originating-stamp offset is the span whose first ten characters decide reason code
     * 103.</p>
     *
     * @throws RecordMappingException if the declared length, the field contiguity, the amount width, or
     *     the card-number, originating-stamp or processing-stamp placement has drifted from what this
     *     class asserts
     */
    private static void verifyGeometry() {
        // WHY : Assumptions: contiguity is re-asserted here even though the registry already validates
        //       it, because the registry's own check runs when CopybookLayout loads and this one runs
        //       when THIS class loads. The two orderings differ, and re-asserting costs one pass over
        //       fourteen descriptors while removing any dependence on which class a job touches first.
        LAYOUT.validateGeometry();

        if (LAYOUT.reclen() != RECORD_LENGTH) {
            throw new RecordMappingException("layout " + REGISTRY_NAME + " must declare "
                    + RECORD_LENGTH + " bytes, which app/cpy/CVTRA06Y.cpy:2 states as RECLN = 350,"
                    + " app/cbl/CBTRN02C.cbl:67-69 and app/cbl/CBTRN01C.cbl:67-69 each repeat as a"
                    + " sixteen-byte identifier plus a 334-byte remainder, and"
                    + " app/jcl/POSTTRAN.jcl:34-36 corroborates as LRECL=430 less an 80-byte reject"
                    + " trailer, but it declares " + LAYOUT.reclen());
        }

        // WHY : Assumptions: THE AMOUNT SPAN IS ELEVEN BYTES, NOT TWELVE, and a reader arriving from the
        //       account master will expect twelve. PIC S9(09)V99 declares nine integer positions and
        //       two fractional ones, and a signed DISPLAY field carries its sign as an OVERPUNCH ON THE
        //       LOW-ORDER DIGIT rather than in a byte of its own -- so the width is nine plus two and
        //       there is no twelfth position. The account master's money is PIC S9(10)V99, twelve bytes,
        //       and the two records meet in one statement at app/cbl/CBTRN02C.cbl:403-405. Deriving the
        //       width from the shared codec's own rule rather than writing 11 as a literal is what makes
        //       this an assertion about the rule instead of a restatement of a number.
        requireFieldGeometry(DALYTRAN_AMT, EXPECTED_AMOUNT_OFFSET,
                ZonedDecimalCodec.widthOf(AMOUNT_INTEGER_DIGITS, AMOUNT_DECIMAL_DIGITS),
                "a signed zoned field overpunches its sign onto the low-order digit, so S9(09)V99"
                        + " occupies nine plus two positions and not twelve");
        requireFieldGeometry(DALYTRAN_CARD_NUM, EXPECTED_CARD_NUM_OFFSET, EXPECTED_CARD_NUM_LENGTH,
                "app/cbl/CBTRN02C.cbl:382 reads the cross-reference by this span, and the"
                        + " identically-shaped posted record is corroborated at one-based position 263"
                        + " by app/jcl/TRANREPT.jcl:41");
        requireFieldGeometry(DALYTRAN_ORIG_TS, EXPECTED_ORIG_TS_OFFSET,
                TimestampFormatter.TIMESTAMP_LENGTH,
                "app/cbl/CBTRN02C.cbl:414 reads the first ten characters of this span as the"
                        + " transaction date when it decides reason code 103");
        requireFieldGeometry(DALYTRAN_PROC_TS, EXPECTED_PROC_TS_OFFSET,
                TimestampFormatter.TIMESTAMP_LENGTH,
                "app/cbl/CBTRN02C.cbl:438 writes the posted record's stamp at this offset, and the"
                        + " feed leaves this span blank on every record of"
                        + " app/data/ASCII/dailytran.txt");
    }

    /**
     * Asserts that one named field occupies the offset and width this class depends on.
     *
     * @param fieldName the String copybook name of the field to check, which must be declared by the
     *     registered specification
     * @param expectedStart the int zero-based offset the field is asserted to begin at
     * @param expectedLength the int byte count the field is asserted to occupy
     * @param corroboration the String naming the independent artifact that agrees with those numbers,
     *     carried into the failure so a reader can check the disagreement against a second source
     * @throws RecordMappingException if the registered descriptor's offset or length differs from the
     *     asserted values
     */
    private static void requireFieldGeometry(String fieldName, int expectedStart, int expectedLength,
            String corroboration) {
        CopybookLayout.FieldSpec field = LAYOUT.field(fieldName);
        if (field.start() != expectedStart || field.length() != expectedLength) {
            throw new RecordMappingException("layout " + REGISTRY_NAME + " must place " + fieldName
                    + " at zero-based offset " + expectedStart + " for " + expectedLength
                    + " bytes, but it declares " + field.describe() + "; " + corroboration);
        }
    }

    /**
     * One decoded feed record, paired with the verbatim image it was decoded from.
     *
     * <p>Assumptions: the pairing exists because the reject stream needs bytes the entity cannot
     * reproduce. {@code app/cbl/CBTRN02C.cbl:447} moves the whole record group into the reject
     * prefix, so that prefix retains the record's own trailing pad and its own processing-timestamp
     * span; an entity carries neither, since the pad reaches no member at all and a timestamp member
     * carries an instant rather than the characters that expressed it. Holding the two together means
     * the reject path never has to choose between re-encoding and reaching back to whatever read the
     * record.</p>
     *
     * <p>Alternatives Considered: a Java {@code record} declaration, which is the shorter way to write
     * a two-component carrier and is what this migration prefers wherever brevity is wanted. Rejected
     * for one specific reason: a record's generated accessor would hand out the array itself, and its
     * generated equality would compare that array by reference. The first makes the image mutable
     * through any holder of this object, which defeats the word verbatim; the second offers an
     * equality that looks value-based and is not, which is worse than offering none. Copying in the
     * constructor and again in the accessor costs two copies of 350 bytes per record and buys an image
     * no caller can alter.</p>
     */
    public static final class DecodedRecord {

        /** The entity carrying the thirteen mapped fields, with the trailing pad dropped. */
        private final DailyTransaction entity;

        /** The 350-byte image the entity was decoded from, held as a private copy. */
        private final byte[] sourceImage;

        /**
         * Pairs a decoded entity with a private copy of the image it came from.
         *
         * @param decodedEntity the {@link DailyTransaction} the decode produced, retained as given
         * @param image the byte array the decode read, of the record's declared length; it is copied
         *     rather than retained, so a later change by the caller cannot alter what this object
         *     reports as the verbatim record
         */
        private DecodedRecord(DailyTransaction decodedEntity, byte[] image) {
            this.entity = decodedEntity;
            this.sourceImage = image.clone();
        }

        /**
         * Returns the decoded feed entity.
         *
         * @return the {@link DailyTransaction} carrying the thirteen mapped fields, never {@code null}
         */
        public DailyTransaction entity() {
            return entity;
        }

        /**
         * Returns the record image exactly as it was read, byte for byte.
         *
         * <p>Assumptions: this is the value the reject path carries forward, and it is the image rather
         * than a rendering of it. Both regions that a re-encode would change are intact here -- the
         * trailing pad exactly as the source held it, and each timestamp span in whichever of the two
         * producer forms the source used -- which is what keeps a reject image comparable byte for byte
         * against a committed expectation.</p>
         *
         * @return a new byte array of the record's declared length holding a copy of the decoded image,
         *     so a caller may modify the returned array without affecting this object or any other
         *     caller
         */
        public byte[] sourceImage() {
            // WHY : Assumptions: a copy is returned rather than the field, because an array field
            //       handed out directly is shared mutable state -- one caller assembling a reject
            //       record in place would silently alter what the next caller reads as the verbatim
            //       image. Arrays.copyOf is used rather than clone only because the intent to copy
            //       reads more plainly at a call site that is about defending an invariant.
            return Arrays.copyOf(sourceImage, sourceImage.length);
        }
    }

    /**
     * Reports a record this mapper cannot translate without inventing or discarding content.
     *
     * <p>Assumptions: it extends {@code IllegalArgumentException} so that it joins the family the
     * shared codecs already raise -- {@code FixedWidthCodec.FieldCodecException},
     * {@code FixedWidthCodec.RecordLengthException} and
     * {@code ZonedDecimalCodec.ZonedDecimalException} are all unchecked argument failures -- so a
     * caller that wants to treat every malformed record alike can catch one type. A distinct type is
     * still declared rather than raising the base class, because the failures this mapper adds are the
     * ones the codec cannot see: an entity whose key is absent, a code whose width would change
     * meaning, a timestamp span in neither producer's form, and a projection with no processing
     * instant.</p>
     *
     * <p>Alternatives Considered: reusing the sibling {@code TransactionRecordMapper}'s failure type
     * rather than declaring one here. Rejected because that type's constructors are private to it, by
     * its own design, so every message it carries has been sanitised by the class that raised it.
     * Widening them to admit this class would let any caller anywhere fabricate a failure attributed
     * to that mapper, which removes the guarantee that a message bearing its name came from it.</p>
     *
     * <p>Assumptions: nothing this type carries may quote a primary account number, so every message
     * handed to it is either a field descriptor or a rendering from which the characters of a
     * sensitive field have already been withheld.</p>
     */
    public static final class RecordMappingException extends IllegalArgumentException {

        // WHY : Assumptions: the platform expects a serial version identifier on a serialisable type,
        //       and this one inherits serialisability from the exception hierarchy. Pinning the value
        //       stops the compiler deriving one that changes whenever a member is added, which is what
        //       keeps a serialised instance readable across builds. The shared codecs pin theirs the
        //       same way.
        private static final long serialVersionUID = 1L;

        /**
         * Creates a failure carrying an already-sanitised description.
         *
         * @param message the String describing what could not be translated; the caller is responsible
         *     for having withheld or omitted any sensitive content, because this constructor performs
         *     no sanitisation of its own
         */
        private RecordMappingException(String message) {
            super(message);
        }

        /**
         * Creates a failure carrying an already-sanitised description and its underlying cause.
         *
         * @param message the String describing what could not be translated, sanitised by the caller
         * @param cause the Throwable that revealed the problem, retained so a parse position is not
         *     lost; it is attached only for fields the layout does not mark sensitive
         */
        private RecordMappingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
