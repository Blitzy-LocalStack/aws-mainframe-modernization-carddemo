package com.carddemo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the renewal exchange this service performs is the one the provisioned pool client permits.
 *
 * <h2>What this class exists to catch</h2>
 *
 * <p>Purpose: the pool client is provisioned with refresh-token ROTATION enabled, and rotation is
 * incompatible with the legacy refresh authentication flow -- the module that provisions the client
 * REFUSES to name that flow while rotation is on. The service must therefore renew sessions with the
 * dedicated rotation-compatible operation. Nothing in either tree can see the other, so the two agreed
 * only by intention: the infrastructure enabled rotation and forbade the legacy flow, and the service went
 * on calling the generic authentication operation with the legacy flow selector. The pool refused every
 * renewal, which the service reported as a refused session, so a signed-on operator was returned to the
 * sign-on screen exactly one access-token lifetime after signing on -- and no test on either side of the
 * seam failed, because each side was internally consistent.</p>
 *
 * <p>Assumptions: the assertions run in BOTH directions across the seam, which is what makes this a
 * contract test rather than two coincidental checks. From the infrastructure it reads that rotation is
 * enabled and that the legacy flow is excluded and cannot be reintroduced; from the service source it
 * reads that the rotation-compatible operation is called and that the legacy selector appears nowhere. A
 * change to either side alone fails here.</p>
 *
 * <p>Assumptions: the infrastructure is read as TEXT rather than by running Terraform, because the
 * property is a declaration in committed source and a runner able to plan against a real account is not
 * available to a unit test. What is given up is that this proves the declaration rather than the deployed
 * state; what it buys is that the seam is checked on every build. The deployed state is the concern of
 * `terraform plan` in the infrastructure pipeline, which reads the same declarations.</p>
 *
 * <p>Assumptions: both environment roots are read, not just the development one. A root that passed the
 * legacy flow explicitly would override the module default and would leave the module's refusal untested
 * in the only place it matters, so each root is asserted to leave the input unset.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception clause.</p>
 */
class RefreshRotationContractTest {

    /** The module that provisions the pool client whose flows and rotation setting are read. */
    private static final String COGNITO_MODULE_MAIN = "infra/modules/cognito/main.tf";

    /** The module's input declarations, where the legacy flow is excluded and the exclusion validated. */
    private static final String COGNITO_MODULE_VARIABLES = "infra/modules/cognito/variables.tf";

    /** The service source whose renewal exchange is read back against those declarations. */
    private static final String IDENTITY_SERVICE_SOURCE =
            "services/auth-service/src/main/java/com/carddemo/auth/service/CognitoIdentityService.java";

    /** The environment roots that instantiate the module, both of which must leave the flows defaulted. */
    private static final List<String> ENVIRONMENT_ROOTS =
            List.of("infra/envs/dev/main.tf", "infra/envs/prod/main.tf");

    /** The flow name rotation forbids, which neither the module nor a root may name. */
    private static final String LEGACY_REFRESH_FLOW = "ALLOW_REFRESH_TOKEN_AUTH";

    /**
     * Asserts the provisioned client enables rotation, which is what makes the legacy flow unusable.
     *
     * <p>Assumptions: the retry grace period is asserted at zero as well as the feature at enabled,
     * because the two together are what make a rotated token MANDATORY rather than optional. A non-zero
     * grace period lets the submitted token stay current for a window, which is the configuration under
     * which the provider may answer a renewal with no replacement -- a case the client tolerates and the
     * service passes through, both documented at their own sites. Pinning zero here records which of the
     * two configurations is actually deployed.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the provisioned pool client enables refresh-token rotation with no grace period")
    void theProvisionedClientEnablesRotation() {
        String module = read(COGNITO_MODULE_MAIN);

        assertThat(module)
                .as("rotation is the premise of every other assertion in this class")
                .contains("RefreshTokenRotation")
                .contains("Feature                 = \"ENABLED\"")
                .contains("RetryGracePeriodSeconds = 0");
    }

    /**
     * Asserts the legacy refresh flow is excluded by default and cannot be reintroduced by an input.
     *
     * <p>Assumptions: the default and the validation are both asserted, because they fail differently. A
     * default that omitted the flow while no validation refused it would let any caller of the module
     * reinstate it silently; a validation that refused it while the default named it would make the module
     * unusable. Together they make the exclusion a property of the module rather than of its default
     * argument.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the module excludes the legacy refresh flow and refuses it as an input")
    void theModuleExcludesAndRefusesTheLegacyFlow() {
        String variables = read(COGNITO_MODULE_VARIABLES);

        assertThat(variables)
                .as("the default flow list must not carry the flow rotation forbids")
                .contains("default     = [\"ALLOW_USER_PASSWORD_AUTH\"]");
        assertThat(variables)
                .as("a caller must not be able to reinstate the legacy flow through the input")
                .contains("condition     = !contains(var.explicit_auth_flows, \""
                        + LEGACY_REFRESH_FLOW + "\")");
    }

    /**
     * Asserts neither environment root overrides the flow list, so the module's exclusion governs both.
     *
     * <p>Assumptions: the absence of the input is asserted rather than its value, because the module's
     * default is what carries the exclusion and a root that set the input at all would be asserting its own
     * list. The production root is read as well as the development one: it instantiates the same module,
     * and a divergence there would break the renewal in the environment where it matters most.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("neither environment root overrides the client's authentication flows")
    void neitherRootOverridesTheAuthenticationFlows() {
        for (String root : ENVIRONMENT_ROOTS) {
            String text = read(root);
            assertThat(text)
                    .as("%s must leave explicit_auth_flows at the module default that excludes the "
                            + "legacy flow", root)
                    .doesNotContain("explicit_auth_flows")
                    .doesNotContain(LEGACY_REFRESH_FLOW);
        }
    }

    /**
     * Asserts the service renews with the rotation-compatible operation and names the legacy flow nowhere.
     *
     * <p>Purpose: this is the half of the seam the infrastructure cannot check. The negative assertion
     * carries as much weight as the positive one: a service that called the dedicated operation for the
     * token and retained the legacy selector anywhere else would still be refused by the pool on that
     * second call, and the positive assertion alone cannot see it.</p>
     *
     * <p>Assumptions: the client secret is asserted present on the request too, because the provisioned
     * client is CONFIDENTIAL -- the module generates a secret for it -- and the rotation-compatible
     * operation takes that secret directly rather than a keyed digest over a user name. Omitting it is
     * refused by the pool at run time, which is exactly the class of failure this seam exists to move
     * forward into the build.</p>
     *
     * <p>Assumptions: the source is read as text rather than through reflection, because the property is
     * WHICH provider operation is invoked and with which members -- a fact of the call site, not of the
     * type's signature. The behavioural assertions on the same call live in
     * {@code com.carddemo.auth.service.CognitoIdentityServiceTest}; what is added here is the tie to the
     * infrastructure declarations above.</p>
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("the service renews with GetTokensFromRefreshToken and never the legacy flow")
    void theServiceRenewsWithTheRotationCompatibleOperation() {
        String source = read(IDENTITY_SERVICE_SOURCE);

        assertThat(source)
                .as("a rotation-enabled client permits only the dedicated renewal operation")
                .contains("GetTokensFromRefreshTokenRequest")
                .contains("provider.getTokensFromRefreshToken(");
        assertThat(source)
                .as("the confidential client's secret is a required member of that request")
                .contains(".clientSecret(clientSecret)");
        assertThat(executableLinesOf(source))
                .as("the legacy flow selector must appear in no executable line, in any spelling")
                .noneMatch(line -> line.contains("REFRESH_TOKEN_AUTH"));
    }

    /**
     * Returns the lines of a source file that are not comment lines.
     *
     * <p>Assumptions: comments are excluded because the negative assertion above would otherwise be
     * unsatisfiable by correct code. The service's own documentation NAMES the withdrawn flow, twice, in
     * the Refactoring Rationale blocks that record why it was withdrawn and where the infrastructure
     * refuses it -- and deleting that explanation to satisfy a text search would remove the one account of
     * the seam a future reader has. Filtering to executable lines keeps the assertion about the code.</p>
     *
     * <p>Alternatives Considered: asserting the two precise code spellings instead -- the enum constant and
     * the quoted parameter value -- which needs no filtering. Rejected because it enumerates the ways the
     * flow could be named and would miss a third: a constant assembled from a prefix, or the value reached
     * through a differently-named enum member. Excluding comments and refusing the substring anywhere else
     * is the form that does not depend on predicting the spelling.</p>
     *
     * <p>Assumptions: a line is treated as a comment when its first non-blank characters open or continue
     * one. This is deliberately a line filter and not a Java parser: a comment opened mid-line after code
     * would be retained, which is the safe direction to be wrong in -- it can only make the assertion
     * stricter, never blind.</p>
     *
     * @param source the file contents to filter; must not be {@code null}
     * @return the lines that are not comment lines, in order; never {@code null}
     */
    private static List<String> executableLinesOf(String source) {
        return source.lines()
                .filter(line -> {
                    String trimmed = line.stripLeading();
                    return !trimmed.startsWith("//")
                            && !trimmed.startsWith("/*")
                            && !trimmed.startsWith("*");
                })
                .toList();
    }

    /**
     * Resolves the repository root from the directory the test process runs in.
     *
     * <p>Assumptions: the walk is upward and looks for two directories rather than assuming a fixed number
     * of parent hops, because the working directory is the module when the module is built alone and the
     * aggregator when the reactor builds it. Anchoring on a pair that exists in exactly one place makes
     * both cases resolve the same way. The approach is the one
     * {@code com.carddemo.authorization.config.EnvironmentClosureTest} established for the same need.</p>
     *
     * @return the repository root; never {@code null}
     * @throws IllegalStateException if no ancestor of the working directory holds both directories, which
     *     would mean the test is running outside the repository and cannot assert anything about it
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
     * @param relativePath the path relative to the repository root; must not be {@code null}
     * @return the file contents; never {@code null}
     * @throws UncheckedIOException if the file cannot be read, which for these paths means the repository
     *     layout changed and this test's premise no longer holds
     */
    private static String read(String relativePath) {
        try {
            return Files.readString(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + relativePath, failure);
        }
    }
}
