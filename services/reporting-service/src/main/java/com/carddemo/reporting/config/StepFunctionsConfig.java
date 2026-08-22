package com.carddemo.reporting.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * Supplies the orchestration client through which an on-demand report execution is started and its
 * outcome is read back.
 *
 * <h2>Purpose</h2>
 *
 * <p>This context answers an on-demand report request by starting an execution of a separate,
 * smaller state machine and returning, rather than producing the report on the request thread. The
 * client that issues that {@code states:StartExecution} call is built here and nowhere else, and the
 * one member this class contributes to the context is the {@link SfnClient} bean below. The state
 * machine itself, its retries and its per-state ceilings belong to the infrastructure code that
 * declares it, so no state definition, no state list and no access policy appears here. The
 * identifier of the machine to start is not read here either; the service that issues the call binds
 * it itself, which keeps this class to the single concern of how the client is built.
 *
 * <p>⚠️ Refactoring Rationale: this client is NOT submission-only, and this class described it as
 * though it were. A review found the deployed task role granting {@code states:StartExecution} alone
 * while the status half of the lifecycle -- {@code ReportExecutionService.describeExecution}, which
 * serves the published status operation -- issues {@code states:DescribeExecution} through this same
 * bean. Two actions therefore have to be granted, and they take DIFFERENT resources: a start names the
 * state machine ARN while a describe names an EXECUTION ARN, so a policy scoped to the machine alone
 * refuses every status read at run time and at no earlier point. The service composes that execution
 * ARN by substituting the execution segment into the configured machine ARN and appending the caller's
 * name, which is what makes {@code arn:<partition>:states:<region>:<account>:execution:<machine
 * name>:*} the exact resource the grant needs -- one machine's executions and no other's.
 *
 * <h2>What the client replaces</h2>
 *
 * <p>Assumptions: the baseline submitted an on-demand report by writing an eighty-byte job-control
 * record to an extra-partition transient data queue -- {@code app/csd/CARDDEMO.CSD} defines it across
 * L499 to L505, with {@code TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER) ERROROPTION(IGNORE)} at L501,
 * {@code RECORDSIZE(80)} at L502 and {@code DISPOSITION(MOD)} at L503. Two properties of that
 * mechanism decide the shape of the replacement. {@code ERROROPTION(IGNORE)} lets an input or output
 * error on the queue go unreported even though {@code app/cbl/CORPT00C.cbl} asks for a response code
 * at L521, evaluates it at L525 and carries its own message at L531, so a submission can be accepted
 * at the screen with nothing recording that it did not arrive. And {@code DISPOSITION(MOD)} left the
 * caller nothing to name the run by. Starting a state machine execution answers both: the call hands
 * back an execution identifier and raises an error rather than discarding one. The queue-to-internal-
 * reader design remains reference material this migration reads and never modifies; the migration
 * adds a path rather than removing one.
 *
 * <p>Assumptions: the eighty-byte width is a contract the replacement no longer has to encode.
 * {@code app/cbl/CORPT00C.cbl} composes the date-parameter record at L117 to L121 from items
 * measuring 10, 1, 10 and 59, and {@code 01 FD-DATEPARM-REC PIC X(80)} at
 * {@code app/cbl/CBTRN03C.cbl} L88 is its reading end. An execution accepts typed input, so a date
 * range travels as two values instead of as characters positioned inside a card image whose column
 * boundaries the producer and the consumer both had to agree on.
 *
 * <p>Trade-offs: the call hands back an execution identifier rather than a rendered report, so a
 * caller wanting the artifact must follow the handle into object storage. The baseline was itself
 * asynchronous -- {@code app/cbl/CORPT00C.cbl} writes to the queue at L517 to L518 and the batch job
 * ran separately afterwards -- so what changes is addressability rather than timing.
 *
 * <h2>What this class is not</h2>
 *
 * <p>Alternatives Considered: giving this module a job repository of its own and running the report
 * as a local batch job. Rejected because the batch service owns the Spring Batch job repository and
 * the {@code batch.batch_run} step ledger that together carry restart, and a second job repository
 * here would be a second, competing restart mechanism over the same runs. This module's POM declares
 * no batch starter for that reason.
 *
 * <p>Alternatives Considered: modelling the report request as a queued message, as the authorization
 * context models its request and reply exchange. Rejected because the baseline submission is a
 * one-way write: {@code app/csd/CARDDEMO.CSD} L502 declares it {@code TYPEFILE(OUTPUT)} and the
 * stanza names no reply queue and no correlation field. An execution identifier is the faithful
 * analogue of a submitted job, whereas a queue would invent a contract the baseline never had. No
 * queue client and no message listener is declared here, and no queue starter sits on this module's
 * classpath.
 *
 * <p>Alternatives Considered: driving the recurring nightly chain from here as well, so that one
 * class held both entry points. Rejected because the two are different paths: the recurring run
 * covers the job definitions under {@code app/jcl} and is started by an EventBridge time-based
 * trigger declared in the infrastructure code, while this class serves only the ad-hoc path
 * {@code app/cbl/CORPT00C.cbl} took at L517 to L518.
 *
 * <p>⚠️ Refactoring Rationale: this class used to record a rejected alternative -- exposing an
 * object-storage client here too -- on the measured ground that nothing under {@code services/}
 * consumed one. That ground no longer holds and the alternative is no longer open: the artifact
 * writers, {@code task/GenerationKeys} and {@code service/ArtifactStore} all take an
 * {@code S3Client}, and {@code ObjectStoreConfig} beside this file supplies it. The two clients stay
 * in two classes for a reason that outlives the measurement, so it is recorded here rather than
 * dropped: they are configured differently on purpose -- this one carries a per-call ceiling because
 * an orchestration call is a small control-plane request, while the object-store client deliberately
 * carries none because an upload part's duration is a function of the object and the link. One class
 * holding both would have to explain why one of its two beans is bounded and the other is not.
 */
@Configuration(proxyBeanMethods = false)
public class StepFunctionsConfig {

    /** Configuration key of the bounded ceiling on a single orchestration call. */
    public static final String API_CALL_TIMEOUT_PROPERTY =
            "carddemo.reporting.step-functions.api-call-timeout";

    // Assumptions: the key above is bound exactly once, in the constructor, and the value it
    // yields is held here already read and already checked rather than re-read inside the
    // factory method below, so the ceiling that reaches the client is demonstrably the one that
    // was validated. Its single declaration is application.yml L1252; binding that one
    // declaration twice would let either reading change without the validation running again,
    // and the client would then carry a ceiling nothing had checked.
    private final Duration apiCallTimeout;

    /**
     * Records the ceiling a single orchestration call may take, refusing a value that cannot bound
     * one.
     *
     * <p>Assumptions: the ceiling arrives from configuration and is never named in source.
     * {@code services/reporting-service/src/main/resources/application.yml} declares
     * {@value #API_CALL_TIMEOUT_PROPERTY} at L1234, and neither the development nor the production
     * overlay overrides it, so one declared value governs every profile. Binding a declared key is
     * what keeps this class free of an invented service-level objective: the repository states the
     * number, and this file only consumes it.</p>
     *
     * @param apiCallTimeoutSpec the configured ceiling on a single orchestration call, as the
     *     duration specification held at {@value #API_CALL_TIMEOUT_PROPERTY}; read in the same
     *     notation the configuration file already uses, and required to denote a positive duration
     * @throws IllegalArgumentException if the configured value cannot be read as a duration, or
     *     denotes a duration that is zero or negative; the message names the configuration key in
     *     both cases
     */
    public StepFunctionsConfig(
            @Value("${" + API_CALL_TIMEOUT_PROPERTY + "}") String apiCallTimeoutSpec) {
        // Alternatives Considered: declaring this parameter as a Duration and letting the
        // framework convert the configured text on the way in. Rejected because that
        // conversion is not intrinsic to the binding -- it is supplied by a conversion service
        // that the full application installs but that a plain application context does not, so
        // the same class would bind in one context and fail in another with a message about
        // editors and conversion strategies rather than about this key. The risk is not
        // hypothetical and it is not shared: a search of services/ for a duration bound through
        // a value annotation finds exactly one such parameter across all nine modules, this
        // one, so nothing else in the tree would exercise the ambient path and reveal a
        // regression in it. Taking the configured text and reading it here makes the outcome
        // identical in every context, and it follows the shape DataSourceConfig beside this
        // file already uses at its own L261, which likewise takes its configuration as text
        // and validates it itself rather than relying on a conversion it does not control.
        this.apiCallTimeout = requirePositiveDuration(apiCallTimeoutSpec);
    }

    /**
     * Reads a configured duration specification and returns it only if it can bound a call.
     *
     * <p>Assumptions: the notation accepted here is the notation the configuration file is already
     * written in, because the reader used is the same one the framework's own converter uses. The
     * declared value at application.yml L1252 is written in the abbreviated form rather than the
     * standard interval form, and this reader accepts both, so reading the value explicitly changes
     * where the conversion happens and not which values are accepted.</p>
     *
     * <p>Assumptions: a zero or negative ceiling is refused rather than passed on, even though the
     * value declared at application.yml L1252 is positive, because a profile overlay or an
     * environment override could supply another. The builder further down accepts a non-positive
     * ceiling and then makes every call fail the instant it is issued, which reads in a log as an
     * orchestration outage rather than as a configuration mistake. Refusing it while the context is
     * still starting names the offending key instead, and the refusal is an
     * {@link IllegalArgumentException} thrown directly rather than wrapped or base-typed, so the
     * type a reader sees declared is the type that actually surfaces.</p>
     *
     * @param spec the configured duration specification, as held at
     *     {@value #API_CALL_TIMEOUT_PROPERTY}
     * @return the ceiling the specification denotes, guaranteed strictly positive
     * @throws IllegalArgumentException if the specification cannot be read as a duration, or denotes
     *     a duration that is zero or negative
     */
    private static Duration requirePositiveDuration(String spec) {
        Duration parsed;
        try {
            parsed = DurationStyle.detectAndParse(spec);
        } catch (IllegalArgumentException unreadable) {
            // Assumptions: the cause is carried rather than discarded, because the reader's own
            // message names the offending notation while this message names the key that
            // carried it, and an operator needs both halves to know what to correct. The
            // thrown type is deliberately IllegalArgumentException, which is both the type this
            // method's own at-clause declares and the type the reader itself raises, so the
            // two agree by construction. The type is named rather than widened because
            // thrown-type validation cannot see a throw raised inside a catch, and a base-typed
            // throw here would leave the documented type unverifiable by any gate. The
            // location is described rather than cited by line, because a line number pointing
            // inside this same file would be stale the moment either member moves.
            throw new IllegalArgumentException(API_CALL_TIMEOUT_PROPERTY
                    + " is not a readable duration: " + spec, unreadable);
        }
        if (parsed.isZero() || parsed.isNegative()) {
            throw new IllegalArgumentException(API_CALL_TIMEOUT_PROPERTY
                    + " must be a positive duration but was " + parsed);
        }
        return parsed;
    }

    /**
     * Builds the client through which a report submission starts a state machine execution and a
     * status read asks what became of one.
     *
     * <p>Assumptions: one shared instance is correct here. The client is documented as thread-safe
     * and is built to be reused, so a singleton bean is what the library expects, and the context
     * needs exactly one: the sole injection point in this module takes it as a single constructor
     * collaborator at {@code service/ReportExecutionService.java} L145. Building one per request
     * would open a connection pool per request, which is the very cost a shared client exists to
     * avoid.</p>
     *
     * @return the orchestration client, carrying the validated per-call ceiling and resolving its
     *     region and its credentials from the environment the task runs in; never {@code null}
     * @throws software.amazon.awssdk.core.exception.SdkClientException if the builder cannot resolve
     *     a region from that environment, which is a start-up failure this method surfaces rather
     *     than conceals
     */
    @Bean
    public SfnClient sfnClient() {
        // Assumptions: no region, no credentials provider and no endpoint override is set on the
        //   builder, so all three resolve through the SDK's default chains from the environment the
        //   task runs in. For credentials that chain reaches the CONTAINER credentials provider,
        //   which reads the address ECS publishes in AWS_CONTAINER_CREDENTIALS_RELATIVE_URI and
        //   receives short-lived, automatically rotated credentials for this service's TASK role --
        //   aws_iam_role.task, wired as task_role_arn in infra/modules/ecs-service. Nothing is read
        //   from a file and nothing from a secret store, which is why no credential can be
        //   committed: there is none to commit. A permission this client needs therefore belongs on
        //   the TASK role's policy and not on the execution role, which is the ECS agent's identity
        //   -- it pulls the image, writes the log stream and resolves the Parameter Store and
        //   Secrets Manager references behind the task definition, so a grant added there does not
        //   reach this client at all.
        //
        // Assumptions: the permissions this client needs are exactly TWO, and they are enumerated
        //   here because the class documentation above explains why they cannot be collapsed into
        //   one statement: states:StartExecution on the configured state machine ARN, and
        //   states:DescribeExecution on that machine's execution ARNs. Neither is inferable from
        //   this file at deploy time -- the policy lives in infra/envs -- so a reader adding a third
        //   call through this bean has to extend both this list and that policy, and the review that
        //   prompted this note found what the omission looks like: a status read that fails only in
        //   a deployed environment and only on the one operation nobody exercised locally.
        //
        // Assumptions: leaving all three unset is what keeps one image deployable in either
        //   environment; naming any of them here would compile one deployment's value into every
        //   image. The two values this module needs -- the machine identifier and the destination
        //   bucket -- arrive as environment variables the task definition resolves from Parameter
        //   Store.
        //
        // Alternatives Considered: adding a resilience library and wrapping the call in a
        // circuit breaker. Rejected on two independent grounds. Retry needs no library at all
        // now: Spring Framework 7, which arrives with the Boot parent this module inherits,
        // moved retry into the core, where the enabling annotation is @EnableResilientMethods
        // and the attribute is maxRetries, whose total attempt count is one plus its value and
        // whose default is three. Both of those names differ from the older module they
        // replace, and they are recorded here because reaching for the older spelling compiles
        // cleanly and silently enables nothing. And a breaker would add a failure mode without
        // removing one: the synchronous hops this service makes stay inside the private
        // network behind an internal load balancer, so a bounded ceiling already converts a
        // stalled call into a reported failure, whereas a breaker would additionally refuse
        // calls that would have succeeded.
        //
        // Alternatives Considered: setting separate connect and read ceilings in addition to
        // the overall one. Rejected because no such value is declared anywhere in this
        // module's configuration -- application.yml declares only
        // carddemo.reporting.step-functions.api-call-timeout, at L1234 -- and inventing a
        // number here would put a service-level objective into source that the repository does
        // not state. The overall per-call ceiling bounds the whole call, those two transport
        // stages included, so the declared value is sufficient on its own and the remaining
        // transport defaults are left exactly as the library sets them.
        return SfnClient.builder()
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(apiCallTimeout)
                        .build())
                .build();
    }
}
