package com.carddemo.batch.mapper;

import com.carddemo.batch.domain.TransactionReject;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.dto.RejectReason;
import com.carddemo.common.codec.CopybookLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Assembles and takes apart the 430-byte posting reject record, the one output in this module whose
 * byte composition is not declared by a copybook.
 *
 * <h2>Purpose, and why this record has no copybook</h2>
 *
 * <p>Every other record boundary in this package transcribes a member of {@code app/cpy}. This one
 * cannot, because no copybook declares it and no {@code COPY} statement anywhere in the baseline
 * names it. The record is assembled in the working storage of one program, at
 * {@code app/cbl/CBTRN02C.cbl:176-182}, so that listing is the layout's whole authority. This class
 * owns both directions of that assembly: a 350-byte daily-transaction image plus a validation
 * outcome becomes the 430-byte stream record and the row that records it, and a 430-byte stream
 * record becomes that row again.</p>
 *
 * <p>Assumptions: the 430 is confirmed four times over, independently, and the four are listed
 * because a single reading of a working-storage group is exactly the kind of claim that turns out to
 * be one field short.</p>
 *
 * <ol> <li><b>The file definition.</b> {@code app/cbl/CBTRN02C.cbl:82-84} declares
 * {@code FD-REJS-RECORD} as {@code FD-REJECT-RECORD PIC X(350)} followed by
 * {@code FD-VALIDATION-TRAILER PIC X(80)}. 350 plus 80.</li> <li><b>The working-storage
 * decomposition.</b> {@code app/cbl/CBTRN02C.cbl:176-178} declares {@code REJECT-RECORD} as
 * {@code REJECT-TRAN-DATA PIC X(350)} followed by {@code VALIDATION-TRAILER PIC X(80)}, and
 * {@code :180-182} decomposes that trailer into {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} followed
 * by {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}. 350 plus 4 plus 76.</li> <li><b>The job's own
 * data-control block.</b> {@code app/jcl/POSTTRAN.jcl:36} allocates the stream
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} -- fixed-length, 430 -- on the dataset the same step
 * creates at {@code :34-38} with a new-and-catalogue disposition and a relative generation
 * reference.</li> <li><b>The parity oracle's committed expectations.</b> Each record in
 * {@code tests/golden/posting/reject_100_card_missing/dalyrejs.expected} and its three siblings for
 * reasons 101, 102 and 103 is exactly 430 characters, with the four-digit code at one-based position
 * 351 and the description occupying positions 355 through 430.</li> </ol>
 *
 * <p>Assumptions: the generation base those records land in is defined at
 * {@code app/jcl/DALYREJS.jcl:24-28} with a five-generation scratch limit, like every generation
 * family in the baseline. That is recorded as context for a reader tracing where an assembled record
 * goes; retention is a property of the dataset and reaches no method here.</p>
 *
 * <h2>Assumptions: the 350-byte prefix is the source bytes, and never a re-encode</h2>
 *
 * <p>This is the single most consequential ruling in the file, and getting it wrong produces a reject
 * file that looks entirely right. {@code app/cbl/CBTRN02C.cbl:447} is
 * {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} -- a <b>group</b> move, which copies bytes rather
 * than re-rendering fields. The prefix is therefore the daily record exactly as it was read, and
 * three regions of it carry content that a re-encode would not reproduce:</p>
 *
 * <ul> <li>the record's own {@code FILLER PIC X(20)} at offset 330, declared at
 * {@code app/cpy/CVTRA06Y.cpy:18}, holding whatever bytes it held -- a group move has no notion of
 * padding and copies the span like any other;</li> <li>the record's own timestamp spans, the
 * originating stamp at offset 278 and the processing stamp at offset 304, declared at
 * {@code app/cpy/CVTRA06Y.cpy:16-17}. Nothing in the posting program writes the feed record's
 * processing stamp -- {@code app/cbl/CBTRN02C.cbl:437-438} stamps the <b>posted</b> transaction's
 * stamp, not this one -- so both spans reach the prefix exactly as the feed supplied them;</li>
 * <li>every trailing blank in each of the five descriptive fields, unaltered.</li> </ul>
 *
 * <p>Assumptions: re-encoding from the decoded entity would differ from the baseline in the first two
 * of those three regions, each for its own reason.
 * {@code com.carddemo.common.codec.FixedWidthCodec} drops a blank registered pad on decode and
 * rebuilds it as blanks on encode, so a {@code FILLER} span that was <b>not</b> blank comes back
 * blank and the bytes it held are gone. And an encode <b>round-trips</b> each timestamp, parsing the
 * span and then rendering it through the target formatter rather than carrying its bytes, so a span
 * the feed wrote in the baseline producer's own 26-character form comes back in the target form --
 * the two differ at one-based positions 11, 14 and 17 of the span, where the target form carries a
 * blank and two colons and the form declared at {@code app/cbl/CBTRN02C.cbl:160-174} carries a hyphen
 * and two dots. Either difference fails a byte-deterministic comparison against the reject
 * expectation files while every decoded field is individually correct -- which is the worst available
 * failure mode, because the fields all check out and the file does not. The oracle settles the point
 * rather than leaving it to inference: the first 350 bytes of the record in
 * {@code tests/golden/posting/reject_102_overlimit/dalyrejs.expected} are byte-identical to the input
 * record in {@code tests/fixtures/posting/reject_102_overlimit/dailytran.txt}, blank
 * processing-timestamp span and blank {@code FILLER} span included.</p>
 *
 * <p>Assumptions: the bytes are therefore obtained through the verbatim-source-bytes path that
 * {@link DailyTransactionMapper.DecodedRecord#sourceImage()} exists to provide, and are never
 * reconstructed. {@code app/cbl/CBTRN02C.cbl:448} then moves the 80-byte trailer group into an
 * 80-byte span, so that half is byte-identical by construction and needs no conversion.</p>
 *
 * <h2>Parity obligation</h2>
 *
 * <p>Assumptions: the assembled record is compared against the reject golden master for the posting
 * job byte for byte across all 430 positions -- not field by field, and with no span exempt. That is
 * why each of the spans named above is treated as content rather than as padding, and it is why this
 * class copies where it can and renders only where it must.</p>
 *
 * <h2>Assumptions: what this class does not do</h2>
 *
 * <p>There is no repository access here, no transaction demarcation, no arithmetic, no clock read and
 * no input or output. {@code app/cbl/CBTRN02C.cbl:451} performs the write and {@code :460-463} abends
 * when it fails; both belong to the caller. Validation belongs to the posting validation service, at
 * {@code app/cbl/CBTRN02C.cbl:370-422}. And the reject <b>count</b> that
 * {@code app/cbl/CBTRN02C.cbl:229-231} turns into the program's return code is the job's, not this
 * class's: nothing here counts anything.</p>
 *
 * <p>Assumptions: no diagnostic raised by this class quotes the record image or any span of it. The
 * prefix contains a full primary account number at offset 262, declared at
 * {@code app/cpy/CVTRA06Y.cpy:15} and marked sensitive by the registry itself, so a message that
 * echoed the prefix -- or echoed a slice wide enough to contain it -- would write a live account
 * number into a log line. Messages therefore carry field descriptors, offsets, widths and reason
 * codes only, which is the same discipline the sibling boundaries in this package keep.</p>
 */
public final class TransactionRejectRecordMapper {

    /**
     * Registry name of the derived layout this class resolves, and the only one it ever resolves.
     *
     * <p>Assumptions: it is held as a constant so that one String names the entry in the resolution
     * below and in every geometry failure, which is what lets a reader of a failure look the entry up
     * without having to guess which of the registry's several 350-byte-prefixed layouts was in
     * force.</p>
     */
    private static final String REGISTRY_NAME = "REJECT";

    /**
     * Copybook name of the four-digit reason field, declared at {@code app/cbl/CBTRN02C.cbl:181}.
     *
     * <p>Assumptions: the name is a working-storage name rather than a copybook name, because this
     * record has no copybook. The registry entry uses the same spelling, so this constant addresses
     * it.</p>
     */
    private static final String FAIL_REASON = "WS-VALIDATION-FAIL-REASON";

    /**
     * Copybook name of the 76-character description field, declared at
     * {@code app/cbl/CBTRN02C.cbl:182}.
     */
    private static final String FAIL_REASON_DESC = "WS-VALIDATION-FAIL-REASON-DESC";

    /**
     * Declared record length of the assembled reject record, from all four confirmations above.
     *
     * <p>Assumptions: this constant exists to be checked against the registry rather than used as a
     * length. Every span this class writes is positioned from the resolved descriptor, so a registry
     * that had drifted to some other total would be caught by the proof below instead of producing a
     * record of the wrong width.</p>
     */
    private static final int RECORD_LENGTH = 430;

    /**
     * Byte width of the numeric reason span, from {@code PIC 9(04)}.
     *
     * <p>Assumptions: four, and it is asserted against {@code RejectReason.CODE_WIDTH} rather than
     * merely stated, so the width this class positions the description at cannot disagree with the
     * width the renderer pads the code to.</p>
     */
    private static final int REASON_CODE_WIDTH = 4;

    /** Byte width of the description span, from {@code PIC X(76)}. */
    private static final int REASON_DESC_WIDTH = 76;

    /** The single character COBOL pads a {@code PIC X(n)} field with, and the only one trimmed here. */
    private static final char BLANK = ' ';

    /**
     * Character set used at the one hop where the record image has to become characters.
     *
     * <p>Trade-offs: this is the single Latin-1 character set rather than the seven-bit set the shared
     * fixed-width codec defaults to, and the divergence is deliberate and narrow. Latin-1 is the one
     * character set in the platform that maps every one of the 256 byte values to a distinct character
     * and back, so a byte-to-character-to-byte hop through it is total and lossless. Seven-bit decoding
     * turns any byte above 127 into a single replacement character, and re-encoding that character
     * yields a question mark -- a silent one-byte substitution in exactly the span
     * {@code app/cbl/CBTRN02C.cbl:447} carries across untouched, because a group move constrains
     * nothing about what the {@code FILLER} span or a descriptive field holds. On every byte the
     * parity oracle's expectation files actually contain, which are seven-bit throughout, the two sets
     * agree byte for byte, so nothing observable changes and the lossless case is gained for free. The
     * accepted cost is that this class names a character set the sibling boundaries do not.</p>
     *
     * <p>Assumptions: the shared codec could not have been handed this set in any case. Its own
     * character-set guard admits only the seven-bit set and IBM code page 037 and refuses anything
     * else, which is a second and independent reason the prefix is never routed through it.</p>
     *
     * <p>Assumptions: this set reaches the prefix only, and only on the hop through the row's character
     * column. {@link #toRecord(byte[], PostingValidationResult)} copies bytes and applies no character
     * set at all, and the trailer is rendered from digits, upper-case letters and blanks, on which this
     * set and the seven-bit set are identical.</p>
     */
    private static final Charset IMAGE_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * The registered specification for the assembled record, resolved once at class initialisation.
     *
     * <p>Assumptions: the registry builds this entry by <b>extending</b> the daily-transaction entry
     * rather than by restating its fourteen field descriptors, so the 350-byte prefix has exactly one
     * declaration in the whole repository. Resolving here means a registry that no longer holds the
     * entry fails at class initialisation rather than at the first rejected record of a nightly
     * run.</p>
     */
    private static final CopybookLayout.RecordSpec LAYOUT = CopybookLayout.layout(REGISTRY_NAME);

    /**
     * Descriptor of the numeric reason span, resolved from the layout rather than declared.
     *
     * <p>Assumptions: the descriptor rather than a literal offset is held, because it carries the
     * content-free rendering that every diagnostic in this class reports a position with.</p>
     */
    private static final CopybookLayout.FieldSpec REASON_CODE_FIELD = LAYOUT.field(FAIL_REASON);

    /** Descriptor of the 76-character description span, resolved from the layout. */
    private static final CopybookLayout.FieldSpec REASON_DESC_FIELD = LAYOUT.field(FAIL_REASON_DESC);

    /**
     * Byte width of the verbatim prefix, <b>derived</b> from the daily-transaction layout.
     *
     * <p>Alternatives Considered: declaring the 350 here as a second literal, which reads more
     * directly than a derivation and was rejected. The migration plan's transformation rule T2 makes a
     * layout descriptor a single-source-of-truth concern, and this package's charter names
     * {@link DailyTransactionMapper} as the one owner of the 350-byte shape. A second declaration of
     * one prefix is precisely the drift the registry exists to prevent, and it drifts silently: each
     * copy stays internally consistent while the two disagree, and a reject image written from the
     * stale one is a file that still opens at its declared length and still reads. Taking the width
     * from the owner means the two cannot disagree at all.</p>
     *
     * <p>Assumptions: the derivation is also a proof rather than merely a lookup. The reason span
     * begins where the prefix ends, so the daily record's declared length and this record's
     * first-trailer-field offset are the same number reached two independent ways, and the check below
     * asserts they agree.</p>
     */
    private static final int PREFIX_LENGTH = DailyTransactionMapper.layout().reclen();

    /**
     * Byte width of the assembled trailer, from {@code VALIDATION-TRAILER PIC X(80)} at
     * {@code app/cbl/CBTRN02C.cbl:178}.
     *
     * <p>Assumptions: the value is taken from {@code RejectReason.TRAILER_WIDTH} rather than written
     * as 80, because that type owns the rendering this class positions. Writing the number here would
     * let a change to either half of the rendering pass a build in which this class still wrote 80
     * bytes of an 81-byte trailer.</p>
     */
    private static final int TRAILER_LENGTH = RejectReason.TRAILER_WIDTH;

    static {
        // WHY : Assumptions: the geometry proof runs at class initialisation, following the precedent
        //       com.carddemo.common.codec.FixedWidthCodec sets with its own registry validation and the
        //       precedent the sibling boundaries in this package follow. Java declarations are the only
        //       layout source in this migration, so a descriptor that had drifted from the four
        //       agreeing confirmations would otherwise surface as a 430-byte record whose trailer sits
        //       four bytes off -- and a 430-byte record still reads as 430 bytes after a shift, so
        //       nothing downstream would notice. Failing here means no job can assemble a single
        //       record against a wrong geometry.
        verifyGeometry();
    }

    /**
     * Prevents instantiation of this stateless boundary.
     *
     * <p>Alternatives Considered: a Spring-managed bean with instance methods, so a job or a service
     * could receive this mapper by constructor injection. Rejected because the type holds no state and
     * has no collaborator to inject -- it reads one immutable registry descriptor and calls static
     * renderings -- so a bean would add a lifecycle and a wiring point without adding a seam any test
     * needs, and every assertion in this file's coverage runs against a byte array with no application
     * context at all. The shared kernel and the sibling boundaries in this package take the same line
     * for the same reason.</p>
     *
     * <p>Alternatives Considered: an abstract base mapper shared with this package's other record
     * boundaries, which would remove the helper methods below that resemble their counterparts
     * elsewhere. Prohibited outright by the package charter: factoring the pattern upward relocates
     * each mapper's justification away from the code it applies to, leaving one paragraph on a
     * superclass that is true of none of them in particular. The duplication is the charter's accepted
     * cost, and it buys a citation of this record's own program lines at every decision.</p>
     */
    private TransactionRejectRecordMapper() {
    }

    /**
     * Returns the registered {@code REJECT} specification this class addresses the record through.
     *
     * <p>Assumptions: the entry is derived rather than transcribed, because the record it describes
     * has no copybook to transcribe. Its 350-byte prefix is the daily-transaction entry's field list
     * reused unchanged, and only the two trailer descriptors are its own, so a caller reading a prefix
     * offset from this specification reads the same number
     * {@link DailyTransactionMapper#layout()} reports.</p>
     *
     * @return the {@code com.carddemo.common.codec.CopybookLayout.RecordSpec} the registry holds under
     *     the name {@code REJECT}, which is the same instance {@code CopybookLayout.layout("REJECT")}
     *     returns, so any codec behaviour that depends on that identity keeps working for a caller
     *     that passes it on
     */
    public static CopybookLayout.RecordSpec layout() {
        return LAYOUT;
    }

    /**
     * Assembles the 430-byte reject record from a verbatim daily-transaction image and an outcome.
     *
     * <p>This is the entry point the posting job writes the stream through, and it is the one method
     * in the class that reproduces {@code app/cbl/CBTRN02C.cbl:446-448} directly: the whole 350-byte
     * image is carried into the prefix and the rendered 80-byte trailer follows it.</p>
     *
     * @param sourceImage the byte array holding the rejected daily-transaction record exactly as it
     *     was read, of the daily record's declared 350-byte length; it is read and never modified, and
     *     the returned record holds a copy of its bytes rather than a reference to this array
     * @param outcome the {@code com.carddemo.batch.dto.PostingValidationResult} the validation service
     *     produced for this record, which must carry a reason the baseline can persist
     * @return a newly allocated byte array of exactly 430 bytes, whose first 350 bytes equal
     *     {@code sourceImage} byte for byte and whose remaining 80 are the rendered trailer
     * @throws RejectRecordException if {@code sourceImage} is null or is not exactly 350 bytes, if
     *     {@code outcome} is null, if the outcome carries no reason at all, or if it carries the one
     *     reason the baseline can never write to this stream
     */
    public static byte[] toRecord(byte[] sourceImage, PostingValidationResult outcome) {
        RejectReason reason = requirePersistableReason(outcome);
        byte[] prefix = requireSourceImage(sourceImage);

        byte[] record = new byte[RECORD_LENGTH];

        // WHY : Assumptions: THE PREFIX IS COPIED, NEVER RE-ENCODED, and this one statement is the
        //       whole of that ruling. app/cbl/CBTRN02C.cbl:447 is MOVE DALYTRAN-RECORD TO
        //       REJECT-TRAN-DATA, a GROUP move, so the baseline transfers bytes and re-renders nothing.
        //       Re-encoding from the decoded entity would differ in two spans: the FILLER span at
        //       offset 330 of app/cpy/CVTRA06Y.cpy:18 comes back blank, because the shared codec drops
        //       a blank registered pad on decode and rebuilds it as blanks on encode, so a pad that was
        //       not blank is lost; and each timestamp span -- the originating stamp at offset 278 and
        //       the processing stamp at offset 304, of app/cpy/CVTRA06Y.cpy:16-17 -- is round-tripped
        //       through a parse and a render rather than carried, so a span written in the form
        //       app/cbl/CBTRN02C.cbl:160-174 declares comes back in the target form and differs at
        //       three separator positions. Either difference fails a byte-exact comparison of the
        //       reject stream while every decoded field is individually right, which is the worst
        //       failure mode available.
        // WHY : Assumptions: no character set is applied to this span at all, deliberately. A byte
        //       array copy cannot transcode, so the prefix survives a byte value that no seven-bit
        //       charset can represent -- and a group move places no constraint whatever on what the
        //       FILLER span or any descriptive field holds. The transcoding decision recorded on
        //       IMAGE_CHARSET applies only where a prefix has to pass through the row's character
        //       column, which is a different hop and a different method.
        System.arraycopy(prefix, 0, record, 0, PREFIX_LENGTH);

        writeTrailer(record, outcome.trailerField(), reason);
        return record;
    }

    /**
     * Assembles the 430-byte reject record from a decoded feed record and an outcome.
     *
     * <p>Assumptions: this overload exists so that the posting walk never has to reach past the carrier
     * it already holds. {@link DailyTransactionMapper#decode(byte[])} returns the entity paired with
     * the image it was decoded from precisely because a record that fails validation has to reach this
     * stream as the bytes it arrived as, and taking the image from that carrier is what keeps the
     * caller from being tempted to re-encode the entity instead.</p>
     *
     * @param decoded the {@link DailyTransactionMapper.DecodedRecord} the feed decode produced, whose
     *     verbatim image supplies the prefix; it is read and never modified
     * @param outcome the {@code com.carddemo.batch.dto.PostingValidationResult} the validation service
     *     produced for the same record, which must carry a reason the baseline can persist
     * @return a newly allocated byte array of exactly 430 bytes, whose first 350 bytes equal the
     *     carrier's image byte for byte and whose remaining 80 are the rendered trailer
     * @throws RejectRecordException if {@code decoded} is null, if {@code outcome} is null, if the
     *     outcome carries no reason at all, or if it carries the one reason the baseline can never
     *     write to this stream
     */
    public static byte[] toRecord(DailyTransactionMapper.DecodedRecord decoded,
            PostingValidationResult outcome) {
        return toRecord(requireDecodedRecord(decoded).sourceImage(), outcome);
    }

    /**
     * Builds the persistable reject row from a verbatim daily-transaction image and an outcome.
     *
     * <p>Assumptions: this is the same pair of inputs {@link #toRecord(byte[], PostingValidationResult)}
     * takes, projected onto the three columns the migration plan decomposes the 430-byte contract into
     * rather than onto bytes. Both projections are produced from the same image and the same outcome,
     * so a row and the record it corresponds to cannot disagree.</p>
     *
     * <p>Alternatives Considered: <b>the row acquires a surrogate ordinal that the baseline record has
     * no counterpart for, and two natural keys were evaluated first.</b> The baseline reject record has
     * no key at all: {@code app/cbl/CBTRN02C.cbl:46-49} selects the stream as a sequential organisation
     * with no record key and {@code app/jcl/POSTTRAN.jcl:36} allocates it fixed-length, so it is a flat
     * stream appended to in arrival order and never keyed into. A key on the rejected transaction's own
     * identifier was rejected because it is not unique across runs -- the same daily transaction can
     * legitimately be rejected again on the next run, and
     * {@code app/jcl/DALYREJS.jcl:24-28} retains five generations of exactly that -- so the two
     * occurrences would collapse into one row and undercount the reject total that
     * {@code app/cbl/CBTRN02C.cbl:229-231} turns into the program's return code. A composite of that
     * identifier and the reason code was rejected for the same reason: a record rejected twice for the
     * same reason is the ordinary case, not an unusual one, so adding the reason narrows nothing. An
     * identity column is the only shape that admits duplicate payloads while still giving each
     * occurrence a stable handle, and this method supplies no value for it because the database assigns
     * it on insert.</p>
     *
     * @param sourceImage the byte array holding the rejected daily-transaction record exactly as it was
     *     read, of the daily record's declared 350-byte length; it is read and never modified
     * @param outcome the {@code com.carddemo.batch.dto.PostingValidationResult} the validation service
     *     produced for this record, which must carry a reason the baseline can persist
     * @return a {@code com.carddemo.batch.domain.TransactionReject} carrying the untrimmed
     *     350-character image, the numeric reason code and the untrimmed reason description, with its
     *     surrogate ordinal unset because the database assigns it
     * @throws RejectRecordException if {@code sourceImage} is null or is not exactly 350 bytes, if
     *     {@code outcome} is null, if the outcome carries no reason at all, or if it carries the one
     *     reason the baseline can never write to this stream
     */
    public static TransactionReject toRejectRow(byte[] sourceImage,
            PostingValidationResult outcome) {
        RejectReason reason = requirePersistableReason(outcome);
        byte[] prefix = requireSourceImage(sourceImage);

        // WHY : Alternatives Considered: naming this method toEntity, which is the convention the
        //       sibling boundaries in this package use for their decode direction. Rejected because
        //       this class has BOTH a 350-byte input and a 430-byte input, and an overload set in which
        //       the same byte-array parameter position carries two different record widths is a
        //       footgun no signature can warn about: a caller passing an assembled record where a
        //       prefix was expected would be refused only by a width check, and one passing a prefix
        //       where a record was expected likewise. Two distinct names make the two widths visible
        //       at the call site, and toEntity is reserved below for the width the whole record is.
        return new TransactionReject(imageText(prefix), reasonCodeOf(reason), reason.description());
    }

    /**
     * Builds the persistable reject row from a decoded feed record and an outcome.
     *
     * @param decoded the {@link DailyTransactionMapper.DecodedRecord} the feed decode produced, whose
     *     verbatim image supplies the stored record image; it is read and never modified
     * @param outcome the {@code com.carddemo.batch.dto.PostingValidationResult} the validation service
     *     produced for the same record, which must carry a reason the baseline can persist
     * @return a {@code com.carddemo.batch.domain.TransactionReject} carrying the untrimmed
     *     350-character image, the numeric reason code and the untrimmed reason description, with its
     *     surrogate ordinal unset
     * @throws RejectRecordException if {@code decoded} is null, if {@code outcome} is null, if the
     *     outcome carries no reason at all, or if it carries the one reason the baseline can never
     *     write to this stream
     */
    public static TransactionReject toRejectRow(DailyTransactionMapper.DecodedRecord decoded,
            PostingValidationResult outcome) {
        return toRejectRow(requireDecodedRecord(decoded).sourceImage(), outcome);
    }

    /**
     * Takes a 430-byte reject record apart into the row that records it.
     *
     * <p>Assumptions: this direction is as real as the assembly above, and it exists for two named
     * uses rather than for symmetry. A generation of the stream staged under the migrated equivalent of
     * {@code app/jcl/DALYREJS.jcl} has to be read back to be loaded or diffed; and the round-trip
     * property is what makes the assembly trustworthy at all, because a record that takes apart and
     * reassembles to the same bytes has demonstrated that no span was positioned wrongly, which no
     * assertion about an individual field can demonstrate on its own.</p>
     *
     * @param record the byte array holding exactly one 430-byte reject record image; it is read and
     *     never modified, and the returned row holds its own copy of the prefix as characters
     * @return a {@code com.carddemo.batch.domain.TransactionReject} whose stored image retains all 350
     *     characters of the prefix untrimmed, whose reason code is the numeric value the four-digit
     *     span carries, whose description is that reason's text with the trailing pad removed, and
     *     whose surrogate ordinal is unset because nothing in the 430 bytes corresponds to it
     * @throws RejectRecordException if {@code record} is null or is not exactly 430 bytes, if the
     *     four-digit span is not four decimal digits, if the code it carries is one no reason claims
     *     including the accepted sentinel zero, if the code is the one reason the baseline can never
     *     write to this stream, or if the description span disagrees with that reason's own text
     */
    public static TransactionReject toEntity(byte[] record) {
        byte[] image = requireRejectRecord(record);

        int code = decodeReasonCode(image);

        // WHY : Assumptions: the code is resolved through RejectReason's lookup factory rather than
        //       through a switch declared here, because that type is this module's single owner of the
        //       five-value vocabulary and of the mapping from a code to its text. A local switch would
        //       be a second statement of the same mapping, free to disagree with the first about text
        //       that app/cbl/CBTRN02C.cbl moves as a literal at :386-387, :398-399, :411-412, :418-419
        //       and :557-558, and that the migration carries across character for character.
        // WHY : Trade-offs: an unresolvable code FAILS rather than defaulting, and the compromise
        //       accepted is that a stream holding one byte of corruption yields no row at all instead
        //       of a partly usable one. That is the right way round here: the factory reports an
        //       unclaimed code and the accepted sentinel zero identically, by an empty result, and zero
        //       is what the reset at :208-209 leaves in the field for a record the writer never
        //       reaches -- so a zero arriving in an image means the image is not a reject record, and
        //       coercing either case to some nearest reason would invent a rejection reason the
        //       baseline never assigned and then persist it as fact.
        RejectReason reason = RejectReason.fromCode(code)
                .orElseThrow(() -> unresolvableReasonCode(code));
        requirePersistableReason(reason);

        String storedDescription = decodeReasonDescription(image, reason);
        return new TransactionReject(imageText(Arrays.copyOf(image, PREFIX_LENGTH)),
                reasonCodeOf(reason), storedDescription);
    }

    /**
     * Re-emits a stored reject row as the 430-byte record it was taken from.
     *
     * <p>Assumptions: the row carries the prefix as characters rather than as bytes, so this direction
     * transcodes where {@link #toRecord(byte[], PostingValidationResult)} copies. The character set
     * recorded on {@code IMAGE_CHARSET} is what makes that hop lossless, so a record re-emitted from a
     * row equals the record the row was taken from.</p>
     *
     * @param reject the {@code com.carddemo.batch.domain.TransactionReject} to render, whose stored
     *     image must still be its full 350 characters; it is read and never modified
     * @return a newly allocated byte array of exactly 430 bytes, being the stored image followed by the
     *     80-byte trailer rendered for the stored reason code
     * @throws RejectRecordException if {@code reject} is null, if its stored image is null or is not
     *     exactly 350 characters, if it holds a character no single byte can carry, if its reason code
     *     is null or is one no reason claims, if that code is the one reason the baseline can never
     *     write to this stream, or if its stored description disagrees with that reason's own text
     */
    public static byte[] toRecord(TransactionReject reject) {
        if (reject == null) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be rendered from a"
                    + " null row; the caller supplies the row it intends to emit");
        }

        Short storedCode = reject.getReasonCode();
        if (storedCode == null) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be rendered from a"
                    + " row whose reason code is absent; field " + REASON_CODE_FIELD.describe()
                    + " has no representation for an absent value, so the trailer could not be"
                    + " positioned at all");
        }

        int code = storedCode.intValue();
        RejectReason reason = RejectReason.fromCode(code)
                .orElseThrow(() -> unresolvableReasonCode(code));
        requirePersistableReason(reason);
        requireStoredDescription(reject.getReasonDesc(), reason);

        byte[] record = new byte[RECORD_LENGTH];

        // WHY : Assumptions: the stored image is written at its full declared width and is never
        //       trimmed on the way out. Its trailing blanks are positional content rather than padding,
        //       for the reason recorded on the storage column: a short image does not fail where it
        //       occurs -- it displaces the trailer and every byte after it, so a byte-exact comparison
        //       fails at position 350 and at every position following, and the reported difference
        //       points nowhere near the cause.
        System.arraycopy(imageBytes(reject.getRawRecord()), 0, record, 0, PREFIX_LENGTH);

        // WHY : Alternatives Considered: rendering the trailer from the row's own stored description
        //       rather than from the reason the stored code resolves to. Rejected because the stored
        //       description is a 76-character-capped column that a caller may have supplied at any
        //       width, whereas RejectReason pads to exactly 76 and is asserted against the oracle's
        //       committed expectation files; rendering from the column would let a short stored value
        //       emit a short trailer. The two are cross-checked above instead, so a row whose text
        //       disagrees with its code is refused rather than silently re-rendered into agreement.
        writeTrailer(record, reason.trailerField(), reason);
        return record;
    }

    /**
     * Resolves the one reason an outcome carries, refusing every outcome this stream cannot hold.
     *
     * <p>Assumptions: <b>reason-code precedence is not re-run here, and must not be.</b>
     * {@code app/cbl/CBTRN02C.cbl:407-413} and {@code :414-420} are two consecutive <b>unguarded</b>
     * {@code IF} blocks inside the same {@code NOT INVALID KEY} branch: nothing between them tests
     * whether a reason has already been assigned, so both can fire on one record and the later
     * assignment overwrites the earlier -- which is how 103 comes to beat 102, by last-writer-wins
     * rather than by any stated ranking. That resolution belongs to
     * {@code com.carddemo.batch.dto.PostingValidationResult}'s precedence-resolving factory, which is
     * the module's one implementation of it. Re-deriving it here would put two implementations of one
     * rule in the codebase, and two implementations of a last-writer-wins rule diverge in exactly the
     * case that matters: a record that trips both boundaries. This method therefore receives at most
     * one reason and compares nothing.</p>
     *
     * @param outcome the {@code com.carddemo.batch.dto.PostingValidationResult} to read the reason
     *     from, which may be null so that a null is reported by name rather than by a runtime failure
     * @return the single {@code com.carddemo.batch.dto.RejectReason} the outcome carries, already
     *     confirmed to be one the baseline can write to this stream
     * @throws RejectRecordException if {@code outcome} is null, if it carries no reason at all, or if it
     *     carries the one reason the baseline can never write to this stream
     */
    private static RejectReason requirePersistableReason(PostingValidationResult outcome) {
        if (outcome == null) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be assembled from a"
                    + " null validation outcome; the reason and its text come from the outcome and"
                    + " have no default");
        }

        Optional<RejectReason> carried = outcome.rejectReason();
        if (carried.isEmpty()) {
            // WHY : Assumptions: an accepted outcome is the sentinel state, not a reason, and it is
            //       refused rather than rendered. app/cbl/CBTRN02C.cbl:208-209 resets the reason to
            //       zero and its text to spaces before every record, and :211 posts on exactly that
            //       state -- the writer at :215 is reached only on the other branch of the same
            //       decision. So a four-zero trailer is a value the field holds between records and
            //       never a record the baseline appends. The outcome type renders that sentinel form so
            //       that its own accessor is total, which is why refusing it has to happen here rather
            //       than being left to the renderer.
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be assembled from an"
                    + " accepted outcome; app/cbl/CBTRN02C.cbl:211-215 reaches its reject writer only"
                    + " on the branch where a reason is present, and the four-zero sentinel that"
                    + " :208-209 leaves in field " + REASON_CODE_FIELD.describe() + " is never"
                    + " written");
        }
        return requirePersistableReason(carried.get());
    }

    /**
     * Refuses the one reason the baseline can never write to this stream.
     *
     * <p>Assumptions: <b>reason 109 cannot reach this stream on any execution path.</b>
     * {@code app/cbl/CBTRN02C.cbl:211} tests the reason for zero and posts at {@code :212} only when it
     * is zero; the reject writer is reached solely from {@code :213-215}, the other branch of that same
     * decision. Reason 109 is assigned at {@code :556}, inside the account-update paragraph at
     * {@code :545-558}, which is performed only from {@code :441} inside posting -- so it is assigned
     * after the reject decision has already been taken and on the branch that does not write. The
     * vocabulary type retains the constant because the literal exists in the baseline, and it publishes
     * the reachability answer as a predicate so that this class does not restate it. The oracle agrees
     * independently: the code appears in none of the committed reject expectation files.</p>
     *
     * <p>Trade-offs: the predicate is used as a <b>fail-closed</b> precondition, so a 109 arriving here
     * raises and produces no bytes at all. The alternative was to render it like any other reason,
     * which was rejected on what the two failure modes cost to diagnose: a 109 reaching this class is a
     * caller-ordering defect rather than a data condition, and a silently written 109 row would surface
     * only as an unexplained extra record in a byte-exact stream comparison, attributed to whatever
     * span the comparator reached first. Failing at the call that made the mistake names the mistake.</p>
     *
     * <p>Assumptions: on the two write-side entry points this check is the second of two, because
     * {@code com.carddemo.batch.dto.PostingValidationResult} already refuses to be constructed with
     * this reason at all -- so a 109 cannot be presented through an outcome, and the guard is
     * unreachable there. It is genuinely reachable on the two read-side entry points,
     * {@link #toEntity(byte[])} and {@link #toRecord(TransactionReject)}, which take a byte image and
     * a stored row rather than an outcome and can therefore each carry a code the writer never
     * emitted. Every entry point is checked rather than only those two, because a guard applied
     * selectively becomes a guard a later entry point forgets.</p>
     *
     * @param reason the {@code com.carddemo.batch.dto.RejectReason} to admit or refuse; never null,
     *     because every caller has already resolved it
     * @return the same reason, so that a caller can admit and bind in one expression
     * @throws RejectRecordException if the reason is the one the baseline can never write to this
     *     stream
     */
    private static RejectReason requirePersistableReason(RejectReason reason) {
        if (!reason.isPersistedToRejectStream()) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot carry reason code "
                    + reason.code() + "; app/cbl/CBTRN02C.cbl:545-558 assigns it inside the posting"
                    + " path, which :211-215 takes only when no reason was assigned, so the reject"
                    + " writer at :215 cannot be reached with it and a record carrying it is one the"
                    + " baseline never produced");
        }
        return reason;
    }

    /**
     * Validates a supplied prefix and returns it, so that a wrong width is refused before any copy.
     *
     * @param sourceImage the byte array offered as the verbatim daily-transaction image, which may be
     *     null so that a null is reported by name
     * @return the same array, confirmed to be exactly the daily record's declared length
     * @throws RejectRecordException if {@code sourceImage} is null or is not exactly 350 bytes
     */
    private static byte[] requireSourceImage(byte[] sourceImage) {
        if (sourceImage == null) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be assembled from a"
                    + " null transaction image; app/cbl/CBTRN02C.cbl:447 moves the whole record"
                    + " unconditionally, so there is no path on which the image is absent");
        }
        if (sourceImage.length != PREFIX_LENGTH) {
            // WHY : Assumptions: the width is checked before the copy rather than relying on the array
            //       copy to fail, because a SHORT image is the case an array copy would not refuse at
            //       all if the destination were larger, and it is the damaging one. A prefix short by
            //       one byte displaces the trailer and every byte after it, so the record still reads
            //       as 430 bytes and parses cleanly into different data. Naming the two widths here
            //       reports the cause; a comparison failure at position 350 would not.
            throw new RejectRecordException("record " + REGISTRY_NAME + " requires a transaction image"
                    + " of exactly " + PREFIX_LENGTH + " bytes, the declared length of the record"
                    + " app/cpy/CVTRA06Y.cpy declares, but was handed " + sourceImage.length);
        }
        return sourceImage;
    }

    /**
     * Validates a supplied decode carrier and returns it, so that a null is reported by name.
     *
     * @param decoded the {@link DailyTransactionMapper.DecodedRecord} offered as the source of the
     *     verbatim image, which may be null
     * @return the same carrier, confirmed non-null
     * @throws RejectRecordException if {@code decoded} is null
     */
    private static DailyTransactionMapper.DecodedRecord requireDecodedRecord(
            DailyTransactionMapper.DecodedRecord decoded) {
        if (decoded == null) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be assembled from a"
                    + " null decode carrier; the carrier is what holds the verbatim image that"
                    + " app/cbl/CBTRN02C.cbl:447 requires, and the image cannot be recovered from the"
                    + " entity once it is gone");
        }
        return decoded;
    }

    /**
     * Validates a supplied assembled record and returns it, so that a wrong width is refused early.
     *
     * @param record the byte array offered as a complete reject record image, which may be null
     * @return the same array, confirmed to be exactly 430 bytes
     * @throws RejectRecordException if {@code record} is null or is not exactly 430 bytes
     */
    private static byte[] requireRejectRecord(byte[] record) {
        if (record == null) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be taken apart from a"
                    + " null image");
        }
        if (record.length != RECORD_LENGTH) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " requires an image of exactly"
                    + " " + RECORD_LENGTH + " bytes, the fixed length app/jcl/POSTTRAN.jcl:36"
                    + " allocates the stream at, but was handed " + record.length);
        }
        return record;
    }

    /**
     * Writes an already-rendered 80-character trailer into the two spans the layout positions.
     *
     * <p>Alternatives Considered: rendering the code and the description in this class -- zero-padding
     * the number to four characters and blank-padding the text to 76 -- which is the shorter way to
     * write it and is prohibited here. Both renderings, and the four description literals themselves,
     * are owned by {@code com.carddemo.batch.dto.RejectReason}, and the complete 80-character trailer is
     * assembled by {@code com.carddemo.batch.dto.PostingValidationResult}. Those texts are user-visible
     * message text carried across from {@code app/cbl/CBTRN02C.cbl:386-387}, {@code :398-399},
     * {@code :411-412}, {@code :418-419} and {@code :557-558} character for character, and a second
     * copy of them in a mapper is a second place they can drift from the baseline -- silently, because
     * a mapper's own copy would keep passing that mapper's own tests. One owner, consumed everywhere;
     * this method places the owner's output and composes none of it.</p>
     *
     * @param record the byte array being assembled, of exactly 430 bytes; the trailer spans are
     *     overwritten in place
     * @param trailer the String the owner already rendered, which it guarantees is exactly 80
     *     characters of four-digit code followed by 76-character description
     * @param reason the {@code com.carddemo.batch.dto.RejectReason} the trailer was rendered for, named
     *     in a width failure so the report identifies which rendering was wrong
     * @throws RejectRecordException if the rendered trailer is not exactly 80 characters, which would
     *     mean the owner's two field widths no longer sum to the width this record positions them at
     */
    private static void writeTrailer(byte[] record, String trailer, RejectReason reason) {
        if (trailer.length() != TRAILER_LENGTH) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " positions field "
                    + REASON_CODE_FIELD.describe() + " and field " + REASON_DESC_FIELD.describe()
                    + " as a " + TRAILER_LENGTH + "-character trailer, but the rendering for reason"
                    + " code " + reason.code() + " was " + trailer.length() + " characters");
        }

        // WHY : Assumptions: the trailer is written as one span rather than as two, because
        //       app/cbl/CBTRN02C.cbl:448 moves the trailer GROUP into a PIC X(80) span -- one move of
        //       equal widths, so byte-identical with no conversion. Writing the two fields separately
        //       would reproduce the same bytes but would model a decomposition the baseline performs
        //       only in working storage, and it would need the code and the text as two values, which
        //       is the split this class deliberately does not own.
        // WHY : Assumptions: the trailer's own bytes are seven-bit by construction -- the code is
        //       decimal digits and the four description texts are upper-case letters and blanks -- so
        //       the Latin-1 set encodes them identically to the seven-bit set the shared codec uses.
        //       Using one set for the whole class keeps a single character-set decision to justify.
        byte[] trailerBytes = trailer.getBytes(IMAGE_CHARSET);
        System.arraycopy(trailerBytes, 0, record, PREFIX_LENGTH, TRAILER_LENGTH);
    }

    /**
     * Renders a verbatim prefix as the 350 characters the row's fixed-width column stores.
     *
     * <p>Trade-offs: the result is stored as a fixed-width 350-character value and is <b>never
     * trimmed</b>, and this is the most consequential representation decision on the row. The prefix is
     * a fixed-width image in which trailing blanks are positional content rather than padding: a
     * trimmed value could not be re-emitted at the correct length, and the five descriptive fields
     * inside it end in blanks by construction because a COBOL move into an alphanumeric target
     * left-justifies and blank-fills. The compromise accepted is a column that stores runs of blanks,
     * in exchange for a byte image that can be re-emitted losslessly. That is the opposite of the
     * treatment the description span gets in
     * {@link #decodeReasonDescription(byte[], RejectReason)}, which strips its pad on the way in and
     * has it reapplied on the way out, because that field's text is a value rather than an image --
     * and the two are deliberately different rather than inconsistent.</p>
     *
     * @param prefix the byte array holding the verbatim image, already confirmed to be exactly 350
     *     bytes
     * @return the image as exactly 350 characters, with every trailing blank retained
     * @throws RejectRecordException if the transcoded text is not exactly 350 characters, which the
     *     character set's one-byte-to-one-character mapping makes unreachable and which is asserted so
     *     that a future change of set cannot silently shorten the stored image
     */
    private static String imageText(byte[] prefix) {
        String text = new String(prefix, IMAGE_CHARSET);
        if (text.length() != PREFIX_LENGTH) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " requires a stored image of"
                    + " exactly " + PREFIX_LENGTH + " characters, but transcoding " + prefix.length
                    + " bytes through " + IMAGE_CHARSET.name() + " produced " + text.length());
        }
        return text;
    }

    /**
     * Renders a stored 350-character image back to the bytes it was transcoded from.
     *
     * @param storedImage the row's stored image, which may be null so that a null is reported by name
     * @return the image as exactly 350 bytes, equal to the bytes it was transcoded from
     * @throws RejectRecordException if {@code storedImage} is null, if it is not exactly 350
     *     characters, or if it holds a character outside the single-byte range the character set covers
     */
    private static byte[] imageBytes(String storedImage) {
        if (storedImage == null) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be rendered from a"
                    + " row whose stored image is absent; the image is the record's first "
                    + PREFIX_LENGTH + " bytes and nothing else can supply them");
        }
        if (storedImage.length() != PREFIX_LENGTH) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " requires a stored image of"
                    + " exactly " + PREFIX_LENGTH + " characters, but the row held "
                    + storedImage.length() + "; a short image displaces the trailer and every byte"
                    + " after it rather than failing where it occurs");
        }

        byte[] bytes = storedImage.getBytes(IMAGE_CHARSET);

        // WHY : Assumptions: an unmappable character is refused rather than substituted, and the check
        //       is a real one rather than a formality. Java's encoder replaces an unmappable character
        //       with a question mark instead of raising, so a stored image that had acquired a
        //       character outside the single-byte range would re-emit as a plausible 350 bytes with one
        //       byte quietly wrong -- and a one-byte difference inside a 430-byte comparison reports as
        //       a difference at that offset with no indication of why. The Latin-1 set covers every
        //       character this class can have produced, so reaching this branch means the stored value
        //       did not come from this class.
        String verified = new String(bytes, IMAGE_CHARSET);
        if (!verified.equals(storedImage)) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot render its stored"
                    + " image, which holds at least one character that " + IMAGE_CHARSET.name()
                    + " cannot carry in a single byte; the value did not originate from this"
                    + " boundary and re-encoding it would substitute a byte rather than fail");
        }
        return bytes;
    }

    /**
     * Reads the four-digit reason span of an assembled record as a number.
     *
     * <p>Assumptions: the span is numeric-display, {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:181}, so a three-digit code occupies it with a leading zero rather
     * than a leading blank -- which the oracle confirms, its expectation files carrying {@code 0100},
     * {@code 0101}, {@code 0102} and {@code 0103} at one-based positions 351 through 354. A blank or a
     * non-digit in the span therefore means the image is not a reject record, and it is reported rather
     * than coerced.</p>
     *
     * @param record the byte array holding one assembled record, already confirmed to be 430 bytes
     * @return the number the four digits denote, in the inclusive range 0 to 9999
     * @throws RejectRecordException if the span is not four decimal digits
     */
    private static int decodeReasonCode(byte[] record) {
        String digits = spanText(record, REASON_CODE_FIELD);
        for (int index = 0; index < digits.length(); index++) {
            char candidate = digits.charAt(index);
            if (candidate < '0' || candidate > '9') {
                throw new RejectRecordException("record " + REGISTRY_NAME + " field "
                        + REASON_CODE_FIELD.describe() + " must hold " + REASON_CODE_WIDTH + " decimal"
                        + " digits, as app/cbl/CBTRN02C.cbl:181 declares it numeric-display, but"
                        + " position " + index + " of the span is not a digit");
            }
        }

        // WHY : Assumptions: the four-digit width makes the value at most 9999, so no overflow check is
        //       needed and none is written. The width itself is asserted at class initialisation
        //       against the vocabulary type's own code width, which is what keeps that reasoning true
        //       rather than merely true today.
        return Integer.parseInt(digits);
    }

    /**
     * Reads the description span of an assembled record and confirms it belongs to the resolved reason.
     *
     * <p>Assumptions: the code is the authority and the text is checked against it, never the other way
     * round. Two of the five declared reasons carry the identical text, because
     * {@code app/cbl/CBTRN02C.cbl:398-399} and {@code :557-558} move the same literal, so text does not
     * determine a code while a code always determines its text. A span that disagrees with its code is
     * therefore a representation defect in the image rather than an ambiguity, and it is reported: the
     * baseline moves each code and its literal together in one adjacent pair of statements, so no
     * execution produces a record in which they disagree.</p>
     *
     * @param record the byte array holding one assembled record, already confirmed to be 430 bytes
     * @param reason the {@code com.carddemo.batch.dto.RejectReason} the four-digit span resolved to
     * @return that reason's own text, with the trailing pad removed, which is the value the row's
     *     variable-width description column stores
     * @throws RejectRecordException if the span, once its trailing pad is removed, is not that reason's
     *     text
     */
    private static String decodeReasonDescription(byte[] record, RejectReason reason) {
        String span = spanText(record, REASON_DESC_FIELD);
        String trimmed = stripTrailingBlanks(span);
        if (!trimmed.equals(reason.description())) {
            // WHY : Trade-offs: neither the span nor the expected text is quoted in this message, and
            //       the offset and the two lengths are reported instead. The four description texts are
            //       not themselves sensitive, but quoting a span positioned by a descriptor invites the
            //       same construction against a span that is -- the prefix holds a primary account
            //       number at offset 262 -- so the class keeps one rule for every span rather than a
            //       rule with an exception a later edit could widen.
            throw new RejectRecordException("record " + REGISTRY_NAME + " field "
                    + REASON_DESC_FIELD.describe() + " does not carry the text reason code "
                    + reason.code() + " is assigned with in app/cbl/CBTRN02C.cbl; the span trims to "
                    + trimmed.length() + " characters where that reason's text is "
                    + reason.description().length());
        }
        return trimmed;
    }

    /**
     * Confirms a row's stored description belongs to the reason its stored code resolves to.
     *
     * @param storedDescription the row's stored description, which may be null so that a null is
     *     reported by name
     * @param reason the {@code com.carddemo.batch.dto.RejectReason} the row's stored code resolved to
     * @throws RejectRecordException if {@code storedDescription} is null, or if it is not that reason's
     *     text once trailing blanks are removed
     */
    private static void requireStoredDescription(String storedDescription, RejectReason reason) {
        if (storedDescription == null) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be rendered from a"
                    + " row whose stored description is absent; field "
                    + REASON_DESC_FIELD.describe() + " has no representation for an absent value");
        }

        // WHY : Assumptions: the stored value is compared with its trailing blanks removed, because the
        //       column is variable-width and the emitter is what pads the text out to the declared 76.
        //       A row read back from a fixed-width column, or one a caller supplied already padded,
        //       would otherwise be refused for carrying the very padding the renderer is about to
        //       reapply.
        if (!stripTrailingBlanks(storedDescription).equals(reason.description())) {
            throw new RejectRecordException("record " + REGISTRY_NAME + " cannot be rendered from a"
                    + " row whose stored description does not match reason code " + reason.code()
                    + "; the stored text trims to " + stripTrailingBlanks(storedDescription).length()
                    + " characters where that reason's text is " + reason.description().length());
        }
    }

    /**
     * Reads one declared span of a record as text, without interpreting it.
     *
     * @param record the byte array holding the record, already confirmed to be its declared length
     * @param field the {@code com.carddemo.common.codec.CopybookLayout.FieldSpec} naming the offset and
     *     width of the span to read
     * @return the span as exactly {@code field.length()} characters, with nothing trimmed or parsed
     */
    private static String spanText(byte[] record, CopybookLayout.FieldSpec field) {
        // WHY : Alternatives Considered: com.carddemo.common.codec.FixedWidthCodec.decodeField, which
        //       would read the same two spans and would additionally reject a non-digit in the numeric
        //       one, so it is the obvious candidate. Rejected because it decodes against the SEVEN-BIT
        //       set and validates that a character field's bytes round-trip through it, which would
        //       refuse an image this boundary must accept -- app/cbl/CBTRN02C.cbl:447 places no
        //       constraint on the prefix's bytes, and one character set has to serve the whole record
        //       for the round-trip property to hold. Reading the span here through the record's own set
        //       keeps one set in force, and the digit check the codec would have performed is written
        //       out explicitly at the one span that needs it.
        return new String(record, field.start(), field.length(), IMAGE_CHARSET);
    }

    /**
     * Removes the trailing blank pad a fixed-width alphanumeric span carries.
     *
     * <p>Assumptions: only the blank is removed and only from the end, because a COBOL move into an
     * alphanumeric target left-justifies the value and fills the remainder with blanks -- so the pad is
     * always trailing and is always this one character. The platform's own general trim would also
     * remove leading blanks and every other character it classifies as whitespace, which would silently
     * alter a description that legitimately began with one.</p>
     *
     * @param span the String fixed-width span to strip, never null
     * @return the same text with its trailing blanks removed, which may be the empty string when the
     *     span is entirely blank
     */
    private static String stripTrailingBlanks(String span) {
        int end = span.length();
        while (end > 0 && span.charAt(end - 1) == BLANK) {
            end--;
        }
        return span.substring(0, end);
    }

    /**
     * Narrows a resolved reason's code to the integral width the row's column declares.
     *
     * <p>Assumptions: the column is a small integer because the source field is
     * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:181} -- unsigned and
     * four digits, so its whole domain is 0 through 9999 and a small integer is the narrowest exact
     * type covering it. The narrowing is therefore always in range, and it is written as a conversion
     * from the vocabulary's own value rather than as one of the four literal codes so that the row and
     * the rendered trailer cannot disagree about which reason they describe.</p>
     *
     * @param reason the {@code com.carddemo.batch.dto.RejectReason} whose code the row records
     * @return that reason's code as the small integral value the column takes
     */
    private static Short reasonCodeOf(RejectReason reason) {
        return Short.valueOf((short) reason.code());
    }

    /**
     * Builds the failure raised for a reason code no declared reason claims.
     *
     * @param code the number the four-digit span or the stored column carried
     * @return the failure to raise, naming the code and why an unclaimed one is not coerced
     */
    private static RejectRecordException unresolvableReasonCode(int code) {
        return new RejectRecordException("record " + REGISTRY_NAME + " carries reason code " + code
                + ", which no reason app/cbl/CBTRN02C.cbl assigns claims; the reset at :208-209 leaves"
                + " zero in field " + REASON_CODE_FIELD.describe() + " for a record the writer at :215"
                + " never reaches, so zero and any other unclaimed value alike mean the image is not a"
                + " reject record and are reported rather than coerced to a nearest reason");
    }

    /**
     * Proves that the registry's derived geometry still matches all four confirmations of the layout.
     *
     * <p>Assumptions: every number this class positions a span with is read from the resolved
     * descriptor, so the only way a wrong width can reach a record is a drifted descriptor. This method
     * is what makes that unreachable, and it asserts the derivation as well as the widths: the prefix
     * length is taken from the daily-transaction layout and is then required to equal the offset the
     * reason span begins at, so the two independent routes to 350 have to agree.</p>
     *
     * @throws RejectRecordException if the resolved record length is not 430, if the prefix length the
     *     daily-transaction layout declares is not where the reason span begins, if either trailer span
     *     is not at its declared offset or width, if the two trailer widths do not sum to the trailer
     *     length, or if either trailer width disagrees with the width the vocabulary type renders to
     */
    private static void verifyGeometry() {
        if (LAYOUT.reclen() != RECORD_LENGTH) {
            throw new RejectRecordException("layout " + REGISTRY_NAME + " must declare a record length"
                    + " of " + RECORD_LENGTH + " bytes, confirmed by app/cbl/CBTRN02C.cbl:82-84,"
                    + " :176-182, app/jcl/POSTTRAN.jcl:36 and the committed reject expectations, but"
                    + " declares " + LAYOUT.reclen());
        }

        // WHY : Assumptions: this is the derivation's proof and not a redundant comparison. PREFIX
        //       LENGTH is read from the daily-transaction layout's declared length, while the reason
        //       span's offset is read from this layout's own descriptor, and the two are the same
        //       number reached by independent routes only because app/cbl/CBTRN02C.cbl:177-178 places
        //       the trailer immediately after the 350-byte data area. Asserting the equality is what
        //       lets this class take the prefix width from its owner rather than declaring a second
        //       350, which is the drift the registry exists to prevent.
        requireSpan(REASON_CODE_FIELD, PREFIX_LENGTH, REASON_CODE_WIDTH);
        requireSpan(REASON_DESC_FIELD, PREFIX_LENGTH + REASON_CODE_WIDTH, REASON_DESC_WIDTH);

        if (REASON_CODE_WIDTH + REASON_DESC_WIDTH != TRAILER_LENGTH) {
            throw new RejectRecordException("layout " + REGISTRY_NAME + " must decompose its"
                    + " " + TRAILER_LENGTH + "-byte trailer into " + REASON_CODE_WIDTH + " plus "
                    + REASON_DESC_WIDTH + " bytes, as app/cbl/CBTRN02C.cbl:178-182 declares, but those"
                    + " widths sum to " + (REASON_CODE_WIDTH + REASON_DESC_WIDTH));
        }
        if (PREFIX_LENGTH + TRAILER_LENGTH != RECORD_LENGTH) {
            throw new RejectRecordException("layout " + REGISTRY_NAME + " must reconcile a "
                    + PREFIX_LENGTH + "-byte prefix and a " + TRAILER_LENGTH + "-byte trailer to "
                    + RECORD_LENGTH + " bytes, but they sum to " + (PREFIX_LENGTH + TRAILER_LENGTH));
        }

        // WHY : Assumptions: the two widths are asserted against the vocabulary type's own constants
        //       rather than trusted, because that type performs the padding while this class positions
        //       the result. A width stated in two places is a width that can disagree, and a
        //       disagreement here would emit a trailer that overruns or underfills its span while every
        //       individual rendering still looked correct.
        if (RejectReason.CODE_WIDTH != REASON_CODE_WIDTH
                || RejectReason.DESCRIPTION_WIDTH != REASON_DESC_WIDTH) {
            throw new RejectRecordException("layout " + REGISTRY_NAME + " positions a "
                    + REASON_CODE_WIDTH + "-byte code and a " + REASON_DESC_WIDTH + "-byte"
                    + " description, but the reason vocabulary renders " + RejectReason.CODE_WIDTH
                    + " and " + RejectReason.DESCRIPTION_WIDTH + " characters respectively");
        }
    }

    /**
     * Asserts that one resolved trailer descriptor sits at the offset and width the baseline declares.
     *
     * @param field the {@code com.carddemo.common.codec.CopybookLayout.FieldSpec} the registry resolved
     * @param expectedStart the zero-based offset the working-storage listing places the span at
     * @param expectedLength the byte width the working-storage listing declares for the span
     * @throws RejectRecordException if the descriptor's offset or width differs from the declaration
     */
    private static void requireSpan(CopybookLayout.FieldSpec field, int expectedStart,
            int expectedLength) {
        if (field.start() != expectedStart || field.length() != expectedLength) {
            throw new RejectRecordException("layout " + REGISTRY_NAME + " must place field "
                    + field.name() + " at offset " + expectedStart + " for " + expectedLength
                    + " bytes, as app/cbl/CBTRN02C.cbl:176-182 declares it, but resolved "
                    + field.describe());
        }
    }

    /**
     * Reports a reject record this boundary cannot assemble or take apart without inventing content.
     *
     * <p>Assumptions: it extends the platform's illegal-argument failure so that it joins the family
     * the shared codecs already raise -- their record-length, field-codec and zoned-decimal failures are
     * all unchecked argument failures -- so a caller that wants to treat every malformed record alike
     * can catch one type. A distinct type is still declared rather than raising the base class, because
     * the failures this boundary adds are the ones a codec cannot see: an outcome carrying no reason, a
     * reason the writer can never be reached with, a code no reason claims, and a description that
     * disagrees with its code.</p>
     *
     * <p>Alternatives Considered: reusing the failure type the sibling feed boundary declares rather
     * than declaring one here. That is impossible rather than merely undesirable -- its constructors
     * are private to it by its own design, so every message it carries has been sanitised by the class
     * that raised it, and widening them would let any caller fabricate a failure attributed to that
     * class. The name differs from that sibling's as well, following the naming the export boundary
     * uses, so that two same-named nested failures do not sit in one package for a reader to
     * disambiguate by enclosing type.</p>
     *
     * <p>Assumptions: nothing this type carries may quote the record image or a span of it. The prefix
     * holds a full primary account number at offset 262, which the registry marks sensitive, so every
     * message handed to it names a field descriptor, an offset, a width or a reason code and never the
     * characters at a position.</p>
     */
    public static final class RejectRecordException extends IllegalArgumentException {

        // WHY : Assumptions: the platform expects a serial version identifier on a serialisable type,
        //       and this one inherits serialisability from the exception hierarchy. Pinning the value
        //       stops the compiler deriving one that changes whenever a member is added, which is what
        //       keeps a serialised instance readable across builds. The shared codecs and the sibling
        //       boundaries pin theirs the same way.
        private static final long serialVersionUID = 1L;

        /**
         * Creates a failure carrying an already-sanitised description.
         *
         * @param message the String describing what could not be assembled or taken apart; the caller is
         *     responsible for having withheld the record image and every span of it, because this
         *     constructor performs no sanitisation of its own
         */
        private RejectRecordException(String message) {
            super(message);
        }
    }
}
