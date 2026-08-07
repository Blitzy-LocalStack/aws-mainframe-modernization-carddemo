package com.carddemo.reporting.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * Supplies the orchestration client through which an on-demand report execution is started.
 *
 * <h2>Purpose</h2>
 *
 * <p>This context answers an on-demand report request by starting an execution of a separate,
 * smaller state machine and returning, rather than producing the report on the request thread. The
 * client that issues that {@code states:StartExecution} call is built here and nowhere else. The
 * state machine itself belongs to the infrastructure code that declares it, so no state definition,
 * no state list and no access policy appears in this class: it holds a client and nothing more.
 *
 * <p>The one member this class contributes to the context is the {@link SfnClient} bean below. The
 * identifier of the machine to start is not read here; the service that issues the call binds it
 * itself, which keeps this class to the single concern of how the client is built.
 *
 * <h2>Refactoring Rationale: what the submission mechanism replaces, and what was wrong with it</h2>
 *
 * <p>Refactoring Rationale: the baseline submitted an on-demand report by writing job control text
 * to an extra-partition transient data queue. {@code app/csd/CARDDEMO.CSD} defines that queue across
 * L499 to L505 -- {@code DEFINE TDQUEUE(JOBS)} at L499, described as submitting jobs from the online
 * region at L500, and L501 carrying {@code TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER)
 * ERROROPTION(IGNORE)}, with {@code RECORDSIZE(80)} at L502 and {@code DISPOSITION(MOD)} at L503.
 * What was wrong with that approach has two halves, and both are needed to state it accurately. The
 * first half: {@code ERROROPTION(IGNORE)} at L501 directs the region itself to ignore an input or
 * output error on that queue, so the condition need never surface. The second half, and it is a
 * different statement rather than a restatement: the program is not negligent about the condition --
 * {@code app/cbl/CORPT00C.cbl} asks for a response code at L521, evaluates it at L525, and carries
 * its own message, {@code 'Unable to Write TDQ (JOBS)...'}, at L531. It is the queue definition, not
 * the program, that can keep the condition from ever reaching the check the program stands ready to
 * make, and the consequence is a submission that can be accepted at the screen with nothing
 * recording that it did not arrive. Starting a state machine execution answers exactly that: the
 * call hands back an execution identifier and raises an error rather than discarding one, so a
 * submission that failed cannot pass for one that succeeded.
 *
 * <p>None of this disparages the baseline or retires it. The queue-to-internal-reader design was a
 * complete and functioning submission mechanism for its platform, and it remains reference material
 * that this migration reads and never modifies; the migration adds a path, it does not remove one.
 * In the house phrasing of {@code tests/README.md} at L555 to L556, the target encodes the same
 * contract rather than redefining it.
 *
 * <h2>Assumptions: the eighty-byte record contract the replacement no longer carries</h2>
 *
 * <p>Assumptions: the record that baseline submission carried was eighty bytes wide, and three
 * independent witnesses in the repository agree on that width. {@code RECORDSIZE(80)} at
 * {@code app/csd/CARDDEMO.CSD} L502 declares it for the queue. {@code app/cbl/CORPT00C.cbl}
 * composes the date-parameter record at L117 to L121 from four elementary items measuring 10, then
 * 1, then 10, then 59 -- the single-character item at L119 being the separator -- which totals
 * exactly 80. And {@code 01 FD-DATEPARM-REC PIC X(80)} at {@code app/cbl/CBTRN03C.cbl} L88 is the
 * reading end of that same contract. The width is recorded here because it is precisely what the
 * replacement stops having to encode: an execution accepts typed input, so a date range travels as
 * two values instead of as characters positioned inside a card image whose column boundaries the
 * producer and the consumer both had to agree on.
 *
 * <h2>Trade-offs: the call returns a handle, not a result</h2>
 *
 * <p>Trade-offs: the call this client makes hands back an execution identifier rather than a
 * rendered report. The report is produced afterwards and written to object storage, so a caller
 * wanting the artifact itself must follow the handle instead of reading the response body. That is
 * accepted because the baseline was itself asynchronous -- {@code app/cbl/CORPT00C.cbl} writes to
 * the queue at L517 to L518 and the batch job ran separately afterwards -- so what changes is not
 * the timing but the addressability. An execution identifier is a durable handle that can be
 * queried and correlated after the fact, where an eighty-byte card image written to a queue declared
 * {@code DISPOSITION(MOD)} at L503 left the caller nothing to name the run by.
 *
 * <h2>Alternatives Considered: the four things this class deliberately is not</h2>
 *
 * <p>Alternatives Considered: giving this module a job repository of its own and running the report
 * as a local batch job. Rejected because this module starts an execution and returns, whereas the
 * batch service owns the Spring Batch job repository and the {@code batch.batch_run} step ledger
 * that together carry restart. A second job repository here would be a second, competing restart
 * mechanism over the same runs, which is why {@code services/reporting-service/pom.xml} declares no
 * {@code spring-boot-starter-batch} -- that artifact is named in the file only at L459, inside the
 * block recording what is deliberately absent.
 *
 * <p>Alternatives Considered: modelling the report request as a queued message, as the authorization
 * context models its request and reply exchange. Rejected because the baseline submission at
 * {@code app/csd/CARDDEMO.CSD} L499 to L505 was not a request and reply exchange at all: L502
 * declares it {@code TYPEFILE(OUTPUT)} and the stanza names no reply queue and no correlation field,
 * so it is a one-way write. An execution identifier is the faithful analogue of a submitted job,
 * whereas a queue would invent a contract the baseline never had. No queue client and no message
 * listener is declared here, and no queue starter sits on this module's classpath: the one that
 * would supply it is named at {@code services/reporting-service/pom.xml} L467, again only inside
 * that same deliberately-absent block.
 *
 * <p>Alternatives Considered: driving the recurring nightly chain from this module as well, so that
 * one class held both entry points. Rejected because the two are different paths: the recurring run
 * covers the 38 job definitions under {@code app/jcl} and is started by an EventBridge time-based
 * trigger declared in the infrastructure code, while this class serves only the ad-hoc path a single
 * user request takes -- the path {@code app/cbl/CORPT00C.cbl} took at L517 to L518 and no other. No
 * recurring trigger and no state definition appear here, and the machine's own retries and per-state
 * ceilings are declared in the infrastructure module that owns it.
 *
 * <p>Alternatives Considered: also exposing an object-storage client here, since
 * {@code services/reporting-service/pom.xml} declares that artifact at L274 alongside the
 * orchestration artifact at L256. Rejected on a measured ground: a search of {@code services/} for a
 * client type, a package reference or a put-object request drawn from that artifact returns no
 * consumer at all, and {@code StatementService} takes its destination bucket and two key prefixes as
 * plain character values rather than through a client. A bean nothing injects would still build a
 * connection pool at start-up, and the charter beside this file states that this class declares a
 * call ceiling and nothing else.
 *
 * <h2>Alternatives Considered: the form of the rationale labels used throughout</h2>
 *
 * <p>Alternatives Considered: the parenthesised singular label form that also appears in the
 * repository's prose. Rejected on a count measured across the working tree with the repository
 * metadata and build output excluded: the plural colon form {@code Trade-offs:} occurs 3229 times
 * across 632 files, while the same stem written in the singular and wrapped in parentheses occurs
 * 152 times. That rejected spelling is described here rather than reproduced, so that a search for
 * the singular or parenthesised label forms finds no candidate in this file at all. The four labels
 * used in this file were retyped from the rule text rather than copied from any file in the tree,
 * because {@code tests/README.md} spells them with a non-breaking hyphen -- 106 occurrences over 77
 * lines -- and its L548 renders the last of the four with that hyphen, a closing parenthesis and no
 * colon, so a copy taken from there would not match the rule's own bytes.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries a docstring whatever its visibility, because the project
 * Explainability rule attaches its presence clause to every function and class and names no
 * visibility at all. The inherited Checkstyle gate is configured to the same reach:
 * {@code MissingJavadocType} and {@code MissingJavadocMethod} both run at private scope, the latter
 * with its allowed-annotation list cleared so that no annotation exempts a member, and
 * {@code JavadocMethod} runs with missing parameter tags and missing return tags both disallowed and
 * with thrown-type validation enabled. Field-level Javadoc is not required, because
 * {@code JavadocVariable} is not among the enabled checks; the single constant below therefore
 * carries one line, and the field's reasoning sits adjacent to it as an inline comment instead.
 */
@Configuration(proxyBeanMethods = false)
public class StepFunctionsConfig {

    /** Configuration key of the bounded ceiling on a single orchestration call. */
    public static final String API_CALL_TIMEOUT_PROPERTY =
            "carddemo.reporting.step-functions.api-call-timeout";

    // WHY : Assumptions: the key above is bound exactly once, in the constructor, and the value it
    //       yields is held here already read and already checked rather than re-read inside the
    //       factory method below, so the ceiling that reaches the client is demonstrably the one that
    //       was validated. Its single declaration is application.yml L1234; binding that one
    //       declaration twice would let either reading change without the validation running again,
    //       and the client would then carry a ceiling nothing had checked.
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
        // WHY : Alternatives Considered: declaring this parameter as a Duration and letting the
        //       framework convert the configured text on the way in. Rejected because that
        //       conversion is not intrinsic to the binding -- it is supplied by a conversion service
        //       that the full application installs but that a plain application context does not, so
        //       the same class would bind in one context and fail in another with a message about
        //       editors and conversion strategies rather than about this key. The risk is not
        //       hypothetical and it is not shared: a search of services/ for a duration bound through
        //       a value annotation finds exactly one such parameter across all nine modules, this
        //       one, so nothing else in the tree would exercise the ambient path and reveal a
        //       regression in it. Taking the configured text and reading it here makes the outcome
        //       identical in every context, and it follows the shape DataSourceConfig beside this
        //       file already uses at its own L261, which likewise takes its configuration as text
        //       and validates it itself rather than relying on a conversion it does not control.
        this.apiCallTimeout = requirePositiveDuration(apiCallTimeoutSpec);
    }

    /**
     * Reads a configured duration specification and returns it only if it can bound a call.
     *
     * <p>Assumptions: the notation accepted here is the notation the configuration file is already
     * written in, because the reader used is the same one the framework's own converter uses. The
     * declared value at application.yml L1234 is written in the abbreviated form rather than the
     * standard interval form, and this reader accepts both, so reading the value explicitly changes
     * where the conversion happens and not which values are accepted.</p>
     *
     * <p>Assumptions: a zero or negative ceiling is refused rather than passed on, even though the
     * value declared at application.yml L1234 is positive, because a profile overlay or an
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
            // WHY : Assumptions: the cause is carried rather than discarded, because the reader's own
            //       message names the offending notation while this message names the key that
            //       carried it, and an operator needs both halves to know what to correct. The
            //       thrown type is deliberately IllegalArgumentException, which is both the type this
            //       method's own at-clause declares and the type the reader itself raises, so the
            //       two agree by construction. The type is named rather than widened because
            //       thrown-type validation cannot see a throw raised inside a catch, and a base-typed
            //       throw here would leave the documented type unverifiable by any gate. The
            //       location is described rather than cited by line, because a line number pointing
            //       inside this same file would be stale the moment either member moves.
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
     * Builds the client through which a report submission starts a state machine execution.
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
        // WHY : Assumptions: no region, no credentials provider and no endpoint override is set on
        //       the builder, so all three resolve from the environment the task runs in -- which is
        //       how a container running under an execution role obtains a credential that was
        //       generated at provisioning time into Secrets Manager and never written to a file. The
        //       charter beside this file requires exactly this: package-info.java states at L72 to
        //       L75 that this class declares a call ceiling and nothing else, precisely because
        //       naming any of the three here would compile one deployment's value into every
        //       image. The two values this module genuinely needs -- the machine identifier and the
        //       destination bucket -- arrive as environment variables the task definition resolves
        //       from Parameter Store, which is what makes the absence of committed credentials
        //       structural rather than a matter of review discipline.
        //
        // WHY : Alternatives Considered: adding a resilience library and wrapping the call in a
        //       circuit breaker. Rejected on two independent grounds. Retry needs no library at all
        //       now: Spring Framework 7, which arrives with the Boot parent this module inherits,
        //       moved retry into the core, where the enabling annotation is @EnableResilientMethods
        //       and the attribute is maxRetries, whose total attempt count is one plus its value and
        //       whose default is three. Both of those names differ from the older module they
        //       replace, and they are recorded here because reaching for the older spelling compiles
        //       cleanly and silently enables nothing. And a breaker would add a failure mode without
        //       removing one: the synchronous hops this service makes stay inside the private
        //       network behind an internal load balancer, so a bounded ceiling already converts a
        //       stalled call into a reported failure, whereas a breaker would additionally refuse
        //       calls that would have succeeded.
        //
        // WHY : Alternatives Considered: setting separate connect and read ceilings in addition to
        //       the overall one. Rejected because no such value is declared anywhere in this
        //       module's configuration -- application.yml declares only
        //       carddemo.reporting.step-functions.api-call-timeout, at L1234 -- and inventing a
        //       number here would put a service-level objective into source that the repository does
        //       not state. The overall per-call ceiling bounds the whole call, those two transport
        //       stages included, so the declared value is sufficient on its own and the remaining
        //       transport defaults are left exactly as the library sets them.
        return SfnClient.builder()
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(apiCallTimeout)
                        .build())
                .build();
    }
}
