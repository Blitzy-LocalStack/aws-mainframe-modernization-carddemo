package com.carddemo.authorization.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.authorization.service.PurgeJob;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * Holds the maintenance jobs to being INVOCABLE, which is the property they previously lacked.
 *
 * <p>Assumptions: these cases assert wiring rather than behaviour. The load and the purge each have their
 * own behavioural tests; what had no test at all was that anything could reach them, and the defect was
 * exactly that -- two fully-implemented, fully-tested services with no production caller, described by
 * their own documentation as orchestrator-invoked. A behavioural test cannot catch that, because it calls
 * the service directly.</p>
 *
 * <p>Assumptions: the bean-name correspondence is checked by reading the ANNOTATIONS on the task classes
 * rather than by starting a context. Starting one would need a database, a queue and an identity provider
 * for a question that is answered by the source, and a test that needs three services to prove a name
 * matches is a test that gets disabled.</p>
 */
class MaintenanceTaskWiringTest {

    /** Where the task classes live, relative to the module root. */
    private static final Path TASK_SOURCES =
            Path.of("src/main/java/com/carddemo/authorization/task");

    /**
     * Every published job name resolves to a task bean, and every task bean is published.
     *
     * <p>Assumptions: the correspondence is asserted in BOTH directions, because each direction is a
     * different defect. A published name with no bean is a job an operator can select and that then fails
     * at invocation; a bean with no published name is a job that compiles, is tested, and can never be
     * invoked -- which is precisely the state the load and the purge were in.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws IOException if the task sources cannot be read, which is a failure of this test's own
     *     arrangement rather than of the wiring under test
     */
    @Test
    @DisplayName("every published job name has a task bean and every task bean is published")
    void everyPublishedJobNameHasATaskBeanAndEveryTaskBeanIsPublished() throws IOException {
        Set<String> annotated;
        try (Stream<Path> sources = Files.list(TASK_SOURCES)) {
            annotated = sources.filter(path -> path.getFileName().toString().endsWith("Task.java"))
                    .map(MaintenanceTaskWiringTest::componentNameOf)
                    .filter(name -> !name.isEmpty())
                    .collect(Collectors.toSet());
        }

        assertThat(annotated)
                .as("every task class carries a @Component name and it is a published job name")
                .containsExactlyInAnyOrderElementsOf(MaintenanceTaskRunner.JOB_NAMES);
    }

    /**
     * All three task classes implement the contract the runner resolves by.
     *
     * <p>Refactoring Rationale: this case named TWO classes and now names three. The export was added to
     * the package after the load and the purge, and a case enumerating its subjects one at a time is a case
     * that cannot see the class nobody added it to -- which is the same reason the set-equality assertion
     * above reads the directory instead of a list.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all three task classes implement the runner's contract")
    void allThreeTaskClassesImplementTheRunnersContract() {
        assertThat(AuthorizationTask.class)
                .as("the load task is resolvable through the contract the runner asks the context for")
                .isAssignableFrom(LoadAuthorizationsTask.class);
        assertThat(AuthorizationTask.class)
                .as("the export task is resolvable through the contract the runner asks the context for")
                .isAssignableFrom(UnloadAuthorizationsTask.class);
        assertThat(AuthorizationTask.class)
                .as("the purge task is resolvable through the contract the runner asks the context for")
                .isAssignableFrom(PurgeAuthorizationsTask.class);
    }

    /**
     * A job selection is recognised, and an ordinary service start-up is not diverted into a task.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("only a job option marks an invocation as a task")
    void onlyAJobOptionMarksAnInvocationAsATask() {
        assertThat(MaintenanceTaskRunner.isTaskInvocation(
                new String[] {"--job=" + MaintenanceTaskRunner.PURGE_JOB})).isTrue();
        assertThat(MaintenanceTaskRunner.isTaskInvocation(
                new String[] {"--spring.profiles.active=prod"})).isFalse();
        assertThat(MaintenanceTaskRunner.isTaskInvocation(new String[0])).isFalse();
        assertThat(MaintenanceTaskRunner.isTaskInvocation(null)).isFalse();
    }

    /**
     * An unknown job name is refused with the published names named.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unknown job is refused and the published jobs are named")
    void anUnknownJobIsRefusedAndThePublishedJobsAreNamed() {
        assertThatThrownBy(() -> MaintenanceTaskRunner.requiredJobName(new String[] {"--job=purge"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MaintenanceTaskRunner.PURGE_JOB)
                .hasMessageContaining(MaintenanceTaskRunner.LOAD_JOB);
        assertThatThrownBy(() -> MaintenanceTaskRunner.requiredJobName(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MaintenanceTaskRunner.JOB_OPTION);
    }

    /**
     * The purge's business date is required and must be an ISO calendar date of exactly ten characters.
     *
     * <p>Assumptions: the over-long form is refused as well as the unparseable one, because the parser
     * accepts a date-time and an operator who passed one would get a different instant than they wrote --
     * and the purge's whole reproducibility rests on that date being the one they intended.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the purge refuses an absent, malformed or over-long business date")
    void thePurgeRefusesAnAbsentMalformedOrOverLongBusinessDate() {
        assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.PURGE_JOB, new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MaintenanceTaskRunner.BUSINESS_DATE_OPTION);
        assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.PURGE_JOB, new String[] {"--business-date=2022-13-40"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.PURGE_JOB, new String[] {"--business-date=2022-07-18T00:00"}))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, String> accepted = MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.PURGE_JOB, new String[] {"--business-date=2022-07-18"});
        assertThat(accepted)
                .containsExactly(Map.entry(MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER, "2022-07-18"));
    }

    /**
     * The load requires both extract paths and publishes them under the names its task reads.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the load requires both extract paths under the names its task reads")
    void theLoadRequiresBothExtractPathsUnderTheNamesItsTaskReads() {
        assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.LOAD_JOB, new String[] {"--root-extract=/staged/roots"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MaintenanceTaskRunner.CHILD_EXTRACT_OPTION);

        Map<String, String> accepted = MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.LOAD_JOB,
                new String[] {"--root-extract=/staged/roots", "--child-extract=/staged/children"});
        assertThat(accepted).containsOnlyKeys(MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER,
                MaintenanceTaskRunner.CHILD_EXTRACT_PARAMETER);
    }

    /**
     * The export requires both destinations and takes the record form as an option with a default.
     *
     * <p>Assumptions: the absent-form case asserts the parameter is ABSENT rather than defaulted, because
     * the default belongs to the exporter, which publishes it. A default written into the argument handling
     * would be a second statement of it, and the copy that drifts would silently change which record shape
     * an operator's unchanged command produced.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the export requires both destinations and a business date, and defaults its form")
    void theExportRequiresBothDestinationsAndDefaultsItsRecordForm() {
        assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.UNLOAD_JOB, new String[] {"--root-extract=s3://bucket/roots"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MaintenanceTaskRunner.CHILD_EXTRACT_OPTION);

        Map<String, String> defaulted = MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.UNLOAD_JOB,
                new String[] {"--root-extract=s3://bucket/roots",
                        "--child-extract=s3://bucket/children", "--business-date=2022-07-18"});
        assertThat(defaulted)
                .as("an omitted form leaves the parameter absent so the exporter applies its own default")
                .containsOnlyKeys(MaintenanceTaskRunner.ROOT_EXTRACT_PARAMETER,
                        MaintenanceTaskRunner.CHILD_EXTRACT_PARAMETER,
                        MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER);

        Map<String, String> sequential = MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.UNLOAD_JOB,
                new String[] {"--root-extract=/staged/roots", "--child-extract=/staged/children",
                        "--business-date=2022-07-18", "--extract-form=sequential"});
        assertThat(sequential).containsEntry(MaintenanceTaskRunner.EXTRACT_FORM_PARAMETER, "sequential");
    }

    /**
     * The export refuses an absent or unparseable business date before a container starts.
     *
     * <p>⚠️ Refactoring Rationale: the export took NO business date while the orchestrator composed
     * both of its destination keys from one, and the only check that value met was the state machine's
     * {@code ????-??-??} shape match -- which admits {@code abcd-ef-gh} and the impossible
     * {@code 2022-02-30} alike. An export then landed under a prefix no later run could find by date.
     * This case pins the parse that replaced that hope, and it pins it at the argument boundary so the
     * refusal reaches an operator as a usage message naming the option rather than as a job failure
     * after a Fargate task has started.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the export refuses an absent or impossible business date")
    void theExportRefusesAnAbsentOrImpossibleBusinessDate() {
        assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.UNLOAD_JOB,
                new String[] {"--root-extract=/r", "--child-extract=/c"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MaintenanceTaskRunner.BUSINESS_DATE_OPTION);

        for (String rejected : new String[] {"abcd-ef-gh", "2022-02-30", "18/07/2022",
                "2022-07-18T00:00:00", ""}) {
            assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                    MaintenanceTaskRunner.UNLOAD_JOB,
                    new String[] {"--root-extract=/r", "--child-extract=/c",
                            "--business-date=" + rejected}))
                    .as("%s must be refused before any destination is opened", rejected)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(MaintenanceTaskRunner.BUSINESS_DATE_OPTION);
        }
    }

    /**
     * An unpublished record form is refused before a container starts, with the published forms named.
     *
     * <p>Assumptions: the refusal is asserted to name the admitted values, not merely to be raised. An
     * operator who mistyped a form learns which forms exist from the message, and the alternative -- a bare
     * rejection plus a documentation lookup -- is the reason the exporter publishes the values as a list
     * this refusal can render.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unpublished record form is refused and the published forms are named")
    void anUnpublishedRecordFormIsRefusedAndThePublishedFormsAreNamed() {
        // WHY : Assumptions: the business date is supplied in both vectors below so this case keeps
        //       measuring the FORM refusal. The export requires a date, and it is validated before
        //       the optional form, so an argument list without one would now be refused for the
        //       missing date and this case would pass while asserting nothing about forms.
        String[] withForm = {"--root-extract=/staged/roots", "--child-extract=/staged/children",
                "--business-date=2022-07-18", "--extract-form=PREFIXED"};
        assertThatThrownBy(() ->
                MaintenanceTaskRunner.taskParameters(MaintenanceTaskRunner.UNLOAD_JOB, withForm))
                .as("the constant NAME is not a wire value, so it is refused like any other unknown form")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefixed")
                .hasMessageContaining("sequential");
        assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.UNLOAD_JOB,
                new String[] {"--root-extract=/r", "--child-extract=/c",
                        "--business-date=2022-07-18", "--extract-form="}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(MaintenanceTaskRunner.EXTRACT_FORM_OPTION);
    }

    /**
     * A job with no declared parameters raises rather than inheriting another job's options.
     *
     * <p>Refactoring Rationale: the parameter collection tested for the purge and treated EVERYTHING ELSE
     * as the load. That was correct for two jobs and became a trap at three, because the export wants the
     * same two location options and would have fallen through -- passing this suite while performing no
     * validation of its own. This case pins the explicit refusal that replaced the fall-through, so a fourth
     * job cannot silently be handed the load's arguments.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a job with no declared parameters is refused rather than given another job's")
    void aJobWithNoDeclaredParametersIsRefusedRatherThanGivenAnothers() {
        assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                "some-future-job", new String[] {"--root-extract=/r", "--child-extract=/c"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("some-future-job");
    }

    /**
     * The usage text names every published job, so a refusal tells an operator what to run instead.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the usage text names every published job")
    void theUsageTextNamesEveryPublishedJob() {
        String usage = MaintenanceTaskRunner.usage();
        List<String> missing = MaintenanceTaskRunner.JOB_NAMES.stream()
                .filter(job -> !usage.contains(job)).toList();

        assertThat(missing).as("the usage text must name every job it will accept").isEmpty();
    }

    /**
     * The purge accepts its three control values and publishes them under the names its task reads.
     *
     * <p>Purpose: the reference program takes an expiry threshold and two frequencies on its control card at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L98 to L108, so an operator must be able to
     * state all three. Before this they were unreachable: the task passed the three published defaults
     * unconditionally, which fixed the expiry at five days and made the job's own ceilings and its
     * documented refusal of a zero threshold impossible to provoke from any runtime entry point.</p>
     *
     * <p>Assumptions: each is asserted to be ABSENT when omitted rather than present with a default,
     * because the default belongs to the purge, which publishes it, and a copy of it in the runner would be
     * a second place to change it.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the purge accepts its expiry threshold and both frequencies, and omits what is unstated")
    void thePurgeAcceptsItsThreeControlValues() {
        Map<String, String> stated = MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.PURGE_JOB, new String[] {
                    "--business-date=2022-07-18", "--expiry-days=30",
                    "--checkpoint-frequency=250", "--progress-log-frequency=4"});

        assertThat(stated).containsExactly(
                Map.entry(MaintenanceTaskRunner.BUSINESS_DATE_PARAMETER, "2022-07-18"),
                Map.entry(MaintenanceTaskRunner.EXPIRY_DAYS_PARAMETER, "30"),
                Map.entry(MaintenanceTaskRunner.CHECKPOINT_FREQUENCY_PARAMETER, "250"),
                Map.entry(MaintenanceTaskRunner.PROGRESS_LOG_FREQUENCY_PARAMETER, "4"));

        Map<String, String> omitted = MaintenanceTaskRunner.taskParameters(
                MaintenanceTaskRunner.PURGE_JOB, new String[] {"--business-date=2022-07-18"});
        assertThat(omitted).doesNotContainKeys(MaintenanceTaskRunner.EXPIRY_DAYS_PARAMETER,
                MaintenanceTaskRunner.CHECKPOINT_FREQUENCY_PARAMETER,
                MaintenanceTaskRunner.PROGRESS_LOG_FREQUENCY_PARAMETER);
    }

    /**
     * A control value that is not a positive whole number is refused with the option named.
     *
     * <p>Assumptions: the refusal happens in the runner, before any container starts, so an operator sees
     * the option they must correct rather than a job failure. The RANGE is deliberately not tested here --
     * the ceilings are properties of the reference card's field widths and are enforced by the purge's own
     * parameter type, so a value inside {@code int} but above a ceiling is that type's refusal to make.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a non-numeric, zero, negative or empty control value is refused by option name")
    void aMalformedControlValueIsRefusedByOptionName() {
        for (String malformed : List.of("--expiry-days=", "--expiry-days=abc", "--expiry-days=0",
                "--expiry-days=-1", "--checkpoint-frequency=0", "--progress-log-frequency=x")) {
            assertThatThrownBy(() -> MaintenanceTaskRunner.taskParameters(
                    MaintenanceTaskRunner.PURGE_JOB,
                    new String[] {"--business-date=2022-07-18", malformed}))
                    .as("%s must be refused", malformed)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(malformed.substring(0, malformed.indexOf('=') + 1));
        }
    }

    /**
     * The usage text names all three purge control options, so an operator can discover them.
     *
     * <p>Assumptions: the ceilings are asserted to appear as well as the option names. An option published
     * without its admitted range leaves an operator to discover the bound by being refused, and both bounds
     * are facts about the reference card's field widths rather than local policy.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the usage text names the three purge control options and their ranges")
    void theUsageTextNamesTheThreePurgeControlOptions() {
        String usage = MaintenanceTaskRunner.usage();

        assertThat(usage)
                .contains(MaintenanceTaskRunner.EXPIRY_DAYS_OPTION)
                .contains(MaintenanceTaskRunner.CHECKPOINT_FREQUENCY_OPTION)
                .contains(MaintenanceTaskRunner.PROGRESS_LOG_FREQUENCY_OPTION)
                .contains(String.valueOf(PurgeJob.MAX_EXPIRY_DAYS))
                .contains(String.valueOf(PurgeJob.MAX_CARD_FREQUENCY));
    }

    /**
     * Reads the {@link Component} bean name a task class declares, from its source.
     *
     * <p>Assumptions: the name is read from the source rather than by reflection on the annotation, so the
     * assertion covers the LITERAL a maintainer edits. A reflective read would resolve a constant
     * reference and would therefore pass even if the constant and the runner's published list had drifted
     * apart, which is the drift being guarded against.</p>
     *
     * @param source the task class source file; must not be {@code null}
     * @return the bean name the class publishes, or an empty string when it declares none
     * @throws IllegalStateException if the source cannot be read
     */
    private static String componentNameOf(Path source) {
        String text;
        try {
            text = Files.readString(source);
        } catch (IOException unreadable) {
            throw new IllegalStateException("could not read " + source, unreadable);
        }
        if (text.contains("@Component(MaintenanceTaskRunner.LOAD_JOB)")) {
            return MaintenanceTaskRunner.LOAD_JOB;
        }
        if (text.contains("@Component(MaintenanceTaskRunner.UNLOAD_JOB)")) {
            return MaintenanceTaskRunner.UNLOAD_JOB;
        }
        if (text.contains("@Component(MaintenanceTaskRunner.PURGE_JOB)")) {
            return MaintenanceTaskRunner.PURGE_JOB;
        }
        return "";
    }
}
