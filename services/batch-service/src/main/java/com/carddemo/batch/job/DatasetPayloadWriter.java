package com.carddemo.batch.job;

import com.carddemo.batch.dto.DatasetGeneration;
import java.io.ByteArrayOutputStream;
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
 * <p>WHY the payload is assembled in memory and put as one object rather than streamed record by
 * record: the baseline step it replaces is a single {@code IDCAMS REPRO} of one dataset into
 * another, which either produces the whole output dataset or produces none of it. A multipart or
 * per-record write would let a failure leave a generation that exists and is short, and a
 * downstream step reading {@code (0)} cannot tell a short generation from a complete one.
 * Trade-offs: a nightly transaction master is held in memory for the duration of the put, which
 * bounds the dataset size by the task's memory rather than by the object store's limits; that is
 * accepted because the alternative is a silently truncated generation, and because the task's
 * memory is a provisioning parameter while a corrupt backup is a data-loss event.</p>
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
     * @param accumulator the payload being assembled; must not be {@code null}
     * @param record the record bytes to append; must not be {@code null}
     */
    static void append(ByteArrayOutputStream accumulator, byte[] record) {
        Objects.requireNonNull(accumulator, "accumulator must not be null");
        Objects.requireNonNull(record, "record must not be null");
        accumulator.write(record, 0, record.length);
    }
}
