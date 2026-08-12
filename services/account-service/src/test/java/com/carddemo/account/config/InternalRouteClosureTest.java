package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every operation this service's contract publishes is reachable through the load balancer.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: two of this service's three published cross-reference operations had no
 * matching listener rule. The account workload was forwarded on the single exact path
 * {@code /api/v1/card-xrefs/lookup} while {@code account-api.yaml} publishes {@code /lookup},
 * {@code /lookup-by-account} and {@code /search-by-account} beneath that prefix, so a call to either of the
 * other two was answered 404 by the listener itself. That failure is the hardest kind to find from either
 * side: the caller sees a not-found it cannot distinguish from a missing row, and the account service
 * records no request at all because none arrived. This test compares the two halves, which is the only
 * place they can be made to disagree visibly.</p>
 *
 * <p>Assumptions: the CONTRACT is the authority on which operations exist and the rule list is not. A rule
 * narrower than the contract silently withdraws an operation, which is exactly what happened, so the
 * assertion runs in that direction -- every published path must match some pattern. It deliberately does
 * NOT assert the converse: a pattern wider than the contract forwards an unpublished address to a service
 * that refuses it, which is a request answered by the component that owns the refusal rather than an
 * operation lost.</p>
 *
 * <p>Assumptions: matching is implemented against the listener's own glob semantics rather than against a
 * servlet path matcher. A load-balancer path pattern treats {@code *} as zero or more characters INCLUDING
 * the separator and {@code ?} as exactly one character, which is not what the servlet form means -- there,
 * a single star stops at a separator. Reusing the servlet matcher would therefore have reported
 * {@code /api/v1/customers/*} as not covering {@code /api/v1/customers/000000456/record}, which the
 * listener does cover, and the test would have failed on a route that works.</p>
 *
 * <p>Alternatives Considered: asserting the patterns against {@code SecurityConfig}'s own pattern constants
 * instead of against the environment roots. Rejected because those constants describe what the service
 * REFUSES once a request has arrived, and the defect was a request that never arrived. The two are
 * independent: the filter chain's {@code /api/v1/card-xrefs/**} rule was correct throughout while the
 * listener forwarded one path in three.</p>
 *
 * <p>Alternatives Considered: reading the rule list from the {@code alb} module rather than from the two
 * environment roots. Rejected because the module receives the list as a variable and never states it; the
 * roots are where the values are written, and they are written twice, so this test also pins that the two
 * roots agree -- a divergence there would mean an operation reachable in one environment and not the
 * other.</p>
 *
 * <p>Trade-offs: this test reads files by a repository-relative path, which the sibling contract tests
 * deliberately avoid. It is unavoidable here and the subject is the reason: the assertion is about a
 * Terraform root that is not packaged into this artifact and never will be, so there is no classpath form
 * of it to read. The path is resolved from the working directory upward so it holds whether the build runs
 * from this module or from the aggregator.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class InternalRouteClosureTest {

    /**
     * The contract whose published operation paths must all be reachable.
     */
    private static final String CONTRACT_PATH =
            "services/account-service/src/main/resources/openapi/account-api.yaml";

    /**
     * The workload key whose forwarding rule this test reads out of each environment root.
     */
    private static final String WORKLOAD_KEY = "account";

    /**
     * The greatest number of values a single listener path-pattern condition accepts.
     *
     * <p>Assumptions: this is the load balancer's own limit and not a project convention, which is why it
     * is asserted rather than merely respected. The account rule is AT it, so the next pattern of any kind
     * requires splitting the rule into a second one with its own priority; a change that quietly added a
     * sixth value would be accepted by Terraform and refused by the service at apply time.</p>
     */
    private static final int PATH_PATTERN_VALUE_CEILING = 5;

    /**
     * Matches a top-level path key in the contract's {@code paths} mapping, capturing the path.
     *
     * <p>Assumptions: the key is recognised by its indentation -- exactly two spaces, which is the one
     * level beneath the document's {@code paths:} node -- so an operation identifier, a parameter name or a
     * path mentioned inside a description is not mistaken for a published path. The alternative, parsing
     * the document with a YAML reader, would pull a parser dependency into this module's test scope to
     * recover ten strings whose shape is fixed by the specification.</p>
     */
    private static final Pattern CONTRACT_PATH_KEY =
            Pattern.compile("(?m)^ {2}(/[^\\s:{}]*(?:\\{[A-Za-z]+}[^\\s:{}]*)*):\\s*$");

    /**
     * Matches a path template parameter, so a published template can be turned into a concrete address.
     */
    private static final Pattern TEMPLATE_PARAMETER = Pattern.compile("\\{[A-Za-z]+}");

    /**
     * The value a template parameter is replaced with before matching.
     *
     * <p>Assumptions: a digit run is used rather than a word because every template parameter on this
     * contract is an identifier the reference declares as a numeric display field, and because a value
     * carrying no separator is the case a listener pattern is most likely to get wrong.</p>
     */
    private static final String SAMPLE_PARAMETER_VALUE = "12345678901";

    /**
     * Verifies every path the contract publishes is matched by some pattern in both environment roots.
     */
    @Test
    @DisplayName("every published account-service path matches a listener rule in both environments")
    void everyPublishedPathIsForwardedByBothEnvironments() {
        Set<String> published = publishedPaths();

        // WHY : ⚠️ Refactoring Rationale: the four end-user addresses named here are the BODY-keyed ones,
        //   where this guard previously named two path-keyed templates -- an account view at
        //   /api/v1/accounts/{accountId}/view and a cross-reference list at
        //   /api/v1/accounts/{accountId}/card-cross-references. Both were withdrawn rather than aliased:
        //   the load balancer composes its access record from the request line before any application code
        //   runs, and this migration's sensitive-data contract names an account identifier among the values
        //   a durable diagnostic may not hold, so the key moved into the body and the address became fixed.
        //   A guard list left on the old templates would have gone on passing while naming operations the
        //   contract no longer publishes, which is the one thing a non-vacuity check must not do.
        assertThat(published)
                .as("the contract must publish the operations this test exists to police")
                .contains("/api/v1/card-xrefs/lookup",
                        "/api/v1/card-xrefs/lookup-by-account",
                        "/api/v1/card-xrefs/search-by-account",
                        "/api/v1/accounts/lookup",
                        "/api/v1/accounts/view",
                        "/api/v1/accounts/update",
                        "/api/v1/accounts/card-cross-references/search",
                        "/api/v1/customers",
                        "/api/v1/customers/lookup",
                        "/api/v1/customers/display",
                        "/api/v1/customers/record");

        for (String environment : List.of("dev", "prod")) {
            List<String> patterns = forwardedPatterns(environment);
            for (String path : published) {
                String address = TEMPLATE_PARAMETER.matcher(path)
                        .replaceAll(SAMPLE_PARAMETER_VALUE);
                assertThat(patterns)
                        .as("%s must forward %s, published by the contract as %s",
                                environment, address, path)
                        .anyMatch(pattern -> matchesListenerPattern(pattern, address));
            }
        }
    }

    /**
     * Verifies the two environment roots forward this service on identical patterns.
     *
     * <p>Assumptions: this is asserted separately from the closure above rather than folded into it,
     * because the two fail for different reasons and a reader needs to know which. Closure failing means an
     * operation is unreachable; equality failing means an operation is reachable in one environment and not
     * the other, which is the shape that lets a change pass every check in a development account and break
     * on promotion.</p>
     */
    @Test
    @DisplayName("both environment roots forward this service on the identical pattern list")
    void bothEnvironmentsForwardTheIdenticalPatternList() {
        assertThat(forwardedPatterns("prod"))
                .as("a pattern present in one root and absent from the other is an operation that "
                        + "works in one environment and not the other")
                .isEqualTo(forwardedPatterns("dev"));
    }

    /**
     * Verifies the forwarding rule stays inside the listener's own per-condition value limit.
     */
    @Test
    @DisplayName("the forwarding rule stays within the five values one path-pattern condition accepts")
    void theForwardingRuleStaysWithinTheConditionCeiling() {
        for (String environment : List.of("dev", "prod")) {
            assertThat(forwardedPatterns(environment))
                    .as("%s: a sixth value requires a second rule with its own priority, not a longer "
                            + "list", environment)
                    .hasSizeLessThanOrEqualTo(PATH_PATTERN_VALUE_CEILING);
        }
    }

    /**
     * Returns every operation path the contract publishes.
     *
     * @return the published paths in document order; never {@code null} and never empty
     */
    private static Set<String> publishedPaths() {
        String contract = read(CONTRACT_PATH);
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = CONTRACT_PATH_KEY.matcher(contract);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        assertThat(paths)
                .as("the contract must declare at least one path, or this test proves nothing")
                .isNotEmpty();
        return paths;
    }

    /**
     * Returns the listener path patterns one environment root forwards this service on.
     *
     * @param environment the environment directory name beneath {@code infra/envs}
     * @return the patterns in the order the root lists them; never {@code null} and never empty
     */
    private static List<String> forwardedPatterns(String environment) {
        String root = read("infra/envs/" + environment + "/main.tf");

        // WHY : Assumptions: the workload block is located by its key and then read to the first closing
        //       bracket of its paths list, rather than by matching the whole block. A whole-block match
        //       would have to model the nested braces of a Terraform map, which is exactly the parsing this
        //       test has no business doing; locating one key and reading one bracketed list does not.
        int workloadAt = root.indexOf("\n    " + WORKLOAD_KEY + " = {");
        assertThat(workloadAt)
                .as("%s must declare a %s workload, or this test is reading the wrong file",
                        environment, WORKLOAD_KEY)
                .isNotNegative();

        int listAt = root.indexOf("paths", workloadAt);
        int opensAt = root.indexOf('[', listAt);
        int closesAt = root.indexOf(']', opensAt);
        assertThat(opensAt).as("%s: the %s workload must carry a bracketed paths list",
                environment, WORKLOAD_KEY).isNotNegative();
        assertThat(closesAt).as("%s: the paths list must be closed", environment).isGreaterThan(opensAt);

        List<String> patterns = new ArrayList<>();
        Matcher quoted = Pattern.compile("\"([^\"]+)\"")
                .matcher(root.substring(opensAt + 1, closesAt));
        while (quoted.find()) {
            patterns.add(quoted.group(1));
        }
        assertThat(patterns)
                .as("%s: the %s workload must forward at least one pattern", environment, WORKLOAD_KEY)
                .isNotEmpty();
        return patterns;
    }

    /**
     * Reports whether a listener path pattern matches a concrete request path.
     *
     * <p>Assumptions: the two wildcards are the listener's own and are implemented by translating the
     * pattern to a regular expression rather than by walking it, so that a pattern carrying several
     * wildcards needs no backtracking logic here. Every other character is quoted, which matters because a
     * path pattern legitimately contains characters a regular expression reads as operators.</p>
     *
     * @param pattern the listener path pattern, in which {@code *} matches zero or more characters
     *     including the separator and {@code ?} matches exactly one character; must not be {@code null}
     * @param path the concrete request path to test; must not be {@code null}
     * @return {@code true} when the listener would forward {@code path} on {@code pattern}
     */
    private static boolean matchesListenerPattern(String pattern, String path) {
        StringBuilder expression = new StringBuilder(pattern.length() * 2);
        StringBuilder literal = new StringBuilder();
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '*' || character == '?') {
                if (literal.length() > 0) {
                    expression.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                expression.append(character == '*' ? ".*" : ".");
                continue;
            }
            literal.append(character);
        }
        if (literal.length() > 0) {
            expression.append(Pattern.quote(literal.toString()));
        }
        return path.matches(expression.toString());
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

    /**
     * Locates the repository root by walking upward from the working directory.
     *
     * @return the first ancestor directory holding both {@code infra/} and {@code services/}; never
     *     {@code null}
     * @throws IllegalStateException if no ancestor holds both, which means this test is running outside a
     *     checkout of this repository
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
}
