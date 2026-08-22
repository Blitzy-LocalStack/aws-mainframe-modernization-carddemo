package com.carddemo.reporting.sink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Refactoring Rationale: publication is {@link #complete()} and {@link #close()} ABORTS, where
 * {@code close()} used to publish. The old shape defeated the atomicity claim the paragraph above makes,
 * and it defeated it on the one path the claim exists for. Every caller holds this writer in a
 * try-with-resources, so an exception raised while records were being written unwound through
 * {@code close()} -- which completed the multipart upload, or put the buffered bytes when the artifact was
 * still under one part, and thereby REPLACED the last good object with the partial output of a run that
 * had just failed. A run that failed before its first record replaced it with an empty object. Splitting
 * the two means an abort is what an unwind reaches: an aborted multipart upload publishes nothing and a
 * discarded buffer is never put, so a failed generation now leaves the previous generation readable
 * exactly as the paragraph above says it does.</p>
 *
 * <p>Alternatives Considered: keeping publication in {@code close()} and having a caller signal failure by
 * some other means -- a flag set before the close, or a subclass hook. Rejected because it inverts the
 * default: a caller who forgets the flag publishes partial output, which is the failure being closed here,
 * and every future caller would have to know to opt out of it. With completion explicit the default is the
 * safe one -- a caller that never reaches its completion call publishes NOTHING, which the absent version
 * identifier and the unchanged destination object both report.</p>
 *
 * <p>Trade-offs: a caller now makes two calls where it made one, and forgetting the second produces no
 * artifact rather than a wrong one. That is the failure this shape prefers, and the cost is real: a run
 * whose generation succeeded but whose completion call was never written would look like a run that
 * produced nothing at all. It is not left to discipline -- {@code S3StatementSink} and {@code S3ReportSink}
 * are the only two callers, each exposes one completion method covering every writer it holds, and the
 * task that drives each of them completes inside the try block whose resource clause aborts.</p>
 *
 * <p>Alternatives Considered: appending to the object per record, which no object store supports without
 * rewriting the object. Alternatives Considered: writing each statement as its own object, which would
 * make the run's output a prefix rather than a file and would not match the reference's two output
 * definitions -- {@code FD-STMTFILE-REC PIC X(80)} at L45 and {@code FD-HTMLFILE-REC PIC X(100)} at L47 of
 * {@code app/cbl/CBSTM03A.CBL} are two datasets holding every statement of the run.</p>
 *
 * <p>Trade-offs: a multipart upload that is never completed and never aborted leaves storage charged for
 * its parts. {@link #close()} aborts a still-open upload whenever completion was not declared, for that
 * reason, and the dataset bucket's lifecycle configuration expires incomplete uploads as the backstop that
 * covers a process killed outright.</p>
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

    // WHY : Assumptions: this class logs on ONE path only -- an abort that itself failed -- because that
    //       is the only outcome it knows about that no caller can be told. Every other outcome is
    //       reported to the caller as a return or as a thrown failure, and the task above it journals
    //       the run with the business date and the counts this class does not have.
    /** Journal for an abort that could not be performed, whose consequence is a storage charge. */
    private static final Logger LOG = LoggerFactory.getLogger(S3ArtifactWriter.class);

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

    // WHY : Assumptions: completion is recorded as a FLAG rather than inferred from the version
    //       identifier below, because an unversioned bucket returns no version from a successful
    //       publication -- so "the version is null" and "nothing was published" are the same state on a
    //       local or test deployment and cannot be told apart. A flag set only by the completion path
    //       distinguishes them on every deployment.
    /** Whether {@link #complete()} published the artifact, which is what stops {@link #close()} aborting. */
    private boolean completed;

    // Assumptions: the version is captured from whichever of the two publication calls completed the
    //     artifact, because BOTH of them return one and a caller cannot know which path a run took.
    //     Capturing it here rather than reading it back with a head call is what makes the identifier
    //     the one the write produced rather than whatever is current by the time a reader asks.
    /**
     * The object version the completed artifact was stored as, {@code null} until it is published and
     * {@code null} afterwards when the bucket carries no versioning.
     */
    private String publishedVersionId;

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
     * @throws IllegalStateException if this writer has already been completed or closed, either of which
     *     means the record could not reach the artifact and would be lost silently
     */
    public void write(byte[] record) throws IOException {
        Objects.requireNonNull(record, "record must not be null");
        if (completed) {
            throw new IllegalStateException(
                    "this artifact writer is complete, so the artifact it wrote is published and cannot"
                            + " receive a further record");
        }
        if (closed) {
            throw new IllegalStateException(
                    "this artifact writer is closed, so the artifact it was writing was discarded and"
                            + " cannot receive a further record");
        }
        buffer.write(record, 0, record.length);
        buffer.write(RECORD_TERMINATOR);
        if (buffer.size() >= PART_SIZE_BYTES) {
            uploadPart();
        }
    }

    /**
     * Publishes the artifact at its key, making the records written so far visible as one object.
     *
     * <p>Purpose: this is the ONE call that makes an artifact readable, and a caller reaches it only on
     * the path where the whole artifact was produced. Everything about the failure behaviour recorded on
     * the class follows from that: {@link #close()} cannot publish, so an unwind cannot.</p>
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
     * operator with the wrong diagnosis. The subsequent {@link #close()} then has nothing left to abort,
     * which is why a failed completion does not double-abort.</p>
     *
     * <p>Assumptions: a second call is REFUSED rather than treated as a no-op. Two completions of one
     * writer means a caller has lost track of which artifact it is publishing, and answering the second
     * call quietly would let that go unnoticed until a reader found the wrong bytes at the key.</p>
     *
     * @throws IOException if the artifact cannot be published, in which case nothing is stored at the key
     *     and the previous object there is unchanged
     * @throws IllegalStateException if this writer has already been completed or already been closed
     */
    public void complete() throws IOException {
        if (completed) {
            throw new IllegalStateException(
                    "this artifact writer has already published its artifact; completing it twice would"
                            + " leave a caller unable to say which artifact is at the key");
        }
        if (closed) {
            throw new IllegalStateException(
                    "this artifact writer was closed without being completed, so the records it held were"
                            + " discarded and there is nothing left to publish");
        }
        try {
            if (uploadId == null) {
                putWholeObject();
                completed = true;
                return;
            }
            if (buffer.size() > 0) {
                uploadPart();
            }
            publishedVersionId = s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(uploaded).build())
                    .build())
                    .versionId();
            completed = true;
        } catch (SdkException failure) {
            IOException refusal =
                    new IOException("the artifact could not be published to object storage", failure);
            abortQuietly(refusal);
            uploadId = null;
            throw refusal;
        }
    }

    /**
     * Discards an artifact that was never completed, publishing nothing.
     *
     * <p>Purpose: this is the resource-clause half of the completion split recorded on the class. It runs
     * on every path, including the unwind of a failed generation, and on that path it must leave the
     * destination key holding whatever it held before -- so it aborts and never publishes.</p>
     *
     * <p>Assumptions: this method does NOT throw. An abort failure means some uploaded parts remain
     * charged until the bucket's incomplete-upload lifecycle rule expires them, which needs no operator
     * action; raising it here would attach a storage-housekeeping failure to whatever exception was
     * already unwinding, or -- worse -- would raise one where the caller had merely forgotten to
     * complete, giving the operator the wrong diagnosis. Alternatives Considered: declaring
     * {@code IOException} as {@link AutoCloseable} permits. Rejected on exactly that ground: the abort
     * has no failure a caller can act on, and try-with-resources would suppress it onto an unrelated
     * primary exception where it would be read as part of the original fault.</p>
     *
     * <p>Assumptions: the buffer is reset even when no multipart upload was started, so a writer that
     * held its whole artifact under one part releases those bytes rather than holding them until it is
     * collected.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (completed) {
            return;
        }
        // WHY : Assumptions: the buffer is cleared BEFORE the abort call rather than after, so the bytes
        //       are released even if the abort throws something the catch below does not name. There is
        //       nothing to publish them to at this point either way -- the abort is the decision, and
        //       holding the bytes for it to fail on would keep a part-sized buffer alive for nothing.
        buffer.reset();
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
            // WHY : Trade-offs: the failure is LOGGED at warning rather than raised, for the reason the
            //       contract above gives. It names the key so an operator can reconcile a charge against
            //       an artifact, and it is the only place this class logs -- the publication path is
            //       silent because its caller journals the outcome with the run's own context.
            LOG.warn("event=reporting.artifact.abort-failed bucket={} key={}", bucket, key,
                    abortFailure);
        } finally {
            uploadId = null;
        }
    }

    /**
     * Publishes an artifact small enough to have needed no multipart upload.
     *
     * @throws IOException if the put is refused
     */
    private void putWholeObject() throws IOException {
        try {
            publishedVersionId = s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(buffer.toByteArray()))
                    .versionId();
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
     * Reports the object version the completed artifact was stored as.
     *
     * <p>Assumptions: the value is captured from the publication call itself rather than read back
     * afterwards, so it names the version THIS writer produced. A later write to the same key produces
     * another version and does not change what this returns, which is the property a caller recording a
     * locator needs -- a locator that named "whatever is current" would stop identifying the artifact
     * the run wrote as soon as the next run wrote one.</p>
     *
     * <p>Assumptions: {@code null} means one of two things and both are legitimate: the writer has not
     * been completed, so nothing has been published; or the destination bucket carries no versioning,
     * in which case the store returns no version and the key alone identifies the object. A caller
     * therefore treats the version as an OPTIONAL refinement of the locator rather than as a required
     * part of it. The production bucket is versioned -- that is the generation-retention analogue the
     * plan fixes -- so the absence is a local-and-test condition.</p>
     *
     * @return the stored object's version identifier, or {@code null} when there is none
     */
    public String publishedVersionId() {
        return publishedVersionId;
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
