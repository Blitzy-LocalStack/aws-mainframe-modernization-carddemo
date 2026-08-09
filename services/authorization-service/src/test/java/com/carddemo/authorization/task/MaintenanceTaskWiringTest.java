package com.carddemo.authorization.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
     * Both task classes implement the contract the runner resolves by.
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("both task classes implement the runner's contract")
    void bothTaskClassesImplementTheRunnersContract() {
        assertThat(AuthorizationTask.class)
                .as("the load task is resolvable through the contract the runner asks the context for")
                .isAssignableFrom(LoadAuthorizationsTask.class);
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
        if (text.contains("@Component(MaintenanceTaskRunner.PURGE_JOB)")) {
            return MaintenanceTaskRunner.PURGE_JOB;
        }
        return "";
    }
}
