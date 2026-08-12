package com.carddemo.batch.job;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.dto.BatchJobName;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.context.annotation.Bean;
import static org.assertj.core.api.Assertions.assertThat;


/**
 * Pins that the set of job names this module ADVERTISES equals the set it can actually RUN.
 *
 * <p>Purpose: {@code BatchApplication} accepts a job token on the command line, validates it against a
 * declared list, and only then looks a bean up in the registry. Those are two independent statements of
 * what this module can run, and nothing but this test makes them agree. A token accepted by the argument
 * check and absent from the registry produces a container that starts, passes its argument validation and
 * then fails inside the state machine with an unresolved-job error -- which is a deployment-time failure
 * for something that was decidable at build time.</p>
 *
 * <p>Assumptions: the registered set is discovered by REFLECTION over the job package's configuration
 * classes rather than by starting an application context. A context start would need a data source, a
 * migrated schema in four schemas this module does not own, and a job repository, none of which settles
 * anything this test asserts: what is asserted is that a bean method returning a job exists for each
 * token, and a bean method is visible without instantiating it. The tier that proves the beans actually
 * wire is the module's integration tier, which runs against a database.</p>
 *
 * <p>Refactoring Rationale: <b>there is no longer a set of unlanded tokens, and there used to be one.</b>
 * This file declared export and import as not-yet-landed and its list of configuration classes named only
 * the other five -- so when both classes landed, the assertion never inspected either file and the stale
 * declaration kept passing. The gap the arrangement was meant to make un-widenable was therefore also
 * un-closable without editing this file. Both are now in the configuration list, the declared-missing set
 * is empty, and the both-directions assertion below is what keeps the two statements honest: a token with
 * nothing behind it fails, and a job that lands while still being declared missing fails too.</p>
 */
@DisplayName("the batch job roster")
class BatchJobRosterTest {

    /**
     * The configuration classes in this package that each declare exactly one job bean.
     *
     * <p>Assumptions: the classes are named rather than discovered by scanning the package, because a
     * scan would find whatever is there and this test's purpose is to compare what is there against what
     * is advertised. A scan-based version of this test would pass by construction.</p>
     */
    private static final List<Class<?>> JOB_CONFIGURATIONS = List.of(
            PreflightDailyTransactionsJob.class,
            PostTransactionsJob.class,
            CalculateInterestJob.class,
            BackupTransactionsJob.class,
            CombineTransactionsJob.class,
            ExportJob.class,
            ImportJob.class);

    /**
     * The tokens the landed jobs register under are exactly the tokens the vocabulary advertises.
     *
     * <p>Refactoring Rationale: this set is now EMPTY and it used to hold export and import. Both have
     * landed -- each has a file in the job package, each registers exactly one job bean under its own
     * token, and both are named in {@link #JOB_CONFIGURATIONS} above -- so declaring them missing would be
     * this file telling a reader that a capability is absent while it is present. The set is kept rather
     * than deleted so that the next genuine gap is stated in one place and asserted in both directions,
     * which is the arrangement that failed silently while it was populated and the list below was not.</p>
     *
     * <p>Assumptions: an empty set makes the both-directions assertion strictly stronger, not weaker.
     * Every advertised token must now have a landed job, with nothing exempt.</p>
     */
    private static final Set<BatchJobName> NOT_YET_LANDED = EnumSet.noneOf(BatchJobName.class);

    /**
     * Every advertised token has a landed job, with nothing declared as not yet landed.
     *
     * <p>Assumptions: the exemption set is asserted empty rather than subtracted. While it was populated
     * this file could tell a reader that a capability was absent when it was present; with it empty the
     * membership assertion above stands unqualified, which is the stronger of the two statements.</p>
     */
    @Test
    @DisplayName("account for every advertised job token exactly once")
    void everyAdvertisedTokenIsAccountedFor() {
        Set<String> advertised = new TreeSet<>();
        Arrays.stream(BatchJobName.values()).forEach(token -> advertised.add(token.token()));

        assertThat(landedJobNames())
                .as("every advertised token must have a landed job, and no landed job may sit outside"
                        + " the advertised vocabulary")
                .containsExactlyInAnyOrderElementsOf(advertised);
        // WHY : Assumptions: ONE direction is asserted and that is now the stronger statement. The
        //       declared-missing set below is empty, so every advertised token must have a landed job
        //       with nothing exempt; a second assertion excluding an empty set would assert nothing at
        //       all, and AssertJ refuses an empty exclusion rather than passing vacuously.
        assertThat(NOT_YET_LANDED)
                .as("nothing is declared missing, so the assertion above admits no exemption")
                .isEmpty();
    }

    /**
     * The command-line token list and the orchestration vocabulary hold the same tokens in the same order.
     *
     * <p>Assumptions: ORDER is asserted as well as membership, because the entry point prints the list in
     * order as its usage text. Two lists holding the same tokens in different orders would leave the usage
     * text and the enumeration disagreeing about the sequence an operator reads them in, which is the one
     * observable difference between them.</p>
     */
    @Test
    @DisplayName("advertise the same tokens the orchestration vocabulary declares")
    void commandLineTokensMatchTheVocabulary() {
        List<String> vocabulary = Arrays.stream(BatchJobName.values())
                .map(BatchJobName::token)
                .toList();

        assertThat(BatchApplication.JOB_NAMES).containsExactlyElementsOf(vocabulary);
    }

    /**
     * Each landed job registers under its own token and no two register under the same one.
     *
     * <p>Assumptions: distinctness is asserted separately from membership. Two configuration classes that
     * both named one token would produce a registry in which one job shadowed the other, and a
     * membership-only assertion would report the roster as complete while one state of the chain ran the
     * wrong job.</p>
     */
    @Test
    @DisplayName("register each landed job under a distinct token")
    void landedJobsRegisterUnderDistinctTokens() {
        assertThat(landedJobNames()).hasSize(JOB_CONFIGURATIONS.size());
    }

    /**
     * Every landed job declares its step name distinctly, so the durable ledger cannot conflate two steps.
     *
     * <p>Assumptions: the step names matter as much as the job names, because the durable step ledger keys
     * on the run identifier paired with the STEP name. Two jobs sharing a step name would make the second
     * one to run in a given execution report as already complete and skip its work entirely -- a silent
     * omission rather than a failure, which is why it is asserted here rather than left to be noticed.</p>
     */
    @Test
    @DisplayName("declare a distinct ledger step name per job")
    void ledgerStepNamesAreDistinct() {
        Set<String> stepNames = new TreeSet<>(List.of(
                PreflightDailyTransactionsJob.STEP_NAME,
                PostTransactionsJob.STEP_NAME,
                CalculateInterestJob.STEP_NAME,
                BackupTransactionsJob.STEP_NAME,
                CombineTransactionsJob.STEP_NAME,
                ExportJob.STEP_NAME,
                ImportJob.STEP_NAME));

        assertThat(stepNames).hasSize(JOB_CONFIGURATIONS.size());
    }

    /**
     * Reads the tokens the landed configuration classes register their job beans under.
     *
     * <p>Assumptions: the token is read from each class's own {@code JOB_NAME} constant rather than from
     * the bean method's name, because the bean method name is a Spring bean identifier while the job's
     * registered name is what the entry point matches against. The two are deliberately different --
     * one is camel case and the other hyphenated -- so reading the wrong one would compare the entry
     * point's tokens against a vocabulary it never uses.</p>
     *
     * @return the tokens, one per landed configuration class, never {@code null}
     */
    private static Set<String> landedJobNames() {
        Set<String> names = new TreeSet<>();
        for (Class<?> configuration : JOB_CONFIGURATIONS) {
            assertThat(declaresOneJobBean(configuration))
                    .as("%s must declare exactly one job bean method", configuration.getSimpleName())
                    .isTrue();
            names.add(jobNameConstantOf(configuration));
        }
        return names;
    }

    /**
     * Reports whether a configuration class declares exactly one bean method returning a job.
     *
     * @param configuration the class to inspect; must not be {@code null}
     * @return {@code true} when exactly one such method is declared
     */
    private static boolean declaresOneJobBean(Class<?> configuration) {
        long jobBeans = Arrays.stream(configuration.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> Job.class.equals(method.getReturnType()))
                .count();
        return jobBeans == 1L;
    }

    /**
     * Reads a configuration class's declared job-name constant.
     *
     * <p>Assumptions: the field is read with {@code getDeclaredField} rather than {@code getField},
     * because two of the seven job classes declare the constant package-private and {@code getField}
     * sees public members only. This test sits in the same package as the classes it reads, so a
     * package-private constant is legitimately accessible to it, and requiring the constant to be public
     * purely so a sibling test could read it would widen a class's API for a test's convenience.</p>
     *
     * @param configuration the class to read; must not be {@code null}
     * @return the value of its {@code JOB_NAME} constant, never {@code null}
     * @throws IllegalStateException if the class declares no readable {@code JOB_NAME} constant, which
     *     would mean it registers under a name this roster cannot see
     */
    private static String jobNameConstantOf(Class<?> configuration) {
        try {
            Object value = configuration.getDeclaredField("JOB_NAME").get(null);
            return String.valueOf(value);
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException(configuration.getName()
                    + " must declare a static JOB_NAME constant, because that constant is what"
                    + " this roster compares against the orchestration vocabulary", unreadable);
        }
    }

    /**
     * Guards that the reflective bean-method lookup this test relies on still finds something.
     *
     * <p>Assumptions: this case exists because every other case here would pass vacuously if the
     * reflective lookup silently found nothing -- an empty landed set is a subset of everything. Asserting
     * that at least one method was found is what keeps the rest of the file meaningful.</p>
     */
    @Test
    @DisplayName("find bean methods reflectively rather than vacuously")
    void reflectiveLookupFindsBeanMethods() {
        Method[] declared = PostTransactionsJob.class.getDeclaredMethods();

        assertThat(declared).isNotEmpty();
        assertThat(declaresOneJobBean(PostTransactionsJob.class)).isTrue();
    }
}
