package com.carddemo.authorization.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.service.UnloadService;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Holds the export task to being invocable, to publishing both extracts together, and to publishing neither
 * when the export raised.
 *
 * <p>Assumptions: the exporter is a mock and the store is real, against filesystem destinations. That
 * division is deliberate: what this class decides is WHICH destinations are opened, in which form, and at
 * what moment they become visible, and every one of those is observable on a real filesystem. Mocking the
 * store instead would assert that the task called a method, which is the part that cannot be wrong.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
class UnloadAuthorizationsTaskTest {

    /** The bytes the stubbed exporter writes to the root destination. */
    private static final byte[] ROOT_BYTES = "ROOTIMAGE".getBytes(StandardCharsets.US_ASCII);

    /** The bytes the stubbed exporter writes to the child destination. */
    private static final byte[] CHILD_BYTES = "CHILDRECORD".getBytes(StandardCharsets.US_ASCII);

    /**
     * The business date every case's parameters carry.
     *
     * <p>Assumptions: a fixed literal rather than {@code LocalDate.now()}, for the same reason every
     * batch step in this migration takes its date as a parameter -- a clock read would make the run
     * that happens to cross midnight produce a different outcome line from the run before it.</p>
     */
    private static final String BUSINESS_DATE = "2022-07-18";

    /**
     * Both destinations carry their bytes once the export has returned.
     *
     * @param directory a fresh directory the case writes into; supplied by the framework
     * @throws IOException if the case's own file handling fails
     */
    @Test
    @DisplayName("a clean export publishes both extracts")
    void aCleanExportPublishesBothExtracts(@TempDir Path directory) throws IOException {
        UnloadService unloader = mock(UnloadService.class);
        Path roots = directory.resolve("roots.dat");
        Path children = directory.resolve("children.dat");
        when(unloader.unload(any(), any(), any())).thenAnswer(invocation -> {
            OutputStream rootStream = invocation.getArgument(1, OutputStream.class);
            OutputStream childStream = invocation.getArgument(2, OutputStream.class);
            rootStream.write(ROOT_BYTES);
            childStream.write(CHILD_BYTES);
            return new UnloadService.UnloadOutcome(1, 1, 0);
        });

        task(unloader).run(parameters(roots, children, null));

        assertThat(roots).hasBinaryContent(ROOT_BYTES);
        assertThat(children).hasBinaryContent(CHILD_BYTES);
    }

    /**
     * An export that raises publishes NEITHER extract, so no consumer sees a complete half.
     *
     * <p>Assumptions: the stub writes the whole root extract and then raises while writing the child one,
     * which is the exact shape that would leave a misleading pair behind if publication happened per
     * stream. A stub that raised before writing anything would pass against a task that published each
     * stream as it closed.</p>
     *
     * @param directory a fresh directory the case writes into; supplied by the framework
     * @throws IOException if the case's own file handling fails
     */
    @Test
    @DisplayName("an export that fails part-way publishes neither extract")
    void anExportThatFailsPartWayPublishesNeitherExtract(@TempDir Path directory) throws IOException {
        UnloadService unloader = mock(UnloadService.class);
        Path roots = directory.resolve("roots.dat");
        Path children = directory.resolve("children.dat");
        when(unloader.unload(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, OutputStream.class).write(ROOT_BYTES);
            throw new UncheckedIOException(new IOException("the walk failed after the roots were written"));
        });

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> task(unloader).run(parameters(roots, children, null)));

        assertThat(roots).as("the complete root extract must not be published either").doesNotExist();
        assertThat(children).doesNotExist();
        assertThat(leftoversIn(directory))
                .as("neither staging file survives the failure")
                .isEmpty();
    }

    /**
     * An omitted form runs the exporter's own default, and a named one runs that form.
     *
     * <p>Assumptions: the default is asserted by the value the EXPORTER receives rather than by the
     * parameter map, because the task is the only thing that decides it. The sequential form is named
     * explicitly because it is the transcription of the second reference program the export covers, and it
     * was reachable from nothing before this task existed.</p>
     *
     * @param directory a fresh directory the case writes into; supplied by the framework
     * @throws IOException if the case's own file handling fails
     */
    @Test
    @DisplayName("the form defaults to the exporter's own and an explicit form is honoured")
    void theFormDefaultsToTheExportersOwnAndAnExplicitFormIsHonoured(@TempDir Path directory)
            throws IOException {
        UnloadService unloader = mock(UnloadService.class);
        when(unloader.unload(any(), any(), any())).thenReturn(UnloadService.UnloadOutcome.NOTHING);
        UnloadAuthorizationsTask task = task(unloader);

        task.run(parameters(directory.resolve("a"), directory.resolve("b"), null));
        verify(unloader).unload(eq(UnloadService.DEFAULT_FORM), any(), any());

        task.run(parameters(directory.resolve("c"), directory.resolve("d"), "sequential"));
        verify(unloader).unload(eq(UnloadService.UnloadForm.SEQUENTIAL), any(), any());
    }

    /**
     * An unpublished form is refused before the exporter or either destination is touched.
     *
     * @param directory a fresh directory the case would have written into; supplied by the framework
     * @throws IOException if the case's own file handling fails
     */
    @Test
    @DisplayName("an unpublished form is refused before anything is opened")
    void anUnpublishedFormIsRefusedBeforeAnythingIsOpened(@TempDir Path directory) throws IOException {
        UnloadService unloader = mock(UnloadService.class);
        Path roots = directory.resolve("roots.dat");

        assertThatThrownBy(() -> task(unloader)
                .run(parameters(roots, directory.resolve("children.dat"), "compressed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefixed");

        verifyNoInteractions(unloader);
        assertThat(roots).doesNotExist();
        assertThat(leftoversIn(directory)).isEmpty();
    }

    /**
     * An absent destination parameter is refused by name rather than dereferenced.
     *
     * @throws IOException if the case's own file handling fails
     */
    @Test
    @DisplayName("an absent destination parameter is refused by name")
    void anAbsentDestinationParameterIsRefusedByName() throws IOException {
        UnloadAuthorizationsTask task = task(mock(UnloadService.class));

        assertThatThrownBy(() -> task.run(Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("root extract");
        assertThatThrownBy(() -> task.run(
                Map.of(MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER, "/staged/roots")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("child extract");
        // WHY : ⚠️ Refactoring Rationale: the business date joins the two destinations as a
        //       parameter this task refuses to run without. It is checked HERE as well as in the
        //       runner because the task bean is resolved and invoked directly -- which is how every
        //       case in this class drives it -- so a check living only in the argument handling
        //       would be absent from the path that opens the destinations and writes.
        assertThatThrownBy(() -> task.run(Map.of(
                MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER, "/staged/roots",
                MaintenanceTaskRunner.CHILD_EXTRACT_PARAMETER, "/staged/children")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("business date");
        assertThatThrownBy(() -> task.run(Map.of(
                MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER, "/staged/roots",
                MaintenanceTaskRunner.CHILD_EXTRACT_PARAMETER, "/staged/children",
                MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER, "2022-02-30")))
                .as("a well-formed but impossible day is refused, not just a mis-shaped one")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("business date");
    }

    /**
     * Builds the task over a stubbed exporter and a real store against the filesystem.
     *
     * @param unloader the stubbed exporter; must not be {@code null}
     * @return the task under test
     */
    private static UnloadAuthorizationsTask task(UnloadService unloader) {
        return new UnloadAuthorizationsTask(unloader, new ExtractStore(mock(S3Client.class)));
    }

    /**
     * Builds the parameter map the runner would publish for an export.
     *
     * @param roots the root destination; must not be {@code null}
     * @param children the child destination; must not be {@code null}
     * @param form the wire value of the form, or {@code null} to omit the parameter
     * @return the parameter map
     */
    private static Map<String, String> parameters(Path roots, Path children, String form) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER, roots.toString());
        parameters.put(MaintenanceTaskRunner.CHILD_EXTRACT_PARAMETER, children.toString());
        // WHY : Assumptions: the business date is included in every case's parameters because the
        //       task now REQUIRES it, and it is a fixed literal rather than today's date so a run
        //       of this suite produces the same outcome line whenever it happens to execute.
        parameters.put(MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER, BUSINESS_DATE);
        if (form != null) {
            parameters.put(MaintenanceTaskRunner.EXTRACT_FORM_PARAMETER, form);
        }
        return parameters;
    }

    /**
     * Lists the staging files left in a destination directory.
     *
     * @param directory the directory to inspect; must not be {@code null}
     * @return the names of the staging leftovers
     * @throws IOException if the directory cannot be listed
     */
    private static java.util.List<String> leftoversIn(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(ExtractStore.PARTIAL_SUFFIX))
                    .toList();
        }
    }
}
