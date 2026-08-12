package com.carddemo.batch.job;

import com.carddemo.batch.dto.DatasetGeneration;
import com.carddemo.common.observability.ThrowableDigest;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Writes an assembled fixed-width payload to the object key a dataset generation names.
 *
 * <p>This is the "writer" half of what the sibling service charter assigns to this package: it
 * holds no rule and makes no decision about WHICH generation is written -- that belongs to
 * {@code DatasetGenerationService}, which decides the coordinate and, in the charter's words, "does
 * not spell one". This class only puts bytes at a coordinate it is handed.</p>
 *
 * <p>WHY the payload is put as ONE object rather than streamed record by record to the store: the
 * baseline step it replaces is a single {@code IDCAMS REPRO} of one dataset into another, which
 * either produces the whole output dataset or produces none of it. A multipart or per-record write
 * would let a failure leave a generation that exists and is short, and a downstream step reading
 * {@code (0)} cannot tell a short generation from a complete one.</p>
 *
 * <p>Refactoring Rationale: where the payload is assembled is a separate question from how it is
 * put, and the two used to be conflated here. {@link #append} now takes any {@link OutputStream},
 * so a caller assembles into a file on the task's ephemeral disk and puts from that file; the
 * all-or-nothing property above is unaffected, because it is a property of the single put. What
 * changes is that peak memory is bounded by one record rather than by the whole dataset.
 * Trade-offs: the accepted cost is a temporary file the size of the artefact, on a volume that is
 * finite, so every caller removes its own file on every path -- including a failed put, because a
 * failed step is retried and a sequence of retries must not fill the volume.</p>
 */
final class DatasetPayloadWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetPayloadWriter.class);

    /**
     * Object key suffix appended to a generation prefix.
     *
     * <p>WHY a fixed member name under the generation prefix rather than the prefix itself: the
     * prefix ends at the {@code gen=NNNN/} segment and names a folder-like coordinate, so a
     * generation may hold exactly one member and still be addressed as a prefix by the retention
     * rules that scratch it.</p>
     */
    private static final String MEMBER_NAME = "records.dat";

    /**
     * Content type recorded on a written generation.
     *
     * <p>WHY it is declared binary rather than text: the payload is fixed-width records that carry
     * zoned-decimal sign overpunches and, in the export family, packed-decimal fields. Labelling it
     * text invites a client or a console preview to transcode it, which corrupts exactly those
     * bytes.</p>
     */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /**
     * Filename prefix shared by every staging file this package creates.
     *
     * <p>Assumptions: the prefix names the project so a file surviving a hard task kill is attributable
     * to it rather than to whatever else shares the temporary directory. The per-job stem follows it, and
     * the platform supplies the unique remainder.</p>
     */
    private static final String STAGING_PREFIX = "carddemo-";

    /** Filename suffix marking a staging file as an intermediate rather than a published artefact. */
    private static final String STAGING_SUFFIX = ".staging";

    /**
     * Prevents instantiation of this static holder.
     *
     * @throws AssertionError always, because the class carries no state to construct
     */
    private DatasetPayloadWriter() {
        throw new AssertionError("DatasetPayloadWriter is a static holder and is not instantiable");
    }

    /**
     * Puts one assembled payload at the coordinate a generation names.
     *
     * @param objectStore the object store client; must not be {@code null}
     * @param bucket the dataset bucket name; must not be {@code null} or blank
     * @param generation the allocated generation whose prefix the payload is written under; must
     *     not be {@code null}
     * @param payload the assembled fixed-width bytes; must not be {@code null}
     * @return the object key written
     * @throws NullPointerException when any argument is {@code null}
     * @throws IllegalArgumentException when {@code bucket} is blank
     */
    static String write(
            S3Client objectStore, String bucket, DatasetGeneration generation, byte[] payload) {

        Objects.requireNonNull(objectStore, "objectStore must not be null");
        Objects.requireNonNull(bucket, "bucket must not be null");
        Objects.requireNonNull(generation, "generation must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }

        String key = generation.keyPrefix() + MEMBER_NAME;
        objectStore.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(CONTENT_TYPE)
                        .build(),
                RequestBody.fromBytes(payload));

        LOG.info("event=batch.dataset.written key={} bytes={}", key, payload.length);
        return key;
    }

    /**
     * Appends one fixed-width record to an accumulating payload.
     *
     * <p>WHY the records are concatenated with no separator: the datasets these payloads replace are
     * {@code RECFM=FB} with a fixed logical record length, so the record boundary is the length
     * itself and a delimiter would shift every field after the first record by one byte.</p>
     *
     * <p>Refactoring Rationale: the parameter is an {@link OutputStream} where it was a
     * {@code ByteArrayOutputStream}, so a caller may accumulate into a file on the task's ephemeral
     * disk instead of into the heap. The narrower type forced every caller's peak memory to be the
     * size of its whole artefact, which for the export and import datasets is the size of a database
     * export; widening it costs nothing here because this method writes and never reads back.
     * Trade-offs: a stream write can fail where a byte-array write could not, so the checked failure
     * is wrapped in an {@link UncheckedIOException} rather than added to this signature -- the
     * callers are record loops whose enclosing job already brackets every failure path with the
     * reference's abend banner, and a checked exception here would put a try-catch inside each loop
     * for a failure none of them can handle locally.</p>
     *
     * <p>Alternatives Considered: a second overload declaring {@code throws IOException} for the
     * file-backed caller, so a staging failure would be checked at that one call site. Rejected, and
     * withdrawn after being written: two methods of the same erasure cannot coexist, and the checked
     * form would put a try-catch inside every record loop for a failure the loop cannot handle --
     * {@code ExportJob} and {@code ImportJob} both stage to the task's ephemeral disk and both bracket
     * the whole body with the reference's abend banner, which is where a staging failure belongs.</p>
     *
     * @param accumulator the payload being assembled, whether in the heap or on the task's ephemeral
     *     disk; must not be {@code null}
     * @param record the record bytes to append; must not be {@code null}
     * @throws NullPointerException when either argument is {@code null}
     * @throws UncheckedIOException if the accumulator cannot accept the record, which for a
     *     file-backed accumulator means the volume is full or the file has been removed
     */
    static void append(OutputStream accumulator, byte[] record) {
        Objects.requireNonNull(accumulator, "accumulator must not be null");
        Objects.requireNonNull(record, "record must not be null");
        try {
            accumulator.write(record, 0, record.length);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(
                    "could not append a " + record.length + "-byte record to the dataset payload",
                    unwritable);
        }
    }


    /**
     * Creates a uniquely named staging file a payload can be assembled in outside the heap.
     *
     * <p>Refactoring Rationale: staging lives here, beside the two append methods, rather than being
     * declared privately in each of the two jobs that needs it. The export job stages one dataset and the
     * import job stages seven artefacts, so the same three operations -- create, measure, discard -- were
     * about to appear in both, and the deletion rule in particular is the one that must not diverge: a
     * staged file holds customer and card data, so a job that forgot to delete one leaves it on a reused
     * task's ephemeral volume. Alternatives Considered: a new class in this package for the three
     * operations. Rejected because this class is already the package's payload-writing utility, and a
     * fourth single-purpose static holder beside it would add a roster entry without adding a
     * boundary.</p>
     *
     * @param stem the filename stem, which should name the job so a surviving file is attributable; must
     *     not be {@code null} or blank
     * @return the created staging file; never {@code null}
     * @throws NullPointerException when {@code stem} is {@code null}
     * @throws IllegalArgumentException when {@code stem} is blank
     * @throws UncheckedIOException if the temporary file cannot be created
     */
    static Path stage(String stem) {
        Objects.requireNonNull(stem, "stem must not be null");
        if (stem.isBlank()) {
            throw new IllegalArgumentException("stem must not be blank");
        }
        try {
            // WHY : Assumptions: the unique remainder of the name is supplied by the platform rather
            //       than by a counter of ours, because two executions of one job can share a reused task
            //       container and must not contend for one path.
            return Files.createTempFile(STAGING_PREFIX + stem + "-", STAGING_SUFFIX);
        } catch (IOException uncreatable) {
            throw new UncheckedIOException(
                    "a staging file for " + stem + " could not be created, so nothing was assembled"
                            + " or published", uncreatable);
        }
    }

    /**
     * Measures a staged payload, so its length can be checked against the records counted.
     *
     * @param staged the staging file to measure; must not be {@code null}
     * @return the staged length in bytes
     * @throws NullPointerException when {@code staged} is {@code null}
     * @throws UncheckedIOException if the file cannot be interrogated
     */
    static long stagedLength(Path staged) {
        Objects.requireNonNull(staged, "staged must not be null");
        try {
            return Files.size(staged);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the staging file at " + staged + " could not be measured,"
                    + " so its completeness could not be verified and nothing was published",
                    unreadable);
        }
    }

    /**
     * Removes a staging file, reporting rather than raising when it cannot be removed.
     *
     * <p>Trade-offs: a failure to delete is logged and swallowed. By the time a caller discards a staged
     * file the run has either published or failed, and replacing that outcome with a housekeeping failure
     * would tell the operator the wrong thing about the artefact. The cost is that a file the process
     * could not remove stays on the task's ephemeral volume until the task is replaced, which is why the
     * line is a warning and names the path.</p>
     *
     * @param staged the staging file to remove; must not be {@code null}
     */
    static void discard(Path staged) {
        Objects.requireNonNull(staged, "staged must not be null");
        try {
            Files.deleteIfExists(staged);
        } catch (IOException undeletable) {
            LOG.warn("event=batch.dataset.staging-not-deleted path={} failure={}",
                    staged, ThrowableDigest.of(undeletable));
        }
    }
}
