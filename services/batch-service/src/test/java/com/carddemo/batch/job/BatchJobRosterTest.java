package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>Assumptions: the two tokens with no landed job are declared here as an explicit, enumerated set
 * rather than left to be inferred from a failure. That is the whole point of the arrangement: the gap is
 * stated, it is small, and it cannot widen without this file changing -- so a later reader finds a
 * measured statement of what is missing instead of discovering it from a production incident.</p>
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
            CombineTransactionsJob.class);

    /**
     * The tokens the orchestration vocabulary declares for which no job has landed in this module.
     *
     * <p>Assumptions: export and import are the two, and the reason is concrete rather than a matter of
     * sequencing. Both re-express programs that read the customer and card masters --
     * {@code app/jcl/CBEXPORT.jcl:49-57} names five input data definitions, two of which are those
     * masters -- and this module holds no customer entity and no card entity, because the account and card
     * contexts own them. Landing the pair would mean adding two entities and two repositories to this
     * module's closed sets for a job that stands outside the nightly chain. There is also no oracle to
     * verify a migration of them against: the existing suite records at {@code tests/README.md:53-69} that
     * {@code CBEXPORT} and {@code CBIMPORT} do not compile under the open-source compiler at all, so no
     * golden master exists for either.</p>
     *
     * <p>Assumptions: the tokens are NOT removed from the orchestration vocabulary to close the gap. The
     * vocabulary is an external contract -- the state machine names states by these tokens -- so removing
     * one would change a contract outside this repository's Java sources in order to make a test pass.</p>
     */
    private static final Set<BatchJobName> NOT_YET_LANDED =
            EnumSet.of(BatchJobName.EXPORT, BatchJobName.IMPORT);

    /**
     * Every advertised token either has a landed job or is one of the two declared as not yet landed.
     *
     * <p>Assumptions: the assertion is made in BOTH directions. One direction catches a token advertised
     * with nothing behind it; the other catches a job that has landed while still being declared missing,
     * which would leave this file telling a reader that a capability is absent when it is present.</p>
     */
    @Test
    @DisplayName("account for every advertised job token exactly once")
    void everyAdvertisedTokenIsAccountedFor() {
        Set<String> landed = landedJobNames();
        Set<String> declaredMissing = new TreeSet<>();
        NOT_YET_LANDED.forEach(token -> declaredMissing.add(token.token()));

        Set<String> advertised = new TreeSet<>();
        Arrays.stream(BatchJobName.values()).forEach(token -> advertised.add(token.token()));

        Set<String> accountedFor = new TreeSet<>(landed);
        accountedFor.addAll(declaredMissing);

        assertThat(accountedFor)
                .as("every advertised token must either have a landed job or be declared missing")
                .containsExactlyInAnyOrderElementsOf(advertised);
        assertThat(landed)
                .as("a token declared missing must not also have a landed job")
                .doesNotContainAnyElementsOf(declaredMissing);
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
                CombineTransactionsJob.STEP_NAME));

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
     * @param configuration the class to read; must not be {@code null}
     * @return the value of its {@code JOB_NAME} constant, never {@code null}
     * @throws IllegalStateException if the class declares no readable {@code JOB_NAME} constant, which
     *     would mean it registers under a name this roster cannot see
     */
    private static String jobNameConstantOf(Class<?> configuration) {
        try {
            Object value = configuration.getField("JOB_NAME").get(null);
            return String.valueOf(value);
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException(configuration.getName()
                    + " must declare a public static JOB_NAME constant, because that constant is what"
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
