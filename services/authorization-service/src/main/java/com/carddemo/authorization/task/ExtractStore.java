package com.carddemo.authorization.task;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Where the pending-authorization extracts are read from and written to.
 *
 * <p>Refactoring Rationale: this class exists because a task's extract locations have to OUTLIVE the
 * container the task ran in, and a bare filesystem path does not. Fargate gives each task definition its
 * own task, and a volume is shared only WITHIN one task definition, so an extract written to a path inside
 * the authorization container is discarded when that container exits and an extract read from a path inside
 * it was never delivered there. A capability invocable only against such a path is reachable and still
 * useless, which is the same defect one layer down from having no entry point at all.</p>
 *
 * <p>Assumptions: a location is EITHER an {@code s3://bucket/key} object or a filesystem path, decided by
 * the scheme and by nothing else. The object form is what the orchestrator passes, because the dataset
 * bucket is the one destination that survives the task; the filesystem form is retained for an operator
 * running the image by hand against a mounted volume, and for the tests, which need a location that
 * needs no cloud.</p>
 *
 * <p>Assumptions: the whole object is staged through a temporary FILE in both directions, so the memory a
 * transfer costs is one buffer rather than one extract. The unload walks every pending authorization in
 * the schema and the load reads every record of both files, so neither size is bounded by anything this
 * service controls -- which is exactly the property that makes an in-memory byte array the wrong shape
 * here.
 * Alternatives Considered: a multipart-upload output stream, as
 * {@code com.carddemo.reporting.sink.S3ArtifactWriter} uses. Rejected for this subject because a staged
 * file publishes as ONE PutObject: a run that fails part-way leaves no object at all, whereas an
 * abandoned multipart upload leaves an incomplete upload that is billed and that a lifecycle rule has to
 * clean up. The reporting writer's subject is a stream whose total size is not known until it ends and
 * which is published even when partial, which is the opposite trade.
 * Trade-offs: the staged file costs ephemeral task storage equal to the extract, and the transfer is
 * serialised behind it rather than overlapped with the walk. Both are accepted because a truncated
 * extract that looks complete is the failure this shape removes.</p>
 *
 * <p>Assumptions: a write is not visible at its destination until {@link StagedWrite#publish()} is called,
 * in BOTH forms -- the object form because PutObject only happens there, and the filesystem form because
 * the bytes land in a sibling {@code .partial} file that publication moves into place atomically. Making
 * the two forms agree on that is deliberate: the caller's guarantee is "a failed run leaves no partial
 * extract", and a guarantee that held for one destination kind and not the other would be worse than
 * none, because the caller could not tell which it had.</p>
 */
@Component
public class ExtractStore {

    /** The scheme that selects an object-store location. */
    static final String OBJECT_SCHEME = "s3://";

    /** The prefix staged temporary files are named with, so an operator can recognise a stranded one. */
    static final String STAGING_PREFIX = "carddemo-authorization-extract-";

    /** The suffix staged temporary files are named with. */
    static final String STAGING_SUFFIX = ".staged";

    /** The suffix an unpublished filesystem write occupies beside its destination. */
    static final String PARTIAL_SUFFIX = ".partial";

    /** The logger transfer outcomes and stranded staging files are reported through. */
    private static final Logger LOG = LoggerFactory.getLogger(ExtractStore.class);

    /** The object-store client remote locations are transferred through. */
    private final S3Client objects;

    /**
     * Builds the store over the object-store client remote locations are transferred through.
     *
     * @param objects the object-store client; must not be {@code null}
     * @throws NullPointerException if {@code objects} is {@code null}
     */
    public ExtractStore(S3Client objects) {
        this.objects = Objects.requireNonNull(objects, "objects must not be null");
    }

    /**
     * Opens an extract for reading, staging a remote one to a temporary file first.
     *
     * <p>Assumptions: a remote extract is transferred IN FULL before the returned stream yields its first
     * byte, so a transfer that fails fails as a transfer rather than as a decode of a truncated record
     * halfway through a load. The load's own refusals then mean what they say.</p>
     *
     * @param location an {@code s3://bucket/key} object or a filesystem path; must not be {@code null}
     * @return a stream over the extract's bytes, which deletes its staging file when closed
     * @throws IOException if the location cannot be opened or a remote extract cannot be transferred
     * @throws IllegalArgumentException if {@code location} is blank or a malformed object location
     * @throws NullPointerException if {@code location} is {@code null}
     */
    public InputStream openForRead(String location) throws IOException {
        ObjectLocation remote = objectLocationOf(location);
        if (remote == null) {
            return Files.newInputStream(Path.of(location.trim()));
        }
        Path staged = Files.createTempFile(STAGING_PREFIX, STAGING_SUFFIX);
        try {
            // WHY : Assumptions: the overload that writes to a PATH is used rather than the one that
            //       returns a response stream, so the transfer is the software development kit's own
            //       file write and this class never holds the object in memory at all.
            this.objects.getObject(
                    GetObjectRequest.builder().bucket(remote.bucket()).key(remote.key()).build(),
                    staged);
        } catch (RuntimeException transferFailure) {
            // WHY : Assumptions: only a RUNTIME failure is caught, because the path overload declares no
            //       checked exception. A catch that also named IOException would not compile, and adding
            //       one that did would mean claiming a failure mode this call cannot have.
            deleteQuietly(staged);
            throw transferFailure;
        }
        LOG.info("event=authorization.extract.staged direction=read bytes={}", Files.size(staged));
        return new StagedRead(Files.newInputStream(staged), staged);
    }

    /**
     * Opens an extract for writing, staging the bytes so nothing is visible until they are published.
     *
     * @param location an {@code s3://bucket/key} object or a filesystem path; must not be {@code null}
     * @return an unpublished write over the location; the caller closes it and publishes it explicitly
     * @throws IOException if the staging file cannot be created
     * @throws IllegalArgumentException if {@code location} is blank or a malformed object location
     * @throws NullPointerException if {@code location} is {@code null}
     */
    public StagedWrite openForWrite(String location) throws IOException {
        ObjectLocation remote = objectLocationOf(location);
        if (remote != null) {
            Path staged = Files.createTempFile(STAGING_PREFIX, STAGING_SUFFIX);
            return new StagedWrite(this.objects, remote, staged, null);
        }
        Path destination = Path.of(location.trim()).toAbsolutePath();
        Path parent = destination.getParent();
        if (parent != null) {
            // WHY : Assumptions: the parent is created rather than required, because the container the
            //       orchestrator starts has an empty filesystem and an operator naming a fresh directory
            //       should not have to pre-create it in a separate step they can forget.
            Files.createDirectories(parent);
        }
        Path staged = Path.of(destination + PARTIAL_SUFFIX);
        return new StagedWrite(this.objects, null, staged, destination);
    }

    /**
     * Parses an object location, or reports that the token names a filesystem path.
     *
     * @param location the location token; must not be {@code null}
     * @return the parsed object location, or {@code null} when the token carries no object scheme
     * @throws IllegalArgumentException if the token is blank, or carries the scheme without both a bucket
     *     and a key
     * @throws NullPointerException if {@code location} is {@code null}
     */
    static ObjectLocation objectLocationOf(String location) {
        Objects.requireNonNull(location, "location must not be null");
        String token = location.trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("an extract location must not be blank");
        }
        if (!token.startsWith(OBJECT_SCHEME)) {
            return null;
        }
        String remainder = token.substring(OBJECT_SCHEME.length());
        int separator = remainder.indexOf('/');
        // WHY : Assumptions: a separator at position zero is refused as well as an absent one. The first
        //       is s3:///key, which names no bucket; the second is s3://bucket, which names no key and
        //       would otherwise be sent as a request for the bucket itself.
        if (separator <= 0 || separator == remainder.length() - 1) {
            throw new IllegalArgumentException(
                    "an object extract location must be of the form " + OBJECT_SCHEME + "bucket/key");
        }
        return new ObjectLocation(remainder.substring(0, separator), remainder.substring(separator + 1));
    }

    /**
     * Deletes a staging file without letting the cleanup mask the failure that prompted it.
     *
     * @param staged the staging file to remove; must not be {@code null}
     */
    private static void deleteQuietly(Path staged) {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException cleanupFailure) {
            // WHY : Trade-offs: reported and swallowed. A cleanup failure leaves a file in the task's own
            //       ephemeral storage, which the task's exit reclaims; rethrowing it would replace the
            //       real cause with a housekeeping error and cost the operator the reason for the run's
            //       failure.
            LOG.warn("event=authorization.extract.staging-not-removed reason={}",
                    cleanupFailure.getClass().getName());
        }
    }

    /**
     * One object-store location: the bucket and the key within it.
     *
     * @param bucket the bucket name
     * @param key the object key
     */
    record ObjectLocation(String bucket, String key) {
    }

    /**
     * A stream over a staged remote extract that removes its staging file when closed.
     *
     * <p>Assumptions: the deletion happens on CLOSE rather than on a shutdown hook or a finaliser, so a
     * long-running task that reads several extracts does not accumulate their staging files for the
     * lifetime of the process.</p>
     */
    private static final class StagedRead extends FilterInputStream {

        /** The staging file removed when this stream is closed. */
        private final Path staged;

        /**
         * Wraps the stream over a staging file.
         *
         * @param delegate the stream over the staging file; must not be {@code null}
         * @param staged the staging file; must not be {@code null}
         */
        private StagedRead(InputStream delegate, Path staged) {
            super(delegate);
            this.staged = staged;
        }

        /**
         * Closes the stream and removes the staging file.
         *
         * @throws IOException if the stream cannot be closed
         */
        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                deleteQuietly(this.staged);
            }
        }
    }

    /**
     * An extract write that is staged and becomes visible at its destination only when published.
     *
     * <p>Assumptions: publication and closing are SEPARATE operations, so a caller writing two extracts
     * can open both, write both and publish both only once the producing walk has returned. A single
     * close-publishes shape could not express that: the first extract would already be at its destination
     * while the second was still being written, and a failure between them would leave a consumer a
     * complete root file and no children.</p>
     */
    public static final class StagedWrite implements Closeable {

        /** The object-store client a remote publication goes through. */
        private final S3Client objects;

        /** The object location to publish to, or {@code null} for a filesystem destination. */
        private final ObjectLocation remote;

        /** The staging file the bytes are written to. */
        private final Path staged;

        /** The filesystem destination to move into place, or {@code null} for an object destination. */
        private final Path destination;

        /** The stream the caller writes through. */
        private final OutputStream stream;

        /** Whether the bytes have been published to the destination. */
        private boolean published;

        /** Whether this write has been closed. */
        private boolean closed;

        /**
         * Opens the staging file and the stream over it.
         *
         * @param objects the object-store client; must not be {@code null}
         * @param remote the object location, or {@code null} for a filesystem destination
         * @param staged the staging file; must not be {@code null}
         * @param destination the filesystem destination, or {@code null} for an object destination
         * @throws IOException if the staging file cannot be opened for writing
         */
        private StagedWrite(S3Client objects, ObjectLocation remote, Path staged, Path destination)
                throws IOException {
            this.objects = objects;
            this.remote = remote;
            this.staged = staged;
            this.destination = destination;
            this.stream = Files.newOutputStream(staged);
        }

        /**
         * The stream the extract is written through.
         *
         * @return the stream, never {@code null}
         */
        public OutputStream stream() {
            return this.stream;
        }

        /**
         * Makes the written bytes visible at the destination.
         *
         * <p>Assumptions: the stream is closed HERE rather than left to {@link #close()}, because an
         * unflushed buffer would otherwise be published short. A second call is a no-op, so a caller may
         * publish inside a try-with-resources without having to reason about whether the block also
         * publishes.</p>
         *
         * @throws IOException if the staged bytes cannot be transferred or moved into place
         */
        public void publish() throws IOException {
            if (this.published) {
                return;
            }
            this.stream.close();
            long bytes = Files.size(this.staged);
            if (this.remote == null) {
                // WHY : Assumptions: ATOMIC_MOVE is the ONLY option passed, and REPLACE_EXISTING is
                //       deliberately absent rather than passed alongside it. Files.move documents that
                //       when ATOMIC_MOVE is present every other option is IGNORED, so naming a second one
                //       would state a behaviour this call does not obtain it from; the replacement comes
                //       from the atomic rename itself. The staging file is a sibling of the destination
                //       precisely so the rename stays within one file store, which is the condition
                //       ATOMIC_MOVE can otherwise refuse.
                Files.move(this.staged, this.destination, StandardCopyOption.ATOMIC_MOVE);
            } else {
                this.objects.putObject(
                        PutObjectRequest.builder().bucket(this.remote.bucket())
                                .key(this.remote.key()).build(),
                        RequestBody.fromFile(this.staged));
                deleteQuietly(this.staged);
            }
            this.published = true;
            LOG.info("event=authorization.extract.published direction=write bytes={}", bytes);
        }

        /**
         * Closes the write, discarding the staged bytes when they were never published.
         *
         * @throws IOException if the stream cannot be closed
         */
        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            try {
                this.stream.close();
            } finally {
                if (!this.published) {
                    // WHY : Assumptions: an unpublished staging file is DELETED rather than left for
                    //       inspection. Leaving it would put a truncated extract beside the destination
                    //       under a name a later run then has to distinguish from its own, and the
                    //       diagnostic value is nil because the failure that prevented publication is
                    //       already on the run's exit status and in its log.
                    deleteQuietly(this.staged);
                }
            }
        }
    }
}
