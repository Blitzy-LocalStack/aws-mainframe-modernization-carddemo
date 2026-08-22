package com.carddemo.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps one feed record a posting run accounted for onto {@code batch.posting_reject_outbox}.
 *
 * <p>A row is one daily-transaction record that a posting run has finished with, and it is written
 * inside that record's own transaction -- beside the posted writes or the decomposed reject row, and
 * beside the watermark advance that accounts for it. A record that was REJECTED carries the verbatim
 * 430-byte reject image; a record that was POSTED carries no image at all. Both carry a row, because
 * the run's two counters are counts of rows.</p>
 *
 * <h2>Why this mapping exists</h2>
 *
 * <p>Assumptions: the reject stream used to exist only as a temporary file on the task's own disk
 * until the walk finished and the file was uploaded, so a failure between the last per-record commit
 * and a durable object lost the stream while every row it described stayed committed and the
 * watermark stayed advanced. A redrive then read strictly above that watermark, found nothing, staged
 * an empty dataset and reported both counters as zero -- publishing a clean tier for a run that had
 * rejected records. Storing the image inside the record's transaction is what makes the dataset and
 * the counters reconstructible from durable state, which is the property a redriven step needs and
 * the one the object-store upload cannot provide on its own.</p>
 *
 * <p>Alternatives Considered: carrying only the rejected records, which is the narrower table the
 * name suggests. Rejected because it recovers the dataset and leaves the PROCESSED counter
 * unrecoverable: {@code app/cbl/CBTRN02C.cbl:227} prints a processed count beside the rejected count
 * at {@code :228}, and a redrive that walks no new feed rows would still report zero processed while
 * reporting its rejects correctly, which is half of the same false report. One row per accounted
 * record makes both counters a count of rows over one table.</p>
 *
 * <p>Alternatives Considered: re-deriving the dataset from {@code ledger.transaction_rejects}, which
 * already holds the three parts the 430-byte record decomposes into. Rejected because that table
 * carries no run discriminator -- no run identifier, no business date and no generation -- so a query
 * against it cannot tell one run's rejects from every reject ever loaded, and because its shape
 * belongs to the transaction context: this module writes into it under a narrowly scoped grant and
 * declares no structure there.</p>
 *
 * <h2>The image, and why it is stored as fixed-width characters</h2>
 *
 * <p>Assumptions: the image is the exact 430 bytes {@code app/jcl/POSTTRAN.jcl:36} allocates as
 * {@code LRECL=430} and {@code app/cbl/CBTRN02C.cbl:83-84} declares as a 350-byte record followed by
 * an 80-byte validation trailer. It is stored WHOLE and never parsed: the dataset's value as an
 * operational artifact is that it is byte-faithful, and the committed expectations under
 * {@code tests/golden/posting} compare it character for character.</p>
 *
 * <p>Assumptions: the column is a fixed-width character column, so the stored value is returned
 * padded to its full 430 characters. That matters because the record's trailing blanks are CONTENT --
 * the 76-character reason description is blank-padded -- so a variable-width column would return a
 * shorter string for a record whose description was short, and the rebuilt dataset would then carry
 * records of differing lengths. {@code ledger.transaction_rejects.raw_record} is declared the same way
 * at 350 for the same reason.</p>
 *
 * <p>Trade-offs: the bytes cross into characters and back, which is lossless here and is documented
 * rather than assumed. The image is carried through {@link #IMAGE_CHARSET}, a single-byte encoding
 * whose 256 code points map one-to-one onto the 256 byte values, so every byte survives the hop in
 * both directions and the character count equals the byte count. A multi-byte encoding would break
 * both properties: a byte above 0x7F would decode to a replacement character or to a multi-character
 * sequence, and the width check below would then pass or fail for the wrong reason.</p>
 *
 * @see com.carddemo.batch.repository.PostingRejectOutboxRepository for the four operations this
 *     mapping is reached through
 */
@Entity
@Table(name = "posting_reject_outbox", schema = "batch")
public class PostingRejectOutbox {

    /**
     * Width of the reject image, in bytes and equally in stored characters.
     *
     * <p>Corroborated four ways in the baseline: the file description at
     * {@code app/cbl/CBTRN02C.cbl:83-84}, the working-storage pair at {@code :176-178}, the assembled
     * trailer at {@code :180-182} and the dataset allocation at {@code app/jcl/POSTTRAN.jcl:36}.</p>
     */
    public static final int REJECT_RECORD_LENGTH = 430;

    /** Width of the business-date token, which is carried verbatim rather than parsed. */
    private static final int BUSINESS_DATE_TOKEN_LENGTH = 10;

    /** Greatest object key the store admits, and therefore the width of the staged-key column. */
    private static final int OBJECT_KEY_LENGTH = 1024;

    /** Width of the run identifier, matching {@code batch.batch_run.run_id}. */
    private static final int RUN_ID_LENGTH = 80;

    /** Width of the feed name, matching {@code batch.daily_feed_watermark.feed_name}. */
    private static final int FEED_NAME_LENGTH = 30;

    /**
     * The single-byte encoding the image crosses between bytes and characters through.
     *
     * <p>Assumptions: the same encoding {@code TransactionRejectRecordMapper} carries the image
     * through, chosen for the same property: it is the one encoding under which a byte and a character
     * are interchangeable, so a 430-byte record is 430 characters and no byte value is unmappable.</p>
     */
    private static final Charset IMAGE_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * Insert ordinal of this row, assigned by the database.
     *
     * <p>It identifies the row and orders nothing: the dataset is ordered by {@link #ingestSeq},
     * because that is the order the walk which produced it appended in.</p>
     */
    // Assumptions: identity generation, matching the GENERATED BY DEFAULT AS IDENTITY column
    //     V4__batch_posting_reject_outbox.sql declares. It delegates to the column the migration
    //     already declares rather than naming a generator of its own, which is what keeps this
    //     package's mappings compatible with a schema nothing here creates.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_seq", updatable = false)
    private Long outboxSeq;

    /**
     * The orchestrator execution that accounted for the record.
     *
     * <p>Assumptions: this is a control value and not a diagnostic one. It is the key a redrive of the
     * same execution reads its predecessor's work back by, so a row written under a different run
     * identifier is a row that redrive cannot find.</p>
     */
    @Column(name = "run_id", nullable = false, length = RUN_ID_LENGTH, updatable = false)
    private String runId;

    /** The record-layout name of the feed the ordinal belongs to, spelled as the watermark spells it. */
    @Column(name = "feed_name", nullable = false, length = FEED_NAME_LENGTH, updatable = false)
    private String feedName;

    /**
     * The feed ordinal that was accounted for, which is the order the dataset is rebuilt in.
     *
     * <p>Assumptions: this is the same ordinal {@code batch.daily_feed_watermark} stores, so ascending
     * order here reproduces the append order of the walk -- including across attempts, since a later
     * attempt begins strictly above the position the earlier one advanced and so contributes only
     * higher ordinals.</p>
     */
    @Column(name = "ingest_seq", nullable = false, updatable = false)
    private long ingestSeq;

    /**
     * The injected business-date token of the accounting run, ten characters carried verbatim.
     *
     * <p>Assumptions: a token and not a date, for the reason {@code BusinessDate} records: the
     * reference injects a compact ten-character form and the migrated chain injects the separated ISO
     * form, both are legitimate, and parsing would fail the compact one.</p>
     */
    @Column(name = "business_date", nullable = false, length = BUSINESS_DATE_TOKEN_LENGTH,
            updatable = false)
    private String businessDate;

    /**
     * The rejected record as its exact 430 characters, or null when the record was posted.
     *
     * <p>Assumptions: null is the only spelling of "this record posted". A posted record still gets a
     * row, because that is what makes the processed counter a count of rows, and it contributes no
     * bytes to the dataset -- so the column that would hold its bytes is absent rather than blank. The
     * owning migration refuses an all-blank image so the two states cannot be confused.</p>
     */
    // Assumptions: the JDBC type is stated explicitly because the provider would otherwise bind a
    //     String as a variable-width value, and the deployed column is fixed-width. The mismatch
    //     would be reported by the validate-only start-up pass rather than at run time, but stating
    //     it here is what makes the mapping and the migration one contract instead of two.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reject_record", length = REJECT_RECORD_LENGTH, updatable = false)
    private String rejectRecord;

    /** When the record's unit of work committed, read from the module's injected clock. */
    @Column(name = "accounted_at", nullable = false, updatable = false)
    private LocalDateTime accountedAt;

    /**
     * When the object holding this image became durable, or null while it has not.
     *
     * <p>Assumptions: this member is written by the repository's marking statement and by nothing on
     * this type, and the asymmetry is deliberate. Marking is a set operation over every unstaged
     * reject of one run, performed once after an upload returns; routing it through this instance
     * would mean re-reading each row to mutate it, and the rows have deliberately just been read as
     * bytes rather than as managed instances.</p>
     */
    @Column(name = "staged_at")
    private LocalDateTime stagedAt;

    /** Object key of the dataset generation this image reached, or null while it has reached none. */
    @Column(name = "staged_object_key", length = OBJECT_KEY_LENGTH)
    private String stagedObjectKey;

    /**
     * Creates an unpopulated instance for the persistence provider to hydrate.
     */
    // Assumptions: the provider requires a non-private no-argument constructor to instantiate this
    //     type reflectively when materialising a row, and it assigns the members afterwards by field
    //     access. The body is empty by design: anything initialised here would be overwritten on
    //     every load and would mask an absent column rather than surface it.
    // Alternatives Considered: private visibility. Rejected because a subclass generated for a lazy
    //     proxy must be able to invoke it, so protected is the narrowest visibility that works.
    //     Public was rejected too: it would let application code create a row belonging to no run.
    protected PostingRejectOutbox() {
        // Assumptions: intentionally empty, because the provider populates every mapped member
        //     immediately after reflective creation.
    }

    /**
     * Creates one accounted-record row, with or without a reject image.
     *
     * @param runId the orchestrator execution that accounted for the record; must not be
     *     {@code null} or blank
     * @param feedName the record-layout name of the feed; must not be {@code null} or blank
     * @param ingestSeq the feed ordinal accounted for; must not be negative
     * @param businessDate the injected business-date token, exactly ten characters; must not be
     *     {@code null}
     * @param rejectRecord the reject image as exactly {@value #REJECT_RECORD_LENGTH} characters, or
     *     {@code null} when the record was posted rather than rejected
     * @param accountedAt the instant the record's unit of work committed; must not be {@code null}
     * @throws NullPointerException if any argument other than {@code rejectRecord} is {@code null}
     * @throws IllegalArgumentException if either identifying string is blank, if the ordinal is
     *     negative, if the business-date token is not exactly ten characters, or if a supplied image
     *     is not exactly {@value #REJECT_RECORD_LENGTH} characters
     */
    // Assumptions: this constructor is private and the two named factories below are the only way in,
    //     because the difference between a posted row and a rejected one is a null argument in the
    //     fifth position. A caller reading `new PostingRejectOutbox(run, feed, seq, date, null, at)`
    //     cannot see which of the two arms it selected, whereas the factory names state it. The
    //     validation lives here rather than being duplicated in both factories.
    private PostingRejectOutbox(String runId, String feedName, long ingestSeq, String businessDate,
            String rejectRecord, LocalDateTime accountedAt) {

        this.runId = requireToken(runId, "runId");
        this.feedName = requireToken(feedName, "feedName");
        this.businessDate = requireBusinessDate(businessDate);
        this.accountedAt = Objects.requireNonNull(accountedAt, "accountedAt must not be null");

        // Assumptions: zero is admitted and negatives are not, matching the owning migration's own
        //     ordinal check and the watermark's. Feed ordinals begin at one, so a negative value is
        //     something computed rather than something read, which is worth failing on.
        if (ingestSeq < 0L) {
            throw new IllegalArgumentException("ingestSeq must not be negative but was " + ingestSeq);
        }
        this.ingestSeq = ingestSeq;

        // Assumptions: an image is checked for an EXACT width rather than a maximum, because its
        //     padding is content: the emitter renders a fixed-width record, so a legitimate value is
        //     never short. The fixed-width column would pad a short value out on storage, but by then
        //     the caller has already been told the row was accepted, and the dataset rebuilt from it
        //     would carry the padding at the wrong offsets.
        if (rejectRecord != null && rejectRecord.length() != REJECT_RECORD_LENGTH) {
            throw new IllegalArgumentException("rejectRecord must be exactly " + REJECT_RECORD_LENGTH
                    + " characters but was " + rejectRecord.length());
        }
        this.rejectRecord = rejectRecord;
    }

    /**
     * Creates the row for a feed record that was POSTED, which carries no reject image.
     *
     * @param runId the orchestrator execution that posted the record; must not be {@code null} or
     *     blank
     * @param feedName the record-layout name of the feed; must not be {@code null} or blank
     * @param ingestSeq the feed ordinal that was posted; must not be negative
     * @param businessDate the injected business-date token, exactly ten characters; must not be
     *     {@code null}
     * @param accountedAt the instant the record's unit of work committed; must not be {@code null}
     * @return the row to insert inside that record's own transaction, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if either identifying string is blank, if the ordinal is
     *     negative, or if the business-date token is not exactly ten characters
     */
    // Assumptions: a posted record gets a row at all because the processed counter is derived from
    //     this table. app/cbl/CBTRN02C.cbl:227 prints a processed count that a redriven step has to
    //     be able to state for the whole run rather than for its own attempt, and the only durable
    //     record of "this record was accounted for" is a row here.
    public static PostingRejectOutbox postedRecord(String runId, String feedName, long ingestSeq,
            String businessDate, LocalDateTime accountedAt) {

        return new PostingRejectOutbox(runId, feedName, ingestSeq, businessDate, null, accountedAt);
    }

    /**
     * Creates the row for a feed record that was REJECTED, carrying its verbatim image.
     *
     * @param runId the orchestrator execution that rejected the record; must not be {@code null} or
     *     blank
     * @param feedName the record-layout name of the feed; must not be {@code null} or blank
     * @param ingestSeq the feed ordinal that was rejected; must not be negative
     * @param businessDate the injected business-date token, exactly ten characters; must not be
     *     {@code null}
     * @param rejectImage the reject record as exactly {@value #REJECT_RECORD_LENGTH} bytes, held
     *     verbatim and never parsed; must not be {@code null}
     * @param accountedAt the instant the record's unit of work committed; must not be {@code null}
     * @return the row to insert inside that record's own transaction, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if either identifying string is blank, if the ordinal is
     *     negative, if the business-date token is not exactly ten characters, or if the image is not
     *     exactly {@value #REJECT_RECORD_LENGTH} bytes
     */
    // Assumptions: the image arrives as BYTES because that is what the unit of work returns and what
    //     the dataset is written from, and it is stored as characters because a character column is
    //     what returns it padded to a fixed width. The conversion is confined to this factory and to
    //     rejectImageBytes below, so no caller sees the encoding.
    public static PostingRejectOutbox rejectedRecord(String runId, String feedName, long ingestSeq,
            String businessDate, byte[] rejectImage, LocalDateTime accountedAt) {

        Objects.requireNonNull(rejectImage, "rejectImage must not be null");
        if (rejectImage.length != REJECT_RECORD_LENGTH) {
            throw new IllegalArgumentException("rejectImage must be exactly " + REJECT_RECORD_LENGTH
                    + " bytes but was " + rejectImage.length);
        }
        return new PostingRejectOutbox(runId, feedName, ingestSeq, businessDate,
                new String(rejectImage, IMAGE_CHARSET), accountedAt);
    }

    /**
     * Returns this row's insert ordinal.
     *
     * @return the ordinal the database assigned, or {@code null} before this row is flushed
     */
    public Long getOutboxSeq() {
        return this.outboxSeq;
    }

    /**
     * Returns the orchestrator execution that accounted for the record.
     *
     * @return the run identifier, never {@code null} on a stored row
     */
    public String getRunId() {
        return this.runId;
    }

    /**
     * Returns the record-layout name of the feed the ordinal belongs to.
     *
     * @return the feed name, never {@code null} on a stored row
     */
    public String getFeedName() {
        return this.feedName;
    }

    /**
     * Returns the feed ordinal this row accounts for.
     *
     * @return the ordinal, which is also the dataset's ordering key
     */
    public long getIngestSeq() {
        return this.ingestSeq;
    }

    /**
     * Returns the injected business-date token of the accounting run.
     *
     * @return the ten-character token exactly as the run received it, never {@code null} on a stored
     *     row
     */
    public String getBusinessDate() {
        return this.businessDate;
    }

    /**
     * Reports whether this row accounts for a rejected record rather than a posted one.
     *
     * @return {@code true} when this row carries a reject image, {@code false} when the record posted
     */
    public boolean isRejected() {
        return this.rejectRecord != null;
    }

    /**
     * Returns the reject image as the exact bytes the dataset carries.
     *
     * <p>Assumptions: the width is re-checked on the way out as well as on the way in. The stored
     * column pads and so cannot return a short value, but a row loaded from a database whose column
     * had been altered would, and a dataset assembled from records of the wrong width is a file that
     * still opens and still reads -- the failure this check exists to make loud.</p>
     *
     * @return exactly {@value #REJECT_RECORD_LENGTH} bytes, never {@code null}
     * @throws IllegalStateException if this row accounts for a posted record, or if the stored image
     *     is not exactly {@value #REJECT_RECORD_LENGTH} characters
     */
    public byte[] rejectImageBytes() {
        if (this.rejectRecord == null) {
            throw new IllegalStateException("row " + this.outboxSeq + " accounts for a posted record"
                    + " and carries no reject image");
        }
        byte[] image = this.rejectRecord.getBytes(IMAGE_CHARSET);
        if (image.length != REJECT_RECORD_LENGTH) {
            throw new IllegalStateException("stored reject image of row " + this.outboxSeq + " is "
                    + image.length + " bytes rather than " + REJECT_RECORD_LENGTH);
        }
        return image;
    }

    /**
     * Returns the instant the object holding this image became durable.
     *
     * @return when the image was staged, or {@code null} while it has not been staged
     */
    public LocalDateTime getStagedAt() {
        return this.stagedAt;
    }

    /**
     * Returns the object key of the dataset generation this image reached.
     *
     * @return the object key, or {@code null} while the image has reached no object
     */
    public String getStagedObjectKey() {
        return this.stagedObjectKey;
    }

    /**
     * Compares two rows on the ordinal the database assigned.
     *
     * @param other the instance to compare against; may be {@code null}
     * @return {@code true} only when both carry the same assigned ordinal
     */
    // Assumptions: two unflushed rows never compare equal however identical their members, because
    //     the ordinal is null until the insert. Two accounted records of one run are two rows even
    //     when their images are identical -- the same feed record re-presented in a later run is
    //     rejected again, and app/jcl/DALYREJS.jcl:24-26 retains five generations of exactly that --
    //     so identity has to come from the ordinal rather than from the members.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PostingRejectOutbox that)) {
            return false;
        }
        if (this.outboxSeq == null || that.getOutboxSeq() == null) {
            return false;
        }
        return this.outboxSeq.equals(that.getOutboxSeq());
    }

    /**
     * Answers one constant for every row of this type, so the value cannot move on insert.
     *
     * @return the hash of this class, the same value for every instance whatever its ordinal
     */
    // Assumptions: a hash derived from the ordinal would take one value before the insert and another
    //     after it, and a hashed collection reads the bucket once at insertion. An instance added
    //     while its ordinal was absent would then be unreachable from the bucket the assigned value
    //     selects. A constant makes every instance select one bucket for its whole lifetime, which is
    //     the same choice TransactionReject and DailyTransaction make for the same reason.
    @Override
    public int hashCode() {
        return PostingRejectOutbox.class.hashCode();
    }

    /**
     * Renders the run, the ordinal and the staging state for operational diagnosis.
     *
     * @return a String naming the run, the feed, the ordinal, whether the record was rejected and
     *     whether its image has been staged, and deliberately not the image itself
     */
    // Trade-offs: the image is omitted entirely rather than abbreviated. It carries a primary account
    //     number at zero-based offset 262 per app/cpy/CVTRA06Y.cpy:15 and rendered output reaches log
    //     lines, so including it would put an unmasked card number into a log. What is given up is
    //     reading the rejected bytes from a log; they are in this row and in the staged dataset,
    //     which are the two places an operator is meant to read them from.
    // Trade-offs: this is a diagnostic and emphatically not the dataset emitter. The bytes the
    //     committed expectations compare come from rejectImageBytes above, so that a log line
    //     improved later cannot change what the dataset holds.
    @Override
    public String toString() {
        return "PostingRejectOutbox{"
                + "outboxSeq=" + this.outboxSeq
                + ", runId='" + this.runId + '\''
                + ", feedName='" + this.feedName + '\''
                + ", ingestSeq=" + this.ingestSeq
                + ", rejected=" + isRejected()
                + ", stagedAt=" + this.stagedAt
                + '}';
    }

    /**
     * Requires one identifying string to be present and to carry a non-blank value.
     *
     * @param value the string to check; must not be {@code null} or blank
     * @param name the member name to report the failure against; must not be {@code null}
     * @return the same value, unchanged
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    // Assumptions: blankness is refused as well as absence, because a blank run identifier passes a
    //     null check and then detaches the row from the run whose redrive has to find it. The owning
    //     migration declares the same two checks, so the refusal holds whether a row arrives through
    //     this type or through a restore.
    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Requires the business-date token to be present and exactly ten characters wide.
     *
     * @param token the injected business-date token; must not be {@code null}
     * @return the same token, unchanged
     * @throws NullPointerException if {@code token} is {@code null}
     * @throws IllegalArgumentException if {@code token} is not exactly ten characters
     */
    // Assumptions: the WIDTH is checked and the layout is not, which is the check BusinessDate itself
    //     performs and for the reason that type records: the reference injects the compact form at
    //     app/jcl/INTCALC.jcl:22 and the migrated chain injects the separated ISO form, so a layout
    //     check here would refuse a token the reference supplies.
    private static String requireBusinessDate(String token) {
        Objects.requireNonNull(token, "businessDate must not be null");
        if (token.length() != BUSINESS_DATE_TOKEN_LENGTH) {
            throw new IllegalArgumentException("businessDate must be exactly "
                    + BUSINESS_DATE_TOKEN_LENGTH + " characters but was " + token.length());
        }
        return token;
    }
}
