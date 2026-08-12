package com.carddemo.authorization.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Holds the extract store to publishing ALL of an extract or none of it, at either kind of destination.
 *
 * <p>Assumptions: the filesystem destination carries most of the behavioural cases, because it is the form
 * whose outcome a test can read back byte for byte without an emulator. The object form is asserted through
 * the request the client is handed, which is the whole of what this class decides about it -- the transfer
 * itself is the software development kit's.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
class ExtractStoreTest {

    /** The bytes cases write, chosen so a truncation is visible rather than plausible. */
    private static final byte[] PAYLOAD = "ROOT0000000001PAYLOAD".getBytes(StandardCharsets.US_ASCII);

    /**
     * How a location token is classified, and which malformed ones are refused.
     */
    @Nested
    @DisplayName("location parsing")
    class OnParsingALocation {

        /**
         * A path is a path and an object location is an object location, decided by the scheme alone.
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the scheme, and nothing else, decides which kind of location a token names")
        void theSchemeAndNothingElseDecidesWhichKindOfLocationATokenNames() {
            assertThat(ExtractStore.objectLocationOf("/staged/roots.dat"))
                    .as("a filesystem path yields no object location")
                    .isNull();
            assertThat(ExtractStore.objectLocationOf("roots.dat")).isNull();
            assertThat(ExtractStore.objectLocationOf("s3://carddemo-datasets-dev/authorization/roots.dat"))
                    .isEqualTo(new ExtractStore.ObjectLocation(
                            "carddemo-datasets-dev", "authorization/roots.dat"));
        }

        /**
         * An object location naming no bucket or no key is refused rather than sent.
         *
         * <p>Assumptions: both malformed shapes are named, because each fails differently. A location with
         * no bucket addresses nothing; a location with no key would be sent as a request for the bucket
         * itself, which is a different operation with a different privilege.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("an object location must carry both a bucket and a key")
        void anObjectLocationMustCarryBothABucketAndAKey() {
            for (String malformed : List.of("s3:///roots.dat", "s3://bucket", "s3://bucket/")) {
                assertThatThrownBy(() -> ExtractStore.objectLocationOf(malformed))
                        .as("refused: %s", malformed)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(ExtractStore.OBJECT_SCHEME);
            }
            assertThatThrownBy(() -> ExtractStore.objectLocationOf("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    /**
     * What reaches a filesystem destination, and when.
     */
    @Nested
    @DisplayName("a filesystem destination")
    class OnAFilesystemDestination {

        /**
         * Nothing is at the destination until publication, and everything is after it.
         *
         * @param directory a fresh directory the case writes into; supplied by the framework
         * @throws IOException if the case's own file handling fails
         */
        @Test
        @DisplayName("the destination is empty until publication and complete after it")
        void theDestinationIsEmptyUntilPublicationAndCompleteAfterIt(@TempDir Path directory)
                throws IOException {
            S3Client objects = mock(S3Client.class);
            Path destination = directory.resolve("roots.dat");

            try (ExtractStore.StagedWrite write = new ExtractStore(objects)
                    .openForWrite(destination.toString())) {
                write.stream().write(PAYLOAD);
                assertThat(destination)
                        .as("an unpublished write must not be visible at its destination")
                        .doesNotExist();
                write.publish();
                assertThat(destination).exists().hasBinaryContent(PAYLOAD);
            }

            assertThat(stagingSiblingsIn(directory))
                    .as("publication moves the staging file rather than leaving it beside the destination")
                    .isEmpty();
            verifyNoInteractions(objects);
        }

        /**
         * A write that is never published leaves neither the destination nor a partial file behind.
         *
         * <p>Assumptions: BOTH absences are asserted. A destination that stayed empty while a
         * {@code .partial} file survived would still leave a later run to distinguish that file from its
         * own, which is the outcome the discard exists to prevent.</p>
         *
         * @param directory a fresh directory the case writes into; supplied by the framework
         * @throws IOException if the case's own file handling fails
         */
        @Test
        @DisplayName("an unpublished write leaves no destination and no partial file")
        void anUnpublishedWriteLeavesNoDestinationAndNoPartialFile(@TempDir Path directory)
                throws IOException {
            Path destination = directory.resolve("children.dat");

            try (ExtractStore.StagedWrite write =
                    new ExtractStore(mock(S3Client.class)).openForWrite(destination.toString())) {
                write.stream().write(PAYLOAD);
            }

            assertThat(destination).doesNotExist();
            assertThat(stagingSiblingsIn(directory)).isEmpty();
        }

        /**
         * A missing parent directory is created, because the container's filesystem starts empty.
         *
         * @param directory a fresh directory the case writes into; supplied by the framework
         * @throws IOException if the case's own file handling fails
         */
        @Test
        @DisplayName("a destination in a directory that does not exist yet is still writable")
        void aDestinationInADirectoryThatDoesNotExistYetIsStillWritable(@TempDir Path directory)
                throws IOException {
            Path destination = directory.resolve("extract/run-1/roots.dat");

            try (ExtractStore.StagedWrite write =
                    new ExtractStore(mock(S3Client.class)).openForWrite(destination.toString())) {
                write.stream().write(PAYLOAD);
                write.publish();
            }

            assertThat(destination).hasBinaryContent(PAYLOAD);
        }

        /**
         * A second publication is a no-op, so publishing inside a try-with-resources is safe.
         *
         * @param directory a fresh directory the case writes into; supplied by the framework
         * @throws IOException if the case's own file handling fails
         */
        @Test
        @DisplayName("publishing twice publishes once")
        void publishingTwicePublishesOnce(@TempDir Path directory) throws IOException {
            Path destination = directory.resolve("roots.dat");
            ExtractStore.StagedWrite write =
                    new ExtractStore(mock(S3Client.class)).openForWrite(destination.toString());
            write.stream().write(PAYLOAD);
            write.publish();
            write.publish();
            write.close();

            assertThat(destination).hasBinaryContent(PAYLOAD);
        }

        /**
         * A filesystem extract is read back without staging, so no temporary file is created for it.
         *
         * @param directory a fresh directory the case writes into; supplied by the framework
         * @throws IOException if the case's own file handling fails
         */
        @Test
        @DisplayName("a filesystem extract is read directly and the client is never consulted")
        void aFilesystemExtractIsReadDirectlyAndTheClientIsNeverConsulted(@TempDir Path directory)
                throws IOException {
            S3Client objects = mock(S3Client.class);
            Path source = Files.write(directory.resolve("roots.dat"), PAYLOAD);

            try (InputStream stream = new ExtractStore(objects).openForRead(source.toString())) {
                assertThat(stream.readAllBytes()).isEqualTo(PAYLOAD);
            }

            verifyNoInteractions(objects);
        }
    }

    /**
     * What the object-store client is asked to do, and when.
     */
    @Nested
    @DisplayName("an object destination")
    class OnAnObjectDestination {

        /**
         * Nothing is sent until publication, and publication sends the staged bytes to the parsed location.
         *
         * @throws IOException if the case's own file handling fails
         */
        @Test
        @DisplayName("publication sends the staged bytes to the parsed bucket and key")
        void publicationSendsTheStagedBytesToTheParsedBucketAndKey() throws IOException {
            S3Client objects = mock(S3Client.class);

            try (ExtractStore.StagedWrite write = new ExtractStore(objects)
                    .openForWrite("s3://carddemo-datasets-dev/authorization/dt=2022-07-18/roots.dat")) {
                write.stream().write(PAYLOAD);
                verify(objects, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
                write.publish();
            }

            ArgumentCaptor<PutObjectRequest> sent = ArgumentCaptor.forClass(PutObjectRequest.class);
            ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
            verify(objects).putObject(sent.capture(), body.capture());
            assertThat(sent.getValue().bucket()).isEqualTo("carddemo-datasets-dev");
            assertThat(sent.getValue().key()).isEqualTo("authorization/dt=2022-07-18/roots.dat");
            assertThat(body.getValue().optionalContentLength())
                    .as("the body is the staged file, so its length is the whole extract's")
                    .contains((long) PAYLOAD.length);
        }

        /**
         * A write that is never published sends nothing at all.
         *
         * <p>Assumptions: this is the case that makes the export's all-or-nothing guarantee real. A store
         * that published on close would put a truncated root extract in the bucket whenever the walk that
         * was filling it raised.</p>
         *
         * @throws IOException if the case's own file handling fails
         */
        @Test
        @DisplayName("an unpublished write sends nothing")
        void anUnpublishedWriteSendsNothing() throws IOException {
            S3Client objects = mock(S3Client.class);

            try (ExtractStore.StagedWrite write =
                    new ExtractStore(objects).openForWrite("s3://bucket/roots.dat")) {
                write.stream().write(PAYLOAD);
            }

            verify(objects, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        /**
         * A failed transfer raises and leaves no staging file behind.
         *
         * <p>Assumptions: the staging directory is counted before and after, rather than a specific file
         * name being looked for, because the name is the temporary-file facility's to choose.</p>
         *
         * @throws IOException if the case's own file handling fails
         */
        @Test
        @DisplayName("a failed read transfer raises and strands no staging file")
        void aFailedReadTransferRaisesAndStrandsNoStagingFile() throws IOException {
            S3Client objects = mock(S3Client.class);
            doThrow(S3Exception.builder().message("no such key").build())
                    .when(objects).getObject(any(GetObjectRequest.class), any(Path.class));
            ExtractStore store = new ExtractStore(objects);
            long before = stagingFileCount();

            assertThatExceptionOfType(S3Exception.class)
                    .isThrownBy(() -> store.openForRead("s3://bucket/roots.dat"));

            assertThat(stagingFileCount())
                    .as("a transfer that failed must not leave its staging file in the temporary directory")
                    .isEqualTo(before);
        }
    }

    /**
     * Counts the staging files this class's own prefix owns in the temporary directory.
     *
     * <p>Assumptions: the count is scoped by the published prefix, so a sibling test's temporary files
     * cannot make this assertion fail or pass.</p>
     *
     * @return how many staging files are present
     * @throws IOException if the temporary directory cannot be listed
     */
    private static long stagingFileCount() throws IOException {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> entries = Files.list(temporary)) {
            return entries.filter(path ->
                    path.getFileName().toString().startsWith(ExtractStore.STAGING_PREFIX)).count();
        }
    }

    /**
     * Lists whatever is left in a destination directory that is not the destination file itself.
     *
     * @param directory the directory to inspect; must not be {@code null}
     * @return the names of the leftover entries
     * @throws IOException if the directory cannot be listed
     */
    private static List<String> stagingSiblingsIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(ExtractStore.PARTIAL_SUFFIX)
                            || name.startsWith(ExtractStore.STAGING_PREFIX))
                    .toList();
        }
    }

    /**
     * The stream a caller writes through is the staged one, not the destination.
     *
     * <p>Assumptions: asserted directly because it is the property every case above rests on. A store that
     * handed back a stream onto the destination would make the unpublished cases pass for the wrong reason
     * -- they would be observing an empty file rather than an absent one.</p>
     *
     * @param directory a fresh directory the case writes into; supplied by the framework
     * @throws IOException if the case's own file handling fails
     */
    @Test
    @DisplayName("the caller writes through a staged stream rather than onto the destination")
    void theCallerWritesThroughAStagedStreamRatherThanOntoTheDestination(@TempDir Path directory)
            throws IOException {
        Path destination = directory.resolve("roots.dat");
        try (ExtractStore.StagedWrite write =
                new ExtractStore(mock(S3Client.class)).openForWrite(destination.toString())) {
            OutputStream stream = write.stream();
            assertThat(stream).isNotNull();
            stream.write(PAYLOAD);
            stream.flush();
            assertThat(destination).doesNotExist();
            assertThat(stagingSiblingsIn(directory))
                    .as("the bytes are in a partial sibling while the write is unpublished")
                    .containsExactly("roots.dat" + ExtractStore.PARTIAL_SUFFIX);
        }
    }
}
