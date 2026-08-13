package com.carddemo.reporting.service;

import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.time.TimestampFormatter;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Reads the artifacts a reporting run produced, for metadata and for delivery.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>⚠️ Refactoring Rationale: a review found three defects that all reduce to the same absence -- nothing
 * in the request-serving path could READ an artifact. The statement response named per-card object keys that
 * no writer creates, because {@code S3StatementSink} writes exactly two run-wide objects and the response
 * composed a key from a per-card token instead. It reported a generation timestamp of twenty-six spaces,
 * because nothing knew when -- or whether -- an artifact had been produced. And it published those keys as
 * raw {@code s3://} locations, which a browser cannot open at all: the dataset bucket in
 * {@code infra/modules/s3-datasets} denies access except through the VPC endpoint, so the one audience for
 * the field could not use it. This class is the read side the three defects needed and none of them had.
 *
 * <p>Assumptions: delivery is a STREAM through this service rather than a redirect to the store, and that is
 * the decision the bucket policy dictates rather than a preference. A pre-signed URL would be a public
 * HTTPS address to an object the bucket refuses outside the VPC endpoint, so it would resolve to a refusal
 * however correctly it were signed; a distribution in front of the bucket would need its own origin
 * access, its own cache semantics for an object that changes nightly, and a second authorization surface
 * beside the one the API already has. Streaming through the service reuses the token check that already
 * guards every other operation, and the object never leaves the private network on its way here.
 *
 * <p>Alternatives Considered: giving this role to {@code S3ReportSink} or {@code S3ArtifactWriter}, both of
 * which already hold an {@link S3Client}. Rejected because both are WRITE paths owned by the batch entry
 * point: they are constructed per run, they hold open multipart uploads, and a request-serving read that
 * shared their lifecycle would either keep an upload open or force the write path to become a singleton.
 * This class holds no per-run state and answers a request.
 *
 * <p>Assumptions: an absent object is an empty answer and not a failure, because a statement read is
 * legitimate before any run has produced an artifact -- the caller still receives the heading, the total and
 * the count, all of which come from the database. Reporting an absence as a failure would make a working
 * read fail for a reason the caller cannot act on.
 *
 * <p>Assumptions: no monetary value and no cardholder value passes through this class. It handles an object
 * key, a byte count and an instant, so the diagnostic below carries the key and nothing else -- and every key
 * it is given is composed by this context from a run-wide artifact name or from a report type and a date
 * range, so no key names a card or an account.
 *
 * <p>⚠️ Refactoring Rationale: the name of this class is {@code ArtifactStore} and not
 * {@code StatementArtifactStore}, which is what it was first called. Nothing about it is statement-specific
 * -- it takes a key and answers what is stored there -- and the report lifecycle needs exactly the same two
 * questions answered about the transaction-report artifact: does it exist, and when was it written. Keeping
 * the narrower name while serving both would have been a name that lies, and the alternative of a second,
 * near-identical class for reports would have been the same code twice with two chances to fix a bug in one
 * of them.
 */
@Service
public class ArtifactStore {

    /** Diagnostic channel for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(ArtifactStore.class);

    /** The bucket this context publishes its artifacts to, shared with the write path. */
    private final String outputBucket;

    /** The object-store client, contributed by {@code ObjectStoreConfig}. */
    private final S3Client s3;

    /**
     * Creates the store over the deployment's object-store client and output bucket.
     *
     * <p>Assumptions: the bucket name is read from the same property the write path reads, so a read and a
     * write of one deployment cannot address two buckets.</p>
     *
     * @param s3 the object-store client; must not be {@code null}
     * @param outputBucket the bucket this context publishes its artifacts to; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ArtifactStore(S3Client s3,
            @Value("${" + StatementService.OUTPUT_BUCKET_PROPERTY + "}") String outputBucket) {
        this.s3 = Objects.requireNonNull(s3, "s3 must not be null");
        this.outputBucket = Objects.requireNonNull(outputBucket, "outputBucket must not be null");
    }

    /**
     * Reports what is stored at one key, or nothing when no object is stored there.
     *
     * <p>Assumptions: the instant is converted to the twenty-six-character form every other timestamp in
     * these contracts uses, at UTC. The store records an instant; the contract publishes a local-time
     * string of fixed width, and UTC is the zone the whole migration stamps in, so converting here keeps
     * the one conversion in one place rather than at each caller.</p>
     *
     * <p>Assumptions: only a genuine absence answers empty. Any other store failure is allowed to
     * propagate, because a read that cannot reach the store is not the same condition as a run that has
     * not produced an artifact, and reporting the first as the second would tell a caller that a statement
     * had never been generated whenever the store was unreachable.</p>
     *
     * @param key the object key to describe; must not be {@code null}
     * @return the descriptor, or empty when no object is stored at that key
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws S3Exception if the store refuses the request for any reason other than the key's absence
     */
    public Optional<ArtifactDescriptor> describe(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            HeadObjectResponse head = this.s3.headObject(HeadObjectRequest.builder()
                    .bucket(this.outputBucket)
                    .key(key)
                    .build());
            return Optional.of(new ArtifactDescriptor(key, head.contentLength(),
                    TimestampFormatter.format(
                            LocalDateTime.ofInstant(head.lastModified(), ZoneOffset.UTC))));
        } catch (NoSuchKeyException absent) {
            // WHY : Assumptions: the absence is logged at debug and the throwable is reduced to a digest
            //       rather than passed to the logger. An absent artifact is an ordinary state of this
            //       service before the first run, so it is not a warning; and the shared observability
            //       contract forbids handing a raw throwable to a logger argument, because its message
            //       carries store detail this service does not own.
            LOG.debug("event=statement.artifact.absent key={} outcome={}", key,
                    ThrowableDigest.of(absent));
            return Optional.empty();
        }
    }

    /**
     * Opens the object at one key for streaming to a caller.
     *
     * <p>Assumptions: the stream is returned open and the CALLER closes it, which is what lets the
     * response body be written straight from the store without the whole artifact being held in memory.
     * A statement run's plain-text artifact is one record per line for every card of the run, so buffering
     * it to size it first would make the service's memory a function of the customer base.</p>
     *
     * <p>Assumptions: the stored size is taken from the SAME call that opens the stream, out of the
     * response the store returns alongside it, rather than from a separate metadata call. Two calls
     * would leave a window in which the size describes one version of the object and the stream carries
     * another -- a run that rewrote the artifact between them would produce a response whose declared
     * length disagreed with its body, which a client reads as a truncated download.</p>
     *
     * <p>Assumptions: the store's own stream type does not leave this method. It is wrapped in
     * {@link OpenArtifact} so that neither the service that resolves a selector nor the request edge
     * that writes the body takes a compile-time dependency on the object-store client -- the same
     * reason the rest of this context confines that dependency to its adapter classes.</p>
     *
     * @param key the object key to open; must not be {@code null}
     * @return the stored size and the open stream, which the caller must close
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws NoSuchElementException if no object is stored at that key, which the request edge renders as
     *     404 rather than as a failure of this service
     * @throws S3Exception if the store refuses the request for any reason other than the key's absence
     */
    public OpenArtifact open(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            ResponseInputStream<GetObjectResponse> stream =
                    this.s3.getObject(GetObjectRequest.builder()
                            .bucket(this.outputBucket)
                            .key(key)
                            .build());
            return new OpenArtifact(stream.response().contentLength(), stream);
        } catch (NoSuchKeyException absent) {
            LOG.debug("event=statement.artifact.absent key={} outcome={}", key,
                    ThrowableDigest.of(absent));
            throw new NoSuchElementException("no statement artifact is stored at the requested key");
        }
    }

    /**
     * Reads one byte range out of a stored object.
     *
     * <p>Purpose: the statement index is a sorted sequence of fixed-width records, and locating one card
     * in it means reading a handful of individual records rather than the whole object. This is the
     * primitive that makes that possible.
     *
     * <p>Assumptions: the range is expressed as a first and last byte position INCLUSIVE, which is what
     * the range header the store honours means, and the caller is given back exactly the bytes the store
     * returned rather than a buffer padded to the requested length. A range extending past the end of
     * the object is answered with what exists, so a caller that asked for one record and received fewer
     * bytes has read the tail -- which the caller checks rather than this method guessing at.
     *
     * <p>Trade-offs: a ranged read costs one request per range, so a caller performing a search issues
     * several small requests where a single whole-object read would issue one large one. That is the
     * intended trade: the index grows with the portfolio, so the whole-object read grows without bound
     * while the search grows logarithmically, and the bytes transferred stay in the tens.
     *
     * @param key the object key to read from; must not be {@code null}
     * @param firstByte the first byte position to read, counted from zero; must not be negative
     * @param lastByte the last byte position to read, inclusive; must not be before {@code firstByte}
     * @return the bytes the store returned, which may be shorter than the range when the range reaches
     *     the end of the object; never {@code null}
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws IllegalArgumentException if the range is negative or inverted
     * @throws NoSuchElementException if no object is stored at that key
     * @throws S3Exception if the store refuses the request for any reason other than the key's absence
     */
    public byte[] readRange(String key, long firstByte, long lastByte) {
        Objects.requireNonNull(key, "key must not be null");
        if (firstByte < 0 || lastByte < firstByte) {
            throw new IllegalArgumentException("a byte range must be non-negative and ascending");
        }
        try {
            return this.s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(this.outputBucket)
                    .key(key)
                    // WHY : Assumptions: the header is composed here rather than by a helper because the
                    //       store's range syntax is part of this adapter's job. The inclusive upper bound
                    //       is the store's own convention, and converting a length into it at the call
                    //       site is exactly the off-by-one this method exists to keep in one place.
                    .range("bytes=" + firstByte + "-" + lastByte)
                    .build()).asByteArray();
        } catch (NoSuchKeyException absent) {
            LOG.debug("event=statement.artifact.absent key={} outcome={}", key,
                    ThrowableDigest.of(absent));
            throw new NoSuchElementException("no statement artifact is stored at the requested key");
        }
    }

    /**
     * One opened artifact: how many bytes it holds, and the stream those bytes arrive on.
     *
     * <p>Assumptions: the size travels WITH the stream rather than being fetched beside it, so a caller
     * cannot declare a length that belongs to a different version of the object than the one it is about
     * to write. Trade-offs: the record owns an open resource, which makes it single-use and makes
     * closing the stream the caller's obligation -- stated on the component rather than implied, because
     * a leaked connection from a pooled HTTP client is exhausted capacity rather than a lost object.</p>
     *
     * @param sizeBytes the stored size in bytes, as the store reported it on the opening call
     * @param content the open stream of the object's bytes, which the caller must close
     */
    public record OpenArtifact(long sizeBytes, InputStream content) {

        /**
         * Validates the size and the stream.
         *
         * @param sizeBytes the stored size in bytes; must not be negative
         * @param content the open stream; must not be {@code null}
         * @throws NullPointerException if {@code content} is {@code null}
         * @throws IllegalArgumentException if {@code sizeBytes} is negative, which no stored object has
         */
        public OpenArtifact {
            Objects.requireNonNull(content, "content must not be null");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }

        /**
         * Renders the opened artifact for a diagnostic.
         *
         * <p>Assumptions: the byte count is printed and the stream is named by its class alone. The
         * count is a whole run's size and discloses no cardholder value; the stream's contents are the
         * statement text itself, so nothing derived from them appears here.</p>
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "OpenArtifact[sizeBytes=" + sizeBytes
                    + ", content=" + content.getClass().getSimpleName() + ']';
        }
    }

    /**
     * What is stored at one artifact key.
     *
     * <p>Assumptions: three components and no more. The key names the object, the byte count lets a caller
     * decide whether to fetch it, and the instant is the only honest answer to "when was this produced" --
     * it is the store's own record of when the object was completed. Nothing here is derived from the
     * object's CONTENT, so describing an artifact costs one metadata call and no transfer.</p>
     *
     * @param key the object key described
     * @param sizeBytes the stored size in bytes
     * @param lastModified when the store completed the object, in the twenty-six-character form
     */
    public record ArtifactDescriptor(String key, long sizeBytes, String lastModified) {

        /**
         * Validates every component is present.
         *
         * @param key the object key described; must not be {@code null}
         * @param sizeBytes the stored size in bytes; must not be negative
         * @param lastModified the completion instant in the twenty-six-character form; must not be
         *     {@code null}
         * @throws NullPointerException if the key or the instant is {@code null}
         * @throws IllegalArgumentException if the size is negative, which no stored object has
         */
        public ArtifactDescriptor {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(lastModified, "lastModified must not be null");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }

        /**
         * Renders the descriptor for a diagnostic.
         *
         * <p>Assumptions: every component is printed, and none of them is protected. The key is one of two
         * fixed run-wide names, the size is a byte count of a whole run and the instant is a publication
         * time, so no cardholder value, identifier or amount can reach a log line through this shape.</p>
         *
         * @return the rendered descriptor; never {@code null}
         */
        @Override
        public String toString() {
            return "ArtifactDescriptor[key=" + key
                    + ", sizeBytes=" + sizeBytes
                    + ", lastModified=" + lastModified + ']';
        }
    }
}
