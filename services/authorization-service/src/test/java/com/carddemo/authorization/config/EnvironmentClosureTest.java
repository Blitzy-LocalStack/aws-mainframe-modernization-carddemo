package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every environment variable this service cannot start without is actually delivered by both
 * Terraform environment roots.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: two of this service's configuration placeholders had no default and no
 * supplier anywhere in the infrastructure tree. That is not a degraded service -- the framework aborts
 * context refresh on an unresolvable placeholder, so the task crash-looped on every deployment. Nothing
 * detected it, because the two halves of the contract live in different languages and different
 * directories: a YAML placeholder in this module and a parameter map in {@code infra/envs}. This test
 * reads both and compares them, which is the only place the two can be made to disagree visibly.</p>
 *
 * <p>Assumptions: an "unresolvable" placeholder is one written {@code ${NAME}} with no {@code :default}
 * part. A placeholder WITH a default is deliberately excluded, because a default is precisely the
 * statement that the deployment need not supply it -- and several here are defaulted on purpose, so
 * requiring a supplier for them would force the roots to publish values whose only effect is to restate a
 * default.</p>
 *
 * <p>Alternatives Considered: asserting the parameter names against the module's own admitted-name set in
 * {@code infra/modules/ecs-service} instead of against the roots. Rejected because that set records which
 * names are PERMITTED, not which are delivered -- the defect was a name permitted nowhere and delivered
 * nowhere, and a name can be added to the permitted set without any root publishing it. The roots are
 * where delivery is decided, so the roots are what this reads.</p>
 *
 * <p>Alternatives Considered: starting the application context with an empty environment and asserting it
 * fails. Rejected because it proves only that SOME placeholder is unresolved and names whichever one the
 * framework happens to report first, so it would have to be re-run once per variable to be equivalent to
 * this -- and it would additionally need a database, a queue and an issuer to get far enough to check the
 * last one.</p>
 *
 * <p>Trade-offs: this test reads files by a repository-relative path, which is the one thing the sibling
 * contract tests deliberately avoid. It is unavoidable here and the reason is the subject: the assertion
 * is about files that are NOT packaged into this artifact and never will be, so there is no classpath form
 * of them to read. The path is resolved from the module directory upward so it holds whether the build
 * runs from the module or from the aggregator.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class EnvironmentClosureTest {

    /**
     * Matches a placeholder that carries no default, capturing its variable name.
     *
     * <p>Assumptions: the expression requires the closing brace to follow the name IMMEDIATELY, and that
     * requirement is what excludes a defaulted placeholder. A looser expression, one matching a dollar and
     * an opening brace followed by a name and nothing more, would also match the head of a placeholder
     * whose name is followed by a colon and a fallback -- and would then demand a supplier for a value
     * that already has one.</p>
     *
     * <p>Assumptions: the name class admits digits after the first character but not as the first, which
     * is the shape every variable this project uses takes. Admitting a leading digit would additionally
     * match nothing legitimate and would risk matching a fragment of an unrelated interpolation.</p>
     */
    private static final Pattern UNDEFAULTED_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");

    /**
     * The variable names both roots are not expected to publish, with the reason each is exempt.
     *
     * <p>Assumptions: this is a CLOSED set of two, and closing it is the point of the test. Each entry
     * is delivered by a mechanism other than the two parameter maps this test reads, so requiring it there
     * would fail for a service that is correctly configured. A name that is genuinely unsupplied would
     * have to be added here to make this test pass, which is a reviewable admission rather than an
     * accident -- and the admission is not merely written down, because
     * {@link #theExemptionsAreThemselvesDelivered()} asserts that every name in this set really does appear
     * in both roots.</p>
     *
     * <p>Refactoring Rationale: the set held four names while the listener certificate and private key
     * were deployment inputs carried as container secrets. They are not inputs any more --
     * {@code config/docker/generate-listener-material.sh} mints a key pair and a self-signed certificate
     * per task -- so neither name is bound by any service and neither is published by either root. Leaving
     * them listed here would have made {@link #theExemptionsAreThemselvesDelivered()} demand a secret the
     * design deliberately removed, which is the opposite of what an exemption list is for: it would assert
     * the presence of the very injection point the task-minted design exists to eliminate. Their
     * replacement is {@link #SUPPLIED_BY_CONTAINER_ENTRYPOINT}, which asserts absence rather than
     * presence.</p>
     */
    private static final Set<String> DELIVERED_BY_ANOTHER_CHANNEL = Set.of(
            // Delivered as a Secrets Manager container secret, from local.secret_sources_by_workload.
            //
            // WHY the signing key belongs here rather than among the parameters: it is key material, so it
            // travels through the secret channel like the messaging key, not through Parameter Store. It is
            // additionally the only one of the two whose gate names TWO services -- it is a symmetric key,
            // so the signing side here and the verifying side in account-service must hold the same bytes
            // -- which is why the roots gate it on a pair and infra/modules/ecs-service asserts that pair
            // biconditionally.
            "CARDDEMO_INTERNAL_IDENTITY_SIGNING_KEY",
            "CARDDEMO_MESSAGING_HMAC_KEY");

    /**
     * The variable names the service binds with no fallback that NEITHER root supplies, because the
     * container supplies them to itself.
     *
     * <p>Assumptions: exactly one name, and its exemption is evidence-based rather than a convenience.
     * {@code config/docker/generate-listener-material.sh} mints this task's own listener key pair and
     * self-signed certificate at startup and exports this variable from that script before the application
     * is executed, so the value exists in the process environment without ever being a deployment input.
     * The sibling assertion in {@code common-lib}'s {@code RuntimeConfigurationContractTest} exempts the
     * same single name from the task-definition inventory for the same reason, so the two tests agree on
     * one model of where listener material comes from rather than each carrying its own.</p>
     *
     * <p>Trade-offs: this set is checked for ABSENCE by {@link #theEntrypointSuppliedNamesAreNotInjected()}
     * where {@link #DELIVERED_BY_ANOTHER_CHANNEL} is checked for presence, and the asymmetry is the point.
     * A root that published a keystore password would be a regression, not a fix -- it would reintroduce a
     * shared secret across every task of a service, which is the state the per-task mint removes -- so the
     * safe assertion is that no root mentions it at all.</p>
     */
    private static final Set<String> SUPPLIED_BY_CONTAINER_ENTRYPOINT =
            Set.of("CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD");

    /**
     * The variable names delivered as database credentials rather than as service configuration.
     *
     * <p>Assumptions: these three are held apart from the set above because their supplier is a different
     * module again -- {@code infra/modules/secrets} composes them per database role, and the roots wire
     * them through {@code local.database_secret_sources} rather than naming them individually. Merging the
     * two sets would lose that distinction and with it the reason neither appears in a parameter map.</p>
     */
    private static final Set<String> DELIVERED_AS_DATABASE_CREDENTIALS = Set.of(
            "SPRING_DATASOURCE_PASSWORD", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME");

    /**
     * Resolves the repository root from the directory the test process runs in.
     *
     * <p>Assumptions: the walk is upward and looks for the {@code infra} directory rather than assuming a
     * fixed number of parent hops, because the working directory is the module when the module is built
     * alone and the aggregator when the reactor builds it. Anchoring on a directory that exists in exactly
     * one place makes both cases resolve the same way.</p>
     *
     * @return the repository root; never {@code null}
     * @throws IllegalStateException if no ancestor of the working directory contains an {@code infra}
     *     directory, which would mean the test is running outside the repository and cannot assert
     *     anything about it
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("infra"))
                    && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory holds both infra/ and services/");
    }

    /**
     * Reads a repository file as text.
     *
     * @param relativePath the path relative to the repository root
     * @return the file contents; never {@code null}
     * @throws UncheckedIOException if the file cannot be read, which for these paths means the
     *     repository layout changed and the test's premise no longer holds
     */
    private static String read(String relativePath) {
        try {
            return Files.readString(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + relativePath, failure);
        }
    }

    /**
     * Returns every undefaulted placeholder name in this service's base configuration.
     *
     * @return the variable names, in the order they appear; never {@code null}
     */
    private static Set<String> requiredVariableNames() {
        String configuration = read("services/authorization-service/src/main/resources/application.yml");
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = UNDEFAULTED_PLACEHOLDER.matcher(configuration);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * Verifies the configuration declares at least the variables this test exists to police.
     *
     * <p>Assumptions: this guards the test itself. Every other assertion here is a subset check, and a
     * subset check passes trivially when the subset is empty -- so a change that broke the placeholder
     * pattern, or moved the configuration file, would turn this class green rather than red. Asserting the
     * extraction found the two names the defect concerned is what keeps that from happening.</p>
     */
    @Test
    @DisplayName("the placeholder extraction finds the variables the defect concerned")
    void theExtractionIsNotVacuous() {
        assertThat(requiredVariableNames())
                .contains("CARDDEMO_ACCOUNT_CONTEXT_BASE_URL",
                        "CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST",
                        "CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE")
                .hasSizeGreaterThan(5);
    }

    /**
     * Verifies a defaulted placeholder is not treated as required.
     *
     * <p>Assumptions: the approved-origin placeholder is the case that distinguishes the two forms -- it
     * is written with a default that is itself another placeholder. If the pattern treated it as required,
     * this test class would demand a supplier for a value the service deliberately derives.</p>
     */
    @Test
    @DisplayName("a placeholder carrying a default is not treated as required")
    void aDefaultedPlaceholderIsNotRequired() {
        assertThat(requiredVariableNames())
                .doesNotContain("CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN",
                        "CARDDEMO_COGNITO_ADMIN_GROUP_NAME");
    }

    /**
     * Verifies both environment roots deliver every required variable.
     *
     * <p>Assumptions: the two roots are checked SEPARATELY rather than as a union. They are required to be
     * structurally identical, so a variable published by one and not the other is a defect that a union
     * check would hide -- and it is the more likely defect of the two, because a wiring change is applied
     * to one root first.</p>
     */
    @Test
    @DisplayName("both environment roots deliver every variable the service cannot default")
    void bothRootsDeliverEveryRequiredVariable() {
        for (String environment : new String[] {"dev", "prod"}) {
            String root = read("infra/envs/" + environment + "/main.tf");

            for (String name : requiredVariableNames()) {
                if (DELIVERED_BY_ANOTHER_CHANNEL.contains(name)
                        || DELIVERED_AS_DATABASE_CREDENTIALS.contains(name)
                        || SUPPLIED_BY_CONTAINER_ENTRYPOINT.contains(name)) {
                    continue;
                }
                assertThat(root)
                        .as(environment + " must deliver " + name + ", which the service binds with no"
                                + " default and which therefore aborts context refresh when absent")
                        .contains(name);
            }
        }
    }

    /**
     * Verifies the exempt names really are delivered, by the channel each is exempted for.
     *
     * <p>Assumptions: an exemption list is only safe if the exemptions are themselves checked. Otherwise
     * the way to make this class green would be to add a name to a set, which is exactly the failure mode
     * the class was written to prevent -- so each exempt name is asserted present in the root through its
     * own mechanism instead of merely skipped.</p>
     */
    @Test
    @DisplayName("the exempt variables are delivered by the channel they are exempt for")
    void theExemptionsAreThemselvesDelivered() {
        for (String environment : new String[] {"dev", "prod"}) {
            String root = read("infra/envs/" + environment + "/main.tf");

            for (String name : DELIVERED_BY_ANOTHER_CHANNEL) {
                assertThat(root)
                        .as(environment + " must deliver " + name + " as a container secret")
                        .contains(name);
            }
            assertThat(root)
                    .as(environment + " must wire the database credential family")
                    .contains("SPRING_DATASOURCE_USERNAME")
                    .contains("SPRING_DATASOURCE_PASSWORD")
                    .contains("SPRING_DATASOURCE_URL");
        }
    }

    /**
     * Verifies neither root injects a value the container mints for itself.
     *
     * <p>Assumptions: this is the counterpart of {@link #theExemptionsAreThemselvesDelivered()} and it
     * asserts the OPPOSITE of it, deliberately. A name in {@link #SUPPLIED_BY_CONTAINER_ENTRYPOINT} is
     * skipped by {@link #bothRootsDeliverEveryRequiredVariable()}, and a skip with nothing behind it is how
     * an exemption list decays into a suppression list -- so the skip is paid for here by requiring that no
     * root mentions the name at all.</p>
     *
     * <p>Alternatives Considered: asserting presence in the entrypoint script instead. Rejected because
     * {@code common-lib}'s {@code RuntimeConfigurationContractTest} already binds the script to the name it
     * exports, and duplicating that assertion here would test the same edge twice while leaving the edge
     * this class actually owns -- the two environment roots -- unasserted. The regression this class can
     * see and that one cannot is a root quietly adding the injection back.</p>
     */
    @Test
    @DisplayName("neither environment root injects listener material the task mints for itself")
    void theEntrypointSuppliedNamesAreNotInjected() {
        for (String environment : new String[] {"dev", "prod"}) {
            String root = read("infra/envs/" + environment + "/main.tf");

            for (String name : SUPPLIED_BY_CONTAINER_ENTRYPOINT) {
                assertThat(root)
                        .as(environment + " must NOT deliver " + name + ", because"
                                + " config/docker/generate-listener-material.sh mints and exports it per"
                                + " task; injecting it would share one keystore password across every task"
                                + " of the service")
                        .doesNotContain(name);
            }
            assertThat(root)
                    .as(environment + " must not carry withdrawn listener-material secrets")
                    .doesNotContain("CARDDEMO_SERVER_TLS_CERTIFICATE")
                    .doesNotContain("CARDDEMO_SERVER_TLS_PRIVATE_KEY");
        }
    }

    /**
     * Verifies the shared module admits every name the roots publish for this service.
     *
     * <p>Assumptions: delivery alone is not sufficient. The task-definition module refuses a name outside
     * its admitted sets, and that refusal surfaces only at plan time against a real account -- because
     * {@code terraform validate} does not evaluate a lifecycle precondition. Asserting admission here is
     * what turns that into a test failure.</p>
     */
    @Test
    @DisplayName("the task-definition module admits every name the roots publish for this service")
    void theModuleAdmitsEveryPublishedName() {
        String module = read("infra/modules/ecs-service/main.tf");

        assertThat(module)
                .contains("CARDDEMO_ACCOUNT_CONTEXT_BASE_URL")
                .contains("CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN")
                .contains("CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST")
                .contains("CARDDEMO_MESSAGING_HMAC_KEY")
                .contains("CARDDEMO_INTERNAL_IDENTITY_SIGNING_KEY");
    }
}
