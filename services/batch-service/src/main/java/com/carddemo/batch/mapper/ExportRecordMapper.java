package com.carddemo.batch.mapper;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.Card;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.Customer;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.time.TimestampFormatter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the 500-byte multi-shape export record of {@code app/cpy/CVEXPORT.cpy}.
 *
 * <h2>Purpose</h2>
 *
 * <p>This type is the anti-corruption boundary for the export and import pair, and it is the only
 * mapper in this package that handles more than one record shape. It handles six: a 40-byte common
 * prefix declared at {@code app/cpy/CVEXPORT.cpy:10-19}, and five mutually exclusive 460-byte
 * payload interpretations that each redefine the single {@code EXPORT-RECORD-DATA PIC X(460)} span
 * declared at line 19. Which of the five applies is knowable only from the one-byte discriminator at
 * line 10, so the discriminator is read before any payload byte is interpreted.</p>
 *
 * <p>The migration plan names the 500-byte packed-decimal record round-trip as the export and import
 * jobs' contract at its section 0.5.1.7, and its section 0.4.3 makes this package the only place
 * representation concerns may appear. The charter beside this file states the second of those from
 * this package's own side and lists this type as owning the discriminator and all five views in one
 * compilation unit rather than five.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a static holder that cannot be
 * instantiated, so the type itself takes no parameter, yields no value and raises nothing; every
 * member below carries its own at-clauses. The inapplicability is stated rather than passed over
 * because user-specified Rule 1 (Explainability) forbids a docstring that omits parameters, return
 * values or purpose, and a reader must be able to tell a declared inapplicability from an omission.
 * The sibling mappers and the shared kernel state it the same way.</p>
 *
 * <h2>Assumptions: the copybook inventory this file was written against</h2>
 *
 * <p>{@code app/cpy/CVEXPORT.cpy} is 103 lines at version
 * {@code CardDemo_v2.0-44-gb6e9c27-254} and declares <b>exactly one {@code 01} level</b>, at line 9.
 * Everything below line 9 is subordinate to that one record, which is what makes the five views
 * overlays of one payload rather than five records. The counts below are recorded as a completeness
 * statement so a later reader can confirm that nothing in the file was missed, and specifically so
 * that the two constructs which appear only inside comment text are not mistaken for declarations:
 * </p>
 *
 * <ul>
 *   <li><b>Six real {@code REDEFINES} clauses</b>, at lines 12, 24, 47, 65, 84 and 93. A seventh
 *       occurrence of the word sits inside the comment banner at line 7 and declares nothing.</li>
 *   <li><b>Four real {@code COMP-3} fields</b>, at lines 41, 50, 52 and 71. A fifth occurrence sits
 *       inside the comment banner at line 6 and declares nothing.</li>
 *   <li><b>Seven bare {@code COMP} fields</b>, at lines 16, 25, 57, 72, 87, 95 and 96.</li>
 *   <li><b>Two {@code OCCURS} clauses</b>, at lines 29 and 34.</li>
 * </ul>
 *
 * <h2>Assumptions: the prefix closes at 500 and the overlay at line 12 contributes nothing</h2>
 *
 * <p>The prefix is {@code 1 + 26 + 4 + 4 + 5 + 460}, which is 500. The term that is easy to get
 * wrong is the second: {@code EXPORT-TIMESTAMP PIC X(26)} at line 11 is immediately followed at line
 * 12 by {@code EXPORT-TIMESTAMP-R REDEFINES EXPORT-TIMESTAMP}, whose three subordinates at lines 13
 * to 15 are {@code PIC X(10)}, {@code PIC X(1)} and {@code PIC X(15)} and themselves sum to 26.</p>
 *
 * <p>Assumptions: <b>that overlay is an alias over bytes that already exist and contributes zero
 * further bytes</b>, per {@code app/cpy/CVEXPORT.cpy:11-15}. Counting its three children as
 * additional fields would put 66 bytes in the prefix instead of 40 and would shift every payload
 * offset by 26, and because the record still reaches its declared 500 bytes the result is a decode
 * that succeeds and is wrong throughout. The overlay is therefore <b>not</b> declared in the
 * descriptor at all; it is exposed as three derived accessors over the single 26-byte field, on
 * {@link Prefix}, so there is no arrangement of this class in which those ten, one and fifteen bytes
 * can be counted twice.</p>
 *
 * <h2>Assumptions: no golden master exists for this stream, so these tests carry the whole load</h2>
 *
 * <p>Every other record shape this module maps has a committed expectation file produced by running
 * the baseline. This one does not. The migration plan records at its section 0.2.2 that
 * {@code CBEXPORT} and {@code CBIMPORT} do not compile under the open-source compiler and that their
 * integration test is consequently skipped, which the reference suite reports as a soft warning
 * rather than a failure. There is therefore <b>no golden master for the export stream</b>, and no
 * oracle outside this repository against which a decoded export record can be checked.</p>
 *
 * <p>The consequence is worth stating plainly because it changes how much this type's own tests
 * matter: the geometry proofs that run at class initialisation, and the round-trip assertions over
 * each of the five views, are the only verification this shape gets. They are not a supplement to a
 * parity comparison here; they are the parity comparison.</p>
 *
 * <h2>Alternatives Considered: the two numeric codecs are reached through the record codec</h2>
 *
 * <p>This record mixes three storage regimes, so both numeric codecs of the shared kernel are involved
 * in reading it -- {@code PackedDecimalCodec} for {@code COMP-3} and {@code COMP}, and
 * {@code ZonedDecimalCodec} for sign overpunch. Neither is imported here, and calling them directly was
 * considered and rejected. {@code FixedWidthCodec} already dispatches to exactly those two on
 * {@link CopybookLayout.FieldSpec#kind()}, so going through it means the regime is selected from the
 * DESCRIPTOR for every field in this file, uniformly and in one place. Calling them directly would put
 * a second numeric decode path in the tree, and a second path is somewhere two readings of the same
 * trailing byte can disagree -- the shared kernel makes the same point from its own side when it
 * declines to let a descriptor decode. The practical consequence is the rule this file follows
 * throughout: a storage regime is never inferred by inspecting bytes.</p>
 *
 * <h2>Warning: {@code TRNXOUT} is not the {@code TRNX} layout</h2>
 *
 * <p>{@code app/cbl/CBIMPORT.cbl:58} declares {@code SELECT TRANSACTION-OUTPUT ASSIGN TO TRNXOUT}
 * for the transaction-shaped output this record splits out. The similarity of that data-definition
 * name to the {@code TRNX} key registered in {@code com.carddemo.common.codec.CopybookLayout} is
 * coincidental and the two are unrelated: {@code TRNX} is the statement-input layout of
 * {@code app/cpy/COSTM01.CPY}, a 350-byte record with a 32-byte composite key and an entirely
 * different field set. Resolving {@code TRNXOUT} to it would decode a transaction view against a
 * statement geometry, which is a silent mis-parse rather than a failure. The transaction view's
 * geometry is declared in this file and comes from {@code app/cpy/CVEXPORT.cpy:65-79} alone.</p>
 *
 * @see CopybookLayout
 * @see FixedWidthCodec
 */
public final class ExportRecordMapper {

    /**
     * The descriptor name of the 500-byte export record, used only in this file's diagnostics.
     */
    private static final String RECORD_NAME = "EXPORT-RECORD";

    /** The one-character discriminator at {@code app/cpy/CVEXPORT.cpy:10}. */
    private static final String FIELD_REC_TYPE = "EXPORT-REC-TYPE";

    /** The 26-character export stamp at {@code app/cpy/CVEXPORT.cpy:11}. */
    private static final String FIELD_TIMESTAMP = "EXPORT-TIMESTAMP";

    /** The binary sequence number at {@code app/cpy/CVEXPORT.cpy:16}, which keys the record. */
    private static final String FIELD_SEQUENCE_NUM = "EXPORT-SEQUENCE-NUM";

    /** The four-character branch identifier at {@code app/cpy/CVEXPORT.cpy:17}. */
    private static final String FIELD_BRANCH_ID = "EXPORT-BRANCH-ID";

    /** The five-character region code at {@code app/cpy/CVEXPORT.cpy:18}. */
    private static final String FIELD_REGION_CODE = "EXPORT-REGION-CODE";

    /** The 460-byte payload span at {@code app/cpy/CVEXPORT.cpy:19} that the five views redefine. */
    private static final String FIELD_RECORD_DATA = "EXPORT-RECORD-DATA";

    /**
     * The name every view's trailing pad is declared under in {@code app/cpy/CVEXPORT.cpy}.
     */
    private static final String FIELD_FILLER = "FILLER";

    /**
     * The customer view's national identifier at {@code app/cpy/CVEXPORT.cpy:36}, named once here.
     *
     * <p>Assumptions: this is the one field name of the customer view given a constant, because it is
     * the one whose VALUE this file decides rather than copies. Every other name in that view appears
     * as a literal beside the value it carries; this one is referenced twice -- by the descriptor and
     * by the elision below -- and a constant is what keeps the two references from drifting apart.</p>
     */
    private static final String FIELD_CUSTOMER_SSN = "EXP-CUST-SSN";

    /**
     * The value written into an unsigned-display span whose source column this module cannot read.
     *
     * <p>Assumptions: zero rather than a blank run, and a {@code Long} rather than digit text, both
     * for the same reason -- the shared codec's unsigned-display encode accepts an integral value or
     * digit text and refuses a non-digit character, so a blank run would fail the encode outright
     * instead of producing an empty value. Zero is the empty value that storage kind HAS, and the
     * codec left-pads it to the field's declared width, so the nine zero digits the customer view
     * carries are derived from the descriptor rather than written out here.</p>
     */
    private static final Long ELIDED_UNSIGNED_VALUE = 0L;

    /** The card view's primary account number at {@code app/cpy/CVEXPORT.cpy:94}. */
    private static final String FIELD_CARD_NUM = "EXP-CARD-NUM";

    /**
     * The card verification value at {@code app/cpy/CVEXPORT.cpy:96}, named once and suppressed
     * everywhere.
     */
    private static final String FIELD_CARD_CVV = "EXP-CARD-CVV-CD";

    /**
     * The declared byte width of the card verification span.
     *
     * <p>Assumptions: derived rather than written down, so the carrier's width check and the
     * descriptor can never disagree. {@code EXP-CARD-CVV-CD} is declared {@code PIC S9(03) COMP} at
     * {@code app/cpy/CVEXPORT.cpy:96}, which the shared kernel's width rule renders as a two-byte
     * halfword; computing it from that rule means a change to either the picture or the rule moves
     * both the span and the check together.</p>
     */
    private static final int CARD_CVV_WIDTH = CopybookLayout.binaryWidth(3, 0);

    /** The declared length of the whole export record, from {@code app/cpy/CVEXPORT.cpy:5}. */
    private static final int RECORD_LENGTH = 500;

    /** The declared length of the payload span, from {@code app/cpy/CVEXPORT.cpy:19}. */
    private static final int PAYLOAD_LENGTH = 460;

    /** The zero-based offset at which the payload span begins. */
    private static final int PAYLOAD_OFFSET = 40;

    /**
     * The zero-based offset of the sequence number, which is also this record's key offset.
     */
    private static final int SEQUENCE_NUM_OFFSET = 27;

    /** The digit count of {@code EXPORT-SEQUENCE-NUM PIC 9(9) COMP}. */
    private static final int SEQUENCE_NUM_DIGITS = 9;

    /**
     * The byte width of the sequence number, which is also this record's key length.
     *
     * <p>Assumptions: nine digits stored as {@code COMP} occupy a four-byte fullword, and the figure
     * is taken from {@link CopybookLayout#binaryWidth(int, int)} rather than written as a literal so
     * that this file cannot disagree with the shared width rule. Writing nine here -- one byte per
     * digit, as a display field would take -- is the reading the {@code USAGE} clause rules out, and
     * it would put every prefix field after this one five bytes late.</p>
     */
    private static final int SEQUENCE_NUM_LENGTH =
            CopybookLayout.binaryWidth(SEQUENCE_NUM_DIGITS, 0);

    /** The width of the date portion of the line-12 timestamp overlay, from line 13. */
    private static final int OVERLAY_DATE_LENGTH = 10;

    /** The relative offset of the separator within the 26-byte stamp, from line 14. */
    private static final int OVERLAY_SEPARATOR_START = 10;

    /** The relative offset of the time portion within the 26-byte stamp, from line 15. */
    private static final int OVERLAY_TIME_START = 11;

    /** The single blank this file pads absent character values back to. */
    private static final char BLANK = ' ';

    /** The value a redacted sensitive field is reported as in any diagnostic. */
    private static final String REDACTED = "[redacted]";

    /**
     * The baseline 26-character timestamp form, kept only so an inbound span in it can be read.
     *
     * <p>Assumptions: this pattern is the form {@code app/cbl/CBTRN02C.cbl:160-174} composes, and it
     * differs from the target form at positions 11, 14, 17 and 21 through 26. It is accepted on
     * decode and is <b>never emitted</b>, because neither form is normalised into the other; the
     * sibling {@code TransactionRecordMapper} takes the same position on the same two fields, and
     * this file reuses it rather than deciding the question a second way.</p>
     */
    private static final DateTimeFormatter BASELINE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SSSSSS", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * The 500-byte record descriptor: the five prefix scalars followed by the payload span.
     *
     * <p>Assumptions: the payload is declared here as a single 460-byte character span exactly as
     * {@code app/cpy/CVEXPORT.cpy:19} declares it, which is what makes the field list contiguous from
     * zero and its lengths sum to the declared 500. It is <b>declared but never decoded as
     * characters</b>; see {@link #decodePrefix(byte[])} for why, and {@link #payloadOf(byte[])} for
     * what is done with it instead.</p>
     *
     * <p>Assumptions: the retrieval key is the four-byte sequence number at offset 27, which is the
     * divergence recorded on {@link #toRecord(ExportRecord)}. The overlay at
     * {@code app/cpy/CVEXPORT.cpy:12} is deliberately absent from this list.</p>
     */
    // WHY : Alternatives Considered: registering these six descriptors in
    //       com.carddemo.common.codec.CopybookLayout alongside the eleven base masters and the three
    //       derived layouts, which is where every other descriptor this package reads comes from.
    //       Rejected on three grounds that hold together. The export record is consumed by exactly
    //       ONE module, so a shared entry would widen a shared contract for a single caller. Its five
    //       views are MUTUALLY EXCLUSIVE readings of one payload rather than independently
    //       addressable records, and the registry's resolve-by-name contract has no way to express
    //       "these five are alternatives" -- a caller could ask for the account view of a record
    //       whose discriminator says customer and be handed a geometry that decodes plausibly.
    //       Finally the registry's keys are the DATASETS every service may legitimately read, and a
    //       single-consumer multi-view shape is not one of those. The descriptors are therefore local
    //       and no export key is added to common-lib.
    // WHY : Trade-offs: being outside the registry costs two behaviours that registered descriptors
    //       get for free, and both are handled explicitly rather than silently lost. FixedWidthCodec
    //       recognises a trailing pad only when the descriptor handed to it is the registered
    //       instance BY IDENTITY, so for these specs it neither drops FILLER on decode nor
    //       blank-fills it on encode; this file drops it from every emitted projection itself and
    //       supplies the pad explicitly when encoding. What is bought is that the shared kernel's
    //       contract stays exactly as wide as its consumers need.
    private static final CopybookLayout.RecordSpec EXPORT_RECORD =
            new CopybookLayout.RecordSpec(RECORD_NAME, RECORD_LENGTH, SEQUENCE_NUM_LENGTH,
                    SEQUENCE_NUM_OFFSET, List.of(
                            CopybookLayout.text(FIELD_REC_TYPE, 0, 1),
                            CopybookLayout.text(FIELD_TIMESTAMP, 1,
                                    TimestampFormatter.TIMESTAMP_LENGTH),
                            CopybookLayout.binary(FIELD_SEQUENCE_NUM, SEQUENCE_NUM_OFFSET,
                                    SEQUENCE_NUM_DIGITS, 0, false),
                            CopybookLayout.text(FIELD_BRANCH_ID, 31, 4),
                            CopybookLayout.text(FIELD_REGION_CODE, 35, 5),
                            CopybookLayout.text(FIELD_RECORD_DATA, PAYLOAD_OFFSET, PAYLOAD_LENGTH)))
                    .validateGeometry();

    /**
     * The customer view of {@code app/cpy/CVEXPORT.cpy:24-42}, keyless and 460 bytes.
     *
     * <p>Assumptions: <b>the two {@code OCCURS} groups contribute 150 and 30 bytes, not 50 and
     * 15.</b> {@code app/cpy/CVEXPORT.cpy:29-30} declares {@code EXP-CUST-ADDR-LINES OCCURS 3 TIMES}
     * containing one {@code EXP-CUST-ADDR-LINE PIC X(50)}, so the group occupies three times fifty;
     * {@code app/cpy/CVEXPORT.cpy:34-35} declares {@code EXP-CUST-PHONE-NUMS OCCURS 2 TIMES}
     * containing one {@code EXP-CUST-PHONE-NUM PIC X(15)}, so that group occupies two times fifteen.
     * A left-to-right reading that takes each subordinate once <b>undercounts the view by exactly 115
     * bytes</b> -- 100 from the address group and 15 from the phone group -- and the field list then
     * sums to 345 instead of 460, which the payload span does not admit. The failure mode this guards
     * is that every field from offset 79 onward is mis-parsed while still looking like data.</p>
     *
     * <p>Assumptions: each occurrence is therefore flattened into its own descriptor entry at its own
     * offset, and is named with the subscript the baseline itself uses to reference it --
     * {@code app/cbl/CBIMPORT.cbl:293-295} moves {@code EXP-CUST-ADDR-LINE(1)} through
     * {@code (3)} and {@code app/cbl/CBIMPORT.cbl:303-304} moves {@code EXP-CUST-PHONE-NUM(1)} and
     * {@code (2)}. The subscripted spelling is the baseline's own reference form rather than a rename,
     * which matters because transformation rule T1 permits no renaming beyond the documented
     * misspellings, and because {@code FixedWidthCodec} rejects a descriptor that declares one field
     * name twice -- a field map cannot represent duplicate keys.</p>
     *
     * <p>Assumptions: the credit score at {@code app/cpy/CVEXPORT.cpy:41} is
     * {@code PIC 9(03) COMP-3}, which is two bytes and not three: three digit positions plus a sign
     * nibble is four nibbles, and four nibbles occupy two bytes. Its base-master counterpart at
     * {@code app/cpy/CVCUS01Y.cpy} is an unsigned display field of three bytes, so this is one of the
     * places the export view is narrower than the master it was built from.</p>
     */
    private static final CopybookLayout.RecordSpec CUSTOMER_VIEW =
            CopybookLayout.RecordSpec.keyless("EXPORT-CUSTOMER-DATA", PAYLOAD_LENGTH, List.of(
                    CopybookLayout.binary("EXP-CUST-ID", 0, 9, 0, false),
                    CopybookLayout.sensitiveText("EXP-CUST-FIRST-NAME", 4, 25),
                    CopybookLayout.sensitiveText("EXP-CUST-MIDDLE-NAME", 29, 25),
                    CopybookLayout.sensitiveText("EXP-CUST-LAST-NAME", 54, 25),
                    CopybookLayout.sensitiveText("EXP-CUST-ADDR-LINE(1)", 79, 50),
                    CopybookLayout.sensitiveText("EXP-CUST-ADDR-LINE(2)", 129, 50),
                    CopybookLayout.sensitiveText("EXP-CUST-ADDR-LINE(3)", 179, 50),
                    CopybookLayout.text("EXP-CUST-ADDR-STATE-CD", 229, 2),
                    CopybookLayout.text("EXP-CUST-ADDR-COUNTRY-CD", 231, 3),
                    CopybookLayout.text("EXP-CUST-ADDR-ZIP", 234, 10),
                    CopybookLayout.sensitiveText("EXP-CUST-PHONE-NUM(1)", 244, 15),
                    CopybookLayout.sensitiveText("EXP-CUST-PHONE-NUM(2)", 259, 15),
                    CopybookLayout.sensitiveUint(FIELD_CUSTOMER_SSN, 274, 9),
                    CopybookLayout.sensitiveText("EXP-CUST-GOVT-ISSUED-ID", 283, 20),
                    CopybookLayout.sensitiveText("EXP-CUST-DOB-YYYY-MM-DD", 303, 10),
                    CopybookLayout.sensitiveText("EXP-CUST-EFT-ACCOUNT-ID", 313, 10),
                    CopybookLayout.text("EXP-CUST-PRI-CARD-HOLDER-IND", 323, 1),
                    CopybookLayout.packed("EXP-CUST-FICO-CREDIT-SCORE", 324, 3, 0, false),
                    CopybookLayout.text(FIELD_FILLER, 326, 134))).validateGeometry();

    /**
     * The account view of {@code app/cpy/CVEXPORT.cpy:47-60}, keyless and 460 bytes.
     *
     * <p>Assumptions: <b>five fields share the identical {@code PIC S9(10)V99} and occupy three
     * different physical widths, because the {@code USAGE} clause fixes the width and the
     * {@code PICTURE} does not.</b> The three declarations that prove it are cited individually
     * because each is the sole evidence for its own width:</p>
     *
     * <ul>
     *   <li>{@code app/cpy/CVEXPORT.cpy:50} declares {@code EXP-ACCT-CURR-BAL PIC S9(10)V99 COMP-3}
     *       -- packed, <b>7 bytes</b>. {@code app/cpy/CVEXPORT.cpy:52} declares
     *       {@code EXP-ACCT-CASH-CREDIT-LIMIT} the same way.</li>
     *   <li>{@code app/cpy/CVEXPORT.cpy:51} declares {@code EXP-ACCT-CREDIT-LIMIT PIC S9(10)V99}
     *       with no usage clause -- zoned display, <b>12 bytes</b>, the sign being an overpunch that
     *       consumes no byte of its own. {@code app/cpy/CVEXPORT.cpy:56} declares
     *       {@code EXP-ACCT-CURR-CYC-CREDIT} the same way.</li>
     *   <li>{@code app/cpy/CVEXPORT.cpy:57} declares
     *       {@code EXP-ACCT-CURR-CYC-DEBIT PIC S9(10)V99 COMP} -- binary, <b>8 bytes</b>, because
     *       twelve digit positions exceed the nine a fullword holds.</li>
     * </ul>
     *
     * <p>Assumptions: the arithmetic consequence is what makes this checkable rather than asserted.
     * The view's named fields are {@code 11 + 1 + 7 + 12 + 7 + 10 + 10 + 10 + 12 + 8 + 10 + 10}, which
     * is 108, and the trailing pad at {@code app/cpy/CVEXPORT.cpy:60} is 352; together they are
     * exactly 460. <b>That closes only if each packed amount is seven bytes.</b> At six the named
     * fields would sum to 106 and the view would close at 458, which the 460-byte payload does not
     * admit -- so this view independently confirms the packed width rule for a twelve-digit field, and
     * a six-byte belief is refuted by the copybook itself rather than merely discouraged.</p>
     *
     * <p>Assumptions: the export view is where the same logical data is stored differently from the
     * master it came from, and the widths must not be borrowed between the two.
     * {@code app/cpy/CVACT01Y.cpy:7-14} declares all five of these amounts as plain zoned
     * {@code PIC S9(10)V99}, twelve bytes each with no usage clause, in a 300-byte record; here two of
     * them are seven bytes and one is eight, in a 460-byte payload. Reusing the registered
     * {@code ACCOUNT} descriptor for this view would therefore read the balance from the wrong span.
     * </p>
     */
    private static final CopybookLayout.RecordSpec ACCOUNT_VIEW =
            CopybookLayout.RecordSpec.keyless("EXPORT-ACCOUNT-DATA", PAYLOAD_LENGTH, List.of(
                    CopybookLayout.uint("EXP-ACCT-ID", 0, 11),
                    CopybookLayout.text("EXP-ACCT-ACTIVE-STATUS", 11, 1),
                    CopybookLayout.packed("EXP-ACCT-CURR-BAL", 12, 10, 2, true),
                    CopybookLayout.signedZoned("EXP-ACCT-CREDIT-LIMIT", 19, 10, 2),
                    CopybookLayout.packed("EXP-ACCT-CASH-CREDIT-LIMIT", 31, 10, 2, true),
                    CopybookLayout.text("EXP-ACCT-OPEN-DATE", 38, 10),
                    CopybookLayout.text("EXP-ACCT-EXPIRAION-DATE", 48, 10),
                    CopybookLayout.text("EXP-ACCT-REISSUE-DATE", 58, 10),
                    CopybookLayout.signedZoned("EXP-ACCT-CURR-CYC-CREDIT", 68, 10, 2),
                    CopybookLayout.binary("EXP-ACCT-CURR-CYC-DEBIT", 80, 10, 2, true),
                    CopybookLayout.text("EXP-ACCT-ADDR-ZIP", 88, 10),
                    CopybookLayout.text("EXP-ACCT-GROUP-ID", 98, 10),
                    CopybookLayout.text(FIELD_FILLER, 108, 352))).validateGeometry();

    /**
     * The transaction view of {@code app/cpy/CVEXPORT.cpy:65-79}, keyless and 460 bytes.
     *
     * <p>Assumptions: two fields carry the same logical data as the posted-transaction master at
     * materially different widths, and the divergence is cited from both sides so that neither
     * descriptor is substituted for the other. The amount at {@code app/cpy/CVEXPORT.cpy:71} is
     * {@code PIC S9(09)V99 COMP-3}, <b>6 bytes</b>, against {@code app/cpy/CVTRA05Y.cpy:10} which
     * declares {@code TRAN-AMT PIC S9(09)V99} as <b>11 zoned bytes</b>; the merchant identifier at
     * {@code app/cpy/CVEXPORT.cpy:72} is {@code PIC 9(09) COMP}, <b>4 bytes</b>, against
     * {@code app/cpy/CVTRA05Y.cpy:11} which declares {@code TRAN-MERCHANT-ID PIC 9(09)} as <b>9
     * display bytes</b>. The two records agree on the first 132 bytes and diverge from there, which is
     * precisely what makes borrowing the registered {@code TRAN} descriptor look safe and be wrong.
     * </p>
     *
     * <p>Assumptions: the category code at {@code app/cpy/CVEXPORT.cpy:68} is {@code PIC 9(04)} --
     * an unsigned four-digit numeric-display field, so a <b>number</b> stored as zero-padded digits
     * and not a two-character code. The sibling {@code TransactionRecordMapper} establishes this
     * reading for the same field of the master and it is reused unchanged here.</p>
     */
    private static final CopybookLayout.RecordSpec TRANSACTION_VIEW =
            CopybookLayout.RecordSpec.keyless("EXPORT-TRANSACTION-DATA", PAYLOAD_LENGTH, List.of(
                    CopybookLayout.text("EXP-TRAN-ID", 0, 16),
                    CopybookLayout.text("EXP-TRAN-TYPE-CD", 16, 2),
                    CopybookLayout.uint("EXP-TRAN-CAT-CD", 18, 4),
                    CopybookLayout.text("EXP-TRAN-SOURCE", 22, 10),
                    CopybookLayout.text("EXP-TRAN-DESC", 32, 100),
                    CopybookLayout.packed("EXP-TRAN-AMT", 132, 9, 2, true),
                    CopybookLayout.binary("EXP-TRAN-MERCHANT-ID", 138, 9, 0, false),
                    CopybookLayout.text("EXP-TRAN-MERCHANT-NAME", 142, 50),
                    CopybookLayout.text("EXP-TRAN-MERCHANT-CITY", 192, 50),
                    CopybookLayout.text("EXP-TRAN-MERCHANT-ZIP", 242, 10),
                    CopybookLayout.sensitiveText("EXP-TRAN-CARD-NUM", 252, 16),
                    CopybookLayout.text("EXP-TRAN-ORIG-TS", 268,
                            TimestampFormatter.TIMESTAMP_LENGTH),
                    CopybookLayout.normalizedTimestamp("EXP-TRAN-PROC-TS", 294,
                            TimestampFormatter.TIMESTAMP_LENGTH),
                    CopybookLayout.text(FIELD_FILLER, 320, 140))).validateGeometry();

    /**
     * The card cross-reference view of {@code app/cpy/CVEXPORT.cpy:84-88}, keyless and 460 bytes.
     *
     * <p>Assumptions: the account identifier at {@code app/cpy/CVEXPORT.cpy:87} is
     * {@code PIC 9(11) COMP}, an <b>eight-byte binary word</b>, against
     * {@code app/cpy/CVACT03Y.cpy:7} which declares {@code XREF-ACCT-ID PIC 9(11)} as <b>eleven
     * display bytes</b> -- the same field, the same eleven digits, two different physical widths.
     * Note once that this view mixes usages internally: the customer identifier at
     * {@code app/cpy/CVEXPORT.cpy:86} stays a nine-byte display field while the account identifier
     * beside it is binary, so a single view is not uniform in its storage regime and a per-field
     * {@code Kind} is the only safe basis for reading it.</p>
     */
    private static final CopybookLayout.RecordSpec CARD_XREF_VIEW =
            CopybookLayout.RecordSpec.keyless("EXPORT-CARD-XREF-DATA", PAYLOAD_LENGTH, List.of(
                    CopybookLayout.sensitiveText("EXP-XREF-CARD-NUM", 0, 16),
                    CopybookLayout.uint("EXP-XREF-CUST-ID", 16, 9),
                    CopybookLayout.binary("EXP-XREF-ACCT-ID", 25, 11, 0, false),
                    CopybookLayout.text(FIELD_FILLER, 33, 427))).validateGeometry();

    /**
     * The card view of {@code app/cpy/CVEXPORT.cpy:93-100}, keyless and 460 bytes.
     *
     * <p>Assumptions: the card verification value at {@code app/cpy/CVEXPORT.cpy:96} is
     * {@code PIC 9(03) COMP}, a <b>two-byte halfword</b> and not three display bytes, because three
     * digit positions fall inside the four a halfword holds. The account identifier at
     * {@code app/cpy/CVEXPORT.cpy:95} is the same eleven-digit binary word as the
     * cross-reference view's.</p>
     *
     * <p>Assumptions: the verification value is the one field in this file constructed through the
     * nine-component descriptor constructor rather than a factory, because the sensitive marking has
     * to be combined with the binary kind and {@code CopybookLayout} publishes sensitive factories
     * only for the character and unsigned-display kinds. Its base-master counterpart at
     * {@code app/cpy/CVACT02Y.cpy} is an unsigned display field, which is why a sensitive binary
     * factory has had no prior caller. The explicit constructor validates that the declared two bytes
     * equal the width its digit count implies, so nothing is bypassed by taking this route.</p>
     */
    private static final CopybookLayout.RecordSpec CARD_VIEW =
            CopybookLayout.RecordSpec.keyless("EXPORT-CARD-DATA", PAYLOAD_LENGTH, List.of(
                    CopybookLayout.sensitiveText(FIELD_CARD_NUM, 0, 16),
                    CopybookLayout.binary("EXP-CARD-ACCT-ID", 16, 11, 0, false),
                    new CopybookLayout.FieldSpec(FIELD_CARD_CVV, 24,
                            CopybookLayout.binaryWidth(3, 0), CopybookLayout.Kind.BINARY,
                            3, 0, false, false, true),
                    CopybookLayout.sensitiveText("EXP-CARD-EMBOSSED-NAME", 26, 50),
                    CopybookLayout.text("EXP-CARD-EXPIRAION-DATE", 76, 10),
                    CopybookLayout.text("EXP-CARD-ACTIVE-STATUS", 86, 1),
                    CopybookLayout.text(FIELD_FILLER, 87, 373))).validateGeometry();

    /**
     * The five payload interpretations, keyed by the discriminator character that selects each.
     *
     * <p>Assumptions: <b>the character values themselves are the contract</b>, not merely an internal
     * encoding, and they are established by both halves of the baseline pair rather than inferred.
     * {@code app/cbl/CBEXPORT.cbl} writes them at lines 274, 343, 407, 462 and 527 -- {@code 'C'},
     * {@code 'A'}, {@code 'X'}, {@code 'T'} and {@code 'D'} respectively -- and
     * {@code app/cbl/CBIMPORT.cbl:272-286} reads the same five in an {@code EVALUATE} whose
     * {@code WHEN OTHER} branch is an error path rather than a default interpretation. A written
     * export file already holds these bytes, so changing one would strand every record produced before
     * the change.</p>
     *
     * <p>Alternatives Considered: comparing bare character literals at each dispatch site, which is
     * what the {@code EVALUATE} does and is the shortest translation of it. Rejected because the five
     * literals would then appear once in the decode dispatch and again in each encode path, and a
     * character constant that means "customer" in one place and nothing in another is exactly the
     * shape of duplication that lets four sites be updated and a fifth silently decode the wrong view.
     * Naming them once here makes the set closed and makes an unrecognised value a distinguishable
     * outcome rather than a missing branch.</p>
     */
    public enum RecordType {

        /** The customer view, written at {@code app/cbl/CBEXPORT.cbl:274} as {@code 'C'}. */
        CUSTOMER('C'),

        /** The account view, written at {@code app/cbl/CBEXPORT.cbl:343} as {@code 'A'}. */
        ACCOUNT('A'),

        /**
         * The card cross-reference view, written at {@code app/cbl/CBEXPORT.cbl:407} as {@code 'X'}.
         */
        CARD_XREF('X'),

        /** The transaction view, written at {@code app/cbl/CBEXPORT.cbl:462} as {@code 'T'}. */
        TRANSACTION('T'),

        /** The card view, written at {@code app/cbl/CBEXPORT.cbl:527} as {@code 'D'}. */
        CARD('D');

        /** The one-character discriminator this constant is selected by. */
        private final char discriminator;

        /**
         * Binds one constant to the discriminator character the baseline writes for it.
         *
         * @param discriminatorCharacter the single character {@code EXPORT-REC-TYPE} carries for this
         *     view, taken from the corresponding {@code MOVE} in {@code app/cbl/CBEXPORT.cbl}
         */
        RecordType(char discriminatorCharacter) {
            this.discriminator = discriminatorCharacter;
        }

        /**
         * The discriminator character that selects this view.
         *
         * @return the single character stored in {@code EXPORT-REC-TYPE} at offset zero
         */
        public char discriminator() {
            return discriminator;
        }

        /**
         * Resolves a discriminator character to the view it selects.
         *
         * @param candidate the character read from {@code EXPORT-REC-TYPE} at offset zero
         * @return the matching view, never {@code null}
         * @throws ExportRecordException if {@code candidate} is none of the five characters
         *     the baseline writes, because there is then no interpretation of the payload to return
         */
        public static RecordType ofDiscriminator(char candidate) {
            for (RecordType view : values()) {
                if (view.discriminator == candidate) {
                    return view;
                }
            }
            // WHY : Trade-offs: an unrecognised discriminator FAILS CLOSED here rather than
            //       defaulting to any view. The compromise accepted is that one unexpected record
            //       type stops a job instead of degrading it; what that buys is the elimination of
            //       the failure mode this migration treats as its worst, because a permissive
            //       default would read 460 bytes under the wrong geometry and every field it
            //       produced would be the right shape and the wrong value. The baseline draws the
            //       same line at app/cbl/CBIMPORT.cbl:283-284, whose WHEN OTHER branch counts and
            //       reports the record rather than interpreting it.
            // WHY : Assumptions: the offending character is reported by CODE POINT rather than as
            //       itself, because a byte that is not one of the five is frequently not printable
            //       and rendering it raw would put an unreadable or control character into a log
            //       line. app/cpy/CVEXPORT.cpy:10 declares the field PIC X(1), which admits any
            //       byte at all.
            throw new ExportRecordException("record " + RECORD_NAME
                    + " field " + EXPORT_RECORD.field(FIELD_REC_TYPE).describe()
                    + " holds code point " + (int) candidate
                    + ", which is none of the five discriminators app/cbl/CBEXPORT.cbl writes at its"
                    + " lines 274, 343, 407, 462 and 527; there is no default interpretation of the"
                    + " 460-byte payload, so no view is selected");
        }
    }

    /**
     * An opaque carrier for bytes that must never appear as a value, a log line or a message.
     *
     * <p>Purpose: this type exists for exactly one field, the card verification value at
     * {@code app/cpy/CVEXPORT.cpy:96}. The migration plan states at its section 0.4.1.9 that the
     * verification value is never returned by any endpoint and at section 0.4.1.3 that it is stored as
     * encrypted bytes, so it is excluded from every emitted field map. It is nonetheless retained in
     * this form because an export image must be reproducible from what a decode yielded, and because
     * an encrypt-on-ingest path needs some channel to receive it.</p>
     *
     * <p>Trade-offs: the compromise accepted is that <b>a decoded export record is not a complete
     * representation of its input</b> -- the card view's projection is missing one field its bytes
     * carried -- in exchange for the value never existing in cleartext outside the two-byte span it
     * was read from. It is deliberately never decoded to a number: holding the raw halfword means
     * there is no numeric variable anywhere in this module whose content is a verification value, and
     * the only way out is the single accessor named below.</p>
     *
     * <p>Assumptions: {@code CopybookLayout.FieldSpec} carries a {@code sensitive} flag and that flag
     * MARKS the field and does nothing else -- the shared kernel's charter is explicit that masking
     * and suppression belong to this package. The flag is set on the descriptor and this type is the
     * suppression; neither substitutes for the other.</p>
     */
    public static final class OpaqueSensitiveValue {

        /** The carrier that denotes a view which has no such value at all. */
        private static final OpaqueSensitiveValue ABSENT = new OpaqueSensitiveValue(null);

        /** The raw bytes, or {@code null} when this carrier is the absent one. */
        private final byte[] bytes;

        /**
         * Wraps a defensive copy of the supplied bytes, or nothing at all.
         *
         * @param rawBytes the bytes to carry, copied on the way in so no caller retains a reference
         *     into the carrier; {@code null} produces the absent carrier
         */
        private OpaqueSensitiveValue(byte[] rawBytes) {
            this.bytes = rawBytes == null ? null : rawBytes.clone();
        }

        /**
         * The exact byte width this carrier admits, which is the declared halfword span.
         *
         * <p>Refactoring Rationale: the width is PUBLISHED on the carrier rather than left as the
         * enclosing mapper's private field it is derived from. A caller offering bytes to this carrier
         * has to know the width it must offer, and a test asserting the refusal has to name the number
         * the message states; with the width private, both had to restate the literal 2, and a literal
         * restated in three places is a literal that will eventually disagree with the layout it came
         * from. It is the same value the enclosing record layout declares, so there is still exactly one
         * source.</p>
         */
        public static final int VALUE_WIDTH = CARD_CVV_WIDTH;

        /**
         * The carrier denoting that no verification value is present.
         *
         * <p>Refactoring Rationale: this accessor exists because the absent carrier was previously
         * reachable only from inside this file, which made {@link ExportRecord#ofCard} unusable by
         * any outside caller: that factory REQUIRES a carrier, and neither a present one nor an
         * absent one could be obtained. A card-view record could therefore be decoded from an image
         * but never assembled from a projection, so the migrated equivalent of the card export step
         * had no way to build the record it must write. Naming the absent state explicitly is also
         * what keeps it distinct from {@code null}, which the factory refuses.</p>
         *
         * @return the shared absent carrier, never {@code null} and never holding bytes
         */
        public static OpaqueSensitiveValue absent() {
            // WHY : Assumptions: one shared instance is returned rather than a fresh one per call,
            //       which is safe because the type is immutable -- the constructor copies on the way
            //       in and copyBytes copies on the way out, so no caller can reach the state of
            //       another. The absent carrier additionally holds no bytes at all, so there is
            //       nothing for sharing to expose.
            return ABSENT;
        }

        /**
         * Wraps a verification value read from a card record, defensively and opaquely.
         *
         * <p>Refactoring Rationale: this is the narrow public entry point {@link ExportRecord#ofCard}
         * needs, and it was previously absent -- the only constructor was private, so an outside
         * caller holding the two bytes had no way to hand them over. The factory is deliberately
         * NARROW rather than a general byte wrapper: it accepts exactly the declared width of the
         * verification span and nothing else, so a caller cannot use this carrier as a route for
         * arbitrary sensitive data whose disclosure rules have not been reasoned about here.</p>
         *
         * <p>Assumptions: the width is validated rather than trusted, and the reason is that the
         * carrier's whole purpose is to reach an encode. A carrier holding some other number of bytes
         * would fail later, inside a span copy, as a length failure naming an offset rather than as a
         * refusal naming the field -- and if it happened to be shorter it would leave part of the
         * target span holding whatever was already there.</p>
         *
         * <p>Alternatives Considered: accepting a {@code BigDecimal} or a {@code short} and encoding
         * it here, which would match the way every other numeric field of this record is supplied.
         * Rejected because it would create a numeric variable somewhere in this module whose content
         * is a verification value, which is exactly what this type exists to prevent -- the class
         * charter above states that the value is deliberately never decoded to a number. Bytes in and
         * bytes out keeps the cleartext confined to the span it was read from.</p>
         *
         * @param rawBytes the bytes of the verification span, whose length must be exactly
         *     {@link #VALUE_WIDTH}; they are copied on the way in, so the caller may zero its
         *     own array afterwards without emptying the carrier
         * @return a carrier holding a private copy of those bytes, never {@code null}
         * @throws ExportRecordException if {@code rawBytes} is {@code null} or its length is not
         *     exactly {@link #VALUE_WIDTH}
         */
        public static OpaqueSensitiveValue of(byte[] rawBytes) {
            if (rawBytes == null) {
                throw new ExportRecordException("record " + RECORD_NAME + " field " + FIELD_CARD_CVV
                        + " must be exactly " + VALUE_WIDTH
                        + " bytes; use absent() to denote that no value is present");
            }
            if (rawBytes.length != CARD_CVV_WIDTH) {
                // WHY : Assumptions: the refusal reports the SUPPLIED LENGTH and never the supplied
                //       bytes, on the same rule the redaction marker below follows -- a message that
                //       echoed the content would disclose through an exception the very value the
                //       type exists to keep out of every diagnostic.
                throw new ExportRecordException("record " + RECORD_NAME + " field " + FIELD_CARD_CVV
                        + " must be exactly " + VALUE_WIDTH
                        + " bytes, the width of the declared halfword span, but the carrier was"
                        + " offered " + rawBytes.length);
            }
            return new OpaqueSensitiveValue(rawBytes);
        }

        /**
         * Whether this carrier holds anything.
         *
         * @return {@code true} when bytes are carried, {@code false} for the absent carrier
         */
        public boolean isPresent() {
            return bytes != null;
        }

        /**
         * Hands the carried bytes to the encrypt-on-ingest path, and is the only way out of here.
         *
         * @return a fresh copy of the carried bytes, or {@code null} for the absent carrier
         */
        public byte[] copyBytes() {
            // WHY : Assumptions: a copy is returned rather than the array itself so that a caller
            //       cannot mutate the carrier, and so that a caller which zeroes its copy after
            //       encrypting does not thereby empty the carrier a re-encode still needs.
            return bytes == null ? null : bytes.clone();
        }

        /**
         * Renders this carrier as a redaction marker and never as its content.
         *
         * @return a constant marker, identical whatever the carried bytes are, so that no diagnostic,
         *     log line or exception message assembled from this object can disclose the value
         */
        @Override
        public String toString() {
            // WHY : Trade-offs: the marker does not distinguish present from absent, and losing that
            //       distinction in the rendering is deliberate. A marker that read differently when a
            //       value was present would confirm the presence of a verification value on a given
            //       card to anyone reading a log, which is information the record is not meant to
            //       disclose either. isPresent exists for code that legitimately needs to know.
            return REDACTED;
        }
    }

    /**
     * The 40-byte common prefix every export record carries, whatever its payload means.
     *
     * <p>Assumptions: the timestamp is held as the single 26-character field
     * {@code app/cpy/CVEXPORT.cpy:11} declares, and the overlay at
     * {@code app/cpy/CVEXPORT.cpy:12} is exposed as the three DERIVED accessors below rather than as
     * three further components. The overlay redescribes bytes that already exist -- its children at
     * lines 13 to 15 sum to the same 26 -- so three components would make the same region storable
     * twice and admit a prefix in which the parts and the whole disagree.</p>
     *
     * @param recordType the view the payload is to be read as, resolved from the discriminator at
     *     {@code app/cpy/CVEXPORT.cpy:10}
     * @param timestamp the 26 characters of {@code EXPORT-TIMESTAMP} exactly as the record carries
     *     them, neither trimmed nor reformatted
     * @param sequenceNumber the value of {@code EXPORT-SEQUENCE-NUM}, which is this record's key
     * @param branchId the four characters of {@code EXPORT-BRANCH-ID}, trailing blanks removed
     * @param regionCode the five characters of {@code EXPORT-REGION-CODE}, trailing blanks removed
     */
    public record Prefix(
            RecordType recordType,
            String timestamp,
            long sequenceNumber,
            String branchId,
            String regionCode) {

        /**
         * Rejects a prefix that is missing any component the record always carries.
         *
         * @param recordType the view the payload is to be read as, which may not be {@code null}
         * @param timestamp the 26 characters of {@code EXPORT-TIMESTAMP}, which must be exactly that
         *     width because the three derived accessors slice fixed sub-ranges of it
         * @param sequenceNumber the value of {@code EXPORT-SEQUENCE-NUM}, this record's key
         * @param branchId the branch identifier, which may not be {@code null}
         * @param regionCode the region code, which may not be {@code null}
         * @throws ExportRecordException if the view, the timestamp, the branch identifier or
         *     the region code is {@code null}, or if the timestamp is not exactly 26 characters
         */
        public Prefix {
            if (recordType == null || timestamp == null || branchId == null || regionCode == null) {
                throw new ExportRecordException("record " + RECORD_NAME
                        + " prefix requires a view, a timestamp, a branch identifier and a region"
                        + " code, and none of the four may be absent");
            }
            if (timestamp.length() != TimestampFormatter.TIMESTAMP_LENGTH) {
                // WHY : Assumptions: the width is checked rather than the content because
                //       app/cpy/CVEXPORT.cpy:11 declares PIC X(26) with no format at all, so 26
                //       blanks are as valid as a rendered stamp. Checking the width is what keeps the
                //       three derived accessors below total: each slices a fixed sub-range, and a
                //       shorter string would make them raise an index failure instead of returning
                //       the blanks the record actually holds.
                throw new ExportRecordException("record " + RECORD_NAME + " field "
                        + FIELD_TIMESTAMP + " is declared PIC X(26) at app/cpy/CVEXPORT.cpy:11 so its"
                        + " value must be exactly " + TimestampFormatter.TIMESTAMP_LENGTH
                        + " characters, but " + timestamp.length() + " were supplied");
            }
        }

        /**
         * The date portion of the timestamp, read through the line-12 overlay.
         *
         * @return the ten characters {@code EXPORT-DATE} describes at
         *     {@code app/cpy/CVEXPORT.cpy:13}, taken from within the 26-character field rather than
         *     from a component of its own
         */
        public String exportDate() {
            return timestamp.substring(0, OVERLAY_DATE_LENGTH);
        }

        /**
         * The one character separating the date and time portions, read through the same overlay.
         *
         * @return the single character {@code EXPORT-DATE-TIME-SEP} describes at
         *     {@code app/cpy/CVEXPORT.cpy:14}
         */
        public String dateTimeSeparator() {
            return timestamp.substring(OVERLAY_SEPARATOR_START, OVERLAY_TIME_START);
        }

        /**
         * The time portion of the timestamp, read through the same overlay.
         *
         * @return the fifteen characters {@code EXPORT-TIME} describes at
         *     {@code app/cpy/CVEXPORT.cpy:15}
         */
        public String exportTime() {
            return timestamp.substring(OVERLAY_TIME_START);
        }
    }

    /**
     * One decoded export record: its prefix, and the single payload projection its view yields.
     *
     * <p>Assumptions: exactly one of the five projection components is populated and the other four
     * are {@code null}, chosen by {@code prefix().recordType()}. That invariant is enforced by the
     * constructor below rather than documented and hoped for, because the whole hazard of this record
     * shape is a payload read under the wrong view.</p>
     *
     * <p>Alternatives Considered: carrying the view as a component of this record as well as of the
     * prefix, which would read slightly more naturally at a call site. Rejected because two copies of
     * one discriminator can disagree, and the disagreement would be undetectable -- there is nothing
     * in a 460-byte payload that says which view produced it. The view is held once, on the prefix
     * that carries the byte it was read from.</p>
     *
     * <p>Refactoring Rationale: this record shape was authored when this module declared no
     * {@code Customer} and no {@code Card} entity, and it argued from that absence that the two views
     * could only ever yield field maps. <b>Both entities now exist</b> --
     * {@link com.carddemo.batch.domain.Customer} and {@link com.carddemo.batch.domain.Card}, added so
     * that the export could emit all five of the reference's record types rather than three -- so
     * that argument no longer holds and is not repeated here. What did NOT change is the shape of
     * these five components, and the reason is stated below rather than left as inherited structure.
     * </p>
     *
     * <p>Assumptions: the two field-map components survive the arrival of the entities because
     * <b>decode and encode do not carry the same values</b>. Both new entities deliberately omit the
     * columns the target stores enciphered -- the customer's national and government-issued
     * identifiers and the card's verification value -- so neither type can hold them. A decode reads
     * a file this module did not write, whose customer view legitimately carries nine digits of
     * national identifier at {@code app/cpy/CVEXPORT.cpy:36} and twenty characters of
     * government-issued identifier at line 37; mapping the decode onto the entities would DISCARD
     * those values silently, which is a loss the import direction has no business taking. The field
     * maps preserve every span a foreign file presents. The two entity factories below therefore
     * serve the encode direction only, and they are the ones this module's own export calls.</p>
     *
     * <p>Alternatives Considered: two additional record components, so that a customer or card view
     * could arrive either as an entity or as a field map. Rejected because the constructor's invariant
     * is that exactly one of the payload components is populated, and adding a second admissible
     * carrier per view would make "exactly one" mean "exactly one view" rather than "exactly one
     * component" -- a weaker invariant expressed by more code. The factories convert at the boundary
     * instead, so the record still carries one populated component and the conversion is visible at
     * the one call site that needs it.</p>
     *
     * @param prefix the 40-byte common prefix, whose view selects which projection below is populated
     * @param customerFields the ordered projection of the customer view, or {@code null} for any other
     *     view; the trailing pad is excluded and the two encrypted-at-rest identifiers are present
     * @param account the account view mapped onto this module's entity, or {@code null} for any other
     *     view
     * @param transaction the transaction view mapped onto this module's entity, or {@code null} for
     *     any other view
     * @param cardXref the card cross-reference view mapped onto this module's entity, or {@code null}
     *     for any other view
     * @param cardFields the ordered projection of the card view, or {@code null} for any other view;
     *     the trailing pad and the card verification value are both excluded
     * @param cardVerificationValue the opaque carrier for the card view's verification value, which is
     *     the absent carrier for every other view and is never {@code null}
     */
    public record ExportRecord(
            Prefix prefix,
            Map<String, Object> customerFields,
            Account account,
            Transaction transaction,
            CardXref cardXref,
            Map<String, Object> cardFields,
            OpaqueSensitiveValue cardVerificationValue) {

        /**
         * Enforces that the populated projection is the one the prefix's view selects.
         *
         * @param prefix the 40-byte common prefix, which may not be {@code null}
         * @param customerFields the customer projection, populated only for the customer view
         * @param account the account projection, populated only for the account view
         * @param transaction the transaction projection, populated only for the transaction view
         * @param cardXref the cross-reference projection, populated only for that view
         * @param cardFields the card projection, populated only for the card view
         * @param cardVerificationValue the opaque carrier, which is the absent carrier rather than
         *     {@code null} for the four views that have no such value
         * @throws ExportRecordException if the prefix or the verification-value carrier is
         *     {@code null}, if the projection the view selects is absent, or if a projection belonging
         *     to any other view is present
         */
        public ExportRecord {
            if (prefix == null || cardVerificationValue == null) {
                throw new ExportRecordException("record " + RECORD_NAME
                        + " requires a prefix and a verification-value carrier; the carrier is the"
                        + " absent one rather than null for the four views that have no such value");
            }
            // WHY : Assumptions: the maps are wrapped unmodifiable over a copy taken here, so a
            //       caller that keeps its builder map cannot mutate a decoded record afterwards. The
            //       insertion-ordered copy is what makes "ordered field map" true of the value a
            //       caller receives and not merely of the map a decode happened to build.
            customerFields = unmodifiableCopy(customerFields);
            cardFields = unmodifiableCopy(cardFields);
            int populated = countPopulated(customerFields, account, transaction, cardXref, cardFields);
            if (populated != 1) {
                throw new ExportRecordException("record " + RECORD_NAME
                        + " must carry exactly one payload projection but " + populated
                        + " were supplied; the five views of app/cpy/CVEXPORT.cpy redefine one span"
                        + " and are alternatives rather than companions");
            }
            Object selected = switch (prefix.recordType()) {
                case CUSTOMER -> customerFields;
                case ACCOUNT -> account;
                case TRANSACTION -> transaction;
                case CARD_XREF -> cardXref;
                case CARD -> cardFields;
            };
            if (selected == null) {
                throw new ExportRecordException("record " + RECORD_NAME
                        + " prefix selects the " + prefix.recordType()
                        + " view but the projection supplied belongs to a different view; a payload"
                        + " read under the wrong view decodes plausibly and is wrong throughout");
            }
        }

        /**
         * Renders this record with its account number masked and every other sensitive field withheld.
         *
         * @return the view, the sequence number, the branch identifier and, for the three views that
         *     carry one, the account number with all but its last four digits replaced
         */
        @Override
        public String toString() {
            // WHY : Trade-offs: the rendering a record would otherwise generate is replaced wholesale
            //       and the loss of its convenience is accepted. It would print both field maps element
            //       by element -- for the customer view that means the national and government-issued
            //       identifiers, for the card view the embossed name -- into whatever log line or
            //       assertion message interpolated the record. What is kept is exactly what a
            //       diagnostic needs in order to say WHICH record is being discussed.
            String maskedPan = maskedCardNumberOf(this);
            return "ExportRecord[view=" + prefix.recordType()
                    + ", sequenceNumber=" + prefix.sequenceNumber()
                    + ", branchId=" + prefix.branchId()
                    + (maskedPan == null ? "" : ", cardNumber=" + maskedPan)
                    + ", sensitiveFields=" + REDACTED + "]";
        }

        /**
         * Wraps a field map as an unmodifiable insertion-ordered copy, passing {@code null} through.
         *
         * @param source the map to copy, or {@code null} for a projection this view does not carry
         * @return an unmodifiable insertion-ordered copy, or {@code null} when {@code source} was
         *     {@code null}
         */
        private static Map<String, Object> unmodifiableCopy(Map<String, Object> source) {
            return source == null
                    ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }

        /**
         * Counts how many of the five payload projections are present.
         *
         * @param customerFields the customer projection or {@code null}
         * @param account the account projection or {@code null}
         * @param transaction the transaction projection or {@code null}
         * @param cardXref the cross-reference projection or {@code null}
         * @param cardFields the card projection or {@code null}
         * @return the number of the five arguments that are not {@code null}
         */
        private static int countPopulated(Map<String, Object> customerFields, Account account,
                Transaction transaction, CardXref cardXref, Map<String, Object> cardFields) {
            int populated = 0;
            for (Object candidate
                    : new Object[] {customerFields, account, transaction, cardXref, cardFields}) {
                if (candidate != null) {
                    populated++;
                }
            }
            return populated;
        }

        /**
         * Builds a customer-view record.
         *
         * @param prefix the decoded prefix, whose view must be {@link RecordType#CUSTOMER}
         * @param fields the ordered customer projection, with the trailing pad already excluded
         * @return the assembled record
         * @throws ExportRecordException if the prefix selects any other view or the
         *     projection is {@code null}
         */
        public static ExportRecord ofCustomer(Prefix prefix, Map<String, Object> fields) {
            return new ExportRecord(prefix, fields, null, null, null, null,
                    OpaqueSensitiveValue.ABSENT);
        }

        /**
         * Builds a customer-view record from this module's read-only customer projection.
         *
         * <p>This is the export direction's entry point for the customer view, and it is the one the
         * export step calls. It exists so that no copybook field name appears in the job: the
         * projection's sixteen mapped columns become the view's eighteen named fields HERE, where the
         * view descriptor also lives, and the two the projection deliberately does not carry are
         * filled with the well-formed empty values recorded on {@link Customer}.</p>
         *
         * @param prefix the assembled prefix, whose view must be {@link RecordType#CUSTOMER}
         * @param customer the customer row to encode, which supplies sixteen of the view's eighteen
         *     named fields
         * @return the assembled record
         * @throws ExportRecordException if the prefix selects any other view
         * @throws IllegalArgumentException if {@code customer} is {@code null}
         */
        public static ExportRecord ofCustomer(Prefix prefix, Customer customer) {
            // WHY : Assumptions: the overload takes the ENTITY and delegates to the map-taking
            //       factory's constructor rather than duplicating it, so both directions converge on
            //       one invariant check. The two overloads are distinguished by argument type alone
            //       and neither is reachable with a null literal at any call site in this module.
            return new ExportRecord(prefix, customerFieldsOf(customer), null, null, null, null,
                    OpaqueSensitiveValue.ABSENT);
        }

        /**
         * Builds an account-view record.
         *
         * @param prefix the decoded prefix, whose view must be {@link RecordType#ACCOUNT}
         * @param account the account this record carries
         * @return the assembled record
         * @throws ExportRecordException if the prefix selects any other view or the account
         *     is {@code null}
         */
        public static ExportRecord ofAccount(Prefix prefix, Account account) {
            return new ExportRecord(prefix, null, account, null, null, null,
                    OpaqueSensitiveValue.ABSENT);
        }

        /**
         * Builds a transaction-view record.
         *
         * @param prefix the decoded prefix, whose view must be {@link RecordType#TRANSACTION}
         * @param transaction the transaction this record carries
         * @return the assembled record
         * @throws ExportRecordException if the prefix selects any other view or the
         *     transaction is {@code null}
         */
        public static ExportRecord ofTransaction(Prefix prefix, Transaction transaction) {
            return new ExportRecord(prefix, null, null, transaction, null, null,
                    OpaqueSensitiveValue.ABSENT);
        }

        /**
         * Builds a card cross-reference view record.
         *
         * @param prefix the decoded prefix, whose view must be {@link RecordType#CARD_XREF}
         * @param cardXref the cross-reference this record carries
         * @return the assembled record
         * @throws ExportRecordException if the prefix selects any other view or the
         *     cross-reference is {@code null}
         */
        public static ExportRecord ofCardXref(Prefix prefix, CardXref cardXref) {
            return new ExportRecord(prefix, null, null, null, cardXref, null,
                    OpaqueSensitiveValue.ABSENT);
        }

        /**
         * Builds a card-view record, whose verification value travels only in the opaque carrier.
         *
         * @param prefix the decoded prefix, whose view must be {@link RecordType#CARD}
         * @param fields the ordered card projection, with the trailing pad and the verification value
         *     both already excluded
         * @param verificationValue the opaque carrier for the two-byte verification value, or the
         *     absent carrier when the record is being assembled without one
         * @return the assembled record
         * @throws ExportRecordException if the prefix selects any other view, the projection
         *     is {@code null}, or the carrier is {@code null}
         */
        public static ExportRecord ofCard(Prefix prefix, Map<String, Object> fields,
                OpaqueSensitiveValue verificationValue) {
            return new ExportRecord(prefix, null, null, null, null, fields, verificationValue);
        }

        /**
         * Builds a card-view record from this module's read-only card projection.
         *
         * <p>This is the export direction's entry point for the card view, and it is the one the
         * export step calls. The verification value is the ABSENT carrier and not a value, because
         * {@link Card} does not map the enciphered column it would come from; the encode consequently
         * writes the two-byte encoding of zero over that span, which is a well-formed field rather
         * than a hole.</p>
         *
         * @param prefix the assembled prefix, whose view must be {@link RecordType#CARD}
         * @param card the card row to encode, which supplies five of the view's six named fields
         * @return the assembled record
         * @throws ExportRecordException if the prefix selects any other view
         * @throws IllegalArgumentException if {@code card} is {@code null}
         */
        public static ExportRecord ofCard(Prefix prefix, Card card) {
            // WHY : Assumptions: the absent carrier is passed EXPLICITLY rather than left to a
            //       shorter overload, because the alternative reading -- that a verification value
            //       was simply forgotten here -- is the one a reader would otherwise reach for. The
            //       absence is a registered divergence, not an omission, and it is recorded on the
            //       Card projection this factory takes.
            return new ExportRecord(prefix, null, null, null, null, cardFieldsOf(card),
                    OpaqueSensitiveValue.ABSENT);
        }
    }

    /**
     * Prevents instantiation, because this type holds no state a caller could vary.
     */
    private ExportRecordMapper() {
        // WHY : Alternatives Considered: an injectable instance, as the sibling
        //       CardXrefRecordMapper is. Rejected because the six descriptors here are immutable
        //       constants and the codecs are stateless holders, so an instance would carry no
        //       distinguishing state and a job would have to be wired to obtain a conversion that
        //       depends on nothing. The static shape matches AccountRecordMapper and
        //       TransactionRecordMapper as well as the three shared-kernel types this file consumes,
        //       and the charter beside this file forbids a shared supertype among these mappers, so
        //       no polymorphism is given up that was available.
    }

    /**
     * The 500-byte record descriptor, exposed so its geometry is assertable from outside.
     *
     * @return the immutable descriptor of the prefix and payload span, whose retrieval key is the
     *     four-byte sequence number at offset 27
     */
    public static CopybookLayout.RecordSpec recordLayout() {
        return EXPORT_RECORD;
    }

    /**
     * The 460-byte descriptor of one payload view.
     *
     * @param view the view whose geometry is wanted
     * @return the immutable descriptor of that view, contiguous from offset zero and summing to 460
     * @throws ExportRecordException if {@code view} is {@code null}, because there is then no
     *     view to resolve
     */
    public static CopybookLayout.RecordSpec viewLayout(RecordType view) {
        if (view == null) {
            throw new ExportRecordException("record " + RECORD_NAME
                    + " has five payload views and no default, so a view must be named");
        }
        return switch (view) {
            case CUSTOMER -> CUSTOMER_VIEW;
            case ACCOUNT -> ACCOUNT_VIEW;
            case TRANSACTION -> TRANSACTION_VIEW;
            case CARD_XREF -> CARD_XREF_VIEW;
            case CARD -> CARD_VIEW;
        };
    }

    /**
     * Reads the discriminator at offset zero, before any payload byte is interpreted.
     *
     * @param image the 500-byte export record image
     * @return the view the payload is to be read as
     * @throws ExportRecordException if the image is {@code null} or not 500 bytes, or if the
     *     discriminator is none of the five characters the baseline writes
     */
    public static RecordType recordTypeOf(byte[] image) {
        // WHY : Assumptions: this is the FIRST thing any caller does with an image, and it is a
        //       separate entry point rather than only an internal step because
        //       app/cpy/CVEXPORT.cpy:19 gives the same 460 bytes five incompatible meanings and
        //       nothing in those bytes says which applies. A caller that routes records by type --
        //       which is what app/cbl/CBIMPORT.cbl:272-286 does -- needs the discriminator without
        //       committing to a full decode.
        String discriminator = textOf(decodedPrefixField(image, FIELD_REC_TYPE), FIELD_REC_TYPE);
        return RecordType.ofDiscriminator(discriminator.charAt(0));
    }

    /**
     * Decodes the 40-byte common prefix, which every view shares.
     *
     * @param image the 500-byte export record image
     * @return the decoded prefix, whose timestamp overlay is available as derived accessors
     * @throws ExportRecordException if the image is {@code null} or not 500 bytes, if the
     *     discriminator is unrecognised, or if a prefix field decodes to an unexpected type
     */
    public static Prefix decodePrefix(byte[] image) {
        // WHY : Assumptions: the five prefix scalars are decoded FIELD BY FIELD rather than by handing
        //       the whole 500-byte descriptor to the record-level decode, and the reason is a hard
        //       constraint of the shared codec rather than a preference. That codec reads character
        //       fields under US-ASCII or IBM037 and verifies the slice is byte-reversible, so any byte
        //       at or above 0x80 in a character field is rejected. EXPORT-RECORD-DATA is declared
        //       PIC X(460) at app/cpy/CVEXPORT.cpy:19 and yet routinely holds packed nibble pairs and
        //       binary words -- a packed field carrying the digits 99 is the single byte 0x99 -- so
        //       decoding the payload as characters would fail on valid input. The span stays in the
        //       descriptor because the record must be provably 500 bytes; it is simply never read as
        //       text.
        long sequenceNumber = sequenceNumberOf(image);
        String discriminator = textOf(decodedPrefixField(image, FIELD_REC_TYPE), FIELD_REC_TYPE);
        return new Prefix(
                RecordType.ofDiscriminator(discriminator.charAt(0)),
                textOf(decodedPrefixField(image, FIELD_TIMESTAMP), FIELD_TIMESTAMP),
                sequenceNumber,
                stripTrailingBlanks(
                        textOf(decodedPrefixField(image, FIELD_BRANCH_ID), FIELD_BRANCH_ID)),
                stripTrailingBlanks(
                        textOf(decodedPrefixField(image, FIELD_REGION_CODE), FIELD_REGION_CODE)));
    }

    /**
     * Decodes a whole export record: the prefix once, then the one view its discriminator selects.
     *
     * @param image the 500-byte export record image
     * @return the decoded record, carrying exactly one payload projection
     * @throws ExportRecordException if the image is {@code null} or not 500 bytes, or if the
     *     discriminator is none of the five the baseline writes
     * @throws FixedWidthCodec.FieldCodecException if a payload field cannot be decoded under the
     *     geometry its view declares
     * @throws IllegalArgumentException if a decoded value is not a value its target property admits,
     *     such as a date span that is neither blank nor a calendar date
     */
    public static ExportRecord decode(byte[] image) {
        Prefix prefix = decodePrefix(image);
        byte[] payload = payloadOf(image);
        RecordType view = prefix.recordType();
        Map<String, Object> decoded = FixedWidthCodec.decodeRecord(payload, viewLayout(view));
        // WHY : Assumptions: the dispatch is an exhaustive switch over the enumeration rather than a
        //       chain of comparisons with a trailing else, so adding a sixth constant would fail to
        //       compile here instead of silently taking a fallback branch. There is deliberately no
        //       default arm: the unrecognised case was already rejected while resolving the prefix,
        //       and a default here would be a second, weaker answer to a question already settled.
        return switch (view) {
            case CUSTOMER -> ExportRecord.ofCustomer(prefix, projectionOf(decoded, CUSTOMER_VIEW));
            case ACCOUNT -> ExportRecord.ofAccount(prefix, accountOf(decoded));
            case TRANSACTION -> ExportRecord.ofTransaction(prefix, transactionOf(decoded));
            case CARD_XREF -> ExportRecord.ofCardXref(prefix, cardXrefOf(decoded));
            case CARD -> ExportRecord.ofCard(prefix, cardProjectionOf(decoded),
                    verificationValueOf(payload));
        };
    }

    /**
     * Encodes a decoded record back to a 500-byte image, with the key as a field of that image.
     *
     * <p>Refactoring Rationale: the record key is the divergence this file carries, and the framing is
     * exact. {@code app/cbl/CBEXPORT.cbl:65-69} selects the export file as
     * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} and
     * {@code RECORD KEY IS EXPORT-SEQUENCE-NUM}. Its file description at
     * {@code app/cbl/CBEXPORT.cbl:89-92} declares {@code RECORD CONTAINS 500 CHARACTERS} over a bare
     * {@code 01 EXPORT-OUTPUT-RECORD PIC X(500).} that carries no subordinate items at all, so the name
     * in the record-key clause resolves to the working-storage copy taken at
     * {@code app/cbl/CBEXPORT.cbl:94-96} -- the field declared at {@code app/cpy/CVEXPORT.cpy:16} --
     * and not to a field of the file's own record description. {@code app/cbl/CBIMPORT.cbl:37-41}
     * declares the mirror image of the same arrangement on the input side. <b>The baseline resolves the
     * record-key name to a working-storage item rather than to a field of the file record; the Java
     * resolves the key to the sequence number at offset 27 within the record image; the divergence is
     * documented in {@code docs/architecture/cobol-to-service-traceability.md}.</b> Nothing under
     * {@code app/**} is altered by this work, and the migration plan's section 0.5.1.7 is what rules
     * that the Java key be a field of the record it keys.</p>
     *
     * <p>Assumptions: the five inputs those five views come from are the five indexed files selected at
     * {@code app/cbl/CBEXPORT.cbl:35-63}, keyed by customer identifier, account identifier, card
     * number, transaction identifier and card number, whose file descriptions at
     * {@code app/cbl/CBEXPORT.cbl:74-87} copy the customer, account, cross-reference, transaction and
     * card layouts. That is context for where the views came from; this mapper performs no
     * input or output of any kind.</p>
     *
     * <p>Trade-offs: three spans are re-rendered from the decoded value rather than reproduced from the
     * bytes they arrived in, so this entry point round-trips a valid image exactly only when that image
     * was already in the target form. A signed zoned or packed field carrying <b>negative zero</b>
     * re-encodes with the positive sign carrier, because the value it decoded to has no sign to
     * preserve; a timestamp that arrived in the baseline 26-character form re-encodes in the target
     * form, because neither form is normalised into the other; and a trailing pad is written as blanks,
     * which is the ruling this package's charter sets for every mapper here. When a caller needs the
     * original bytes back regardless, {@link #toRecord(ExportRecord, byte[])} takes the source image
     * and restores those spans from it.</p>
     *
     * @param decoded the record to encode, carrying the one projection its view selects
     * @return exactly 500 bytes, with the sequence number occupying offsets 27 through 30
     * @throws ExportRecordException if {@code decoded} is {@code null}
     * @throws FixedWidthCodec.FieldCodecException if a projection value does not fit the span its
     *     descriptor declares
     * @throws IllegalArgumentException if a projection is missing a value its view requires
     */
    public static byte[] toRecord(ExportRecord decoded) {
        requireDecoded(decoded);
        byte[] image = new byte[RECORD_LENGTH];
        Prefix prefix = decoded.prefix();
        FixedWidthCodec.encodeField(String.valueOf(prefix.recordType().discriminator()),
                EXPORT_RECORD.field(FIELD_REC_TYPE), image);
        FixedWidthCodec.encodeField(prefix.timestamp(),
                EXPORT_RECORD.field(FIELD_TIMESTAMP), image);
        // WHY : Assumptions: the key is written through the descriptor's own BINARY span, so the four
        //       bytes at offsets 27 through 30 are a genuine field of the image rather than a value
        //       held beside it. That is the whole substance of the divergence recorded above: the key
        //       is addressable within the record it keys.
        FixedWidthCodec.encodeField(BigDecimal.valueOf(prefix.sequenceNumber()),
                EXPORT_RECORD.field(FIELD_SEQUENCE_NUM), image);
        FixedWidthCodec.encodeField(prefix.branchId(),
                EXPORT_RECORD.field(FIELD_BRANCH_ID), image);
        FixedWidthCodec.encodeField(prefix.regionCode(),
                EXPORT_RECORD.field(FIELD_REGION_CODE), image);
        System.arraycopy(encodePayload(decoded), 0, image, PAYLOAD_OFFSET, PAYLOAD_LENGTH);
        return image;
    }

    /**
     * Encodes a decoded record while restoring from the source image the spans a re-render cannot.
     *
     * <p>Refactoring Rationale: the image is now checked for BELONGING as well as for length, and the
     * verification value is no longer taken from it at all. Those two changes address one weakness:
     * the image is an ordinary parameter, so nothing established that the bytes restored from it were
     * the bytes this record was read from, and the most sensitive of those bytes -- the card
     * verification value -- was copied straight across. See {@link #requireMatchingImage(ExportRecord,
     * byte[])} for what is checked and {@link #restoreVerificationValue(ExportRecord,
     * CopybookLayout.RecordSpec, byte[])} for where the value comes from instead.</p>
     *
     * @param decoded the record to encode, carrying the one projection its view selects
     * @param sourceImage the 500-byte image {@code decoded} was read from, used only as the source of
     *     the sign carriers, the trailing pad and the unchanged timestamps; it must carry the same
     *     discriminator and sequence number as {@code decoded}, and it is NOT the source of the
     *     verification value
     * @return exactly 500 bytes, identical to {@code sourceImage} wherever the decoded projection
     *     still holds the value that image carried
     * @throws ExportRecordException if {@code decoded} is {@code null}, if {@code sourceImage} is
     *     {@code null} or not 500 bytes, or if the image's discriminator or sequence number differs
     *     from the decoded record's
     * @throws FixedWidthCodec.FieldCodecException if a projection value does not fit the span its
     *     descriptor declares
     * @throws IllegalArgumentException if a projection is missing a value its view requires
     */
    public static byte[] toRecord(ExportRecord decoded, byte[] sourceImage) {
        requireDecoded(decoded);
        requireImage(sourceImage);
        // WHY : Assumptions: the belonging check runs BEFORE the encode rather than after it, so a
        //       mismatched image is refused without the record ever being rendered. Ordering it the
        //       other way would leave a fully encoded 500-byte array in hand at the moment of refusal,
        //       which is the kind of value that gets logged.
        requireMatchingImage(decoded, sourceImage);
        byte[] image = toRecord(decoded);
        byte[] sourcePayload = payloadOf(sourceImage);
        CopybookLayout.RecordSpec view = viewLayout(decoded.prefix().recordType());
        // WHY : Trade-offs: the sign-preserving encode is applied to the PAYLOAD alone and not to the
        //       whole image, because the only signed numeric spans in this record are inside the
        //       views -- the prefix's one numeric field is an unsigned binary sequence number, which
        //       has no sign carrier to preserve. Restricting the operation to the payload also keeps
        //       the width the codec checks equal to the view's declared 460, so a caller that passed
        //       a wrongly sized image is told so by width rather than by an out-of-bounds failure.
        byte[] payload = FixedWidthCodec.encodeRecordPreservingSign(
                fieldMapFor(decoded, view), view, sourcePayload);
        copySpan(sourcePayload, payload, view.field(FIELD_FILLER));
        restoreViewSpecificSpans(decoded, view, sourcePayload, payload);
        System.arraycopy(payload, 0, image, PAYLOAD_OFFSET, PAYLOAD_LENGTH);
        return image;
    }

    /**
     * Projects a decoded view into the ordered field map a caller receives, minus the trailing pad.
     *
     * <p>Assumptions: the pad is dropped here rather than by the shared codec, and the reason is
     * mechanical. That codec omits a blank pad only when the descriptor it was handed is the registered
     * instance, tested by identity, and these six descriptors are deliberately local -- so the pad
     * arrives in the decoded map and this method removes it. Transformation rule T1 requires the drop be
     * RECORDED, and the widths dropped are 134 bytes for the customer view at
     * {@code app/cpy/CVEXPORT.cpy:42}, 352 for the account view at line 60, 140 for the transaction
     * view at line 79, 427 for the cross-reference view at line 88 and 373 for the card view at line
     * 100. Each is a bare {@code FILLER} carrying neither a {@code REDEFINES} clause nor a
     * {@code VALUE} clause, which is what makes it padding to the fixed record length rather than
     * content.</p>
     *
     * <p>Alternatives Considered: also trimming the trailing blanks of every character field, as the
     * entity-backed views do for their descriptive fields. Rejected for the two map-backed views
     * because a field map is a projection of the RECORD whereas an entity is a target-side object with
     * typed properties: the loader that consumes these maps applies its own column normalisation, and
     * leaving the spans at their declared widths is what lets a re-encode reproduce the input without a
     * per-field re-padding rule this file would then have to hold. The two views that do map to
     * entities keep the sibling mappers' trimming conventions exactly.</p>
     *
     * @param decoded the complete field map the codec produced for one view
     * @param view the descriptor that map was decoded against, used to name the pad to remove
     * @return an insertion-ordered map holding every named field of the view and not its pad
     */
    private static Map<String, Object> projectionOf(Map<String, Object> decoded,
            CopybookLayout.RecordSpec view) {
        Map<String, Object> projection = new LinkedHashMap<>(decoded);
        projection.remove(view.field(FIELD_FILLER).name());
        return projection;
    }

    /**
     * Projects the card view, dropping the trailing pad and suppressing the verification value.
     *
     * <p>Trade-offs: the card verification value at {@code app/cpy/CVEXPORT.cpy:96} is removed here and
     * appears in no map this file returns, no log line, no exception message and no rendering. The
     * migration plan states at its section 0.4.1.9 that it is never returned by any endpoint and at
     * section 0.4.1.3 that it is stored as encrypted bytes, so the compromise accepted is that the map
     * returned from this method is <b>not a complete representation of the bytes it was read from</b> --
     * one declared field is deliberately missing from it. What that buys is that the value exists in
     * cleartext nowhere outside the two-byte span it was read from: it is handed on only through the
     * opaque carrier, whose single accessor feeds the encrypt-on-ingest path. Its descriptor also sets
     * the {@code sensitive} flag, and the division is worth stating plainly because a reader who
     * assumed otherwise would ship the value -- <b>the flag marks the field and this file performs the
     * suppression</b>.</p>
     *
     * @param decoded the complete field map the codec produced for the card view
     * @return an insertion-ordered map holding the card view's other named fields only
     */
    private static Map<String, Object> cardProjectionOf(Map<String, Object> decoded) {
        Map<String, Object> projection = projectionOf(decoded, CARD_VIEW);
        projection.remove(CARD_VIEW.field(FIELD_CARD_CVV).name());
        return projection;
    }

    /**
     * Lifts the verification value's raw bytes straight out of the payload into the opaque carrier.
     *
     * <p>Assumptions: the two bytes are copied as bytes and are never decoded to a number, so no
     * numeric variable anywhere in this module ever holds a verification value. The span is taken from
     * the descriptor rather than written as a literal pair of offsets, so the halfword width that
     * {@code app/cpy/CVEXPORT.cpy:96} implies is stated in exactly one place.</p>
     *
     * @param payload the 460-byte card-view payload
     * @return the opaque carrier holding those two bytes
     */
    private static OpaqueSensitiveValue verificationValueOf(byte[] payload) {
        CopybookLayout.FieldSpec field = CARD_VIEW.field(FIELD_CARD_CVV);
        return new OpaqueSensitiveValue(
                Arrays.copyOfRange(payload, field.start(), field.end()));
    }

    /**
     * Maps the account view onto this module's account entity.
     *
     * <p>Assumptions: the misspelling is carried across here and nowhere else.
     * {@code app/cpy/CVEXPORT.cpy:54} declares {@code EXP-ACCT-EXPIRAION-DATE}, without the
     * {@code T} in the third syllable, and this is the <b>second</b> occurrence of that same baseline
     * spelling in this module's sources -- the first is the base master's
     * {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:11}, which the sibling
     * {@code AccountRecordMapper} owns. <b>Both spellings map to the one target column,
     * {@code accounts.expiration_date}, which names the field for its ordinary spelling and is
     * reached here through the entity's expiration-date property.</b>
     * Both citations are given so that a reader meeting the baseline spelling beside the target name
     * does not take it for a typo introduced here; the rename is registered in
     * {@code docs/architecture/data-model-and-schema-mapping.md} and happens at this boundary only, so
     * no type outside this package sees the baseline spelling.</p>
     *
     * <p>Assumptions: the two adjacent ten-character fields are treated differently on purpose and the
     * asymmetry is the sibling's ruling rather than a fresh one -- the group identifier keeps all ten
     * characters because its blank padding is load-bearing for the disclosure-group join, while the ZIP
     * is descriptive and has its padding removed and restored on encode. {@code AccountRecordMapper}
     * documents that at length for the same two fields of the master and this view follows it
     * unchanged. The account view likewise carries <b>no version column</b>: optimistic-locking state
     * is a target-side artifact with no span in any byte image, which is the same ruling the sibling
     * records for the master.</p>
     *
     * @param decoded the complete field map the codec produced for the account view
     * @return the account entity the view's twelve named fields populate
     * @throws IllegalArgumentException if a field decodes to a type its property does not admit, if a
     *     money field arrives at a scale other than two, or if a date span is neither all blanks nor a
     *     calendar date
     */
    private static Account accountOf(Map<String, Object> decoded) {
        return new Account(
                unsignedOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-ID"),
                textOf(require(decoded, ACCOUNT_VIEW, "EXP-ACCT-ACTIVE-STATUS"),
                        "EXP-ACCT-ACTIVE-STATUS"),
                moneyOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-CURR-BAL"),
                moneyOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-CREDIT-LIMIT"),
                moneyOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-CASH-CREDIT-LIMIT"),
                isoDateOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-OPEN-DATE"),
                isoDateOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-EXPIRAION-DATE"),
                isoDateOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-REISSUE-DATE"),
                moneyOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-CURR-CYC-CREDIT"),
                moneyOf(decoded, ACCOUNT_VIEW, "EXP-ACCT-CURR-CYC-DEBIT"),
                stripTrailingBlanks(textOf(require(decoded, ACCOUNT_VIEW, "EXP-ACCT-ADDR-ZIP"),
                        "EXP-ACCT-ADDR-ZIP")),
                textOf(require(decoded, ACCOUNT_VIEW, "EXP-ACCT-GROUP-ID"), "EXP-ACCT-GROUP-ID"));
    }

    /**
     * Maps the transaction view onto this module's transaction entity.
     *
     * <p>Assumptions: every field convention here is the sibling {@code TransactionRecordMapper}'s and
     * is reused rather than re-decided. The identifier, the type code and the card number are handed on
     * at their exact declared widths because they are keys and a key compared against one read from
     * another fixed-width record must be compared like with like. The category code is a NUMBER stored
     * as zero-padded digits, so it is padded back to its declared four characters rather than handed on
     * as the bare integer, which would be the correct number and the wrong join key. A 26-blank
     * timestamp is a legitimate absent value and decodes to nothing rather than raising, and <b>the
     * baseline byte pattern is never normalised into the target pattern</b> in either direction. The
     * card number is carried in full into the entity and masked in every diagnostic.</p>
     *
     * @param decoded the complete field map the codec produced for the transaction view
     * @return the transaction entity the view's thirteen named fields populate
     * @throws IllegalArgumentException if a field decodes to a type its property does not admit, or if
     *     a timestamp span is neither blank nor one of the two 26-character forms
     */
    private static Transaction transactionOf(Map<String, Object> decoded) {
        Transaction transaction = new Transaction(
                textOf(require(decoded, TRANSACTION_VIEW, "EXP-TRAN-ID"), "EXP-TRAN-ID"));
        transaction.setTypeCd(
                textOf(require(decoded, TRANSACTION_VIEW, "EXP-TRAN-TYPE-CD"), "EXP-TRAN-TYPE-CD"));
        transaction.setCategoryCd(zeroPad(
                Long.toString(unsignedOf(decoded, TRANSACTION_VIEW, "EXP-TRAN-CAT-CD")),
                TRANSACTION_VIEW.field("EXP-TRAN-CAT-CD").length()));
        transaction.setSource(stripTrailingBlanks(
                textOf(require(decoded, TRANSACTION_VIEW, "EXP-TRAN-SOURCE"), "EXP-TRAN-SOURCE")));
        transaction.setDescription(stripTrailingBlanks(
                textOf(require(decoded, TRANSACTION_VIEW, "EXP-TRAN-DESC"), "EXP-TRAN-DESC")));
        transaction.setAmount(moneyOf(decoded, TRANSACTION_VIEW, "EXP-TRAN-AMT"));
        transaction.setMerchantId(unsignedOf(decoded, TRANSACTION_VIEW, "EXP-TRAN-MERCHANT-ID"));
        transaction.setMerchantName(stripTrailingBlanks(textOf(
                require(decoded, TRANSACTION_VIEW, "EXP-TRAN-MERCHANT-NAME"),
                "EXP-TRAN-MERCHANT-NAME")));
        transaction.setMerchantCity(stripTrailingBlanks(textOf(
                require(decoded, TRANSACTION_VIEW, "EXP-TRAN-MERCHANT-CITY"),
                "EXP-TRAN-MERCHANT-CITY")));
        transaction.setMerchantZip(stripTrailingBlanks(textOf(
                require(decoded, TRANSACTION_VIEW, "EXP-TRAN-MERCHANT-ZIP"),
                "EXP-TRAN-MERCHANT-ZIP")));
        transaction.setCardNum(
                textOf(require(decoded, TRANSACTION_VIEW, "EXP-TRAN-CARD-NUM"), "EXP-TRAN-CARD-NUM"));
        transaction.setOrigTs(timestampOf(decoded, TRANSACTION_VIEW, "EXP-TRAN-ORIG-TS"));
        transaction.setProcTs(timestampOf(decoded, TRANSACTION_VIEW, "EXP-TRAN-PROC-TS"));
        return transaction;
    }

    /**
     * Maps the card cross-reference view onto this module's cross-reference entity.
     *
     * @param decoded the complete field map the codec produced for the cross-reference view
     * @return the cross-reference entity the view's three named fields populate
     * @throws IllegalArgumentException if a field decodes to a type its property does not admit
     */
    private static CardXref cardXrefOf(Map<String, Object> decoded) {
        return new CardXref(
                textOf(require(decoded, CARD_XREF_VIEW, "EXP-XREF-CARD-NUM"), "EXP-XREF-CARD-NUM"),
                unsignedOf(decoded, CARD_XREF_VIEW, "EXP-XREF-CUST-ID"),
                unsignedOf(decoded, CARD_XREF_VIEW, "EXP-XREF-ACCT-ID"));
    }

    /**
     * Encodes one record's payload to exactly 460 bytes under the view its prefix selects.
     *
     * @param decoded the record whose payload is wanted
     * @return the 460 bytes of {@code EXPORT-RECORD-DATA} for that record
     * @throws FixedWidthCodec.FieldCodecException if a projection value does not fit its declared span
     * @throws IllegalArgumentException if a projection is missing a value its view requires
     */
    private static byte[] encodePayload(ExportRecord decoded) {
        CopybookLayout.RecordSpec view = viewLayout(decoded.prefix().recordType());
        byte[] payload = FixedWidthCodec.encodeRecord(fieldMapFor(decoded, view), view);
        if (decoded.prefix().recordType() == RecordType.CARD
                && decoded.cardVerificationValue().isPresent()) {
            // WHY : Trade-offs: the verification value is written over its span as the raw halfword
            //       the carrier holds, AFTER the record-level encode has written a well-formed
            //       encoded zero there. The alternative -- decoding the carried bytes to a number and
            //       putting that in the field map -- was rejected because it would create a numeric
            //       variable in this module whose content is a verification value, which is exactly
            //       the state the suppression exists to prevent. Overwriting the span costs one extra
            //       array copy and keeps the value a byte span from the moment it is read to the
            //       moment it is written.
            CopybookLayout.FieldSpec cvv = view.field(FIELD_CARD_CVV);
            System.arraycopy(decoded.cardVerificationValue().copyBytes(), 0,
                    payload, cvv.start(), cvv.length());
        }
        return payload;
    }

    /**
     * Assembles the complete field map one view's encode needs, pad included.
     *
     * <p>Assumptions: the pad is supplied explicitly as a blank run of the descriptor's own length,
     * which is the second consequence of these descriptors being local rather than registered. The
     * shared codec blank-fills a missing pad only for a registered descriptor and otherwise rejects the
     * map as missing a required field, so omitting it here would fail rather than pad. The run is sized
     * from {@code field.length()} rather than written as a literal so that a pad width edited in the
     * descriptor cannot leave a literal measured against a span it no longer fits.</p>
     *
     * @param decoded the record to encode
     * @param view the descriptor the map must satisfy
     * @return an insertion-ordered map naming every field the view declares, including its pad
     * @throws IllegalArgumentException if a projection is missing a value its view requires
     */
    private static Map<String, Object> fieldMapFor(ExportRecord decoded,
            CopybookLayout.RecordSpec view) {
        Map<String, Object> fields = switch (decoded.prefix().recordType()) {
            case CUSTOMER -> new LinkedHashMap<>(decoded.customerFields());
            case ACCOUNT -> accountFieldMap(decoded.account());
            case TRANSACTION -> transactionFieldMap(decoded.transaction());
            case CARD_XREF -> cardXrefFieldMap(decoded.cardXref());
            case CARD -> cardFieldMap(decoded);
        };
        CopybookLayout.FieldSpec pad = view.field(FIELD_FILLER);
        fields.put(pad.name(), "");
        return fields;
    }

    /**
     * Builds the customer view's ordered projection from this module's customer entity.
     *
     * <p>Trade-offs: two of the view's eighteen named fields are filled with empty values rather than
     * with data, because {@link Customer} does not map the enciphered columns they would come from --
     * the national identifier is written as nine zero digits and the government-issued identifier as
     * twenty blanks. Both are valid values of their declared pictures, so the record stays well formed
     * and the counterpart import still routes it; what is given up is byte-identity with a reference
     * export on those two spans alone. The divergence is registered as
     * {@code D-EXPORT-PROTECTED-FIELDS-ELIDED} in
     * {@code docs/architecture/cobol-to-service-traceability.md} and argued at length on the
     * projection itself. Refusing to encode without them was the alternative and was rejected: it
     * would mean this module could not export customers at all, which is the very gap this method
     * closes.</p>
     *
     * @param customer the customer to encode, which supplies sixteen of the eighteen named fields
     * @return an insertion-ordered map in copybook declaration order, without the pad
     * @throws IllegalArgumentException if {@code customer} is {@code null}
     */
    private static Map<String, Object> customerFieldsOf(Customer customer) {
        requireProjection(customer, RecordType.CUSTOMER);
        // WHY : Assumptions: insertion order follows the copybook declaration order of
        //       app/cpy/CVEXPORT.cpy:25-41 so that the map reads in the order the codec walks the
        //       descriptor. The codec keys by name and does not require it, but a map ordered
        //       differently from the record it produces is one a reader has to reconcile by hand.
        // WHY : Assumptions: the three address lines and the two telephone numbers are named with
        //       their subscripts -- EXP-CUST-ADDR-LINE(1) and so on -- because the copybook declares
        //       them as OCCURS groups at app/cpy/CVEXPORT.cpy:29 and :34, and the view descriptor
        //       spells the subscript into the field name for exactly that reason. A flat name would
        //       not resolve against the descriptor at all.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("EXP-CUST-ID", customer.getCustomerId());
        fields.put("EXP-CUST-FIRST-NAME", blankIfAbsent(customer.getFirstName()));
        fields.put("EXP-CUST-MIDDLE-NAME", blankIfAbsent(customer.getMiddleName()));
        fields.put("EXP-CUST-LAST-NAME", blankIfAbsent(customer.getLastName()));
        fields.put("EXP-CUST-ADDR-LINE(1)", blankIfAbsent(customer.getAddrLine1()));
        fields.put("EXP-CUST-ADDR-LINE(2)", blankIfAbsent(customer.getAddrLine2()));
        fields.put("EXP-CUST-ADDR-LINE(3)", blankIfAbsent(customer.getAddrLine3()));
        fields.put("EXP-CUST-ADDR-STATE-CD", blankIfAbsent(customer.getAddrStateCd()));
        fields.put("EXP-CUST-ADDR-COUNTRY-CD", blankIfAbsent(customer.getAddrCountryCd()));
        fields.put("EXP-CUST-ADDR-ZIP", blankIfAbsent(customer.getAddrZip()));
        fields.put("EXP-CUST-PHONE-NUM(1)", blankIfAbsent(customer.getPhoneNum1()));
        fields.put("EXP-CUST-PHONE-NUM(2)", blankIfAbsent(customer.getPhoneNum2()));
        // WHY : Alternatives Considered: leaving the national identifier's span blank, as the
        //       government-issued identifier's is. Rejected because the two fields have different
        //       storage kinds and only one of them admits blanks: EXP-CUST-SSN is an unsigned display
        //       field at app/cpy/CVEXPORT.cpy:36, and the shared codec refuses a non-digit in one,
        //       so a blank run would fail the encode outright rather than produce an empty value.
        //       Zero is the empty value an unsigned display field HAS, and nine zero digits is what
        //       that field's width makes of it.
        fields.put(FIELD_CUSTOMER_SSN, ELIDED_UNSIGNED_VALUE);
        fields.put("EXP-CUST-GOVT-ISSUED-ID", "");
        fields.put("EXP-CUST-DOB-YYYY-MM-DD", isoDateText(customer.getDob()));
        fields.put("EXP-CUST-EFT-ACCOUNT-ID", blankIfAbsent(customer.getEftAccountId()));
        fields.put("EXP-CUST-PRI-CARD-HOLDER-IND", blankIfAbsent(customer.getPriCardHolderInd()));
        fields.put("EXP-CUST-FICO-CREDIT-SCORE", customer.getFicoCreditScore());
        return fields;
    }

    /**
     * Builds the card view's ordered projection from this module's card entity.
     *
     * <p>Assumptions: the verification value is ABSENT from this map by construction, not merely
     * unset. {@link #cardFieldMap(ExportRecord)} rebuilds the map in descriptor order and supplies
     * that field itself, so a value placed here would be discarded; the encode then writes the
     * two-byte encoding of zero over the span, which is the {@code COMP} encoding of zero and a
     * well-formed field. The divergence from a reference export, which carries the stored digits, is
     * registered as {@code D-EXPORT-PROTECTED-FIELDS-ELIDED} in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * @param card the card to encode, which supplies five of the six named fields
     * @return an insertion-ordered map in copybook declaration order, without the pad and without the
     *     verification value
     * @throws IllegalArgumentException if {@code card} is {@code null}
     */
    private static Map<String, Object> cardFieldsOf(Card card) {
        requireProjection(card, RecordType.CARD);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_CARD_NUM, blankIfAbsent(card.getCardNum()));
        fields.put("EXP-CARD-ACCT-ID", card.getAccountId());
        fields.put("EXP-CARD-EMBOSSED-NAME", blankIfAbsent(card.getEmbossedName()));
        // WHY : Assumptions: the export field name keeps the copybook's own spelling,
        //       EXP-CARD-EXPIRAION-DATE, while the entity property spells the word out. The
        //       difference is deliberate and is one of exactly three name corrections the migration
        //       takes: the correction belongs to the target column, and this map's keys are copybook
        //       field names that must match the descriptor byte for byte.
        fields.put("EXP-CARD-EXPIRAION-DATE", isoDateText(card.getExpirationDate()));
        fields.put("EXP-CARD-ACTIVE-STATUS", blankIfAbsent(card.getActiveStatus()));
        return fields;
    }

    /**
     * Builds the account view's field map from the entity.
     *
     * @param account the account to encode, which supplies all twelve named fields
     * @return an insertion-ordered map in copybook declaration order, without the pad
     * @throws IllegalArgumentException if {@code account} is {@code null}
     */
    private static Map<String, Object> accountFieldMap(Account account) {
        requireProjection(account, RecordType.ACCOUNT);
        // WHY : Assumptions: insertion order follows the copybook declaration order of
        //       app/cpy/CVEXPORT.cpy:48-59 so that the map reads in the order the codec walks the
        //       descriptor. The codec keys by name and does not require it, but a map ordered
        //       differently from the record it produces is one a reader has to reconcile by hand.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("EXP-ACCT-ID", account.getAccountId());
        fields.put("EXP-ACCT-ACTIVE-STATUS", blankIfAbsent(account.getActiveStatus()));
        fields.put("EXP-ACCT-CURR-BAL", account.getCurrBal());
        fields.put("EXP-ACCT-CREDIT-LIMIT", account.getCreditLimit());
        fields.put("EXP-ACCT-CASH-CREDIT-LIMIT", account.getCashCreditLimit());
        fields.put("EXP-ACCT-OPEN-DATE", isoDateText(account.getOpenDate()));
        fields.put("EXP-ACCT-EXPIRAION-DATE", isoDateText(account.getExpirationDate()));
        fields.put("EXP-ACCT-REISSUE-DATE", isoDateText(account.getReissueDate()));
        fields.put("EXP-ACCT-CURR-CYC-CREDIT", account.getCurrCycCredit());
        fields.put("EXP-ACCT-CURR-CYC-DEBIT", account.getCurrCycDebit());
        fields.put("EXP-ACCT-ADDR-ZIP", blankIfAbsent(account.getAddrZip()));
        fields.put("EXP-ACCT-GROUP-ID", blankIfAbsent(account.getGroupId()));
        return fields;
    }

    /**
     * Builds the transaction view's field map from the entity.
     *
     * @param transaction the transaction to encode, which supplies all thirteen named fields
     * @return an insertion-ordered map in copybook declaration order, without the pad
     * @throws IllegalArgumentException if {@code transaction} is {@code null}
     */
    private static Map<String, Object> transactionFieldMap(Transaction transaction) {
        requireProjection(transaction, RecordType.TRANSACTION);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("EXP-TRAN-ID", blankIfAbsent(transaction.getTransactionId()));
        fields.put("EXP-TRAN-TYPE-CD", blankIfAbsent(transaction.getTypeCd()));
        // WHY : Assumptions: the category is handed to the codec as digit TEXT rather than parsed to
        //       an integer here. The codec's unsigned-display path validates every character is a
        //       digit and zero-pads to the declared width, so passing the text keeps one validation
        //       of this value in the tree instead of two that could disagree about a stray blank.
        fields.put("EXP-TRAN-CAT-CD", zeroPad(blankIfAbsent(transaction.getCategoryCd()),
                TRANSACTION_VIEW.field("EXP-TRAN-CAT-CD").length()));
        fields.put("EXP-TRAN-SOURCE", blankIfAbsent(transaction.getSource()));
        fields.put("EXP-TRAN-DESC", blankIfAbsent(transaction.getDescription()));
        fields.put("EXP-TRAN-AMT", transaction.getAmount());
        fields.put("EXP-TRAN-MERCHANT-ID", transaction.getMerchantId());
        fields.put("EXP-TRAN-MERCHANT-NAME", blankIfAbsent(transaction.getMerchantName()));
        fields.put("EXP-TRAN-MERCHANT-CITY", blankIfAbsent(transaction.getMerchantCity()));
        fields.put("EXP-TRAN-MERCHANT-ZIP", blankIfAbsent(transaction.getMerchantZip()));
        fields.put("EXP-TRAN-CARD-NUM", blankIfAbsent(transaction.getCardNum()));
        fields.put("EXP-TRAN-ORIG-TS", encodedTimestamp(transaction.getOrigTs()));
        fields.put("EXP-TRAN-PROC-TS", encodedTimestamp(transaction.getProcTs()));
        return fields;
    }

    /**
     * Builds the card cross-reference view's field map from the entity.
     *
     * @param cardXref the cross-reference to encode, which supplies all three named fields
     * @return an insertion-ordered map in copybook declaration order, without the pad
     * @throws IllegalArgumentException if {@code cardXref} is {@code null}
     */
    private static Map<String, Object> cardXrefFieldMap(CardXref cardXref) {
        requireProjection(cardXref, RecordType.CARD_XREF);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("EXP-XREF-CARD-NUM", blankIfAbsent(cardXref.getCardNum()));
        fields.put("EXP-XREF-CUST-ID", cardXref.getCustomerId());
        fields.put("EXP-XREF-ACCT-ID", cardXref.getAccountId());
        return fields;
    }

    /**
     * Builds the card view's field map, reinstating the verification value from the opaque carrier.
     *
     * <p>Trade-offs: the verification value re-enters the byte image here and only here, and it does so
     * as the raw halfword the carrier holds rather than as a number decoded from it, so the value has
     * still never been a numeric variable in this module. When the carrier is absent -- a record
     * assembled rather than decoded -- the span is written as two zero bytes, which is the encoding of
     * the value zero in a {@code COMP} halfword and is therefore a well-formed field rather than a hole.
     * The alternative of refusing to encode without a carrier was rejected because the export direction
     * legitimately builds records that carry no verification value at all.</p>
     *
     * @param decoded the card-view record being encoded
     * @return an insertion-ordered map in copybook declaration order, without the pad
     * @throws IllegalArgumentException if the card projection is {@code null}
     */
    private static Map<String, Object> cardFieldMap(ExportRecord decoded) {
        requireProjection(decoded.cardFields(), RecordType.CARD);
        Map<String, Object> supplied = decoded.cardFields();
        // WHY : Assumptions: the map is rebuilt in the descriptor's own field order rather than in the
        //       order the projection happened to arrive in, because the verification value has to be
        //       reinstated in the middle of the view -- at app/cpy/CVEXPORT.cpy:96, between the account
        //       identifier and the embossed name -- and appending it would leave the map reading in an
        //       order the record does not have.
        Map<String, Object> fields = new LinkedHashMap<>();
        for (CopybookLayout.FieldSpec field : CARD_VIEW.fields()) {
            if (FIELD_CARD_CVV.equals(field.name())) {
                // WHY : Trade-offs: an encoded zero is written into the field map and the carried
                //       bytes are laid over its span afterwards. Two zero bytes are the COMP encoding
                //       of the value zero, so the span is well formed even when no carrier is present
                //       -- a record assembled for export rather than decoded from one legitimately
                //       has no verification value -- which is why refusing to encode without a
                //       carrier was rejected.
                fields.put(field.name(), BigDecimal.ZERO);
            } else if (!FIELD_FILLER.equals(field.name())) {
                fields.put(field.name(), supplied.get(field.name()));
            }
        }
        return fields;
    }

    /**
     * Restores the spans of one view that a re-render cannot reproduce byte for byte.
     *
     * @param decoded the record being encoded
     * @param view the descriptor the payload was encoded against
     * @param sourcePayload the 460 bytes the record was decoded from
     * @param target the freshly encoded 460 bytes, modified in place
     * @throws ExportRecordException if the record's view has no restoration rule here, which
     *     can arise only from a sixth view being added without extending this method
     * @throws IllegalArgumentException if a source timestamp span is neither blank nor one of the two
     *     26-character forms
     */
    private static void restoreViewSpecificSpans(ExportRecord decoded,
            CopybookLayout.RecordSpec view, byte[] sourcePayload, byte[] target) {
        switch (decoded.prefix().recordType()) {
            case TRANSACTION -> {
                // WHY : Assumptions: only the transaction view holds timestamps, so only it needs
                //       this restoration. The two 26-character forms differ at positions 11, 14, 17
                //       and 21 through 26, and neither is normalised into the other, so a span that
                //       arrived in the baseline form would otherwise come back in the target form --
                //       the same instant and different bytes.
                restoreUnchangedTimestamp(sourcePayload, target, view, "EXP-TRAN-ORIG-TS",
                        decoded.transaction().getOrigTs());
                restoreUnchangedTimestamp(sourcePayload, target, view, "EXP-TRAN-PROC-TS",
                        decoded.transaction().getProcTs());
            }
            case CARD -> restoreVerificationValue(decoded, view, target);
            // WHY : Assumptions: the three remaining views need nothing beyond the sign carriers and
            //       the pad, which the caller has already restored. They are named rather than left
            //       to a default arm so that a sixth view could not acquire this behaviour by
            //       omission.
            case CUSTOMER, ACCOUNT, CARD_XREF -> {
                break;
            }
            default -> throw new ExportRecordException("record " + RECORD_NAME
                    + " has no restoration rule for view " + decoded.prefix().recordType());
        }
    }

    /**
     * Copies a source span verbatim over the same span of a freshly encoded image.
     *
     * @param source the bytes to read from, of the record's declared length
     * @param target the bytes to write into, of the same length, modified in place
     * @param field the descriptor naming the offset and length of the span to copy
     */
    private static void copySpan(byte[] source, byte[] target, CopybookLayout.FieldSpec field) {
        System.arraycopy(source, field.start(), target, field.start(), field.length());
    }

    /**
     * Writes the card verification value into the encoded payload from the record's OWN carrier.
     *
     * <p>Refactoring Rationale: <b>the value comes from {@code decoded} and no longer from the
     * caller's source image.</b> An earlier revision copied this span straight out of the supplied
     * image, which made the encode disclose whatever verification value that image happened to hold
     * regardless of which card the decoded record described. Because the image is a plain parameter,
     * a caller could pass card A's record together with card B's image -- by mixing up two records
     * mid-loop, by reusing a buffer, or deliberately -- and the export written for card A would then
     * carry card B's verification value, in cleartext, into a dataset generation that is retained for
     * five versions. Reading the value from the record's own carrier removes the possibility
     * structurally rather than warning against it: there is no longer any parameter through which one
     * card's secret can reach another card's record.</p>
     *
     * <p>Assumptions: the absent carrier leaves the span exactly as the plain encode wrote it, which
     * is the encoded zero the card field map supplies. That is the correct outcome and not a silent
     * omission: a record assembled without a verification value has none to write, and substituting
     * the source image's bytes for it -- which the earlier form did -- would have INVENTED a secret
     * the projection did not carry.</p>
     *
     * <p>Trade-offs: a caller that decoded a card record, discarded the carrier, and then asked for a
     * source-image encode no longer reproduces that image byte for byte across these two bytes. That
     * is accepted, and it is the right trade: the carrier is returned by every decode, so the only way
     * to lose it is to drop it deliberately, whereas the previous behaviour silently repaired that
     * loss from an image whose provenance nothing checked. The general identity check the caller now
     * performs on the image narrows the exposure further, but it cannot close it, because two records
     * of one card and one generation legitimately share a discriminator and a sequence number.</p>
     *
     * @param decoded the record being encoded, whose opaque carrier is the only source of the value
     * @param view the card view descriptor, which names the offset and width of the span
     * @param target the freshly encoded 460-byte payload, modified in place
     * @throws ExportRecordException if the carrier holds bytes of some width other than the span's,
     *     which would leave part of the span holding whatever the encode had already put there
     */
    private static void restoreVerificationValue(ExportRecord decoded,
            CopybookLayout.RecordSpec view, byte[] target) {
        OpaqueSensitiveValue carrier = decoded.cardVerificationValue();
        if (carrier == null || !carrier.isPresent()) {
            return;
        }

        CopybookLayout.FieldSpec field = view.field(FIELD_CARD_CVV);
        byte[] value = carrier.copyBytes();
        if (value.length != field.length()) {
            // WHY : Assumptions: the refusal names the widths and never the bytes, on the same rule
            //       the carrier's own factory and redaction marker follow. A message echoing the
            //       content would disclose through an exception the value this whole path exists to
            //       keep out of every diagnostic.
            throw new ExportRecordException("record " + RECORD_NAME + " field "
                    + field.describe() + " is declared " + field.length()
                    + " bytes but the carried value is " + value.length);
        }
        System.arraycopy(value, 0, target, field.start(), field.length());
    }

    /**
     * Refuses a source image that does not belong to the record being encoded.
     *
     * <p>Refactoring Rationale: an earlier revision accepted any 500-byte array as the source image
     * and checked only its LENGTH, so every span restored from it -- the sign carriers, the trailing
     * pad, the timestamps -- was taken from bytes nothing had established were the bytes this record
     * was read from. A caller that mixed up two records mid-loop therefore produced a well-formed
     * export record assembled from two different sources, which no length check could detect and which
     * a downstream reader has no way to notice. Checking the two prefix fields that identify a record
     * turns that class of mistake into a refusal at the boundary that made it.</p>
     *
     * <p>Assumptions: the two fields checked are the DISCRIMINATOR and the SEQUENCE NUMBER, and they
     * are the right two because they are the only prefix members that identify a record rather than
     * describe it. The discriminator decides how the whole 460-byte payload is read, so an image
     * carrying a different one is not merely a different record but a differently SHAPED one, and every
     * span offset restored from it would be wrong. The sequence number is the record's retrieval key --
     * {@code app/cpy/CVEXPORT.cpy} declares it as the field the divergence note above discusses -- so
     * two images agreeing on it are the same record of the same generation.</p>
     *
     * <p>Alternatives Considered: comparing the whole 40-byte prefix, including the timestamp, the
     * branch and the region. Rejected because the timestamp is a value a caller may legitimately have
     * changed before re-encoding -- that is exactly what the conditional timestamp restoration below
     * exists to accommodate -- so requiring it to match would refuse the very case the restoration is
     * designed for. Alternatives Considered: comparing the payload's own key field instead. Rejected
     * because the payload is read through a view chosen BY the discriminator, so it cannot be decoded
     * safely until the discriminator has already been agreed.</p>
     *
     * <p>Trade-offs: this is a soundness check and NOT proof of provenance. Two distinct records of one
     * card within one generation would share both checked values, so a determined mix-up between them
     * still passes. It is retained because the realistic failure -- a loop that pairs record N with
     * image N minus one, or a view mismatch -- is caught, and because the sensitive span no longer
     * comes from this image at all.</p>
     *
     * @param decoded the record being encoded, whose prefix is the authority for the two values
     * @param sourceImage the 500-byte image the caller offered as the record's source
     * @throws ExportRecordException if the image's discriminator or sequence number differs from the
     *     decoded record's, which means the image does not belong to this record
     */
    private static void requireMatchingImage(ExportRecord decoded, byte[] sourceImage) {
        Prefix prefix = decoded.prefix();

        RecordType imageType = recordTypeOf(sourceImage);
        if (imageType != prefix.recordType()) {
            throw new ExportRecordException("record " + RECORD_NAME
                    + " was decoded as view " + prefix.recordType()
                    + " but the source image offered for it carries view " + imageType
                    + ", so the image does not belong to this record and every span restored from it"
                    + " would be read at the wrong offset");
        }

        long imageSequence = decodePrefix(sourceImage).sequenceNumber();
        if (imageSequence != prefix.sequenceNumber()) {
            throw new ExportRecordException("record " + RECORD_NAME + " field "
                    + EXPORT_RECORD.field(FIELD_SEQUENCE_NUM).describe()
                    + " is " + prefix.sequenceNumber() + " on the decoded record but "
                    + imageSequence + " on the source image offered for it, so the image does not"
                    + " belong to this record");
        }
    }

    /**
     * Copies a timestamp span back from the source when the decoded value still equals what it carried.
     *
     * @param source the 460-byte payload the record was decoded from
     * @param target the freshly encoded 460 bytes, modified in place
     * @param view the descriptor both payloads are read against
     * @param fieldName the name of the 26-character timestamp field to consider
     * @param current the value the projection now holds for that field, or {@code null} for absent
     * @throws IllegalArgumentException if the source span is neither blank nor one of the two
     *     26-character forms
     */
    private static void restoreUnchangedTimestamp(byte[] source, byte[] target,
            CopybookLayout.RecordSpec view, String fieldName, LocalDateTime current) {
        CopybookLayout.FieldSpec field = view.field(fieldName);
        String span = textOf(FixedWidthCodec.decodeField(source, field), fieldName);
        LocalDateTime carried = stripTrailingBlanks(span).isEmpty()
                ? null
                : parseEitherForm(span, field);
        // WHY : Trade-offs: equality of the VALUE decides this and not equality of the bytes. A byte
        //       comparison would never match when the source held the baseline form and the fresh
        //       encode held the target form, which is the case this restoration exists for; copying
        //       unconditionally would overwrite a stamp the caller had deliberately replaced. One
        //       parse distinguishes "same instant, different form" from "different instant".
        boolean unchanged = carried == null ? current == null : carried.equals(current);
        if (unchanged) {
            copySpan(source, target, field);
        }
    }

    /**
     * Slices the 460-byte payload out of a whole record image.
     *
     * @param image the 500-byte export record image
     * @return a fresh array holding the payload span declared at {@code app/cpy/CVEXPORT.cpy:19}
     * @throws ExportRecordException if the image is {@code null} or not 500 bytes
     */
    private static byte[] payloadOf(byte[] image) {
        requireImage(image);
        return Arrays.copyOfRange(image, PAYLOAD_OFFSET, PAYLOAD_OFFSET + PAYLOAD_LENGTH);
    }

    /**
     * Decodes one prefix scalar from a whole record image.
     *
     * @param image the 500-byte export record image
     * @param fieldName the name of the prefix field to decode
     * @return the decoded value, a character string or an exact decimal depending on the field's kind
     * @throws ExportRecordException if the image is {@code null} or not 500 bytes
     * @throws FixedWidthCodec.FieldCodecException if the span cannot be decoded under its descriptor
     */
    private static Object decodedPrefixField(byte[] image, String fieldName) {
        requireImage(image);
        return FixedWidthCodec.decodeField(image, EXPORT_RECORD.field(fieldName));
    }

    /**
     * Decodes the four-byte sequence number that keys the record.
     *
     * @param image the 500-byte export record image
     * @return the sequence number as an integral value
     * @throws ExportRecordException if the image is malformed, or if the span decodes to
     *     something other than an exact decimal, which would mean this file and the shared width rule
     *     had diverged
     */
    private static long sequenceNumberOf(byte[] image) {
        Object value = decodedPrefixField(image, FIELD_SEQUENCE_NUM);
        // WHY : Assumptions: a BINARY span decodes to an exact decimal even when its declared scale is
        //       zero, so the integral value is taken from that rather than expected as an integral
        //       type. Reading it exactly and then converting keeps the conversion visible instead of
        //       relying on a cast that would fail at run time if the shared codec ever widened.
        if (!(value instanceof BigDecimal integral)) {
            throw new ExportRecordException("record " + RECORD_NAME + " field "
                    + EXPORT_RECORD.field(FIELD_SEQUENCE_NUM).describe()
                    + " should decode to an exact decimal but was "
                    + (value == null ? "absent" : value.getClass().getSimpleName())
                    + "; this file's descriptor and app/cpy/CVEXPORT.cpy:16 have diverged");
        }
        return integral.longValueExact();
    }

    /**
     * Narrows a decoded value to character text.
     *
     * @param value the value the codec produced for one field
     * @param fieldName the field's name, used only to identify it in a failure
     * @return the value as text
     * @throws IllegalArgumentException if the value is not character text, which would mean the
     *     descriptor in this file no longer matches the copybook it transcribes
     */
    private static String textOf(Object value, String fieldName) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("record " + RECORD_NAME + " field " + fieldName
                + " should decode to character text but was "
                + (value == null ? "absent" : value.getClass().getSimpleName())
                + "; this file's descriptor and app/cpy/CVEXPORT.cpy have diverged");
    }

    /**
     * Reads an identifier that may be stored either as display digits or as a binary word.
     *
     * <p>Assumptions: this record stores identifiers in <b>two</b> regimes and one accessor has to
     * accept both, which is why this method exists rather than a narrower one per kind. Within the
     * cross-reference view alone the customer identifier at {@code app/cpy/CVEXPORT.cpy:86} is nine
     * display digits while the account identifier at line 87 is an eight-byte binary word; the codec
     * therefore yields an integral value for the first and an exact decimal for the second. Which one
     * arrives is fixed by the descriptor's kind, so accepting both here is not a tolerance for
     * ambiguity but a consequence of the copybook.</p>
     *
     * @param decoded the field map the codec produced for one view
     * @param view the descriptor that map was decoded against
     * @param fieldName the name of the identifier field to read
     * @return the identifier as an integral value
     * @throws IllegalArgumentException if the field is absent, decodes to neither regime's type, or
     *     holds a value with a fractional part that an identifier cannot have
     */
    private static Long unsignedOf(Map<String, Object> decoded, CopybookLayout.RecordSpec view,
            String fieldName) {
        Object value = require(decoded, view, fieldName);
        if (value instanceof Long integral) {
            return integral;
        }
        if (value instanceof BigDecimal exact) {
            return exact.longValueExact();
        }
        throw typeFailure(view, fieldName, value, "an integral or exact decimal identifier");
    }

    /**
     * Reads a money field at exactly scale two, whatever storage regime it is declared in.
     *
     * <p>Assumptions: all five of the account view's amounts and the transaction view's amount decode
     * to an exact decimal at the declared scale regardless of whether their bytes were packed, zoned or
     * binary, because the regime is selected from the descriptor's kind and never by inspecting the
     * bytes. Transformation rule T3 fixes the representation at scale two throughout, and the scale is
     * asserted rather than adjusted here: a value arriving at another scale means this file's descriptor
     * and the copybook have diverged, and rescaling it would hide that.</p>
     *
     * <p>Alternatives Considered: the language's binary approximate type, which is the reflex choice
     * for an amount and is wrong for a specific reason. {@code app/cpy/CVEXPORT.cpy:50} declares twelve
     * significant decimal digits once the two cent positions are counted, and one cent has no
     * terminating binary expansion -- so an amount held that way is an approximation before any
     * arithmetic runs, and the error lands in the least significant cent. That is the position the
     * inclusive over-limit comparison in the posting program turns on, so the approximation would decide
     * whether a transaction posts. The exact decimal type carries the digits and the scale as declared
     * values and has no representation error to accumulate.</p>
     *
     * @param decoded the field map the codec produced for one view
     * @param view the descriptor that map was decoded against
     * @param fieldName the name of the money field to read
     * @return the amount as an exact decimal at scale two
     * @throws IllegalArgumentException if the field is absent, is not an exact decimal, or arrives at
     *     any scale other than two
     */
    private static BigDecimal moneyOf(Map<String, Object> decoded, CopybookLayout.RecordSpec view,
            String fieldName) {
        Object value = require(decoded, view, fieldName);
        if (!(value instanceof BigDecimal amount)) {
            throw typeFailure(view, fieldName, value, "an exact decimal amount");
        }
        if (amount.scale() != Money.SCALE) {
            throw new IllegalArgumentException("record " + RECORD_NAME + " field "
                    + view.field(fieldName).describe() + " decoded at scale " + amount.scale()
                    + " but the money contract requires exactly " + Money.SCALE
                    + "; this file's descriptor and app/cpy/CVEXPORT.cpy have diverged");
        }
        return amount;
    }

    /**
     * Reads a ten-character date span, treating an all-blank span as absent.
     *
     * @param decoded the field map the codec produced for one view
     * @param view the descriptor that map was decoded against
     * @param fieldName the name of the date field to read
     * @return the parsed date, or {@code null} when the span is all blanks
     * @throws IllegalArgumentException if the span is neither all blanks nor a calendar date in the
     *     year-month-day form the copybook stores
     */
    private static LocalDate isoDateOf(Map<String, Object> decoded, CopybookLayout.RecordSpec view,
            String fieldName) {
        String text = textOf(require(decoded, view, fieldName), fieldName);
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException malformed) {
            // WHY : Alternatives Considered: letting the parse failure propagate unwrapped. Rejected
            //       so that every failure this mapper raises belongs to one family -- the codec's own
            //       record and field failures both extend the platform argument exception -- and a
            //       caller wrapping a conversion needs one catch rather than two unrelated ones. The
            //       cause is chained rather than discarded, so the position the parser objected to
            //       remains available.
            CopybookLayout.FieldSpec field = view.field(fieldName);
            throw new IllegalArgumentException("record " + RECORD_NAME + " field " + field.describe()
                    + " is neither all blanks nor a calendar date in the form the copybook stores, "
                    + forDiagnostic(text, field), malformed);
        }
    }

    /**
     * Reads a 26-character timestamp span, treating an all-blank span as absent.
     *
     * @param decoded the field map the codec produced for one view
     * @param view the descriptor that map was decoded against
     * @param fieldName the name of the timestamp field to read
     * @return the parsed timestamp, or {@code null} when the span is all blanks
     * @throws IllegalArgumentException if the span is neither blank nor one of the two 26-character
     *     forms the migration recognises
     */
    private static LocalDateTime timestampOf(Map<String, Object> decoded,
            CopybookLayout.RecordSpec view, String fieldName) {
        String span = textOf(require(decoded, view, fieldName), fieldName);
        // WHY : Assumptions: a 26-BLANK TIMESTAMP IS LEGITIMATE and decodes to absent rather than
        //       raising, because a transaction that has not been posted genuinely has no processing
        //       stamp. The shared codec signals that case by returning an empty string for a 26-byte
        //       field whose name ends in -TS, so the emptiness test below reads that signal rather
        //       than measuring a length.
        if (stripTrailingBlanks(span).isEmpty()) {
            return null;
        }
        return parseEitherForm(span, view.field(fieldName));
    }

    /**
     * Parses a timestamp span in either the target form or the baseline form, preferring the target.
     *
     * @param span the 26 characters to parse
     * @param field the descriptor naming the span, used to identify it in a failure
     * @return the parsed timestamp
     * @throws IllegalArgumentException if the span is neither form
     */
    private static LocalDateTime parseEitherForm(String span, CopybookLayout.FieldSpec field) {
        try {
            return TimestampFormatter.parse(span);
        } catch (DateTimeParseException notTargetForm) {
            try {
                return LocalDateTime.parse(span, BASELINE_TIMESTAMP_FORMAT);
            } catch (DateTimeParseException notBaselineForm) {
                // WHY : Trade-offs: the target attempt is chained as the cause and the baseline
                //       attempt is described in prose, because the platform attaches one cause and
                //       the target form is the one a migrated writer produces -- so its parse
                //       position is the more useful of the two to a reader diagnosing a record this
                //       module wrote.
                throw new IllegalArgumentException("record " + RECORD_NAME + " field "
                        + field.describe() + " holds " + forDiagnostic(span, field)
                        + ", which is neither the target 26-character form owned by"
                        + " com.carddemo.common.time.TimestampFormatter nor the baseline form; the two"
                        + " differ at positions 11, 14, 17 and 21 through 26 and neither is"
                        + " normalised into the other", notTargetForm);
            }
        }
    }

    /**
     * Reads one field out of a decoded map, refusing to proceed when the descriptor did not yield it.
     *
     * @param decoded the field map the codec produced for one view
     * @param view the descriptor that map was decoded against
     * @param fieldName the name of the field to read
     * @return the decoded value, never {@code null}
     * @throws IllegalArgumentException if the map holds no entry under that name
     */
    private static Object require(Map<String, Object> decoded, CopybookLayout.RecordSpec view,
            String fieldName) {
        Object value = decoded.get(fieldName);
        if (value == null) {
            throw new IllegalArgumentException("record " + RECORD_NAME + " view " + view.name()
                    + " decoded no value for " + view.field(fieldName).describe()
                    + "; every field the descriptor declares is decoded, so an absent entry means"
                    + " this file's descriptor and app/cpy/CVEXPORT.cpy have diverged");
        }
        return value;
    }

    /**
     * Renders a value's content for a diagnostic, or a redaction marker when its field is sensitive.
     *
     * <p>Assumptions: the marking is read from the descriptor rather than from a list of names kept
     * here, so a field that becomes sensitive in a descriptor becomes redacted in every diagnostic
     * without a second edit. This package's charter requires that a message concerning a sensitive
     * field report only that field's name, offset, length and kind, which the caller has already
     * supplied through {@code describe()}, and never the bytes -- a decode failure is precisely the
     * moment the tempting thing to log is the input that failed, and a message quoting a card number
     * has put it into a log aggregator where it long outlives the incident.</p>
     *
     * @param value the raw text the field decoded to
     * @param field the descriptor whose sensitive marking decides whether the text may be shown
     * @return the text in quotes when the field is not sensitive, otherwise a redaction marker
     */
    private static String forDiagnostic(String value, CopybookLayout.FieldSpec field) {
        return field.sensitive() ? REDACTED : "'" + value + "'";
    }

    /**
     * Renders whichever account number a record carries with all but its last four digits masked.
     *
     * <p>Assumptions: the primary account number appears at <b>three different offsets in three
     * different views</b> -- offset 252 of the transaction view at {@code app/cpy/CVEXPORT.cpy:76},
     * offset 0 of the cross-reference view at line 85 and offset 0 of the card view at line 94 -- so
     * masking it is not a single-site concern in this file and is resolved once here for all three. The
     * rendering is delegated to {@code com.carddemo.common.security.CardNumberMasker} because masking is
     * a shared concern that must not be re-declared per service: a second implementation could disagree
     * about how many digits it keeps, and the sibling {@code TransactionRecordMapper} already resolves
     * it the same way.</p>
     *
     * <p>Trade-offs: a record-level rendering masks the account number while a field-level failure
     * withholds it entirely through {@link #forDiagnostic(String, CopybookLayout.FieldSpec)}, and the
     * two treatments differ on purpose. A field-level failure already names the field, its offset, its
     * length and its kind, so the value adds nothing to locating a geometry defect; a record-level
     * rendering has no other way to say which of many records is meant, and the last four digits are the
     * migration's sanctioned identifier for exactly that. Neither path can emit all sixteen digits.</p>
     *
     * @param decoded the record whose account number is wanted, already past its invariant check
     * @return the masked account number, or {@code null} for the two views that carry none and for a
     *     projection whose account number is absent
     */
    private static String maskedCardNumberOf(ExportRecord decoded) {
        String cardNumber = switch (decoded.prefix().recordType()) {
            case TRANSACTION -> decoded.transaction() == null
                    ? null
                    : decoded.transaction().getCardNum();
            case CARD_XREF -> decoded.cardXref() == null ? null : decoded.cardXref().getCardNum();
            case CARD -> decoded.cardFields() == null
                    ? null
                    : asTextOrNull(decoded.cardFields().get(FIELD_CARD_NUM));
            // WHY : Assumptions: the customer and account views carry no account number at all --
            //       app/cpy/CVEXPORT.cpy:25-42 and :48-60 declare none -- so there is nothing to mask
            //       rather than something being withheld. They are named instead of left to a default
            //       arm so that a view added later cannot inherit "no account number" silently.
            case CUSTOMER, ACCOUNT -> null;
        };
        return CardNumberMasker.mask(cardNumber);
    }

    /**
     * Narrows a field-map value to text without raising, for use on a path that must not fail.
     *
     * @param value the value taken from a field map, of any type or {@code null}
     * @return the value as text when it is text, otherwise {@code null}
     */
    private static String asTextOrNull(Object value) {
        // WHY : Assumptions: this returns null instead of raising because its only caller is a
        //       rendering, and a rendering that threw would replace the failure a reader was trying to
        //       diagnose with one of its own. The strict narrowing used on the decode path is
        //       textOf, which does raise, and the two are deliberately separate.
        return value instanceof String text ? text : null;
    }

    /**
     * Builds the failure raised when a decoded value is not of the type its field's kind implies.
     *
     * @param view the descriptor the field belongs to
     * @param fieldName the name of the field whose value was unexpected
     * @param actual the value that arrived, reported by type only and never by content
     * @param expected a phrase naming what the field should have decoded to
     * @return the failure to throw, never thrown from here so a caller's stack trace starts at the site
     */
    private static IllegalArgumentException typeFailure(CopybookLayout.RecordSpec view,
            String fieldName, Object actual, String expected) {
        // WHY : Assumptions: the value's TYPE is reported and its content is not, following this
        //       package's charter on diagnostics. The type is what identifies a descriptor that has
        //       changed kind, which is the only way this failure arises, so withholding the content
        //       loses nothing diagnostic and cannot leak a sensitive field by omission.
        return new IllegalArgumentException("record " + RECORD_NAME + " view " + view.name()
                + " field " + view.field(fieldName).describe() + " should decode to " + expected
                + " but was " + (actual == null ? "absent" : actual.getClass().getSimpleName())
                + "; this file's descriptor and app/cpy/CVEXPORT.cpy have diverged");
    }

    /**
     * Rejects an image that is absent or of the wrong length before any span is addressed.
     *
     * @param image the candidate 500-byte export record image
     * @throws ExportRecordException if the image is {@code null} or not exactly 500 bytes
     */
    private static void requireImage(byte[] image) {
        if (image == null || image.length != RECORD_LENGTH) {
            throw new ExportRecordException("record " + RECORD_NAME
                    + " is declared 500 bytes at app/cpy/CVEXPORT.cpy:5 and every span this file"
                    + " addresses lies within that, but the image supplied was "
                    + (image == null ? "absent" : image.length + " bytes"));
        }
    }

    /**
     * Rejects an absent record before its prefix or projection is read.
     *
     * @param decoded the candidate record
     * @throws ExportRecordException if {@code decoded} is {@code null}
     */
    private static void requireDecoded(ExportRecord decoded) {
        if (decoded == null) {
            throw new ExportRecordException("record " + RECORD_NAME
                    + " cannot be encoded from an absent decoded record");
        }
    }

    /**
     * Rejects an absent projection for a view that requires one.
     *
     * @param projection the entity or field map the view is to be encoded from
     * @param view the view being encoded, named in the failure
     * @throws IllegalArgumentException if {@code projection} is {@code null}
     */
    private static void requireProjection(Object projection, RecordType view) {
        if (projection == null) {
            throw new IllegalArgumentException("record " + RECORD_NAME + " cannot encode its "
                    + view + " view without the projection that view carries");
        }
    }

    /**
     * Substitutes an empty string for an absent character value, leaving padding to the codec.
     *
     * @param value the character value, or {@code null} when the projection holds none
     * @return the value unchanged, or an empty string which the codec blank-fills to the declared width
     */
    private static String blankIfAbsent(String value) {
        // WHY : Assumptions: an empty string is returned rather than a run of blanks sized here,
        //       because the codec blank-fills a short character value to the field's declared width
        //       anyway. Sizing a literal run here would put the width in a second place and would stop
        //       matching the moment a descriptor changed.
        return value == null ? "" : value;
    }

    /**
     * Renders a date as the ten characters the copybook stores, or as blanks when absent.
     *
     * @param date the date to render, or {@code null} for an absent value
     * @return the ten-character rendering, or an empty string the codec blank-fills
     */
    private static String isoDateText(LocalDate date) {
        // WHY : Trade-offs: a year outside the four-digit range renders with a sign and extra digits,
        //       which the codec then refuses because the value exceeds the ten bytes the field holds.
        //       Refusing is the intended outcome and is why no range check is written here: such a date
        //       has no representation in this record, and truncating it to ten characters would write a
        //       different date rather than report an unwritable one.
        return date == null ? "" : date.toString();
    }

    /**
     * Renders a timestamp in the target 26-character form, or as blanks when absent.
     *
     * @param timestamp the timestamp to render, or {@code null} for an absent value
     * @return the target-form rendering, or an empty string the codec blank-fills to 26 blanks
     */
    private static String encodedTimestamp(LocalDateTime timestamp) {
        // WHY : Assumptions: the target form comes from com.carddemo.common.time.TimestampFormatter
        //       rather than from a pattern applied here, because that contract belongs to the shared
        //       kernel under transformation rule T2. The BASELINE form is deliberately never emitted
        //       from this path: the two are different byte patterns and neither is normalised into the
        //       other, so a caller needing the original bytes uses the byte-preserving encode instead.
        return timestamp == null ? "" : TimestampFormatter.format(timestamp);
    }

    /**
     * Left-pads digit text with zeros to a declared width.
     *
     * @param digits the digit text to pad, which may be empty
     * @param width the declared field width to pad to
     * @return the text padded to {@code width} characters, or unchanged when already that long
     */
    private static String zeroPad(String digits, int width) {
        // WHY : Assumptions: text already at or beyond the width is returned untouched rather than
        //       truncated, so an over-long value reaches the codec and is refused there with the field
        //       named. Truncating here would silently produce a different number.
        return digits.length() >= width
                ? digits
                : "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Removes trailing blanks from a fixed-width value, leaving any leading blank in place.
     *
     * @param text the value read from a fixed-width span
     * @return the value with its trailing blanks removed
     */
    private static String stripTrailingBlanks(String text) {
        // WHY : Alternatives Considered: trimming both ends. Rejected because only the trailing side is
        //       padding in a fixed-width field: content is written left-justified into the span, so a
        //       leading blank is a character the source actually holds and removing it would change the
        //       value rather than remove its padding.
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == BLANK) {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /**
     * The failure this mapper raises when an image, a descriptor or a projection is unusable.
     *
     * <p>Alternatives Considered: raising the shared kernel's own {@code LayoutException} instead, which
     * would have put every geometry failure in the tree under one type. It is not available: that type's
     * constructor is deliberately not public, so it can be raised only from inside
     * {@code com.carddemo.common.codec} -- which is correct, because a failure raised there means the
     * kernel's own registry is inconsistent, and a failure raised here means this file's local
     * descriptors or a caller's input are. Keeping them distinguishable is worth more than collapsing
     * them.</p>
     *
     * <p>Assumptions: this extends the platform's argument exception, which is what both shared-kernel
     * failure types extend as well, so a caller wrapping a conversion still needs one catch rather than
     * three unrelated ones. The sibling {@code TransactionRecordMapper} declares its own failure type on
     * the same reasoning, so a reader meeting both meets one pattern.</p>
     */
    public static final class ExportRecordException extends IllegalArgumentException {

        // WHY : Assumptions: the platform expects a serial version identifier on a serialisable type,
        //       and this one inherits serialisability from the exception hierarchy. Pinning the value
        //       stops the compiler deriving one that changes whenever a member is added, which is what
        //       keeps a serialised instance readable across builds. The shared codecs and the sibling
        //       mapper pin theirs the same way.
        private static final long serialVersionUID = 1L;

        /**
         * Creates a failure carrying a message and no cause.
         *
         * @param message the diagnostic, which names the field, offset, length and kind involved and
         *     never the bytes of a field a descriptor marks sensitive
         */
        private ExportRecordException(String message) {
            super(message);
        }
    }
}
