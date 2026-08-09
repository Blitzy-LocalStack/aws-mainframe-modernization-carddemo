package com.carddemo.reporting.sink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * Writes one fixed-width artifact to object storage in bounded parts, visible only once complete.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: the two statement artifacts and the transaction report are byte-for-byte parity artifacts
 * compared against golden masters, and each is produced one record at a time by a generator that holds no
 * table of records. Something has to turn that stream of records into one stored object, and it has to do
 * so without holding the whole object in memory and without ever publishing a partial one.</p>
 *
 * <p>Assumptions: BOUNDED means the buffer is fixed. Records accumulate into a buffer of
 * {@value #PART_SIZE_BYTES} bytes and each full buffer is uploaded as one part, so the high-water mark of
 * this writer is one part regardless of how many statements a run produces. A single-object put would have
 * been simpler and is rejected on exactly that ground: it requires the whole artifact in memory or on
 * local disk first, and a run's size is a function of the portfolio rather than of anything this task
 * controls.</p>
 *
 * <p>Assumptions: ATOMIC means the object appears whole or not at all. A multipart upload publishes
 * nothing at the destination key until the completion call, so a run that fails part way leaves the
 * PREVIOUS artifact in place rather than a truncated new one. That property is what makes the
 * previous-run deletion the reference performs at L66 of {@code app/jcl/CREASTMT.JCL} unnecessary here and
 * unsafe if it were reproduced literally: deleting first and failing during the write would leave a reader
 * with no artifact at all, where overwriting on completion leaves it with the last good one. The
 * difference on the failure path is registered as divergence D-ARTIFACT-REPLACE-ON-COMPLETION in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which also records that the SUCCESS path is
 * indistinguishable from the reference's, because a key write is a replacement.</p>
 *
 * <p>Alternatives Considered: appending to the object per record, which no object store supports without
 * rewriting the object. Alternatives Considered: writing each statement as its own object, which would
 * make the run's output a prefix rather than a file and would not match the reference's two output
 * definitions -- {@code FD-STMTFILE-REC PIC X(80)} at L45 and {@code FD-HTMLFILE-REC PIC X(100)} at L47 of
 * {@code app/cbl/CBSTM03A.CBL} are two datasets holding every statement of the run.</p>
 *
 * <p>Trade-offs: a multipart upload that is never completed and never aborted leaves storage charged for
 * its parts. {@link #close()} aborts a still-open upload on the failure path for that reason, and the
 * dataset bucket's lifecycle configuration expires incomplete uploads as the backstop that covers a
 * process killed outright.</p>
 *
 * <p>Assumptions: this writer is NOT thread-safe and does not need to be. One artifact is produced by one
 * task on one thread, which is the same discipline the reference's single sequential pass has.</p>
 *
 * <p>Assumptions: every storage call is guarded on {@code SdkException} and nothing wider. That one type
 * is the root of both halves of what the client can raise -- a service refusal and a client-side transport
 * or configuration failure -- and both are storage failures this writer is responsible for converting into
 * a checked {@link IOException} the calling task can attribute. Refactoring Rationale: the first revision
 * caught the service refusal type alongside {@code RuntimeException}, which does not compile, because the
 * two are related by subclassing; widening the surviving alternative to {@code RuntimeException} would
 * have compiled but would also have swallowed a programming error -- a null argument, an illegal state --
 * and reported it as "the artifact could not be published", which is the wrong diagnosis and hides the
 * defect. Narrowing to the SDK's own root keeps a programming error propagating as itself.</p>
 */
public final class S3ArtifactWriter implements AutoCloseable {

    /**
     * Size of one upload part, being 5 MiB.
     *
     * <p>Assumptions: 5 MiB is the smallest part size the object store admits for any part other than the
     * last, so it is the smallest buffer that keeps a long artifact uploadable. A larger buffer would
     * raise this writer's memory high-water mark for no benefit at the record rates a nightly statement
     * run produces; a smaller one would be refused.</p>
     */
    public static final int PART_SIZE_BYTES = 5 * 1024 * 1024;

    /**
     * The line terminator appended after every record.
     *
     * <p>Assumptions: a single line feed, because the golden masters are text files with one record per
     * line and the comparator normalises nothing about line endings. A carriage-return pair would make
     * every record two bytes wider than its declared width and every byte comparison fail from the first
     * record.</p>
     */
    private static final byte RECORD_TERMINATOR = (byte) '\n';

    /** The destination bucket. */
    private final S3Client s3;

    /** The bucket every part and the completed object are written to. */
    private final String bucket;

    /** The object key the completed artifact appears under. */
    private final String key;

    /** The part buffer, flushed and reused each time it reaches {@link #PART_SIZE_BYTES}. */
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(PART_SIZE_BYTES);

    /** The parts already uploaded, in order, as the completion call requires them. */
    private final List<CompletedPart> uploaded = new ArrayList<>();

    /** The upload identifier, {@code null} until the first part is buffered past the threshold. */
    private String uploadId;

    /** Whether {@link #close()} has already run, so that a second call is a no-op. */
    private boolean closed;

    /**
     * Creates a writer for one artifact.
     *
     * @param s3 the object-store client; must not be {@code null}
     * @param bucket the destination bucket; must not be {@code null} or blank
     * @param key the destination object key; must not be {@code null} or blank
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code bucket} or {@code key} is blank, because an object
     *     written to a blank key is unreachable and the failure would surface only when a reader looked
     *     for it
     */
    public S3ArtifactWriter(S3Client s3, String bucket, String key) {
        this.s3 = Objects.requireNonNull(s3, "s3 must not be null");
        this.bucket = requireText(bucket, "bucket");
        this.key = requireText(key, "key");
    }

    /**
     * Appends one already-encoded record, uploading a part whenever the buffer fills.
     *
     * <p>Assumptions: the record's bytes are written verbatim and a terminator is appended. The record
     * arrives at its declared width from the mapper layer that owns the widths, and this writer neither
     * pads nor trims -- doing either here would put a width decision in two places and make neither
     * authoritative.</p>
     *
     * @param record the encoded record; must not be {@code null}
     * @throws IOException if a part cannot be uploaded, which the generator treats as the reference's own
     *     write failure and converts into an abend
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if this writer has already been closed
     */
    public void write(byte[] record) throws IOException {
        Objects.requireNonNull(record, "record must not be null");
        if (closed) {
            throw new IllegalStateException(
                    "this artifact writer is closed, so the artifact it wrote is already complete and"
                            + " cannot receive a further record");
        }
        buffer.write(record, 0, record.length);
        buffer.write(RECORD_TERMINATOR);
        if (buffer.size() >= PART_SIZE_BYTES) {
            uploadPart();
        }
    }

    /**
     * Completes the artifact, publishing it at its key, or aborts an upload that cannot be completed.
     *
     * <p>Assumptions: an artifact that never grew past one part is published with a single put rather
     * than through a multipart completion. A multipart upload of one small part is admitted by the store
     * but costs three round trips where one suffices, and the whole-object put is also the only shape
     * available when a run produced no record at all -- a multipart upload with zero parts cannot be
     * completed, and the correct outcome there is an EMPTY artifact rather than a failure, because a run
     * over an empty portfolio produces an empty file in the reference too.</p>
     *
     * <p>Assumptions: an in-flight multipart upload is ABORTED when completion fails, and the abort's own
     * failure is suppressed onto the original. A failure to abort leaves storage charged for orphaned
     * parts, which the bucket lifecycle expires, whereas losing the original failure would leave the
     * operator with the wrong diagnosis.</p>
     *
     * @throws IOException if the artifact cannot be published
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (uploadId == null) {
                putWholeObject();
                return;
            }
            if (buffer.size() > 0) {
                uploadPart();
            }
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(uploaded).build())
                    .build());
        } catch (SdkException failure) {
            abortQuietly(failure);
            throw new IOException("the artifact could not be published to object storage", failure);
        }
    }

    /**
     * Publishes an artifact small enough to have needed no multipart upload.
     *
     * @throws IOException if the put is refused
     */
    private void putWholeObject() throws IOException {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(buffer.toByteArray()));
        } catch (SdkException failure) {
            throw new IOException("the artifact could not be published to object storage", failure);
        } finally {
            buffer.reset();
        }
    }

    /**
     * Uploads the buffered bytes as the next part, starting the upload on the first call.
     *
     * <p>Assumptions: the upload is created lazily, on the first part rather than in the constructor, so
     * a run that produces an artifact smaller than one part never creates a multipart upload at all and
     * therefore never leaves one to abort.</p>
     *
     * @throws IOException if the part cannot be uploaded
     */
    private void uploadPart() throws IOException {
        try {
            if (uploadId == null) {
                CreateMultipartUploadResponse created =
                        s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .build());
                uploadId = created.uploadId();
            }
            int partNumber = uploaded.size() + 1;
            byte[] part = buffer.toByteArray();
            buffer.reset();
            String etag = s3.uploadPart(UploadPartRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .build(),
                    RequestBody.fromBytes(part)).eTag();
            uploaded.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
        } catch (SdkException failure) {
            throw new IOException("an artifact part could not be uploaded to object storage", failure);
        }
    }

    /**
     * Aborts an in-flight multipart upload, attaching any abort failure to the original.
     *
     * @param original the failure that made completion impossible; must not be {@code null}
     */
    private void abortQuietly(Throwable original) {
        if (uploadId == null) {
            return;
        }
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (SdkException abortFailure) {
            // WHY : Trade-offs: the abort's failure is SUPPRESSED onto the original rather than replacing
            //       it or being logged separately. The original names why the artifact was not published,
            //       which is what an operator acts on; the abort failure only means some parts remain
            //       charged, which the bucket's incomplete-upload lifecycle rule expires without
            //       intervention. Suppression keeps both without letting the lesser one obscure the
            //       greater.
            original.addSuppressed(abortFailure);
        }
    }

    /**
     * Refuses a blank configuration value, naming which one it was.
     *
     * @param value the value to check; must not be {@code null}
     * @param what the name to quote in a refusal; must not be {@code null}
     * @return the value, so a call site can check and assign in one expression
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    private static String requireText(String value, String what) {
        Objects.requireNonNull(value, what + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return value;
    }
}
