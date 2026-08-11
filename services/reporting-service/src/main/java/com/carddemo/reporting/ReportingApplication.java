package com.carddemo.reporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the reporting and statement bounded context.
 *
 * <p>This type is the repackaging target the module's build plugin writes into the executable archive,
 * and the class the container image's entry command launches. It carries the annotation that builds the
 * application context, it exposes the process entry point, and it holds no configuration of its own.</p>
 *
 * <p><strong>What this context migrates.</strong> Four reference programs contribute to it, and each is
 * read as the specification rather than reinterpreted. The 649-line {@code app/cbl/CORPT00C.cbl} drives
 * the online report request under transaction {@code CR00}: three presets, a six-component date entry, a
 * confirmation gate and a submission step. The 649-line {@code app/cbl/CBTRN03C.cbl} produces the
 * 133-column transaction report, joining four sources and totalling at three levels with a line-counter
 * scheme that breaks the output into report pages. The 924-line {@code app/cbl/CBSTM03A.CBL} produces
 * statements as 17 plain-text bands of 80 bytes and 34 markup fragments of 100 bytes. The 230-line
 * {@code app/cbl/CBSTM03B.CBL} is a dynamically called data-access dispatcher over four files, which
 * becomes four read-only repositories here. The normative layouts are the 73-line
 * {@code app/cpy/CVTRA07Y.cpy}, whose seven {@code 01} levels define the report record, and the 38-line
 * {@code app/cpy/COSTM01.CPY}, which re-keys the 350-byte statement input by card.</p>
 *
 * <p><strong>What this context owns.</strong> No tables. Its reads reach the writer through read-only
 * cross-schema views under a database role that holds select and nothing else, so no schema migration
 * resource exists anywhere in this module.</p>
 *
 * <p>Refactoring Rationale: the baseline submits an on-demand report by writing job control text into a
 * transient data queue that is mapped to an internal reader. That queue is declared across lines 499 to
 * 505 of {@code app/csd/CARDDEMO.CSD}: line 499 names it, line 501 gives it the reader destination and an
 * ignore-on-error option, and line 502 declares an 80-byte record. Nothing in the target submits text to
 * a job entry subsystem, so the mechanism is replaced rather than reproduced: this service starts a state
 * machine execution instead, and the substitution is recorded in
 * {@code docs/architecture/batch-orchestration.md}. The load library declared at line 491 of the same
 * resource is likewise replaced by this module's container image rather than emulated.</p>
 *
 * <p>Refactoring Rationale: the service holds no session state, which is what makes a horizontally scaled
 * entry point viable. The baseline is pseudo-conversational, so every screen turn ends its task and all
 * continuity travels in one 160-byte structure, the 47-line {@code app/cpy/COCOM01Y.cpy} shared by all
 * eighteen online programs. That single structure decomposes into four different target mechanisms and,
 * for one field, into none at all. Its navigation fields at lines 21 to 24 become client-side router
 * history, so no server-side next-program field exists. Its identity fields, {@code CDEMO-USER-ID} at
 * line 25 and {@code CDEMO-USER-TYPE} at line 26 with the two conditions at lines 27 and 28 that admit
 * only {@code 'A'} and {@code 'U'}, become validated token claims; that is a genuine improvement in
 * security rather than a transliteration, because the structure is storage the client echoes back and
 * could therefore assert its own user type, whereas a signed group claim cannot be asserted by the
 * client at all. Its selection context, the account identifier at line 38 and the card number at line 41,
 * becomes request path and query values, so every request is self-describing and independently
 * authorizable. Its turn discriminator {@code CDEMO-PGM-CONTEXT} at line 29, with the two conditions at
 * lines 30 and 31, has no target at all: a stateless handler that answers with a structured field-error
 * array has no first-turn-versus-repeat-turn distinction to draw. The consequence is that this service
 * needs neither sticky sessions nor a server-side session store.</p>
 *
 * <p>Alternatives Considered: the shared kernel's three Spring contributions are left to the kernel's own
 * auto-configuration and are deliberately not declared on this class. The kernel names exactly one class
 * in its {@code META-INF/spring} auto-configuration registration resource,
 * {@code com.carddemo.common.CardDemoCommonAutoConfiguration}, and that class already registers all
 * three: it imports {@code MetricsConfig} at its line 78, it declares the money module bean at its line
 * 227, and it declares the single {@code @RestControllerAdvice} error advice at its line 516. The money
 * module is registered a second and framework-neutral time by the kernel's
 * {@code META-INF/services/tools.jackson.databind.JacksonModule} provider file, which is what makes a
 * plain mapper built outside a context render an amount as a quoted string too. Two alternatives were
 * evaluated. The first was declaring those three types explicitly on this class. It was rejected on two
 * independent grounds: the error advice's sole constructor takes a clock, declared at line 638 of
 * {@code com.carddemo.common.error.GlobalExceptionHandler}, so there is no no-argument form to declare;
 * and a declaration written here would be unconditional, whereas the kernel confines that advice to a
 * nested configuration guarded across lines 492 to 499 by a servlet-web condition precisely so that a
 * non-web consumer never resolves servlet types. This class is the source for exactly such a non-web
 * context, built with the web type set to none at lines 274 and 275 of {@link ReportingTaskRunner}, so an
 * unconditional declaration here would register a request-scoped advice in a context that has no request
 * to advise, and its missing-bean condition would then make the kernel's guarded registration stand down
 * in favour of the unguarded one. The second alternative was widening the component-scan base package to
 * name the kernel root. It was rejected because it does not even solve the problem it appears to solve:
 * the money module carries no stereotype for a scan to match, so a scan would still not register it.</p>
 *
 * <p>Trade-offs: the component-scan root is left at this package and is never widened toward the parent
 * package or a bare prefix. Dependence on a sibling context is refused outright by the layering rules in
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}, and
 * a root wide enough to reach the kernel would also reach the seven sibling bounded-context roots and
 * attempt to instantiate their beans. The cost accepted is that the kernel's contributions arrive by a
 * mechanism this file's source does not show, so a reader has to follow one pointer to see them; what it
 * buys is that no service can omit a registration, which is the failure the kernel's registration
 * resource was introduced to end.</p>
 *
 * <p>Assumptions: the correlation filter and the group-claim role converter are registered elsewhere and
 * deliberately not here. Filter order is a property of the chain that declares the filter, so ordering
 * knowledge belongs with that chain: the kernel registers its correlation filter inside a second
 * servlet-only nested configuration, and the resource-server chain in
 * {@code services/reporting-service/src/main/java/com/carddemo/reporting/config/SecurityConfig.java}
 * wires the converter that turns group claims into authorities.</p>
 *
 * <p>Assumptions: nothing about the data source, the security chain, the published contract or the
 * management surface is configured on this class. Each of those concerns is owned by a dedicated class in
 * the sibling {@code config} package -- {@code DataSourceConfig}, {@code SecurityConfig},
 * {@code OpenApiConfig} and {@code StepFunctionsConfig} -- and the settings that select a port or expose
 * a health path live in this module's {@code application.yml} and its two profile documents. The module's
 * {@code Dockerfile} probes {@code /actuator/health} on port 8080, the same port the application serves,
 * and the load balancer's target group probes that identical path, so the two can never disagree about
 * liveness. Declaring any of it here would put a second authority beside the first.</p>
 *
 * <p>Assumptions: no active profile is selected in code, and none may be. Profiles arrive from the
 * container's command layer and from the module's {@code application-dev.yml} and
 * {@code application-prod.yml} documents, so selecting one here would defeat the parameterisation that
 * lets one image serve both environments.</p>
 *
 * <p>Alternatives Considered: no resilience library and no circuit breaker are present, and neither is a
 * code generator. Retry moved into the framework core in the release that arrives with this module's
 * parent, so the framework's own form -- an enabling annotation on a configuration class, with a maximum
 * retry count whose total attempts are one more than its value -- is available without a dependency; an
 * external resilience artifact would add one for a capability already on the path. A breaker is omitted
 * because the only synchronous hops here are inside the private network behind an internal load balancer
 * with bounded connect and read timeouts, so a breaker would add a failure mode without removing one. A
 * generated-accessor library is rejected because generated members cannot carry the documentation
 * user-specified Rule 1 (Explainability) requires, and Java records with explicit constructors give the
 * same brevity with documentable members. A generated-mapper library is rejected because its most recent
 * published release is a beta and, more decisively, the mapping in this context is not mechanical: it
 * drops padding, masks a primary account number to its last four digits, suppresses a card verification
 * value entirely and truncates several values at widths that each need a justification written at the
 * mapping site, which a generated mapper cannot hold.</p>
 *
 * <p>Alternatives Considered: the four decision labels in this file are written in the plural,
 * unparenthesised, colon-terminated form, with an ASCII hyphen-minus in the compromise label. The
 * alternative was the singular parenthesised register that the sibling declarative artifacts use, and it
 * was measured rather than guessed: the plural colon-terminated form appears in 5 files, the singular
 * appears far more widely in configuration and shell artifacts, and the house test-suite guide renders
 * the compromise label with a non-breaking hyphen, a closing parenthesis and no colon -- three
 * divergences in one token. The plural ASCII form is the one the rule itself declares, so this Java tree
 * uses it exclusively and no register in this file mixes the two. This paragraph exists so that the
 * divergence from the sibling artifacts reads as deliberate rather than as an oversight to be undone.</p>
 */
@SpringBootApplication
public final class ReportingApplication {

    /**
     * Prevents this entry point from being instantiated.
     *
     * <p>Refactoring Rationale: the type is declared final with a private constructor because nothing
     * constructs it and nothing extends it. The framework bootstraps from the class literal handed to
     * {@link SpringApplication#run(Class, String[])} rather than from an instance, and this class
     * declares no bean method at all, so there is no inter-bean reference for the framework to intercept
     * and no need for it to subclass this type. That replaces the reference model in which a program is
     * its own entry point and its working storage is implicit shared state: here the entry point is a
     * static method and every piece of state belongs to a bean the context owns.</p>
     *
     * <p>Assumptions: the shape above holds only while this class stays a primary source registered by
     * the framework itself, which is how both of this module's two start paths register it. A final class
     * discovered by component scanning instead could not be enhanced, because a scanned configuration
     * class is subclassed and a final class cannot be.</p>
     */
    private ReportingApplication() {
        // WHY : Assumptions: empty by design, and it must stay empty. The context is built from the
        //       annotation on this type rather than from an instance of it, so there is no state to
        //       establish here and any initialisation written here would never run.
    }

    /**
     * Starts either the reporting web service or one orchestrated reporting task.
     *
     * <p>Assumptions: this image has two start modes and this method chooses between them on the
     * arguments alone. The nightly state machine dispatches two of the task names and the on-demand
     * machine dispatches the third, all three against this module's own task definition, so a container
     * started with any of them must run that one task and stop. A single-mode entry point would instead
     * have started a web server and listened, and the orchestrator would have reported a timeout after
     * its whole ceiling elapsed rather than reporting what the run actually did.</p>
     *
     * <p>Trade-offs: task mode publishes the runner's status as the process status explicitly, and this
     * is the one place the file accepts a second exit path. Returning normally from this method would end
     * the process with zero whatever the task did, because that is the status of a process whose entry
     * method completes, and every gate the orchestrator places on these states tests for equality with
     * zero; a failed report would then present as a clean one and the chain would carry on. Allowing the
     * failure to propagate instead would fail the state correctly but would surface a stack trace in
     * place of the runner's stable error code and would abandon the single non-zero value the sibling
     * batch context publishes for the same tier. Service mode adds no such path: a context that fails to
     * start propagates and ends the process on its own, which is exactly what the container's restart
     * logic and its health probe depend on, so this method neither catches nor recovers.</p>
     *
     * <p>Assumptions: the arguments are forwarded unmodified in both modes. The framework's own
     * command-line property overrides and its profile activation are read from them, so inspecting,
     * reordering or filtering them here would silently break the container's command layer.</p>
     *
     * @param args the process command-line arguments as a {@code String} array, each entry one raw token
     *     the container's command layer passed. When any entry begins with the runner's job option the
     *     process runs that single task and ends with the runner's status; otherwise every entry is
     *     forwarded unmodified to {@link SpringApplication#run(Class, String[])}, so an operator can
     *     override any property on the command line exactly as on every other service in this repository.
     *     Must not be {@code null}
     */
    public static void main(final String[] args) {
        // WHY : Assumptions: the option token and the dispatch both belong to the runner, and this method
        //       only asks it which mode applies. Recognising the token here would put the command-line
        //       contract in two places, and the two could then disagree about what counts as a task.
        if (ReportingTaskRunner.isTaskInvocation(args)) {
            System.exit(ReportingTaskRunner.execute(args));
        }
        SpringApplication.run(ReportingApplication.class, args);
    }
}
