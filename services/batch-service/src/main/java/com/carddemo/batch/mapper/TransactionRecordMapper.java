package com.carddemo.batch.mapper;

import com.carddemo.batch.domain.Transaction;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.time.TimestampFormatter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// WHY : Assumptions: these class-level rationales sit above the Javadoc rather than between it and
//       the declaration, matching the sibling com.carddemo.batch.domain.Transaction. Keeping the
//       Javadoc immediately adjacent to the declaration preserves the association that Checkstyle's
//       type-documentation gate reads, while this uninterrupted rationale block still belongs to the
//       same declaration.
//
// WHY : Trade-offs: THIS MAPPER IS BIDIRECTIONAL, where most persistence mapping is one-way. The
//       decode direction alone would serve every read this module performs, so the encode direction
//       is the one that needs justifying, and it is a parity requirement rather than a convenience.
//       Two migrated flows have to reproduce a baseline dataset BYTE FOR BYTE. app/jcl/TRANBKP.jcl
//       lines 26 to 33 copy the transaction master into a new generation of TRANSACT.BKUP with
//       IDCAMS REPRO at DCB=(LRECL=350,RECFM=FB), so the backup is a byte image of the master and
//       not a re-rendering of it. app/jcl/COMBTRAN.jcl lines 27 to 30 then declare the DFSORT
//       symbol TRAN-ID,1,16,CH and SORT FIELDS=(TRAN-ID,A) over a concatenation of two such
//       generations, and line 48 REPROs the sorted result straight back into the master -- so those
//       350-byte images survive a sort and a reload unchanged. A decode-only mapper would leave the
//       migrated backup and combine flows with no way to emit that width and that field placement,
//       and the committed parity expectations under tests/golden/posting compare the emitted bytes
//       position by position rather than field by field. The cost accepted is a second direction to
//       keep correct, and the round-trip assertion in this mapper's test is what holds the two
//       directions to each other.
//
// WHY : Assumptions: the card number sits at zero-based offset 262 and the processing timestamp at
//       304, and those two offsets in particular are beyond doubt because THREE UNRELATED SOURCES
//       agree on them. First, summing the declared widths of app/cpy/CVTRA05Y.cpy lines 5 to 18 --
//       with a zoned S9(09)V99 occupying 11 bytes in DISPLAY usage -- gives
//       0, 16, 18, 22, 32, 132, 143, 152, 202, 252, 262, 278, 304, 330, reconciling to the 350 that
//       line 2 of that copybook declares as RECLN. Second, app/jcl/TRANREPT.jcl lines 41 and 42
//       declare the same two fields to the sort utility as TRAN-CARD-NUM,263,16,ZD and
//       TRAN-PROC-DT,305,10,CH, and DFSORT positions are ONE-based, so zeroBased = oneBased - 1
//       yields 262 and 304. Third, app/jcl/TRANIDX.jcl line 27 defines a VSAM alternate index over
//       this record as KEYS(26 304) -- a 26-byte key at zero-based offset 304, which is exactly the
//       processing timestamp span. A disagreement with any one of the three is therefore a defect in
//       the reader's arithmetic rather than in the sources, and the geometry guard below asserts
//       both numbers at class load so that a drifting descriptor cannot pass silently.
//
// WHY : Assumptions: a fourth source agrees on the record key. app/jcl/TRANBKP.jcl line 58 defines
//       the master cluster with KEYS(16 0) -- a 16-byte key at zero-based offset 0 -- which is
//       TRAN-ID occupying the record prefix. That prefix position is what makes the combine flow's
//       ascending sort on a 16-byte character field at one-based position 1 the same ordering as an
//       ORDER BY on the primary key, so the significance of TRAN-ID sitting first is recorded here
//       rather than left to be rediscovered from the sort control cards.
//
// WHY : Assumptions: app/jcl/TRANREPT.jcl line 42 sorts the processing date as a CHARACTER field of
//       length 10 taken from the head of the 26-byte timestamp, which is sound only because the
//       leading ten characters are ISO ordered and therefore compare lexically the way they compare
//       chronologically. That is the reporting service's concern rather than this mapper's, and it
//       is recorded because it establishes that the first ten characters of these spans are relied
//       upon as a date elsewhere in the migration -- so a mapper that reordered or re-punctuated
//       them would break a comparison in another module.

/**
 * Translates the 350-byte posted-transaction record of {@code app/cpy/CVTRA05Y.cpy} to and from
 * {@link Transaction}, in both directions.
 *
 * <p>This is one of the record-shaped boundaries the package charter governs, and the only one in
 * this module whose arrow points both ways. Read
 * {@code com.carddemo.batch.mapper.package-info} first: every ruling it states -- that
 * representation concerns appear here and nowhere else downstream, that {@code FILLER} is dropped on
 * decode and padded back on encode, that masking belongs to this layer rather than to the codec, and
 * that a diagnostic naming a sensitive field reports its descriptor rather than its bytes -- applies
 * to this type without restatement.</p>
 *
 * <h2>The field set, and where each byte goes</h2>
 *
 * <p>Assumptions: the copybook is normative under the migration plan's transformation rule T1, so a
 * field's {@code PICTURE} clause decides its offset, its width and its Java type. The geometry is
 * never written out as literals here: both layouts are resolved from the registry in
 * {@code com.carddemo.common.codec.CopybookLayout}, which is the single declaration every consumer
 * of this record shares.</p>
 *
 * <table border="1">
 *   <caption>Derivation from {@code app/cpy/CVTRA05Y.cpy}, with the entity member each field feeds
 *       and whether trailing blanks are trimmed</caption>
 *   <tr><th>Copybook field</th><th>Line</th><th>Offset</th><th>Len</th><th>PICTURE</th>
 *       <th>Kind</th><th>Entity member</th><th>Trimmed</th></tr>
 *   <tr><td>{@code TRAN-ID}</td><td>5</td><td>0</td><td>16</td><td>{@code X(16)}</td>
 *       <td>{@code TEXT}</td><td>{@code transactionId}</td><td>no</td></tr>
 *   <tr><td>{@code TRAN-TYPE-CD}</td><td>6</td><td>16</td><td>2</td><td>{@code X(02)}</td>
 *       <td>{@code TEXT}</td><td>{@code typeCd}</td><td>no</td></tr>
 *   <tr><td>{@code TRAN-CAT-CD}</td><td>7</td><td>18</td><td>4</td><td>{@code 9(04)}</td>
 *       <td>{@code UINT}</td><td>{@code categoryCd}</td><td>no</td></tr>
 *   <tr><td>{@code TRAN-SOURCE}</td><td>8</td><td>22</td><td>10</td><td>{@code X(10)}</td>
 *       <td>{@code TEXT}</td><td>{@code source}</td><td>yes</td></tr>
 *   <tr><td>{@code TRAN-DESC}</td><td>9</td><td>32</td><td>100</td><td>{@code X(100)}</td>
 *       <td>{@code TEXT}</td><td>{@code description}</td><td>yes</td></tr>
 *   <tr><td>{@code TRAN-AMT}</td><td>10</td><td>132</td><td>11</td><td>{@code S9(09)V99}</td>
 *       <td>{@code ZONED}</td><td>{@code amount}</td><td>n/a</td></tr>
 *   <tr><td>{@code TRAN-MERCHANT-ID}</td><td>11</td><td>143</td><td>9</td><td>{@code 9(09)}</td>
 *       <td>{@code UINT}</td><td>{@code merchantId}</td><td>n/a</td></tr>
 *   <tr><td>{@code TRAN-MERCHANT-NAME}</td><td>12</td><td>152</td><td>50</td><td>{@code X(50)}</td>
 *       <td>{@code TEXT}</td><td>{@code merchantName}</td><td>yes</td></tr>
 *   <tr><td>{@code TRAN-MERCHANT-CITY}</td><td>13</td><td>202</td><td>50</td><td>{@code X(50)}</td>
 *       <td>{@code TEXT}</td><td>{@code merchantCity}</td><td>yes</td></tr>
 *   <tr><td>{@code TRAN-MERCHANT-ZIP}</td><td>14</td><td>252</td><td>10</td><td>{@code X(10)}</td>
 *       <td>{@code TEXT}</td><td>{@code merchantZip}</td><td>yes</td></tr>
 *   <tr><td>{@code TRAN-CARD-NUM}</td><td>15</td><td>262</td><td>16</td><td>{@code X(16)}</td>
 *       <td>{@code TEXT}</td><td>{@code cardNum}</td><td>no</td></tr>
 *   <tr><td>{@code TRAN-ORIG-TS}</td><td>16</td><td>278</td><td>26</td><td>{@code X(26)}</td>
 *       <td>{@code TEXT}</td><td>{@code origTs}</td><td>n/a</td></tr>
 *   <tr><td>{@code TRAN-PROC-TS}</td><td>17</td><td>304</td><td>26</td><td>{@code X(26)}</td>
 *       <td>{@code TEXT}</td><td>{@code procTs}</td><td>n/a</td></tr>
 *   <tr><td>{@code FILLER}</td><td>18</td><td>330</td><td>20</td><td>{@code X(20)}</td>
 *       <td>{@code TEXT}</td><td>dropped</td><td>n/a</td></tr>
 * </table>
 *
 * <h2>Byte identity is load-bearing, and what the round trip does and does not promise</h2>
 *
 * <p>Assumptions: the image {@link #toRecord(Transaction)} returns is compared BYTE-WISE against the
 * committed parity expectations at {@code tests/golden/posting/*}{@code /tranfile.expected}, so
 * every decision in the encode direction is observable at the level of a single character position.
 * Those files are reference-only under the migration plan's section 0.2.2: they are read as the
 * oracle, never rewritten, and the reference suite's own pins are never moved.</p>
 *
 * <p>Assumptions: {@code toRecord(toEntity(image))} reproduces {@code image} exactly whenever three
 * conditions hold, and each of the three has a named cause rather than being a general caveat.
 * First, the trailing {@code FILLER} is blank, because the entity carries no member for those 20
 * bytes and the encode direction rebuilds them as blanks. Second, the amount is not a NEGATIVE ZERO:
 * {@code com.carddemo.common.codec.ZonedDecimalCodec} emits the canonical positive-zero overpunch
 * for every zero, since the target decimal type has no negative zero to preserve, and that trailing
 * character is the one span the codec documents as not round-tripping. Third, both timestamps are
 * already in the target 26-character form, for the reason the next section gives. The overload
 * {@link #toRecord(Transaction, Layout, byte[])} removes all three conditions by restoring those
 * exact byte regions from the image the entity was decoded from.</p>
 *
 * <h2>Two registry layouts for one physical shape</h2>
 *
 * <p>Assumptions: {@code TRAN} and {@code INTTRAN} are both 350 bytes with identical field
 * placement, and the second is not redundant. Two migrated jobs write this shape to two different
 * baseline destinations. {@code app/cbl/CBTRN02C.cbl:562-564} writes it to the transaction master,
 * copying the origination stamp straight through from the daily feed at
 * {@code app/cbl/CBTRN02C.cbl:436} and generating only the processing stamp at
 * {@code app/cbl/CBTRN02C.cbl:437-438}. {@code app/cbl/CBACT04C.cbl:500} writes it instead to a new
 * generation of a separate sequential dataset that {@code app/jcl/INTCALC.jcl:37-41} mounts at
 * {@code LRECL=350}, and there a single timestamp read at {@code app/cbl/CBACT04C.cbl:496} is moved
 * into BOTH stamps at lines 497 and 498. So the origination stamp is deterministic feed content in
 * one producer and a machine-generated clock read in the other, which is precisely the difference
 * the registry expresses by flipping the {@code normalizeTs} flag on {@code TRAN-ORIG-TS} for
 * {@code INTTRAN}. {@link Layout} is how a caller states which producer it is speaking for.</p>
 *
 * <p>Assumptions: the flag MARKS a field and normalises nothing, so this mapper's byte behaviour is
 * identical under both layouts. That is worth stating plainly, because it makes the choice look
 * cosmetic when it is not: a parity comparator reads the flag to decide which spans to blank before
 * a byte-wise comparison, and a caller that selected the wrong layout would have a comparator
 * normalise a deterministic field or compare a non-deterministic one.</p>
 *
 * <p>Assumptions: {@code TRNX} IS A DIFFERENT LAYOUT and is deliberately unreachable from here. The
 * registry's {@code TRNX} entry is the statement-input shape derived from {@code COSTM01}, and
 * although it is also 350 bytes its geometry differs -- its card number occupies the record prefix
 * and its identifier follows -- so decoding this record under it would place every field wrongly
 * while still consuming exactly 350 bytes. Separately, and confusingly,
 * {@code app/cbl/CBIMPORT.cbl:58} names a transaction output {@code TRNXOUT} that carries THIS
 * {@code CVTRA05Y} shape; the resemblance between that data-definition name and the {@code TRNX}
 * layout name is a coincidence and not an alias. {@link Layout} is a closed set of two constants for
 * exactly this reason: it makes wiring the wrong specification a compile error rather than a
 * plausible-looking record.</p>
 *
 * <h2>What this mapper deliberately does not do</h2>
 *
 * <p>Assumptions: no repository is reached, no transaction is opened, no money arithmetic is
 * performed and no identifier is generated. Composing a generated transaction identifier from a
 * business-date token and a sequence -- which
 * {@code app/cbl/CBACT04C.cbl:476-480} does with {@code STRING PARM-DATE, WS-TRANID-SUFFIX
 * DELIMITED BY SIZE INTO TRAN-ID} -- belongs to the interest service; this mapper only carries the
 * resulting sixteen characters. Money arithmetic belongs to
 * {@code com.carddemo.common.money.Money}, and where an amount crosses a JSON boundary it is
 * serialised as a STRING by {@code com.carddemo.common.money.MoneyModule} rather than by anything
 * declared here, because a JSON number is parsed into a binary floating-point value by most clients
 * and a balance wrong in the cents still looks entirely plausible.</p>
 *
 * <p>Assumptions: the two secondary indexes this record is read through --
 * {@code idx_transactions_card_num} and {@code idx_transactions_proc_ts} -- are declared by
 * {@code transaction-service}'s own migration and are not declared, requested or assumed mutable
 * here. This module reaches {@code ledger.transactions} under a grant narrower than ownership, as
 * {@link Transaction} sets out.</p>
 */
public final class TransactionRecordMapper {

    /** Copybook name of the record key at {@code app/cpy/CVTRA05Y.cpy:5}, offset 0, 16 bytes. */
    private static final String TRAN_ID = "TRAN-ID";

    /** Copybook name of the type code at {@code app/cpy/CVTRA05Y.cpy:6}, offset 16, 2 bytes. */
    private static final String TRAN_TYPE_CD = "TRAN-TYPE-CD";

    /** Copybook name of the category code at {@code app/cpy/CVTRA05Y.cpy:7}, offset 18, 4 bytes. */
    private static final String TRAN_CAT_CD = "TRAN-CAT-CD";

    /** Copybook name of the source label at {@code app/cpy/CVTRA05Y.cpy:8}, offset 22, 10 bytes. */
    private static final String TRAN_SOURCE = "TRAN-SOURCE";

    /** Copybook name of the description at {@code app/cpy/CVTRA05Y.cpy:9}, offset 32, 100 bytes. */
    private static final String TRAN_DESC = "TRAN-DESC";

    /** Copybook name of the signed amount at {@code app/cpy/CVTRA05Y.cpy:10}, offset 132, 11 bytes. */
    private static final String TRAN_AMT = "TRAN-AMT";

    /** Copybook name of the merchant identifier at {@code app/cpy/CVTRA05Y.cpy:11}, offset 143. */
    private static final String TRAN_MERCHANT_ID = "TRAN-MERCHANT-ID";

    /** Copybook name of the merchant name at {@code app/cpy/CVTRA05Y.cpy:12}, offset 152, 50 bytes. */
    private static final String TRAN_MERCHANT_NAME = "TRAN-MERCHANT-NAME";

    /** Copybook name of the merchant city at {@code app/cpy/CVTRA05Y.cpy:13}, offset 202, 50 bytes. */
    private static final String TRAN_MERCHANT_CITY = "TRAN-MERCHANT-CITY";

    /** Copybook name of the merchant postal code at {@code app/cpy/CVTRA05Y.cpy:14}, offset 252. */
    private static final String TRAN_MERCHANT_ZIP = "TRAN-MERCHANT-ZIP";

    /** Copybook name of the card number at {@code app/cpy/CVTRA05Y.cpy:15}, offset 262, 16 bytes. */
    private static final String TRAN_CARD_NUM = "TRAN-CARD-NUM";

    /** Copybook name of the origination stamp at {@code app/cpy/CVTRA05Y.cpy:16}, offset 278. */
    private static final String TRAN_ORIG_TS = "TRAN-ORIG-TS";

    /** Copybook name of the processing stamp at {@code app/cpy/CVTRA05Y.cpy:17}, offset 304. */
    private static final String TRAN_PROC_TS = "TRAN-PROC-TS";

    /** Copybook name of the trailing pad at {@code app/cpy/CVTRA05Y.cpy:18}, offset 330, 20 bytes. */
    private static final String FILLER = "FILLER";

    /**
     * Zero-based offset the card number is asserted to occupy, corroborated three ways above.
     *
     * <p>Assumptions: this constant exists to be CHECKED against the registry rather than to be used
     * as an offset. Nothing in this class slices a span with it; the geometry guard compares it with
     * the descriptor the registry publishes, so a descriptor that drifted away from the three
     * agreeing sources fails at class load instead of decoding a shifted record.</p>
     */
    private static final int EXPECTED_CARD_NUM_OFFSET = 262;

    /**
     * Zero-based offset the processing stamp is asserted to occupy, corroborated three ways above.
     *
     * <p>Assumptions: as with the card-number offset, this is an assertion target and not a slice
     * index. {@code app/jcl/TRANIDX.jcl:27} builds a 26-byte alternate-index key at this offset, so
     * a drift here would silently change which bytes a migrated secondary access path stands for.</p>
     */
    private static final int EXPECTED_PROC_TS_OFFSET = 304;

    /**
     * Byte width the card number is asserted to occupy, from {@code PIC X(16)}.
     *
     * <p>Assumptions: {@code app/jcl/TRANREPT.jcl:41} declares the same 16 to the sort utility, so the
     * width is corroborated by the same artifact that corroborates the offset.</p>
     */
    private static final int EXPECTED_CARD_NUM_LENGTH = 16;

    /** Zero-based offset the signed amount is asserted to occupy, from the width summation above. */
    private static final int EXPECTED_AMOUNT_OFFSET = 132;

    /** Integer digit positions the amount declares, from {@code PIC S9(09)V99}. */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Fractional digit positions the amount declares, from {@code PIC S9(09)V99}. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /** Declared record length, stated as {@code RECLN = 350} at {@code app/cpy/CVTRA05Y.cpy:2}. */
    private static final int RECORD_LENGTH = 350;

    /** The single character COBOL pads a {@code PIC X(n)} field with, and the only one trimmed here. */
    private static final char BLANK = ' ';

    /**
     * The 26-character timestamp form the two baseline producers write.
     *
     * <p>Assumptions: the pattern is transcribed from the record layout itself rather than guessed.
     * {@code app/cbl/CBTRN02C.cbl:149} labels the field {@code EEEE-MM-DD-UU.MM.SS.HH0000} and the
     * overlay at {@code app/cbl/CBTRN02C.cbl:160-174} decomposes it: four year characters, a hyphen,
     * two month, a hyphen, two day, A THIRD HYPHEN WHERE THE TARGET FORM HAS A SPACE, two hour, a
     * dot where the target form has a colon, two minute, a dot where the target form has a colon,
     * two second, a dot, then {@code DB2-MIL PIC 9(002)} -- only TWO fractional digits -- followed by
     * {@code DB2-REST PIC X(04)}, which
     * {@code app/cbl/CBTRN02C.cbl:701} fills with the literal {@code 0000}. Reading those last four
     * characters as fractional digits is what makes the whole six-character tail parse as
     * microseconds, so the pattern below is {@code SSSSSS} rather than {@code SS} plus a literal.</p>
     *
     * <p>Assumptions: declaring this pattern here does NOT re-derive a format the shared kernel owns,
     * and the distinction matters because the migration plan's transformation rule T2 forbids exactly
     * that. {@code com.carddemo.common.time.TimestampFormatter} owns the TARGET emission contract and
     * neither writes nor reads this one; this is the producer's own declared layout, and reading a
     * producer's layout is what this package exists to do.</p>
     *
     * <p>Trade-offs: the resolver style is strict, matching the shared formatter, so an impossible
     * date such as a thirty-first of February is refused rather than shifted into March. A lenient
     * resolver would accept it and yield a date no baseline record could have carried, which is worse
     * than a refusal because it is not detectable downstream.</p>
     */
    private static final DateTimeFormatter BASELINE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SSSSSS", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    static {
        // WHY : Assumptions: the geometry proof runs at class load, following the precedent
        //       com.carddemo.common.codec.FixedWidthCodec sets with its own registry validation. Java
        //       declarations are the only layout source in this migration, so a descriptor that
        //       drifted from the three agreeing sources would otherwise surface as a decoded record
        //       whose fields are all plausible and all shifted -- and a 350-byte record still reads as
        //       350 bytes after the shift. Failing here means no job can decode a single record
        //       against a wrong geometry.
        verifyGeometry();
    }

    /**
     * Prevents instantiation of this stateless boundary.
     *
     * <p>Alternatives Considered: a Spring-managed bean with instance methods, so a service could
     * receive this mapper by constructor injection. Rejected because the type holds no state and has
     * no collaborator to inject -- it reads two immutable registry descriptors and calls static
     * codecs -- so a bean would add a lifecycle and a wiring point without adding a seam any test
     * needs. The shared kernel takes the same line for the same reason, declaring
     * {@code CopybookLayout}, {@code FixedWidthCodec}, {@code ZonedDecimalCodec} and
     * {@code TimestampFormatter} all as final classes with private constructors and static
     * entry points.</p>
     *
     * <p>Alternatives Considered: an abstract base mapper or a generic reflective mapper shared with
     * the package's other record boundaries. Prohibited outright by the package charter: eight
     * mappers that each decode a fixed-width record look like eight instances of one pattern, and
     * factoring the pattern upward relocates each mapper's justification away from the code it
     * applies to, leaving one paragraph on a superclass that is true of none of the eight in
     * particular.</p>
     */
    private TransactionRecordMapper() {
    }

    /**
     * Which of the two registry layouts for this physical shape a call is speaking for.
     *
     * <p>Assumptions: the set is CLOSED at two constants deliberately. Both name a 350-byte layout
     * with identical field placement, and the registry holds a third 350-byte entry, {@code TRNX},
     * whose geometry is different; an open parameter of type
     * {@code com.carddemo.common.codec.CopybookLayout.RecordSpec} would let a caller hand that one in
     * and get a decode that consumed exactly 350 bytes and placed every field wrongly. Naming the two
     * admissible layouts as constants makes that mistake unwritable.</p>
     *
     * <p>Assumptions: each constant resolves its specification from the registry rather than
     * declaring fields of its own, so the instance held here is the same instance
     * {@code CopybookLayout.layout(String)} returns. That identity is not cosmetic:
     * {@code FixedWidthCodec} recognises a trailing pad descriptor only when the specification it was
     * given IS the registered one, so a hand-built copy would silently stop dropping {@code FILLER}
     * on decode and stop rebuilding it on encode.</p>
     */
    public enum Layout {

        /**
         * The transaction master written by the posting job, registered as {@code TRAN}.
         *
         * <p>Assumptions: posting copies the origination stamp through from the daily feed at
         * {@code app/cbl/CBTRN02C.cbl:436} and generates only the processing stamp, so under this
         * layout the origination stamp is deterministic feed content that a parity comparator must
         * compare rather than blank.</p>
         */
        POSTED_MASTER("TRAN"),

        /**
         * The system-transaction generation written by the interest job, registered as {@code INTTRAN}.
         *
         * <p>Assumptions: interest accrual performs one timestamp read at
         * {@code app/cbl/CBACT04C.cbl:496} and moves that same value into both stamps at lines 497
         * and 498, so under this layout the origination stamp is a clock read and carries the
         * {@code normalizeTs} marker that tells a parity comparator to blank it.</p>
         */
        INTEREST_GENERATED("INTTRAN");

        /** The registry name this constant resolves, held so diagnostics can quote it. */
        private final String registryName;

        /** The registered specification, resolved once so registry identity is preserved. */
        private final CopybookLayout.RecordSpec spec;

        /**
         * Binds one constant to its registry entry.
         *
         * @param registryName the String naming this layout in
         *     {@code com.carddemo.common.codec.CopybookLayout}'s registry; it must be a registered
         *     name, and resolution happens here so a missing entry fails at class initialisation
         *     rather than at the first record
         */
        Layout(String registryName) {
            this.registryName = registryName;
            this.spec = CopybookLayout.layout(registryName);
        }

        /**
         * Returns the registry name this layout resolves.
         *
         * @return the String name under which
         *     {@code com.carddemo.common.codec.CopybookLayout} registers this layout, which is also
         *     the name that appears in a codec diagnostic raised against it
         */
        public String registryName() {
            return registryName;
        }

        /**
         * Returns the registered record specification for this layout.
         *
         * @return the {@code com.carddemo.common.codec.CopybookLayout.RecordSpec} the registry holds,
         *     which is the same instance {@code CopybookLayout.layout(registryName())} returns, so
         *     the trailing-pad handling that depends on that identity keeps working
         */
        public CopybookLayout.RecordSpec spec() {
            return spec;
        }
    }

    /**
     * Decodes a 350-byte posted-transaction image into an entity, under the posting layout.
     *
     * <p>Assumptions: the posting layout is the default because the transaction master is the
     * destination every online and reporting read resolves against, while the interest generation is
     * an intermediate that only the combine flow consumes. A caller reading a staged
     * {@code SYSTRAN} generation states so explicitly through
     * {@link #toEntity(byte[], Layout)}.</p>
     *
     * @param record the byte array holding exactly one 350-byte record image in the fixed-width form
     *     declared by {@code app/cpy/CVTRA05Y.cpy}; it is read and never modified
     * @return a {@link Transaction} carrying the thirteen mapped fields, with the trailing pad
     *     dropped and each timestamp either parsed or left absent
     * @throws RecordMappingException if a timestamp span is in neither producer's form, or if the
     *     category span holds no usable value
     */
    public static Transaction toEntity(byte[] record) {
        return toEntity(record, Layout.POSTED_MASTER);
    }

    /**
     * Decodes a 350-byte posted-transaction image into an entity, under an explicitly named layout.
     *
     * <p>Assumptions: the two layouts place every field identically, so this method's byte behaviour
     * does not vary with the argument. The argument still matters, because the layout name travels
     * into every codec diagnostic raised while decoding, and because the {@code normalizeTs} marker
     * the two layouts disagree on is what a downstream parity comparator reads.</p>
     *
     * @param record the byte array holding exactly one 350-byte record image; it is read and never
     *     modified
     * @param layout the {@link Layout} naming which producer's registry entry applies, and therefore
     *     which specification the shared codec is given
     * @return a {@link Transaction} carrying the thirteen mapped fields
     * @throws RecordMappingException if {@code layout} is {@code null}, if a timestamp span is in
     *     neither producer's form, or if the category span holds no usable value
     */
    public static Transaction toEntity(byte[] record, Layout layout) {
        CopybookLayout.RecordSpec spec = requireLayout(layout).spec();

        // WHY : Trade-offs: the whole record is decoded in one call rather than field by field. The
        //       record-level entry point length-checks once before the first slice, so a truncated
        //       image is refused by width instead of yielding a partial map in which a money field has
        //       silently shifted; the cost is that a caller wanting one field still pays for thirteen.
        //       That cost is the right way round here, because every call site in this module needs the
        //       whole row.
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(record, spec);

        Transaction transaction = new Transaction(exactWidthText(decoded, spec, TRAN_ID));

        // WHY : Assumptions: THE TRIM RULE IS ASYMMETRIC ACROSS ADJACENT PIC X(n) FIELDS, and the
        //       asymmetry is the decision rather than an inconsistency. TRAN-ID, TRAN-TYPE-CD and
        //       TRAN-CARD-NUM are carried at their full declared width; TRAN-SOURCE, TRAN-DESC,
        //       TRAN-MERCHANT-NAME, TRAN-MERCHANT-CITY and TRAN-MERCHANT-ZIP have their trailing
        //       blanks removed. The dividing line is whether the value is COMPARED at a fixed width.
        //       The first three are: the identifier keys the row and orders the combine flow's sort,
        //       the type code is a reference-data key whose counterpart column is itself fixed width,
        //       and the card number is an index key. A trimmed value of any of the three would still
        //       equal itself and would no longer equal its blank-padded counterpart in an
        //       application-level String comparison, which is a mismatch no length check reveals. The
        //       other five are never compared, so under transformation rule T1 their trailing blanks
        //       are padding to the fixed record length rather than data. Trimming is safe for the two
        //       of those five whose physical columns are nevertheless fixed width -- the source label
        //       and the postal code -- because a CHAR column restores the padding on store, and
        //       trimming only TRAILING blanks cannot disturb the leading zero of a postal code.
        transaction.setTypeCd(exactWidthText(decoded, spec, TRAN_TYPE_CD));
        transaction.setCategoryCd(paddedDigits(decoded, spec, TRAN_CAT_CD));
        transaction.setSource(trimmedText(decoded, spec, TRAN_SOURCE));
        transaction.setDescription(trimmedText(decoded, spec, TRAN_DESC));
        transaction.setAmount(signedAmount(decoded, spec, TRAN_AMT));
        transaction.setMerchantId(unsignedValue(decoded, spec, TRAN_MERCHANT_ID));
        transaction.setMerchantName(trimmedText(decoded, spec, TRAN_MERCHANT_NAME));
        transaction.setMerchantCity(trimmedText(decoded, spec, TRAN_MERCHANT_CITY));
        transaction.setMerchantZip(trimmedText(decoded, spec, TRAN_MERCHANT_ZIP));
        transaction.setCardNum(exactWidthText(decoded, spec, TRAN_CARD_NUM));
        transaction.setOrigTs(timestampOf(decoded, spec, TRAN_ORIG_TS));
        transaction.setProcTs(timestampOf(decoded, spec, TRAN_PROC_TS));
        return transaction;
    }

    /**
     * Encodes an entity into a 350-byte posted-transaction image, under the posting layout.
     *
     * @param transaction the {@link Transaction} to render; its members supply the thirteen mapped
     *     fields and the trailing pad is rebuilt as blanks
     * @return a byte array of exactly 350 bytes in the fixed-width form declared by
     *     {@code app/cpy/CVTRA05Y.cpy}, with each timestamp rendered in the target 26-character form
     * @throws RecordMappingException if a required member is absent, if a fixed-width code is not at
     *     its declared width, or if the amount cannot be represented without discarding precision
     */
    public static byte[] toRecord(Transaction transaction) {
        return toRecord(transaction, Layout.POSTED_MASTER);
    }

    /**
     * Encodes an entity into a 350-byte posted-transaction image, under an explicitly named layout.
     *
     * <p>Assumptions: both timestamps are rendered through
     * {@code com.carddemo.common.time.TimestampFormatter}, so the output carries the TARGET
     * 26-character form and not the baseline one. That is the correct choice for the flows that read
     * rows back out of the database and have no source image to defer to. Where an image IS available
     * and the output has to match it byte for byte, {@link #toRecord(Transaction, Layout, byte[])} is
     * the entry point that preserves the original spans.</p>
     *
     * @param transaction the {@link Transaction} to render
     * @param layout the {@link Layout} naming which producer's registry entry applies
     * @return a byte array of exactly 350 bytes
     * @throws RecordMappingException if {@code layout} or {@code transaction} is {@code null}, if a
     *     required member is absent, if a fixed-width code is not at its declared width, or if the
     *     amount cannot be represented without discarding precision
     */
    public static byte[] toRecord(Transaction transaction, Layout layout) {
        CopybookLayout.RecordSpec spec = requireLayout(layout).spec();
        return FixedWidthCodec.encodeRecord(fieldMap(transaction, spec), spec);
    }

    /**
     * Encodes an entity into a 350-byte image, restoring from a source image the byte regions the
     * entity cannot carry.
     *
     * <p>Assumptions: three regions of this record survive a decode only as bytes, so an entity alone
     * cannot reproduce them, and each has a named cause. The trailing {@code FILLER} has no entity
     * member at all, so it is copied unconditionally -- and the committed parity expectations show
     * why that matters rather than being theoretical: the 20 bytes at offset 330 of
     * {@code tests/golden/posting/happy_path/tranfile.expected} are low values rather than blanks, so
     * an encode that rebuilt them as blanks would differ from the oracle in exactly those 20
     * positions. The sign carrier of a ZERO amount is copied because
     * {@code com.carddemo.common.codec.ZonedDecimalCodec} emits the canonical positive-zero overpunch
     * for every zero, the target decimal type having no negative zero to distinguish. And each
     * 26-character timestamp span is copied WHEN AND ONLY WHEN it decodes to the value the entity now
     * holds, which is the case where re-encoding would change only the form.</p>
     *
     * <p>Trade-offs: the timestamp restoration is conditional rather than unconditional, and the
     * condition is what keeps the method honest. Copying the source span unconditionally would make
     * this entry point ignore the entity's own value, so a caller that had adjusted a processing
     * stamp would silently emit the old one -- a wrong record that still matched its source. Testing
     * for equality first costs one parse of each span and means a changed value is rendered in the
     * target form while an unchanged one keeps its original bytes.</p>
     *
     * @param transaction the {@link Transaction} to render
     * @param layout the {@link Layout} naming which producer's registry entry applies
     * @param sourceImage the byte array of exactly 350 bytes the entity was decoded from; it is read
     *     and never modified, and it supplies the trailing pad, the zero-amount sign carrier and each
     *     unchanged timestamp span
     * @return a byte array of exactly 350 bytes that reproduces {@code sourceImage} wherever the
     *     entity's members still agree with it
     * @throws RecordMappingException if {@code layout} or {@code transaction} is {@code null}, if a
     *     required member is absent, if a fixed-width code is not at its declared width, or if the
     *     amount cannot be represented without discarding precision
     */
    public static byte[] toRecord(Transaction transaction, Layout layout, byte[] sourceImage) {
        CopybookLayout.RecordSpec spec = requireLayout(layout).spec();
        Map<String, Object> fields = fieldMap(transaction, spec);

        // WHY : Assumptions: the sign-preserving entry point is used rather than the plain one because
        //       it also performs the width check on the source image, so a caller that passed an image
        //       of the wrong length is told so by width rather than by an array bounds failure raised
        //       from inside the copy loop below.
        byte[] encoded = FixedWidthCodec.encodeRecordPreservingSign(fields, spec, sourceImage);

        copySpan(sourceImage, encoded, spec.field(FILLER));
        restoreUnchangedTimestamp(sourceImage, encoded, spec, TRAN_ORIG_TS, transaction.getOrigTs());
        restoreUnchangedTimestamp(sourceImage, encoded, spec, TRAN_PROC_TS, transaction.getProcTs());
        return encoded;
    }

    /**
     * Assembles the thirteen mapped field values an encode needs, in copybook declaration order.
     *
     * <p>Assumptions: the trailing pad is DELIBERATELY ABSENT from the returned map. The shared codec
     * recognises a registered trailing-pad descriptor that no caller supplied and rebuilds it with the
     * target charset's blank byte, which is what makes the emitted image exactly 350 bytes rather than
     * merely correct in its populated 330-byte prefix. Supplying the pad explicitly would work and is
     * avoided, because it would put a second opinion about what the pad contains into this module.</p>
     *
     * @param transaction the {@link Transaction} whose members supply the field values
     * @param spec the {@code CopybookLayout.RecordSpec} in force, supplying each field's declared
     *     width and the
     *     descriptor a failure quotes
     * @return a Map of field values keyed by copybook name, holding thirteen entries in declaration
     *     order and no entry for the trailing pad
     * @throws RecordMappingException if {@code transaction} is {@code null}, if the record key or the
     *     amount is absent, or if a fixed-width code is present at some width other than its declared
     *     one
     */
    private static Map<String, Object> fieldMap(Transaction transaction,
            CopybookLayout.RecordSpec spec) {
        if (transaction == null) {
            throw new RecordMappingException("layout " + spec.name()
                    + " cannot be encoded from an absent transaction");
        }

        // WHY : Trade-offs: insertion order is preserved so that a diagnostic listing the supplied keys
        //       reads in copybook order, which is the order a reader compares against the copybook and
        //       against a hex dump of the image. A plain hash map would encode identically, because the
        //       codec walks the SPECIFICATION rather than the map, so the only thing given up by not
        //       choosing one is a negligible amount of allocation.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(TRAN_ID, encodedKey(transaction.getTransactionId(), spec.field(TRAN_ID)));
        fields.put(TRAN_TYPE_CD,
                encodedFixedWidth(transaction.getTypeCd(), spec.field(TRAN_TYPE_CD)));
        fields.put(TRAN_CAT_CD,
                encodedDigits(transaction.getCategoryCd(), spec.field(TRAN_CAT_CD)));
        fields.put(TRAN_SOURCE, encodedDescriptive(transaction.getSource()));
        fields.put(TRAN_DESC, encodedDescriptive(transaction.getDescription()));
        fields.put(TRAN_AMT, encodedAmount(transaction.getAmount(), spec.field(TRAN_AMT)));
        fields.put(TRAN_MERCHANT_ID, encodedMerchantId(transaction.getMerchantId()));
        fields.put(TRAN_MERCHANT_NAME, encodedDescriptive(transaction.getMerchantName()));
        fields.put(TRAN_MERCHANT_CITY, encodedDescriptive(transaction.getMerchantCity()));
        fields.put(TRAN_MERCHANT_ZIP, encodedDescriptive(transaction.getMerchantZip()));
        fields.put(TRAN_CARD_NUM,
                encodedFixedWidth(transaction.getCardNum(), spec.field(TRAN_CARD_NUM)));
        fields.put(TRAN_ORIG_TS, encodedTimestamp(transaction.getOrigTs()));
        fields.put(TRAN_PROC_TS, encodedTimestamp(transaction.getProcTs()));
        return fields;
    }

    /**
     * Validates the record key and returns it at its declared width.
     *
     * @param value the String the entity holds for the record key, which may be {@code null}
     * @param field the {@code CopybookLayout.FieldSpec} descriptor of the key field, supplying its
     *     declared width and the text a failure
     *     quotes
     * @return the same String, guaranteed to be exactly the declared width
     * @throws RecordMappingException if the key is absent or empty
     */
    private static String encodedKey(String value, CopybookLayout.FieldSpec field) {
        if (value == null || value.isEmpty()) {
            // WHY : Assumptions: an absent key is refused rather than blank-filled, unlike every other
            //       character field here. app/cbl/CBTRN02C.cbl:34-37 selects the master ORGANIZATION IS
            //       INDEXED with this field as its whole RECORD KEY, and app/jcl/TRANBKP.jcl:58 defines
            //       the cluster as KEYS(16 0), so a blank key is not an unpopulated field -- it is a
            //       record that cannot be loaded, and app/jcl/COMBTRAN.jcl:30 would additionally sort
            //       every such record to the front of the combined extract.
            throw new RecordMappingException("field " + field.describe()
                    + " is the whole record key and must be present, but the entity holds "
                    + (value == null ? "no value" : "an empty value"));
        }
        return encodedFixedWidth(value, field);
    }

    /**
     * Validates a fixed-width code and returns it, or blank-fills it when the entity holds no value.
     *
     * @param value the String the entity holds, which may be {@code null} or empty
     * @param field the {@code CopybookLayout.FieldSpec} descriptor, supplying the declared width,
     *     the sensitivity marking
     *     and the text a failure quotes
     * @return the same String when it is exactly the declared width, or an empty String when the entity
     *     holds no value, which the codec then renders as a blank-filled span
     * @throws RecordMappingException if the value is present at some width other than the declared one
     */
    private static String encodedFixedWidth(String value, CopybookLayout.FieldSpec field) {
        if (value == null || value.isEmpty()) {
            // WHY : Assumptions: a character span has no representation for absent, so an unpopulated
            //       member becomes blanks rather than raising. That is the baseline's own convention:
            //       app/cbl/CBACT04C.cbl:492-494 moves SPACES into three of this record's character
            //       fields on every generated interest transaction. Returning an empty string rather
            //       than a run of blanks lets the shared codec pad to the declared width with the target
            //       charset's own blank byte, so this method never needs to know that byte.
            return "";
        }
        if (value.length() != field.length()) {
            // WHY : Trade-offs: a code of the WRONG WIDTH is refused rather than padded or truncated,
            //       and the alternative is worse in a way no length check downstream would catch.
            //       Blank-padding a short code produces a span that is a DIFFERENT value to every
            //       fixed-width comparison -- a type code of '1' padded to two characters is not
            //       the '01'
            //       that app/cbl/CBACT04C.cbl:482 writes and that the reference-data key is
            //       declared at --
            //       and truncating a long one silently discards a digit. Refusing costs a caller an
            //       explicit correction and buys a guarantee that every emitted code compares as the
            //       baseline's does.
            // WHY : Trade-offs: THE CARD NUMBER IS MASKED HERE WHILE THE ENTITY CARRIES IT IN FULL, and
            //       the two are not in tension. The migration plan places primary account number masking
            //       at the mapping layer, which is this one, and the full value has to be carried on the
            //       entity because card_num is a real column with a secondary index over it and it is
            //       one of the sixteen-character spans the parity expectations compare byte for byte --
            //       so a masked value stored there would fail parity rather than harden anything. A
            //       DIAGNOSTIC has neither of those needs: the field descriptor locates the defect and
            //       the last four digits are enough to recognise which record raised it, while a batch
            //       job raising one line per record would otherwise deposit an entire feed's card
            //       numbers into a log that outlives the incident. The shared codec deliberately does no
            //       masking of its own, because a codec that masked could not round-trip, so this is the
            //       receiving end of that division and not a duplicate of it.
            throw new RecordMappingException("field " + field.describe() + " must be exactly "
                    + field.length() + " characters so that its fixed-width comparisons still match,"
                    + " but the entity holds " + value.length() + " in "
                    + forDiagnostic(value, field));
        }
        return value;
    }

    /**
     * Validates a numeric-display code and returns the zero-padded digits its span must carry.
     *
     * @param value the String the entity holds, expected to be decimal digits, which may be
     *     {@code null}
     * @param field the {@code CopybookLayout.FieldSpec} descriptor, supplying the declared width
     *     and the text a failure
     *     quotes
     * @return the String of exactly the declared width, being the digits left-padded with zeros
     * @throws RecordMappingException if the value is absent, empty, longer than the declared width or
     *     contains a character that is not a decimal digit
     */
    private static String encodedDigits(String value, CopybookLayout.FieldSpec field) {
        if (value == null || value.isEmpty()) {
            // WHY : Assumptions: unlike a character span, a numeric-display span has no blank form the
            //       decode direction could read back -- the shared codec rejects any non-digit in it
            //       outright -- so blank-filling an absent value would emit a record this mapper could
            //       not itself decode. Refusing is the only option that keeps the two directions
            //       consistent.
            throw new RecordMappingException("field " + field.describe()
                    + " is an unsigned numeric-display span with no blank form, so it must be present,"
                    + " but the entity holds "
                    + (value == null ? "no value" : "an empty value"));
        }
        for (int index = 0; index < value.length(); index++) {
            char candidate = value.charAt(index);
            if (candidate < '0' || candidate > '9') {
                // WHY : Trade-offs: a non-digit is REFUSED rather than coerced, and this is where a
                //       two-character reading of the category code is caught. A mapper that had carried
                //       app/cbl/CBACT04C.cbl:483's literal across as '05' would present '05' followed by
                //       a blank here, and coercing that -- by dropping the blank, say -- would emit a
                //       span that decoded back to the right number and hid the defect. Refusing surfaces
                //       it at the first record instead.
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
        //       CHAR(4) column stores and what every counterpart key this value joins to is declared at.
        //       Doing it here means the map handed to the codec already carries the canonical form, so a
        //       diagnostic listing that map shows what will be written rather than what was asked for.
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
        // WHY : Assumptions: no width check is applied on this side, unlike the fixed-width codes above.
        //       A descriptive field's trailing blanks are padding rather than data under transformation
        //       rule T1, so a value shorter than the declared width is the ordinary case --
        //       app/cbl/CBACT04C.cbl:485-489 writes roughly two dozen characters into a hundred-byte
        //       field -- and blank-padding it is exactly right. An OVER-long value is still refused, by
        //       the shared codec, which names the byte count it could not fit.
        return value == null ? "" : value;
    }

    /**
     * Returns the amount ready for encoding, insisting it is present.
     *
     * @param amount the BigDecimal the entity holds, which may be {@code null}
     * @param field the {@code CopybookLayout.FieldSpec} descriptor of the signed zoned field,
     *     quoted in a failure
     * @return the same BigDecimal, which the shared codec then renders losslessly or refuses
     * @throws RecordMappingException if the amount is absent
     */
    private static BigDecimal encodedAmount(BigDecimal amount, CopybookLayout.FieldSpec field) {
        if (amount == null) {
            // WHY : Assumptions: an absent amount is refused and NOT defaulted to zero, which is the one
            //       tempting default in this method. A signed zoned span has no absent form, so
            //       something
            //       has to be written; writing zero would emit a posted transaction of no value that
            //       every subsequent balance and total would accept without complaint, and the parity
            //       comparison would show a plausible record rather than a missing one. Refusing
            //       keeps an
            //       incomplete row from ever reaching a monetary total.
            throw new RecordMappingException("field " + field.describe()
                    + " is a signed zoned span with no absent form and is never defaulted to zero, but"
                    + " the entity holds no amount");
        }

        // WHY : Assumptions: the encode is LOSSLESS-OR-THROW and the enforcement is delegated
        //       rather than
        //       repeated. com.carddemo.common.codec.ZonedDecimalCodec rescales to the field's two
        //       fractional digits with RoundingMode.UNNECESSARY, so a value carrying fewer decimal
        //       places
        //       is padded -- 2065 becomes 2065.00 -- while one carrying a non-zero third place is
        //       refused
        //       rather than rounded, and it separately refuses a value needing more than the eleven
        //       digit
        //       positions this span provides. Re-checking either rule here would put a second, drifting
        //       opinion about precision next to the one the parity oracle actually depends on.
        return amount;
    }

    /**
     * Returns the merchant identifier ready for encoding, treating absent as the baseline's own zero.
     *
     * @param merchantId the Long the entity holds, which may be {@code null}
     * @return the same Long, or {@code 0} when the entity holds no value
     */
    private static Long encodedMerchantId(Long merchantId) {
        // WHY : Assumptions: absent becomes ZERO rather than raising, because zero is the value the
        //       baseline itself writes when there is no merchant: app/cbl/CBACT04C.cbl:491 issues
        //       MOVE 0 TO TRAN-MERCHANT-ID on every generated interest transaction. A nine-byte
        //       numeric-display span has no absent form, so zero is not a substitute for missing data
        //       here -- it is the encoding of "no merchant" that the decode direction reads straight
        //       back. A NEGATIVE value is still refused, by the shared codec, since an unsigned display
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
        //       form the physical TIMESTAMP(6) column and the migrated writers agree on. The BASELINE
        //       26-character form is deliberately NOT emitted from this path: the two are different byte
        //       patterns and neither is normalised into the other, so a caller that needs the original
        //       bytes back uses the byte-preserving encode instead of asking this method for them.
        // WHY : Assumptions: absent becomes 26 blanks, closing the loop with the decode direction, which
        //       reads a 26-blank span as absent. An unposted row genuinely has no processing stamp, so
        //       this pair of conversions is the record's representation of that state rather than a
        //       tolerance for missing data.
        return timestamp == null ? "" : TimestampFormatter.format(timestamp);
    }

    /**
     * Renders a field value for a diagnostic, masking it when the layout marks the field sensitive.
     *
     * @param value the String that could not be encoded, which may hold a primary account number
     * @param field the {@code CopybookLayout.FieldSpec} descriptor of the field it belongs to,
     *     whose sensitivity marking decides whether
     *     the value may be shown
     * @return a String safe to place in an exception message: the value in quotes when the field is not
     *     marked sensitive, and otherwise a rendering of the same width whose final four characters are
     *     the only ones retained
     */
    private static String forDiagnostic(String value, CopybookLayout.FieldSpec field) {
        // WHY : Assumptions: the marking is read from the descriptor rather than from a list of field
        //       names kept here, so a field that becomes sensitive in the shared layout catalogue
        //       becomes
        //       masked here without an edit. com.carddemo.common.security.CardNumberMasker performs the
        //       rendering because masking is a shared concern that must not be re-declared per
        //       service --
        //       a second implementation could disagree about how many digits it keeps, and the sibling
        //       com.carddemo.batch.domain.CardXref already resolves it the same way.
        return field.sensitive() ? CardNumberMasker.mask(value) : "'" + value + "'";
    }

    /**
     * Copies one field's span verbatim from a source image into an encoded image.
     *
     * @param source the byte array to read from, of the record's declared length
     * @param target the byte array to write into, of the same length; the span is overwritten in place
     * @param field the {@code CopybookLayout.FieldSpec} descriptor naming the offset and length of
     *     the span to copy
     */
    private static void copySpan(byte[] source, byte[] target, CopybookLayout.FieldSpec field) {
        System.arraycopy(source, field.start(), target, field.start(), field.length());
    }

    /**
     * Restores a timestamp span from a source image when the entity still holds the value it carried.
     *
     * @param source the byte array the entity was decoded from, of the record's declared length
     * @param target the byte array already encoded from the entity, whose span is overwritten only when
     *     the value has not changed
     * @param spec the {@code CopybookLayout.RecordSpec} in force, supplying the field descriptor
     * @param fieldName the String copybook name of the timestamp field to consider
     * @param current the LocalDateTime the entity now holds for that field, which may be {@code null}
     * @throws RecordMappingException if the source span is neither blank nor in either producer's
     *     26-character form, which means the image does not belong to this record
     */
    private static void restoreUnchangedTimestamp(byte[] source, byte[] target,
            CopybookLayout.RecordSpec spec, String fieldName, LocalDateTime current) {
        CopybookLayout.FieldSpec field = spec.field(fieldName);
        Object decodedSpan = FixedWidthCodec.decodeField(source, field);
        if (!(decodedSpan instanceof String span)) {
            throw typeFailure(spec, fieldName, decodedSpan, "a character value");
        }

        // WHY : Assumptions: the source span is decoded through the shared codec rather than sliced with
        //       a charset chosen here, so the character set the source is read under stays a single
        //       decision owned by that codec. The codec also verifies the slice is byte-reversible,
        //       which
        //       is what stops a span from being compared as characters it does not actually hold.
        LocalDateTime carried = stripTrailingBlanks(span).isEmpty()
                ? null
                : parseEitherForm(span, field);

        // WHY : Trade-offs: equality of the VALUE is the condition, not equality of the bytes. Comparing
        //       bytes would never match when the source carried the baseline form and the fresh encode
        //       carried the target form, which is precisely the case this restoration exists for; and
        //       copying unconditionally would emit a stamp the caller had already replaced.
        //       Comparing the
        //       parsed values costs one parse and distinguishes "same instant, different form" -- keep
        //       the source bytes -- from "different instant" -- keep the freshly rendered ones.
        boolean unchanged = carried == null ? current == null : carried.equals(current);
        if (unchanged) {
            copySpan(source, target, field);
        }
    }

    /**
     * Proves at class load that both registry layouts still carry the geometry this mapper asserts.
     *
     * <p>Assumptions: the three checks are chosen because each stands for a fact an independent
     * artifact depends on, rather than because they are cheap. The record length is what the backup
     * and combine data definitions declare; the card-number and processing-stamp offsets are the pair
     * three unrelated sources agree on, one of which builds a secondary access path from the second
     * of them; and the amount width is the eleven-versus-twelve distinction that separates this
     * record's money from the account master's.</p>
     *
     * @throws RecordMappingException if either layout's declared length, field contiguity, card-number
     *     offset, processing-stamp offset or amount width has drifted from what this mapper asserts
     */
    private static void verifyGeometry() {
        for (Layout layout : Layout.values()) {
            CopybookLayout.RecordSpec spec = layout.spec();

            // WHY : Assumptions: contiguity is re-asserted here even though the registry already
            //       validates it, because the registry's own check runs when CopybookLayout loads and
            //       this one runs when THIS class loads. The two orderings differ, and re-asserting
            //       costs one pass over fourteen descriptors while removing any dependence on which
            //       class a job happens to touch first.
            spec.validateGeometry();

            if (spec.reclen() != RECORD_LENGTH) {
                throw new RecordMappingException("layout " + layout.registryName() + " must declare "
                        + RECORD_LENGTH + " bytes, which app/cpy/CVTRA05Y.cpy:2 states as RECLN = 350"
                        + " and app/jcl/TRANBKP.jcl:31 repeats as LRECL=350, but it declares "
                        + spec.reclen());
            }

            requireFieldGeometry(layout, TRAN_CARD_NUM, EXPECTED_CARD_NUM_OFFSET,
                    EXPECTED_CARD_NUM_LENGTH,
                    "app/jcl/TRANREPT.jcl:41 declares TRAN-CARD-NUM,263,16 in ONE-based positions");
            requireFieldGeometry(layout, TRAN_PROC_TS, EXPECTED_PROC_TS_OFFSET,
                    TimestampFormatter.TIMESTAMP_LENGTH,
                    "app/jcl/TRANIDX.jcl:27 builds an alternate index as KEYS(26 304)");

            // WHY : Assumptions: THE AMOUNT SPAN IS ELEVEN BYTES, NOT TWELVE, and a reader arriving
            //       from the account master will expect twelve. PIC S9(09)V99 declares nine integer
            //       positions and two fractional ones, and a signed DISPLAY field carries its sign as
            //       an OVERPUNCH ON THE LOW-ORDER DIGIT rather than in a byte of its own -- so the
            //       width is nine plus two and there is no thirteenth position and no twelfth. The
            //       account master's money is PIC S9(10)V99, twelve bytes, for example ACCT-CURR-BAL at
            //       app/cpy/CVACT01Y.cpy:7, and the two records meet in one statement at
            //       app/cbl/CBTRN02C.cbl:547. Deriving the width here from the shared codec's own rule
            //       rather than writing 11 as a literal is what makes this an assertion about the rule
            //       instead of a restatement of a number.
            requireFieldGeometry(layout, TRAN_AMT, EXPECTED_AMOUNT_OFFSET,
                    ZonedDecimalCodec.widthOf(AMOUNT_INTEGER_DIGITS, AMOUNT_DECIMAL_DIGITS),
                    "a signed zoned field overpunches its sign onto the low-order digit, so"
                            + " S9(09)V99 occupies nine plus two positions and not twelve");
        }
    }

    /**
     * Asserts that one named field occupies the offset and width this mapper depends on.
     *
     * @param layout the {@link Layout} whose registered specification is being checked, named in any
     *     failure so a reader knows which of the two entries drifted
     * @param fieldName the String copybook name of the field to check, which must be declared by that
     *     specification
     * @param expectedStart the int zero-based offset the field is asserted to begin at
     * @param expectedLength the int byte count the field is asserted to occupy
     * @param corroboration the String naming the independent artifact that agrees with those numbers,
     *     carried into the failure so the reader can check the disagreement against a second source
     * @throws RecordMappingException if the registered descriptor's offset or length differs from the
     *     asserted values
     */
    private static void requireFieldGeometry(Layout layout, String fieldName, int expectedStart,
            int expectedLength, String corroboration) {
        CopybookLayout.FieldSpec field = layout.spec().field(fieldName);
        if (field.start() != expectedStart || field.length() != expectedLength) {
            throw new RecordMappingException("layout " + layout.registryName() + " must place "
                    + fieldName + " at zero-based offset " + expectedStart + " for " + expectedLength
                    + " bytes, but it declares " + field.describe() + "; " + corroboration);
        }
    }

    /**
     * Returns one decoded field at its full declared width, with nothing trimmed.
     *
     * @param decoded the Map of decoded field values keyed by copybook name, as the record-level
     *     decode returned it
     * @param spec the {@code com.carddemo.common.codec.CopybookLayout.RecordSpec} in force, consulted
     *     only to build a descriptor when the value is missing or of the wrong Java type
     * @param fieldName the String copybook name of the field to read
     * @return the String occupying that span, at exactly the declared width including any trailing
     *     blanks
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than a character value
     */
    private static String exactWidthText(Map<String, Object> decoded,
            CopybookLayout.RecordSpec spec, String fieldName) {
        Object value = require(decoded, spec, fieldName);
        if (!(value instanceof String text)) {
            throw typeFailure(spec, fieldName, value, "a character value");
        }

        // WHY : Assumptions: the value is returned UNTRIMMED even though three of the five callers
        //       could not observe the difference, because the two that can are a record key and an
        //       index key. Handing back the full span means a caller comparing this value with one read
        //       from another fixed-width record compares like with like; trimming here would make that
        //       comparison depend on which of the two values had been through a mapper.
        return text;
    }

    /**
     * Returns one decoded field with its trailing blanks removed and nothing else changed.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param spec the {@code CopybookLayout.RecordSpec} in force, consulted only for failure descriptors
     * @param fieldName the String copybook name of the field to read
     * @return the String occupying that span with trailing blank characters removed; an all-blank span
     *     yields an empty String rather than {@code null}
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than a character value
     */
    private static String trimmedText(Map<String, Object> decoded, CopybookLayout.RecordSpec spec,
            String fieldName) {
        Object value = require(decoded, spec, fieldName);
        if (!(value instanceof String text)) {
            throw typeFailure(spec, fieldName, value, "a character value");
        }

        // WHY : Assumptions: an all-blank span becomes an EMPTY STRING and never null.
        //       app/cbl/CBACT04C.cbl:492-494 writes SPACES into the merchant name, city and postal code
        //       on every generated interest transaction, because an accrual has no merchant, so blank is
        //       an ordinary value here rather than a missing one. Normalising it to null would change
        //       what the encode direction emits and would therefore change bytes the parity expectations
        //       compare.
        return stripTrailingBlanks(text);
    }

    /**
     * Removes trailing blank characters from a fixed-width span, and only blank characters.
     *
     * <p>Alternatives Considered: {@code String.stripTrailing}, which is the obvious call and is
     * rejected. It removes every trailing character that counts as whitespace -- a tab, a newline, a
     * form feed -- and in a fixed-width record only the blank is padding. A description that genuinely
     * ended in a tab would lose it, and the encode direction would then rebuild that position as a
     * blank, so a round trip would differ from its source in a byte no test asserted on. Stripping
     * exactly the one character COBOL pads with keeps the round trip byte-exact by construction.</p>
     *
     * <p>Assumptions: only the TRAILING run is removed, which is what makes trimming safe on
     * {@code TRAN-DESC}. {@code app/cbl/CBACT04C.cbl:485-489} fills that field with
     * {@code STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC}, and a COBOL
     * {@code STRING} statement does NOT clear the remainder of its receiving field -- so bytes left
     * from a previous iteration of the accrual loop can persist beyond the meaningful content. This is
     * observed behaviour of the statement rather than a hypothesis. The consequence is that this method
     * must be conservative and must not raise: residue that is not blank is left exactly where it is,
     * and residue that is blank is removed, so neither reading of the tail can turn a decodable record
     * into a rejected one.</p>
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
     * @param spec the {@code CopybookLayout.RecordSpec} in force, used for the field width and for
     *     failure
     *     descriptors
     * @param fieldName the String copybook name of the unsigned-display field to read
     * @return the String zero-padded to the field's declared width, which is the exact byte image the
     *     span carried
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than an integral value
     */
    private static String paddedDigits(Map<String, Object> decoded, CopybookLayout.RecordSpec spec,
            String fieldName) {
        Object value = require(decoded, spec, fieldName);
        if (!(value instanceof Long integral)) {
            throw typeFailure(spec, fieldName, value, "an integral value");
        }

        // WHY : Assumptions: TRAN-CAT-CD IS A NUMBER STORED AS ZERO-PADDED DIGITS, NOT A TWO-CHARACTER
        //       CODE, and this is the reading of this record most likely to be got wrong.
        //       app/cbl/CBACT04C.cbl:483 writes the generated interest transaction's category with
        //       MOVE '05' TO TRAN-CAT-CD. The literal looks like a two-character code, but
        //       app/cpy/CVTRA05Y.cpy:7 declares the receiving field PIC 9(04) -- an unsigned four-digit
        //       NUMERIC-DISPLAY field -- so the move is alphanumeric to numeric, which right-justifies
        //       and zero-fills. The four bytes at offsets 18 through 21 therefore hold 0005, and the
        //       category is the NUMBER 5 rather than the string '05'. The committed parity expectation
        //       at tests/golden/posting/happy_path/tranfile.expected shows the same shape at those four
        //       offsets. A mapper that round-tripped this as a two-character code would emit a record
        //       two bytes short, or -- worse, because it still reaches 350 bytes -- one whose remaining
        //       twelve fields are all shifted and all plausible.
        // WHY : Assumptions: the canonical form handed on is therefore the zero-padded one at the
        //       declared width, which is also the form the physical CHAR(4) column stores and the form
        //       every counterpart key this value joins to is declared at. Handing on an unpadded '5'
        //       would be the correct number and the wrong key, and the join would simply miss.
        return zeroPad(Long.toString(integral), spec.field(fieldName).length());
    }

    /**
     * Returns one decoded unsigned-display field as an integral value.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param spec the {@code CopybookLayout.RecordSpec} in force, consulted only for failure descriptors
     * @param fieldName the String copybook name of the unsigned-display field to read
     * @return the Long the span's digits denote, which is zero when the span is all zeros
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than an integral value
     */
    private static Long unsignedValue(Map<String, Object> decoded, CopybookLayout.RecordSpec spec,
            String fieldName) {
        Object value = require(decoded, spec, fieldName);
        if (!(value instanceof Long integral)) {
            throw typeFailure(spec, fieldName, value, "an integral value");
        }

        // WHY : Assumptions: an unsigned-display span carries PLAIN DIGITS and no sign, so its trailing
        //       byte must never be read as an overpunch even when it happens to be a character that
        //       would be one on a signed field. The shared codec enforces that by rejecting any
        //       non-digit in the span outright, which is why this method receives an already-validated
        //       integral rather than doing its own scan. Zero is an ordinary value:
        //       app/cbl/CBACT04C.cbl:491 writes MOVE 0 TO TRAN-MERCHANT-ID on every generated interest
        //       transaction, so a check rejecting zero would reject one of the two producers entirely.
        return integral;
    }

    /**
     * Returns one decoded signed zoned field as an exact decimal at the scale the picture declares.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param spec the {@code CopybookLayout.RecordSpec} in force, consulted only for failure descriptors
     * @param fieldName the String copybook name of the signed zoned field to read
     * @return the BigDecimal the span denotes, at scale two, with its sign taken from the trailing
     *     overpunch character
     * @throws RecordMappingException if the decode produced no value for that field, or produced
     *     something other than an exact decimal
     */
    private static BigDecimal signedAmount(Map<String, Object> decoded,
            CopybookLayout.RecordSpec spec, String fieldName) {
        Object value = require(decoded, spec, fieldName);
        if (!(value instanceof BigDecimal amount)) {
            throw typeFailure(spec, fieldName, value, "an exact decimal value");
        }

        // WHY : Assumptions: the scale arrives as two from the shared codec and is NEVER stripped here.
        //       Applying stripTrailingZeros would turn 50.00 into 5E+1, which is the same number and a
        //       different value to every comparison the parity oracle makes and to the NUMERIC(11,2)
        //       column, and it would then re-encode with the wrong digit count. The overpunch itself is
        //       delegated to com.carddemo.common.codec.ZonedDecimalCodec, which owns the two sign
        //       tables;
        //       decoding it here would put a second reading of the same trailing byte in the tree.
        // WHY : Assumptions: A NEGATIVE AMOUNT IS LEGITIMATE and is not clamped or rejected.
        //       app/cbl/CBTRN02C.cbl:548-552 branches on exactly that sign, adding a non-negative amount
        //       to the cycle credit and a negative one to the cycle debit, so refusing a negative value
        //       here would refuse the debit half of the baseline's own domain.
        return amount;
    }

    /**
     * Returns one decoded 26-character timestamp span as a date and time, or absent when it is blank.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param spec the {@code CopybookLayout.RecordSpec} in force, used for the field descriptor a
     *     failure quotes
     * @param fieldName the String copybook name of the timestamp field to read
     * @return the LocalDateTime the span denotes, or {@code null} when the span is entirely blank
     * @throws RecordMappingException if the span is neither blank nor in either producer's
     *     26-character form
     */
    private static LocalDateTime timestampOf(Map<String, Object> decoded,
            CopybookLayout.RecordSpec spec, String fieldName) {
        Object value = require(decoded, spec, fieldName);
        if (!(value instanceof String span)) {
            throw typeFailure(spec, fieldName, value, "a character value");
        }

        // WHY : Assumptions: A 26-BLANK TIMESTAMP IS A LEGITIMATE VALUE and decodes to absent rather
        //       than raising. TRAN-PROC-TS is genuinely blank on a row that has not been posted, which
        //       is why the migrated daily-transaction walk is ordered by transaction identifier instead
        //       of by processing stamp -- ordering by a column that is blank on precisely the rows the
        //       walk exists to find would put them in an arbitrary place. The shared codec signals the
        //       blank case by returning an empty string for a 26-byte field whose name ends in -TS, so
        //       the emptiness test below is reading that signal rather than testing a length.
        if (stripTrailingBlanks(span).isEmpty()) {
            return null;
        }
        return parseEitherForm(span, spec.field(fieldName));
    }

    /**
     * Parses a 26-character span written by either producer, normalising neither form into the other.
     *
     * <p>Assumptions: TWO DIFFERENT 26-CHARACTER BYTE PATTERNS REACH THIS FIELD, and they are not
     * interchangeable even though both are exactly 26 characters. The target form,
     * {@code uuuu-MM-dd HH:mm:ss} followed by a point and six fractional digits, is owned by
     * {@code com.carddemo.common.time.TimestampFormatter}. The baseline form is declared by
     * {@code app/cbl/CBTRN02C.cbl:149} and decomposed by the overlay at
     * {@code app/cbl/CBTRN02C.cbl:160-174}, and it differs at character position 11, where it carries a
     * THIRD HYPHEN where the target form carries a space; at positions 14 and 17, where it carries a dot
     * where the target form carries a colon; and across positions 21 through 26, where only the first
     * two are significant fractional digits and the remaining four are the literal {@code 0000} that
     * {@code app/cbl/CBTRN02C.cbl:701} writes.</p>
     *
     * <p>Assumptions: THIS MAPPER NEVER NORMALISES ONE PATTERN INTO THE OTHER, and never assumes a
     * decoded 26-character span is in the target form. The two patterns are mutually exclusive -- each
     * fails the other's parse at position 11 -- so reading defensively is a matter of trying both rather
     * than of guessing, and the outcome is a date and time that carries no form at all. Which form is
     * EMITTED is then an explicit decision of whichever encode entry point the caller chose, and the
     * byte-preserving overload keeps the original span rather than rewriting it. Sibling contexts warn
     * that three 26-character patterns coexist across this migration; collapsing any two of them would
     * make a record that still reads as 350 bytes and no longer says what it said.</p>
     *
     * <p>Assumptions: the {@code normalizeTs} flag on a field descriptor MARKS the field and normalises
     * nothing, so it is not consulted here. The 26-character emission contract belongs to the shared
     * formatter and the migration plan's transformation rule T2 forbids re-deriving it, which is why the
     * target form is parsed by calling that formatter rather than by a second pattern declared here.</p>
     *
     * <p>Assumptions: no ten-character token is reformatted on the assumption that it is ISO ordered.
     * {@code app/jcl/INTCALC.jcl:22} supplies the baseline business date as {@code '2022071800'} -- ten
     * characters, unpunctuated, and not ISO -- and {@code app/cbl/CBACT04C.cbl:476-480} concatenates it
     * byte for byte into a generated identifier, so a mapper that punctuated such a token would change
     * a key.</p>
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
                //       and the target form is the one a migrated writer produces, so its parse position
                //       is the more useful of the two to a reader diagnosing a record this module wrote.
                //       The span itself is quoted because a timestamp is not sensitive; the
                //       descriptor is
                //       quoted alongside it so the offset is available even when the characters are not
                //       recognisable.
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
     * Returns one decoded value, insisting the record-level decode actually produced it.
     *
     * @param decoded the Map of decoded field values keyed by copybook name
     * @param spec the {@code CopybookLayout.RecordSpec} in force, used to build the descriptor a
     *     failure quotes
     * @param fieldName the String copybook name of the field to read
     * @return the Object the decode produced for that field, never {@code null}
     * @throws RecordMappingException if the decode produced no entry for that field
     */
    private static Object require(Map<String, Object> decoded, CopybookLayout.RecordSpec spec,
            String fieldName) {
        Object value = decoded.get(fieldName);
        if (value == null) {
            // WHY : Assumptions: the record-level decode omits ONLY a blank trailing pad, and this
            //       mapper never asks for the pad, so a missing entry here means the specification and
            //       this mapper disagree about which fields exist rather than that a record was short --
            //       a short record was already refused by width. Naming the descriptor points at that
            //       disagreement instead of surfacing later as a null member on the entity.
            throw new RecordMappingException("layout " + spec.name() + " produced no value for "
                    + spec.field(fieldName).describe() + ", so this mapper and that layout disagree"
                    + " about the record's field set");
        }
        return value;
    }

    /**
     * Builds the failure raised when a decoded value is not of the Java type its kind implies.
     *
     * @param spec the {@code CopybookLayout.RecordSpec} in force, used to build the field descriptor
     * @param fieldName the String copybook name of the field whose value was of the wrong type
     * @param value the Object the decode produced, whose class is named but whose content is not
     *     quoted
     * @param expected the String describing what the field's kind should have produced, such as
     *     {@code "a character value"}
     * @return a {@link RecordMappingException} naming the field descriptor, the expectation and the
     *     class actually produced
     */
    private static RecordMappingException typeFailure(CopybookLayout.RecordSpec spec,
            String fieldName, Object value, String expected) {
        // WHY : Assumptions: the CLASS of the offending value is named and its CONTENT is not, for every
        //       field rather than only the ones the layout marks sensitive. A type mismatch is a
        //       descriptor defect, so the class is the whole diagnosis and the content adds nothing;
        //       withholding it uniformly also means this method cannot become the one path that leaks a
        //       card number after a later edit changes which field reaches it.
        return new RecordMappingException("layout " + spec.name() + " field "
                + spec.field(fieldName).describe() + " should decode to " + expected
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
     * Rejects an absent layout selector before it can reach a codec as a null specification.
     *
     * @param layout the {@link Layout} a caller supplied, which may be {@code null}
     * @return the same {@link Layout} instance, so the check composes into a single expression at each
     *     public entry point
     * @throws RecordMappingException if {@code layout} is {@code null}
     */
    private static Layout requireLayout(Layout layout) {
        if (layout == null) {
            // WHY : Alternatives Considered: defaulting a null selector to the posting layout. Rejected
            //       because the two layouts differ in which timestamp a parity comparator blanks, so a
            //       silent default would send an interest generation down the posting policy and the
            //       comparison would then compare a clock read. A null selector is a caller defect and
            //       is reported as one.
            throw new RecordMappingException("a layout must be named; it selects between the "
                    + Layout.POSTED_MASTER.registryName() + " and "
                    + Layout.INTEREST_GENERATED.registryName() + " registry entries, which differ in"
                    + " which timestamp a parity comparison normalises");
        }
        return layout;
    }

    /**
     * Reports a record this mapper cannot translate without inventing or discarding content.
     *
     * <p>Assumptions: it extends {@code IllegalArgumentException} so that it joins the family the
     * shared codecs already raise -- {@code FixedWidthCodec.FieldCodecException},
     * {@code FixedWidthCodec.RecordLengthException} and
     * {@code ZonedDecimalCodec.ZonedDecimalException} are all unchecked arguments failures -- and a
     * caller that wants to treat every malformed record alike can catch one type. A distinct type is
     * still declared rather than raising the base class, because the failures this mapper adds are
     * the ones the codec cannot see: an entity whose key is absent, a code whose width would change
     * meaning, and a timestamp span in neither producer's form.</p>
     *
     * <p>Assumptions: nothing this type carries may quote a primary account number, so every message
     * handed to it is either a field descriptor or an already-masked rendering.</p>
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
         * @param message the String describing what could not be translated; the caller is
         *     responsible for having masked or omitted any sensitive content, because this
         *     constructor performs no sanitisation of its own
         */
        private RecordMappingException(String message) {
            super(message);
        }

        /**
         * Creates a failure carrying an already-sanitised description and its underlying cause.
         *
         * @param message the String describing what could not be translated, sanitised by the caller
         * @param cause the Throwable that revealed the problem, retained so a parse position or a
         *     codec diagnostic is not lost; it is attached only for fields the layout does not mark
         *     sensitive
         */
        private RecordMappingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
