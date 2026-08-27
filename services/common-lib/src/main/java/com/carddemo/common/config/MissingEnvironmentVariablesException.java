package com.carddemo.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised at startup when the active configuration reads environment variables the platform did not
 * supply, naming every one of them and the property each feeds.
 *
 * <p>Assumptions: the failure is unrecoverable by construction, so this carries no remediation
 * behaviour beyond its message. A service whose datasource URL is absent cannot degrade into a
 * read-only mode or retry until the variable appears -- the value is supplied by the deployment
 * before the process starts or not at all -- so the only useful action is to stop with a message an
 * operator can act on without reading source.</p>
 *
 * <p>Alternatives Considered: letting the binder fail on its own, which is what happened before this
 * type existed. A missing {@code SPRING_DATASOURCE_URL} was bound as the LITERAL text
 * {@code ${SPRING_DATASOURCE_URL}} -- Spring Boot's placeholder resolver ignores an unresolvable
 * placeholder rather than raising -- and the process then died several layers downstream with
 * {@code 'url' must start with "jdbc"} from the driver-class lookup, naming no environment variable
 * at all. Rejected because the diagnosis a deployment needs is the NAME of the variable it forgot,
 * and no downstream failure carries it.</p>
 *
 * <p>Alternatives Considered: {@code org.springframework.core.env.MissingRequiredPropertiesException},
 * reached by declaring the keys through {@code ConfigurableEnvironment.setRequiredProperties}.
 * Rejected because that mechanism tests whether a PROPERTY is present, and every property here IS
 * present: what is absent is the environment variable its value refers to, which that check cannot
 * see.</p>
 *
 * <p>Trade-offs: an unchecked exception, extending {@link IllegalStateException} rather than
 * defining a new checked type. The cost is that no compiler forces a caller to acknowledge it; that
 * is accepted because the only caller is a Spring Boot environment post-processor, whose contract
 * declares no checked exception, and because the intended handler is the framework's own run-failure
 * reporter rather than any code of this project's.</p>
 */
public class MissingEnvironmentVariablesException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * The variables that could not be resolved, each mapped to the properties that read it.
     *
     * <p>Assumptions: held as a copy taken in the constructor rather than as the caller's map, so
     * that the diagnosis a test or a failure analyser reads back cannot be mutated after the
     * exception is raised.</p>
     */
    private final Map<String, String> missingVariables;

    /**
     * Creates the failure from the variables the environment could not resolve.
     *
     * @param missingVariables each unresolved environment variable name mapped to the
     *     comma-separated configuration properties whose values read it, in the order the message
     *     should name them; must be neither {@code null} nor empty, because an exception reporting
     *     nothing missing would be raised where the startup contract holds
     */
    public MissingEnvironmentVariablesException(Map<String, String> missingVariables) {
        super(message(missingVariables));
        this.missingVariables = new LinkedHashMap<>(missingVariables);
    }

    /**
     * Returns the variables this failure reports, mapped to the properties that read each one.
     *
     * @return an unmodifiable view of the unresolved variables; never {@code null} and never empty
     */
    public Map<String, String> getMissingVariables() {
        return Map.copyOf(this.missingVariables);
    }

    /**
     * Renders the one sentence an operator reads when a deployment is incomplete.
     *
     * <p>Assumptions: the whole diagnosis is ONE log record rather than a line per variable. A
     * container's log is read through a collector that treats each record separately, so a
     * multi-record failure is routinely seen with its lines interleaved or truncated to the first;
     * naming every variable in one record makes the count and the list arrive together.</p>
     *
     * <p>Trade-offs: the property that reads a variable is named in parentheses beside it, which
     * makes the sentence longer than the variable list alone. The cost is accepted because the two
     * questions an operator asks are which variable is missing and what breaks without it, and the
     * property answers the second without a source lookup.</p>
     *
     * @param missingVariables each unresolved variable mapped to the properties that read it
     * @return the exception message; never {@code null}
     * @throws IllegalArgumentException if no variable is given, because the caller would then be
     *     reporting a contract that holds
     */
    private static String message(Map<String, String> missingVariables) {
        if (missingVariables == null || missingVariables.isEmpty()) {
            throw new IllegalArgumentException(
                    "a missing-environment-variable failure must name at least one variable");
        }
        StringBuilder rendered = new StringBuilder("CardDemo cannot start: ")
                .append(missingVariables.size())
                .append(missingVariables.size() == 1
                        ? " environment variable the active configuration reads is not set: "
                        : " environment variables the active configuration reads are not set: ");
        String separator = "";
        for (Map.Entry<String, String> variable : missingVariables.entrySet()) {
            rendered.append(separator)
                    .append(variable.getKey())
                    .append(" (")
                    .append(variable.getValue())
                    .append(')');
            separator = ", ";
        }
        return rendered.append(". Set each variable in the deployment's task definition or shell")
                .append(" environment, or supply the property named beside it directly; each name")
                .append(" is the placeholder the configuration resolves and the parenthesised")
                .append(" property is where that placeholder is read.")
                .toString();
    }
}
